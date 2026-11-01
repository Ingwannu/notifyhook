package com.notifyhook.app.service

import com.notifyhook.app.model.HookDelivery
import com.notifyhook.app.model.HttpHeader
import com.notifyhook.app.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HookDeliveryRequestValidatorTest {

    @Test
    fun allowsSupportedPendingDeliveryRequests() {
        val delivery = HookDelivery(
            hookId = "hook-id",
            hookName = "hook",
            requestUrl = "https://example.com/webhook",
            requestMethod = HttpMethod.POST.name
        )

        assertNull(HookDeliveryRequestValidator.nonRetryableErrorMessage(delivery))
    }

    @Test
    fun rejectsUnsupportedPendingDeliveryMethodsBeforeRetry() {
        val delivery = HookDelivery(
            hookId = "hook-id",
            hookName = "hook",
            requestUrl = "https://example.com/webhook",
            requestMethod = "DELETE"
        )

        assertEquals(
            "Invalid webhook method; retry skipped",
            HookDeliveryRequestValidator.nonRetryableErrorMessage(delivery)
        )
    }

    @Test
    fun rejectsPendingDeliveriesWithHeadersThatCannotBeSent() {
        val delivery = HookDelivery(
            hookId = "hook-id",
            hookName = "hook",
            requestUrl = "https://example.com/webhook",
            requestMethod = HttpMethod.POST.name,
            requestHeaders = listOf(HttpHeader("X-Key", "line\nbreak"))
        )

        assertEquals(
            "Invalid webhook header; retry skipped",
            HookDeliveryRequestValidator.nonRetryableErrorMessage(delivery)
        )
    }
}
