package com.notifyhook.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class NotifyHookApp : Application() {

    companion object {
        const val CHANNEL_ID = "notifyhook_foreground"
        const val CHANNEL_LOG_ID = "notifyhook_log"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }

        val logChannel = NotificationChannel(
            CHANNEL_LOG_ID,
            "훅 실행 알림",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "훅 실행 결과를 알려줍니다"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(logChannel)
    }
}
