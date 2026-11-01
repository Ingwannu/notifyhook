package com.notifyhook.app.service

import com.notifyhook.app.model.HookDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class DeliveryRetryPolicyTest {

    @Test
    fun retriesOnlyTransientHttpStatuses() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach {
            assertTrue("$it should retry", DeliveryRetryPolicy.isRetryableStatus(it))
        }
        listOf(301, 302, 307, 400, 401, 403, 404, 405, 409, 410, 413, 422).forEach {
            assertFalse("$it should not retry", DeliveryRetryPolicy.isRetryableStatus(it))
        }
    }

    @Test
    fun stopsRetryingAfterTheRetryWindow() {
        val createdAt = 1_000L

        assertTrue(DeliveryRetryPolicy.isWithinRetryWindow(createdAt, createdAt + TimeUnit.HOURS.toMillis(23)))
        assertFalse(DeliveryRetryPolicy.isWithinRetryWindow(createdAt, createdAt + TimeUnit.HOURS.toMillis(24)))
    }

    @Test
    fun noRetryReasonExplainsWhyAFailureIsDropped() {
        val now = 10_000L
        val transient = HookDeliverySender.SendResult(success = false, responseCode = 503, retryable = true)
        val permanent = HookDeliverySender.SendResult(success = false, responseCode = 401, retryable = false)

        assertNull(DeliveryRetryPolicy.noRetryReason(transient, createdAtMillis = now, nowMillis = now))
        assertNotNull(DeliveryRetryPolicy.noRetryReason(permanent, createdAtMillis = now, nowMillis = now))
        assertNotNull(
            DeliveryRetryPolicy.noRetryReason(
                transient,
                createdAtMillis = now,
                nowMillis = now + DeliveryRetryPolicy.RETRY_WINDOW_MILLIS
            )
        )
    }

    @Test
    fun failedAttemptAdvancesAttemptCountAndBackoff() {
        val delivery = HookDelivery(hookId = "h", hookName = "Hook", attemptCount = 1)

        val failed = delivery.failedAttempt("HTTP 503", nowMillis = 1_000L)

        assertEquals(2, failed.attemptCount)
        assertEquals("HTTP 503", failed.lastError)
        assertEquals(1_000L, failed.lastAttemptAt)
        assertEquals(1_000L + TimeUnit.SECONDS.toMillis(20), failed.nextAttemptAt)
    }
}
