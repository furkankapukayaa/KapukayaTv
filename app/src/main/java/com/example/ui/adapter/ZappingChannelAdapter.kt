package com.example.ui.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.model.Channel
import com.example.databinding.ItemZappingChannelBinding

class ZappingChannelAdapter(
    private val onChannelSelected: (Channel) -> Unit,
    private val onMoveUp: (Channel) -> Unit,
    private val onMoveDown: (Channel) -> Unit,
    private val onDelete: (Channel) -> Unit
) : RecyclerView.Adapter<ZappingChannelAdapter.ZappingViewHolder>() {

    private val items = mutableListOf<Channel>()
    private var activeChannelId: String? = null
    var isEditMode = false

    fun setChannels(newItems: List<Channel>, currentId: String?) {
        items.clear()
        items.addAll(newItems)
        activeChannelId = currentId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ZappingViewHolder {
        val binding = ItemZappingChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ZappingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ZappingViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ZappingViewHolder(private val binding: ItemZappingChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // Animasyon kaldırıldı. Odak değişimi bg_zapping_item (stroke) ile CSS mantığında yapılıyor.

            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelSelected(items[position])
                }
            }

            binding.root.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onChannelSelected(items[position])
                        return@setOnKeyListener true
                    }
                }
                false
            }

            binding.btnZappingUp.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMoveUp(items[position])
                }
            }

            binding.btnZappingDown.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMoveDown(items[position])
                }
            }

            binding.btnZappingDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDelete(items[position])
                }
            }
        }

        fun bind(channel: Channel) {
            val displayName = if (channel.channelNumber > 0) "${channel.channelNumber}. ${channel.name}" else channel.name
            binding.tvZappingName.text = displayName

            if (!channel.logoUrl.isNullOrBlank()) {
                binding.ivZappingLogo.load(channel.logoUrl) {
                    placeholder(R.drawable.ic_tv)
                    error(R.drawable.ic_tv)
                }
            } else {
                binding.ivZappingLogo.setImageResource(R.drawable.ic_tv)
            }

            binding.root.isSelected = (channel.id == activeChannelId)
            binding.editContainer.visibility = if (isEditMode) View.VISIBLE else View.GONE
        }
    }
}
