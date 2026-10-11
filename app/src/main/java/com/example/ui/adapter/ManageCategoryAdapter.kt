package com.example.ui.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.data.db.entity.Category
import com.example.databinding.ItemManageCategoryBinding

class ManageCategoryAdapter(
    private val onEditClick: (Category) -> Unit,
    private val onDeleteClick: (Category) -> Unit
) : RecyclerView.Adapter<ManageCategoryAdapter.CategoryViewHolder>() {

    private val categories = mutableListOf<Category>()

    fun setCategories(newCategories: List<Category>) {
        categories.clear()
        categories.addAll(newCategories)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemManageCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(private val binding: ItemManageCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.categoryItemContainer.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
            }

            binding.btnEditCategory.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onEditClick(categories[pos])
                }
            }

            binding.btnDeleteCategory.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDeleteClick(categories[pos])
                }
            }
        }

        fun bind(category: Category) {
            binding.tvCategoryName.text = category.name
        }
    }
}
