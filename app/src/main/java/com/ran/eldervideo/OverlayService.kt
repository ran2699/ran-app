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
        private const val TOP_H_DP = 72
        private const val BOTTOM_H_DP = 200

        /** 回桌面期间强制不显示浮层；由 MainActivity.onResume 解除 */
        @Volatile
        var forceHidden: Boolean = false

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

        fun alive(): Boolean = inst != null

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

    private var shownState: Boolean? = null

    private val pendingShow = Runnable { applyVisibilityNow(true) }

    /**
     * 抖音一进来会连发好几个窗口状态事件，原来是每来一个就 GONE/VISIBLE 折腾一遍，
     * 屏幕上就是"闪几下"。现在：要显示就等 420 毫秒稳定后再挂，且状态没变不再重设；
     * 要隐藏（回桌面）立刻执行，一帧都不拖。
     */
    private fun applyVisibility(v: Boolean) {
        handler.post {
            handler.removeCallbacks(pendingShow)
            if (v) {
                if (returningHome) {
                    Log.i(TAG, "visibility -> true 被忽略（正在回桌面）")
                    return@post
                }
                if (shownState == true) return@post
                handler.postDelayed(pendingShow, 420L)
            } else {
                applyVisibilityNow(false)
            }
        }
    }

    private fun applyVisibilityNow(v: Boolean) {
        if (v && returningHome) return
        if (v == shownState) return
        // 被系统摘掉的浮层要先补回来，否则会出现"明明在抖音里却没有遮罩"
        if (v && (maskView?.parent == null || topView?.parent == null || bottomView?.parent == null)) {
            rebuildOverlay()
        }
        shownState = v
        val vis = if (v) View.VISIBLE else View.GONE
        try {
            maskView?.visibility = vis
            topView?.visibility = vis
            bottomView?.visibility = vis
        } catch (e: Throwable) {
        }
        Log.i(TAG, "visibility -> $v")
    }

    private fun rebuildOverlay() {
        try {
            listOf(maskView, topView, bottomView).forEach { v ->
                v?.let { runCatching { wm.removeView(it) } }
            }
        } catch (e: Throwable) {
        }
        maskView = null
        topView = null
        bottomView = null
        timeText = null
        dateText = null
        shownState = null
        try {
            buildAll()
            Log.i(TAG, "overlay rebuilt")
        } catch (e: Throwable) {
            Log.e(TAG, "overlay rebuild failed", e)
        }
    }

    /**
     * 临时放开触摸墙。
     * 注入的滑动手势和真人手指走的是同一套事件分发，会先命中我们这层最上面的浮层。
     * 所以每次发滑动前把触摸墙设成"不接收触摸"，抖音才能收到这一下；完成后再收回来。
     */
    private fun setMaskTouchable(touchable: Boolean) {
        handler.post {
            val v = maskView ?: return@post
            try {
                val lp = v.layoutParams as WindowManager.LayoutParams
                lp.flags = if (touchable) baseFlags()
                else baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(v, lp)
                Log.i(TAG, "mask touchable=$touchable")
            } catch (e: Throwable) {
                Log.e(TAG, "mask touchable failed", e)
            }
        }
    }

    /** 兜底：万一回调没回来，1.2 秒后一定把触摸墙收回来 */
    private fun restoreMaskSoon() {
        handler.postDelayed({ setMaskTouchable(true) }, 1200L)
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
        // 竖向排：一行大字时间，一行日期电量。并排会挤成两行、顶出黑带
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), sb, dp(24), 0)
        }
        val time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
        val info = TextView(this).apply {
            setTextColor(Color.parseColor("#EDEDED"))
            textSize = 17f
            gravity = Gravity.START
            includeFontPadding = false
            setSingleLine(true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
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
            labelSizeSp = 30f
            depthDp = 20f
            setOnPressListener { trigger(false) }
        }
        val next = MechanicalButton(this).apply {
            labelText = "下一个"
            faceColor = Color.parseColor("#1B5FD9")
            faceColorDark = Color.parseColor("#0A2E63")
            labelSizeSp = 30f
            depthDp = 20f
            setOnPressListener { trigger(true) }
        }
        val home = MechanicalButton(this).apply {
            circular = true
            faceColor = Color.parseColor("#D3261B")
            faceColorDark = Color.parseColor("#6E0A05")
            depthDp = 18f
            setOnPressListener { goHome() }
        }

        val btnH = dp(176)
        row.addView(prev, LinearLayout.LayoutParams(0, btnH, 1f).apply { rightMargin = dp(12) })
        row.addView(next, LinearLayout.LayoutParams(0, btnH, 1f).apply { rightMargin = dp(12) })
        row.addView(home, LinearLayout.LayoutParams(dp(176), btnH))

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
            dateText?.text = "$md $week  ${batteryPercent()}%"
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
        // 注入的手势和真人手指走同一套分发，会先落到最上面这层触摸墙上。
        // 先让墙"不接收触摸"，等窗口属性生效再注入手势，完事立刻收回。
        setMaskTouchable(false)
        handler.postDelayed({
            val ok = svc.swipe(next) { handler.post { setMaskTouchable(true) } }
            if (!ok) {
                handler.post { setMaskTouchable(true) }
                toast("滑动没发出去")
            }
            restoreMaskSoon()
        }, 130L)
    }

    /**
     * 返回：先把浮层收掉，再回老人自己的桌面。
     * 屏蔽层必须先消失，否则桌面上的大按钮点不动；
     * 回到桌面后 SwipeService 的前台判断也会把它按回去，这里加双重保险 + 重试。
     */
    private fun goHome() {
        try {
            returningHome = true
            // 关键：从点击到桌面真正回来这段时间，任何人都别想把浮层再打开
            forceHidden = true
            applyVisibility(false)
            handler.postDelayed({ applyVisibility(false) }, 200L)

            val launch = Runnable {
                try {
                    val i = Intent(this, MainActivity::class.java)
                    i.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    startActivity(i)
                } catch (e: Throwable) {
                    Log.e(TAG, "goHome launch failed", e)
                }
            }
            // 第二只手：直接发 HOME。老人桌面注册成默认桌面，HOME 一定会回到它。
            val homeKey = Runnable {
                try {
                    val h = Intent(Intent.ACTION_MAIN)
                    h.addCategory(Intent.CATEGORY_HOME)
                    h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(h)
                } catch (e: Throwable) {
                    Log.e(TAG, "goHome HOME failed", e)
                }
            }
            // 第一只手：无障碍 global action 发 HOME，最可靠
            handler.post(Runnable {
                try { SwipeService.instance?.goHomeNow() } catch (e: Throwable) {}
            })
            handler.post(launch)
            handler.postDelayed(homeKey, 240L)
            handler.postDelayed(launch, 620L)
            handler.postDelayed(homeKey, 1100L)
            handler.postDelayed({ returningHome = false }, 3000L)
            // 兜底：万一桌面没 resumed，3.5 秒后解除屏蔽，避免以后永远不显示
            handler.postDelayed({
                if (!returningHome) forceHidden = false
            }, 3500L)
        } catch (e: Throwable) {
            Log.e(TAG, "goHome failed", e)
            forceHidden = false
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
