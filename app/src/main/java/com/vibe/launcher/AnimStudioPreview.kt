package com.vibe.launcher

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class AnimStudioPreview @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class PPoint(var x: Float, var y: Float, var strength: Float = 1f)
    data class Corner(var x: Float, var y: Float) // normalized 0..1 offset from default corner

    var points = mutableListOf<PPoint>()
    var corners = mutableListOf(Corner(0f,0f), Corner(0f,0f), Corner(0f,0f), Corner(0f,0f)) // TL,TR,BR,BL
    var onChanged: (() -> Unit)? = null
    var mode: String = "points" // points | corners
    var target: String = "window" // window | icon

    private val gridPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553B82F6.toInt() }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF.toInt() }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A6A.toInt() }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CD964.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 28f }
    private var dragCorner = -1

    fun defaultWindowRect(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        return RectF(w*0.2f, h*0.18f, w*0.8f, h*0.62f)
    }
    fun defaultIconRect(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w,h)*0.16f
        return RectF(w/2-s, h*0.72f, w/2+s, h*0.72f+s*2)
    }

    private fun activeRect() = if (target == "icon") defaultIconRect() else defaultWindowRect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(0x11111822)
        val step = 40f
        var x = 0f; while (x <= w) { canvas.drawLine(x,0f,x,h,gridPaint); x += step }
        var y = 0f; while (y <= h) { canvas.drawLine(0f,y,w,y,gridPaint); y += step }

        val wr = defaultWindowRect()
        val ir = defaultIconRect()
        val path = Path()
        val r = activeRect()
        val pts = cornerPoints(r)
        path.moveTo(pts[0].x, pts[0].y); path.lineTo(pts[1].x, pts[1].y); path.lineTo(pts[2].x, pts[2].y); path.lineTo(pts[3].x, pts[3].y); path.close()
        canvas.drawPath(path, if (target=="window") winPaint else Paint(winPaint).apply { alpha=30 })
        canvas.drawPath(path, borderPaint)

        // icon
        val irp = Path(); irp.addRoundRect(if(target=="icon") RectF(pts[0].x,pts[0].y,pts[2].x,pts[2].y) else ir, 16f,16f, Path.Direction.CW)
        canvas.drawPath(irp, iconPaint)
        canvas.drawText("Vibe", ir.centerX()-28f, ir.centerY()+8f, textPaint)

        // points
        points.forEach { p -> canvas.drawCircle(p.x*w, p.y*h, 10f, pointPaint) }
        // corner handles
        for (i in 0 until 4) { val c = pts[i]; canvas.drawCircle(c.x, c.y, 14f, handlePaint); canvas.drawCircle(c.x,c.y,14f,borderPaint) }
    }

    private fun cornerPoints(r: RectF): List<PointF> {
        val dx = r.width(); val dy = r.height()
        return listOf(
            PointF(r.left + corners[0].x*dx, r.top + corners[0].y*dy),
            PointF(r.right + corners[1].x*dx, r.top + corners[1].y*dy),
            PointF(r.right + corners[2].x*dx, r.bottom + corners[2].y*dy),
            PointF(r.left + corners[3].x*dx, r.bottom + corners[3].y*dy)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = activeRect(); val pts = cornerPoints(r)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragCorner = pts.indexOfFirst { hypot(it.x - event.x, it.y - event.y) < 40f }
                if (dragCorner < 0 && mode == "points") {
                    val nx = (event.x/width.coerceAtLeast(1)).coerceIn(0f,1f)
                    val ny = (event.y/height.coerceAtLeast(1)).coerceIn(0f,1f)
                    if (points.size < 6) points.add(PPoint(nx,ny,1f))
                    onChanged?.invoke(); invalidate(); return true
                }
                return dragCorner >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCorner >= 0) {
                    val dx = (event.x - (if (dragCorner==0||dragCorner==3) r.left else r.right)) / r.width().coerceAtLeast(1f)
                    val dy = (event.y - (if (dragCorner<2) r.top else r.bottom)) / r.height().coerceAtLeast(1f)
                    corners[dragCorner].x = dx.coerceIn(-0.5f,0.5f)
                    corners[dragCorner].y = dy.coerceIn(-0.5f,0.5f)
                    onChanged?.invoke(); invalidate(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragCorner = -1 }
        }
        return super.onTouchEvent(event)
    }

    fun resetCorners() { for (c in corners) { c.x = 0f; c.y = 0f }; invalidate(); onChanged?.invoke() }
    fun cornerTilt(): FloatArray {
        // approximate rotation/scale from corner offsets
        val tl=corners[0]; val tr=corners[1]; val br=corners[2]; val bl=corners[3]
        val rx = ((tl.y + tr.y) - (bl.y + br.y)) * 90f
        val ry = ((tr.x + br.x) - (tl.x + bl.x)) * 90f
        val rz = ((tr.y - tl.y) + (br.y - bl.y)) * 30f
        val sx = 1f + ((tr.x - tl.x) + (br.x - bl.x)) * 0.5f
        val sy = 1f + ((bl.y - tl.y) + (br.y - tr.y)) * 0.5f
        return floatArrayOf(rx, ry, rz, sx.coerceIn(0.5f,1.5f), sy.coerceIn(0.5f,1.5f))
    }

    fun cornersString(): String = corners.joinToString(";") { "${it.x},${it.y}" }
    fun loadCorners(s: String) {
        if (s.isBlank()) return
        val parts = s.split(";")
        for (i in 0 until minOf(4, parts.size)) {
            val a = parts[i].split(","); if (a.size>=2) { corners[i].x = a[0].toFloatOrNull()?:0f; corners[i].y = a[1].toFloatOrNull()?:0f }
        }
        invalidate()
    }
}
