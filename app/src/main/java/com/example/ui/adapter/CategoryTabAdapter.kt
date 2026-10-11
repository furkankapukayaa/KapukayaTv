package com.example.ui.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemCategoryTabBinding

enum class TabType {
    FAVORITES,
    ALL,
    CUSTOM
}

data class CategoryTabItem(
    val id: String,
    val name: String,
    val type: TabType,
    val customCategoryId: Long = -1L
)

class CategoryTabAdapter(
    private val onTabClick: (CategoryTabItem) -> Unit
) : RecyclerView.Adapter<CategoryTabAdapter.TabViewHolder>() {

    private val items = mutableListOf<CategoryTabItem>()
    private var selectedIndex = 0

    fun setItems(newItems: List<CategoryTabItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelectedPosition(position: Int) {
        val oldIndex = selectedIndex
        selectedIndex = position
        notifyItemChanged(oldIndex)
        notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemCategoryTabBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(items[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class TabViewHolder(private val binding: ItemCategoryTabBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
            }

            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    setSelectedPosition(position)
                    onTabClick(items[position])
                }
            }

            binding.root.setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        setSelectedPosition(position)
                        onTabClick(items[position])
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        fun bind(item: CategoryTabItem, isSelected: Boolean) {
            binding.tvCategoryTabName.text = item.name
            binding.root.isSelected = isSelected
        }
    }
}
