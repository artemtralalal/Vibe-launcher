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
    private val shape: () -> String = { "circle" },
    private val cornerRadii: () -> FloatArray? = { null },
    private val iconScale: () -> Int = { 100 }
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
        val scale = iconScale().coerceIn(60, 140) / 100f
        val base = (56 * holder.itemView.resources.displayMetrics.density).toInt()
        val sz = (base * scale).toInt().coerceAtLeast(1)
        val lp = holder.icon.layoutParams
        if (lp.width != sz || lp.height != sz) { lp.width = sz; lp.height = sz; holder.icon.layoutParams = lp }
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        AppGridAdapter.applyShape(holder.icon, shape(), cornerRadii())
        holder.itemView.setOnClickListener { onClick(item, holder.icon) }
        holder.itemView.setOnLongClickListener { onLongPress(item, holder.itemView) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }
}
