package com.chorand.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
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
    private var filePath = ""
    private var eventsList = mutableListOf<ApiEvent>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupedEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        groupType = intent.getStringExtra(EXTRA_TYPE) ?: ""
        filePath = intent.getStringExtra(SummaryActivity.EXTRA_FILE_PATH) ?: ""

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
        setupSwipeToDelete()
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

    private fun setupSwipeToDelete() {
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val event = eventsList[position]
                eventsList.removeAt(position)
                eventAdapter.notifyItemRemoved(position)
                deleteEventFromFile(event.eventId)
                if (eventsList.isEmpty()) {
                    finish()
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val paint = Paint()
                    paint.color = ContextCompat.getColor(recyclerView.context, R.color.error)

                    val background: RectF
                    val iconDest: Rect
                    val deleteIcon = ContextCompat.getDrawable(recyclerView.context, R.drawable.ic_delete)

                    if (deleteIcon != null) {
                        val iconMargin = (itemView.height - deleteIcon.intrinsicHeight) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + deleteIcon.intrinsicHeight

                        if (dX > 0) { // Swiping right
                            background = RectF(
                                itemView.left.toFloat(),
                                itemView.top.toFloat(),
                                itemView.left.toFloat() + dX,
                                itemView.bottom.toFloat()
                            )
                            c.drawRect(background, paint)

                            val iconLeft = itemView.left + iconMargin
                            val iconRight = iconLeft + deleteIcon.intrinsicWidth
                            iconDest = Rect(iconLeft, iconTop, iconRight, iconBottom)
                            deleteIcon.bounds = iconDest
                            deleteIcon.draw(c)
                        } else if (dX < 0) { // Swiping left
                            background = RectF(
                                itemView.right.toFloat() + dX,
                                itemView.top.toFloat(),
                                itemView.right.toFloat(),
                                itemView.bottom.toFloat()
                            )
                            c.drawRect(background, paint)

                            val iconRight = itemView.right - iconMargin
                            val iconLeft = iconRight - deleteIcon.intrinsicWidth
                            iconDest = Rect(iconLeft, iconTop, iconRight, iconBottom)
                            deleteIcon.bounds = iconDest
                            deleteIcon.draw(c)
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(binding.rvEvents)
    }

    private fun deleteEventFromFile(eventId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val file = File(filePath)
            if (file.exists()) {
                val gson = Gson()
                val remainingEvents = mutableListOf<ApiEvent>()
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        val event = gson.fromJson(line, ApiEvent::class.java)
                        if (event.eventId != eventId) {
                            remainingEvents.add(event)
                        }
                    } catch (e: Exception) {}
                }
                try {
                    file.bufferedWriter().use { writer ->
                        remainingEvents.forEach { event ->
                            writer.write(gson.toJson(event))
                            writer.newLine()
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }
}
