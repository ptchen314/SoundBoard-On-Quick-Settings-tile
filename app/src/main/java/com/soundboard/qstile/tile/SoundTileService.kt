package com.soundboard.qstile.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.soundboard.qstile.data.SoundPlayer
import com.soundboard.qstile.data.SoundRepository

/**
 * 快速設定磚。Android 規定磚必須事先在 Manifest 宣告，
 * 所以預先提供 SLOT_COUNT 個磚位，每個磚位在 App 內指定要播放的音效。
 *
 * 每次打開快速設定，系統都會呼叫 onStartListening，此時依「預計結束時間」重算狀態，
 * 即使 App 在背景被凍結、沒收到播完回呼，磚也會正確顯示。
 */
abstract class SoundTileService(private val slot: Int) : TileService() {

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refresh() }
    private val playerListener: () -> Unit = { refresh() }
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        SoundRepository.init(this)
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        SoundPlayer.addChangeListener(playerListener)
        refresh()
    }

    override fun onStopListening() {
        listening = false
        SoundPlayer.removeChangeListener(playerListener)
        handler.removeCallbacks(refreshRunnable)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val sound = SoundRepository.soundForSlot(slot)
        if (sound == null) {
            // 尚未指定音效：開啟 App 讓使用者設定
            openApp()
            return
        }
        SoundPlayer.toggle(sound)
        refresh()
    }

    private fun refresh() {
        handler.removeCallbacks(refreshRunnable)
        val tile = qsTile ?: return
        val sound = SoundRepository.soundForSlot(slot)
        if (sound == null) {
            tile.label = "音效 $slot"
            tile.subtitleCompat("點我設定")
            tile.state = Tile.STATE_INACTIVE
        } else {
            val playing = SoundPlayer.isPlaying(sound.id)
            tile.label = sound.name
            tile.subtitleCompat(if (playing) "播放中…" else "點一下播放")
            tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            // 快速設定開著時，時間一到就自動轉灰（不依賴 MediaPlayer 回呼）
            if (playing && listening) {
                handler.postDelayed(refreshRunnable, SoundPlayer.remainingMs() + 50)
            }
        }
        tile.updateTile()
    }

    private fun Tile.subtitleCompat(text: String) {
        if (Build.VERSION.SDK_INT >= 29) subtitle = text
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        val tileClasses: List<Class<out SoundTileService>> = listOf(
            SoundTile1::class.java,
            SoundTile2::class.java,
            SoundTile3::class.java,
            SoundTile4::class.java,
            SoundTile5::class.java,
        )

        fun componentFor(context: Context, slot: Int) =
            ComponentName(context, tileClasses[slot - 1])

        /** 請系統重新整理所有磚的顯示（名稱變更等）。 */
        fun requestRefreshAll(context: Context) {
            for (slot in 1..SoundRepository.SLOT_COUNT) {
                runCatching { requestListeningState(context, componentFor(context, slot)) }
            }
        }
    }
}

class SoundTile1 : SoundTileService(1)
class SoundTile2 : SoundTileService(2)
class SoundTile3 : SoundTileService(3)
class SoundTile4 : SoundTileService(4)
class SoundTile5 : SoundTileService(5)
