package com.notifyhook.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHeaderValidatorTest {

    @Test
    fun acceptsCommonAuthHeaders() {
        val headers = listOf(
            HttpHeader("Authorization", "Bearer abc.DEF-123_~+/="),
            HttpHeader("X-API-Key", "k3y"),
            HttpHeader("X-Empty", "")
        )

        assertTrue(HttpHeaderValidator.areValid(headers))
    }

    @Test
    fun rejectsNamesThatAreNotHttpTokens() {
        assertEquals(HttpHeaderValidator.Problem.EMPTY_NAME, HttpHeaderValidator.problemOf(HttpHeader("", "v")))
        assertEquals(HttpHeaderValidator.Problem.INVALID_NAME, HttpHeaderValidator.problemOf(HttpHeader("X Key", "v")))
        assertEquals(HttpHeaderValidator.Problem.INVALID_NAME, HttpHeaderValidator.problemOf(HttpHeader("X:Key", "v")))
        assertEquals(HttpHeaderValidator.Problem.INVALID_NAME, HttpHeaderValidator.problemOf(HttpHeader("키", "v")))
    }

    @Test
    fun rejectsHeadersManagedByTheRequestBuilder() {
        assertEquals(
            HttpHeaderValidator.Problem.RESERVED_NAME,
            HttpHeaderValidator.problemOf(HttpHeader("content-type", "text/plain"))
        )
        assertEquals(
            HttpHeaderValidator.Problem.RESERVED_NAME,
            HttpHeaderValidator.problemOf(HttpHeader("Content-Length", "1"))
        )
        assertEquals(
            HttpHeaderValidator.Problem.RESERVED_NAME,
            HttpHeaderValidator.problemOf(HttpHeader("Transfer-Encoding", "chunked"))
        )
    }

    @Test
    fun rejectsValuesThatOkHttpWouldRefuseToSend() {
        assertEquals(HttpHeaderValidator.Problem.INVALID_VALUE, HttpHeaderValidator.problemOf(HttpHeader("X-A", "한글")))
        assertEquals(HttpHeaderValidator.Problem.INVALID_VALUE, HttpHeaderValidator.problemOf(HttpHeader("X-A", "a\r\nX-Injected: 1")))
        assertNull(HttpHeaderValidator.problemOf(HttpHeader("X-A", "tab\tok")))
    }

    @Test
    fun enforcesLengthAndCountLimits() {
        assertEquals(
            HttpHeaderValidator.Problem.NAME_TOO_LONG,
            HttpHeaderValidator.problemOf(HttpHeader("X".repeat(HttpHeaderValidator.MAX_NAME_LENGTH + 1), "v"))
        )
        assertEquals(
            HttpHeaderValidator.Problem.VALUE_TOO_LONG,
            HttpHeaderValidator.problemOf(HttpHeader("X-A", "v".repeat(HttpHeaderValidator.MAX_VALUE_LENGTH + 1)))
        )
        val tooMany = List(HttpHeaderValidator.MAX_HEADERS + 1) { HttpHeader("X-$it", "v") }
        assertEquals(HttpHeaderValidator.Problem.TOO_MANY, HttpHeaderValidator.firstIssue(tooMany)?.problem)
    }

    @Test
    fun reportsTheIndexOfTheFirstInvalidHeader() {
        val headers = listOf(HttpHeader("X-Ok", "1"), HttpHeader("Bad Name", "2"), HttpHeader("", "3"))

        assertEquals(
            HttpHeaderValidator.Issue(1, HttpHeaderValidator.Problem.INVALID_NAME),
            HttpHeaderValidator.firstIssue(headers)
        )
    }
}
