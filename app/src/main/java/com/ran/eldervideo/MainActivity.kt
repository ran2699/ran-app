package com.ran.eldervideo

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
    private lateinit var backBtn: View
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var battery: BatteryView
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var torchOn = false

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
        try {
            OverlayService.setVisible(false)
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
            FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(22)
                bottomMargin = dp(22)
            }
        )

        setContentView(root)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 78f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
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

        col.addView(buildGrid(), LinearLayout.LayoutParams(-1, 0, 1f))

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
            labelSizeSp = 34f
            depthDp = 16f
            setOnPressListener { confirmOff() }
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
            toast(if (torchOn) "手电筒已开" else "手电筒已关")
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

    private fun confirmOff() {
        val layer = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#F2000000")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(60), dp(30), dp(60))
        }
        val ask = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            text = "要关掉手机吗？"
        }
        val yes = MechanicalButton(this).apply {
            labelText = "关掉"
            faceColor = Color.parseColor("#D3261B")
            faceColorDark = Color.parseColor("#6E0A05")
            labelSizeSp = 40f
            setOnPressListener {
                root.removeView(layer)
                blackOut()
            }
        }
        val no = MechanicalButton(this).apply {
            labelText = "还用呢"
            faceColor = Color.parseColor("#2E7D32")
            faceColorDark = Color.parseColor("#14401A")
            labelSizeSp = 40f
            setOnPressListener { root.removeView(layer) }
        }
        col.addView(ask, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(50) })
        col.addView(yes, LinearLayout.LayoutParams(-1, dp(150)).apply { bottomMargin = dp(24) })
        col.addView(no, LinearLayout.LayoutParams(-1, dp(150)))
        layer.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun blackOut() {
        val black = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val tip = TextView(this).apply {
            setTextColor(Color.parseColor("#3A3F46"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            text = "长按屏幕 3 秒可以打开"
        }
        black.addView(tip, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(70)
        })
        black.isClickable = true
        black.setOnLongClickListener {
            root.removeView(black)
            true
        }
        root.addView(black, FrameLayout.LayoutParams(-1, -1))
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
