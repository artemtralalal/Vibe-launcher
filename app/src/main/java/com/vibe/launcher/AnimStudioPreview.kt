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
    data class Corner(var x: Float, var y: Float)

    var points = mutableListOf<PPoint>()
    var corners = mutableListOf(Corner(0f,0f), Corner(0f,0f), Corner(0f,0f), Corner(0f,0f)) // TL,TR,BR,BL
    var onChanged: (() -> Unit)? = null
    var mode: String = "points" // points | corners
    var target: String = "window"
    var pressure: Int = 0
    var perspX: Int = 0; var perspY: Int = 0; var perspZ: Int = 0
    var testIcon: Bitmap? = null

    private val gridPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x664A90E2.toInt() }
    private val winPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332196F3.toInt(); style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF.toInt() }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A6A.toInt() }
    private val pointRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CD964.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER }
    private var dragCorner = -1

    fun defaultWindowRect(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val ww = w * 0.42f; val hh = h * 0.72f
        val l = (w - ww) / 2f; val t = h * 0.08f
        return RectF(l, t, l + ww, t + hh)
    }
    fun defaultIconRect(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w,h)*0.14f
        return RectF(w/2-s, h*0.78f-s, w/2+s, h*0.78f+s)
    }

    private fun activeRect() = if (target == "icon") defaultIconRect() else defaultWindowRect()

    private fun computeTilt(): FloatArray {
        val p = pressure.coerceIn(0, 200) / 100f
        var rx = 0f; var ry = 0f; var rz = 0f
        if (p > 0.001f) {
            points.forEach { pt ->
                val dx = (pt.x - 0.5f); val dy = (pt.y - 0.5f); val s = pt.strength * p
                rx += (-dy) * 35f * s; ry += dx * 35f * s; rz += dx * -8f * s
            }
            rx += perspX.coerceIn(-100, 100) / 100f * 25f * p
            ry += perspY.coerceIn(-100, 100) / 100f * 25f * p
            rz += perspZ.coerceIn(-100, 100) / 100f * 20f * p
        }
        val tl=corners[0]; val tr=corners[1]; val br=corners[2]; val bl=corners[3]
        rx += ((tl.y + tr.y) - (bl.y + br.y)) * 90f
        ry += ((tr.x + br.x) - (tl.x + bl.x)) * 90f
        rz += ((tr.y - tl.y) + (br.y - bl.y)) * 30f
        return floatArrayOf(rx.coerceIn(-80f,80f), ry.coerceIn(-80f,80f), rz.coerceIn(-45f,45f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(0xFF151922.toInt())
        val step = 36f
        var gx = 0f; while (gx <= w) { canvas.drawLine(gx,0f,gx,h,gridPaint); gx += step }
        var gy = 0f; while (gy <= h) { canvas.drawLine(0f,gy,w,gy,gridPaint); gy += step }

        val wr = defaultWindowRect()
        val ir = defaultIconRect()
        val tilt = computeTilt()

        // draw window with live transform
        drawTransformedRect(canvas, wr, tilt, winPaint, "Окно предпросмотра", true)
        // draw icon
        drawIcon(canvas, ir, tilt)

        // draw points on top
        points.forEachIndexed { i, p ->
            val px = p.x*w; val py = p.y*h
            canvas.drawCircle(px, py, 12f, pointPaint)
            canvas.drawCircle(px, py, 12f, pointRing)
            canvas.drawText("${i+1}", px, py - 16f, smallPaint)
        }
        // corner handles for active rect
        val r = activeRect()
        val pts = cornerPoints(r)
        for (i in 0 until 4) {
            val c = pts[i]
            canvas.drawCircle(c.x, c.y, 13f, handlePaint)
            canvas.drawCircle(c.x, c.y, 13f, borderPaint)
        }
    }

    private fun drawTransformedRect(canvas: Canvas, r: RectF, tilt: FloatArray, paint: Paint, label: String, drawLabel: Boolean) {
        canvas.save()
        val cx = r.centerX(); val cy = r.centerY()
        val cam = Camera()
        cam.save()
        cam.rotateX(tilt[0]); cam.rotateY(tilt[1]); cam.rotateZ(tilt[2])
        val m = Matrix()
        cam.getMatrix(m)
        cam.restore()
        m.preTranslate(-cx, -cy)
        m.postTranslate(cx, cy)
        canvas.concat(m)
        val rr = RectF(r)
        canvas.drawRoundRect(rr, 28f, 28f, paint)
        canvas.drawRoundRect(rr, 28f, 28f, winPaint2)
        canvas.drawRoundRect(rr, 28f, 28f, borderPaint)
        if (drawLabel) {
            textPaint.textSize = 28f
            canvas.drawText(label, r.centerX(), r.centerY() - 10f, textPaint)
            smallPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("живая перспектива", r.centerX(), r.centerY() + 24f, smallPaint)
        }
        canvas.restore()
    }

    private fun drawIcon(canvas: Canvas, r: RectF, tilt: FloatArray) {
        canvas.save()
        val cx = r.centerX(); val cy = r.centerY()
        val t = if (target == "icon") tilt else floatArrayOf(0f,0f,0f)
        val cam = Camera(); cam.save(); cam.rotateX(t[0]); cam.rotateY(t[1]); cam.rotateZ(t[2]); cam.translate(0f,0f, -20f)
        val m = Matrix(); cam.getMatrix(m); cam.restore()
        m.preTranslate(-cx, -cy); m.postTranslate(cx, cy)
        canvas.concat(m)
        val rr = RectF(r)
        // 3D shadow/depth
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL) }
        canvas.drawRoundRect(RectF(rr.left+6, rr.top+10, rr.right+6, rr.bottom+10), 24f, 24f, shadow)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(rr.left, rr.top, rr.right, rr.bottom, 0xFF6EE7FF.toInt(), 0xFF3B82F6.toInt(), Shader.TileMode.CLAMP) }
        canvas.drawRoundRect(rr, 24f, 24f, base)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x88FFFFFF.toInt() }
        canvas.drawRoundRect(rr, 24f, 24f, edge)
        if (testIcon != null) {
            val src = Rect(0,0,testIcon!!.width, testIcon!!.height)
            val dst = RectF(r.left+10,r.top+10,r.right-10,r.bottom-10)
            canvas.drawBitmap(testIcon!!, src, dst, null)
        } else {
            textPaint.textSize = 36f
            canvas.drawText("☎", r.centerX(), r.centerY() + 6f, textPaint)
            smallPaint.textSize = 16f
            canvas.drawText("Vibe Dialer", r.centerX(), r.bottom - 12f, smallPaint)
        }
        canvas.restore()
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
                parent?.requestDisallowInterceptTouchEvent(true)
                dragCorner = pts.indexOfFirst { hypot(it.x - event.x, it.y - event.y) < 44f }
                if (dragCorner < 0 && mode == "points") {
                    val nx = (event.x/width.coerceAtLeast(1)).coerceIn(0f,1f)
                    val ny = (event.y/height.coerceAtLeast(1)).coerceIn(0f,1f)
                    if (points.size < 6) points.add(PPoint(nx,ny,1f))
                    onChanged?.invoke(); invalidate(); return true
                }
                return dragCorner >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (dragCorner >= 0) {
                    val dx = (event.x - (if (dragCorner==0||dragCorner==3) r.left else r.right)) / r.width().coerceAtLeast(1f)
                    val dy = (event.y - (if (dragCorner<2) r.top else r.bottom)) / r.height().coerceAtLeast(1f)
                    corners[dragCorner].x = dx.coerceIn(-0.6f,0.6f)
                    corners[dragCorner].y = dy.coerceIn(-0.6f,0.6f)
                    onChanged?.invoke(); invalidate(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragCorner = -1; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return super.onTouchEvent(event)
    }

    fun resetCorners() { for (c in corners) { c.x = 0f; c.y = 0f }; invalidate(); onChanged?.invoke() }
    fun cornerTilt(): FloatArray {
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
        if (s.isBlank()) { resetCornersSilent(); return }
        val parts = s.split(";")
        for (i in 0 until 4) { if (i < parts.size) { val a = parts[i].split(","); if (a.size>=2) { corners[i].x = a[0].toFloatOrNull()?:0f; corners[i].y = a[1].toFloatOrNull()?:0f } } else { corners[i].x=0f; corners[i].y=0f } }
        invalidate()
    }
    private fun resetCornersSilent() { for (c in corners) { c.x=0f; c.y=0f }; invalidate() }
}
