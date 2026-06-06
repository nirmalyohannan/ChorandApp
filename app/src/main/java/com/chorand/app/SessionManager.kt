package com.chorand.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the persistent scraping session state using SharedPreferences.
 * Tracks the active URL, JSONL file path, and recorded event count.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "chorand_session"
        private const val KEY_URL = "session_url"
        private const val KEY_FILE_PATH = "session_file_path"
        private const val KEY_HAS_SESSION = "has_active_session"
        private const val KEY_EVENT_COUNT = "session_event_count"
        private const val KEY_SESSION_STARTED_AT = "session_started_at"
    }

    data class Session(
        val url: String,
        val filePath: String,
        val eventCount: Int,
        val startedAt: Long
    )

    fun hasActiveSession(): Boolean = prefs.getBoolean(KEY_HAS_SESSION, false)

    fun getSession(): Session? {
        if (!hasActiveSession()) return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        val filePath = prefs.getString(KEY_FILE_PATH, null) ?: return null
        val eventCount = prefs.getInt(KEY_EVENT_COUNT, 0)
        val startedAt = prefs.getLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
        return Session(url, filePath, eventCount, startedAt)
    }

    fun saveSession(url: String, filePath: String, eventCount: Int) {
        prefs.edit().apply {
            putBoolean(KEY_HAS_SESSION, true)
            putString(KEY_URL, url)
            putString(KEY_FILE_PATH, filePath)
            putInt(KEY_EVENT_COUNT, eventCount)
            if (!prefs.contains(KEY_SESSION_STARTED_AT)) {
                putLong(KEY_SESSION_STARTED_AT, System.currentTimeMillis())
            }
            apply()
        }
    }

    fun updateEventCount(count: Int) {
        prefs.edit().putInt(KEY_EVENT_COUNT, count).apply()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
