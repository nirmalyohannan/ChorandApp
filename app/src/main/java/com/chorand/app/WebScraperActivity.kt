package com.chorand.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.chorand.app.databinding.ActivityWebScraperBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class WebScraperActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_RESUME = "extra_resume"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_CUSTOM_HEADERS = "extra_custom_headers"

        // A realistic modern Chrome UA on Android
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/AP2A.240805.005; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/127.0.6533.103 Mobile Safari/537.36"
    }

    private lateinit var binding: ActivityWebScraperBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var jsonlWriter: JsonlWriter
    private lateinit var scraperInterface: ScraperJsInterface

    private var targetUrl = ""
    private var filePath = ""
    private var isResume = false
    private var customUserAgent = ""
    private var customHeadersString = ""
    private var scraperJs = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebScraperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        isResume = intent.getBooleanExtra(EXTRA_RESUME, false)
        customUserAgent = intent.getStringExtra(EXTRA_USER_AGENT) ?: ""
        customHeadersString = intent.getStringExtra(EXTRA_CUSTOM_HEADERS) ?: ""

        sessionManager = SessionManager(this)
        jsonlWriter = JsonlWriter(File(filePath))

        // Parse custom headers, build JSON window variable injection, and prepend it to scraperJs
        val headersMap = parseHeaders(customHeadersString)
        val headersJson = com.google.gson.Gson().toJson(headersMap)
        val customHeadersJsInject = "window.ChorandCustomHeaders = $headersJson;\n"

        // Load the JS injector from assets
        scraperJs = customHeadersJsInject + assets.open("scraper_injector.js").bufferedReader().readText()

        scraperInterface = ScraperJsInterface(jsonlWriter)

        applyStatusBarInset()
        setupMiniBar()
        setupWebView()
        observeEventCount()

        // Open file and start loading
        lifecycleScope.launch {
            jsonlWriter.open()
            sessionManager.saveSession(targetUrl, filePath, jsonlWriter.eventCount)
            val headersMap = parseHeaders(customHeadersString)
            if (headersMap.isNotEmpty()) {
                binding.webView.loadUrl(targetUrl, headersMap)
            } else {
                binding.webView.loadUrl(targetUrl)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = if (customUserAgent.isNotEmpty()) customUserAgent else CHROME_USER_AGENT
            allowContentAccess = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
        }

        // Enable cookies
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Add JS bridge
        webView.addJavascriptInterface(scraperInterface, "ScraperBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // We don't block any requests; interception is done via JS bridge
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject the scraper JS into every page load
                view?.evaluateJavascript(scraperJs, null)
                // Update address bar
                binding.tvCurrentUrl.text = url ?: targetUrl
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                binding.tvCurrentUrl.text = url ?: ""
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Reads the real status bar height via WindowInsetsCompat and applies it
     * as the height of the statusBarSpacer view, pushing the mini bar content
     * below the system notification bar on all devices.
     */
    private fun applyStatusBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams =
                binding.statusBarSpacer.layoutParams.also { it.height = statusBarHeight }
            insets
        }
    }

    private fun setupMiniBar() {
        binding.tvEventCount.text = "Events: 0"
        binding.tvCurrentUrl.text = targetUrl

        binding.btnStop.setOnClickListener {
            showExitConfirmationDialog()
        }

        // Intercept onBackPressed to show warning dialog
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitConfirmationDialog()
            }
        })
    }

    private fun showExitConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.ChorandAlertDialog)
            .setTitle("Stop Scraping?")
            .setMessage("Are you sure you want to stop recording traffic and view the summary?")
            .setPositiveButton("Stop") { _, _ ->
                stopScraping()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeEventCount() {
        lifecycleScope.launch {
            scraperInterface.eventCount.collectLatest { count ->
                binding.tvEventCount.text = "Events: $count"
                // Save updated count to session
                sessionManager.updateEventCount(count)
            }
        }
    }

    private fun stopScraping() {
        lifecycleScope.launch {
            jsonlWriter.close()
            val intent = Intent(this@WebScraperActivity, SummaryActivity::class.java).apply {
                putExtra(SummaryActivity.EXTRA_URL, targetUrl)
                putExtra(SummaryActivity.EXTRA_FILE_PATH, filePath)
                putExtra(SummaryActivity.EXTRA_EVENT_COUNT, jsonlWriter.eventCount)
            }
            startActivity(intent)
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch { jsonlWriter.close() }
        binding.webView.destroy()
    }

    private fun parseHeaders(headersStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        headersStr.split("\n").forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }
}
