package com.notifyhook.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HookDeliveryTest {

    @Test
    fun logTruncatesRequestBodyWithoutChangingQueuedDeliveryBody() {
        val requestBody = "x".repeat(4_100)
        val delivery = HookDelivery(
            hookId = "hook-1",
            hookName = "Hook",
            requestBody = requestBody
        )

        val log = delivery.toLog(success = false)

        assertEquals(requestBody, delivery.requestBody)
        assertTrue(log.requestBody.length < requestBody.length)
        assertTrue(log.requestBody.endsWith("\n...[truncated]"))
    }

    @Test
    fun logTruncatesLargeNotificationMetadata() {
        val notificationText = "n".repeat(2_100)
        val delivery = HookDelivery(
            hookId = "hook-1",
            hookName = "Hook",
            notificationTitle = notificationText,
            notificationText = notificationText
        )

        val log = delivery.toLog(success = false)

        assertTrue(log.notificationTitle.length < notificationText.length)
        assertTrue(log.notificationText.length < notificationText.length)
        assertTrue(log.notificationTitle.endsWith("\n...[truncated]"))
        assertTrue(log.notificationText.endsWith("\n...[truncated]"))
    }

    @Test
    fun boundedStorageCopyTruncatesMetadataWithoutChangingRequestBody() {
        val notificationText = "n".repeat(2_100)
        val requestBody = "x".repeat(2_100)
        val delivery = HookDelivery(
            hookId = "hook-1",
            hookName = "Hook",
            notificationTitle = notificationText,
            notificationText = notificationText,
            requestBody = requestBody
        )

        val bounded = delivery.boundedForStorage()

        assertEquals(requestBody, bounded.requestBody)
        assertTrue(bounded.notificationTitle.length < notificationText.length)
        assertTrue(bounded.notificationText.length < notificationText.length)
        assertTrue(bounded.notificationTitle.endsWith("\n...[truncated]"))
        assertTrue(bounded.notificationText.endsWith("\n...[truncated]"))
    }
}
