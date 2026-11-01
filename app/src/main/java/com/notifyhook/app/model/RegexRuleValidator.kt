package com.notifyhook.app.model

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object RegexRuleValidator {
    fun arePatternsValid(rules: List<RegexRule>): Boolean {
        return firstInvalidPattern(rules) == null
    }

    fun firstInvalidPattern(rules: List<RegexRule>): String? {
        rules.forEach { rule ->
            val pattern = rule.pattern.trim()
            if (pattern.isEmpty()) {
                return@forEach
            }
            try {
                Pattern.compile(pattern)
            } catch (e: PatternSyntaxException) {
                return pattern
            }
        }
        return null
    }
}
