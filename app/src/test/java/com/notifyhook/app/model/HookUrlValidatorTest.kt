package com.notifyhook.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookUrlValidatorTest {

    @Test
    fun acceptsHttpAndHttpsWebhookUrls() {
        assertTrue(HookUrlValidator.isSupported("http://example.com/hook"))
        assertTrue(HookUrlValidator.isSupported("https://example.com/hook?token=abc"))
    }

    @Test
    fun rejectsUnsupportedOrMalformedWebhookUrls() {
        assertFalse(HookUrlValidator.isSupported(""))
        assertFalse(HookUrlValidator.isSupported("example.com/hook"))
        assertFalse(HookUrlValidator.isSupported("ftp://example.com/hook"))
        assertFalse(HookUrlValidator.isSupported("https://"))
    }
}
