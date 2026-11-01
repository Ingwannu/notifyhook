package com.notifyhook.app.data

import android.service.notification.StatusBarNotification
import android.util.Log
import com.notifyhook.app.model.RegexRule
import com.notifyhook.app.model.RegexSourceField
import java.util.LinkedHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object TemplateEngine {

    private const val TAG = "TemplateEngine"
    private const val PATTERN_CACHE_MAX = 128
    private val PLACEHOLDER = Regex("\\{([^{}]+)\\}")
    private val patternCache = object : LinkedHashMap<String, Pattern>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?): Boolean {
            return size > PATTERN_CACHE_MAX
        }
    }

    /**
     * 템플릿 변수를 알림 데이터로 치환합니다.
     *
     * 사용 가능한 변수:
     *   {notification}       → 알림 전체 텍스트 (title + text)
     *   {title}              → 알림 제목
     *   {text}               → 알림 내용
     *   {subtext}            → 알림 서브텍스트
     *   {app_package}        → 알림을 보낸 앱의 패키지명
     *   {app_name}           → 알림을 보낸 앱 이름
     *   {timestamp}          → 알림 수신 시각 (ISO 8601)
     *   {timestamp_unix}     → 알림 수신 시각 (Unix timestamp, ms)
     *   {ticker}             → 티커 텍스트
     *   {big_text}           → 확장된 알림 텍스트
     *   {summary}            → 요약 텍스트
     */
    fun process(
        template: String,
        sbn: StatusBarNotification,
        appName: String,
        regexRules: List<RegexRule> = emptyList(),
        globalStore: GlobalVariableStore? = null
    ): String {
        val extras = sbn.notification.extras
        val globalUpdates = mutableMapOf<String, String>()
        val result = processFields(
            template = template,
            packageName = sbn.packageName,
            appName = appName,
            postTime = sbn.postTime,
            title = extras.getCharSequence("android.title")?.toString() ?: "",
            text = extras.getCharSequence("android.text")?.toString() ?: "",
            subText = extras.getCharSequence("android.subText")?.toString() ?: "",
            ticker = sbn.notification.tickerText?.toString() ?: "",
            bigText = extras.getCharSequence("android.bigText")?.toString() ?: "",
            summary = extras.getCharSequence("android.summaryText")?.toString() ?: "",
            regexRules = regexRules,
            globalVariables = globalStore?.getAll().orEmpty(),
            onGlobalVariable = { name, value -> globalUpdates[name] = value }
        )
        globalStore?.setAll(globalUpdates)
        return result
    }

    fun processFields(
        template: String,
        packageName: String,
        appName: String,
        postTime: Long,
        title: String,
        text: String,
        subText: String,
        ticker: String,
        bigText: String,
        summary: String,
        regexRules: List<RegexRule> = emptyList(),
        globalVariables: Map<String, String> = emptyMap(),
        onGlobalVariable: ((String, String) -> Unit)? = null
    ): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())
        val notificationFull = if (title.isNotEmpty() && text.isNotEmpty()) {
            "$title: $text"
        } else {
            title + text
        }

        val mutableGlobalVariables = globalVariables.toMutableMap()
        val localRegexVariables = extractRegexVariables(
            regexRules = regexRules,
            title = title,
            text = text,
            notification = notificationFull,
            onGlobalVariable = { name, value ->
                mutableGlobalVariables[name] = value
                onGlobalVariable?.invoke(name, value)
            }
        )

        val values = LinkedHashMap<String, String>()
        values["notification"] = notificationFull
        values["title"] = title
        values["text"] = text
        values["subtext"] = subText
        values["app_package"] = packageName
        values["package"] = packageName
        values["app_name"] = appName
        values["timestamp"] = isoFormat.format(Date(postTime))
        values["timestamp_unix"] = postTime.toString()
        values["ticker"] = ticker
        values["big_text"] = bigText
        values["summary"] = summary
        localRegexVariables.forEach { (name, value) -> values["var_$name"] = value }
        mutableGlobalVariables.forEach { (name, value) -> values["global.$name"] = value }

        return substitute(template, values)
    }

    /* [Decision Log]
    - 목적: 템플릿의 {변수}를 한 번에 치환해, 치환된 값 안의 "{...}" 문자열이 다시 치환되지 않게 한다.
      기존 방식(변수마다 String.replace 반복)은 알림 제목이 "{text}"이면 그 부분이 본문으로 바뀌고,
      알림 내용에 "{global.이름}"이 있으면 전역 변수 값이 끼어드는 등 알림 내용에 따라 결과가 달라졌다.
    - 대안 분석:
      1) 기존 반복 replace 유지: 구현은 단순하지만 위 문제가 남는다.
      2) 치환값의 중괄호를 이스케이프: 서버가 받는 값 자체가 바뀐다.
      3) 템플릿을 한 번만 훑으며 {이름}을 표에서 찾아 바꾸기: 치환 결과는 다시 훑지 않으므로 값이 그대로 보존된다.
    - 선택 근거: 3). 기존 템플릿과 결과가 같고(모르는 {이름}은 그대로 둔다) 템플릿 길이에 비례해 한 번만 처리한다.
    */
    private fun substitute(template: String, values: Map<String, String>): String {
        return PLACEHOLDER.replace(template) { match ->
            values[match.groupValues[1]]?.let(::escapeJson) ?: match.value
        }
    }

    private fun extractRegexVariables(
        regexRules: List<RegexRule>,
        title: String,
        text: String,
        notification: String,
        onGlobalVariable: (String, String) -> Unit
    ): Map<String, String> {
        val localVariables = mutableMapOf<String, String>()

        regexRules.forEach ruleLoop@{ rule ->
            val pattern = rule.pattern.trim()
            if (pattern.isEmpty()) {
                return@ruleLoop
            }

            val source = when (rule.sourceField) {
                RegexSourceField.TITLE -> title
                RegexSourceField.TEXT -> text
                RegexSourceField.NOTIFICATION -> notification
            }

            val matchResult = try {
                val matcher = getPattern(pattern).matcher(source)
                if (matcher.find()) matcher else null
            } catch (e: PatternSyntaxException) {
                Log.w(TAG, "Invalid regex pattern skipped: ${e.description}")
                null
            }

            if (matchResult == null) {
                return@ruleLoop
            }

            rule.variables.forEach variableLoop@{ variable ->
                val variableName = variable.variableName.trim()
                if (variableName.isEmpty()) {
                    return@variableLoop
                }

                val captureGroup = variable.captureGroup
                if (captureGroup < 0 || captureGroup > matchResult.groupCount()) {
                    return@variableLoop
                }

                val value = matchResult.group(captureGroup).orEmpty()
                if (variable.isGlobal) {
                    onGlobalVariable(variableName, value)
                } else {
                    localVariables[variableName] = value
                }
            }
        }

        return localVariables
    }

    private fun getPattern(pattern: String): Pattern {
        synchronized(patternCache) {
            patternCache[pattern]?.let { return it }
        }
        val compiled = Pattern.compile(pattern)
        synchronized(patternCache) {
            patternCache[pattern] = compiled
        }
        return compiled
    }

    // JSON 문자열 규칙상 U+0000..U+001F 제어 문자는 모두 이스케이프해야 한다. 일부만 처리하면 알림에 섞인 제어 문자로 본문이 깨진 JSON이 된다.
    private fun escapeJson(value: String): String {
        val builder = StringBuilder(value.length + 16)
        value.forEach { c ->
            when (c) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> if (c < ' ') {
                    builder.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                } else {
                    builder.append(c)
                }
            }
        }
        return builder.toString()
    }
}
