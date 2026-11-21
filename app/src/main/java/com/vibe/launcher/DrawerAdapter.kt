package com.vibe.launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DrawerAdapter(
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongPress: (AppEntry, View) -> Boolean,
    private val shape: () -> String = { "circle" }
) : RecyclerView.Adapter<DrawerAdapter.VH>() {

    private val items = mutableListOf<AppEntry>()

    fun submit(list: List<AppEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        holder.icon.clipToOutline = shape() != "square"
        holder.icon.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = when (shape()) {
                    "circle" -> view.width / 2f
                    "rounded" -> view.width * 0.22f
                    "squircle" -> view.width * 0.32f
                    else -> 0f
                }
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        holder.itemView.setOnClickListener { onClick(item, holder.icon) }
        holder.itemView.setOnLongClickListener { onLongPress(item, holder.itemView) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }
}
