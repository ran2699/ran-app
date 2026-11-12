package com.ran.eldervideo

import android.animation.ValueAnimator
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
 * 仿机械键盘键帽（v6）。
 * 结构自下而上：接地投影 -> 深色凹槽底座 -> 下宽上窄的柱体侧壁 -> 带渐变高光的键面。
 * 侧壁厚度固定为控件高度的 30%，所以无论按钮多大，看上去都是一块从洞里凸出来的塑料键帽。
 * 按下时键面下沉接近整个厚度，松手弹回。
 */
class MechanicalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var faceColor: Int = Color.parseColor("#1B5FD9")
    var faceColorDark: Int = Color.parseColor("#0B2E63")
    var labelText: String = ""
    var labelSizeSp: Float = 34f
    var circular: Boolean = false
    var depthDp: Float = 16f

    private val d = resources.displayMetrics.density
    private var pressed = false
    private var sink = 0f
    private var anim: ValueAnimator? = null
    private var action: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun setOnPressListener(a: () -> Unit) {
        action = a
    }

    fun setLabel(t: String) {
        labelText = t
        invalidate()
    }

    private fun shade(c: Int, f: Float): Int = Color.argb(
        Color.alpha(c),
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    private fun animateTo(target: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(sink, target).apply {
            duration = if (target > sink) 70L else 130L
            addUpdateListener {
                sink = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(widthMeasureSpec) else (150 * d).toInt()
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else (120 * d).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                animateTo(1f)
            }
            MotionEvent.ACTION_UP -> {
                val was = pressed
                pressed = false
                animateTo(0f)
                if (was) action?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                animateTo(0f)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (circular) drawCircleKey(canvas, w, h) else drawRectKey(canvas, w, h)
    }

    private fun drawRectKey(canvas: Canvas, w: Float, h: Float) {
        val pad = 5f * d
        val baseBottom = h - pad
        // 侧壁厚度：控件的 30%，但不小于请求值
        val depth = maxOf(h * 0.30f, minOf(depthDp * d, h * 0.42f))
        val faceTop = pad + 2f * d
        val faceBottom = baseBottom - depth
        if (faceBottom - faceTop < 24f) return
        val r = minOf(26f * d, (faceBottom - faceTop) * 0.24f)
        val taper = depth * 0.42f
        val sinkPx = depth * 0.88f * sink

        paint.shader = null

        // ① 接地投影
        paint.color = Color.argb(165, 0, 0, 0)
        rect.set(pad - 4f * d, baseBottom - depth * 0.55f, w - pad + 4f * d, baseBottom + 7f * d)
        canvas.drawRoundRect(rect, r * 1.4f, r * 1.4f, paint)

        // ② 底座凹槽
        paint.color = Color.parseColor("#05070D")
        rect.set(pad - 2f * d, faceTop - 4f * d, w - pad + 2f * d, baseBottom + 2f * d)
        canvas.drawRoundRect(rect, r + 5f * d, r + 5f * d, paint)

        // ③ 侧壁：自下而上逐层收窄，底部最宽最暗
        val steps = 26
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            val yBottom = baseBottom - depth * t + sinkPx * 0.15f
            val yTop = yBottom - depth / steps - 1.5f * d
            val inset = taper * t
            paint.color = shade(faceColor, 0.28f + 0.42f * t)
            rect.set(pad + inset, yTop, w - pad - inset, yBottom)
            canvas.drawRoundRect(rect, maxOf(r * 0.8f, 7f * d), maxOf(r * 0.8f, 7f * d), paint)
        }

        // ④ 键面
        val fy = faceTop + sinkPx
        val fb = faceBottom + sinkPx
        rect.set(pad + taper, fy, w - pad - taper, fb)
        paint.shader = LinearGradient(
            0f, fy, 0f, fb,
            intArrayOf(shade(faceColor, 1.58f), faceColor, shade(faceColor, 0.58f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null

        // ⑤ 顶面高光带
        paint.color = Color.argb(140, 255, 255, 255)
        rect.set(pad + taper + 9f * d, fy + 7f * d, w - pad - taper - 9f * d, fy + 16f * d)
        canvas.drawRoundRect(rect, 7f * d, 7f * d, paint)

        // ⑥ 键面白边（塑料件的高光边，最能让老人看出"这是按钮"）
        strokePaint.strokeWidth = 3.2f * d
        strokePaint.color = Color.argb(215, 255, 255, 255)
        rect.set(pad + taper + 1.8f * d, fy + 1.8f * d, w - pad - taper - 1.8f * d, fb - 1.8f * d)
        canvas.drawRoundRect(rect, r, r, strokePaint)

        // ⑦ 文字
        if (labelText.isNotEmpty()) {
            textPaint.textSize = labelSizeSp * d
            textPaint.setShadowLayer(4f * d, 0f, 3f * d, Color.argb(215, 0, 0, 0))
            canvas.drawText(labelText, w / 2f, (fy + fb) / 2f + textPaint.textSize * 0.36f, textPaint)
        }
    }

    private fun drawCircleKey(canvas: Canvas, w: Float, h: Float) {
        val pad = 5f * d
        val depth = maxOf(h * 0.24f, minOf(depthDp * d, h * 0.34f))
        val rMax = minOf(w, h) / 2f - pad - depth * 0.60f
        if (rMax <= 12f) return
        val cx = w / 2f
        val cyFaceBase = h / 2f - depth * 0.45f
        val cyBottom = cyFaceBase + depth
        val sinkPx = depth * 0.86f * sink

        paint.shader = null

        // ① 接地投影
        paint.color = Color.argb(175, 0, 0, 0)
        canvas.drawCircle(cx, cyBottom + 3f * d, rMax + 3f * d, paint)

        // ② 底座
        paint.color = Color.parseColor("#05070D")
        canvas.drawCircle(cx, cyBottom, rMax + 5f * d, paint)

        // ③ 侧壁
        val steps = 26
        for (i in 0 until steps) {
            val t = i / (steps - 1f)
            paint.color = shade(faceColor, 0.26f + 0.44f * t)
            canvas.drawCircle(cx, cyBottom - depth * t, rMax + 5f * d - depth * 0.12f * t, paint)
        }

        // ④ 键面
        val cyFace = cyFaceBase + sinkPx
        paint.shader = LinearGradient(
            cx, cyFace - rMax, cx, cyFace + rMax,
            intArrayOf(shade(faceColor, 1.55f), faceColor, shade(faceColor, 0.55f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace, rMax, paint)
        paint.shader = null

        // ⑤ 中央白色圆环
        strokePaint.strokeWidth = rMax * 0.26f
        strokePaint.color = Color.WHITE
        canvas.drawCircle(cx, cyFace, rMax * 0.50f, strokePaint)

        // ⑥ 外沿白边
        strokePaint.strokeWidth = 3.2f * d
        strokePaint.color = Color.argb(215, 255, 255, 255)
        canvas.drawCircle(cx, cyFace, rMax - 1.8f * d, strokePaint)

        // ⑦ 文字（圆键一般不写字）
        if (labelText.isNotEmpty()) {
            textPaint.textSize = labelSizeSp * d
            textPaint.setShadowLayer(4f * d, 0f, 3f * d, Color.argb(215, 0, 0, 0))
            canvas.drawText(labelText, cx, cyFace + textPaint.textSize * 0.36f, textPaint)
        }
    }
}
