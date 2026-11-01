package com.notifyhook.app.model

import java.util.UUID

/*
 * 모든 생성자 인자에 기본값을 두어 Kotlin이 무인자 생성자를 만들게 한다.
 * Gson은 무인자 생성자가 없으면 Unsafe로 객체를 만들어, 저장된 JSON에 없는 필드(예: 새로 추가한 requestHeaders)를
 * non-null 타입이어도 null로 남긴다. 업데이트 전에 쌓인 재시도 큐를 읽을 때 NPE가 나지 않게 하기 위한 제약이다.
 */
data class HookDelivery(
    val id: String = UUID.randomUUID().toString(),
    val hookId: String = "",
    val hookName: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val notificationApp: String = "",
    val notificationTitle: String = "",
    val notificationText: String = "",
    val requestUrl: String = "",
    val requestMethod: String = "",
    val contentType: String = "",
    val requestHeaders: List<HttpHeader> = emptyList(),
    val requestBody: String = "",
    val timeoutSeconds: Int = 30,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastError: String = ""
) {
    fun boundedForStorage(): HookDelivery {
        return copy(
            notificationApp = notificationApp.truncateForLog(METADATA_MAX_CHARS),
            notificationTitle = notificationTitle.truncateForLog(METADATA_MAX_CHARS),
            notificationText = notificationText.truncateForLog(METADATA_MAX_CHARS)
        )
    }

    fun toLog(
        success: Boolean,
        responseCode: Int = -1,
        responseBody: String = "",
        errorMessage: String = ""
    ): HookLog {
        return HookLog(
            hookId = hookId,
            hookName = hookName,
            notificationApp = notificationApp.truncateForLog(METADATA_MAX_CHARS),
            notificationTitle = notificationTitle.truncateForLog(METADATA_MAX_CHARS),
            notificationText = notificationText.truncateForLog(METADATA_MAX_CHARS),
            requestUrl = requestUrl,
            requestMethod = requestMethod,
            requestHeaders = HttpHeaderMasking.summary(requestHeaders),
            requestBody = requestBody.truncateForLog(LOG_REQUEST_BODY_MAX_CHARS),
            responseCode = responseCode,
            responseBody = responseBody,
            success = success,
            errorMessage = errorMessage
        )
    }

    private fun String.truncateForLog(maxChars: Int): String {
        return if (length <= maxChars) {
            this
        } else {
            take(maxChars) + "\n...[truncated]"
        }
    }

    private companion object {
        private const val METADATA_MAX_CHARS = 1000
        private const val LOG_REQUEST_BODY_MAX_CHARS = 4000
    }
}
