package com.notifyhook.app.service

import com.notifyhook.app.model.HookDelivery
import com.notifyhook.app.model.HttpHeader
import com.notifyhook.app.model.HttpMethod
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HookDeliverySenderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun delivery(
        headers: List<HttpHeader> = emptyList(),
        method: HttpMethod = HttpMethod.POST,
        path: String = "/hook"
    ) = HookDelivery(
        hookId = "h1",
        hookName = "Hook",
        requestUrl = server.url(path).toString(),
        requestMethod = method.name,
        contentType = "application/json",
        requestHeaders = headers,
        requestBody = """{"title":"hi"}""",
        timeoutSeconds = 5
    )

    @Test
    fun sendsJsonBodyWithCustomHeaders() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val result = HookDeliverySender.send(
            delivery(
                headers = listOf(
                    HttpHeader("Authorization", "Bearer token-123", sensitive = true),
                    HttpHeader("X-API-Key", "key-456", sensitive = true),
                    HttpHeader("X-Multi", "a"),
                    HttpHeader("X-Multi", "b")
                )
            )
        )

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(result.success)
        assertEquals("ok", result.responseBody)
        assertEquals("POST", request.method)
        assertEquals("""{"title":"hi"}""", request.body.readUtf8())
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("Bearer token-123", request.getHeader("Authorization"))
        assertEquals("key-456", request.getHeader("X-API-Key"))
        assertEquals(listOf("a", "b"), request.headers.values("X-Multi"))
    }

    @Test
    fun usesPutWhenConfigured() {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = HookDeliverySender.send(delivery(method = HttpMethod.PUT))

        assertTrue(result.success)
        assertEquals("PUT", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun doesNotFollowRedirectsSoBodyAndKeysAreNotSentElsewhere() {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/moved").toString()))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = HookDeliverySender.send(delivery(headers = listOf(HttpHeader("X-API-Key", "k"))))

        assertFalse(result.success)
        assertFalse(result.retryable)
        assertEquals(301, result.responseCode)
        assertTrue(result.errorMessage.contains("/moved"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun classifiesClientErrorsAsPermanentAndServerErrorsAsTransient() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))

        val unauthorized = HookDeliverySender.send(delivery())
        val unavailable = HookDeliverySender.send(delivery())
        val throttled = HookDeliverySender.send(delivery())

        assertEquals("HTTP 401", unauthorized.errorMessage)
        assertFalse(unauthorized.retryable)
        assertTrue(unavailable.retryable)
        assertTrue(throttled.retryable)
    }

    @Test
    fun networkFailuresAreRetryable() {
        val unreachable = delivery()
        server.shutdown()

        val result = HookDeliverySender.send(unreachable)

        assertFalse(result.success)
        assertTrue(result.retryable)
        assertEquals(-1, result.responseCode)
    }

    @Test
    fun malformedRequestsAreNotRetried() {
        val result = HookDeliverySender.send(delivery().copy(requestMethod = "DELETE"))

        assertFalse(result.success)
        assertFalse(result.retryable)
        assertEquals(0, server.requestCount)
    }
}
