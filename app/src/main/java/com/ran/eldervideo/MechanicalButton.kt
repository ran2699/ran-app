package com.ran.eldervideo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 仿机械键盘键帽。
 * 结构自下而上：接地阴影 -> 等宽直筒侧壁 -> 带渐变与白描边的键面。
 * 侧壁厚度是固定 dp（depthDp），不随控件高度放大——这样看起来才是一整块
 * 从底座里抬起来的塑料键帽，而不是一个梯形裙边。按下时键面下沉接近整层厚度。
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
    var depthDp: Float = 18f

    private val d = resources.displayMetrics.density
    private var pressed = false
    private var sink = 0f
    private var anim: ValueAnimator? = null
    private var action: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val path = Path()

    fun setOnPressListener(a: () -> Unit) {
        action = a
    }

    fun setLabel(t: String) {
        labelText = t
        invalidate()
    }

    fun refresh() {
        invalidate()
    }

    private fun shade(c: Int, f: Float): Int = Color.argb(
        255,
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
        val pad = 4f * d
        val shadowH = 6f * d
        // 侧壁厚度固定，不按比例放大
        val sideH = minOf(depthDp * d, h * 0.42f)
        val faceTop = pad
        val faceBottom = h - pad - shadowH - sideH
        val faceH = faceBottom - faceTop
        if (faceH < 18f) return
        val r = minOf(26f * d, faceH * 0.26f)
        val sinkPx = sideH * 0.85f * sink

        paint.shader = null

        // ① 接地阴影：贴着键帽底部的柔和暗影，宽度略大
        paint.color = Color.argb(150, 0, 0, 0)
        rect.set(pad - 6f * d, h - pad - shadowH, w - pad + 6f * d, h - pad + 1f * d)
        canvas.drawRoundRect(rect, shadowH * 1.6f, shadowH * 1.6f, paint)

        // ② 侧壁：和键面同宽的直筒，上亮下暗
        val sideTop = faceBottom + sinkPx
        val sideBottom = h - pad - shadowH + sinkPx
        paint.shader = LinearGradient(
            0f, sideTop, 0f, maxOf(sideBottom, sideTop + 1f),
            intArrayOf(shade(faceColor, 0.72f), shade(faceColorDark, 1.15f)),
            null, Shader.TileMode.CLAMP
        )
        rect.set(pad, sideTop, w - pad, sideBottom)
        canvas.drawRoundRect(rect, r * 0.5f, r * 0.5f, paint)
        paint.shader = null

        // ③ 键面
        val fy = faceTop + sinkPx
        val fb = faceBottom + sinkPx
        paint.shader = LinearGradient(
            0f, fy, 0f, fb,
            intArrayOf(shade(faceColor, 1.45f), faceColor, shade(faceColor, 0.70f)),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        rect.set(pad, fy, w - pad, fb)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null

        // ④ 白描边
        stroke.strokeWidth = 2.4f * d
        stroke.color = Color.argb(225, 255, 255, 255)
        rect.set(pad + 1.2f * d, fy + 1.2f * d, w - pad - 1.2f * d, fb - 1.2f * d)
        canvas.drawRoundRect(rect, r, r, stroke)

        // ⑤ 顶部高光带
        paint.color = Color.argb(115, 255, 255, 255)
        rect.set(
            pad + 12f * d, fy + 6f * d,
            w - pad - 12f * d, fy + minOf(15f * d, faceH * 0.16f)
        )
        canvas.drawRoundRect(rect, 8f * d, 8f * d, paint)

        // ⑥ 文字
        drawLabel(canvas, w / 2f, fy + faceH / 2f, w - 2 * pad - 18f * d, faceH * 0.60f)
    }

    private fun drawCircleKey(canvas: Canvas, w: Float, h: Float) {
        val sideH = minOf(depthDp * d, h * 0.30f)
        val shadowH = 5f * d
        val rMax = minOf(w / 2f, (h - sideH - shadowH - 8f * d) / 2f) - 2f * d
        if (rMax <= 10f) return
        val cx = w / 2f
        val sinkPx = sideH * 0.85f * sink
        val cyFace = 6f * d + rMax + sinkPx
        val sideTop = cyFace
        val sideBottom = cyFace + sideH

        paint.shader = null

        // ① 接地阴影
        paint.color = Color.argb(160, 0, 0, 0)
        canvas.drawOval(
            cx - rMax - 6f * d, sideBottom - rMax * 0.25f,
            cx + rMax + 6f * d, sideBottom + 10f * d,
            paint
        )

        // ② 侧壁：圆柱
        paint.shader = LinearGradient(
            0f, sideTop, 0f, maxOf(sideBottom, sideTop + 1f),
            intArrayOf(shade(faceColor, 0.70f), shade(faceColorDark, 1.20f)),
            null, Shader.TileMode.CLAMP
        )
        path.reset()
        path.addRoundRect(
            RectF(cx - rMax, sideTop, cx + rMax, sideBottom),
            rMax, rMax, Path.Direction.CW
        )
        canvas.drawPath(path, paint)
        paint.shader = null

        // ③ 球面
        paint.shader = LinearGradient(
            cx, cyFace - rMax, cx, cyFace + rMax,
            intArrayOf(shade(faceColor, 1.50f), faceColor, shade(faceColor, 0.62f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cyFace, rMax, paint)
        paint.shader = null

        // ④ 中央白色粗圆环
        stroke.strokeWidth = rMax * 0.26f
        stroke.color = Color.WHITE
        canvas.drawCircle(cx, cyFace, rMax * 0.48f, stroke)

        // ⑤ 外沿白边
        stroke.strokeWidth = 3.4f * d
        stroke.color = Color.argb(230, 255, 255, 255)
        canvas.drawCircle(cx, cyFace, rMax - 2f * d, stroke)

        drawLabel(canvas, cx, cyFace, rMax * 1.5f, rMax * 0.8f)
    }

    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, maxW: Float, maxH: Float) {
        if (labelText.isEmpty()) return
        var size = labelSizeSp * d
        textPaint.textSize = size
        while (textPaint.measureText(labelText) > maxW && size > 9f * d) {
            size -= 1f
            textPaint.textSize = size
        }
        while (size > maxH && size > 9f * d) {
            size -= 1f
            textPaint.textSize = size
        }
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        textPaint.setShadowLayer(4f * d, 0f, 3f * d, Color.argb(220, 0, 0, 0))
        canvas.drawText(labelText, cx, baseline, textPaint)
        textPaint.clearShadowLayer()
    }
}
