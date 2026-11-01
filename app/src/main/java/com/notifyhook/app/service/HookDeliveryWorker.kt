package com.notifyhook.app.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.notifyhook.app.data.HookStorage

class HookDeliveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val storage = HookStorage.getInstance(applicationContext)
        val now = System.currentTimeMillis()
        val dueDeliveries = storage.getDuePendingDeliveries(now, MAX_BATCH_SIZE)

        if (dueDeliveries.isEmpty()) {
            DeliveryWorkScheduler.enqueueIfPending(applicationContext)
            ForegroundService.stopIfIdle(applicationContext)
            return Result.success()
        }

        dueDeliveries.forEach { delivery ->
            if (!storage.hasHook(delivery.hookId)) {
                storage.removePendingDelivery(delivery.id)
                Log.d(TAG, "Pending hook '${delivery.hookName}' skipped because hook was deleted")
                return@forEach
            }
            val nonRetryableError = HookDeliveryRequestValidator.nonRetryableErrorMessage(delivery)
            if (nonRetryableError != null) {
                storage.removePendingDelivery(delivery.id)
                storage.addLog(
                    delivery.toLog(
                        success = false,
                        errorMessage = nonRetryableError
                    )
                )
                Log.e(TAG, "Pending hook '${delivery.hookName}' is invalid: $nonRetryableError")
                return@forEach
            }
            // 기기가 꺼져 있었던 등의 이유로 재시도 기간이 이미 지난 요청은 보내지 않는다.
            if (!DeliveryRetryPolicy.isWithinRetryWindow(delivery.createdAt, System.currentTimeMillis())) {
                storage.removePendingDelivery(delivery.id)
                storage.addLog(
                    delivery.toLog(
                        success = false,
                        errorMessage = "${delivery.lastError.ifBlank { "Delivery expired" }} (not retried: 24h retry window exceeded)"
                    )
                )
                Log.e(TAG, "Pending hook '${delivery.hookName}' expired before retry")
                return@forEach
            }

            val result = HookDeliverySender.send(delivery)
            if (result.success) {
                storage.removePendingDelivery(delivery.id)
                storage.addLog(
                    delivery.toLog(
                        success = true,
                        responseCode = result.responseCode,
                        responseBody = result.responseBody
                    )
                )
                Log.d(TAG, "Pending hook '${delivery.hookName}' delivered: ${result.responseCode}")
            } else {
                val failedAt = System.currentTimeMillis()
                val updated = delivery.failedAttempt(result.errorMessage, failedAt)
                val noRetryReason = DeliveryRetryPolicy.noRetryReason(result, delivery.createdAt, failedAt)
                val retryPersisted = noRetryReason == null &&
                    storage.savePendingDeliveryIfHookExists(updated)
                if (!retryPersisted) {
                    storage.removePendingDelivery(delivery.id)
                }
                storage.addLog(
                    updated.toLog(
                        success = false,
                        responseCode = result.responseCode,
                        responseBody = result.responseBody,
                        errorMessage = when {
                            retryPersisted -> result.errorMessage
                            noRetryReason != null -> "${result.errorMessage} ($noRetryReason)"
                            storage.hasHook(delivery.hookId) -> "${result.errorMessage} (retry not persisted)"
                            else -> "${result.errorMessage} (hook deleted; retry skipped)"
                        }
                    )
                )
                Log.e(TAG, "Pending hook '${delivery.hookName}' failed: ${result.errorMessage}")
            }
        }

        DeliveryWorkScheduler.enqueueIfPending(applicationContext)
        ForegroundService.stopIfIdle(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "HookDeliveryWorker"
        // Keep the worst-case 120s sequential sends below WorkManager's execution window.
        private const val MAX_BATCH_SIZE = 4
    }
}
