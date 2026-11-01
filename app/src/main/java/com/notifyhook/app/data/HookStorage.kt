package com.notifyhook.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.notifyhook.app.model.BodySourceType
import com.notifyhook.app.model.ContentType
import com.notifyhook.app.model.Hook
import com.notifyhook.app.model.HookDelivery
import com.notifyhook.app.model.HookLog
import com.notifyhook.app.model.HookUrlValidator
import com.notifyhook.app.model.HttpHeader
import com.notifyhook.app.model.HttpHeaderMasking
import com.notifyhook.app.model.HttpHeaderValidator
import com.notifyhook.app.model.HttpMethod
import com.notifyhook.app.model.RegexRule
import com.notifyhook.app.model.RegexRuleValidator
import com.notifyhook.app.model.RegexSourceField
import com.notifyhook.app.model.RegexVariable
import java.util.UUID

class HookStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("notifyhook_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val presetGson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()

    companion object {
        private const val KEY_HOOKS = "hooks"
        private const val KEY_HOOKS_REVISION = "hooks_revision"
        private const val KEY_LOGS = "logs"
        private const val KEY_PENDING_DELIVERIES = "pending_deliveries"
        private const val MAX_LOGS = 200
        private const val PRESET_FORMAT = "notifyhook-preset"
        private const val PRESET_VERSION = 1

        @Volatile
        private var instance: HookStorage? = null

        fun getInstance(context: Context): HookStorage {
            return instance ?: synchronized(this) {
                instance ?: HookStorage(context.applicationContext).also { instance = it }
            }
        }
    }

    private data class HookPresetFile(
        val format: String? = null,
        val version: Int? = null,
        val exportedAt: Long? = null,
        val hook: RawHook? = null
    )

    private data class RawHook(
        val id: String? = null,
        val name: String? = null,
        val enabled: Boolean? = null,
        val targetPackages: List<String>? = null,
        val method: String? = null,
        val url: String? = null,
        val headers: List<RawHeader>? = null,
        val timeoutSeconds: Int? = null,
        val contentType: String? = null,
        val bodyTemplate: String? = null,
        val bodySourceType: String? = null,
        val bodyFilePath: String? = null,
        val regexRules: List<RawRegexRule>? = null,
        val createdAt: Long? = null
    )

    private data class RawHeader(
        val name: String? = null,
        val value: String? = null,
        val sensitive: Boolean? = null
    )

    private data class RawRegexRule(
        val pattern: String? = null,
        val sourceField: String? = null,
        val variables: List<RawRegexVariable>? = null
    )

    private data class RawRegexVariable(
        val captureGroup: Int? = null,
        val variableName: String? = null,
        val isGlobal: Boolean? = null
    )

    fun getHooks(): List<Hook> {
        synchronized(lock) {
            val json = prefs.getString(KEY_HOOKS, null) ?: return emptyList()
            val type = object : TypeToken<List<Hook>>() {}.type
            return try {
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun saveHook(hook: Hook): Boolean {
        synchronized(lock) {
            if (!RegexRuleValidator.arePatternsValid(hook.regexRules) ||
                !HttpHeaderValidator.areValid(hook.headers)
            ) {
                return false
            }
            val hooks = getHooksInternal().toMutableList()
            val index = hooks.indexOfFirst { it.id == hook.id }
            if (index >= 0) {
                hooks[index] = hook
            } else {
                hooks.add(hook)
            }
            return prefs.edit()
                .putString(KEY_HOOKS, gson.toJson(hooks))
                .putLong(KEY_HOOKS_REVISION, nextHooksRevisionInternal())
                .commit()
        }
    }

    fun deleteHook(hookId: String): Boolean {
        synchronized(lock) {
            val hooks = getHooksInternal().toMutableList()
            hooks.removeAll { it.id == hookId }
            val deliveries = getPendingDeliveriesInternal()
                .filterNot { it.hookId == hookId }
            return prefs.edit()
                .putString(KEY_HOOKS, gson.toJson(hooks))
                .putString(KEY_PENDING_DELIVERIES, gson.toJson(deliveries))
                .putLong(KEY_HOOKS_REVISION, nextHooksRevisionInternal())
                .commit()
        }
    }

    fun getHooksRevision(): Long {
        synchronized(lock) {
            return prefs.getLong(KEY_HOOKS_REVISION, 0L)
        }
    }

    fun getHook(hookId: String): Hook? {
        synchronized(lock) {
            return getHooksInternal().find { it.id == hookId }
        }
    }

    fun hasHook(hookId: String): Boolean {
        synchronized(lock) {
            return getHooksInternal().any { it.id == hookId }
        }
    }

    fun isBodyFileReferenced(uriString: String, excludingHookId: String? = null): Boolean {
        synchronized(lock) {
            return getHooksInternal().any { hook ->
                hook.bodySourceType == BodySourceType.FILE &&
                    hook.bodyFilePath == uriString &&
                    hook.id != excludingHookId
            }
        }
    }

    /**
     * @param includeSensitiveHeaderValues false이면 민감 헤더(HttpHeaderMasking.shouldMask)는 이름만 남기고 값을 비운다.
     *   프리셋 파일은 메신저·클라우드로 공유되기 쉬워 API 키가 함께 퍼지는 것을 기본적으로 막기 위함이다.
     */
    fun createPresetJson(hook: Hook, includeSensitiveHeaderValues: Boolean = false): String {
        val exportedHook = if (includeSensitiveHeaderValues) {
            hook
        } else {
            hook.copy(headers = hook.headers.map { if (HttpHeaderMasking.shouldMask(it)) it.copy(value = "") else it })
        }
        val preset = HookPresetFile(
            format = PRESET_FORMAT,
            version = PRESET_VERSION,
            exportedAt = System.currentTimeMillis(),
            hook = exportedHook.toRawHook()
        )
        return presetGson.toJson(preset)
    }

    fun importPreset(json: String): Hook? {
        synchronized(lock) {
            val preset = try {
                gson.fromJson(json, HookPresetFile::class.java)
            } catch (e: Exception) {
                return null
            }

            val importedHook = preset.toImportedHook() ?: return null
            return if (saveHook(importedHook)) importedHook else null
        }
    }

    fun addLog(log: HookLog) {
        synchronized(lock) {
            val logs = getLogsInternal().toMutableList()
            logs.add(0, log)
            if (logs.size > MAX_LOGS) {
                logs.subList(MAX_LOGS, logs.size).clear()
            }
            prefs.edit().putString(KEY_LOGS, gson.toJson(logs)).apply()
        }
    }

    fun savePendingDelivery(delivery: HookDelivery): Boolean {
        synchronized(lock) {
            val deliveries = getPendingDeliveriesInternal().toMutableList()
            upsertPendingDeliveryInternal(deliveries, delivery.boundedForStorage())
            return savePendingDeliveriesInternal(deliveries)
        }
    }

    fun savePendingDeliveryIfHookExists(delivery: HookDelivery): Boolean {
        synchronized(lock) {
            if (getHooksInternal().none { it.id == delivery.hookId }) {
                return false
            }
            val deliveries = getPendingDeliveriesInternal().toMutableList()
            upsertPendingDeliveryInternal(deliveries, delivery.boundedForStorage())
            return savePendingDeliveriesInternal(deliveries)
        }
    }

    fun removePendingDelivery(deliveryId: String): Boolean {
        synchronized(lock) {
            val deliveries = getPendingDeliveriesInternal().toMutableList()
            val removed = deliveries.removeAll { it.id == deliveryId }
            if (!removed) return true
            return prefs.edit()
                .putString(KEY_PENDING_DELIVERIES, gson.toJson(deliveries))
                .commit()
        }
    }

    fun getPendingDeliveries(): List<HookDelivery> {
        synchronized(lock) {
            return getPendingDeliveriesInternal()
        }
    }

    fun getDuePendingDeliveries(nowMillis: Long, limit: Int): List<HookDelivery> {
        synchronized(lock) {
            return getPendingDeliveriesInternal()
                .filter { it.nextAttemptAt <= nowMillis }
                .sortedBy { it.createdAt }
                .take(limit.coerceAtLeast(1))
        }
    }

    fun nextPendingDeliveryAttemptAt(): Long? {
        synchronized(lock) {
            return getPendingDeliveriesInternal().minOfOrNull { it.nextAttemptAt }
        }
    }

    fun getLogs(): List<HookLog> {
        synchronized(lock) {
            return getLogsInternal()
        }
    }

    fun clearLogs() {
        synchronized(lock) {
            prefs.edit().remove(KEY_LOGS).commit()
        }
    }

    // Internal methods (must be called within synchronized block)
    private fun getHooksInternal(): List<Hook> {
        val json = prefs.getString(KEY_HOOKS, null) ?: return emptyList()
        val type = object : TypeToken<List<Hook>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun nextHooksRevisionInternal(): Long {
        return prefs.getLong(KEY_HOOKS_REVISION, 0L) + 1L
    }

    private fun upsertPendingDeliveryInternal(
        deliveries: MutableList<HookDelivery>,
        delivery: HookDelivery
    ) {
        val index = deliveries.indexOfFirst { it.id == delivery.id }
        if (index >= 0) {
            deliveries[index] = delivery
        } else {
            deliveries.add(delivery)
        }
    }

    private fun savePendingDeliveriesInternal(deliveries: List<HookDelivery>): Boolean {
        return prefs.edit()
            .putString(KEY_PENDING_DELIVERIES, gson.toJson(deliveries))
            .commit()
    }

    private fun getLogsInternal(): List<HookLog> {
        val json = prefs.getString(KEY_LOGS, null) ?: return emptyList()
        val type = object : TypeToken<List<HookLog>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getPendingDeliveriesInternal(): List<HookDelivery> {
        val json = prefs.getString(KEY_PENDING_DELIVERIES, null) ?: return emptyList()
        val type = object : TypeToken<List<HookDelivery>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Hook.toRawHook(): RawHook {
        return RawHook(
            id = id,
            name = name,
            enabled = enabled,
            targetPackages = targetPackages,
            method = method.name,
            url = url,
            headers = headers.map { header ->
                RawHeader(
                    name = header.name,
                    value = header.value,
                    sensitive = header.sensitive
                )
            },
            timeoutSeconds = timeoutSeconds,
            contentType = contentType.name,
            bodyTemplate = bodyTemplate,
            bodySourceType = bodySourceType.name,
            bodyFilePath = bodyFilePath,
            regexRules = regexRules.map { rule ->
                RawRegexRule(
                    pattern = rule.pattern,
                    sourceField = rule.sourceField.name,
                    variables = rule.variables.map { variable ->
                        RawRegexVariable(
                            captureGroup = variable.captureGroup,
                            variableName = variable.variableName,
                            isGlobal = variable.isGlobal
                        )
                    }
                )
            },
            createdAt = createdAt
        )
    }

    private fun HookPresetFile?.toImportedHook(): Hook? {
        if (this?.format != PRESET_FORMAT || this.version != PRESET_VERSION) {
            return null
        }
        return hook?.toHook()
    }

    private fun RawHook.toHook(): Hook? {
        val parsedMethod = method?.let { parseEnum<HttpMethod>(it) } ?: return null
        val parsedContentType = contentType?.let { parseEnum<ContentType>(it) } ?: return null
        val parsedBodySourceType = bodySourceType?.let { parseEnum<BodySourceType>(it) } ?: return null
        val parsedUrl = url.orEmpty().trim()
        if (!HookUrlValidator.isSupported(parsedUrl)) {
            return null
        }
        val parsedRegexRules = regexRules.orEmpty().map { rawRule ->
            rawRule.toRegexRule() ?: return null
        }
        if (!RegexRuleValidator.arePatternsValid(parsedRegexRules)) {
            return null
        }
        // 헤더가 없는 이전 프리셋(필드 없음)도 그대로 가져올 수 있도록 null은 빈 목록으로 본다.
        val parsedHeaders = headers.orEmpty().map { rawHeader ->
            HttpHeader(
                name = rawHeader.name.orEmpty().trim(),
                value = rawHeader.value.orEmpty().trim(),
                sensitive = rawHeader.sensitive ?: false
            )
        }
        if (!HttpHeaderValidator.areValid(parsedHeaders)) {
            return null
        }

        return Hook(
            id = UUID.randomUUID().toString(),
            name = name.orEmpty(),
            // 키 값을 빼고 내보낸 프리셋은 키 없이 전송되어 401만 쌓이므로, 값을 채울 때까지 꺼진 상태로 가져온다.
            enabled = (enabled ?: true) && !HttpHeaderMasking.hasMissingSecretValue(parsedHeaders),
            targetPackages = targetPackages.orEmpty(),
            method = parsedMethod,
            url = parsedUrl,
            headers = parsedHeaders,
            timeoutSeconds = (timeoutSeconds ?: 30).coerceIn(1, 120),
            contentType = parsedContentType,
            bodyTemplate = bodyTemplate.orEmpty(),
            bodySourceType = parsedBodySourceType,
            bodyFilePath = bodyFilePath,
            regexRules = parsedRegexRules,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun RawRegexRule.toRegexRule(): RegexRule? {
        val parsedSourceField = sourceField?.let { parseEnum<RegexSourceField>(it) } ?: return null
        val parsedVariables = variables.orEmpty().map { rawVariable -> rawVariable.toRegexVariable() }

        return RegexRule(
            pattern = pattern.orEmpty(),
            sourceField = parsedSourceField,
            variables = parsedVariables
        )
    }

    private fun RawRegexVariable.toRegexVariable(): RegexVariable {
        return RegexVariable(
            captureGroup = (captureGroup ?: 1).coerceAtLeast(1),
            variableName = variableName.orEmpty(),
            isGlobal = isGlobal ?: false
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String): T? {
        return enumValues<T>().find { it.name == value }
    }
}
