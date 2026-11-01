package com.notifyhook.app.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.notifyhook.app.NotifyHookApp
import com.notifyhook.app.R
import com.notifyhook.app.data.HookStorage
import com.notifyhook.app.receiver.RestartReceiver
import com.notifyhook.app.ui.MainActivity

class ForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15분
        private const val WATCHDOG_REQUEST_CODE = 9999
        private const val TAG = "NotifyHookService"

        fun start(context: Context) {
            try {
                val intent = Intent(context, ForegroundService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        fun startIfNeeded(context: Context) {
            if (shouldRun(context)) {
                start(context)
            } else {
                stop(context)
            }
        }

        fun stop(context: Context) {
            cancelWatchdog(context)
            context.stopService(Intent(context, ForegroundService::class.java))
        }

        fun stopIfIdle(context: Context) {
            if (!shouldRun(context)) {
                stop(context)
            }
        }

        private fun shouldRun(context: Context): Boolean {
            val storage = HookStorage.getInstance(context)
            if (storage.nextPendingDeliveryAttemptAt() != null) {
                return true
            }
            return storage.getHooks().any { it.enabled } &&
                NotificationListenerAccess.isEnabled(context)
        }

        private fun cancelWatchdog(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(getWatchdogPendingIntent(context))
        }

        private fun getWatchdogPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, RestartReceiver::class.java).apply {
                action = "com.notifyhook.WATCHDOG"
            }
            return PendingIntent.getBroadcast(
                context, WATCHDOG_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        scheduleWatchdog()
        DeliveryWorkScheduler.enqueueIfPending(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (shouldRun(this)) {
            scheduleWatchdog()
        } else {
            cancelWatchdog(this)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldRun(this)) {
            scheduleWatchdog()
        } else {
            cancelWatchdog(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = getWatchdogPendingIntent(this)
        try {
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000, // 1분 후 첫 실행
                WATCHDOG_INTERVAL_MS,
                pi
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog", e)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotifyHookApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
