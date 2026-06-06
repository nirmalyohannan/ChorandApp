package com.chorand.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.chorand.app.databinding.ActivitySummaryBinding
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SummaryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_EVENT_COUNT = "extra_event_count"
    }

    private lateinit var binding: ActivitySummaryBinding
    private lateinit var sessionManager: SessionManager
    private val gson = Gson()

    private var targetUrl = ""
    private var filePath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""

        sessionManager = SessionManager(this)

        setupEdgeToEdge()
        setupUI()
        loadSummary()
    }

    private fun setupEdgeToEdge() {
        // Enable Edge-to-Edge window layout
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        // Apply window insets programmatically
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())

            // Apply status bar top inset as padding to the Header view
            // The header view is the first child in contentGroup, which is a vertical LinearLayout
            val headerView = binding.contentGroup.getChildAt(0)
            headerView?.setPadding(
                headerView.paddingLeft,
                statusInsets.top,
                headerView.paddingRight,
                headerView.paddingBottom
            )

            // Apply navigation bar bottom inset as padding to the Bottom Action Bar container
            // The bottom action bar is the last child in contentGroup vertical LinearLayout
            val bottomBar = binding.contentGroup.getChildAt(binding.contentGroup.childCount - 1)
            bottomBar?.setPadding(
                bottomBar.paddingLeft,
                bottomBar.paddingTop,
                bottomBar.paddingRight,
                navInsets.bottom
            )

            insets
        }
    }

    private fun setupUI() {
        binding.tvUrl.text = targetUrl

        binding.btnSaveToDownloads.setOnClickListener { saveToDownloads() }
        binding.btnShare.setOnClickListener { shareFile() }
        binding.btnStartNew.setOnClickListener { startNew() }

        binding.rvEvents.layoutManager = LinearLayoutManager(this)
    }

    private fun loadSummary() {
        binding.loadingGroup.visibility = View.VISIBLE
        binding.contentGroup.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val file = File(filePath)
            val events = mutableListOf<ApiEvent>()
            var requestCount = 0
            var responseCount = 0
            var errorCount = 0
            var firstTimestamp: Long? = null
            var lastTimestamp: Long? = null
            var successCount = 0
            var clientErrorCount = 0
            var serverErrorCount = 0

            if (file.exists()) {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        val event = gson.fromJson(line, ApiEvent::class.java)
                        events.add(event)
                        when (event.type) {
                            "request" -> requestCount++
                            "response" -> {
                                responseCount++
                                when {
                                    (event.status ?: 0) in 200..299 -> successCount++
                                    (event.status ?: 0) in 400..499 -> clientErrorCount++
                                    (event.status ?: 0) in 500..599 -> serverErrorCount++
                                }
                            }
                            "error" -> errorCount++
                        }
                        val ts = event.timestamp
                        if (firstTimestamp == null || ts < firstTimestamp!!) firstTimestamp = ts
                        if (lastTimestamp == null || ts > lastTimestamp!!) lastTimestamp = ts
                    } catch (e: Exception) { /* skip malformed lines */ }
                }
            }

            val fileSize = file.length()
            val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault())

            withContext(Dispatchers.Main) {
                binding.loadingGroup.visibility = View.GONE
                binding.contentGroup.visibility = View.VISIBLE

                binding.tvTotalEvents.text = events.size.toString()
                binding.tvSuccess.text = successCount.toString()
                binding.tvClientErrors.text = clientErrorCount.toString()
                binding.tvServerErrors.text = serverErrorCount.toString()
                binding.tvFileSize.text = formatFileSize(fileSize)
                binding.tvFilePath.text = file.name

                // Wire stat card includes via ViewBinding generated binding objects
                binding.statRequests.tvStatValue.text = requestCount.toString()
                binding.statRequests.tvStatLabel.text = "Requests"
                binding.statResponses.tvStatValue.text = responseCount.toString()
                binding.statResponses.tvStatLabel.text = "Responses"
                binding.statErrors.tvStatValue.text = errorCount.toString()
                binding.statErrors.tvStatLabel.text = "Errors"

                firstTimestamp?.let {
                    binding.tvFirstEvent.text = dateFormat.format(Date(it))
                }
                lastTimestamp?.let {
                    binding.tvLastEvent.text = dateFormat.format(Date(it))
                }

                val adapter = EventAdapter(events.takeLast(50).reversed())
                binding.rvEvents.adapter = adapter
            }
        }
    }

    private fun saveToDownloads() {
        val sourceFile = File(filePath)
        if (!sourceFile.exists()) return
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            downloadsDir.mkdirs()
            val destFile = File(downloadsDir, sourceFile.name)
            sourceFile.copyTo(destFile, overwrite = true)
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)))
            showSnackbar("Saved to Downloads/${sourceFile.name}", isError = false)
        } catch (e: Exception) {
            showSnackbar("Failed to save: ${e.message}", isError = true)
        }
    }

    private fun shareFile() {
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Chorand API Capture — ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share API Capture"))
        } catch (e: Exception) {
            showSnackbar("Failed to share: ${e.message}", isError = true)
        }
    }

    private fun startNew() {
        sessionManager.clearSession()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showSnackbar(message: String, isError: Boolean) {
        val color = if (isError) getColor(R.color.error) else getColor(R.color.success)
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setBackgroundTint(color)
            .setTextColor(getColor(android.R.color.white))
            .show()
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
