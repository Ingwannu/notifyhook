package com.notifyhook.app.data

import com.notifyhook.app.model.RegexRule
import com.notifyhook.app.model.RegexSourceField
import com.notifyhook.app.model.RegexVariable
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateEngineTest {

    @Test
    fun processFieldsAppliesRegexAndGlobalVariables() {
        val rules = listOf(
            RegexRule(
                pattern = "order #(\\d+)",
                sourceField = RegexSourceField.TEXT,
                variables = listOf(
                    RegexVariable(
                        captureGroup = 1,
                        variableName = "order_id"
                    )
                )
            )
        )

        val result = TemplateEngine.processFields(
            template = """{"order":"{var_order_id}","app":"{app_name}","device":"{global.device}"}""",
            packageName = "com.example.shop",
            appName = "Shop",
            postTime = 1_700_000_000_000L,
            title = "ignored",
            text = "new order #12345",
            subText = "",
            ticker = "",
            bigText = "",
            summary = "",
            regexRules = rules,
            globalVariables = mapOf("device" to "phone-1")
        )

        assertEquals("""{"order":"12345","app":"Shop","device":"phone-1"}""", result)
    }

    @Test
    fun processFieldsCollectsGlobalRegexVariables() {
        val updates = mutableMapOf<String, String>()
        val rules = listOf(
            RegexRule(
                pattern = "status=(\\w+)",
                sourceField = RegexSourceField.TEXT,
                variables = listOf(
                    RegexVariable(
                        captureGroup = 1,
                        variableName = "last_status",
                        isGlobal = true
                    )
                )
            )
        )

        val result = TemplateEngine.processFields(
            template = "{global.last_status}",
            packageName = "com.example.shop",
            appName = "Shop",
            postTime = 1_700_000_000_000L,
            title = "",
            text = "status=paid",
            subText = "",
            ticker = "",
            bigText = "",
            summary = "",
            regexRules = rules,
            globalVariables = emptyMap(),
            onGlobalVariable = { name, value -> updates[name] = value }
        )

        assertEquals("paid", result)
        assertEquals(mapOf("last_status" to "paid"), updates)
    }

    private fun render(
        template: String,
        title: String = "",
        text: String = "",
        globalVariables: Map<String, String> = emptyMap()
    ): String = TemplateEngine.processFields(
        template = template,
        packageName = "com.example",
        appName = "App",
        postTime = 1_700_000_000_000L,
        title = title,
        text = text,
        subText = "",
        ticker = "",
        bigText = "",
        summary = "",
        globalVariables = globalVariables
    )

    @Test
    fun substitutedValuesAreNotExpandedAgain() {
        val result = render(
            template = """{"title":"{title}","text":"{text}"}""",
            title = "literal {text} and {global.secret}",
            text = "body",
            globalVariables = mapOf("secret" to "s3cr3t")
        )

        assertEquals("""{"title":"literal {text} and {global.secret}","text":"body"}""", result)
    }

    @Test
    fun unknownPlaceholdersAndJsonBracesAreLeftUntouched() {
        assertEquals("""{"a":{"b":1},"c":"{unknown}"}""", render("""{"a":{"b":1},"c":"{unknown}"}"""))
    }

    @Test
    fun escapesAllJsonControlCharacters() {
        val result = render("\"{text}\"", text = "q\" b\\ n\n t\t bell\u0007 nul\u0000")

        assertEquals("\"q\\\" b\\\\ n\\n t\\t bell\\u0007 nul\\u0000\"", result)
    }
}
