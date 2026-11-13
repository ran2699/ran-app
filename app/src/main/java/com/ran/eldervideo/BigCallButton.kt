package com.ran.eldervideo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 大圆角矩形按钮：先画一层深色投影再画正面，按下去正面会沉一下，
 * 和桌面上那套机械键同一个手感。图标是手绘的：接听、挂断、对勾、叉。
 */
class BigCallButton(
    ctx: Context,
    private val label: String,
    private val face: Int,
    private val dark: Int,
    private val icon: Int
) : View(ctx) {

    companion object {
        const val ICON_ANSWER = 0
        const val ICON_HANG = 1
        const val ICON_TICK = 2
        const val ICON_CROSS = 3
        const val ICON_NONE = 4
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var pressed = false
    private var onPress: (() -> Unit)? = null

    var textSizeSp: Float = 38f
    var iconSizeDp: Float = 78f

    fun setOnPressListener(f: () -> Unit) {
        onPress = f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val off = dp(10f)
        val rad = dp(34f)
        val sink = if (pressed) off * 0.7f else 0f

        paint.style = Paint.Style.FILL
        paint.color = dark
        c.drawRoundRect(RectF(0f, off, w, h), rad, rad, paint)

        paint.color = face
        c.drawRoundRect(RectF(0f, sink, w, h - off + sink), rad, rad, paint)

        val bottom = h - off + sink
        textPaint.textSize = dp(textSizeSp)

        if (icon == ICON_NONE) {
            val ty = sink + (bottom - sink) / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            c.drawText(label, w / 2f, ty, textPaint)
        } else {
            drawIcon(c, w / 2f, sink + bottom * 0.32f, dp(iconSizeDp))
            c.drawText(label, w / 2f, sink + bottom * 0.80f, textPaint)
        }
    }

    private fun drawIcon(c: Canvas, cx: Float, cy: Float, size: Float) {
        when (icon) {
            ICON_ANSWER, ICON_HANG -> drawHandset(c, cx, cy, size, icon == ICON_HANG)
            ICON_TICK -> drawTick(c, cx, cy, size)
            ICON_CROSS -> drawCross(c, cx, cy, size)
        }
    }

    private fun drawHandset(c: Canvas, cx: Float, cy: Float, size: Float, hang: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.22f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE

        c.save()
        c.rotate(if (hang) 135f else -45f, cx, cy)
        val r = size * 0.40f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, 200f, 140f, false, paint)
        c.restore()

        paint.style = Paint.Style.FILL
        val dot = paint.strokeWidth * 0.62f
        c.save()
        c.rotate(if (hang) 135f else -45f, cx, cy)
        val a = Math.toRadians(200.0)
        val b = Math.toRadians(340.0)
        c.drawCircle(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat(), dot, paint)
        c.drawCircle(cx + (r * Math.cos(b)).toFloat(), cy + (r * Math.sin(b)).toFloat(), dot, paint)
        c.restore()
    }

    private fun drawTick(c: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.24f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.WHITE
        val p = Path()
        p.moveTo(cx - size * 0.42f, cy + size * 0.02f)
        p.lineTo(cx - size * 0.10f, cy + size * 0.34f)
        p.lineTo(cx + size * 0.44f, cy - size * 0.34f)
        c.drawPath(p, paint)
    }

    private fun drawCross(c: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.24f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        val s = size * 0.34f
        c.drawLine(cx - s, cy - s, cx + s, cy + s, paint)
        c.drawLine(cx - s, cy + s, cx + s, cy - s, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                Haptics.heavy(context)
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                invalidate()
                onPress?.invoke()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
