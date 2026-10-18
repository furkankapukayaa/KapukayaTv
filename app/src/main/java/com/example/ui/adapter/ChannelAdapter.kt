package com.example.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.epg.EpgRepository
import com.example.data.model.Channel
import com.example.databinding.ItemChannelBinding
import com.example.util.TextUtil

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteToggle: (Channel) -> Unit,
    private val onChannelLongClick: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    /** Taşıma modundaki kanal (kırmızı çerçeveyle gösterilir). */
    var movingChannelId: String? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
        }

    /** Yayını açılmayan kanallar (soluk gösterilir). */
    var brokenIds: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(getItem(position))
        } else {
            holder.bindDynamic(getItem(position))
        }
    }

    /** EPG bilgisi değiştiğinde (ör. her dakika) kartları yeniden çizmeden günceller. */
    fun refreshDynamicContent() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
    }

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.cardChannel.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1.0f
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(if (hasFocus) 6f else 0f)
                    .setDuration(120)
                    .start()
            }

            // DPAD_CENTER / ENTER tıklama ve uzun basma, View tarafından otomatik yönetilir
            binding.cardChannel.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(position))
                }
            }

            binding.cardChannel.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelLongClick(getItem(position))
                    true
                } else {
                    false
                }
            }

            // Favori Butonu (dokunmatik ekranlar için): Anında görsel yıldız yanma efekti
            binding.btnFavorite.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val channel = getItem(position)
                    channel.isFavorite = !channel.isFavorite
                    bindFavorite(channel.isFavorite)

                    binding.btnFavorite.animate()
                        .scaleX(1.25f)
                        .scaleY(1.25f)
                        .setDuration(100)
                        .withEndAction {
                            binding.btnFavorite.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(100)
                                .start()
                        }
                        .start()

                    onFavoriteToggle(channel)
                }
            }
        }

        fun bind(channel: Channel) {
            binding.tvChannelName.text = channel.displayName()

            if (!channel.logoUrl.isNullOrBlank()) {
                binding.ivChannelLogo.load(channel.logoUrl) {
                    placeholder(R.drawable.ic_tv)
                    error(R.drawable.ic_tv)
                    crossfade(true)
                }
            } else {
                binding.ivChannelLogo.setImageResource(R.drawable.ic_tv)
            }

            if (channel.quality.isNullOrBlank()) {
                binding.tvQualityBadge.visibility = View.GONE
            } else {
                binding.tvQualityBadge.visibility = View.VISIBLE
                binding.tvQualityBadge.text = channel.quality
            }

            bindFavorite(channel.isFavorite)
            bindDynamic(channel)
        }

        fun bindDynamic(channel: Channel) {
            binding.cardChannel.isActivated = channel.id == movingChannelId

            val isBroken = channel.id in brokenIds
            binding.ivChannelLogo.alpha = if (isBroken) 0.35f else 1f
            binding.tvChannelName.alpha = if (isBroken) 0.55f else 1f

            val (now, _) = EpgRepository.nowAndNext(channel)
            if (isBroken) {
                binding.tvChannelCategory.text = "⚠ Yayın şu an açılmıyor"
                binding.pbEpgProgress.visibility = View.GONE
            } else if (now != null) {
                binding.tvChannelCategory.text = now.title
                binding.pbEpgProgress.visibility = View.VISIBLE
                binding.pbEpgProgress.progress = now.progress()
            } else {
                binding.tvChannelCategory.text = TextUtil.displayGroups(channel.groupTitle)
                binding.pbEpgProgress.visibility = View.GONE
            }
        }

        private fun bindFavorite(isFavorite: Boolean) {
            val favRes = if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_border
            binding.btnFavorite.setImageResource(favRes)
            binding.btnFavorite.imageTintList = if (isFavorite) {
                ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, R.color.accent_gold))
            } else {
                ContextCompat.getColorStateList(binding.root.context, R.color.text_muted)
            }
        }
    }

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val PAYLOAD_STATE = "state"
    }
}
