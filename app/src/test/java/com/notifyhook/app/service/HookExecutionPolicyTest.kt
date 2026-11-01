package com.notifyhook.app.service

import com.notifyhook.app.model.Hook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class HookExecutionPolicyTest {

    @Test
    fun wakeLockTimeoutCoversTheSlowestHookPlusBuffer() {
        val hooks = listOf(
            Hook(timeoutSeconds = 30),
            Hook(timeoutSeconds = 120)
        )

        assertEquals(
            TimeUnit.SECONDS.toMillis(130),
            HookExecutionPolicy.wakeLockTimeoutMillis(hooks)
        )
    }

    @Test
    fun wakeLockTimeoutCoversQueuedDispatchWaves() {
        val hooks = List(5) {
            Hook(timeoutSeconds = 30)
        }

        assertEquals(
            TimeUnit.SECONDS.toMillis(70),
            HookExecutionPolicy.wakeLockTimeoutMillis(hooks)
        )
    }

    @Test
    fun hookTimeoutsAreClampedToTheSupportedProductRange() {
        assertEquals(1, HookExecutionPolicy.normalizedTimeoutSeconds(Hook(timeoutSeconds = 0)))
        assertEquals(120, HookExecutionPolicy.normalizedTimeoutSeconds(Hook(timeoutSeconds = 999)))
    }

    @Test
    fun initialDeliveryRetryWaitsForImmediateDispatchWindow() {
        val now = 1_000L

        assertEquals(
            now + TimeUnit.SECONDS.toMillis(40),
            DeliveryRetryPolicy.initialNextAttemptAtMillis(now, Hook(timeoutSeconds = 30))
        )
    }

    @Test
    fun failedDeliveryUsesBoundedExponentialBackoff() {
        val now = 1_000L

        assertEquals(now + TimeUnit.SECONDS.toMillis(10), DeliveryRetryPolicy.failureNextAttemptAtMillis(now, 0))
        assertEquals(now + TimeUnit.SECONDS.toMillis(20), DeliveryRetryPolicy.failureNextAttemptAtMillis(now, 1))
        assertEquals(now + TimeUnit.MINUTES.toMillis(15), DeliveryRetryPolicy.failureNextAttemptAtMillis(now, 20))
    }
}
