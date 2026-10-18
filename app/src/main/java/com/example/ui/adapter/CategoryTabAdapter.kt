package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.databinding.ItemCategoryTabBinding

enum class TabType {
    FAVORITES,
    RECENT,
    ALL,
    CUSTOM,
    GROUP
}

data class CategoryTabItem(
    val id: String,
    val name: String,
    val type: TabType,
    val customCategoryId: Long = -1L,
    /** GROUP sekmeleri için M3U grup adı */
    val groupName: String? = null
)

class CategoryTabAdapter(
    private val onTabClick: (CategoryTabItem) -> Unit
) : RecyclerView.Adapter<CategoryTabAdapter.TabViewHolder>() {

    private val items = mutableListOf<CategoryTabItem>()
    private var selectedIndex = 0

    fun setItems(newItems: List<CategoryTabItem>) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun setSelectedPosition(position: Int) {
        val oldIndex = selectedIndex
        selectedIndex = position
        notifyItemChanged(oldIndex)
        notifyItemChanged(selectedIndex)
    }

    fun selectedId(): String? = items.getOrNull(selectedIndex)?.id

    /** Sekmeyi kimliğiyle seçer. Bulunamazsa -1 döner. */
    fun selectById(id: String): Int {
        val index = items.indexOfFirst { it.id == id }
        if (index != -1) setSelectedPosition(index)
        return index
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
                    view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(100).start()
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
        }

        fun bind(item: CategoryTabItem, isSelected: Boolean) {
            binding.tvCategoryTabName.text = item.name
            binding.root.isSelected = isSelected
        }
    }
}
