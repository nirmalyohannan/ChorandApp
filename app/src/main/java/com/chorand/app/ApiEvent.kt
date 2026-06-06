package com.chorand.app

/**
 * Represents a single intercepted network event (request, response, or error)
 * captured from the WebView's JavaScript bridge.
 */
data class ApiEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val eventId: String = java.util.UUID.randomUUID().toString(),
    val type: String,                           // "request" | "response" | "error"
    val url: String,
    val method: String? = null,
    val requestHeaders: Map<String, String>? = null,
    val requestBody: String? = null,
    val status: Int? = null,
    val statusText: String? = null,
    val responseHeaders: Map<String, String>? = null,
    val responseBody: String? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val initiator: String? = null               // "fetch" | "xhr"
)
