package com.notifyhook.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHeaderMaskingTest {

    @Test
    fun masksFlaggedAndSensitiveLookingHeaders() {
        val headers = listOf(
            HttpHeader("Authorization", "Bearer secret"),
            HttpHeader("X-API-Key", "secret"),
            HttpHeader("X-Custom", "secret", sensitive = true),
            HttpHeader("X-Trace-Id", "trace-1")
        )

        assertEquals(
            "Authorization: ********\nX-API-Key: ********\nX-Custom: ********\nX-Trace-Id: trace-1",
            HttpHeaderMasking.summary(headers)
        )
    }

    @Test
    fun summaryNeverContainsSecretValues() {
        val summary = HttpHeaderMasking.summary(listOf(HttpHeader("Cookie", "session=abc123")))

        assertFalse(summary.contains("abc123"))
    }

    @Test
    fun detectsSecretHeadersWhoseValueWasStrippedOnExport() {
        assertTrue(HttpHeaderMasking.hasMissingSecretValue(listOf(HttpHeader("X-API-Key", ""))))
        assertTrue(HttpHeaderMasking.hasMissingSecretValue(listOf(HttpHeader("X-Custom", " ", sensitive = true))))
        assertFalse(HttpHeaderMasking.hasMissingSecretValue(listOf(HttpHeader("X-Trace-Id", ""))))
        assertFalse(HttpHeaderMasking.hasMissingSecretValue(listOf(HttpHeader("X-API-Key", "k"))))
    }
}
