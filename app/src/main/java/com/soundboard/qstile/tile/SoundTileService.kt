package com.soundboard.qstile.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.soundboard.qstile.data.SoundPlayer
import com.soundboard.qstile.data.SoundRepository

/**
 * 快速設定磚。Android 規定磚必須事先在 Manifest 宣告，
 * 所以預先宣告 MAX_SLOTS 個磚位，再依「磚位數量」設定啟用／停用元件。
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
        // 打開快速設定時先把音效載入好，按下去就能立刻出聲
        handler.post { SoundRepository.soundForSlot(slot)?.let { SoundPlayer.preload(it) } }
    }

    override fun onStopListening() {
        listening = false
        SoundPlayer.removeChangeListener(playerListener)
        handler.removeCallbacks(refreshRunnable)
        super.onStopListening()
    }

    override fun onClick() {
        val clickNanos = android.os.SystemClock.elapsedRealtimeNanos()
        super.onClick()
        val sound = SoundRepository.soundForSlot(slot)
        if (sound == null) {
            // 尚未指定音效：開啟 App 讓使用者設定
            openApp()
            return
        }
        // 一律從頭播放：連按就連續重播，不會被當成「停止」
        SoundPlayer.play(sound, source = "磚", clickNanos = clickNanos)
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
            tile.subtitleCompat(if (playing) "播放中・點一下重播" else "點一下播放")
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
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // 已依 SDK 版本分流
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
            SoundTile6::class.java,
            SoundTile7::class.java,
            SoundTile8::class.java,
            SoundTile9::class.java,
            SoundTile10::class.java,
            SoundTile11::class.java,
            SoundTile12::class.java,
            SoundTile13::class.java,
            SoundTile14::class.java,
            SoundTile15::class.java,
            SoundTile16::class.java,
            SoundTile17::class.java,
            SoundTile18::class.java,
            SoundTile19::class.java,
            SoundTile20::class.java,
        )

        fun componentFor(context: Context, slot: Int) =
            ComponentName(context, tileClasses[slot - 1])

        /** 請系統重新整理所有磚的顯示（名稱變更等）。 */
        fun requestRefreshAll(context: Context) {
            for (slot in 1..SoundRepository.slotCount.value) {
                runCatching { requestListeningState(context, componentFor(context, slot)) }
            }
        }

        /**
         * 只啟用前 count 個磚，其餘停用，停用的磚不會出現在快速設定的編輯清單裡。
         * 這只是開關本 App 自己的元件，不會改動系統設定。
         */
        fun applyEnabledSlots(context: Context, count: Int) {
            val pm = context.packageManager
            for (slot in 1..SoundRepository.MAX_SLOTS) {
                val want = if (slot <= count) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val cn = componentFor(context, slot)
                if (pm.getComponentEnabledSetting(cn) != want) {
                    runCatching { pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP) }
                }
            }
        }
    }
}

class SoundTile1 : SoundTileService(1)
class SoundTile2 : SoundTileService(2)
class SoundTile3 : SoundTileService(3)
class SoundTile4 : SoundTileService(4)
class SoundTile5 : SoundTileService(5)
class SoundTile6 : SoundTileService(6)
class SoundTile7 : SoundTileService(7)
class SoundTile8 : SoundTileService(8)
class SoundTile9 : SoundTileService(9)
class SoundTile10 : SoundTileService(10)
class SoundTile11 : SoundTileService(11)
class SoundTile12 : SoundTileService(12)
class SoundTile13 : SoundTileService(13)
class SoundTile14 : SoundTileService(14)
class SoundTile15 : SoundTileService(15)
class SoundTile16 : SoundTileService(16)
class SoundTile17 : SoundTileService(17)
class SoundTile18 : SoundTileService(18)
class SoundTile19 : SoundTileService(19)
class SoundTile20 : SoundTileService(20)
