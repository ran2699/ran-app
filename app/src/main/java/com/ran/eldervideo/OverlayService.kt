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
 * 抖音专用浮层。
 * 1. 触摸墙——全屏透明，吃掉中间所有触摸（不涂黑、不遮挡画面）
 * 2. 顶部细黑带——盖住系统状态栏那一行，只放时间、日期、电量
 * 3. 底部按键带——上一个 / 下一个 / 大号红色返回
 * 返回时整层隐藏并回到老人桌面。
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "ElderOverlay"
        private const val TOP_H_DP = 74
        private const val BOTTOM_H_DP = 224

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

    /** 正在回桌面的途中：期间不允许任何逻辑把浮层再显示出来 */
    private var returningHome = false

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
            applyVisibility(false)
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
            if (v && returningHome) {
                Log.i(TAG, "visibility -> true 被忽略（正在回桌面）")
                return@post
            }
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

    /** ① 触摸墙：完全透明，只负责吃掉触摸 */
    private fun buildMask() {
        val wall = TouchWallView(this)
        addView(
            wall,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START, 0, 0
        )
        maskView = wall
    }

    /** ② 顶部细黑带：从屏幕最上沿开始，盖住系统状态栏 */
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
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
        val info = TextView(this).apply {
            setTextColor(Color.parseColor("#EDEDED"))
            textSize = 18f
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

    /** ③ 底部按键带 */
    private fun buildBottomBar() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
            depthDp = 18f
            setOnPressListener { goHome() }
        }

        val btnH = dp(172)
        row.addView(prev, LinearLayout.LayoutParams(0, btnH, 1f).apply { rightMargin = dp(12) })
        row.addView(next, LinearLayout.LayoutParams(0, btnH, 1f).apply { rightMargin = dp(12) })
        row.addView(home, LinearLayout.LayoutParams(dp(188), btnH))

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
            dateText?.text = "$md $week  电量 ${batteryPercent()}%"
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

    /**
     * 返回：先把浮层收掉，再回老人自己的桌面。
     * 屏蔽层必须先消失，否则桌面上的大按钮点不动；
     * 回到桌面后 SwipeService 的前台判断也会把它按回去，这里加双重保险 + 重试。
     */
    private fun goHome() {
        try {
            returningHome = true
            applyVisibility(false)
            handler.postDelayed({ applyVisibility(false) }, 260L)

            val go = Runnable {
                try {
                    val i = Intent(this, MainActivity::class.java)
                    i.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    startActivity(i)
                } catch (e: Throwable) {
                    Log.e(TAG, "goHome failed", e)
                }
            }
            handler.postDelayed(go, 60L)
            handler.postDelayed(go, 320L)     // 第一次被系统吞掉时再推一次
            handler.postDelayed({ returningHome = false }, 2600L)
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

/** 全屏触摸墙：不画任何东西，onTouchEvent 一律 true，把中间区的滑动和点击全部吃掉 */
class TouchWallView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        // 保持一次空绘制，确保窗口真正参与合成与命中测试
        paint.color = Color.TRANSPARENT
        canvas.drawPoint(-1f, -1f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
