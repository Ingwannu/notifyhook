package com.notifyhook.app.model

data class RegexVariable(
    val captureGroup: Int = 1,
    val variableName: String = "",
    val isGlobal: Boolean = false
)
