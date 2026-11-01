package com.notifyhook.app.service

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DeliveryWorkSchedulerTest {

    @Test
    fun replacesQueuedDrainWhenNoWorkerIsRunning() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            DeliveryWorkScheduler.existingWorkPolicy(listOf(WorkInfo.State.ENQUEUED))
        )
    }

    @Test
    fun appendsFollowUpDrainWhenCurrentWorkerIsRunning() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            DeliveryWorkScheduler.existingWorkPolicy(listOf(WorkInfo.State.RUNNING))
        )
    }

    @Test
    fun keepsExistingFollowUpWhenRunningWorkerAlreadyHasQueuedDrain() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            DeliveryWorkScheduler.existingWorkPolicy(
                listOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)
            )
        )
    }
}
