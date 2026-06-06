package com.chorand.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chorand.app.databinding.ItemEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventAdapter(private val events: List<ApiEvent>) :
    RecyclerView.Adapter<EventAdapter.EventViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    inner class EventViewHolder(val binding: ItemEventBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EventViewHolder(binding)
    }

    override fun getItemCount() = events.size

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = events[position]
        val ctx = holder.binding.root.context

        with(holder.binding) {
            // Type badge
            tvType.text = event.type.uppercase()
            tvType.setBackgroundColor(ctx.getColor(getTypeColor(event)))
            tvType.setTextColor(ctx.getColor(android.R.color.white))

            // Method + URL
            val method = event.method ?: ""
            tvMethod.text = method
            tvUrl.text = event.url

            // Status
            if (event.status != null) {
                tvStatus.visibility = android.view.View.VISIBLE
                tvStatus.text = event.status.toString()
                tvStatus.setTextColor(ctx.getColor(getStatusColor(event.status)))
            } else if (event.error != null) {
                tvStatus.visibility = android.view.View.VISIBLE
                tvStatus.text = "ERR"
                tvStatus.setTextColor(ctx.getColor(R.color.error))
            } else {
                tvStatus.visibility = android.view.View.GONE
            }

            // Duration
            if (event.durationMs != null) {
                tvDuration.visibility = android.view.View.VISIBLE
                tvDuration.text = "${event.durationMs}ms"
            } else {
                tvDuration.visibility = android.view.View.GONE
            }

            // Timestamp
            tvTimestamp.text = timeFormat.format(Date(event.timestamp))

            // Initiator tag
            if (event.initiator != null) {
                tvInitiator.visibility = android.view.View.VISIBLE
                tvInitiator.text = event.initiator.uppercase()
            } else {
                tvInitiator.visibility = android.view.View.GONE
            }
        }
    }

    private fun getTypeColor(event: ApiEvent): Int {
        return when (event.type) {
            "request" -> R.color.accent_blue
            "response" -> when {
                (event.status ?: 0) in 200..299 -> R.color.success
                (event.status ?: 0) in 300..399 -> R.color.warning
                (event.status ?: 0) >= 400 -> R.color.error
                else -> R.color.accent_blue
            }
            "error" -> R.color.error
            else -> R.color.accent_blue
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
}
