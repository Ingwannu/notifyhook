package com.notifyhook.app.service

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationDedupKeyTest {

    @Test
    fun samePostedNotificationBuildsStableKey() {
        val first = NotificationDedupKey.build(
            notificationKey = "0|com.example|42|null|1000",
            postTime = 1_700_000_000_000L,
            title = "Payment",
            text = "Approved"
        )
        val second = NotificationDedupKey.build(
            notificationKey = "0|com.example|42|null|1000",
            postTime = 1_700_000_000_000L,
            title = "Payment",
            text = "Approved"
        )

        assertEquals(first, second)
    }

    @Test
    fun repeatedNotificationTextWithNewPostTimeDoesNotDeduplicate() {
        val first = NotificationDedupKey.build(
            notificationKey = "0|com.example|42|null|1000",
            postTime = 1_700_000_000_000L,
            title = "Payment",
            text = "Approved"
        )
        val second = NotificationDedupKey.build(
            notificationKey = "0|com.example|42|null|1000",
            postTime = 1_700_000_000_250L,
            title = "Payment",
            text = "Approved"
        )

        assertNotEquals(first, second)
    }
}
