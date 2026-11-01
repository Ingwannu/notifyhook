package com.notifyhook.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyFileSaveStateTest {

    @Test
    fun textBodySaveStoresNoFilePathButKeepsSelectedPathForFailureCleanup() {
        val selectedPath = "content://body/new"

        val saveState = BodyFileSaveState.prepare(
            bodySourceType = BodySourceType.TEXT,
            selectedBodyFilePath = selectedPath
        )

        assertNull(saveState.pathForStorage)
        assertEquals(selectedPath, saveState.selectedPath)
    }
}
