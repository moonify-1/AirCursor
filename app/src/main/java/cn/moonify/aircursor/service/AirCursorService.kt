package cn.moonify.aircursor.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.moonify.aircursor.MainActivity
import cn.moonify.aircursor.R
import cn.moonify.aircursor.bluetooth.AirCursorConnectionManager

/**
 * 前台连接保活服务 (Foreground Service):
 * 确保应用退到后台、返回桌面或 Activity 销毁后，蓝牙 HID 与 RFCOMM 连接依然稳定保持。
 * 类型为 FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE，完全符合 Android 14+ 规范。
 */
class AirCursorService : Service() {

    companion object {
        private const val TAG = "AirCursorService"
        const val CHANNEL_ID = "aircursor_bg_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "cn.moonify.aircursor.action.START_SERVICE"
        const val ACTION_STOP = "cn.moonify.aircursor.action.STOP_SERVICE"
        const val ACTION_DISCONNECT = "cn.moonify.aircursor.action.DISCONNECT"

        fun start(context: Context) {
            val intent = Intent(context, AirCursorService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AirCursorService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirCursorService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop AirCursorService", e)
            }
        }
    }

    private val connectionManager = AirCursorConnectionManager.instance

    private val connectionListener = object : AirCursorConnectionManager.Listener {
        override fun onStateChanged() {
            updateNotification()
        }

        override fun onReconnectProgress(attempt: Int, maxAttempts: Int, message: String) {
            updateNotification()
        }

        override fun onReconnectFailed(device: BluetoothDevice?) {
            updateNotification()
        }

        override fun onError(message: String) {
            // Notification handles error state through onStateChanged
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AirCursorService onCreate")
        connectionManager.init(applicationContext)
        connectionManager.addListener(connectionListener)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        if (intent?.action == ACTION_DISCONNECT) {
            Log.d(TAG, "Notification Disconnect action triggered")
            connectionManager.disconnectAll(manual = true)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForegroundCompat(notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AirCursorService onDestroy")
        connectionManager.removeListener(connectionListener)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, AirCursorService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeDevice = connectionManager.getActiveDevice()
        val deviceName = activeDevice?.let {
            try { it.name } catch (_: Exception) { null } ?: it.address
        } ?: "平板设备"

        val title: String
        val content: String

        when {
            connectionManager.isConnected() -> {
                title = getString(R.string.notification_title_connected)
                content = "已连接: $deviceName (后台运行中)"
            }
            connectionManager.isReconnecting -> {
                title = getString(R.string.notification_title_reconnecting)
                val attempt = connectionManager.reconnectAttempt
                val max = AirCursorConnectionManager.MAX_RECONNECT_ATTEMPTS
                content = "正在重连: $deviceName (第 $attempt/$max 次)..."
            }
            connectionManager.isConnecting() -> {
                title = getString(R.string.notification_title_connecting)
                content = "正在连接: $deviceName..."
            }
            connectionManager.isConfirmedUnreachable -> {
                title = "AirCursor 连接已断开"
                content = "重试已结束：无法连接至 $deviceName"
            }
            else -> {
                title = getString(R.string.notification_title_idle)
                content = "等待连接平板设备"
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (connectionManager.isConnectedOrConnecting() || connectionManager.isReconnecting) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_disconnect),
                disconnectPendingIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            val notification = buildNotification()
            nm?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground", e)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stopForeground", e)
        }
    }
}
