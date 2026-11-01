package com.notifyhook.app.service

internal object NotificationDedupKey {
    fun build(
        notificationKey: String,
        postTime: Long,
        title: CharSequence?,
        text: CharSequence?
    ): String = buildString {
        appendLengthPrefixed(notificationKey)
        append('|')
        append(postTime)
        append('|')
        appendLengthPrefixed(title?.toString().orEmpty())
        append('|')
        appendLengthPrefixed(text?.toString().orEmpty())
    }

    private fun StringBuilder.appendLengthPrefixed(value: String) {
        append(value.length)
        append(':')
        append(value)
    }
}
