package com.lumiyaviewer.lumiya.ui.inventory

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lumiyaviewer.lumiya.R
import com.lumiyaviewer.lumiya.databinding.ItemInventoryBinding
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryItem
import com.lumiyaviewer.lumiya.slproto.inventory.InventoryType

/**
 * Flat list that renders a collapsible inventory tree. Rows are `InventoryItem`
 * records carrying their own depth, so expanding a folder is an insert into the
 * backing list instead of a recursive adapter.
 */
class InventoryAdapter(
    private val rows: MutableList<InventoryItem>,
    private val onRowClick: (String) -> Unit
) : RecyclerView.Adapter<InventoryAdapter.InventoryHolder>() {

    class InventoryHolder(val binding: ItemInventoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InventoryHolder {
        val binding = ItemInventoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return InventoryHolder(binding)
    }

    override fun onBindViewHolder(holder: InventoryHolder, position: Int) {
        val item = rows[position]
        val context = holder.itemView.context
        holder.binding.iconEl.setImageResource(iconFor(item.type))
        holder.binding.nameEl.text = prefix(item) + item.name
        holder.binding.typeEl.text = subtitle(item, holder)
        val density = holder.itemView.resources.displayMetrics.density
        holder.binding.nameEl.setPadding((item.depth * 20 * density).toInt(), 0, 0, 0)
        holder.itemView.setOnClickListener { onRowClick(item.id) }
    }

    override fun getItemCount(): Int = rows.size

    private fun prefix(item: InventoryItem): String {
        if (!item.isFolder) {
            return ""
        }
        return when {
            item.loading -> "…  "
            item.expanded -> "▾  "
            else -> "▸  "
        }
    }

    private fun subtitle(item: InventoryItem, holder: InventoryHolder): String {
        val context = holder.itemView.context
        if (item.loading) {
            return context.getString(R.string.inventory_loading)
        }
        if (!item.isFolder) {
            return item.type.name
        }
        if (item.childCount >= 0) {
            return context.getString(R.string.inventory_children, item.childCount)
        }
        return context.getString(R.string.inventory_type_folder)
    }

    private fun iconFor(type: InventoryType): Int {
        return when (type) {
            InventoryType.FOLDER -> R.drawable.ic_folder
            InventoryType.NOTECARD -> R.drawable.ic_note
            InventoryType.TEXTURE -> R.drawable.ic_texture
            else -> R.drawable.ic_item
        }
    }
}
