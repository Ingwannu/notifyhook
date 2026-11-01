package com.notifyhook.app.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 업데이트 전 버전이 SharedPreferences에 저장한 JSON(새 필드 없음)을 새 모델로 읽어도
 * non-null 필드가 null이 되지 않는지 확인한다. HookStorage와 같은 Gson 기본 설정을 쓴다.
 */
class StoredModelCompatibilityTest {

    private val gson = Gson()

    @Test
    fun pendingDeliveryStoredBeforeHeadersExistedReadsAsNoHeaders() {
        val legacyJson = """[{"id":"d1","hookId":"h1","hookName":"Hook","createdAt":1,"requestUrl":"https://example.com",
            "requestMethod":"POST","contentType":"application/json","requestBody":"{}","timeoutSeconds":30,
            "attemptCount":2,"nextAttemptAt":5,"lastAttemptAt":4,"lastError":"HTTP 503"}]"""
        val type = object : TypeToken<List<HookDelivery>>() {}.type

        val delivery = gson.fromJson<List<HookDelivery>>(legacyJson, type).single()

        assertEquals(emptyList<HttpHeader>(), delivery.requestHeaders)
        assertEquals("HTTP 503", delivery.lastError)
        assertEquals(2, delivery.attemptCount)
    }

    @Test
    fun hookStoredBeforeHeadersExistedReadsAsNoHeaders() {
        val legacyJson = """[{"id":"h1","name":"Hook","enabled":true,"targetPackages":[],"method":"POST",
            "url":"https://example.com","timeoutSeconds":30,"contentType":"APPLICATION_JSON","bodyTemplate":"{}",
            "bodySourceType":"TEXT","regexRules":[],"createdAt":1}]"""
        val type = object : TypeToken<List<Hook>>() {}.type

        val hook = gson.fromJson<List<Hook>>(legacyJson, type).single()

        assertEquals(emptyList<HttpHeader>(), hook.headers)
    }

    @Test
    fun logStoredBeforeHeadersExistedReadsAsEmptyHeaderSummary() {
        val legacyJson = """[{"id":"l1","hookId":"h1","hookName":"Hook","timestamp":1,"success":true,"responseCode":200}]"""
        val type = object : TypeToken<List<HookLog>>() {}.type

        val log = gson.fromJson<List<HookLog>>(legacyJson, type).single()

        assertEquals("", log.requestHeaders)
        assertEquals("", log.errorMessage)
    }

    @Test
    fun deliveryLogMasksSecretHeaderValues() {
        val delivery = HookDelivery(
            hookId = "h1",
            hookName = "Hook",
            requestHeaders = listOf(HttpHeader("X-API-Key", "super-secret"), HttpHeader("X-Trace", "t1"))
        )

        assertEquals("X-API-Key: ********\nX-Trace: t1", delivery.toLog(success = true).requestHeaders)
    }
}
