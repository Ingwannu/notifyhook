package com.notifyhook.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectLoadGuardTest {

    @Test
    fun dismissCancelsTrackedLoadAndBlocksResultApplication() {
        var cancelled = false
        val guard = AppSelectLoadGuard()

        guard.trackLoad { cancelled = true }
        guard.dismiss()

        assertTrue(cancelled)
        assertFalse(guard.shouldApplyResult(isFinishing = false, isDestroyed = false))
    }

    @Test
    fun loadTrackedAfterDismissIsCancelledImmediately() {
        var cancelled = false
        val guard = AppSelectLoadGuard()

        guard.dismiss()
        guard.trackLoad { cancelled = true }

        assertTrue(cancelled)
    }
}
