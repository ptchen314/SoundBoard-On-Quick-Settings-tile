package com.soundboard.qstile.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 全 App 共用的播放器：同一時間只播一個音效。
 *
 * 兩種引擎：
 * - 低延遲（AudioTrack）：30 秒內的音效預先解碼成 PCM 放在記憶體，按下時直接送進低延遲音訊通道，
 *   不經過 MediaPlayer 的準備／緩衝流程，幾乎立刻出聲。
 * - MediaPlayer：較長的音效，或 PCM 還沒解碼好時使用（播放器預先 prepare 並保留）。
 *
 * 快速設定收起後系統可能凍結 App，回呼可能收不到，所以播放狀態以「預計結束時間」判斷。
 */
object SoundPlayer {
    private const val PCM_MAX_MS = 30_000L
    private const val PCM_CACHE_BYTES = 96L shl 20 // 96 MB
    private const val MAX_CACHED_MP = 8

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "pcm-decoder") }

    // ---------- 狀態 ----------
    private var currentId: String? = null
    private var endAtElapsed = 0L

    private val _playingId = MutableStateFlow<String?>(null)
    /** 給 App 畫面用。 */
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    /** 診斷：最近一次從「App 收到點擊」到「音訊實際開始輸出」的時間。 */
    data class Latency(val ms: Long, val source: String, val engine: String)
    private val _lastLatency = MutableStateFlow<Latency?>(null)
    val lastLatency: StateFlow<Latency?> = _lastLatency.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()
    fun addChangeListener(listener: () -> Unit) { listeners += listener }
    fun removeChangeListener(listener: () -> Unit) { listeners -= listener }

    // ---------- PCM 快取（key = 檔名，剪輯覆蓋後檔名會變，自然失效） ----------
    private val lock = Any()
    private val pcm = LinkedHashMap<String, DecodedAudio>(16, 0.75f, true)
    private var pcmBytes = 0L
    private val decoding = HashSet<String>()
    /** 太長或無法解碼的檔案，改用 MediaPlayer */
    private val notPcm = HashSet<String>()

    private fun DecodedAudio.bytes() = pcm.size * 2L

    // ---------- MediaPlayer 快取（key = soundId） ----------
    private val mpCache = object : LinkedHashMap<String, MediaPlayer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaPlayer>): Boolean {
            if (size > MAX_CACHED_MP && eldest.key != currentId) {
                eldest.value.runCatching { release() }
                mpFile.remove(eldest.key)
                return true
            }
            return false
        }
    }
    private val mpFile = HashMap<String, String>()

    // ---------- AudioTrack 播放 ----------
    private class TrackSession(val track: AudioTrack, val soundId: String) {
        @Volatile var cancelled = false
    }
    private var session: TrackSession? = null

    /** 依時間判斷這個音效是否還在播放。 */
    fun isPlaying(id: String): Boolean {
        expireIfNeeded()
        return currentId == id
    }

    /** 距離播放結束還有幾毫秒（沒在播放回傳 0）。 */
    fun remainingMs(): Long {
        expireIfNeeded()
        return if (currentId == null) 0 else (endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    /** App 內的播放鍵：播放中再按就停止。回傳 true 代表開始播放。 */
    fun toggle(sound: Sound, source: String = "App"): Boolean {
        if (isPlaying(sound.id)) {
            stop()
            return false
        }
        return play(sound, source)
    }

    /**
     * 從頭播放（正在播同一個音效就重新開始）。
     * @param clickNanos 收到點擊的時間（SystemClock.elapsedRealtimeNanos），用來量延遲
     */
    fun play(
        sound: Sound,
        source: String = "App",
        clickNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): Boolean {
        haltCurrent()
        currentId = null
        val audio = synchronized(lock) { pcm[sound.fileName] }
        val ok = if (audio != null) {
            startTrack(sound, audio, source, clickNanos) || startMediaPlayer(sound, source, clickNanos)
        } else {
            startMediaPlayer(sound, source, clickNanos).also { preload(sound) } // 下次就走低延遲
        }
        _playingId.value = currentId
        notifyChanged()
        return ok
    }

    // ---------- 預先載入 ----------

    /** 預先解碼成 PCM（背景執行緒）；不適合的檔案改為預先 prepare MediaPlayer。 */
    fun preload(sound: Sound) {
        val key = sound.fileName
        synchronized(lock) {
            if (pcm.containsKey(key) || key in decoding) return
            if (key in notPcm) {
                main.post { obtainMediaPlayer(sound) }
                return
            }
            decoding += key
        }
        decoder.execute {
            val file = SoundRepository.fileOf(sound)
            val audio = runCatching {
                val durMs = MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.absolutePath)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        release()
                    }
                }
                if (durMs == null || durMs > PCM_MAX_MS) null
                else AudioEdit.decode(file).takeIf { it.frames > 0 && it.channels in 1..2 }
            }.getOrNull()
            synchronized(lock) {
                decoding -= key
                if (audio == null) {
                    notPcm += key
                } else {
                    pcm[key] = audio
                    pcmBytes += audio.bytes()
                    val it = pcm.entries.iterator()
                    while (pcmBytes > PCM_CACHE_BYTES && it.hasNext()) {
                        val e = it.next()
                        if (e.key == key) continue
                        pcmBytes -= e.value.bytes()
                        it.remove()
                    }
                }
            }
            if (audio == null) main.post { obtainMediaPlayer(sound) }
        }
    }

    /** 預先載入所有啟用中磚位的音效。 */
    fun preloadSlots() {
        for (slot in 1..SoundRepository.slotCount.value) {
            SoundRepository.soundForSlot(slot)?.let { preload(it) }
        }
    }

    // ---------- 低延遲引擎 ----------

    private fun startTrack(sound: Sound, audio: DecodedAudio, source: String, clickNanos: Long): Boolean {
        val track = try {
            val mask = if (audio.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(audio.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(max(minBuf, 4096) * 2)
                .build()
        } catch (e: Exception) {
            return false
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return false
        }
        val total = audio.frames * audio.channels
        val s = TrackSession(track, sound.id)
        try {
            track.setVolume(volume())
            // 先塞滿緩衝區再 play，按下去立刻有聲音
            val primed = track.write(audio.pcm, 0, total, AudioTrack.WRITE_NON_BLOCKING).coerceAtLeast(0)
            track.play()
            session = s
            currentId = sound.id
            endAtElapsed = SystemClock.elapsedRealtime() + audio.durationMs + 150

            Thread({ writeRest(s, audio, primed, total) }, "pcm-writer").start()
            Thread({ measureTrackLatency(s, clickNanos, source) }, "latency").start()
            return true
        } catch (e: Exception) {
            runCatching { track.release() }
            if (session === s) session = null
            return false
        }
    }

    private fun writeRest(s: TrackSession, audio: DecodedAudio, start: Int, total: Int) {
        val track = s.track
        var off = start
        val chunk = 4096 * audio.channels
        try {
            while (!s.cancelled && off < total) {
                val n = track.write(audio.pcm, off, min(chunk, total - off))
                if (n <= 0) break
                off += n
            }
            // 等最後一段播完
            val deadline = SystemClock.elapsedRealtime() + audio.durationMs + 2000
            while (!s.cancelled && track.playbackHeadPosition < audio.frames &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(15)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            main.post {
                if (session === s) {
                    session = null
                    if (currentId == s.soundId) markStopped()
                }
            }
        }
    }

    private fun measureTrackLatency(s: TrackSession, clickNanos: Long, source: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!s.cancelled && SystemClock.elapsedRealtime() < deadline) {
            val head = runCatching { s.track.playbackHeadPosition }.getOrDefault(0)
            if (head > 0) {
                val ms = (SystemClock.elapsedRealtimeNanos() - clickNanos) / 1_000_000
                _lastLatency.value = Latency(ms, source, "低延遲")
                return
            }
            Thread.sleep(2)
        }
    }

    // ---------- MediaPlayer 引擎 ----------

    private fun startMediaPlayer(sound: Sound, source: String, clickNanos: Long): Boolean {
        fun attempt(): Boolean {
            val mp = obtainMediaPlayer(sound) ?: return false
            return runCatching {
                mp.setVolume(volume(), volume())
                mp.seekTo(0)
                mp.start()
                currentId = sound.id
                endAtElapsed = SystemClock.elapsedRealtime() + mp.duration.coerceAtLeast(0) + 150
                measureMpLatency(mp, sound.id, clickNanos, source)
            }.isSuccess
        }
        if (attempt()) return true
        discardMediaPlayer(sound.id) // 狀態可能壞掉，重建再試一次
        return attempt()
    }

    private fun measureMpLatency(mp: MediaPlayer, id: String, clickNanos: Long, source: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        val check = object : Runnable {
            override fun run() {
                if (currentId != id || mpCache[id] !== mp) return
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                if (pos > 0) {
                    val ms = (SystemClock.elapsedRealtimeNanos() - clickNanos) / 1_000_000
                    _lastLatency.value = Latency(ms, source, "MediaPlayer")
                } else if (SystemClock.elapsedRealtime() < deadline) {
                    main.postDelayed(this, 5)
                }
            }
        }
        main.post(check)
    }

    private fun obtainMediaPlayer(sound: Sound): MediaPlayer? {
        mpCache[sound.id]?.let { cached ->
            if (mpFile[sound.id] == sound.fileName) return cached
            discardMediaPlayer(sound.id)
        }
        val file = SoundRepository.fileOf(sound)
        if (!file.exists()) return null
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(audioAttributes())
                setDataSource(file.absolutePath)
                prepare()
            }
            mp.setOnCompletionListener { if (currentId == sound.id && session == null && mpCache[sound.id] === mp) markStopped() }
            mp.setOnErrorListener { _, _, _ ->
                if (mpCache[sound.id] === mp) discardMediaPlayer(sound.id)
                if (currentId == sound.id && session == null) markStopped()
                true
            }
            mpCache[sound.id] = mp
            mpFile[sound.id] = sound.fileName
            mp
        } catch (e: Exception) {
            null
        }
    }

    private fun discardMediaPlayer(id: String) {
        mpCache.remove(id)?.runCatching { release() }
        mpFile.remove(id)
    }

    // ---------- 共用 ----------

    /** 音效被刪除時呼叫。 */
    fun forget(id: String) {
        if (currentId == id) stop()
        discardMediaPlayer(id)
        SoundRepository.find(id)?.let { s ->
            synchronized(lock) {
                pcm.remove(s.fileName)?.let { pcmBytes -= it.bytes() }
                notPcm -= s.fileName
            }
        }
    }

    /** 換音訊通道等設定改變時，重建 MediaPlayer（AudioTrack 每次播放都會用最新設定）。 */
    fun releaseAll() {
        stop()
        mpCache.values.forEach { it.runCatching { release() } }
        mpCache.clear()
        mpFile.clear()
    }

    fun volume(): Float = SoundRepository.playbackVolume.value

    /** 調整 App 音量時即時套用。 */
    fun applyVolume() {
        mpCache.values.forEach { it.runCatching { setVolume(volume(), volume()) } }
        session?.track?.runCatching { setVolume(volume()) }
    }

    /** 依設定決定走媒體或鬧鐘音訊通道。 */
    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            if (SoundRepository.ignoreMediaMute.value) AudioAttributes.USAGE_ALARM
            else AudioAttributes.USAGE_MEDIA
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun stop() {
        haltCurrent()
        markStopped()
    }

    /** 停掉正在播的聲音（不改狀態、不通知）。 */
    private fun haltCurrent() {
        session?.let { s ->
            s.cancelled = true
            runCatching { s.track.pause() }
            runCatching { s.track.flush() }
            runCatching { s.track.stop() }
        }
        session = null
        currentId?.let { id -> mpCache[id]?.runCatching { if (isPlaying) pause() } }
    }

    private fun markStopped() {
        currentId = null
        _playingId.value = null
        notifyChanged()
    }

    private fun expireIfNeeded() {
        if (currentId != null && SystemClock.elapsedRealtime() >= endAtElapsed) stop()
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }
}
