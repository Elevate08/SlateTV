package com.stasisstate.slatetv.connector.homeassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps Slate's process running while doorbell alerts are on. Without it Android caches and
 * freezes the process as soon as another app is in front, which stalls the Home Assistant
 * WebSocket and the doorbell never pops up. The service holds no logic of its own: the
 * connection lives in [HomeAssistantRuntime].
 */
class DoorbellListenerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            Log.w(TAG, "Could not enter the foreground: ${it.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!HomeAssistantRuntime.isInitialized || !HomeAssistantRuntime.shouldListenInBackground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        HomeAssistantRuntime.reconnectIfIdle()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Doorbell alerts", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps Slate TV listening for your Home Assistant doorbell."
                    setShowBadge(false)
                },
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Listening for the doorbell")
            .setContentText("Connected to Home Assistant")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "SlateHA"
        private const val CHANNEL_ID = "slatetv_doorbell_listener"
        private const val NOTIFICATION_ID = 0x5A7E

        /**
         * Starts or stops the service. Starting can be refused while Slate is in the background
         * (Android 12+); callers retry from the foreground (Slate's home screen resuming).
         */
        fun sync(context: Context, run: Boolean) {
            val intent = Intent(context, DoorbellListenerService::class.java)
            if (run) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { Log.i(TAG, "Doorbell listener not started yet: ${it.javaClass.simpleName}") }
            } else {
                context.stopService(intent)
            }
        }
    }
}
