package com.notifyhook.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.notifyhook.app.service.DeliveryWorkScheduler
import com.notifyhook.app.service.ForegroundService

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("NotifyHook", "RestartReceiver triggered: ${intent?.action}")
        ForegroundService.startIfNeeded(context)
        DeliveryWorkScheduler.enqueueIfPending(context)
    }
}
