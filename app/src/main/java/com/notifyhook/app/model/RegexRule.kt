package com.notifyhook.app.model

data class RegexRule(
    val pattern: String = "",
    val sourceField: RegexSourceField = RegexSourceField.TITLE,
    val variables: List<RegexVariable> = emptyList()
)
