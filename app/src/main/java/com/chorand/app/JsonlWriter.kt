package com.chorand.app

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Writes ApiEvent entries to a JSONL file (one JSON object per line).
 * Thread-safe via Mutex for concurrent coroutine access.
 */
class JsonlWriter(private val file: File) {

    private val gson = Gson()
    private val mutex = Mutex()
    private var writer: BufferedWriter? = null
    private var _eventCount = 0

    val eventCount: Int get() = _eventCount
    val filePath: String get() = file.absolutePath
    val fileSize: Long get() = if (file.exists()) file.length() else 0L

    /**
     * Opens the writer in append mode. Call before writing.
     */
    suspend fun open() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (writer == null) {
                // Count existing events if resuming
                if (file.exists() && file.length() > 0) {
                    _eventCount = file.readLines().count { it.isNotBlank() }
                }
                writer = BufferedWriter(FileWriter(file, true))
            }
        }
    }

    /**
     * Writes a single ApiEvent as a JSON line.
     */
    suspend fun write(event: ApiEvent) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val json = gson.toJson(event)
            writer?.apply {
                write(json)
                newLine()
                flush()
            }
            _eventCount++
        }
    }

    /**
     * Flushes and closes the underlying file writer.
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            writer?.close()
            writer = null
        }
    }

    companion object {
        /**
         * Creates a new JSONL file with a timestamp-based name in the given directory.
         */
        fun createNewFile(dir: File): File {
            dir.mkdirs()
            val timestamp = System.currentTimeMillis()
            return File(dir, "chorand_capture_$timestamp.jsonl")
        }
    }
}
