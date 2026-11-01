package com.notifyhook.app.model

data class BodyFileSaveState(
    val selectedPath: String?,
    val pathForStorage: String?
) {
    companion object {
        fun prepare(
            bodySourceType: BodySourceType,
            selectedBodyFilePath: String?
        ): BodyFileSaveState {
            return BodyFileSaveState(
                selectedPath = selectedBodyFilePath,
                pathForStorage = if (bodySourceType == BodySourceType.FILE) {
                    selectedBodyFilePath
                } else {
                    null
                }
            )
        }
    }
}
