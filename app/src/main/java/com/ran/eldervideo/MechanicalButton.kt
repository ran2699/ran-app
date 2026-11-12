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
 * 仿古机械按键。
 * 从下往上的层次：底座(近黑) -> 侧壁(深色，露出的厚度) -> 键面(竖向渐变) -> 顶部高光 -> 内描边 -> 文字。
 * 按下时键面下沉，底座上沿露出缝隙，底部厚度变薄；抬手弹回。
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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(4f * d, 0f, 3f * d, Color.argb(170, 0, 0, 0))
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

    /** 按下时的下沉量 */
    private fun pressShift(): Float = if (pressed) depthDp * d * 0.55f else 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 4f * d
        val depth = depthDp * d
        val o = pressShift()
        if (circular) drawCircleKey(canvas, w, h, pad, depth, o)
        else drawRectKey(canvas, w, h, pad, depth, o)
    }

    private fun drawRectKey(canvas: Canvas, w: Float, h: Float, pad: Float, depth: Float, o: Float) {
        val r = 20f * d
        val l = pad
        val rr = w - pad
        val top = pad
        val faceBottom = h - pad - depth
        if (faceBottom - top < 24f) return

        // ① 底座：整块近黑，形成按键的"洞"
        basePaint.style = Paint.Style.FILL
        basePaint.color = Color.parseColor("#05070A")
        rect.set(l, top, rr, faceBottom + depth)
        canvas.drawRoundRect(rect, r, r, basePaint)

        // ② 侧壁：键面之下露出的厚度，深色渐变
        basePaint.shader = LinearGradient(
            0f, faceBottom - 2f * d, 0f, faceBottom + depth,
            shade(faceColor, 0.50f), Color.parseColor("#080A0E"), Shader.TileMode.CLAMP
        )
        rect.set(l, faceBottom - 2f * d, rr, faceBottom + depth)
        canvas.drawRoundRect(rect, r, r, basePaint)
        basePaint.shader = null

        // ③ 键面：亮在上、暗在下，像整块塑料被光打亮
        rect.set(l, top + o, rr, faceBottom + o)
        facePaint.shader = LinearGradient(
            0f, top + o, 0f, faceBottom + o,
            shade(faceColor, 1.35f), faceColorDark, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, facePaint)
        facePaint.shader = null

        // ④ 顶部高光
        val glossBottom = top + o + (faceBottom - top) * 0.50f
        glossPaint.shader = LinearGradient(
            0f, top + o, 0f, glossBottom,
            Color.argb(105, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        rect.set(l + 3f * d, top + o + 3f * d, rr - 3f * d, glossBottom)
        canvas.drawRoundRect(rect, r, r, glossPaint)
        glossPaint.shader = null

        // ⑤ 内描边
        rimPaint.strokeWidth = 3f * d
        rimPaint.color = Color.argb(130, 225, 235, 250)
        rect.set(l + 2f * d, top + o + 2f * d, rr - 2f * d, faceBottom + o - 2f * d)
        canvas.drawRoundRect(rect, r, r, rimPaint)

        // ⑥ 文字
        if (labelText.isNotEmpty()) {
            textPaint.textSize = labelSizeSp * d
            canvas.drawText(labelText, w / 2f, (top + o + faceBottom + o) / 2f + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawCircleKey(canvas: Canvas, w: Float, h: Float, pad: Float, depth: Float, o: Float) {
        val cx = w / 2f
        val r = minOf(w, h) / 2f - pad - depth * 0.5f
        if (r <= 12f) return
        val cy = h / 2f - depth * 0.25f
        val cyFace = cy + o

        // ① 底座
        basePaint.style = Paint.Style.FILL
        basePaint.color = Color.parseColor("#05070A")
        canvas.drawCircle(cx, cy + depth * 0.45f, r + 2f * d, basePaint)

        // ② 侧壁
        basePaint.shader = LinearGradient(
            cx, cy + r * 0.2f, cx, cy + r + depth * 0.5f,
            shade(faceColor, 0.45f), Color.parseColor("#080A0E"), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + depth * 0.40f, r, basePaint)
        basePaint.shader = null

        // ③ 键面
        facePaint.shader = LinearGradient(
            cx, cyFace - r, cx, cyFace + r,
            shade(faceColor, 1.40f), faceColorDark, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace, r, facePaint)
        facePaint.shader = null

        // ④ 高光
        glossPaint.shader = LinearGradient(
            cx, cyFace - r, cx, cyFace - r * 0.05f,
            Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace - r * 0.10f, r * 0.88f, glossPaint)
        glossPaint.shader = null

        // ⑤ 描边
        rimPaint.strokeWidth = 3.5f * d
        rimPaint.color = Color.argb(145, 235, 240, 250)
        canvas.drawCircle(cx, cyFace, r - 2f * d, rimPaint)

        // ⑥ 白色圆环
        ringPaint.strokeWidth = 13f * d
        ringPaint.color = Color.WHITE
        canvas.drawCircle(cx, cyFace, r * 0.44f, ringPaint)
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
