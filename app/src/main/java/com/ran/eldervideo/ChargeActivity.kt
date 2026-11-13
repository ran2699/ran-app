package com.ran.eldervideo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.sin

/**
 * 充电页：插上充电器自动弹出来（等效锁屏——点亮屏幕、盖住一切，只看到这个界面）。
 * 上半屏一个巨大的电池，绿色液面随电量涨、里面有气泡和呼吸的闪电；
 * 上面大字「正在充电中…」，下面大字「还有 xx 分钟充满」。
 * 点一下屏幕任意位置就回到桌面正常用手机；拔掉充电器也自动关。
 *
 * 剩余时间：系统给的 EXTRA_CHARGING_TIME 在小米上基本是 -1，
 * 所以自己测——记下最近几个 (时间,电量) 采样点，按涨电速度推算。
 */
class ChargeActivity : Activity() {

    private lateinit var big: BigBatteryView
    private lateinit var topText: TextView
    private lateinit var bottomText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val samples = ArrayDeque<Pair<Long, Int>>()

    private val batteryFilter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                Intent.ACTION_POWER_DISCONNECTED -> finishSafely()
            }
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
        buildUi()
        // 先拿一次当前状态，别等广播
        try {
            registerReceiver(receiver, batteryFilter)?.let { onBattery(it) }
        } catch (t: Throwable) {
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#050505"))
        // 点任意处回桌面
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) goHome()
            true
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(60), dp(24), dp(40))
        }

        topText = TextView(this).apply {
            text = "正在充电中…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        col.addView(topText, LinearLayout.LayoutParams(-1, -2))

        big = BigBatteryView(this)
        col.addView(
            big,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(30)
                bottomMargin = dp(30)
            }
        )

        bottomText = TextView(this).apply {
            text = "正在测算时间…"
            setTextColor(Color.parseColor("#7BD88F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        col.addView(bottomText, LinearLayout.LayoutParams(-1, -2))

        val tip = TextView(this).apply {
            text = "点一下屏幕，就能用手机"
            setTextColor(Color.parseColor("#6E7A85"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        }
        col.addView(tip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        big.percent = pct
        big.charging = !full

        if (full) {
            topText.text = "已经充满了"
            bottomText.text = "可以拔下来用了"
            return
        }
        topText.text = "正在充电中…"

        // 系统给的剩余时间（毫秒）。这个 extra 没有公开常量（@hide），
        // 直接按字符串键读，读不到就走下面的自测推算。
        val ms = intent.getLongExtra("android.os.extra.CHARGING_TIME", -1L)
        if (ms > 0) {
            bottomText.text = "还有 ${ceil(ms / 60000.0).toInt()} 分钟充满"
            return
        }

        // 自己推算：保留 15 分钟内的采样点
        val now = System.currentTimeMillis()
        if (pct >= 0) {
            if (samples.isNotEmpty() && now - samples.last().first > 15 * 60_000L) samples.clear()
            samples.addLast(now to pct)
            while (samples.size > 40) samples.removeFirst()
            val first = samples.first()
            val last = samples.last()
            val minutes = (last.first - first.first) / 60000.0
            val gain = last.second - first.second
            if (minutes >= 1.0 && gain >= 1) {
                val perMin = gain / minutes
                val left = ceil((100 - last.second) / perMin).toInt()
                bottomText.text = if (left <= 0) "马上就好" else "还有 $left 分钟充满"
            } else {
                bottomText.text = "正在测算时间…"
            }
        }
    }

    private fun goHome() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        } catch (t: Throwable) {
        }
        finishSafely()
    }

    private fun finishSafely() {
        runCatching { finish() }
    }

    override fun onBackPressed() {
        goHome()
    }
}

/**
 * 巨大的充电电池：白色描边、右侧正极块，绿色液面按电量填充，
 * 液面右缘做正弦波浪，液面下有气泡上升，正中一个呼吸的黄色闪电。
 * 充电时逐帧自绘（postInvalidateOnAnimation），拔掉就停。
 */
class BigBatteryView(ctx: Context) : View(ctx) {

    var percent: Int = 0
        set(v) {
            field = v.coerceIn(0, 100)
            invalidate()
        }
    var charging: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val shell = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B9F6CA") }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = System.currentTimeMillis()

        val capW = h * 0.12f
        val capH = h * 0.34f
        val stroke = 8f * d
        val body = RectF(stroke, (h - capH) / 2f + 6f * d, w - capW - stroke, (h + capH) / 2f - 6f * d)

        // 正极块
        fill.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(body.right + 4f * d, h / 2f - capH / 2f, w - 2f * d, h / 2f + capH / 2f),
            8f * d, 8f * d, fill
        )

        // 外框
        shell.strokeWidth = stroke
        canvas.drawRoundRect(body, 24f * d, 24f * d, shell)

        // 液面（波浪右缘）
        val inset = 16f * d
        val inner = RectF(
            body.left + inset, body.top + inset,
            body.right - inset, body.bottom - inset
        )
        val wave = if (charging) sin(t / 260.0).toFloat() * inset * 0.9f else 0f
        val surfaceX = inner.left + (inner.width()) * (percent / 100f) + wave
        fill.color = Color.parseColor("#35C759")
        if (surfaceX > inner.left + 2f) {
            canvas.drawRoundRect(
                RectF(inner.left, inner.top, surfaceX.coerceAtMost(inner.right), inner.bottom),
                16f * d, 16f * d, fill
            )
        }

        // 气泡：三个小圆从液面底部往上浮
        if (charging && percent > 0) {
            for (i in 0 until 3) {
                val phase = ((t / 1400.0) + i * 0.37) % 1.0
                val bx = inner.left + inner.width() * (0.18f + 0.22f * i)
                if (bx < surfaceX - inner.left * 0) {
                    val by = inner.bottom - (inner.height() * phase.toFloat())
                    bubble.alpha = ((1.0 - phase) * 160).toInt().coerceIn(0, 255)
                    canvas.drawCircle(bx, by, (4f + 2f * i) * d, bubble)
                }
            }
        }

        // 中央闪电，呼吸
        bolt.color = Color.parseColor("#FFD54F")
        bolt.alpha = if (charging) (200 + (55 * sin(t / 320.0))).toInt().coerceIn(120, 255) else 255
        val s = minOf(body.width(), body.height()) * 0.34f
        val cx = body.centerX()
        val cy = body.centerY()
        val p = Path()
        p.moveTo(cx + s * 0.14f, cy - s * 0.52f)
        p.lineTo(cx - s * 0.30f, cy + s * 0.08f)
        p.lineTo(cx - s * 0.04f, cy + s * 0.08f)
        p.lineTo(cx - s * 0.14f, cy + s * 0.52f)
        p.lineTo(cx + s * 0.30f, cy - s * 0.08f)
        p.lineTo(cx + s * 0.04f, cy - s * 0.08f)
        p.close()
        canvas.drawPath(p, bolt)

        if (charging) postInvalidateOnAnimation()
    }
}
