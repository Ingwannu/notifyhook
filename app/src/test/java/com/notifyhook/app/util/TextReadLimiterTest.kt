package com.notifyhook.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class TextReadLimiterTest {

    @Test
    fun readsTextWithinLimit() {
        assertEquals(
            "hello",
            TextReadLimiter.read(StringReader("hello"), maxChars = 5)
        )
    }

    @Test(expected = TextTooLargeException::class)
    fun rejectsTextThatExceedsLimit() {
        TextReadLimiter.read(StringReader("hello!"), maxChars = 5)
    }

    @Test
    fun readsOnlyPrefixWhenCallerNeedsTruncatedText() {
        assertEquals(
            "hello",
            TextReadLimiter.readPrefix(StringReader("hello!"), maxChars = 5)
        )
    }
}
