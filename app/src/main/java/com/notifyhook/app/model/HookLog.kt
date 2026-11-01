package com.notifyhook.app.model

// HookDelivery와 같은 이유로 모든 인자에 기본값을 둔다(Gson 무인자 생성자 경로 유지).
data class HookLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val hookId: String = "",
    val hookName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val notificationApp: String = "",
    val notificationTitle: String = "",
    val notificationText: String = "",
    val requestUrl: String = "",
    val requestMethod: String = "",
    // 민감 헤더 값은 HttpHeaderMasking으로 가린 "이름: 값" 줄 목록
    val requestHeaders: String = "",
    val requestBody: String = "",
    val responseCode: Int = -1,
    val responseBody: String = "",
    val success: Boolean = false,
    val errorMessage: String = ""
)
