package com.vibe.launcher

import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppGridAdapter(
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongPressDrag: (AppEntry, View) -> Boolean,
    private val onEmptyLongPress: () -> Unit,
    private val shape: () -> String = { "circle" },
    private val showLabels: () -> Boolean = { true },
    private val cornerRadii: () -> FloatArray? = { null },
    private val iconScale: () -> Int = { 100 },
    private val iconTintMode: () -> String = { "default" },
    private val iconTintColor: () -> Int = { 0xFFFFFFFF.toInt() }
) : RecyclerView.Adapter<AppGridAdapter.VH>() {

    var pageSize: Int = 24
        private set
    private val slots = MutableList(pageSize) { null as AppEntry? }

    fun resize(newSize: Int) {
        val n = newSize.coerceIn(8, 48)
        if (n == pageSize) return
        if (n > slots.size) {
            while (slots.size < n) slots.add(null)
        } else if (n < slots.size) {
            while (slots.size > n) slots.removeAt(slots.lastIndex)
        }
        pageSize = n
        notifyDataSetChanged()
    }

    fun submitApps(apps: List<AppEntry?>) {
        ensureSize()
        for (i in 0 until pageSize) slots[i] = apps.getOrNull(i)
        notifyDataSetChanged()
    }

    fun submitAppList(apps: List<AppEntry>) {
        ensureSize()
        for (i in 0 until pageSize) slots[i] = apps.getOrNull(i)
        notifyDataSetChanged()
    }

    private fun ensureSize() {
        while (slots.size < pageSize) slots.add(null)
        if (slots.size > pageSize) slots.subList(pageSize, slots.size).clear()
    }

    fun allSlots(): List<AppEntry?> = slots.take(pageSize).toList()
    fun all(): List<AppEntry> = slots.take(pageSize).filterNotNull()
    fun currentItems(): List<AppEntry> = all()

    fun getSlot(index: Int): AppEntry? = slots.getOrNull(index)

    fun setSlot(index: Int, app: AppEntry?) {
        if (index in 0 until pageSize) { slots[index] = app; notifyDataSetChanged() }
    }

    fun removeByKey(key: String): AppEntry? {
        val idx = slots.indexOfFirst { it?.key == key }
        if (idx == -1) return null
        val item = slots[idx]
        slots[idx] = null
        notifyDataSetChanged()
        return item
    }

    fun insertAt(index: Int, entry: AppEntry) {
        val existing = slots.indexOfFirst { it?.key == entry.key }
        if (existing != -1) slots[existing] = null
        val target = index.coerceIn(0, pageSize - 1)
        if (slots[target] == null) {
            slots[target] = entry
        } else {
            val empty = findNearestEmpty(target)
            if (empty != -1) {
                shiftToward(target, empty)
                slots[target] = entry
            } else {
                slots[target] = entry
            }
        }
        notifyDataSetChanged()
    }

    private fun findNearestEmpty(from: Int): Int {
        for (d in 1 until pageSize) {
            val r = from + d; if (r < pageSize && slots[r] == null) return r
            val l = from - d; if (l >= 0 && slots[l] == null) return l
        }
        return -1
    }

    private fun shiftToward(target: Int, empty: Int) {
        if (empty > target) {
            for (i in empty downTo target + 1) slots[i] = slots[i - 1]
        } else {
            for (i in empty until target) slots[i] = slots[i + 1]
        }
        slots[target] = null
    }

    fun moveItem(fromKey: String, toIndex: Int) {
        val from = slots.indexOfFirst { it?.key == fromKey }
        if (from == -1) return
        val item = slots[from] ?: return
        slots[from] = null
        insertAt(toIndex, item)
    }

    fun filter(query: String) { /* no-op for fixed slots */ }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = pageSize

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = slots.getOrNull(position)
        if (item == null) {
            holder.icon.setImageDrawable(null)
            holder.icon.alpha = 0f
            holder.icon.clearColorFilter()
            holder.label.text = ""
            holder.label.visibility = View.GONE
            holder.itemView.setOnClickListener { }
            holder.itemView.setOnLongClickListener { onEmptyLongPress(); true }
            return
        }
        val scale = iconScale().coerceIn(60, 140) / 100f
        val base = (56 * holder.itemView.resources.displayMetrics.density).toInt()
        val sz = (base * scale).toInt().coerceAtLeast(1)
        val lp = holder.icon.layoutParams
        if (lp.width != sz || lp.height != sz) { lp.width = sz; lp.height = sz; holder.icon.layoutParams = lp }
        holder.icon.alpha = 1f
        holder.icon.setImageDrawable(item.icon)
        applyIconTint(holder.icon, iconTintMode(), iconTintColor())
        holder.label.text = item.label
        holder.label.visibility = if (showLabels()) View.VISIBLE else View.GONE
        applyShape(holder.icon, shape(), cornerRadii())
        holder.itemView.setOnClickListener { onClick(item, holder.icon) }
        holder.itemView.setOnLongClickListener { onLongPressDrag(item, holder.itemView) }
    }

    companion object {
        fun applyIconTint(icon: ImageView, mode: String, color: Int) {
            when (mode) {
                "light" -> { icon.alpha = 1f; icon.setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_ATOP) }
                "dark" -> { icon.alpha = 1f; icon.setColorFilter(0xFF222222.toInt(), android.graphics.PorterDuff.Mode.SRC_ATOP) }
                "transparent" -> { icon.alpha = 0.55f; icon.clearColorFilter() }
                "color" -> { icon.alpha = 1f; icon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP) }
                else -> { icon.alpha = 1f; icon.clearColorFilter() }
            }
        }
        fun applyShape(view: ImageView, shape: String, radii: FloatArray? = null) {
            val custom = radii != null && radii.size == 8
            view.clipToOutline = shape != "square" || custom
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = view.width; val h = view.height
                    if (w <= 0 || h <= 0) return
                    if (custom) {
                        val path = Path()
                        path.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radii!!, Path.Direction.CW)
                        if (Build.VERSION.SDK_INT >= 30) outline.setPath(path) else @Suppress("DEPRECATION") outline.setConvexPath(path)
                        return
                    }
                    val r = when (shape) {
                        "circle" -> minOf(w, h) / 2f
                        "rounded" -> minOf(w, h) * 0.22f
                        "squircle" -> minOf(w, h) * 0.32f
                        else -> 0f
                    }
                    if (r <= 0f) outline.setRect(0, 0, w, h) else outline.setRoundRect(0, 0, w, h, r)
                }
            }
            view.invalidateOutline()
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
    }
}
