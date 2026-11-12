package com.ran.eldervideo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三个窗口：
 * 1. 触摸墙——全屏、透明、吃掉所有触摸（视频不许滑、图标不许点），只把最右侧点赞栏涂成实黑
 * 2. 顶部信息条——全实黑，超大时间 / 日期 / 电量，不接收触摸
 * 3. 底部按键条——全实黑高条，三颗机械按键，接收触摸
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "ElderOverlay"

        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) {
                Log.w(TAG, "no overlay permission")
                return
            }
            try {
                ctx.startService(Intent(ctx, OverlayService::class.java))
            } catch (e: Throwable) {
                Log.e(TAG, "startService failed", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, OverlayService::class.java))
            } catch (e: Throwable) {
            }
        }
    }

    private lateinit var wm: WindowManager
    private var maskView: View? = null
    private var topView: View? = null
    private var bottomView: View? = null
    private var timeText: TextView? = null
    private var dateText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastTap = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refreshClock()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            buildAll()
            running = true
        } catch (e: Throwable) {
            Log.e(TAG, "build overlay failed", e)
        }
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            try {
                buildAll()
                running = true
            } catch (e: Throwable) {
                Log.e(TAG, "rebuild failed", e)
            }
        }
        return START_STICKY
    }

    private fun buildAll() {
        // 顺序即层序：先加的在下
        buildMask()
        buildTopBar()
        buildBottomBar()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        listOf(maskView, topView, bottomView).forEach { v ->
            v?.let { runCatching { wm.removeView(it) } }
        }
        maskView = null
        topView = null
        bottomView = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(32)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    // ---------------- 触摸墙 ----------------
    private fun buildMask() {
        val wall = TouchWallView(this)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        wm.addView(wall, lp)
        maskView = wall
        Log.i(TAG, "touch wall added")
    }

    // ---------------- 顶部信息条 ----------------
    private fun buildTopBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(8), dp(20), dp(10))
            minimumHeight = dp(120)
        }

        val time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
        val info = TextView(this).apply {
            setTextColor(Color.parseColor("#F0F0F0"))
            textSize = 19f
            gravity = Gravity.END
            includeFontPadding = false
        }

        bar.addView(time, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timeText = time
        dateText = info

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = statusBarHeight()
        }
        wm.addView(bar, lp)
        topView = bar
        refreshClock()
    }

    private fun refreshClock() {
        try {
            val now = Date()
            val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
            val md = SimpleDateFormat("M月d日", Locale.CHINA).format(now)
            val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
            val pct = batteryPercent()
            timeText?.text = hm
            dateText?.text = "$md $week\n电量 ${pct}%"
        } catch (e: Throwable) {
            Log.e(TAG, "refreshClock failed", e)
        }
    }

    private fun batteryPercent(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Throwable) {
            -1
        }
    }

    // ---------------- 底部按键条 ----------------
    private fun buildBottomBar() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }

        val prev = MechanicalButton(this).apply {
            labelText = "上一个"
            faceColor = Color.parseColor("#1B5FD9")
            faceColorDark = Color.parseColor("#0A2E63")
            labelSizeSp = 32f
            depthDp = 16f
            setOnPressListener { trigger(false) }
        }
        val next = MechanicalButton(this).apply {
            labelText = "下一个"
            faceColor = Color.parseColor("#1B5FD9")
            faceColorDark = Color.parseColor("#0A2E63")
            labelSizeSp = 32f
            depthDp = 16f
            setOnPressListener { trigger(true) }
        }
        val home = MechanicalButton(this).apply {
            circular = true
            faceColor = Color.parseColor("#D3261B")
            faceColorDark = Color.parseColor("#6E0A05")
            depthDp = 16f
            setOnPressListener { goHome() }
        }

        val btnH = dp(150)
        row.addView(prev, LinearLayout.LayoutParams(0, btnH, 1f).apply { rightMargin = dp(14) })
        row.addView(next, LinearLayout.LayoutParams(0, btnH, 1f).apply { rightMargin = dp(14) })
        row.addView(home, LinearLayout.LayoutParams(0, btnH, 1f))

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(330),
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            y = 0
        }
        wm.addView(row, lp)
        bottomView = row
    }

    // ---------------- 动作 ----------------
    private fun trigger(next: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastTap < 450L) return
        lastTap = now

        val svc = SwipeService.instance
        if (svc == null) {
            toast("辅助功能没开，请重新打开")
            return
        }
        Log.i(TAG, "trigger next=$next")
        val ok = svc.swipe(next) { }
        if (!ok) {
            toast("滑动没发出去")
        }
    }

    private fun goHome() {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Throwable) {
            Log.e(TAG, "goHome failed", e)
        }
    }

    private fun toast(msg: String) {
        try {
            handler.post {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
        }
    }
}

/**
 * 全屏触摸墙：自身可触摸，onTouchEvent 一律返回 true，把落在视频区的滑动和点击全部吃掉。
 * 只画一条实黑，盖住右侧点赞/评论/收藏/转发那一列。
 */
class TouchWallView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.color = Color.BLACK
        // 右侧图标栏：整条涂黑
        canvas.drawRect(w * 0.70f, 0f, w, h, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return true
    }
}
