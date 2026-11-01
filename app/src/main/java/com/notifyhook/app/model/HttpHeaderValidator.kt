package com.notifyhook.app.model

/* [Decision Log]
- 목적: 사용자 지정 헤더를 저장·프리셋 가져오기·재시도 전송 전에 같은 규칙으로 검증한다.
  저장에 성공한 헤더가 전송 시점에 OkHttp 예외를 일으키면 네트워크 오류로 분류되어 재시도 큐에 계속 남기 때문이다.
- 대안 분석:
  1) OkHttp Headers.Builder에 실제로 넣어 보고 예외를 잡기: 규칙이 라이브러리 내부 메시지에 묶여 UI에서 원인을 구분해 안내할 수 없다.
  2) OkHttp 문자 규칙(이름 0x21..0x7E, 값 탭/0x20..0x7E)만 그대로 복제: 이름에 ':' 같은 구분자가 들어가도 통과해 서버마다 다르게 해석된다.
  3) 이름은 RFC 9110 token, 값은 OkHttp 규칙, 프레이밍을 깨는 관리 헤더 차단: OkHttp보다 같거나 엄격하므로 "저장 가능 = 전송 가능"이 보장된다.
- 선택 근거: 3). 비ASCII 값(한글 등)은 OkHttp가 전송 시 거부하므로 저장 단계에서 알려 주는 편이 낫다.
  Content-Type은 화면의 Content-Type 선택 항목과 값이 둘로 갈라지지 않도록 차단하고,
  Content-Length/Transfer-Encoding은 OkHttp가 본문 길이로 계산하는 값이라 덮어쓰면 요청이 깨지므로 차단한다.
*/
object HttpHeaderValidator {
    const val MAX_HEADERS = 50
    const val MAX_NAME_LENGTH = 256
    const val MAX_VALUE_LENGTH = 8 * 1024

    private val RESERVED_NAMES = setOf("content-type", "content-length", "transfer-encoding")
    private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

    enum class Problem {
        TOO_MANY,
        EMPTY_NAME,
        NAME_TOO_LONG,
        INVALID_NAME,
        RESERVED_NAME,
        VALUE_TOO_LONG,
        INVALID_VALUE
    }

    data class Issue(
        val index: Int,
        val problem: Problem
    )

    fun areValid(headers: List<HttpHeader>): Boolean = firstIssue(headers) == null

    fun firstIssue(headers: List<HttpHeader>): Issue? {
        if (headers.size > MAX_HEADERS) {
            return Issue(MAX_HEADERS, Problem.TOO_MANY)
        }
        headers.forEachIndexed { index, header ->
            problemOf(header)?.let { return Issue(index, it) }
        }
        return null
    }

    fun problemOf(header: HttpHeader): Problem? {
        val name = header.name
        return when {
            name.isEmpty() -> Problem.EMPTY_NAME
            name.length > MAX_NAME_LENGTH -> Problem.NAME_TOO_LONG
            !name.all(::isTokenChar) -> Problem.INVALID_NAME
            name.lowercase() in RESERVED_NAMES -> Problem.RESERVED_NAME
            header.value.length > MAX_VALUE_LENGTH -> Problem.VALUE_TOO_LONG
            !header.value.all(::isValueChar) -> Problem.INVALID_VALUE
            else -> null
        }
    }

    private fun isTokenChar(c: Char): Boolean {
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || TOKEN_SYMBOLS.indexOf(c) >= 0
    }

    private fun isValueChar(c: Char): Boolean {
        return c == '\t' || c in ' '..'~'
    }
}
