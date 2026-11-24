package com.vibe.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.random.Random

class TouchFxView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    data class Fx(val x: Float, val y: Float, var t: Float = 0f, val style: String, val end: String, val size: Float, val speed: Float, val seed: Int = Random.nextInt())
    private val fxs = mutableListOf<Fx>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val anim = ValueAnimator.ofFloat(0f,1f).apply {
        duration = 16; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { val dt = 0.016f; val it = fxs.iterator(); while (it.hasNext()) { val f = it.next(); f.t += dt * f.speed; if (f.t > 1.2f) it.remove() }; if (fxs.isEmpty()) { if (isRunning) cancel() } else invalidate() }
    }

    fun addTouch(x: Float, y: Float, style: String, end: String, size: Int, speed: Int) {
        fxs.add(Fx(x,y,0f,style,end, (20f + size * 0.8f) * resources.displayMetrics.density, 0.5f + speed/100f))
        if (!anim.isRunning) anim.start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (f in fxs) drawFx(canvas, f)
    }

    private fun drawFx(c: Canvas, f: Fx) {
        val t = f.t.coerceIn(0f,1.2f)
        val endT = ((t - 0.7f) / 0.5f).coerceIn(0f,1f)
        val baseA = ((1f - endT) * 255).toInt().coerceIn(0,255)
        val r = f.size * (0.4f + 0.8f * min(t,1f))
        when (f.style) {
            "fireworks" -> {
                paint.style = Paint.Style.FILL
                for (i in 0 until 12) {
                    val ang = i * 30f * Math.PI.toFloat()/180f
                    val d = r * (0.3f + t)
                    val px = f.x + cos(ang)*d; val py = f.y + sin(ang)*d
                    paint.color = Color.HSVToColor(baseA, floatArrayOf((i*30f)%360f,0.8f,1f))
                    c.drawCircle(px,py,6f*(1f-endT)+2f,paint)
                }
            }
            "miui" -> {
                paint.style = Paint.Style.FILL
                paint.shader = RadialGradient(f.x,f.y,r, intArrayOf(Color.argb(baseA,80,160,255), Color.argb(baseA/3,80,160,255), Color.TRANSPARENT), floatArrayOf(0f,0.6f,1f), Shader.TileMode.CLAMP)
                c.drawCircle(f.x,f.y,r,paint); paint.shader = null
            }
            "ios" -> {
                val s = r*0.9f
                val rect = RectF(f.x-s/2,f.y-s/2,f.x+s/2,f.y+s/2)
                paint.style = Paint.Style.FILL; paint.color = Color.argb((baseA*0.25f).toInt(),255,255,255)
                c.drawRoundRect(rect, 18f,18f,paint)
                stroke.strokeWidth = 2f; stroke.color = Color.argb(baseA,255,255,255)
                c.drawRoundRect(rect,18f,18f,stroke)
                // glint
                stroke.color = Color.argb((baseA*0.8f).toInt(),255,255,255)
                val g = (t*4f)%1f
                c.drawLine(rect.left, rect.top + rect.height()*g, rect.right, rect.top + rect.height()*g, stroke)
            }
            else -> { // android
                paint.style = Paint.Style.FILL; paint.color = Color.argb((baseA*0.35f).toInt(),255,255,255)
                c.drawCircle(f.x,f.y,r,paint)
                stroke.style = Paint.Style.STROKE; stroke.strokeWidth = 3f; stroke.color = Color.argb(baseA,40,40,40)
                c.drawCircle(f.x,f.y,r,stroke)
            }
        }
        if (endT > 0f) {
            when (f.end) {
                "fade" -> { paint.style=Paint.Style.FILL; paint.color=Color.argb((baseA*0.2f).toInt(),255,255,255); c.drawCircle(f.x,f.y,r*(1f+endT*0.5f),paint) }
                "burst" -> { paint.style=Paint.Style.FILL; paint.color=Color.argb(baseA,255,255,255); for(i in 0 until 8){ val a=i*45f*Math.PI.toFloat()/180f; c.drawCircle(f.x+cos(a)*r*endT, f.y+sin(a)*r*endT, 4f*(1f-endT), paint)} }
                "liquid" -> { paint.style=Paint.Style.FILL; paint.color=Color.argb(baseA,180,220,255); for(i in 0 until 6){ val a=i*60f*Math.PI.toFloat()/180f; c.drawCircle(f.x+cos(a)*r*endT*0.7f, f.y+sin(a)*r*endT*0.7f, (r*0.25f)*(1f-endT), paint)} }
                else -> { /* shrink handled by radius */ }
            }
        }
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}
