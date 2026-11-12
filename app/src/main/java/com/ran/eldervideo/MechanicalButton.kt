package com.ran.eldervideo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 仿古机械风实体按键。
 * 分层绘制：底部投影(厚度) -> 金属外框 -> 按键面(竖向渐变) -> 顶部高光 -> 文字。
 * 按下时整体下沉、投影收紧、整体压暗，抬起弹回。
 */
class MechanicalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // 按键面主色（可外部设置）
    var faceColor: Int = Color.parseColor("#1E5FD0")
    var faceColorDark: Int = Color.parseColor("#12408F")
    var labelText: String = ""
    var labelSizeSp: Float = 34f

    /** 圆形按键（返回桌面用） */
    var circular: Boolean = false

    private val dp = resources.displayMetrics.density

    private val depthPx = 7f * dp      // 按键厚度
    private val pressTrip = 6f * dp    // 按下位移

    private var pressedDown = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#000000")
        alpha = 110
        setShadowLayer(9f * dp, 0f, 4f * dp, Color.argb(150, 0, 0, 0))
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    private val faceRect = RectF()
    private val frameRect = RectF()

    private var clickAction: (() -> Unit)? = null

    fun setOnPressListener(action: () -> Unit) {
        clickAction = action
    }

    private fun shade(c: Int, factor: Float): Int {
        val a = Color.alpha(c)
        val r = (Color.red(c) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(c) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(c) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 5f * dp
        val top = if (pressedDown) pressTrip else 0f

        val bodyTop = inset + top
        val bodyBottom = h - inset - depthPx + top
        val bodyHeight = bodyBottom - bodyTop
        if (bodyHeight <= 0f) return

        // 1. 底部投影（按键厚度感）
        if (!pressedDown) {
            paint.reset()
            paint.isAntiAlias = true
            paint.color = shade(faceColorDark, 0.30f)
            paint.alpha = 200
            val shadowRect = RectF(inset, bodyTop + depthPx * 0.75f, w - inset, bodyBottom + depthPx)
            if (circular) {
                canvas.drawOval(shadowRect, paint)
            } else {
                val rr = 16f * dp
                canvas.drawRoundRect(shadowRect, rr, rr, paint)
            }
        }

        // 2. 金属外框
        frameRect.set(inset, bodyTop, w - inset, bodyBottom)
        framePaint.shader = LinearGradient(
            0f, bodyTop, 0f, bodyBottom,
            intArrayOf(
                Color.parseColor("#E8E8EA"),
                Color.parseColor("#9C9CA2"),
                Color.parseColor("#6E6E74"),
                Color.parseColor("#B4B4BA")
            ),
            floatArrayOf(0f, 0.35f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        if (circular) {
            canvas.drawOval(frameRect, framePaint)
        } else {
            val rr = 18f * dp
            canvas.drawRoundRect(frameRect, rr, rr, framePaint)
        }
        framePaint.shader = null

        // 3. 按键面（留出金属边框）
        val pad = 4f * dp
        faceRect.set(
            frameRect.left + pad, frameRect.top + pad,
            frameRect.right - pad, frameRect.bottom - pad
        )
        val bright = if (pressedDown) shade(faceColor, 0.72f) else faceColor
        val dark = if (pressedDown) shade(faceColorDark, 0.72f) else faceColorDark
        facePaint.shader = LinearGradient(
            0f, faceRect.top, 0f, faceRect.bottom,
            intArrayOf(shade(bright, 1.22f), bright, dark, shade(dark, 0.70f)),
            floatArrayOf(0f, 0.22f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        if (circular) {
            canvas.drawOval(faceRect, facePaint)
        } else {
            val rr = 14f * dp
            canvas.drawRoundRect(faceRect, rr, rr, facePaint)
        }
        facePaint.shader = null

        // 4. 顶部高光（按下时减弱）
        if (!pressedDown) {
            glossPaint.color = Color.argb(90, 255, 255, 255)
            val gh = faceRect.height() * 0.30f
            val glossRect = RectF(
                faceRect.left + 6f * dp, faceRect.top + 4f * dp,
                faceRect.right - 6f * dp, faceRect.top + 4f * dp + gh
            )
            glossPaint.shader = LinearGradient(
                0f, glossRect.top, 0f, glossRect.bottom,
                intArrayOf(Color.argb(115, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP
            )
            if (circular) {
                canvas.drawOval(glossRect, glossPaint)
            } else {
                val rr = 12f * dp
                canvas.drawRoundRect(glossRect, rr, rr, glossPaint)
            }
            glossPaint.shader = null
        }

        // 5. 文字或圆环
        if (circular) {
            // 白色圆环：粗描边
            ringPaint.strokeWidth = 11f * dp
            ringPaint.color = Color.WHITE
            val r = faceRect.width() * 0.30f
            canvas.drawCircle(faceRect.centerX(), faceRect.centerY(), r, ringPaint)
        } else if (labelText.isNotEmpty()) {
            textPaint.textSize = labelSizeSp * resources.displayMetrics.scaledDensity
            textPaint.isFakeBoldText = true
            textPaint.setShadowLayer(3f * dp, 0f, 2f * dp, Color.argb(190, 0, 0, 0))
            val fm = textPaint.fontMetrics
            val baseline = faceRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(labelText, faceRect.centerX(), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedDown = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedDown) {
                    pressedDown = false
                    invalidate()
                    if (event.x >= 0 && event.x <= width && event.y >= 0 && event.y <= height) {
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedDown = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        clickAction?.invoke()
        return true
    }
}
