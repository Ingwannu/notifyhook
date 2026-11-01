package com.notifyhook.app.service

import com.notifyhook.app.model.HookDelivery
import com.notifyhook.app.model.HttpMethod
import com.notifyhook.app.util.TextReadLimiter
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/* [Decision Log]
- 목적: 실제 알림 전송, 재시도 전송, 편집 화면의 "테스트" 전송이 모두 같은 요청(메서드, Content-Type, 사용자 헤더)을 만들게 한다.
  기존에는 테스트 화면이 OkHttp 요청을 따로 만들어, 실제 전송과 테스트 결과가 달라질 수 있었다.
- 대안 분석:
  1) 화면마다 요청 생성 코드를 유지: 헤더 기능을 두 곳에 똑같이 구현해야 하고 어긋나기 쉽다.
  2) 요청 생성(buildRequest)과 실행(execute)을 분리해 공유: 테스트는 Call을 직접 받아 코루틴 취소에 연결할 수 있다.
- 선택 근거: 2). 단일 경로라 테스트 성공이 곧 실제 전송 성공을 뜻한다.

리다이렉트를 따라가지 않는 이유:
  OkHttp는 301/302/303에서 POST를 본문 없는 GET으로 바꿔 따라간다. 그러면 서버는 알림 내용을 받지 못했는데 로그에는 "성공"이 찍힌다.
  또 OkHttp는 다른 호스트로 리다이렉트될 때 Authorization만 지우고 X-API-Key 같은 사용자 헤더는 그대로 보내 키가 제3자에게 넘어갈 수 있다.
  그래서 3xx는 실패로 기록하고 Location을 함께 보여 줘 사용자가 최종 URL로 고치게 한다.
*/
internal object HookDeliverySender {
    private const val RESPONSE_BODY_MAX_CHARS = 1000

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val clientCache = ConcurrentHashMap<Int, OkHttpClient>()

    data class SendResult(
        val success: Boolean,
        val responseCode: Int = -1,
        val responseBody: String = "",
        val errorMessage: String = "",
        val retryable: Boolean = true
    )

    fun send(delivery: HookDelivery): SendResult {
        val call = try {
            newCall(delivery)
        } catch (e: IllegalArgumentException) {
            // URL/헤더 형식 오류는 다시 보내도 똑같이 실패한다.
            return SendResult(success = false, errorMessage = e.message ?: "Invalid request", retryable = false)
        }
        return execute(call)
    }

    fun newCall(delivery: HookDelivery): Call {
        val timeoutSeconds = delivery.timeoutSeconds.coerceIn(1, 120)
        return getClientForTimeout(timeoutSeconds).newCall(buildRequest(delivery))
    }

    fun buildRequest(delivery: HookDelivery): Request {
        val mediaType = delivery.contentType.toMediaTypeOrNull()
        val requestBody = delivery.requestBody.toRequestBody(mediaType)
        val requestBuilder = Request.Builder()
            .url(delivery.requestUrl)
            .header("Content-Type", delivery.contentType)

        delivery.requestHeaders.forEach { header ->
            requestBuilder.addHeader(header.name, header.value)
        }

        when (delivery.requestMethod) {
            HttpMethod.POST.name -> requestBuilder.post(requestBody)
            HttpMethod.PUT.name -> requestBuilder.put(requestBody)
            else -> throw IllegalArgumentException("Unsupported method: ${delivery.requestMethod}")
        }
        return requestBuilder.build()
    }

    fun execute(call: Call, responseBodyMaxChars: Int = RESPONSE_BODY_MAX_CHARS): SendResult {
        return try {
            call.execute().use { response ->
                val success = response.isSuccessful
                val errorMessage = when {
                    success -> ""
                    response.isRedirect -> {
                        val location = response.header("Location").orEmpty().ifBlank { "(no Location)" }
                        "HTTP ${response.code} redirect to $location; redirects are not followed, update the webhook URL"
                    }
                    else -> "HTTP ${response.code}"
                }
                SendResult(
                    success = success,
                    responseCode = response.code,
                    responseBody = response.body?.charStream()?.use { reader ->
                        TextReadLimiter.readPrefix(reader, responseBodyMaxChars)
                    }.orEmpty(),
                    errorMessage = errorMessage,
                    retryable = !success && DeliveryRetryPolicy.isRetryableStatus(response.code)
                )
            }
        } catch (e: Exception) {
            SendResult(
                success = false,
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    private fun getClientForTimeout(timeoutSeconds: Int): OkHttpClient {
        return clientCache.getOrPut(timeoutSeconds) {
            baseClient.newBuilder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
        }
    }
}
