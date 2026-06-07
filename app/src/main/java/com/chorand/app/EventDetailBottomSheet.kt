package com.chorand.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.chorand.app.databinding.LayoutEventDetailBottomSheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutEventDetailBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutEventDetailBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val eventJson = arguments?.getString(ARG_EVENT_JSON) ?: return
        val event = Gson().fromJson(eventJson, ApiEvent::class.java)

        setupUI(event)
    }

    private fun setupUI(event: ApiEvent) {
        // Method
        binding.tvDetailMethod.text = event.method ?: "CONNECT"

        // Status & Color
        val ctx = requireContext()
        when {
            event.status != null -> {
                binding.tvDetailStatus.text = "${event.status} ${event.statusText ?: ""}"
                binding.tvDetailStatus.setTextColor(ContextCompat.getColor(ctx, getStatusColor(event.status)))
            }
            event.error != null -> {
                binding.tvDetailStatus.text = event.error
                binding.tvDetailStatus.setTextColor(ContextCompat.getColor(ctx, R.color.error))
            }
            else -> {
                binding.tvDetailStatus.text = "Pending..."
                binding.tvDetailStatus.setTextColor(ContextCompat.getColor(ctx, R.color.warning))
            }
        }

        // URL
        binding.tvDetailUrl.text = event.url

        // Metadata Grid
        binding.tvDetailInitiator.text = event.initiator?.uppercase() ?: "UNKNOWN"
        binding.tvDetailDuration.text = if (event.durationMs != null) "${event.durationMs} ms" else "—"
        binding.tvDetailTime.text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(event.timestamp))

        // Headers
        binding.tvRequestHeaders.text = formatHeaders(event.requestHeaders)
        binding.tvResponseHeaders.text = formatHeaders(event.responseHeaders)

        // Payloads pretty-printing
        val requestBodyFormatted = formatPayload(event.requestBody)
        val responseBodyFormatted = formatPayload(event.responseBody)

        binding.tvRequestBody.text = requestBodyFormatted
        binding.tvResponseBody.text = responseBodyFormatted

        // Hide Request/Response Tab Buttons if no body is present
        // Actually, let's keep them enabled so the user can see "(Empty)" details.

        // Tab Selector Click Listeners
        binding.btnTabHeaders.setOnClickListener { selectTab("headers") }
        binding.btnTabRequest.setOnClickListener { selectTab("request") }
        binding.btnTabResponse.setOnClickListener { selectTab("response") }

        // Copy Listeners
        binding.btnCopyUrl.setOnClickListener {
            copyToClipboard("URL", event.url)
        }
        binding.btnCopyRequest.setOnClickListener {
            copyToClipboard("Request Body", requestBodyFormatted)
        }
        binding.btnCopyResponse.setOnClickListener {
            copyToClipboard("Response Body", responseBodyFormatted)
        }
        binding.btnCopyRaw.setOnClickListener {
            val prettyGson = GsonBuilder().setPrettyPrinting().create()
            copyToClipboard("Raw Event JSON", prettyGson.toJson(event))
        }

        // Select default tab
        selectTab("headers")
    }

    private fun selectTab(tab: String) {
        val ctx = requireContext()
        val activeColor = ContextCompat.getColor(ctx, R.color.bg_elevated)
        val activeTextColor = ContextCompat.getColor(ctx, R.color.text_primary)
        val inactiveColor = ContextCompat.getColor(ctx, android.R.color.transparent)
        val inactiveTextColor = ContextCompat.getColor(ctx, R.color.text_secondary)

        binding.btnTabHeaders.backgroundTintList = ColorStateList.valueOf(if (tab == "headers") activeColor else inactiveColor)
        binding.btnTabHeaders.setTextColor(if (tab == "headers") activeTextColor else inactiveTextColor)
        binding.layoutHeaders.visibility = if (tab == "headers") View.VISIBLE else View.GONE

        binding.btnTabRequest.backgroundTintList = ColorStateList.valueOf(if (tab == "request") activeColor else inactiveColor)
        binding.btnTabRequest.setTextColor(if (tab == "request") activeTextColor else inactiveTextColor)
        binding.layoutRequest.visibility = if (tab == "request") View.VISIBLE else View.GONE

        binding.btnTabResponse.backgroundTintList = ColorStateList.valueOf(if (tab == "response") activeColor else inactiveColor)
        binding.btnTabResponse.setTextColor(if (tab == "response") activeTextColor else inactiveTextColor)
        binding.layoutResponse.visibility = if (tab == "response") View.VISIBLE else View.GONE
    }

    private fun formatHeaders(headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty()) return "(Empty)"
        val builder = StringBuilder()
        headers.forEach { (key, value) ->
            builder.append(key).append(": ").append(value).append("\n")
        }
        return builder.toString().trim()
    }

    private fun formatPayload(payload: String?): String {
        if (payload.isNullOrBlank()) return "(Empty)"
        return try {
            val prettyGson = GsonBuilder().setPrettyPrinting().create()
            @Suppress("DEPRECATION")
            val parser = JsonParser()
            @Suppress("DEPRECATION")
            val jsonElement = parser.parse(payload)
            prettyGson.toJson(jsonElement)
        } catch (e: Exception) {
            payload
        }
    }

    private fun getStatusColor(status: Int): Int {
        return when (status) {
            in 200..299 -> R.color.success
            in 300..399 -> R.color.warning
            in 400..499 -> R.color.error
            in 500..599 -> R.color.error
            else -> R.color.text_secondary
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val ctx = requireContext()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(ctx, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_EVENT_JSON = "arg_event_json"

        fun newInstance(event: ApiEvent): EventDetailBottomSheet {
            val fragment = EventDetailBottomSheet()
            val args = Bundle().apply {
                putString(ARG_EVENT_JSON, Gson().toJson(event))
            }
            fragment.arguments = args
            return fragment
        }
    }
}
