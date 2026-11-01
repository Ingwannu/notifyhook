package com.notifyhook.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexRuleValidatorTest {

    @Test
    fun acceptsBlankAndValidPatterns() {
        assertTrue(RegexRuleValidator.arePatternsValid(emptyList()))
        assertTrue(
            RegexRuleValidator.arePatternsValid(
                listOf(RegexRule(pattern = "order #(\\d+)"))
            )
        )
    }

    @Test
    fun rejectsInvalidPatternsBeforeRuntimeDispatch() {
        assertFalse(
            RegexRuleValidator.arePatternsValid(
                listOf(RegexRule(pattern = "(unclosed"))
            )
        )
    }
}
