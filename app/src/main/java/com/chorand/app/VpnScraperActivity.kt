package com.chorand.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.chorand.app.databinding.ActivityVpnScraperBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class VpnScraperActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_RESUME = "extra_resume"
    }

    private lateinit var binding: ActivityVpnScraperBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var eventAdapter: EventAdapter
    private val capturedEvents = mutableListOf<ApiEvent>()
    private var filePath = ""
    private var isResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVpnScraperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        isResume = intent.getBooleanExtra(EXTRA_RESUME, false)

        sessionManager = SessionManager(this)

        setupUI()
        applyStatusBarInset()
        observeVpnEvents()

        if (isResume) {
            // Load existing events from JSONL file if resuming
            lifecycleScope.launch {
                loadExistingEvents()
            }
        } else {
            sessionManager.saveSession("Global VPN Capture", filePath, 0)
        }
    }

    private fun setupUI() {
        binding.tvEventCount.text = "Events: 0"

        eventAdapter = EventAdapter(capturedEvents)
        binding.rvEvents.layoutManager = LinearLayoutManager(this)
        binding.rvEvents.adapter = eventAdapter

        binding.btnStop.setOnClickListener {
            showExitConfirmationDialog()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitConfirmationDialog()
            }
        })
    }

    private fun applyStatusBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams =
                binding.statusBarSpacer.layoutParams.also { it.height = statusBarHeight }
            insets
        }
    }

    private fun loadExistingEvents() {
        val file = File(filePath)
        if (file.exists()) {
            val gson = com.google.gson.Gson()
            try {
                file.forEachLine { line ->
                    if (line.isNotBlank()) {
                        val event = gson.fromJson(line, ApiEvent::class.java)
                        capturedEvents.add(event)
                    }
                }
                // Reverse to show latest first
                capturedEvents.reverse()
                eventAdapter.notifyDataSetChanged()
                binding.tvEventCount.text = "Events: ${capturedEvents.size}"
                sessionManager.updateEventCount(capturedEvents.size)
            } catch (e: Exception) {
                // Ignore parsing errors
            }
        }
    }

    private fun observeVpnEvents() {
        lifecycleScope.launch {
            LocalVpnService.eventFlow.collectLatest { event ->
                // Add to start of list to show most recent at the top
                capturedEvents.add(0, event)
                eventAdapter.notifyItemInserted(0)
                binding.rvEvents.scrollToPosition(0)
                binding.tvEventCount.text = "Events: ${capturedEvents.size}"
                sessionManager.updateEventCount(capturedEvents.size)
            }
        }
    }

    private fun showExitConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.ChorandAlertDialog)
            .setTitle("Stop Intercepting?")
            .setMessage("Are you sure you want to stop the VPN interceptor and view the summary?")
            .setPositiveButton("Stop") { _, _ ->
                stopScraping()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopScraping() {
        stopService(Intent(this, LocalVpnService::class.java))
        val intent = Intent(this, SummaryActivity::class.java).apply {
            putExtra(SummaryActivity.EXTRA_URL, "Global VPN Capture")
            putExtra(SummaryActivity.EXTRA_FILE_PATH, filePath)
            putExtra(SummaryActivity.EXTRA_EVENT_COUNT, capturedEvents.size)
        }
        startActivity(intent)
        finish()
    }
}
