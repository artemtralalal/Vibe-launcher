package com.vibe.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class DragGridOverlay(context: Context) : View(context) {
    var cols: Int = 4
    var rows: Int = 6
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x14FFFFFF
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
        strokeWidth = resources.displayMetrics.density * 1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 8f * resources.displayMetrics.density
        val gap = 8f * resources.displayMetrics.density
        val usableW = width - pad * 2
        val usableH = height - pad * 2
        if (usableW <= 0 || usableH <= 0) return
        val cellW = (usableW - gap * (cols - 1)) / cols
        val cellH = (usableH - gap * (rows - 1)) / rows
        val r = 14f * resources.displayMetrics.density
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = pad + col * (cellW + gap)
                val top = pad + row * (cellH + gap)
                val rect = RectF(left, top, left + cellW, top + cellH)
                canvas.drawRoundRect(rect, r, r, fill)
                canvas.drawRoundRect(rect, r, r, stroke)
            }
        }
    }
}
