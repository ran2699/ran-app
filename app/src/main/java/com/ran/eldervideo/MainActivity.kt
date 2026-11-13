package com.ran.eldervideo

import android.util.Log
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
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
    /** 自愈尝试次数：HyperOS 清后台会把无障碍开关整条删掉，进桌面时自己写回 */
    private var a11yHealTries = 0
    private lateinit var datePage: View
    /** 底栏「关掉手机」键：天气页是后加的整屏 view，会盖住它，得把它抬回最上层 */
    private lateinit var offBtn: View
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

    /** 息屏页（AOD）相关 */
    private var aodView: FrameLayout? = null
    private var aodClock: TextView? = null
    private var aodDate: TextView? = null
    private var aodWakeBtn: MechanicalButton? = null
    // 1.0.34：充电显示并进黑屏页——竖向大电池 + 文字，充电时时间不显示
    private var aodClockCol: LinearLayout? = null
    private var aodChargeCol: LinearLayout? = null
    private var aodBattery: ChargeBatteryView? = null
    private var aodChargeTitle: TextView? = null
    private var aodChargeSub: TextView? = null
    private var aodChargingShown = false
    private var lastCharging = false
    // 剩余时间自估：记最近 16 分钟的 (时间,电量) 采样
    private val chargeSamples = ArrayList<Pair<Long, Int>>()
    private val aodTicker = object : Runnable {
        override fun run() {
            paintAod()
            handler.postDelayed(this, 10_000L)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshClock()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildUi()
        } catch (t: Throwable) {
            showFatal(t)
        }
        // 启动时就插着电不算"刚插上"，别一打开就弹充电锁屏
        lastCharging = try { isChargingNow() } catch (t: Throwable) { false }
        battery.charging = lastCharging
        handler.post(ticker)
        ensureCallPermission()
        ensureDefaultHome()
        Haptics.attach(window.decorView)
        // 桌面活着的时候插电，前台直接弹充电页（不依赖广播接收器的后台启动权限）
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: Intent?) {
                    Log.i("ElderUI", "power receiver action=${i?.action}")
                    when (i?.action) {
                        Intent.ACTION_POWER_CONNECTED -> {
                            // 插电：自动进黑屏页（充电锁屏形态），点一下回桌面
                            battery.charging = true
                            if (aodView == null) blackOut() else applyAodMode()
                        }
                        Intent.ACTION_POWER_DISCONNECTED -> {
                            // 上一版这里没分支：拔线事件收到就扔掉，充电状态没人清
                            battery.charging = false
                            applyAodMode()
                        }
                    }
                }
            }
            registerReceiver(r, f)
            chargeReceiver = r
        } catch (t: Throwable) {
        }
    }

    private var chargeReceiver: android.content.BroadcastReceiver? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            // --es 走 mExtras、--ez/putExtra 走 individual extras，两种传法都认
            val flag = (intent?.getBooleanExtra("show_charge", false) == true) ||
                    (intent?.extras?.getString("show_charge") == "true")
            if (flag && isChargingNow() && aodView == null) {
                blackOut()
            }
        } catch (t: Throwable) {
        }
    }

    override fun onDestroy() {
        try {
            chargeReceiver?.let { unregisterReceiver(it) }
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    /** 打开软件就检查自己是不是默认桌面；不是就把系统设置页顶出来让人确认一次。
     *  （正常情况这台机器已经是默认，走不到这个分支。） */
    private fun ensureDefaultHome() {
        try {
            val who = packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            if (who?.activityInfo?.packageName == packageName) return
            toast("请点一下「大视频」，再点「始终」")
            startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
        } catch (t: Throwable) {
        }
    }

    /** 拨号器角色换来的电话权限在 HyperOS 上不会真正落地，只能自己申请一次。
     *  放在开机这一下申请，老人点「确定打电话」时才不会被对话框打断。 */
    private fun ensureCallPermission() {
        try {
            val sp = getSharedPreferences("elder", MODE_PRIVATE)
            val granted = checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) return
            if (sp.getBoolean("asked_call_perm", false)) return
            sp.edit().putBoolean("asked_call_perm", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), 2001)
        } catch (t: Throwable) {
            Log.w("ElderUI", "申请电话权限失败：" + t.message)
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibility()
        // 无障碍服务由系统异步绑定：页面刚起来那一两秒 instance 还是 null，一次性判断会把
        // 红条钉在桌面上（实测红条一直盖着「抖音」键，老人点不到）。这里补几次重查，连上就撤。
        handler.postDelayed({ checkAccessibility() }, 1500L)
        handler.postDelayed({ checkAccessibility() }, 4000L)
        handler.postDelayed({ checkAccessibility() }, 8000L)
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
            if (aodView != null) applyAodMode()
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
        // 底栏规格（全 App 统一，抖音浮层同一套）：16 边距 + 144 键高 + 16 边距 = 176dp
        root.addView(
            backBtn,
            FrameLayout.LayoutParams(dp(144), dp(144), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(16)
                bottomMargin = dp(16)
            }
        )

        // 常驻：红球占右下角，关机键站在它左边——日历、天气页都跟着显示
        val offBar = MechanicalButton(this).apply {
            labelText = "关掉手机"
            faceColor = Color.parseColor("#D3261B")
            faceColorDark = Color.parseColor("#6E0A05")
            labelSizeSp = 34f
            depthDp = 18f
            setOnPressListener { blackOut() }
        }
        root.addView(
            offBar,
            FrameLayout.LayoutParams(-1, dp(144), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(16)
                rightMargin = dp(16 + 144 + 14)
                bottomMargin = dp(16)
            }
        )
        offBtn = offBar

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

        // 底部两枚常驻键是绝对定位，整条底栏占 176dp，网格再留 16dp 缝 → 192dp。
        // 少于这个数第三行会被压住（老包实测「手电筒」被关机键盖掉一半）。
        col.addView(buildGrid(), LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(192) })

        hint = TextView(this).apply {
            setTextColor(Color.parseColor("#111111"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            setBackgroundColor(YELLOW)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setOnClickListener { openOverlaySettings() }
        }
        col.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })


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
        // 天气页是后 addView 的，默认压在两枚底栏键之上（1.0.7 实测「关掉手机」被整块盖掉）
        root.bringChildToFront(backBtn)
        root.bringChildToFront(offBtn)
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
            // 充电形态下点屏幕任意处即回桌面（老人要的就是这个）
            setOnClickListener { if (aodChargingShown) wakeUp() }
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
        aodClockCol = col

        // —— 充电形态：竖向大电池 + 文字（1.0.34，按用户要求并进黑屏页）——
        aodBattery = ChargeBatteryView(this)
        aodChargeTitle = TextView(this).apply {
            text = "正在充电中…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        aodChargeSub = TextView(this).apply {
            text = "正在测算时间…"
            setTextColor(Color.parseColor("#FFD54F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            gravity = Gravity.CENTER
        }
        val ccol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        ccol.addView(aodBattery, LinearLayout.LayoutParams(dp(210), dp(360)).apply { bottomMargin = dp(30) })
        ccol.addView(aodChargeTitle, LinearLayout.LayoutParams(-1, -2))
        ccol.addView(aodChargeSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        ccol.addView(
            TextView(this).apply {
                text = "点一下屏幕，就能用手机"
                setTextColor(Color.parseColor("#5A626C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) }
        )
        black.addView(ccol, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        aodChargeCol = ccol

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
        aodWakeBtn = wake

        aodView = black
        root.addView(black, FrameLayout.LayoutParams(-1, -1))
        try {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (t: Throwable) {
        }
        paintAod()
        applyAodMode()
        handler.post(aodTicker)
    }

    /** 黑屏页双形态：充电=竖电池+文字（时间不显示）；不充电=原来的时间+绿键 */
    private fun applyAodMode() {
        val v = aodView ?: return
        val charging = try { isChargingNow() } catch (t: Throwable) { false }
        aodChargingShown = charging
        val vis = if (charging) View.GONE else View.VISIBLE
        aodClockCol?.visibility = vis
        aodWakeBtn?.visibility = vis
        aodChargeCol?.visibility = if (charging) View.VISIBLE else View.GONE
        if (charging) {
            val p = batteryPercent()
            aodBattery?.let { it.percent = p; it.invalidate() }
            aodChargeTitle?.text = if (p >= 100) "已经充满了" else "正在充电中…"
            aodChargeSub?.text = if (p >= 100) "可以拔下来用了" else "还有 ${estimateChargeMinutes(p)} 分钟充满"
        }
    }

    /** 剩余时间自估：16 分钟窗口里最早的采样和现在比，算涨电速度 */
    private fun estimateChargeMinutes(p: Int): Int {
        return try {
            val now = System.currentTimeMillis()
            chargeSamples.removeAll { now - it.first > 16 * 60_000L }
            chargeSamples.add(now to p)
            val first = chargeSamples.first()
            val mins = (now - first.first) / 60_000.0
            if (mins < 1.5) return 999
            val gain = p - first.second
            if (gain <= 0) return 999
            val perMin = gain / mins
            ((100 - p) / perMin).toInt().coerceIn(1, 999)
        } catch (t: Throwable) {
            999
        }
    }

    private fun paintAod() {
        val c = aodClock ?: return
        val now = Date()
        c.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        aodDate?.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
        // 黑屏页开着时拔/插线没有广播也保证 10 秒内变回来
        if (aodChargingShown || runCatching { isChargingNow() }.getOrDefault(false)) {
            applyAodMode()
        }
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
        aodClockCol = null
        aodChargeCol = null
        aodBattery = null
        aodChargeTitle = null
        aodChargeSub = null
        aodWakeBtn = null
        aodChargingShown = false
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
        val charging = isChargingNow()
        // 充电状态跳变兜底：HyperOS 可能不发 POWER_CONNECTED/DISCONNECTED
        // （1.0.35 实测：reset 造出真实插线事件后动态接收器毫无日志），
        // 但 BATTERY_CHANGED 一定发——5 秒一跳也能接上。
        if (charging != lastCharging) {
            Log.i("ElderUI", "charging transition $lastCharging -> $charging")
            lastCharging = charging
            battery.charging = charging
            if (charging) {
                if (aodView == null) blackOut() else applyAodMode()
            } else {
                applyAodMode()
            }
        } else {
            battery.charging = charging
        }
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

    /** 充电中吗：读粘性广播，status=充电或已充满都算插着电 */
    private fun isChargingNow(): Boolean {
        return try {
            val it = registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (it == null) false else {
                val st = it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        st == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (t: Throwable) {
            false
        }
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
        // 一路不够：小米上 AccessibilityManager 返回空列表、Settings 表有时读不到，
        // 单看任何一路都会误判成"没打开"，桌面底部那条红条就永远消不掉（实测 1.0.2 仍是）。
        // 现在三路取并集，任一成立即算开。
        val alive = try {
            SwipeService.alive()
        } catch (t: Throwable) {
            false
        }
        val sec = try {
            val on = Settings.Secure.getInt(
                contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
            ) == 1
            val list = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            on && list.split(':').any {
                val s = it.trim()
                s.startsWith("$packageName/") || s.startsWith("$packageName.")
            }
        } catch (t: Throwable) {
            false
        }
        val mgrOn = try {
            // 用 serviceInfo.id（形如 包名/.类名）比对，别用 resolveInfo——它经常是 null
            getSystemService(AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.id.startsWith("$packageName/") } == true
        } catch (t: Throwable) {
            false
        }
        Log.i("ElderUI", "a11y alive=$alive sec=$sec mgr=$mgrOn")
        return alive || sec || mgrOn
    }

    /**
     * 用自己的权限把无障碍开关写回系统设置。
     * 实测：HyperOS 清理后台后 accessibility_enabled=0、enabled_accessibility_services=null，
     * 开关是被整条删掉的，不是服务实例掉了——所以每次都"要重新授权"。
     * 装完包用 `adb shell pm grant com.ran.eldervideo android.permission.WRITE_SECURE_SETTINGS`
     * 授一次即可（永久生效、重启不丢），之后老人不用再点任何东西。
     */
    private fun selfHealA11y(): Boolean {
        val svcId = "$packageName/com.ran.eldervideo.SwipeService"
        return try {
            val cur = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val others = cur.split(':').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("$packageName/") }
            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (others + svcId).joinToString(":")
            )
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            val now = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val ok = now.contains("SwipeService")
            Log.i("ElderUI", "selfHeal a11y ok=$ok list=$now")
            ok
        } catch (t: Throwable) {
            Log.w("ElderUI", "selfHeal a11y failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun checkAccessibility() {
        if (a11yEnabled()) {
            a11yHealTries = 0
            a11yTip?.let { v -> runCatching { root.removeView(v) } }
            a11yTip = null
            return
        }
        // 开关被清掉时先自己写回去，等系统把服务绑上来再判；写不进去才亮红条
        if (a11yHealTries < 3) {
            a11yHealTries++
            if (selfHealA11y()) {
                handler.postDelayed({ checkAccessibility() }, 1200L)
                return
            }
        }
        if (a11yTip != null) return
        val bar = TextView(this).apply {
            text = "抖音滑动没打开 · 点这里打开"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    Toast.makeText(this@MainActivity, "找到「大视频控制」，打开它", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(
            bar,
            // 放顶部、不放到下方：原来压在两枚底部键和第三行键上，
            // 无障碍没开时正好把「手电筒／空」盖死，老人根本点不到。
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(196)
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

/**
 * 竖向大电池（充电锁屏页用，1.0.34）：
 * 绿色液面从底部涨到电量高度、表面一道缓波、液里有气泡上浮、
 * 中央一颗白色闪电轻轻呼吸。百分比写在液面下方的电池里。
 */
class ChargeBatteryView(ctx: Context) : View(ctx) {

    private val d = resources.displayMetrics.density
    var percent: Int = 50
        set(v) {
            field = v.coerceIn(0, 100)
            postInvalidateOnAnimation()
        }

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 235, 245, 255)
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 235, 245, 255)
    }
    private val liquidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boltEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Color.argb(120, 0, 0, 0)
    }
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f * d, 0f, 2f * d, Color.argb(160, 0, 0, 0))
    }

    // 气泡
    private class Bubble(val fx: Float, val speed: Float, val size: Float, var phase: Float)
    private val bubbles = ArrayList<Bubble>().apply {
        add(Bubble(0.28f, 0.00035f, 7f, 0.05f))
        add(Bubble(0.55f, 0.00028f, 5f, 0.42f))
        add(Bubble(0.74f, 0.00040f, 6f, 0.71f))
        add(Bubble(0.42f, 0.00032f, 4f, 0.88f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = System.currentTimeMillis()

        val capW = w * 0.34f
        val capH = h * 0.035f
        val body = RectF(w * 0.10f, capH + h * 0.02f, w * 0.90f, h * 0.985f)

        // 壳
        shellPaint.strokeWidth = 4.5f * d
        canvas.drawRoundRect(body, 20f * d, 20f * d, shellPaint)
        // 顶部正极帽
        canvas.drawRoundRect(
            RectF(body.centerX() - capW / 2f, h * 0.008f, body.centerX() + capW / 2f, capH + h * 0.012f),
            7f * d, 7f * d, capPaint
        )

        // 液面：底部涨到 percent，表面一道缓波
        val inset = 7f * d
        val innerLeft = body.left + inset
        val innerRight = body.right - inset
        val innerBottom = body.bottom - inset
        val innerTop = body.top + inset
        val waveAmp = 4f * d
        val level = innerBottom - (innerBottom - innerTop) * (percent / 100f)

        canvas.save()
        val clip = android.graphics.Path().apply {
            addRoundRect(
                RectF(innerLeft, innerTop, innerRight, innerBottom),
                14f * d, 14f * d, android.graphics.Path.Direction.CCW
            )
        }
        canvas.clipPath(clip)

        val liquid = android.graphics.Path().apply {
            moveTo(innerLeft, innerBottom)
            lineTo(innerLeft, level)
            var x = innerLeft
            val span = innerRight - innerLeft
            val k = 2f * Math.PI / span
            while (x < innerRight) {
                x += 8f * d
                val y = level + waveAmp * Math.sin(k * x + t / 620.0)
                lineTo(minOf(x, innerRight), y.toFloat())
            }
            lineTo(innerRight, innerBottom)
            close()
        }
        liquidPaint.shader = android.graphics.LinearGradient(
            0f, level, 0f, innerBottom,
            Color.parseColor("#4ADE80"), Color.parseColor("#15803D"),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawPath(liquid, liquidPaint)
        liquidPaint.shader = null

        // 液面高光线
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }
        hl.strokeWidth = 2f * d
        hl.style = Paint.Style.STROKE
        val hlPath = android.graphics.Path()
        var hx = innerLeft
        val hspan = innerRight - innerLeft
        val hk = 2f * Math.PI / hspan
        hlPath.moveTo(hx, (level + waveAmp * Math.sin(t / 620.0)).toFloat())
        while (hx < innerRight) {
            hx += 8f * d
            hlPath.lineTo(minOf(hx, innerRight), (level + waveAmp * Math.sin(hk * hx + t / 620.0)).toFloat())
        }
        canvas.drawPath(hlPath, hl)

        // 气泡：只在液面以下上浮
        for (b in bubbles) {
            val ph = ((b.phase + t * b.speed) % 1.0f)
            val y = innerBottom - ph * (innerBottom - level).coerceAtLeast(1f)
            val wob = 3f * d * Math.sin(ph * 6.0 + b.fx * 9.0).toFloat()
            glowPaint.color = Color.argb(((1f - ph) * 110f).toInt(), 255, 255, 255)
            canvas.drawCircle(innerLeft + b.fx * (innerRight - innerLeft) + wob, y, b.size * d, glowPaint)
        }

        // 中央闪电：白色 + 深描边 + 呼吸；压在液面中央最显眼
        val s = minOf(w * 0.30f, (innerBottom - innerTop) * 0.22f)
        val cx = body.centerX()
        val cy = body.centerY() - h * 0.02f
        val bolt = android.graphics.Path().apply {
            moveTo(cx + s * 0.12f, cy - s * 0.62f)
            lineTo(cx - s * 0.34f, cy + s * 0.10f)
            lineTo(cx - s * 0.04f, cy + s * 0.10f)
            lineTo(cx - s * 0.14f, cy + s * 0.62f)
            lineTo(cx + s * 0.34f, cy - s * 0.10f)
            lineTo(cx + s * 0.04f, cy - s * 0.10f)
            close()
        }
        boltPaint.alpha = (215 + (40 * Math.sin(t / 380.0)).toInt()).coerceIn(150, 255)
        canvas.drawPath(bolt, boltEdge)
        canvas.drawPath(bolt, boltPaint)

        // 百分比：液面下方居中
        txtPaint.textSize = 30f * d
        canvas.drawText("$percent%", body.centerX(), innerBottom - 16f * d, txtPaint)

        canvas.restore()
        postInvalidateOnAnimation()
    }
}

/** 大电池图标：<20% 红，<40% 黄，其余绿 */
class BatteryView(ctx: Context) : View(ctx) {

    var percent: Int = 100
        set(v) {
            field = v
            invalidate()
        }

    /** 充电中：电池中央画一颗呼吸的黄色闪电，并逐帧刷新 */
    var charging: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
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

        // 布局：数字靠左、电池壳靠右。上一版闪电直接压在数字上，两层字叠着看，丑且读不清。
        val capW = h * 0.10f
        val bodyW = h * 1.55f
        val body = RectF(w - bodyW - capW - d * 2f, h * 0.16f, w - capW - d * 2f, h * 0.84f)

        // 充电时壳色轻微呼吸，远看也知道在充电
        var shellAlpha = 255
        if (charging) {
            val t = System.currentTimeMillis()
            shellAlpha = (200 + (55 * kotlin.math.sin(t / 320.0)).toInt()).coerceIn(140, 255)
        }
        shellPaint.strokeWidth = 3.5f * d
        shellPaint.color = Color.argb(shellAlpha, Color.red(c), Color.green(c), Color.blue(c))
        canvas.drawRoundRect(body, 8f * d, 8f * d, shellPaint)

        // 正极小帽
        fillPaint.color = Color.argb(shellAlpha, Color.red(c), Color.green(c), Color.blue(c))
        canvas.drawRoundRect(
            RectF(body.right + d * 2f, body.centerY() - h * 0.13f, w - d, body.centerY() + h * 0.13f),
            3f * d, 3f * d, fillPaint
        )

        // 电量填充
        val inset = 5f * d
        val ratio = percent.coerceIn(0, 100) / 100f
        val inner = RectF(
            body.left + inset, body.top + inset,
            body.left + inset + (body.width() - inset * 2f) * ratio,
            body.bottom - inset
        )
        fillPaint.color = c
        if (inner.width() > 1f && percent >= 0) canvas.drawRoundRect(inner, 5f * d, 5f * d, fillPaint)

        // 数字：在电池壳左边，白字大号
        txtPaint.textSize = 30f * d
        val label = if (percent < 0) "?" else "$percent%"
        canvas.drawText(label, body.left - 12f * d, h / 2f + 11f * d, txtPaint)

        // 充电中：白色小闪电嵌在壳内右侧空白处（不压数字、不压填充），呼吸
        if (charging) {
            val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            boltPaint.color = Color.WHITE
            boltPaint.alpha = shellAlpha
            val s = h * 0.42f
            val cx = body.centerX()
            val cy = body.centerY()
            val p = android.graphics.Path()
            p.moveTo(cx + s * 0.10f, cy - s * 0.50f)
            p.lineTo(cx - s * 0.26f, cy + s * 0.08f)
            p.lineTo(cx - s * 0.02f, cy + s * 0.08f)
            p.lineTo(cx - s * 0.10f, cy + s * 0.50f)
            p.lineTo(cx + s * 0.26f, cy - s * 0.08f)
            p.lineTo(cx + s * 0.02f, cy - s * 0.08f)
            p.close()
            // 描一圈深色，保证压在绿/黄/红填充上都看得清
            val withStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.10f
                color = Color.argb(150, 0, 0, 0)
            }
            canvas.drawPath(p, withStroke)
            canvas.drawPath(p, boltPaint)
            postInvalidateOnAnimation()
        }
    }
}
