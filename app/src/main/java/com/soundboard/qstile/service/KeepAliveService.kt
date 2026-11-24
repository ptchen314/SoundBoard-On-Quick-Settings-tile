package com.soundboard.qstile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.soundboard.qstile.MainActivity
import com.soundboard.qstile.R
import com.soundboard.qstile.data.SoundPlayer
import com.soundboard.qstile.data.SoundRepository

/**
 * 常駐背景用的前景服務：通知列會有一則常駐通知，系統就不會凍結或回收 App，
 * 快速設定磚按下去反應更快，播放狀態也能即時更新。
 */
class KeepAliveService : Service() {

    private val playerListener: () -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SoundRepository.init(this)
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // 系統不允許此時從背景啟動（例如被終止後自動重啟），等下次開 App 再啟動
            stopSelf()
            return
        }
        SoundPlayer.addChangeListener(playerListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SoundRepository.keepAlive.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_PLAYBACK) SoundPlayer.stop()
        updateNotification()
        // 被系統終止時自動重新啟動
        return START_STICKY
    }

    override fun onDestroy() {
        SoundPlayer.removeChangeListener(playerListener)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "常駐背景", NotificationManager.IMPORTANCE_MIN).apply {
            description = "讓音效快捷磚保持在背景執行"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playing = SoundRepository.find(SoundPlayer.playingId.value)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (playing != null) "播放中：${playing.name}" else "音效快捷磚 常駐中")
            .setContentText("下拉快速設定即可播放音效")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (playing != null) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP_PLAYBACK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "停止播放", stop)
        }
        return builder.build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_PLAYBACK = "com.soundboard.qstile.STOP_PLAYBACK"

        /** 依設定啟動服務；從背景啟動可能被系統拒絕，失敗就略過。 */
        fun startIfEnabled(context: Context) {
            if (!SoundRepository.keepAlive.value) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}

/** 開機或 App 更新後，若有開啟「常駐背景」就自動啟動。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                SoundRepository.init(context)
                KeepAliveService.startIfEnabled(context)
            }
        }
    }
}
