package com.chorand.app

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * JavaScript Interface bridging the injected JS scraper_injector.js to Kotlin.
 * Receives intercepted request/response data from the WebView and writes to JSONL.
 */
class ScraperJsInterface(
    private val jsonlWriter: JsonlWriter
) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _eventCount = MutableStateFlow(0)
    val eventCount: StateFlow<Int> = _eventCount.asStateFlow()

    init {
        // Initialize counter from existing file if resuming
        _eventCount.value = jsonlWriter.eventCount
    }

    @JavascriptInterface
    fun onRequest(jsonPayload: String) {
        scope.launch {
            try {
                val data = gson.fromJson(jsonPayload, JsonObject::class.java)
                val event = ApiEvent(
                    type = "request",
                    url = data.getString("url"),
                    method = data.getString("method"),
                    requestHeaders = data.getMap("requestHeaders"),
                    requestBody = data.getStringOrNull("requestBody"),
                    initiator = data.getStringOrNull("initiator"),
                    timestamp = data.getLongOrNull("timestamp") ?: System.currentTimeMillis()
                )
                jsonlWriter.write(event)
                _eventCount.value = jsonlWriter.eventCount
            } catch (e: Exception) {
                // Silently ignore malformed events
            }
        }
    }

    @JavascriptInterface
    fun onResponse(jsonPayload: String) {
        scope.launch {
            try {
                val data = gson.fromJson(jsonPayload, JsonObject::class.java)
                val event = ApiEvent(
                    type = "response",
                    url = data.getString("url"),
                    method = data.getString("method"),
                    status = data.getIntOrNull("status"),
                    statusText = data.getStringOrNull("statusText"),
                    responseHeaders = data.getMap("responseHeaders"),
                    responseBody = data.getStringOrNull("responseBody"),
                    durationMs = data.getLongOrNull("durationMs"),
                    initiator = data.getStringOrNull("initiator"),
                    timestamp = data.getLongOrNull("timestamp") ?: System.currentTimeMillis()
                )
                jsonlWriter.write(event)
                _eventCount.value = jsonlWriter.eventCount
            } catch (e: Exception) {
                // Silently ignore malformed events
            }
        }
    }

    @JavascriptInterface
    fun onError(jsonPayload: String) {
        scope.launch {
            try {
                val data = gson.fromJson(jsonPayload, JsonObject::class.java)
                val event = ApiEvent(
                    type = "error",
                    url = data.getString("url"),
                    method = data.getStringOrNull("method"),
                    error = data.getStringOrNull("error"),
                    durationMs = data.getLongOrNull("durationMs"),
                    initiator = data.getStringOrNull("initiator"),
                    timestamp = data.getLongOrNull("timestamp") ?: System.currentTimeMillis()
                )
                jsonlWriter.write(event)
                _eventCount.value = jsonlWriter.eventCount
            } catch (e: Exception) {
                // Silently ignore malformed events
            }
        }
    }

    // ─── JsonObject extension helpers ─────────────────────────────────────────

    private fun JsonObject.getString(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun JsonObject.getStringOrNull(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.getIntOrNull(key: String): Int? =
        if (has(key) && !get(key).isJsonNull) get(key).asInt else null

    private fun JsonObject.getLongOrNull(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) get(key).asLong else null

    private fun JsonObject.getMap(key: String): Map<String, String>? {
        if (!has(key) || get(key).isJsonNull) return null
        return try {
            val obj = get(key).asJsonObject
            val map = mutableMapOf<String, String>()
            obj.entrySet().forEach { map[it.key] = it.value.asString }
            map
        } catch (e: Exception) { null }
    }
}
