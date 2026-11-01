package com.notifyhook.app.util

import java.io.IOException
import java.io.Reader

internal object TextReadLimits {
    const val PRESET_FILE_MAX_CHARS = 256 * 1024
    const val BODY_FILE_MAX_CHARS = 256 * 1024
}

internal object TextReadLimiter {
    fun read(reader: Reader, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive" }
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        val output = StringBuilder()
        var totalChars = 0

        while (true) {
            val readCount = reader.read(buffer)
            if (readCount == -1) {
                return output.toString()
            }
            totalChars += readCount
            if (totalChars > maxChars) {
                throw TextTooLargeException(maxChars)
            }
            output.append(buffer, 0, readCount)
        }
    }

    fun readPrefix(reader: Reader, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive" }
        val buffer = CharArray(DEFAULT_BUFFER_SIZE.coerceAtMost(maxChars))
        val output = StringBuilder()

        while (output.length < maxChars) {
            val charsToRead = minOf(buffer.size, maxChars - output.length)
            val readCount = reader.read(buffer, 0, charsToRead)
            if (readCount == -1) {
                break
            }
            output.append(buffer, 0, readCount)
        }

        return output.toString()
    }

    private const val DEFAULT_BUFFER_SIZE = 8 * 1024
}

internal class TextTooLargeException(maxChars: Int) :
    IOException("Text input exceeds $maxChars characters")
