package com.notifyhook.app.service

import com.notifyhook.app.model.Hook
import com.notifyhook.app.model.HookDelivery
import java.util.concurrent.TimeUnit
import kotlin.math.min

/* [Decision Log]
- 목적: 재시도해도 결과가 달라지지 않는 실패를 큐에서 빼고, 서버가 오래 죽어 있을 때 큐와 포그라운드 서비스가 무기한 유지되지 않게 한다.
  기존에는 모든 실패를 15분 간격으로 끝없이 재시도해, 잘못된 API 키(401)나 잘못된 경로(404)도 영원히 재전송되었다.
- 대안 분석:
  1) 기존처럼 모든 실패 재시도: 일시 장애에는 강하지만 설정 오류가 영구 재시도되고 서버에 불필요한 부하를 준다.
  2) 최대 시도 횟수 제한: 백오프 간격이 바뀌면 실제 보장 기간도 바뀌어 사용자에게 설명하기 어렵다.
  3) 상태 코드 분류(408/425/429/5xx와 네트워크 오류만 재시도) + 생성 시점 기준 재시도 기간(24시간) 제한: 일시 장애만 재시도하고 기간이 명확하다.
- 선택 근거: 3). 4xx는 요청 자체(키, 경로, 본문)를 고쳐야 성공하므로 같은 요청을 다시 보내도 결과가 같다.
  408(요청 시간 초과)/425(Too Early)/429(요청 과다)는 서버가 나중에 다시 보내라고 알려 주는 코드라 재시도한다.
  3xx는 전송 클라이언트가 리다이렉트를 따라가지 않으므로(HookDeliverySender 참고) URL을 고쳐야 하는 설정 오류로 본다.
  24시간은 휴대폰 재부팅, 서버 점검 같은 하루 단위 장애는 버티면서 오래된 알림이 뒤늦게 쏟아지지 않는 선이다.
*/
internal object DeliveryRetryPolicy {
    private const val BASE_RETRY_DELAY_SECONDS = 10L
    private const val MAX_RETRY_DELAY_MINUTES = 15L
    val RETRY_WINDOW_MILLIS: Long = TimeUnit.HOURS.toMillis(24)

    fun initialNextAttemptAtMillis(nowMillis: Long, hook: Hook): Long {
        return nowMillis + HookExecutionPolicy.wakeLockTimeoutMillis(listOf(hook))
    }

    fun failureNextAttemptAtMillis(nowMillis: Long, previousAttemptCount: Int): Long {
        val safeAttempt = previousAttemptCount.coerceAtLeast(0)
        val multiplier = 1L shl min(safeAttempt, 7)
        val delayMillis = TimeUnit.SECONDS.toMillis(BASE_RETRY_DELAY_SECONDS * multiplier)
            .coerceAtMost(TimeUnit.MINUTES.toMillis(MAX_RETRY_DELAY_MINUTES))
        return nowMillis + delayMillis
    }

    fun isRetryableStatus(code: Int): Boolean {
        return code == 408 || code == 425 || code == 429 || code >= 500
    }

    fun isWithinRetryWindow(createdAtMillis: Long, nowMillis: Long): Boolean {
        return nowMillis - createdAtMillis < RETRY_WINDOW_MILLIS
    }

    /** 재시도하지 않을 실패라면 로그에 붙일 사유를, 재시도할 실패라면 null을 돌려준다. */
    fun noRetryReason(result: HookDeliverySender.SendResult, createdAtMillis: Long, nowMillis: Long): String? {
        return when {
            !result.retryable -> "not retried: request must be fixed"
            !isWithinRetryWindow(createdAtMillis, nowMillis) -> "not retried: 24h retry window exceeded"
            else -> null
        }
    }
}

internal fun HookDelivery.failedAttempt(errorMessage: String, nowMillis: Long): HookDelivery {
    return copy(
        attemptCount = attemptCount + 1,
        lastAttemptAt = nowMillis,
        lastError = errorMessage,
        nextAttemptAt = DeliveryRetryPolicy.failureNextAttemptAtMillis(nowMillis, attemptCount)
    )
}
