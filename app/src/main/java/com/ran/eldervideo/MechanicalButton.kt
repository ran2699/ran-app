package com.ran.eldervideo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 仿机械键盘键帽（v4）。
 * 侧壁用十几层梯形堆出来：底部最暗、往上渐亮、顶面比底部窄一圈，
 * 所以看上去是一个有厚度、被压下去的墩子，而不是一张贴纸。
 * 按下时整个键帽下沉，底座上沿露出缝隙。
 */
class MechanicalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var faceColor: Int = Color.parseColor("#1B5FD9")
    var faceColorDark: Int = Color.parseColor("#0B2E63")
    var labelText: String = ""
    var labelSizeSp: Float = 32f
    var circular: Boolean = false
    var depthDp: Float = 16f

    private val d = resources.displayMetrics.density
    private var pressed = false
    private var action: (() -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(5f * d, 0f, 3f * d, Color.argb(200, 0, 0, 0))
    }
    private val rect = RectF()

    fun setOnPressListener(a: () -> Unit) {
        action = a
    }

    private fun shade(c: Int, f: Float): Int = Color.argb(
        Color.alpha(c),
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    private fun pressShift(depth: Float): Float = if (pressed) depth * 0.62f else 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 6f * d
        // 键帽厚度：设定值放大近两倍，但最多占高度的 26%
        val depth = minOf(depthDp * d * 1.9f, h * 0.26f)
        val o = pressShift(depth)
        if (circular) drawCircleKey(canvas, w, h, pad, depth, o)
        else drawRectKey(canvas, w, h, pad, depth, o)
    }

    private fun drawRectKey(canvas: Canvas, w: Float, h: Float, pad: Float, depth: Float, o: Float) {
        val r = 20f * d
        val l = pad
        val rr = w - pad
        val top = pad
        val faceBottom = h - pad - depth
        if (faceBottom - top < 20f) return

        // ① 落地阴影：比键体更大更靠下的一圈黑，alpha 从中心往外淡
        shadowPaint.shader = LinearGradient(
            0f, top, 0f, faceBottom + depth + 10f * d,
            Color.argb(200, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP
        )
        rect.set(l - 3f * d, top + 6f * d, rr + 3f * d, faceBottom + depth + 10f * d)
        canvas.drawRoundRect(rect, r + 4f * d, r + 4f * d, shadowPaint)
        shadowPaint.shader = null

        // ② 底座：最暗的一层，标出键"陷进去"的洞
        basePaint.shader = null
        basePaint.color = Color.parseColor("#03040A")
        rect.set(l, top + 2f * d, rr, faceBottom + depth)
        canvas.drawRoundRect(rect, r, r, basePaint)

        // ③ 侧壁：十六层梯形，底部宽、顶上窄，颜色由暗到亮
        val steps = 16
        val taper = 7f * d
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val yBottom = faceBottom + depth * (1f - t) + o
            val yTop = yBottom - (depth / steps) - 1.5f * d
            val inset = taper * (1f - t)
            basePaint.color = shade(faceColor, 0.26f + 0.30f * t)
            rect.set(l + inset, yTop, rr - inset, yBottom)
            canvas.drawRoundRect(rect, r * 0.9f, r * 0.9f, basePaint)
        }

        // ④ 键面：亮 -> 本色 -> 稍暗
        rect.set(l + taper, top + o, rr - taper, faceBottom + o)
        facePaint.shader = LinearGradient(
            0f, top + o, 0f, faceBottom + o,
            intArrayOf(shade(faceColor, 1.60f), faceColor, shade(faceColor, 0.62f)),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, facePaint)
        facePaint.shader = null

        // ⑤ 顶部亮线
        glossPaint.shader = null
        glossPaint.color = Color.argb(165, 255, 255, 255)
        rect.set(l + taper + 7f * d, top + o + 5f * d, rr - taper - 7f * d, top + o + 9f * d)
        canvas.drawRoundRect(rect, 5f * d, 5f * d, glossPaint)

        // ⑥ 上半部柔光
        glossPaint.shader = LinearGradient(
            0f, top + o, 0f, top + o + (faceBottom - top) * 0.45f,
            Color.argb(74, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        rect.set(l + taper + 2f * d, top + o + 2f * d, rr - taper - 2f * d, top + o + (faceBottom - top) * 0.45f)
        canvas.drawRoundRect(rect, r, r, glossPaint)
        glossPaint.shader = null

        // ⑦ 键面描边
        rimPaint.strokeWidth = 3.5f * d
        rimPaint.color = Color.argb(175, 235, 242, 252)
        rect.set(l + taper + 2f * d, top + o + 2f * d, rr - taper - 2f * d, faceBottom + o - 2f * d)
        canvas.drawRoundRect(rect, r, r, rimPaint)

        // ⑧ 文字
        if (labelText.isNotEmpty()) {
            textPaint.textSize = labelSizeSp * d
            canvas.drawText(
                labelText, w / 2f,
                (top + o + faceBottom + o) / 2f + textPaint.textSize * 0.35f,
                textPaint
            )
        }
    }

    private fun drawCircleKey(canvas: Canvas, w: Float, h: Float, pad: Float, depth: Float, o: Float) {
        val cx = w / 2f
        val rMax = minOf(w, h) / 2f - pad - depth * 0.5f
        if (rMax <= 12f) return
        val cy = h / 2f - depth * 0.25f
        val cyFace = cy + o

        // ① 落地阴影
        shadowPaint.shader = LinearGradient(
            cx, cy - rMax, cx, cy + rMax + depth + 8f * d,
            Color.argb(190, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + depth * 0.5f, rMax + 4f * d, shadowPaint)
        shadowPaint.shader = null

        // ② 底座
        basePaint.shader = null
        basePaint.color = Color.parseColor("#03040A")
        canvas.drawCircle(cx, cy + depth * 0.55f, rMax + 2f * d, basePaint)

        // ③ 侧壁：多层圆，半径从大到小
        val steps = 16
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val yc = cy + depth * 0.55f * (1f - t) + o
            basePaint.color = shade(faceColor, 0.26f + 0.30f * t)
            canvas.drawCircle(cx, yc, rMax - depth * 0.10f * (1f - t), basePaint)
        }

        // ④ 键面
        facePaint.shader = LinearGradient(
            cx, cyFace - rMax, cx, cyFace + rMax,
            intArrayOf(shade(faceColor, 1.62f), faceColor, shade(faceColor, 0.58f)),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace, rMax - depth * 0.06f, facePaint)
        facePaint.shader = null

        // ⑤ 柔光
        glossPaint.shader = LinearGradient(
            cx, cyFace - rMax, cx, cyFace,
            Color.argb(135, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace - rMax * 0.18f, rMax * 0.84f, glossPaint)
        glossPaint.shader = null

        // ⑥ 描边
        rimPaint.strokeWidth = 4f * d
        rimPaint.color = Color.argb(175, 238, 244, 252)
        canvas.drawCircle(cx, cyFace, rMax - depth * 0.06f - 2f * d, rimPaint)

        // ⑦ 中间白环
        ringPaint.strokeWidth = 14f * d
        ringPaint.color = Color.WHITE
        canvas.drawCircle(cx, cyFace, rMax * 0.40f, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val was = pressed
                pressed = false
                invalidate()
                if (was && event.x >= 0f && event.x <= width && event.y >= 0f && event.y <= height) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        action?.invoke()
        return true
    }
}
