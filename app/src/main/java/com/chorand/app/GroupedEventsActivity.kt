package com.chorand.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.chorand.app.databinding.ActivityGroupedEventsBinding

class GroupedEventsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_EVENTS = "extra_events"
    }

    private lateinit var binding: ActivityGroupedEventsBinding
    private lateinit var eventAdapter: EventAdapter
    private var groupTitle = ""
    private var groupType = ""
    private var eventsList = mutableListOf<ApiEvent>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupedEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        groupType = intent.getStringExtra(EXTRA_TYPE) ?: ""

        val rawEvents = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_EVENTS, ArrayList::class.java) as? ArrayList<*>
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_EVENTS) as? ArrayList<*>
        }

        rawEvents?.forEach {
            if (it is ApiEvent) {
                eventsList.add(it)
            }
        }

        setupUI()
        applyStatusBarInset()
    }

    private fun setupUI() {
        binding.tvGroupHeader.text = "GROUPED BY ${groupType.uppercase()}"
        binding.tvGroupValue.text = groupTitle

        binding.btnBack.setOnClickListener {
            finish()
        }

        eventAdapter = EventAdapter(eventsList) { event ->
            EventDetailBottomSheet.newInstance(event).show(supportFragmentManager, "EventDetail")
        }
        binding.rvEvents.layoutManager = LinearLayoutManager(this)
        binding.rvEvents.adapter = eventAdapter
    }

    private fun applyStatusBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.layoutParams =
                binding.statusBarSpacer.layoutParams.also { it.height = statusBarHeight }
            insets
        }
    }
}
