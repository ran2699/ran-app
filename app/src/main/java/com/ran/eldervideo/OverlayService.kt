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
        private const val TOP_H_DP = 86
        // 底栏总高（不含系统导航栏）：和老人桌面那条底栏同高同位
        private const val BOTTOM_H_DP = 176

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
        // 建好必须先收起来。
        // WindowManager.addView 加进去的视图默认就是 VISIBLE，而 applyVisibility(false) 里有一句
        //「没显示过就不用隐藏」(shownState != true 直接 return)，于是浮层从服务起来那一刻就糊在
        // 老人桌面上：顶部压住大字号时间、底部把两个底部键换成「上一个／下一个」，全屏触摸墙还
        // 把桌面所有点击都吃掉——老人点了没反应。这就是实测看到的那一屏。
        shownState = false
        try {
            maskView?.visibility = View.GONE
            topView?.visibility = View.GONE
            bottomView?.visibility = View.GONE
        } catch (t: Throwable) {
        }
    }

    private var shownState: Boolean? = null

    /** 触摸墙被临时摘下来过（注入手势期间），此时 parent 为空是正常的，不要误判成"被系统摘掉" */
    private var maskDetached = false

    private val pendingShow = Runnable { applyVisibilityNow(true) }
    private val pendingHide = Runnable { applyVisibilityNow(false) }

    /**
     * 抖音启动时会连着抛好几个窗口状态事件（splash → 主界面 → 系统弹窗），
     * 原来每来一个就 VISIBLE/GONE 折腾一遍，屏幕上就是"闪几下、有时干脆不出"。
     * 现在的规则：
     *   - 要显示：等 520 毫秒没有反向事件再挂，期间来了 false 就整个取消；
     *   - 要隐藏：不在"回桌面"意图里时也等 800 毫秒，别被抖音内部的窗口抖动带跑；
     *   - 回到桌面（forceHidden / returningHome）必须立刻隐藏，一帧都不拖。
     */
    private fun applyVisibility(v: Boolean) {
        handler.post {
            if (v) {
                handler.removeCallbacks(pendingHide)
                if (returningHome || forceHidden) {
                    Log.i(TAG, "visibility -> true 被忽略（正在回桌面）")
                    return@post
                }
                if (shownState == true) return@post
                handler.removeCallbacks(pendingShow)
                handler.postDelayed(pendingShow, 520L)
            } else {
                handler.removeCallbacks(pendingShow)
                if (forceHidden || returningHome) {
                    handler.removeCallbacks(pendingHide)
                    applyVisibilityNow(false)
                } else {
                    if (shownState != true) return@post
                    handler.removeCallbacks(pendingHide)
                    handler.postDelayed(pendingHide, 800L)
                }
            }
        }
    }

    private fun applyVisibilityNow(v: Boolean) {
        if (v && returningHome) return
        if (v == shownState) return
        // 被系统摘掉的浮层要先补回来，否则会出现"明明在抖音里却没有遮罩"
        if (v && !maskDetached && (maskView?.parent == null || topView?.parent == null || bottomView?.parent == null)) {
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
    /**
     * 注入手势前把整层触摸墙从窗口上摘下来。
     * 1.0.6 之前只是给墙加 FLAG_NOT_TOUCHABLE，实测抖音一点反应都没有——合成手势照样被这层
     * 全屏窗口接走（日志里 dispatchGesture=true、gesture completed 齐全，画面就是不动）。
     * 摘掉窗口才是干净的：此刻屏幕上没有自己的窗口挡着，抖音直接收接这一下。
     */
    private fun detachMask() {
        handler.post {
            try {
                val v = maskView ?: return@post
                if (v.parent != null) {
                    wm.removeView(v)
                    maskDetached = true
                    Log.i(TAG, "mask detached")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "detach mask failed", e)
            }
        }
    }

    /** 把手势发完的触摸墙挂回去；顶栏底栏跟着重新抬一遍，否则按钮点不动 */
    private fun attachMask() {
        handler.post {
            try {
                val v = maskView ?: return@post
                if (v.parent == null) {
                    wm.addView(
                        v,
                        WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            overlayType(), baseFlags(), PixelFormat.TRANSLUCENT
                        ).apply { gravity = Gravity.TOP or Gravity.START }
                    )
                }
                // 后加进 WindowManager 的窗口在上面，顶栏/底栏必须重新排到触摸墙之上
                listOf(topView, bottomView).forEach { vv ->
                    if (vv != null && vv.parent != null) {
                        val lp = vv.layoutParams as WindowManager.LayoutParams
                        wm.removeView(vv)
                        wm.addView(vv, lp)
                    }
                }
                maskDetached = false
                Log.i(TAG, "mask attached")
            } catch (e: Throwable) {
                Log.e(TAG, "attach mask failed", e)
            }
        }
    }

    /** 兜底：万一手势回调没回来，1.2 秒后一定把触摸墙挂回去 */
    private fun restoreMaskSoon() {
        handler.postDelayed({ attachMask() }, 1200L)
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

    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(48)
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
            setPadding(dp(22), sb, dp(22), dp(6))
        }
        val time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
        }
        val info = TextView(this).apply {
            setTextColor(Color.parseColor("#EDEDED"))
            textSize = 19f
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
            WindowManager.LayoutParams.WRAP_CONTENT, // 顶栏高度跟着字走，系统放大字体也不会被裁
            Gravity.TOP or Gravity.START, 0, 0
        )
        topView = bar
        refreshClock()
    }

    /** ③ 底部按键带：左边「上一个 / 下一个」横向并排，右边红球返回桌面 */
    private fun buildBottomBar() {
        // 和老人桌面共用同一条底栏规格：16 边距 + 144 键高 + 16 边距 = 176dp。
        // 桌面那边这一格的宽度给「关掉手机」，抖音里换成两个方向键横向并排。
        val navH = navigationBarHeight()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16) + navH)
        }

        val prev = MechanicalButton(this).apply {
            labelText = "上一个"
            faceColor = Color.parseColor("#1B5FD9")
            faceColorDark = Color.parseColor("#0A2E63")
            labelSizeSp = 30f
            depthDp = 18f
            setOnPressListener { trigger(false) }
        }
        val next = MechanicalButton(this).apply {
            labelText = "下一个"
            faceColor = Color.parseColor("#1B5FD9")
            faceColorDark = Color.parseColor("#0A2E63")
            labelSizeSp = 30f
            depthDp = 18f
            setOnPressListener { trigger(true) }
        }
        val home = MechanicalButton(this).apply {
            circular = true
            faceColor = Color.parseColor("#D3261B")
            faceColorDark = Color.parseColor("#6E0A05")
            depthDp = 26f
            setOnPressListener { goHome() }
        }

        // 两键横向均分左区，中间留一道缝
        val arrows = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arrows.addView(prev, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(7) })
        arrows.addView(next, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(7) })

        row.addView(arrows, LinearLayout.LayoutParams(0, dp(144), 1f))
        row.addView(home, LinearLayout.LayoutParams(dp(144), dp(144)).apply { leftMargin = dp(14) })

        addView(
            row,
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(BOTTOM_H_DP) + navH,
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
        detachMask()
        handler.postDelayed({
            val ok = svc.swipe(next) { handler.post { attachMask() } }
            if (!ok) {
                handler.post { attachMask() }
                toast("滑动没发出去")
            }
            restoreMaskSoon()
        }, 120L)
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
