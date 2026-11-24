package com.soundboard.qstile.data

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全 App 共用的播放器：同一時間只播一個音效，再按一次同一個就停止。
 *
 * 注意：快速設定收起後，系統可能凍結 App 程序，MediaPlayer 的「播完」回呼就收不到。
 * 所以播放狀態以「預計結束時間」判斷，不只依賴回呼。
 */
object SoundPlayer {
    private var player: MediaPlayer? = null
    private var currentId: String? = null
    private var endAtElapsed = 0L

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

    /** 依時間判斷這個音效是否還在播放；若已超過結束時間就順便清掉播放器。 */
    fun isPlaying(id: String): Boolean {
        expireIfNeeded()
        return currentId == id
    }

    /** 距離播放結束還有幾毫秒（沒在播放回傳 0）。 */
    fun remainingMs(): Long {
        expireIfNeeded()
        return if (currentId == null) 0 else (endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    /** 播放；若該音效正在播放則停止。回傳 true 代表開始播放。 */
    fun toggle(sound: Sound): Boolean {
        if (isPlaying(sound.id)) {
            stop()
            return false
        }
        return play(sound)
    }

    fun play(sound: Sound): Boolean {
        stopInternal()
        val file = SoundRepository.fileOf(sound)
        if (!file.exists()) {
            notifyChanged()
            return false
        }
        return try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(audioAttributes())
                setVolume(volume(), volume())
                setDataSource(file.absolutePath)
                prepare()
            }
            mp.setOnCompletionListener { if (player === mp) stop() }
            mp.setOnErrorListener { _, _, _ -> if (player === mp) stop(); true }
            mp.start()
            player = mp
            currentId = sound.id
            endAtElapsed = SystemClock.elapsedRealtime() + mp.duration.coerceAtLeast(0) + 150
            _playingId.value = sound.id
            notifyChanged()
            true
        } catch (e: Exception) {
            stopInternal()
            notifyChanged()
            false
        }
    }

    fun volume(): Float = SoundRepository.playbackVolume.value

    /** 播放中調整 App 音量時即時套用。 */
    fun applyVolume() {
        player?.runCatching { setVolume(volume(), volume()) }
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
        stopInternal()
        notifyChanged()
    }

    private fun expireIfNeeded() {
        if (currentId != null && SystemClock.elapsedRealtime() >= endAtElapsed) stop()
    }

    private fun stopInternal() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
        currentId = null
        _playingId.value = null
    }

    private fun notifyChanged() = listeners.toList().forEach { it() }
}
