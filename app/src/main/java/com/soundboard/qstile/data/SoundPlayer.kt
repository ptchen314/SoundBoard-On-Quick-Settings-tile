package com.soundboard.qstile.data

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全 App 共用的播放器：同一時間只播一個音效。
 *
 * - 每個音效的 MediaPlayer 會預先 prepare 並保留下來，按下時直接從頭播放，反應快、連按也不會漏。
 * - 快速設定收起後，系統可能凍結 App 程序，MediaPlayer 的「播完」回呼就收不到，
 *   所以播放狀態以「預計結束時間」判斷，不只依賴回呼。
 */
object SoundPlayer {
    private const val MAX_CACHED = 8

    private var currentId: String? = null
    private var endAtElapsed = 0L

    /** soundId -> 已 prepare 的播放器（依存取順序排列，超過上限就釋放最久沒用的） */
    private val cache = object : LinkedHashMap<String, MediaPlayer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaPlayer>): Boolean {
            if (size > MAX_CACHED && eldest.key != currentId) {
                eldest.value.runCatching { release() }
                cachedFile.remove(eldest.key)
                return true
            }
            return false
        }
    }
    /** 每個快取播放器對應的檔名；剪輯覆蓋後檔名會變，就要重新載入 */
    private val cachedFile = HashMap<String, String>()

    private val _playingId = MutableStateFlow<String?>(null)
    /** 給 App 畫面用（畫面在前景時回呼一定收得到）。 */
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners -= listener
    }

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
    fun toggle(sound: Sound): Boolean {
        if (isPlaying(sound.id)) {
            stop()
            return false
        }
        return play(sound)
    }

    /** 從頭播放（若正在播同一個音效，就重新從頭開始）。回傳 true 代表成功出聲。 */
    fun play(sound: Sound): Boolean {
        pauseCurrent()
        currentId = null
        var ok = startFromBeginning(sound)
        if (!ok) {
            // 播放器狀態可能壞掉：丟掉重建再試一次
            discard(sound.id)
            ok = startFromBeginning(sound)
        }
        _playingId.value = currentId
        notifyChanged()
        return ok
    }

    private fun startFromBeginning(sound: Sound): Boolean {
        val mp = obtain(sound) ?: return false
        return runCatching {
            mp.setVolume(volume(), volume())
            mp.seekTo(0)
            mp.start()
            currentId = sound.id
            endAtElapsed = SystemClock.elapsedRealtime() + mp.duration.coerceAtLeast(0) + 150
        }.isSuccess
    }

    /** 預先載入，讓之後按下時可以立刻出聲。 */
    fun preload(sound: Sound) {
        obtain(sound)
    }

    private fun obtain(sound: Sound): MediaPlayer? {
        cache[sound.id]?.let { cached ->
            if (cachedFile[sound.id] == sound.fileName) return cached
            discard(sound.id)
        }
        val file = SoundRepository.fileOf(sound)
        if (!file.exists()) return null
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(audioAttributes())
                setDataSource(file.absolutePath)
                prepare()
            }
            mp.setOnCompletionListener { if (currentId == sound.id && cache[sound.id] === mp) markStopped() }
            mp.setOnErrorListener { _, _, _ ->
                if (cache[sound.id] === mp) discard(sound.id)
                if (currentId == sound.id) markStopped()
                true
            }
            cache[sound.id] = mp
            cachedFile[sound.id] = sound.fileName
            mp
        } catch (e: Exception) {
            null
        }
    }

    /** 音效被刪除時呼叫。 */
    fun forget(id: String) {
        if (currentId == id) stop()
        discard(id)
    }

    private fun discard(id: String) {
        cache.remove(id)?.runCatching { release() }
        cachedFile.remove(id)
    }

    /** 設定改變（音訊通道、檔案刪除或替換）時清掉所有快取。 */
    fun releaseAll() {
        stop()
        cache.values.forEach { it.runCatching { release() } }
        cache.clear()
        cachedFile.clear()
    }

    fun volume(): Float = SoundRepository.playbackVolume.value

    /** 調整 App 音量時即時套用。 */
    fun applyVolume() {
        cache.values.forEach { it.runCatching { setVolume(volume(), volume()) } }
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
        pauseCurrent()
        markStopped()
    }

    private fun pauseCurrent() {
        val id = currentId ?: return
        cache[id]?.runCatching { if (isPlaying) pause() }
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
