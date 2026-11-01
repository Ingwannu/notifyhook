package com.notifyhook.app.model

/**
 * 훅 요청에 함께 보내는 사용자 지정 HTTP 헤더.
 *
 * [sensitive]가 true이면 API 키/토큰처럼 노출되면 안 되는 값으로 보고
 * 편집 화면에서 가리고, 전송 로그에는 값을 남기지 않으며, 프리셋 내보내기 시 제외를 제안한다.
 */
data class HttpHeader(
    val name: String = "",
    val value: String = "",
    val sensitive: Boolean = false
)
