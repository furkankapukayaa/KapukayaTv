package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.epg.EpgRepository
import com.example.data.epg.Programme
import com.example.data.model.Channel
import com.example.databinding.ItemGuideChannelBinding
import com.example.databinding.ItemGuideProgrammeBinding

/** Rehber ekranının sol tarafı: kanallar ve şu anki programları. */
class GuideChannelAdapter(
    private val onFocused: (Channel) -> Unit,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<GuideChannelAdapter.Holder>() {

    private val items = mutableListOf<Channel>()

    fun setChannels(channels: List<Channel>) {
        items.clear()
        items.addAll(channels)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemGuideChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class Holder(private val binding: ItemGuideChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                val position = bindingAdapterPosition
                if (hasFocus && position != RecyclerView.NO_POSITION) onFocused(items[position])
            }
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }

        fun bind(channel: Channel) {
            binding.tvGuideChannelName.text = channel.displayName()
            if (!channel.logoUrl.isNullOrBlank()) {
                binding.ivGuideChannelLogo.load(channel.logoUrl) {
                    placeholder(R.drawable.ic_tv)
                    error(R.drawable.ic_tv)
                }
            } else {
                binding.ivGuideChannelLogo.setImageResource(R.drawable.ic_tv)
            }
            val now = EpgRepository.nowAndNext(channel).first
            if (now != null) {
                binding.tvGuideChannelNow.text = now.title
                binding.pbGuideChannelNow.visibility = View.VISIBLE
                binding.pbGuideChannelNow.progress = now.progress()
            } else {
                binding.tvGuideChannelNow.text = "Yayın akışı bilgisi yok"
                binding.pbGuideChannelNow.visibility = View.INVISIBLE
            }
        }
    }
}

/** Rehber ekranının sağ tarafı: seçili kanalın programları. */
class GuideProgrammeAdapter(
    private val onClick: (Programme) -> Unit
) : RecyclerView.Adapter<GuideProgrammeAdapter.Holder>() {

    private val items = mutableListOf<Programme>()

    fun setProgrammes(programmes: List<Programme>) {
        items.clear()
        items.addAll(programmes)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemGuideProgrammeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class Holder(private val binding: ItemGuideProgrammeBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            // Odaklanan programın açıklaması tamamen açılır
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.tvProgrammeDesc.maxLines = if (hasFocus) 6 else 1
                binding.tvProgrammeTitle.maxLines = if (hasFocus) 2 else 1
            }
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }

        fun bind(programme: Programme) {
            val now = System.currentTimeMillis()
            val isNow = now >= programme.start && now < programme.stop
            binding.tvProgrammeTime.text = programme.timeRange()
            binding.tvProgrammeTitle.text = programme.title
            binding.tvProgrammeNowBadge.visibility = if (isNow) View.VISIBLE else View.GONE
            if (programme.description.isNullOrBlank()) {
                binding.tvProgrammeDesc.visibility = View.GONE
            } else {
                binding.tvProgrammeDesc.visibility = View.VISIBLE
                binding.tvProgrammeDesc.text = programme.description
            }
        }
    }
}
