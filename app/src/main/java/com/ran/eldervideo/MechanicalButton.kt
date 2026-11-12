package com.ran.eldervideo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 仿机械键帽：有真实厚度（侧壁）、按下沉到底、松手弹回。
 * 侧壁用横向渐变模拟圆柱受光（左亮右暗），键面用纵向渐变 + 白色描边，
 * 底部带接地投影，看起来是"凸出来的一块"。
 * 文字字号强制自适应键面，绝不溢出、绝不被压成线。
 */
class MechanicalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var faceColor: Int = Color.parseColor("#1B5FD9")
    var faceColorDark: Int = Color.parseColor("#0A2E63")
    var labelText: String = ""
    var labelSizeSp: Float = 34f
    var circular: Boolean = false
    var depthDp: Float = 26f

    private val d = resources.displayMetrics.density
    private var pressed = false
    private var sink = 0f
    private var anim: ValueAnimator? = null
    private var action: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val rect2 = RectF()
    private val path = Path()

    fun setOnPressListener(a: () -> Unit) { action = a }

    fun setLabel(t: String) {
        labelText = t
        invalidate()
    }

    fun refresh() { invalidate() }

    private fun shade(c: Int, f: Float): Int = Color.argb(
        255,
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    private fun animateTo(target: Float) {
        anim?.cancel()
        val from = sink
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = if (target > from) 55L else 110L
            addUpdateListener {
                sink = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                animateTo(1f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressed) {
                    pressed = false
                    animateTo(0f)
                    performClick()
                    action?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                animateTo(0f)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(c: Canvas) {
        val m = 3f * d
        val l = m
        val t = m
        val r = width - m
        val b = height - m
        if (r - l < 20f || b - t < 20f) return
        if (circular) drawRoundKey(c, l, t, r, b) else drawSquareKey(c, l, t, r, b)
    }

    private fun drawSquareKey(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val h = b - t
        // 侧壁厚度：占高度的 24%~42%，且不小于 depthDp
        val sideH = (depthDp * d).coerceAtLeast(h * 0.24f).coerceAtMost(h * 0.42f)
        val faceH = h - sideH
        val sinkPx = sink * sideH * 0.92f
        val faceTop = t + sinkPx
        val faceBottom = faceTop + faceH
        val inset = w * 0.065f
        val radius = min(w, faceH) * 0.19f

        paint.style = Paint.Style.FILL
        paint.shader = null

        // ① 接地投影（两层，越贴地越黑）
        paint.color = Color.argb(95, 0, 0, 0)
        rect.set(l + inset * 0.2f, b - sideH * 0.55f, r - inset * 0.2f, b + sideH * 0.45f)
        c.drawOval(rect, paint)
        paint.color = Color.argb(150, 0, 0, 0)
        rect.set(l + inset * 0.9f, b - sideH * 0.25f, r - inset * 0.9f, b + sideH * 0.10f)
        c.drawOval(rect, paint)

        // ② 侧壁：上宽下窄的柱体，横向渐变 ⇒ 左亮右暗的圆柱受光
        path.reset()
        path.moveTo(l, faceBottom)
        path.lineTo(r, faceBottom)
        path.lineTo(r - inset, b)
        path.lineTo(l + inset, b)
        path.close()
        paint.shader = LinearGradient(
            l, 0f, r, 0f,
            intArrayOf(
                shade(faceColorDark, 1.70f),
                shade(faceColorDark, 1.05f),
                shade(faceColorDark, 0.42f)
            ),
            floatArrayOf(0f, 0.32f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, paint)
        paint.shader = null

        // ③ 键面
        rect.set(l, faceTop, r, faceBottom)
        paint.shader = LinearGradient(
            0f, faceTop, 0f, faceBottom,
            intArrayOf(
                shade(faceColor, 1.34f),
                shade(faceColor, 1.00f),
                shade(faceColor, 0.70f)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        // ④ 白色描边（键面轮廓，让老人一眼看出这是按钮）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.4f * d
        paint.color = Color.argb(240, 255, 255, 255)
        rect2.set(l, faceTop, r, faceBottom)
        rect2.inset(1.7f * d, 1.7f * d)
        c.drawRoundRect(rect2, radius, radius, paint)
        paint.style = Paint.Style.FILL

        // ⑤ 顶部高光带（倒角反光）
        paint.color = Color.argb(105, 255, 255, 255)
        rect2.set(
            l + radius * 0.85f,
            faceTop + 5f * d,
            r - radius * 0.85f,
            faceTop + 5f * d + (faceH * 0.11f).coerceAtLeast(2f * d)
        )
        c.drawRoundRect(rect2, faceH * 0.055f, faceH * 0.055f, paint)

        drawLabel(c, l, faceTop, r, faceBottom, faceH)
    }

    private fun drawRoundKey(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val w = r - l
        val h = b - t
        val cx = (l + r) / 2f
        // 球必须完全落在自己的格子里：半径 = (可用高 - 侧壁 - 余量) / 2，否则底弧被裁、看着像溢出
        val sideH = (depthDp * d).coerceAtLeast(h * 0.22f).coerceAtMost(h * 0.34f)
        val sinkPx = sink * sideH * 0.90f
        val availH = h - sideH - 6f * d
        val R = min(availH / 2f, w / 2f - 3f * d).coerceAtLeast(10f)
        val topCy = t + 3f * d + sinkPx + R
        val botCy = b - sideH - R

        paint.style = Paint.Style.FILL
        paint.shader = null

        // 投影
        paint.color = Color.argb(110, 0, 0, 0)
        rect.set(cx - R * 1.02f, b - sideH * 0.60f, cx + R * 1.02f, b - 1f * d)
        c.drawOval(rect, paint)
        paint.color = Color.argb(170, 0, 0, 0)
        rect.set(cx - R * 0.84f, b - sideH * 0.42f, cx + R * 0.84f, b - 3f * d)
        c.drawOval(rect, paint)

        // 圆柱侧壁
        path.reset()
        path.moveTo(cx - R, topCy)
        path.lineTo(cx - R, botCy)
        path.arcTo(RectF(cx - R, botCy - R, cx + R, botCy + R), 180f, 180f, false)
        path.lineTo(cx + R, topCy)
        path.close()
        paint.shader = LinearGradient(
            cx - R, 0f, cx + R, 0f,
            intArrayOf(
                shade(faceColorDark, 1.75f),
                shade(faceColorDark, 1.00f),
                shade(faceColorDark, 0.40f)
            ),
            floatArrayOf(0f, 0.30f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawPath(path, paint)
        paint.shader = null

        // 顶面球冠
        paint.shader = RadialGradient(
            cx - R * 0.34f, topCy - R * 0.44f, R * 1.55f,
            intArrayOf(
                shade(faceColor, 1.55f),
                faceColor,
                shade(faceColorDark, 0.95f)
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, topCy, R, paint)
        paint.shader = null

        // 白色粗圆环（老人要的那圈白）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = R * 0.30f
        paint.color = Color.WHITE
        c.drawCircle(cx, topCy, R * 0.50f, paint)
        paint.style = Paint.Style.FILL

        drawLabel(c, l, topCy - R, r, topCy + R, R * 1.6f)
    }

    private fun drawLabel(c: Canvas, l: Float, t: Float, r: Float, b: Float, faceH: Float) {
        if (labelText.isEmpty()) return
        var size = labelSizeSp * d
        textPaint.textSize = size
        val maxW = (r - l) * 0.84f
        val tw = textPaint.measureText(labelText)
        if (tw > maxW && tw > 0f) size *= maxW / tw
        val maxH = faceH * 0.56f
        if (size > maxH) size = maxH
        if (size < 8f * d) size = 8f * d
        textPaint.textSize = size
        val fm = textPaint.fontMetrics
        val cy = (t + b) / 2f - (fm.ascent + fm.descent) / 2f
        textPaint.color = Color.WHITE
        textPaint.setShadowLayer(4f * d, 0f, 2f * d, Color.argb(160, 0, 0, 0))
        c.drawText(labelText, (l + r) / 2f, cy, textPaint)
        textPaint.clearShadowLayer()
    }
}
