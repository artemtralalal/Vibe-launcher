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
    var mode: String = "points" // points | corners | deform
    var target: String = "window"
    var pressure: Int = 0
    var perspX: Int = 0; var perspY: Int = 0; var perspZ: Int = 0
    var testIcon: Bitmap? = null
    var targetPoint = PointF(0.5f, 0.85f) // suction target in view coords 0..1
    var previewProgress: Float = 1f // 0..1 animation progress for genie preview

    private val gridPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val winPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x554A90E2.toInt() }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF.toInt() }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A6A.toInt() }
    private val pointRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CD964.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }
    private var dragCorner = -1
    private var dragTarget = false

    fun defaultWindowRect(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val ww = w * 0.46f; val hh = h * 0.62f
        val l = (w - ww) / 2f; val t = h * 0.08f
        return RectF(l, t, l + ww, t + hh)
    }
    fun defaultIconRect(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w,h)*0.12f
        val tx = targetPoint.x * w; val ty = targetPoint.y * h
        return RectF(tx-s, ty-s, tx+s, ty+s)
    }

    private fun activeRect() = if (target == "icon") defaultIconRect() else defaultWindowRect()

    private fun computeTilt(): FloatArray {
        val p = pressure.coerceIn(0, 200) / 100f
        var rx = 0f; var ry = 0f; var rz = 0f
        if (p > 0.001f) {
            points.forEach { pt ->
                val dx = (pt.x - 0.5f); val dy = (pt.y - 0.5f); val s = pt.strength * p
                rx += (-dy) * 45f * s; ry += dx * 45f * s; rz += dx * -10f * s
            }
            rx += perspX.coerceIn(-100, 100) / 100f * 35f * p
            ry += perspY.coerceIn(-100, 100) / 100f * 35f * p
            rz += perspZ.coerceIn(-100, 100) / 100f * 25f * p
        }
        val tl=corners[0]; val tr=corners[1]; val br=corners[2]; val bl=corners[3]
        rx += ((tl.y + tr.y) - (bl.y + br.y)) * 120f
        ry += ((tr.x + br.x) - (tl.x + bl.x)) * 120f
        rz += ((tr.y - tl.y) + (br.y - bl.y)) * 40f
        return floatArrayOf(rx.coerceIn(-85f,85f), ry.coerceIn(-85f,85f), rz.coerceIn(-45f,45f))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(0xFF121720.toInt())
        val step = 32f
        var gx = 0f; while (gx <= w) { canvas.drawLine(gx,0f,gx,h,gridPaint); gx += step }
        var gy = 0f; while (gy <= h) { canvas.drawLine(0f,gy,w,gy,gridPaint); gy += step }

        val wr = defaultWindowRect()
        val ir = defaultIconRect()
        val tilt = computeTilt()

        // target icon marker
        drawIcon(canvas, ir, if (target=="icon") tilt else floatArrayOf(0f,0f,0f))
        // window with live transform or genie deform
        if (mode == "deform") drawGenie(canvas, wr, ir) else drawTransformedRect(canvas, wr, tilt)

        // points
        points.forEachIndexed { i, p ->
            val px = p.x*w; val py = p.y*h
            canvas.drawCircle(px, py, 13f, pointPaint)
            canvas.drawCircle(px, py, 13f, pointRing)
            smallPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${i+1}", px, py - 18f, smallPaint)
        }
        // corner handles
        val r = activeRect()
        val pts = cornerPoints(r)
        for (i in 0 until 4) {
            val c = pts[i]
            canvas.drawCircle(c.x, c.y, 14f, handlePaint)
            canvas.drawCircle(c.x, c.y, 14f, borderPaint)
        }
        // target marker
        canvas.drawCircle(targetPoint.x*w, targetPoint.y*h, 10f, targetPaint)
        smallPaint.color = 0xFFFFD54F.toInt()
        canvas.drawText("цель", targetPoint.x*w, targetPoint.y*h - 14f, smallPaint)
        smallPaint.color = 0xAAFFFFFF.toInt()
    }

    private fun drawTransformedRect(canvas: Canvas, r: RectF, tilt: FloatArray) {
        canvas.save()
        val cx = r.centerX(); val cy = r.centerY()
        val cam = Camera()
        cam.save()
        cam.rotateX(tilt[0]); cam.rotateY(tilt[1]); cam.rotateZ(tilt[2])
        cam.translate(0f, 0f, -abs(tilt[0]+tilt[1])*0.5f)
        val m = Matrix(); cam.getMatrix(m); cam.restore()
        m.preTranslate(-cx, -cy); m.postTranslate(cx, cy)
        canvas.concat(m)
        // shadow
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL) }
        canvas.drawRoundRect(RectF(r.left+8,r.top+14,r.right+8,r.bottom+14), 30f,30f, shadow)
        val grad = LinearGradient(r.left,r.top,r.right,r.bottom,0x884A90E2.toInt(),0x663B82F6.toInt(),Shader.TileMode.CLAMP)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad }
        canvas.drawRoundRect(r, 30f,30f, p)
        canvas.drawRoundRect(r, 30f,30f, borderPaint)
        // inner grid for 3D feel
        for (i in 1..4) {
            val y = r.top + r.height()*i/5f
            canvas.drawLine(r.left+12, y, r.right-12, y, meshPaint)
        }
        for (i in 1..3) {
            val x = r.left + r.width()*i/4f
            canvas.drawLine(x, r.top+12, x, r.bottom-12, meshPaint)
        }
        textPaint.textSize = 26f
        canvas.drawText("Окно предпросмотра", r.centerX(), r.centerY()-8f, textPaint)
        smallPaint.textSize = 20f; smallPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("живая 3D-перспектива", r.centerX(), r.centerY()+22f, smallPaint)
        canvas.restore()
    }

    private fun drawGenie(canvas: Canvas, wr: RectF, ir: RectF) {
        // Simple genie-like mesh deformation from window to icon target
        val cols = 10; val rows = 14
        val dstCx = ir.centerX(); val dstCy = ir.centerY()
        val prog = previewProgress.coerceIn(0f,1f)
        for (r in 0..rows) {
            val v = r.toFloat()/rows
            val yTop = wr.top + wr.height()*v
            val yBot = wr.top + wr.height()*((r+1f)/rows)
            val squeeze = (1f - prog) + prog * (0.15f + 0.85f * v)
            val yTop2 = lerp(yTop, lerp(dstCy - ir.height()/2, dstCy + ir.height()/2, v), prog)
            val leftTop = lerp(wr.left, dstCx - ir.width()/2 * squeeze, prog)
            val rightTop = lerp(wr.right, dstCx + ir.width()/2 * squeeze, prog)
            val leftBot = lerp(wr.left, dstCx - ir.width()/2 * squeeze, prog)
            val rightBot = lerp(wr.right, dstCx + ir.width()/2 * squeeze, prog)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((120*(1f-prog*0.3f)).toInt(), 74, 144, 226); style = Paint.Style.STROKE; strokeWidth = 1.2f }
            canvas.drawLine(leftTop, yTop2, rightTop, yTop2, p)
        }
        for (c in 0..cols) {
            val u = c.toFloat()/cols
            val path = Path()
            for (r in 0..rows) {
                val v = r.toFloat()/rows
                val squeeze = (1f - prog) + prog * (0.15f + 0.85f * v)
                val x = lerp(lerp(wr.left, wr.right, u), dstCx + (u-0.5f)*ir.width()*squeeze, prog)
                val y = lerp(wr.top + wr.height()*v, lerp(dstCy - ir.height()/2, dstCy + ir.height()/2, v), prog)
                if (r==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            canvas.drawPath(path, meshPaint)
        }
        // outline
        val outline = Path()
        for (i in 0..20) {
            val v = i/20f
            val squeeze = (1f - prog) + prog * (0.15f + 0.85f * v)
            val x = lerp(wr.left, dstCx - ir.width()/2*squeeze, prog)
            val y = lerp(wr.top + wr.height()*v, lerp(dstCy-ir.height()/2, dstCy+ir.height()/2, v), prog)
            if (i==0) outline.moveTo(x,y) else outline.lineTo(x,y)
        }
        for (i in 20 downTo 0) {
            val v = i/20f
            val squeeze = (1f - prog) + prog * (0.15f + 0.85f * v)
            val x = lerp(wr.right, dstCx + ir.width()/2*squeeze, prog)
            val y = lerp(wr.top + wr.height()*v, lerp(dstCy-ir.height()/2, dstCy+ir.height()/2, v), prog)
            outline.lineTo(x,y)
        }
        outline.close()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70,74,144,226) }
        canvas.drawPath(outline, fill)
        canvas.drawPath(outline, borderPaint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b-a)*t

    private fun drawIcon(canvas: Canvas, r: RectF, tilt: FloatArray) {
        canvas.save()
        val cx = r.centerX(); val cy = r.centerY()
        val cam = Camera(); cam.save(); cam.rotateX(tilt[0]); cam.rotateY(tilt[1]); cam.rotateZ(tilt[2]); cam.translate(0f,0f, -24f)
        val m = Matrix(); cam.getMatrix(m); cam.restore()
        m.preTranslate(-cx, -cy); m.postTranslate(cx, cy)
        canvas.concat(m)
        val rr = RectF(r)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) }
        canvas.drawRoundRect(RectF(rr.left+4, rr.top+8, rr.right+4, rr.bottom+8), 22f, 22f, shadow)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(rr.left, rr.top, rr.right, rr.bottom, 0xFF7C4DFF.toInt(), 0xFF448AFF.toInt(), Shader.TileMode.CLAMP) }
        canvas.drawRoundRect(rr, 22f, 22f, base)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x99FFFFFF.toInt() }
        canvas.drawRoundRect(rr, 22f, 22f, edge)
        if (testIcon != null) {
            val src = Rect(0,0,testIcon!!.width, testIcon!!.height)
            val dst = RectF(r.left+8,r.top+8,r.right-8,r.bottom-8)
            canvas.drawBitmap(testIcon!!, src, dst, null)
        } else {
            textPaint.textSize = 32f
            canvas.drawText("App", r.centerX(), r.centerY()+10f, textPaint)
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
                dragTarget = hypot(targetPoint.x*width - event.x, targetPoint.y*height - event.y) < 40f
                if (dragTarget) return true
                dragCorner = pts.indexOfFirst { hypot(it.x - event.x, it.y - event.y) < 44f }
                if (dragCorner < 0 && mode != "corners") {
                    val nx = (event.x/width.coerceAtLeast(1)).coerceIn(0f,1f)
                    val ny = (event.y/height.coerceAtLeast(1)).coerceIn(0f,1f)
                    if (points.size < 8) points.add(PPoint(nx,ny,1f))
                    onChanged?.invoke(); invalidate(); return true
                }
                return dragCorner >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (dragTarget) {
                    targetPoint.x = (event.x/width.coerceAtLeast(1)).coerceIn(0f,1f)
                    targetPoint.y = (event.y/height.coerceAtLeast(1)).coerceIn(0f,1f)
                    invalidate(); return true
                }
                if (dragCorner >= 0) {
                    val dx = (event.x - (if (dragCorner==0||dragCorner==3) r.left else r.right)) / r.width().coerceAtLeast(1f)
                    val dy = (event.y - (if (dragCorner<2) r.top else r.bottom)) / r.height().coerceAtLeast(1f)
                    corners[dragCorner].x = dx.coerceIn(-0.8f,0.8f)
                    corners[dragCorner].y = dy.coerceIn(-0.8f,0.8f)
                    onChanged?.invoke(); invalidate(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragCorner = -1; dragTarget = false; parent?.requestDisallowInterceptTouchEvent(false) }
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
