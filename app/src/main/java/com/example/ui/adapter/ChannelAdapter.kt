package com.example.ui.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.R
import com.example.data.model.Channel
import com.example.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteToggle: (Channel) -> Unit,
    private val onAddCategoryClick: ((Channel) -> Unit)? = null,
    private val onMoveUpClick: ((Channel) -> Unit)? = null,
    private val onMoveDownClick: ((Channel) -> Unit)? = null
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    var isFavoritesTab: Boolean = false

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

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.cardChannel.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate()
                        .scaleX(1.02f)
                        .scaleY(1.02f)
                        .translationZ(3f)
                        .setDuration(100)
                        .start()
                } else {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(100)
                        .start()
                }
            }

            binding.cardChannel.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(position))
                }
            }

            binding.cardChannel.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onChannelClick(getItem(position))
                        return@setOnKeyListener true
                    }
                }
                false
            }

            // Favori Butonu: Anında görsel yıldız yanma efekti
            binding.btnFavorite.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val channel = getItem(position)
                    val newFavState = !channel.isFavorite
                    channel.isFavorite = newFavState

                    // Anında 0ms yıldız değiştirme, renk ve ölçekleme efekti
                    val favRes = if (newFavState) R.drawable.ic_star_filled else R.drawable.ic_star_border
                    val tintColor = if (newFavState) R.color.accent_gold else R.color.white
                    binding.btnFavorite.setImageResource(favRes)
                    binding.btnFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(binding.root.context, tintColor)
                    )
                    
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

            // Kategoriye Ekle Butonu
            binding.btnAddCategory.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onAddCategoryClick?.invoke(getItem(position))
                }
            }

            binding.btnMoveUp.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMoveUpClick?.invoke(getItem(position))
                }
            }

            binding.btnMoveDown.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onMoveDownClick?.invoke(getItem(position))
                }
            }
        }

        fun bind(channel: Channel) {
            val displayName = if (channel.channelNumber > 0) "${channel.channelNumber}. ${channel.name}" else channel.name
            binding.tvChannelName.text = displayName
            binding.tvChannelCategory.text = channel.groupTitle ?: "Genel"

            if (!channel.logoUrl.isNullOrBlank()) {
                binding.ivChannelLogo.load(channel.logoUrl) {
                    placeholder(R.drawable.ic_tv)
                    error(R.drawable.ic_tv)
                    crossfade(true)
                }
            } else {
                binding.ivChannelLogo.setImageResource(R.drawable.ic_tv)
            }

            val favRes = if (channel.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_border
            val tintColor = if (channel.isFavorite) R.color.accent_gold else R.color.white
            binding.btnFavorite.setImageResource(favRes)
            binding.btnFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(binding.root.context, tintColor)
            )

            binding.reorderContainer.visibility = if (isFavoritesTab) View.VISIBLE else View.GONE
        }
    }

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id && oldItem.isFavorite == newItem.isFavorite && oldItem.channelNumber == newItem.channelNumber
        }
    }
}
