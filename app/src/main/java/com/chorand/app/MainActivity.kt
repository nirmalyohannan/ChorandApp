package com.chorand.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chorand.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private var isWebMode = true
    private var pendingVpnResume = false

    private val vpnPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnScraper(pendingVpnResume)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        setupUI()
        checkForExistingSession()
    }

    private fun setupUI() {
        // Animate logo on enter
        binding.logoContainer.alpha = 0f
        binding.logoContainer.translationY = -60f
        binding.logoContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(100)
            .start()

        binding.inputCard.alpha = 0f
        binding.inputCard.translationY = 40f
        binding.inputCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .setStartDelay(300)
            .start()

        binding.btnScrape.alpha = 0f
        binding.btnScrape.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(600)
            .start()

        // Switcher Listeners
        binding.btnModeWeb.setOnClickListener { setWebMode(true) }
        binding.btnModeGlobal.setOnClickListener { setWebMode(false) }

        // URL input validation
        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isWebMode) {
                    val text = s?.toString()?.trim() ?: ""
                    binding.btnScrape.isEnabled = text.isNotEmpty()
                    binding.tilUrl.error = null
                }
            }
        })

        binding.btnScrape.setOnClickListener {
            if (isWebMode) {
                val rawUrl = binding.etUrl.text?.toString()?.trim() ?: ""
                val url = normalizeUrl(rawUrl)
                if (url.isNullOrEmpty()) {
                    binding.tilUrl.error = "Please enter a valid URL"
                    return@setOnClickListener
                }
                startNewSession(url)
            } else {
                prepareAndStartVpn(resume = false)
            }
        }

        // Initialize state
        setWebMode(true)
    }

    private fun setWebMode(web: Boolean) {
        isWebMode = web
        if (web) {
            binding.btnModeWeb.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.bg_elevated))
            binding.btnModeWeb.setTextColor(getColor(R.color.text_primary))
            binding.btnModeGlobal.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(android.R.color.transparent))
            binding.btnModeGlobal.setTextColor(getColor(R.color.text_secondary))

            binding.inputCard.visibility = View.VISIBLE
            binding.vpnInfoCard.visibility = View.GONE

            val text = binding.etUrl.text?.toString()?.trim() ?: ""
            binding.btnScrape.isEnabled = text.isNotEmpty()
            binding.btnScrape.text = "START SCRAPING"
        } else {
            binding.btnModeGlobal.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.bg_elevated))
            binding.btnModeGlobal.setTextColor(getColor(R.color.text_primary))
            binding.btnModeWeb.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(android.R.color.transparent))
            binding.btnModeWeb.setTextColor(getColor(R.color.text_secondary))

            binding.inputCard.visibility = View.GONE
            binding.vpnInfoCard.visibility = View.VISIBLE

            binding.btnScrape.isEnabled = true
            binding.btnScrape.text = "START VPN INTERCEPTOR"
        }
    }

    private fun checkForExistingSession() {
        if (sessionManager.hasActiveSession()) {
            val session = sessionManager.getSession() ?: return
            val file = File(session.filePath)
            if (!file.exists()) {
                sessionManager.clearSession()
                return
            }
            showResumeDialog(session)
        }
    }

    private fun showResumeDialog(session: SessionManager.Session) {
        val dateFormat = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        val startedDate = dateFormat.format(Date(session.startedAt))
        val fileSize = formatFileSize(File(session.filePath).length())
        val displayUrl = if (session.url == "Global VPN Capture") "Global Device Traffic (VPN)" else session.url

        AlertDialog.Builder(this, R.style.ChorandAlertDialog)
            .setTitle("Resume Session?")
            .setMessage(
                "You have an active recording session.\n\n" +
                "🌐 Target: $displayUrl\n" +
                "📦 Events recorded: ${session.eventCount}\n" +
                "💾 File size: $fileSize\n" +
                "🕐 Started: $startedDate"
            )
            .setPositiveButton("▶  Continue Recording") { _, _ ->
                if (session.url == "Global VPN Capture") {
                    prepareAndStartVpn(resume = true)
                } else {
                    launchWebScraper(session.url, session.filePath, resume = true)
                }
            }
            .setNegativeButton("🔄 Start New") { _, _ ->
                sessionManager.clearSession()
                binding.etUrl.setText("")
            }
            .setCancelable(false)
            .show()
    }

    private fun startNewSession(url: String) {
        val captureDir = File(filesDir, "captures")
        val file = JsonlWriter.createNewFile(captureDir)
        sessionManager.clearSession()
        launchWebScraper(url, file.absolutePath, resume = false)
    }

    private fun launchWebScraper(url: String, filePath: String, resume: Boolean) {
        val intent = Intent(this, WebScraperActivity::class.java).apply {
            putExtra(WebScraperActivity.EXTRA_URL, url)
            putExtra(WebScraperActivity.EXTRA_FILE_PATH, filePath)
            putExtra(WebScraperActivity.EXTRA_RESUME, resume)
        }
        startActivity(intent)
    }

    private fun prepareAndStartVpn(resume: Boolean) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingVpnResume = resume
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnScraper(resume)
        }
    }

    private fun startVpnScraper(resume: Boolean) {
        val captureDir = File(filesDir, "captures")
        val file = if (resume) {
            val session = sessionManager.getSession()
            if (session != null) File(session.filePath) else JsonlWriter.createNewFile(captureDir)
        } else {
            JsonlWriter.createNewFile(captureDir)
        }

        // Start VPN Service
        val serviceIntent = Intent(this, LocalVpnService::class.java).apply {
            putExtra(LocalVpnService.EXTRA_FILE_PATH, file.absolutePath)
        }
        startService(serviceIntent)

        // Start VpnScraperActivity
        val intent = Intent(this, VpnScraperActivity::class.java).apply {
            putExtra(VpnScraperActivity.EXTRA_FILE_PATH, file.absolutePath)
            putExtra(VpnScraperActivity.EXTRA_RESUME, resume)
        }
        startActivity(intent)
    }

    private fun normalizeUrl(input: String): String? {
        if (input.isEmpty()) return null
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") -> "https://$input"
            else -> null
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
