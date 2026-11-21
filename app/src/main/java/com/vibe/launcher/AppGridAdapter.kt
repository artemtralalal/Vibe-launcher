package com.vibe.launcher

import android.content.ClipData
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppGridAdapter(
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongPressDrag: (AppEntry, View) -> Boolean,
    private val onEmptyLongPress: () -> Unit
) : RecyclerView.Adapter<AppGridAdapter.VH>() {

    private val items = mutableListOf<AppEntry>()
    private val allItems = mutableListOf<AppEntry>()

    fun submitApps(apps: List<AppEntry>) {
        allItems.clear()
        allItems.addAll(apps)
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        items.clear()
        if (q.isEmpty()) {
            items.addAll(allItems)
        } else {
            items.addAll(allItems.filter { it.label.lowercase().contains(q) })
        }
        notifyDataSetChanged()
    }

    fun currentItems(): List<AppEntry> = items.toList()
    fun all(): List<AppEntry> = allItems.toList()

    fun moveItem(fromKey: String, toIndex: Int) {
        val from = allItems.indexOfFirst { it.key == fromKey }
        if (from == -1) return
        val item = allItems.removeAt(from)
        val target = toIndex.coerceIn(0, allItems.size)
        allItems.add(target, item)
        items.clear()
        items.addAll(allItems)
        notifyDataSetChanged()
    }

    fun removeByKey(key: String): AppEntry? {
        val idx = allItems.indexOfFirst { it.key == key }
        if (idx == -1) return null
        val item = allItems.removeAt(idx)
        items.clear()
        items.addAll(allItems)
        notifyDataSetChanged()
        return item
    }

    fun insertAt(index: Int, entry: AppEntry) {
        val existing = allItems.indexOfFirst { it.key == entry.key }
        if (existing != -1) allItems.removeAt(existing)
        val target = index.coerceIn(0, allItems.size)
        allItems.add(target, entry)
        items.clear()
        items.addAll(allItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        holder.itemView.setOnClickListener { onClick(item, holder.icon) }
        holder.itemView.setOnLongClickListener { onLongPressDrag(item, holder.itemView) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }
}
