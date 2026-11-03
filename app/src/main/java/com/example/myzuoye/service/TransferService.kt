package com.example.myzuoye.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.myzuoye.MainActivity
import com.example.myzuoye.R

/**
 * 前台服务：在文件/文本传输期间保活连接，并在通知栏展示进度。
 *
 * 传输结束后由调用方（TransferManager 的回调）停止本服务。
 */
class TransferService : Service() {

    companion object {
        private const val CHANNEL_ID = "transfer"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.example.myzuoye.action.START"
        const val ACTION_STOP = "com.example.myzuoye.action.STOP"
        const val ACTION_UPDATE = "com.example.myzuoye.action.UPDATE"

        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MAX = "max"

        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun update(
            context: Context,
            title: String,
            text: String,
            progress: Int = 0,
            max: Int = 100
        ) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_MAX, max)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // START 或 UPDATE：确保是前台状态，并更新通知
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.notif_transferring)
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
                val max = intent?.getIntExtra(EXTRA_MAX, 100) ?: 100

                val notification = buildNotification(title, text, progress, max)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIF_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        }

        return builder.build()
    }
}
