package com.notifyhook.app.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

internal object NotificationListenerAccess {
    fun isEnabled(context: Context): Boolean {
        val componentName = ComponentName(
            context,
            NotificationListener::class.java
        ).flattenToString()
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners
            .split(':')
            .any { it == componentName }
    }
}
