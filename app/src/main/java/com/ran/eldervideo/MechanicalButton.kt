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
 * 仿古机械按键（v3，加强厚度）。
 * 层次：外投影 -> 底座 -> 侧壁(露出的厚度) -> 键面(亮-本色-暗三段渐变) -> 顶部亮线 -> 柔光 -> 内描边 -> 文字。
 * 按下时键面下沉，底座上沿露出缝隙，底部厚度变薄，抬手弹回。
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
        setShadowLayer(5f * d, 0f, 3f * d, Color.argb(190, 0, 0, 0))
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

    private fun pressShift(): Float = if (pressed) depthDp * d * 0.55f else 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = 5f * d
        // 厚度比设定值再放三成，手机上看才明显
        val depth = depthDp * d * 1.3f
        val o = pressShift()
        if (circular) drawCircleKey(canvas, w, h, pad, depth, o)
        else drawRectKey(canvas, w, h, pad, depth, o)
    }

    private fun drawRectKey(canvas: Canvas, w: Float, h: Float, pad: Float, depth: Float, o: Float) {
        val r = 22f * d
        val l = pad
        val rr = w - pad
        val top = pad
        val faceBottom = h - pad - depth
        if (faceBottom - top < 24f) return

        // ① 底座（比键面更靠下，形成"洞"和落地阴影）
        basePaint.style = Paint.Style.FILL
        basePaint.color = Color.parseColor("#04050A")
        rect.set(l, top + 3f * d, rr, faceBottom + depth)
        canvas.drawRoundRect(rect, r, r, basePaint)

        // ② 侧壁：露出的厚度
        basePaint.shader = LinearGradient(
            0f, faceBottom - 3f * d, 0f, faceBottom + depth,
            shade(faceColor, 0.72f), Color.parseColor("#06080C"), Shader.TileMode.CLAMP
        )
        rect.set(l, faceBottom - 3f * d, rr, faceBottom + depth)
        canvas.drawRoundRect(rect, r, r, basePaint)
        basePaint.shader = null

        // ③ 键面：亮 -> 本色 -> 暗
        rect.set(l, top + o, rr, faceBottom + o)
        facePaint.shader = LinearGradient(
            0f, top + o, 0f, faceBottom + o,
            intArrayOf(shade(faceColor, 1.55f), faceColor, shade(faceColor, 0.52f)),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, facePaint)
        facePaint.shader = null

        // ④ 顶部亮线（塑料边缘反光）
        glossPaint.shader = null
        glossPaint.color = Color.argb(155, 255, 255, 255)
        rect.set(l + 8f * d, top + o + 5f * d, rr - 8f * d, top + o + 9.5f * d)
        canvas.drawRoundRect(rect, 5f * d, 5f * d, glossPaint)

        // ⑤ 上半部柔光
        glossPaint.shader = LinearGradient(
            0f, top + o, 0f, top + o + (faceBottom - top) * 0.45f,
            Color.argb(72, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        rect.set(l + 3f * d, top + o + 3f * d, rr - 3f * d, top + o + (faceBottom - top) * 0.45f)
        canvas.drawRoundRect(rect, r, r, glossPaint)
        glossPaint.shader = null

        // ⑥ 内描边
        rimPaint.strokeWidth = 3.5f * d
        rimPaint.color = Color.argb(170, 232, 240, 252)
        rect.set(l + 2f * d, top + o + 2f * d, rr - 2f * d, faceBottom + o - 2f * d)
        canvas.drawRoundRect(rect, r, r, rimPaint)

        // ⑦ 文字
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
        val r = minOf(w, h) / 2f - pad - depth * 0.6f
        if (r <= 12f) return
        val cy = h / 2f - depth * 0.30f
        val cyFace = cy + o

        // ① 底座
        basePaint.style = Paint.Style.FILL
        basePaint.color = Color.parseColor("#04050A")
        canvas.drawCircle(cx, cy + depth * 0.55f, r + 3f * d, basePaint)

        // ② 侧壁
        basePaint.shader = LinearGradient(
            cx, cy + r * 0.15f, cx, cy + r + depth * 0.6f,
            shade(faceColor, 0.66f), Color.parseColor("#06080C"), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + depth * 0.45f, r, basePaint)
        basePaint.shader = null

        // ③ 键面
        facePaint.shader = LinearGradient(
            cx, cyFace - r, cx, cyFace + r,
            intArrayOf(shade(faceColor, 1.60f), faceColor, shade(faceColor, 0.50f)),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace, r, facePaint)
        facePaint.shader = null

        // ④ 上方柔光
        glossPaint.shader = LinearGradient(
            cx, cyFace - r, cx, cyFace - r * 0.05f,
            Color.argb(130, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace - r * 0.12f, r * 0.86f, glossPaint)
        glossPaint.shader = null

        // ⑤ 描边
        rimPaint.strokeWidth = 4f * d
        rimPaint.color = Color.argb(170, 235, 242, 252)
        canvas.drawCircle(cx, cyFace, r - 2f * d, rimPaint)

        // ⑥ 白色圆环
        ringPaint.strokeWidth = 14f * d
        ringPaint.color = Color.WHITE
        canvas.drawCircle(cx, cyFace, r * 0.42f, ringPaint)
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
