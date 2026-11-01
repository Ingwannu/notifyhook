package com.notifyhook.app.model

import java.util.UUID

data class Hook(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var enabled: Boolean = true,
    var targetPackages: List<String> = emptyList(),
    var method: HttpMethod = HttpMethod.POST,
    var url: String = "",
    var headers: List<HttpHeader> = emptyList(),
    var timeoutSeconds: Int = 30,
    var contentType: ContentType = ContentType.APPLICATION_JSON,
    var bodyTemplate: String = "",
    var bodySourceType: BodySourceType = BodySourceType.TEXT,
    var bodyFilePath: String? = null,
    var regexRules: List<RegexRule> = emptyList(),
    var createdAt: Long = System.currentTimeMillis()
)

enum class HttpMethod {
    POST, PUT
}

enum class ContentType(val value: String, val displayName: String) {
    APPLICATION_JSON("application/json", "application/json"),
    APPLICATION_XML("application/xml", "application/xml"),
    TEXT_PLAIN("text/plain", "text/plain"),
    APPLICATION_FORM("application/x-www-form-urlencoded", "application/x-www-form-urlencoded"),
    TEXT_HTML("text/html", "text/html"),
    APPLICATION_OCTET("application/octet-stream", "application/octet-stream");

    companion object {
        fun fromValue(value: String): ContentType {
            return entries.find { it.value == value } ?: APPLICATION_JSON
        }
    }
}

enum class BodySourceType {
    TEXT, FILE
}
