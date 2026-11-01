package com.notifyhook.app.service

import com.notifyhook.app.model.HookDelivery
import com.notifyhook.app.model.HookUrlValidator
import com.notifyhook.app.model.HttpHeader
import com.notifyhook.app.model.HttpHeaderValidator
import com.notifyhook.app.model.HttpMethod

internal object HookDeliveryRequestValidator {
    fun nonRetryableErrorMessage(delivery: HookDelivery): String? {
        return requestProblem(delivery.requestUrl, delivery.requestMethod, delivery.requestHeaders)
            ?.let { "$it; retry skipped" }
    }

    /** 요청을 만들 수 없는 설정이면 사유("Invalid webhook ...")를 돌려준다. 즉시 전송과 재시도 전송이 같은 규칙을 쓴다. */
    fun requestProblem(url: String, method: String, headers: List<HttpHeader>): String? {
        if (!HookUrlValidator.isSupported(url)) {
            return "Invalid webhook URL"
        }
        if (method != HttpMethod.POST.name && method != HttpMethod.PUT.name) {
            return "Invalid webhook method"
        }
        if (!HttpHeaderValidator.areValid(headers)) {
            return "Invalid webhook header"
        }
        return null
    }
}
