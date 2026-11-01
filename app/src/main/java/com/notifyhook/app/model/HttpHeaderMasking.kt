package com.notifyhook.app.model

/* [Decision Log]
- 목적: 전송 로그·테스트 결과에 헤더를 보여 주되 API 키/토큰 값은 남기지 않는다.
  로그는 화면 표시, 클립보드 복사, 사용자 공유로 쉽게 밖으로 나가는 경로다.
- 대안 분석:
  1) 로그에 헤더를 아예 남기지 않기: 가장 안전하지만 "헤더가 실제로 붙었는지" 확인할 수 없어 디버깅이 어렵다.
  2) 사용자가 켠 sensitive 플래그만 보고 가리기: 사용자가 플래그를 깜빡하면 키가 그대로 노출된다.
  3) sensitive 플래그 + 이름 휴리스틱(authorization, key, token 등)으로 가리고 이름은 그대로 표시: 디버깅 정보는 남기고 실수로 인한 노출은 줄인다.
- 선택 근거: 3). 휴리스틱이 민감하지 않은 헤더까지 가리는 오탐은 디버깅이 조금 불편해질 뿐이지만,
  미탐은 비밀 유출이므로 넓게 잡는다. 값 길이도 노출하지 않도록 고정 마스크를 쓴다.
*/
object HttpHeaderMasking {
    const val MASK = "********"

    private val SENSITIVE_NAME_HINTS = listOf(
        "auth", "key", "token", "secret", "password", "passwd",
        "cookie", "session", "signature", "credential"
    )

    fun looksSensitive(name: String): Boolean {
        val lower = name.lowercase()
        return SENSITIVE_NAME_HINTS.any { lower.contains(it) }
    }

    fun shouldMask(header: HttpHeader): Boolean = header.sensitive || looksSensitive(header.name)

    fun maskedValue(header: HttpHeader): String {
        return if (shouldMask(header) && header.value.isNotEmpty()) MASK else header.value
    }

    /** 키 값을 빼고 내보낸 프리셋처럼, 민감 헤더인데 값이 비어 있는 경우. */
    fun hasMissingSecretValue(headers: List<HttpHeader>): Boolean {
        return headers.any { shouldMask(it) && it.value.isBlank() }
    }

    fun summary(headers: List<HttpHeader>): String {
        return headers.joinToString("\n") { "${it.name}: ${maskedValue(it)}" }
    }
}
