package com.notifyhook.app.ui

internal class AppSelectLoadGuard {
    private var active = true
    private var cancelLoad: (() -> Unit)? = null

    fun trackLoad(cancel: () -> Unit) {
        if (!active) {
            cancel()
            return
        }
        cancelLoad = cancel
    }

    fun dismiss() {
        if (!active) {
            return
        }
        active = false
        cancelLoad?.invoke()
        cancelLoad = null
    }

    fun shouldApplyResult(isFinishing: Boolean, isDestroyed: Boolean): Boolean {
        return active && !isFinishing && !isDestroyed
    }
}
