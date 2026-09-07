package com.qiuminal.juicedict.wifi

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
import androidx.core.app.NotificationManagerCompat
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.ui.WiFiTransferActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wi-Fi 传词典前台服务：仅负责「保活」与通知栏呈现，
 * 真正的 HTTP 服务与状态由 [WifiTransferController] 单例持有。
 *
 * - 页面进入即启动（startForegroundService），页面退出继续运行；
 * - 通知栏显示访问网址 + 「停止」动作；
 * - 应用任务被划掉（onTaskRemoved）时自动停止，避免无人值守的开放端口。
 */
class WifiTransferService : Service() {

    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wifi_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 先占住前台身份，再异步拉起服务器，避免 5 秒超时
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        WifiTransferController.startAsync(this)
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
                s.launch {
                    WifiTransferController.state.collect { updateNotification(it) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        WifiTransferController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WiFiTransferActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WifiTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.wifi_notification_title))
            .setContentText(getString(R.string.wifi_notification_starting))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(ACCENT_COLOR)
            .addAction(0, getString(R.string.wifi_stop_action), stopIntent)
            .build()
    }

    private fun updateNotification(state: WifiTransferController.UiState) {
        val text = when (state) {
            is WifiTransferController.UiState.Running ->
                getString(R.string.wifi_notification_text, state.url)
            is WifiTransferController.UiState.Failed -> state.reason
            else -> getString(R.string.wifi_notification_starting)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WiFiTransferActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WifiTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.wifi_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(ACCENT_COLOR)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, getString(R.string.wifi_stop_action), stopIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 13+ 未授予通知权限时静默跳过，前台服务本身不受影响
        }
    }

    companion object {
        private const val CHANNEL_ID = "wifi_transfer"
        private const val NOTIFICATION_ID = 402
        private const val ACTION_STOP = "com.qiuminal.juicedict.action.STOP_WIFI_TRANSFER"
        private const val ACCENT_COLOR = 0xFF3949AB.toInt()

        fun start(context: Context) {
            val intent = Intent(context, WifiTransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiTransferService::class.java))
        }
    }
}
