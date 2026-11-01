package com.notifyhook.app.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object HookUrlValidator {
    fun isSupported(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false
        }
        return trimmed.toHttpUrlOrNull() != null
    }
}
