package com.chorand.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chorand.app.databinding.ItemGroupBinding

data class EventGroup(
    val key: String,
    val description: String,
    val events: List<ApiEvent>
)

class GroupAdapter(
    private var groups: List<EventGroup>,
    private val onGroupClick: (EventGroup) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    fun getGroupAt(position: Int): EventGroup = groups[position]

    fun updateGroups(newGroups: List<EventGroup>) {
        this.groups = newGroups
        notifyDataSetChanged()
    }

    inner class GroupViewHolder(val binding: ItemGroupBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return GroupViewHolder(binding)
    }

    override fun getItemCount() = groups.size

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val group = groups[position]

        with(holder.binding) {
            tvGroupTitle.text = group.key
            tvGroupDescription.text = group.description

            val count = group.events.size
            tvGroupCount.text = "$count event" + if (count != 1) "s" else ""

            root.setOnClickListener {
                onGroupClick(group)
            }
        }
    }
}
