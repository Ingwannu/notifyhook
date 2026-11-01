package com.notifyhook.app.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.notifyhook.app.data.HookStorage
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

object DeliveryWorkScheduler {
    private const val UNIQUE_WORK_NAME = "notifyhook-pending-delivery-drain"
    private val directExecutor = Executor { command -> command.run() }

    fun enqueue(context: Context, delayMillis: Long = 0L) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        val requestedDelayMillis = nextDelayMillis(appContext, delayMillis)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<HookDeliveryWorker>()
            .setConstraints(constraints)
            .setInitialDelay(requestedDelayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        val existingWork = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
        existingWork.addListener(
            listener@{
                if (HookStorage.getInstance(appContext).nextPendingDeliveryAttemptAt() == null) {
                    return@listener
                }
                val existingStates = existingWorkStates(existingWork)
                workManager.enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    existingWorkPolicy(existingStates),
                    request
                )
            },
            directExecutor
        )
    }

    fun enqueueIfPending(context: Context) {
        val storage = HookStorage.getInstance(context)
        val now = System.currentTimeMillis()
        val delayMillis = storage.nextPendingDeliveryAttemptAt()
            ?.let { (it - now).coerceAtLeast(0L) }
            ?: return
        enqueue(context, delayMillis)
    }

    fun cancelIfNoPending(context: Context) {
        val storage = HookStorage.getInstance(context)
        if (storage.nextPendingDeliveryAttemptAt() == null) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private fun nextDelayMillis(context: Context, requestedDelayMillis: Long): Long {
        val storage = HookStorage.getInstance(context)
        val now = System.currentTimeMillis()
        val pendingDelayMillis = storage.nextPendingDeliveryAttemptAt()
            ?.let { (it - now).coerceAtLeast(0L) }
        return minOf(requestedDelayMillis.coerceAtLeast(0L), pendingDelayMillis ?: Long.MAX_VALUE)
    }

    private fun existingWorkStates(existingWork: ListenableFuture<List<WorkInfo>>): List<WorkInfo.State> {
        return runCatching {
            existingWork.get().map { it.state }
        }.getOrDefault(emptyList())
    }

    internal fun existingWorkPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy {
        val hasRunningDrain = states.any { it == WorkInfo.State.RUNNING }
        val hasQueuedDrain = states.any {
            it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED
        }
        return when {
            hasRunningDrain && hasQueuedDrain -> ExistingWorkPolicy.KEEP
            hasRunningDrain -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.REPLACE
        }
    }
}
