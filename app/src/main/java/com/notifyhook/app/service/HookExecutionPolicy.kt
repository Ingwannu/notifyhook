package com.notifyhook.app.service

import com.notifyhook.app.model.Hook
import java.util.concurrent.TimeUnit

internal object HookExecutionPolicy {
    const val MAX_CONCURRENT_DISPATCHES = 4

    private const val MIN_TIMEOUT_SECONDS = 1
    private const val MAX_TIMEOUT_SECONDS = 120
    private const val WAKE_LOCK_BUFFER_SECONDS = 10

    fun normalizedTimeoutSeconds(hook: Hook): Int {
        return hook.timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    }

    fun wakeLockTimeoutMillis(hooks: List<Hook>): Long {
        val slowestTimeoutSeconds = hooks.maxOfOrNull { normalizedTimeoutSeconds(it) } ?: return 0L
        val dispatchWaves = (hooks.size + MAX_CONCURRENT_DISPATCHES - 1) / MAX_CONCURRENT_DISPATCHES
        return TimeUnit.SECONDS.toMillis(
            (slowestTimeoutSeconds * dispatchWaves + WAKE_LOCK_BUFFER_SECONDS).toLong()
        )
    }
}
