package com.ran.eldervideo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.Call
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自绘的来电 / 通话界面。系统来电界面不会出现（本应用是默认电话应用）。
 * 上面超大写着谁打来的，下面两个大圆角矩形：左红「挂电话」、右绿「接电话」。
 * 接通之后绿键收起，红键撑满一行，用来挂断。
 */
class CallActivity : Activity() {

    private lateinit var tagView: TextView
    private lateinit var nameView: TextView
    private lateinit var numView: TextView
    private lateinit var stateView: TextView
    private lateinit var hangBtn: BigCallButton
    private lateinit var answerBtn: BigCallButton

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var connectAt = 0L
    private var ringing = false
    private var preview = false

    private val ticker = object : Runnable {
        override fun run() {
            try {
                render()
            } catch (t: Throwable) {
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        preview = intent?.getBooleanExtra("preview", false) ?: false
        buildUi()
        setupImmersive()
        render()
        handler.post(ticker)
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersive()
    }

    private fun setupImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
            }
        } catch (t: Throwable) {
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            render()
        } catch (t: Throwable) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopRing()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B0B0B"))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(96), dp(22), dp(28))
        }

        tagView = TextView(this).apply {
            text = "来电"
            setTextColor(Color.parseColor("#9AA5B1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        }
        col.addView(tagView, LinearLayout.LayoutParams(-1, -2))

        nameView = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
        }
        col.addView(nameView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })

        numView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
        }
        col.addView(numView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        stateView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#8D9AA5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            gravity = Gravity.CENTER
        }
        col.addView(stateView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        hangBtn = BigCallButton(this, "挂电话", Color.parseColor("#C62828"), Color.parseColor("#6E0A0A"), true)
        hangBtn.setOnPressListener { CallService.endCall() }
        answerBtn = BigCallButton(this, "接电话", Color.parseColor("#2E9E4F"), Color.parseColor("#14501F"), false)
        answerBtn.setOnPressListener { CallService.acceptCall() }

        row.addView(hangBtn, LinearLayout.LayoutParams(0, dp(196), 1f).apply { rightMargin = dp(10) })
        row.addView(answerBtn, LinearLayout.LayoutParams(0, dp(196), 1f).apply { leftMargin = dp(10) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(36) })

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun render() {
        if (preview) {
            tagView.text = "来电"
            nameView.text = "王敬然"
            numView.text = "187 1296 1913"
            stateView.text = ""
            answerBtn.visibility = View.VISIBLE
            return
        }
        val call = CallService.current
        if (call == null) {
            finishSafely()
            return
        }
        val st = try {
            call.details.state
        } catch (t: Throwable) {
            Call.STATE_DISCONNECTED
        }
        val number = try {
            call.details.handle?.schemeSpecificPart.orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val name = CallBook.nameFor(number)
        nameView.text = if (name.isNotEmpty()) name else "陌生号码"
        numView.text = CallBook.pretty(number)

        when (st) {
            Call.STATE_RINGING -> {
                tagView.text = "来电"
                stateView.text = ""
                answerBtn.visibility = View.VISIBLE
                startRing()
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                tagView.text = "正在拨打"
                stateView.text = ""
                answerBtn.visibility = View.GONE
                stopRing()
            }
            Call.STATE_ACTIVE -> {
                tagView.text = "通话中"
                answerBtn.visibility = View.GONE
                stopRing()
                if (connectAt == 0L) connectAt = System.currentTimeMillis()
                val sec = (System.currentTimeMillis() - connectAt) / 1000L
                stateView.text = String.format("%02d:%02d", sec / 60, sec % 60)
            }
            Call.STATE_DISCONNECTED -> {
                stopRing()
                finishSafely()
            }
            else -> {
            }
        }
    }

    private fun finishSafely() {
        handler.removeCallbacks(ticker)
        stopRing()
        try {
            finish()
        } catch (t: Throwable) {
        }
    }

    private fun startRing() {
        if (ringing) return
        ringing = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true
            r.play()
            ringtone = r
        } catch (t: Throwable) {
        }
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            val pattern = longArrayOf(0, 800, 700, 800, 700)
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
                vibrator = v
            }
        } catch (t: Throwable) {
        }
    }

    private fun stopRing() {
        if (!ringing) return
        ringing = false
        try {
            ringtone?.stop()
        } catch (t: Throwable) {
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (t: Throwable) {
        }
        vibrator = null
    }
}

/**
 * 大圆角矩形按钮：先画一层深色投影再画正面，按下去正面会沉下去，
 * 和桌面上那套机械键是同一个手感。图标是手绘的听筒——挂断的转 135°。
 */
class BigCallButton(
    ctx: Context,
    private val label: String,
    private val face: Int,
    private val dark: Int,
    private val hangUp: Boolean
) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var pressed = false
    private var onPress: (() -> Unit)? = null

    fun setOnPressListener(f: () -> Unit) {
        onPress = f
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

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

        val faceBottom = h - off + sink
        val iconCy = sink + faceBottom * 0.33f
        drawHandset(c, w / 2f, iconCy, dp(78f))

        textPaint.textSize = dp(38f)
        c.drawText(label, w / 2f, sink + faceBottom * 0.80f, textPaint)
    }

    private fun drawHandset(c: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.22f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE

        c.save()
        c.rotate(if (hangUp) 135f else -45f, cx, cy)
        val r = size * 0.40f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, 200f, 140f, false, paint)
        c.restore()

        paint.style = Paint.Style.FILL
        val dot = paint.strokeWidth * 0.62f
        c.save()
        c.rotate(if (hangUp) 135f else -45f, cx, cy)
        val a = Math.toRadians(200.0)
        val b = Math.toRadians(340.0)
        c.drawCircle(cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat(), dot, paint)
        c.drawCircle(cx + (r * Math.cos(b)).toFloat(), cy + (r * Math.sin(b)).toFloat(), dot, paint)
        c.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
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
