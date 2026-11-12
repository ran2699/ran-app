package com.ran.eldervideo

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 老人桌面。
 * 顶部：超大时间 + 日期 + 大电池（<40% 黄，<20% 红）
 * 中间：两列三行立体大按键
 * 底部左：红色"关掉手机"；底部右：常驻大号红色返回键（所有页面都在）
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var homePage: View
    private lateinit var datePage: View
    private var weatherPage: View? = null
    private var torchBtn: MechanicalButton? = null
    private lateinit var backBtn: View
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var battery: BatteryView
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var torchOn = false

    /** 时间上的暗号：连续点 7 下才出密码页 */
    private var secretTaps = 0
    private var lastSecretAt = 0L
    private var pinPage: View? = null
    private var pinTimer: Runnable? = null
    private var a11yTip: View? = null

    private val ticker = object : Runnable {
        override fun run() {
            refreshClock()
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (t: Throwable) {
            showFatal(t)
        }
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        checkAccessibility()
        try {
            OverlayService.setVisible(false)
            // 稍等一会儿再解除屏蔽，防止抖音的滞后事件把浮层又弹出来
            root.postDelayed({
                try {
                    OverlayService.forceHidden = false
                } catch (t: Throwable) {
                }
            }, 1000L)
        } catch (t: Throwable) {
        }
        try {
            refreshClock()
            refreshHint()
        } catch (t: Throwable) {
        }
        setupImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersive()
    }

    override fun onBackPressed() {
        // 系统返回键无效，只有屏幕右下角那颗大红键能返回
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

    // ---------------- 界面 ----------------
    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(BG)
        homePage = buildHome()
        datePage = buildDatePage()
        datePage.visibility = View.GONE
        root.addView(homePage)
        root.addView(datePage)

        backBtn = MechanicalButton(this).apply {
            circular = true
            faceColor = Color.parseColor("#D3261B")
            faceColorDark = Color.parseColor("#6E0A05")
            depthDp = 18f
            setOnPressListener { onBackKey() }
        }
        root.addView(
            backBtn,
            FrameLayout.LayoutParams(dp(176), dp(176), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20)
                bottomMargin = dp(20)
            }
        )

        setContentView(root)
        checkAccessibility()
        refreshClock()
    }

    private fun buildHome(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(30), dp(24), dp(24))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 66f)
            setSingleLine(true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            setOnClickListener { onSecretTap() }
        }
        dateText = TextView(this).apply {
            setTextColor(Color.parseColor("#B9C2CC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            includeFontPadding = false
        }
        left.addView(timeText)
        left.addView(dateText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        battery = BatteryView(this)
        top.addView(battery, LinearLayout.LayoutParams(dp(160), dp(80)))
        col.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        col.addView(buildGrid(), LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(58) })

        hint = TextView(this).apply {
            setTextColor(Color.parseColor("#111111"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            setBackgroundColor(YELLOW)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setOnClickListener { openOverlaySettings() }
        }
        col.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // 底行：关机键占左边，右边留出返回键的位置
        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val off = MechanicalButton(this).apply {
            labelText = "关掉手机"
            faceColor = Color.parseColor("#D3261B")
            faceColorDark = Color.parseColor("#6E0A05")
            labelSizeSp = 26f
            depthDp = 16f
            setOnPressListener { blackOut() }
        }
        bottomRow.addView(off, LinearLayout.LayoutParams(0, dp(120), 1f))
        bottomRow.addView(View(this), LinearLayout.LayoutParams(dp(160), dp(120)))
        col.addView(bottomRow, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(16) })

        return col
    }

    private fun buildGrid(): View {
        val specs = listOf(
            "抖音" to Color.parseColor("#1B5FD9"),
            "打电话" to Color.parseColor("#1B5FD9"),
            "日历" to Color.parseColor("#1B5FD9"),
            "天气" to Color.parseColor("#1B5FD9"),
            "手电筒" to Color.parseColor("#1B5FD9"),
            "空" to Color.parseColor("#3A3F46")
        )
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in 0 until 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                val idx = r * 2 + c
                val (label, color) = specs[idx]
                val b = MechanicalButton(this).apply {
                    labelText = label
                    faceColor = color
                    faceColorDark = shade(color, 0.32f)
                    labelSizeSp = 36f
                    depthDp = 14f
                    if (idx == 4) torchBtn = this
                    setOnPressListener { onKey(idx) }
                }
                row.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (c == 0) rightMargin = dp(18)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply {
                if (r < 2) bottomMargin = dp(18)
            })
        }
        return grid
    }

    private fun buildDatePage(): View = CalendarPage(this) { backToHome() }

    // ---------------- 导航 ----------------
    private fun onBackKey() {
        val w = weatherPage
        if (w != null && w.visibility == View.VISIBLE) {
            w.visibility = View.GONE
            return
        }
        if (datePage.visibility == View.VISIBLE) {
            datePage.visibility = View.GONE
            return
        }
        // 已经在桌面上
    }

    private fun backToHome() {
        weatherPage?.visibility = View.GONE
        datePage.visibility = View.GONE
    }

    // ---------------- 暗号与密码 ----------------
    /** 连续点 7 下时间，才出密码页 */
    private fun onSecretTap() {
        val now = System.currentTimeMillis()
        if (now - lastSecretAt > 1200L) secretTaps = 0
        lastSecretAt = now
        secretTaps++
        if (secretTaps >= 7) {
            secretTaps = 0
            showPinPage()
        }
    }
    private fun savedPin(): String =
        getSharedPreferences("elder", MODE_PRIVATE).getString("pin", "2468") ?: "2468"
    private fun showPinPage() {
        if (pinPage != null) return
        val entered = StringBuilder()
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#F0000000")) }
        layer.isClickable = true
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(40), dp(26), dp(40))
        }
        val title = TextView(this).apply {
            text = "请输入密码"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val dots = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 54f)
            gravity = Gravity.CENTER
        }
        val tip = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
        }
        fun refresh() {
            dots.text = "●".repeat(entered.length) + "○".repeat((4 - entered.length).coerceAtLeast(0))
        }
        refresh()
        fun submit() {
            if (entered.length < 4) return
            if (entered.toString() == savedPin()) {
                closePinPage()
                toast("密码对了")
                handler.postDelayed({ openHomeSettings() }, 320L)
            } else {
                entered.setLength(0)
                refresh()
                tip.text = "不对，再试一次"
            }
        }
        col.addView(title)
        col.addView(dots, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        col.addView(tip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("取消", "0", "删掉")
        )
        for (r in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) {
                val isCancel = k == "取消"
                val kb = MechanicalButton(this).apply {
                    labelText = k
                    labelSizeSp = if (k.length > 1) 30f else 46f
                    depthDp = 10f
                    faceColor = if (isCancel) Color.parseColor("#5A5A5A") else Color.parseColor("#1B5FD9")
                    faceColorDark = if (isCancel) Color.parseColor("#242424") else Color.parseColor("#0A2E63")
                    setOnPressListener {
                        when (k) {
                            "取消" -> closePinPage()
                            "删掉" -> if (entered.isNotEmpty()) {
                                entered.deleteCharAt(entered.length - 1)
                                refresh()
                            }
                            else -> {
                                if (entered.length < 4) {
                                    entered.append(k)
                                    refresh()
                                }
                                if (entered.length == 4) handler.postDelayed({ submit() }, 140L)
                            }
                        }
                    }
                }
                row.addView(kb, LinearLayout.LayoutParams(0, dp(118), 1f).apply {
                    rightMargin = dp(10)
                    topMargin = dp(10)
                })
            }
            keypad.addView(row)
        }
        col.addView(keypad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        layer.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        pinPage = layer
        tip.text = "10 秒后自动关闭"
        // 10 秒没输对就自己关掉，防误触
        var left = 10
        val countdown = object : Runnable {
            override fun run() {
                if (pinPage !== layer) return
                left--
                if (left <= 0) {
                    closePinPage()
                    return
                }
                tip.text = "$left 秒后自动关闭"
                handler.postDelayed(this, 1000L)
            }
        }
        pinTimer = countdown
        handler.postDelayed(countdown, 1000L)
    }
    private fun closePinPage() {
        pinTimer?.let { handler.removeCallbacks(it) }
        pinTimer = null
        pinPage?.let { v ->
            try {
                root.removeView(v)
            } catch (t: Throwable) {
            }
        }
        pinPage = null
    }
    /** 密码对了才允许离开：打开系统的"默认桌面"设置页 */
    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Throwable) {
                toast("打不开设置")
            }
        }
    }
    // ---------------- 动作 ----------------
    private fun onKey(id: Int) {
        when (id) {
            0 -> openDouyin()
            1 -> dial()
            2 -> datePage.visibility = View.VISIBLE
            3 -> openWeather()
            4 -> toggleTorch()
            else -> toast("这个位置先空着")
        }
    }

    private fun openDouyin() {
        if (!Settings.canDrawOverlays(this)) {
            toast("先给一次显示权限")
            openOverlaySettings()
            return
        }
        try {
            OverlayService.start(this)
            OverlayService.setVisible(true)
            handler.postDelayed({ OverlayService.setVisible(true) }, 900L)
        } catch (t: Throwable) {
            toast("控制条没起来")
        }
        openTikTok()
    }

    private fun openTikTok() {
        for (p in SwipeService.TIKTOK_PACKAGES) {
            val i = packageManager.getLaunchIntentForPackage(p) ?: continue
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
        toast("这台手机没找到抖音")
    }

    private fun dial() {
        try {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Throwable) {
            toast("打不开拨号")
        }
    }

    /** 自己写的天气页，不走第三方 App */
    private fun openWeather() {
        var w = weatherPage
        if (w == null) {
            w = WeatherPage(this)
            w.visibility = View.GONE
            root.addView(w, FrameLayout.LayoutParams(-1, -1))
            weatherPage = w
        }
        w.visibility = View.VISIBLE
        root.bringChildToFront(backBtn)
    }

    private fun toggleTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run {
                toast("这台手机没有闪光灯")
                return
            }
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            torchBtn?.let { b ->
                b.faceColor = if (torchOn) Color.parseColor("#F2B705") else Color.parseColor("#1B5FD9")
                b.faceColorDark = if (torchOn) Color.parseColor("#7A5C00") else Color.parseColor("#0A2E63")
                b.invalidate()
            }
            toast(if (torchOn) "手电筒打开啦，再点一下关掉" else "手电筒关掉了")
        } catch (t: Throwable) {
            toast("手电筒打不开")
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Throwable) {
            toast("打不开设置")
        }
    }

    private fun blackOut() {
        if (aodView != null) return
        val black = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }

        aodClock = TextView(this).apply {
            setTextColor(Color.parseColor("#4A5058"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 132f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        aodDate = TextView(this).apply {
            setTextColor(Color.parseColor("#39404A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        col.addView(aodClock, LinearLayout.LayoutParams(-1, -2))
        col.addView(aodDate, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        black.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        val wake = MechanicalButton(this).apply {
            labelText = "打开手机"
            faceColor = Color.parseColor("#2E6B33")
            faceColorDark = Color.parseColor("#0E2A12")
            labelSizeSp = 46f
            depthDp = 18f
            setOnPressListener { wakeUp() }
        }
        black.addView(wake, FrameLayout.LayoutParams(-1, dp(160), Gravity.BOTTOM).apply {
            leftMargin = dp(28)
            rightMargin = dp(28)
            bottomMargin = dp(90)
        })

        aodView = black
        root.addView(black, FrameLayout.LayoutParams(-1, -1))
        try {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (t: Throwable) {
        }
        paintAod()
        handler.post(aodTicker)
    }

    private fun paintAod() {
        val c = aodClock ?: return
        val now = Date()
        c.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        aodDate?.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
    }

    private fun wakeUp() {
        handler.removeCallbacks(aodTicker)
        aodView?.let {
            try {
                root.removeView(it)
            } catch (t: Throwable) {
            }
        }
        aodView = null
        aodClock = null
        aodDate = null
        try {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (t: Throwable) {
        }
        refreshClock()
    }

    // ---------------- 小工具 ----------------
    private fun refreshClock() {
        val now = Date()
        timeText.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        val md = SimpleDateFormat("M月d日", Locale.CHINA).format(now)
        val wk = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
        battery.percent = batteryPercent()
        dateText.text = "$md  $wk"
    }

    private fun refreshHint() {
        if (Settings.canDrawOverlays(this)) {
            hint.visibility = View.GONE
        } else {
            hint.visibility = View.VISIBLE
            hint.text = "点这里打开「显示在其他应用上层」，只需一次"
        }
    }

    private fun batteryPercent(): Int = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (t: Throwable) {
        -1
    }

    private fun showFatal(t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val tv = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "启动失败。请把这一屏截图发给我：\n\n" + sw.toString()
            setPadding(40, 120, 40, 40)
        }
        setContentView(tv)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 无障碍（滑动开关）是否已打开——关掉它，抖音里的上一个/下一个就按不动 */
    private fun a11yEnabled(): Boolean {
        return try {
            val mgr = getSystemService(AccessibilityManager::class.java)
            mgr?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo?.serviceInfo?.packageName == packageName } == true
        } catch (e: Throwable) {
            false
        }
    }

    private fun checkAccessibility() {
        if (a11yEnabled()) {
            a11yTip?.let { v -> runCatching { root.removeView(v) } }
            a11yTip = null
            return
        }
        if (a11yTip != null) return
        val bar = TextView(this).apply {
            text = "抖音滑动没打开 · 点这里打开"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(16), dp(10), dp(16))
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this@MainActivity, "找到「大视频控制」，打开它", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(224)
                leftMargin = dp(16)
                rightMargin = dp(16)
            }
        )
        a11yTip = bar
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shade(c: Int, f: Float): Int = Color.argb(
        Color.alpha(c),
        (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255),
        (Color.blue(c) * f).toInt().coerceIn(0, 255)
    )

    companion object {
        private val BG = Color.parseColor("#0B0B0B")
        private val YELLOW = Color.parseColor("#FFC300")
    }
}

/** 大电池图标：<20% 红，<40% 黄，其余绿 */
class BatteryView(ctx: Context) : View(ctx) {

    var percent: Int = 100
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f * d, 0f, 2f * d, Color.BLACK)
    }

    private fun levelColor(p: Int): Int = when {
        p < 0 -> Color.parseColor("#9E9E9E")
        p < 20 -> Color.parseColor("#FF3B30")
        p < 40 -> Color.parseColor("#FFC300")
        else -> Color.parseColor("#35C759")
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val c = levelColor(percent)
        val capW = h * 0.14f
        val body = RectF(d * 3f, d * 5f, w - capW - d * 3f, h - d * 5f)

        shellPaint.strokeWidth = 4f * d
        shellPaint.color = c
        canvas.drawRoundRect(body, 10f * d, 10f * d, shellPaint)

        fillPaint.color = c
        canvas.drawRoundRect(
            RectF(body.right + d, h * 0.36f, w - d, h * 0.64f),
            4f * d, 4f * d, fillPaint
        )

        val inset = 7f * d
        val ratio = percent.coerceIn(0, 100) / 100f
        val inner = RectF(
            body.left + inset, body.top + inset,
            body.left + inset + (body.width() - inset * 2f) * ratio,
            body.bottom - inset
        )
        if (inner.width() > 1f) canvas.drawRoundRect(inner, 6f * d, 6f * d, fillPaint)

        txtPaint.textSize = 22f * d
        val label = if (percent < 0) "?" else "$percent%"
        canvas.drawText(label, body.centerX(), body.centerY() + 8f * d, txtPaint)
    }
}
