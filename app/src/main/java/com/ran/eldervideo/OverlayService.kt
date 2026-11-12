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
 * 1. 触摸墙——全屏透明，吃掉所有触摸（视频不许滑、图标不许点），顺手把右侧点赞栏涂成实黑
 * 2. 顶端黑带——从屏幕最上沿开始，盖住系统状态栏，放超大时间 / 日期 / 电量
 * 3. 底部黑带——三颗机械按键
 * 回到自己的桌面时整层隐藏。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "ElderOverlay"
        private const val TOP_H_DP = 132
        private const val BOTTOM_H_DP = 330

        @Volatile
        private var running = false

        @Volatile
        private var inst: OverlayService? = null

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

        fun setVisible(v: Boolean) {
            inst?.applyVisibility(v)
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
        inst = this
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
        buildMask()
        buildTopBar()
        buildBottomBar()
    }

    private fun applyVisibility(v: Boolean) {
        handler.post {
            val vis = if (v) View.VISIBLE else View.GONE
            try {
                maskView?.visibility = vis
                topView?.visibility = vis
                bottomView?.visibility = vis
            } catch (e: Throwable) {
            }
            Log.i(TAG, "visibility -> $v")
        }
    }

    override fun onDestroy() {
        running = false
        inst = null
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

    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun addView(v: View, w: Int, h: Int, gravity: Int, x: Int, y: Int) {
        val lp = WindowManager.LayoutParams(w, h, overlayType(), baseFlags(), PixelFormat.TRANSLUCENT)
        lp.gravity = gravity
        lp.x = x
        lp.y = y
        wm.addView(v, lp)
    }

    // ---------------- ① 触摸墙（含右侧黑条） ----------------
    private fun buildMask() {
        val wall = TouchWallView(this, statusBarHeight() + dp(TOP_H_DP), dp(BOTTOM_H_DP), 0.70f)
        addView(
            wall,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START, 0, 0
        )
        maskView = wall
    }

    // ---------------- ② 顶部黑带 ----------------
    private fun buildTopBar() {
        val sb = statusBarHeight()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), sb, dp(20), 0)
        }
        val time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 48f
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

        addView(
            bar,
            WindowManager.LayoutParams.MATCH_PARENT,
            sb + dp(TOP_H_DP),
            Gravity.TOP or Gravity.START, 0, 0
        )
        topView = bar
        refreshClock()
    }

    // ---------------- ③ 底部黑带 ----------------
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

        addView(
            row,
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(BOTTOM_H_DP),
            Gravity.BOTTOM or Gravity.START, 0, 0
        )
        bottomView = row
    }

    private fun refreshClock() {
        try {
            val now = Date()
            timeText?.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
            val md = SimpleDateFormat("M月d日", Locale.CHINA).format(now)
            val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
            dateText?.text = "$md $week\n电量 ${batteryPercent()}%"
        } catch (e: Throwable) {
            Log.e(TAG, "refreshClock failed", e)
        }
    }

    private fun batteryPercent(): Int = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Throwable) {
        -1
    }

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
        if (!svc.swipe(next)) toast("滑动没发出去")
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
 * 全屏触摸墙：onTouchEvent 一律返回 true，把落在中间视频区的滑动和点击全部吃掉。
 * 同时把右侧点赞/评论/收藏那一列涂成实黑（顶带与底带之间那条）。
 */
class TouchWallView(
    ctx: Context,
    private val topPx: Int,
    private val bottomPx: Int,
    private val xRatio: Float
) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bottom = h - bottomPx
        if (bottom <= topPx) return
        paint.color = Color.BLACK
        canvas.drawRect(w * xRatio, topPx.toFloat(), w, bottom, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
