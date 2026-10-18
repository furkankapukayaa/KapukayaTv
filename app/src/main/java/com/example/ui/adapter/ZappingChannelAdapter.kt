package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.epg.EpgRepository
import com.example.data.model.Channel
import com.example.databinding.ItemZappingChannelBinding

class ZappingChannelAdapter(
    private val onChannelSelected: (Channel) -> Unit,
    private val onMoveUp: (Channel) -> Unit,
    private val onMoveDown: (Channel) -> Unit,
    private val onDelete: (Channel) -> Unit,
    private val onLongPress: (Channel) -> Unit
) : RecyclerView.Adapter<ZappingChannelAdapter.ZappingViewHolder>() {

    private val items = mutableListOf<Channel>()
    private var activeChannelId: String? = null
    var isEditMode = false

    val channels: List<Channel> get() = items

    fun setChannels(newItems: List<Channel>, currentId: String?) {
        items.clear()
        items.addAll(newItems)
        activeChannelId = currentId
        notifyDataSetChanged()
    }

    fun setActiveChannel(currentId: String?) {
        val old = items.indexOfFirst { it.id == activeChannelId }
        activeChannelId = currentId
        val new = items.indexOfFirst { it.id == currentId }
        if (old != -1) notifyItemChanged(old)
        if (new != -1) notifyItemChanged(new)
    }

    fun indexOf(channelId: String?): Int = items.indexOfFirst { it.id == channelId }

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
            // Odak değişimi bg_zapping_item (stroke) ile yapılıyor. OK tıklaması View tarafından yönetilir.
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelSelected(items[position])
                }
            }

            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onLongPress(items[position])
                    true
                } else {
                    false
                }
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
            val favMark = if (channel.isFavorite) "  ★" else ""
            binding.tvZappingName.text = channel.displayName() + favMark

            if (!channel.logoUrl.isNullOrBlank()) {
                binding.ivZappingLogo.load(channel.logoUrl) {
                    placeholder(R.drawable.ic_tv)
                    error(R.drawable.ic_tv)
                }
            } else {
                binding.ivZappingLogo.setImageResource(R.drawable.ic_tv)
            }

            val now = EpgRepository.nowAndNext(channel).first
            if (now != null) {
                binding.tvZappingNow.visibility = View.VISIBLE
                binding.tvZappingNow.text = now.title
            } else {
                binding.tvZappingNow.visibility = View.GONE
            }

            binding.root.isSelected = (channel.id == activeChannelId)
            binding.editContainer.visibility = if (isEditMode) View.VISIBLE else View.GONE
        }
    }
}
