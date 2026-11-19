package com.ran.eldervideo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：只用它做两件事
 * 1. 向抖音注入上滑/下滑手势（代替老人手指）
 * 2. 尝试关闭开屏广告
 */
class SwipeService : AccessibilityService() {

    companion object {
        const val TAG = "ElderSwipe"

        /** MainActivity 判断当前前台是否是抖音用的 */
        val TIKTOK_PACKAGES = arrayOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live"
        )

        @Volatile
        var instance: SwipeService? = null

        fun alive(): Boolean = instance != null

        /** 当前前台包名，由无障碍事件持续更新 */
        @Volatile
        private var fgPkg: String = ""

        /**
         * 这些包名只是抖音启动时弹出来的系统层窗口（权限框、状态栏、系统壳），
         * 拿它们当下面前台会把遮罩误判成"已经离开抖音"，于是遮罩闪一下就没了。
         */
        private val NOISE_PKGS = arrayOf(
            "com.android.systemui",
            "android",
            "com.miui.securitycenter",
            "com.miui.permission",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.miui.aod"
        )

        fun foregroundPackage(): String = fgPkg

        fun isTiktokForeground(): Boolean =
            TIKTOK_PACKAGES.any { fgPkg.startsWith(it) }

        /** 上一次能确认的前台包名：拿不到窗口时用它兜底，不因为"读不到"就当作离开了 */
        @Volatile
        private var lastKnownFg: String = ""

        private var usageStats: android.app.usage.UsageStatsManager? = null

        private fun isNoise(pkg: String): Boolean =
            pkg.isEmpty() || NOISE_PKGS.any { pkg == it || pkg.startsWith("$it.") }

        /** 自家桌面 Resume 是最硬的前台信号：抖音的滞后事件不能把它顶掉 */
        fun markSelfForeground() {
            val self = instance?.packageName ?: "com.ran.eldervideo"
            fgPkg = self
            lastKnownFg = self
        }

        /**
         * 第二判据：UsageStats 最近恢复的 Activity。
         * 需要 GET_USAGE_STATS（adb appops set com.ran.eldervideo GET_USAGE_STATS allow，一次性）。
         * 抖音全屏播放时 AccessibilityService.windows 经常拿不到 active 窗口，
         * 这条能补上前台到底是谁；拿不到就返回空，不影响别处。
         */
        private fun usageFg(): String {
            return try {
                val inst = instance ?: return ""
                if (usageStats == null) {
                    usageStats = inst.getSystemService(Context.USAGE_STATS_SERVICE)
                        as? android.app.usage.UsageStatsManager
                }
                val us = usageStats ?: return ""
                val end = System.currentTimeMillis()
                val cursor = us.queryEvents(end - 20000L, end) ?: return ""
                val ev = android.app.usage.UsageEvents.Event()
                var best = ""
                var bestT = 0L
                while (cursor.hasNextEvent()) {
                    cursor.getNextEvent(ev)
                    if (ev.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED &&
                        ev.timeStamp >= bestT
                    ) {
                        bestT = ev.timeStamp
                        best = ev.packageName.orEmpty()
                    }
                }
                best
            } catch (t: Throwable) {
                ""
            }
        }

        /** 直接问无障碍服务：系统认为现在最上层的那个窗口属于谁 */
        /**
         * 守卫白名单（1.0.44）：这些前台包不算"跑了"。
         * 自家全部页面（桌面/通话/拨号/锁屏）、抖音（走桌面键或自己滑进来都允许）、
         * 系统壳与权限弹窗、设置页、小米负一屏/锁屏、输入法。
         */
        private val GUARD_ALLOWED = arrayOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live",
            "com.android.systemui",
            "android",
            "com.android.settings",
            "com.miui.securitycenter",
            "com.miui.permission",
            "com.google.android.permissioncontroller",
            "com.miui.personalassistant",
            "com.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou.xiaomi"
        )

        @Volatile
        private var guardPausedUntil = 0L

        /** 短时间内不守卫：退出流程、主动开设置页时用 */
        fun pauseGuard(seconds: Long) {
            guardPausedUntil = System.currentTimeMillis() + seconds * 1000L
            Log.i(TAG, "guard paused ${seconds}s")
        }

        private fun realActivePackage(): String {
            val inst = instance ?: return ""
            try {
                val list = inst.windows
                val w = if (list != null) list.firstOrNull { it.isActive } ?: list.firstOrNull() else null
                val p = w?.root?.packageName?.toString().orEmpty()
                if (p.isNotEmpty()) return p
            } catch (e: Throwable) {
            }
            // 兜底：小米上 windows 列表常常是空的（1.0.2 日志里 active= 一直是空），
            // 这时问"当前活动窗口"，据此才认得出抖音外面盖着的那层系统弹窗。
            return try {
                inst.rootInActiveWindow?.packageName?.toString().orEmpty()
            } catch (e: Throwable) {
                ""
            }
        }

        /** 浮层只该在抖音出现，别的界面一律关掉（含老人桌面） */
        private fun syncOverlay() {
            try {
                if (OverlayService.forceHidden) {
                    OverlayService.setVisible(false)
                    return
                }
                val self = instance?.packageName ?: "com.ran.eldervideo"
                // 真实活动窗口最可信。抖音是单 Activity 应用，切视频、进直播间都不一定发
                // TYPE_WINDOW_STATE_CHANGED，事件里的包名会一直停在离开前的那个应用上
                // （1.0.5 实测：人在抖音里，fg 还是自家桌面，浮层被"桌面在前台"这条规则误关掉，
                //  满屏原生抖音、广告也没挡住）。所以 actual 有值就只信 actual。
                // 三级取值（1.0.49）：真实活动窗口 → UsageStats 最近前台 → 上次已知前台。
                // 抖音全屏播放时 windows 常常给不出 active 窗口（actual 空），而
                // TYPE_WINDOW_STATE_CHANGED 也不随切换视频刷新，fgPkg 会一直停在进入前的应用上。
                // 原来两路一起失效就直接判「不在抖音」把浮层收掉——表现就是停留几秒后消失。
                // 现在只有拿到明确证据（自家桌面或别的应用）才隐藏，读不到就沿用上次已知前台。
                val actual = realActivePackage()
                val usg = if (isNoise(actual)) usageFg() else ""
                val eff = when {
                    !isNoise(actual) -> actual
                    !isNoise(usg) -> usg
                    actual.isNotEmpty() -> actual
                    else -> ""
                }
                if (!isNoise(eff)) lastKnownFg = eff
                val judge = when {
                    eff.isNotEmpty() -> eff
                    lastKnownFg.isNotEmpty() -> lastKnownFg
                    else -> fgPkg
                }
                if (judge == self || judge.startsWith("$self.")) {
                    Log.i(TAG, "sync overlay 前台是自家桌面 judge=$judge（actual=$actual usg=$usg），隐藏")
                    OverlayService.setVisible(false)
                    return
                }
                val want = TIKTOK_PACKAGES.any { judge.startsWith(it) }
                // 服务被系统回收过，实例没了就没人能开浮层——这里补一刀
                if (want && !OverlayService.alive()) {
                    instance?.let { OverlayService.start(it) }
                }
                Log.i(TAG, "sync overlay judge=$judge fg=$fgPkg actual=$actual usg=$usg last=$lastKnownFg want=$want")
                OverlayService.setVisible(want)
                // 事件包名可能滞留（抖音窗口抖动那类问题），守卫以判定出来的前台为准兜底。
                // syncOverlay 在 companion 里，实例方法要走 instance 调（#145 编译教训）
                instance?.guardCheck(judge)
            } catch (e: Throwable) {
                Log.e(TAG, "sync overlay failed", e)
            }
        }
    }

    // ---------------- 系统低电量弹窗守卫（1.0.50） ----------------
    /**
     * 澎湃OS 3 没有「关闭低电量提示」的开关：实测 设置→省电与电池→更多电池功能 整页只有五项，
     * secure/global/system 三张设置表也搜不到任何相关开关。它每跨过一次 20%、10%，就用
     * 安全中心的悬浮窗弹一次：
     *   window = com.miui.securitycenter/.FloatingWindow
     *   标题   = "电量已低于20%" / "电量已低于10%"
     *   按钮   = [知道了] [省电模式]，两个都是 android.widget.Button
     * 无障碍能读到完整节点树（capabilities 含 CAN_RETRIEVE_WINDOW_CONTENT，实测 dump 通过），
     * 点「知道了」实测能关掉，按返回键实测也能关掉。
     *
     * 为什么必须做：右边那个蓝色「省电模式」按钮对老人是个陷阱——误点一下就进省电模式，
     * 前台服务和无障碍会被系统限死，整套桌面等于瘫痪。自动点掉「知道了」顺手把这个坑堵上。
     *
     * 铁律：只按文本找「知道了」，绝不按坐标点——坐标一偏就点成「省电模式」。
     */
    private object LowBatt {
        const val SC_PKG = "com.miui.securitycenter"
        const val TITLE_KEY = "电量已低于"
        const val OK_TEXT = "知道了"
        const val SP_KEY = "lowbatt_log"
    }

    @Volatile
    private var lastLowBattAt = 0L
    @Volatile
    private var lastScDiagAt = 0L
    @Volatile
    private var lastBattDiagAt = 0L
    private var tickCount = 0

    /** 电量已进低电区且没在充电：只有这时候才值得去盯安全中心的弹窗 */
    private fun batteryLow(): Boolean {
        return try {
            val i = registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            ) ?: return false
            val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val pct = if (scale > 0) level * 100 / scale else level
            // 不排除「充电中」：实测手机插着 USB 时 status 恒为 CHARGING，
            // 若据此跳过，守门就整个失效（本机验证时踩到过）。低电区一律盯着。
            pct in 0..21
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 在所有窗口里找安全中心的根节点。
     * 悬浮窗不一定是 active window（实测它挂着时 active 仍可能是下层那个应用），
     * 所以 rootInActiveWindow 和 windows 两个来源都要问。
     */
    private fun scWindowRoot(): android.view.accessibility.AccessibilityNodeInfo? {
        try {
            val active = rootInActiveWindow
            if (active != null && active.packageName?.toString() == LowBatt.SC_PKG) return active
        } catch (t: Throwable) {
        }
        try {
            for (w in windows) {
                val r = try { w.root } catch (t: Throwable) { null } ?: continue
                if (r.packageName?.toString() == LowBatt.SC_PKG) return r
            }
        } catch (t: Throwable) {
        }
        return null
    }

    /** 递归找文本节点；exact=true 全等，false 包含 */
    private fun nodeWith(
        root: android.view.accessibility.AccessibilityNodeInfo?,
        text: String,
        exact: Boolean,
        depth: Int = 0
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (root == null || depth > 30) return null
        val t = root.text?.toString() ?: ""
        if (if (exact) t == text else t.contains(text)) return root
        for (i in 0 until root.childCount) {
            val c = try { root.getChild(i) } catch (e: Throwable) { null } ?: continue
            val hit = nodeWith(c, text, exact, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    /** 文本节点本身通常不可点，往上找第一个可点的祖先；找不到就退回原节点试一次 */
    private fun clickableAround(
        node: android.view.accessibility.AccessibilityNodeInfo?
    ): android.view.accessibility.AccessibilityNodeInfo? {
        var n = node
        var d = 0
        while (n != null && d < 6) {
            if (n.isClickable) return n
            n = try { n.parent } catch (t: Throwable) { null }
            d++
        }
        return node
    }

    /** 认准「电量已低于」才动手，别的安全中心弹窗一概不碰 */
    private fun dismissLowBatteryPopup() {
        val sp = getSharedPreferences("elder", MODE_PRIVATE)
        if (!sp.getBoolean("lowbatt_guard_on", true)) return
        val now = System.currentTimeMillis()
        if (now - lastLowBattAt < 1200L) return
        try {
            val root = scWindowRoot() ?: return
            val titleNode = nodeWith(root, LowBatt.TITLE_KEY, false)
            if (titleNode == null) {
                // 诊断：能读到安全中心的窗口但没有低电标题（限流 10 秒一条）。
                // 用来区分「弹窗没出现」和「出现了但无障碍读不到」。
                if (now - lastScDiagAt > 10000L) {
                    lastScDiagAt = now
                    Log.i(TAG, "sc window present, no low-battery title")
                }
                return
            }
            val title = titleNode.text?.toString().orEmpty()
            lastLowBattAt = now

            var how = "back"
            var done = false
            val btn = clickableAround(nodeWith(root, LowBatt.OK_TEXT, true))
            if (btn != null) {
                done = try {
                    btn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                } catch (t: Throwable) {
                    false
                }
                if (done) how = "click"
            }
            if (!done) performGlobalAction(GLOBAL_ACTION_BACK)

            Log.i(TAG, "low battery popup [$title] dismissed by $how")
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date())
            sp.edit().putString(LowBatt.SP_KEY, "$stamp $title → $how").apply()

            // 复核：500ms 后还在就再按一次返回键
            handler.postDelayed({
                try {
                    val again = scWindowRoot()
                    if (again != null && nodeWith(again, LowBatt.TITLE_KEY, false) != null) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        Log.i(TAG, "low battery popup still there, back again")
                    }
                } catch (t: Throwable) {
                }
            }, 500L)
        } catch (e: Throwable) {
            Log.e(TAG, "dismiss low battery failed", e)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenW = 0
    private var screenH = 0

    /**
     * 自动收拢守卫：老人误开别的软件（微信、浏览器、相机…）立刻弹回桌面。
     * 白名单见 GUARD_ALLOWED；退出老人模式会把 guard_on 关掉，
     * 主动进设置页等场景用 pauseGuard 临时放行；息屏时不动作。
     */
    private fun guardCheck(pkg: String) {
        if (pkg.isEmpty()) return
        val sp = getSharedPreferences("elder", MODE_PRIVATE)
        if (!sp.getBoolean("guard_on", true)) return
        if (System.currentTimeMillis() < guardPausedUntil) return
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm != null && !pm.isInteractive) return
        if (pkg == packageName || pkg.startsWith("$packageName.")) return
        if (GUARD_ALLOWED.any { pkg == it || pkg.startsWith("$it.") }) return
        Log.i(TAG, "guard kick $pkg back home")
        android.widget.Toast.makeText(this, "请从桌面大按钮选应用", android.widget.Toast.LENGTH_LONG).show()
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (t: Throwable) {}
    }

    /**
     * 定时核对前台。
     * 只靠 TYPE_WINDOW_STATE_CHANGED 事件不可靠：抖音内部的窗口抖动、系统弹窗、
     * 息屏唤醒都可能让最后一条事件停在"抖音"，于是退回桌面后浮层糊在老人桌面上不走
     * （实测就是这样：桌面上半截被黑色时间带压着，底部换成上一个／下一个）。
     * 每 1.5 秒问一次真实前台，不该出现就收掉，代价可以忽略。
     */
    private val syncTicker = object : Runnable {
        override fun run() {
            try {
                syncOverlay()
            } catch (e: Throwable) {
                Log.e(TAG, "sync ticker failed", e)
            }
            // 悬浮窗的事件可能不派发（实测 HYPEROS 的 FloatingWindow 不保证发 WINDOW_STATE_CHANGED），
            // 所以不靠事件：只要电量进了低电区，每跳（1.5 秒）都查一次，弹出来就点掉。
            try {
                tickCount++
                if (batteryLow()) {
                    // 诊断：确认电量门控真的读到了低电量（限流 10 秒一条，便于真机核对）
                    val nowBatt = System.currentTimeMillis()
                    if (nowBatt - lastBattDiagAt > 10000L) {
                        lastBattDiagAt = nowBatt
                        Log.i(TAG, "in low battery zone, polling for popup")
                    }
                    dismissLowBatteryPopup()
                }
            } catch (t: Throwable) {
            }
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 关键修复：动态补齐手势能力位，不依赖 XML 是否被系统正确解析。
        // SDK 里 setCapabilities 不可直接赋值，走反射写公开字段。
        try {
            serviceInfo?.let { info ->
                val field = AccessibilityServiceInfo::class.java.getField("capabilities")
                val current = field.getInt(info)
                val wanted = current or AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
                if (wanted != current) {
                    field.setInt(info, wanted)
                    setServiceInfo(info)
                }
                Log.i(TAG, "capabilities ${current} -> ${field.getInt(info)}")
            }
            // 要点到"当前真正在最上层的窗口"，才能识破抖音外面那层系统弹窗
            serviceInfo?.let { info ->
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                setServiceInfo(info)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "set capabilities failed", e)
        }

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = realHeight(dm)
        Log.i(TAG, "connected ${screenW}x${screenH}")

        // 无障碍一旦可用，就把浮层拉起来
        try {
            OverlayService.start(this)
        } catch (e: Throwable) {
            Log.e(TAG, "start overlay failed", e)
        }

        // 起来之后立刻核对一次，并开始定时核对
        handler.removeCallbacks(syncTicker)
        handler.post(syncTicker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            // 安全中心的低电量弹窗（1.0.50）：弹出来就点掉，别让它挡住老人
            if (pkg == LowBatt.SC_PKG) handler.postDelayed({ dismissLowBatteryPopup() }, 250L)
            if (pkg.isNotEmpty()) {
                // 系统层弹窗（权限框、状态栏、系统壳）不代表真的离开了抖音，跳过不记
                val noise = NOISE_PKGS.any { pkg == it || pkg.startsWith("$it.") }
                if (!noise) {
                    fgPkg = pkg
                    guardCheck(pkg)
                }
                // 浮层跟着抖音走：抖音在前台才出现，回到任何别的界面收掉。
                syncOverlay()
            }
            if (TIKTOK_PACKAGES.any { pkg.contains(it) }) {
                handler.postDelayed({ trySkipSplashAd() }, 1200)
            }
        }
    }

    override fun onInterrupt() {}

    // ---------------- 解锁 → 自绘锁屏（1.0.45） ----------------
    /**
     * HyperOS 的自启动管理会把 manifest 注册的 BootReceiver 挡在
     * USER_PRESENT 投递名单外（1.0.44 实测：广播发了、我们没收到）。
     * 无障碍服务进程常驻且不受自启动限制，改用动态注册接解锁广播。
     * 后台拉起 Activity 靠锚点窗口换 BAL 豁免（与 CallService 1.0.43 同一手法）。
     */
    private var anchorView: View? = null

    private val unlockWatcher = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            Log.i(TAG, "USER_PRESENT -> show lock page")
            ensureAnchor()
            try {
                startActivity(
                    Intent(ctx, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Log.w(TAG, "lock start failed: ${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    private fun ensureAnchor() {
        if (anchorView != null) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission, anchor skipped")
            return
        }
        try {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val v = View(this)
            wm.addView(
                v,
                WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply { x = -2000; y = -2000; alpha = 0f }
            )
            anchorView = v
            Log.i(TAG, "anchor overlay added")
        } catch (t: Throwable) {
            Log.w(TAG, "anchor failed: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun dropAnchor() {
        val v = anchorView ?: return
        anchorView = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            registerReceiver(unlockWatcher, IntentFilter(Intent.ACTION_USER_PRESENT))
            Log.i(TAG, "unlock watcher registered")
        } catch (t: Throwable) {
            Log.w(TAG, "watcher register failed: $t")
        }
        ensureAnchor()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        try {
            unregisterReceiver(unlockWatcher)
        } catch (_: Throwable) {}
        dropAnchor()
        try {
            OverlayService.stop(this)
        } catch (e: Throwable) {}
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        try {
            unregisterReceiver(unlockWatcher)
        } catch (_: Throwable) {}
        dropAnchor()
        super.onDestroy()
    }

    /**
     * 注入一次全屏竖向滑动。
     * @param up true=下一条（手指往上划），false=上一条
     * @return 是否成功派发
     */
    /**
     * 用无障碍 global action 直接发 HOME。
     * 后台 startActivity 在 Android 10+ 常被系统拦掉，global action 是无障碍的特权通道，一定生效。
     */
    /** 取真实屏高：可用高度比真机高度小一截，会让滑动坐标整体上偏 */
    private fun realHeight(dm: android.util.DisplayMetrics): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
                maxOf(b.height(), dm.heightPixels)
            } else {
                dm.heightPixels
            }
        } catch (e: Throwable) {
            dm.heightPixels
        }
    }

    fun goHomeNow(): Boolean = try {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.i(TAG, "globalAction HOME=$ok")
        ok
    } catch (e: Throwable) {
        Log.e(TAG, "globalAction HOME failed", e)
        false
    }

    fun swipe(up: Boolean, onDone: (() -> Unit)? = null): Boolean {
        if (screenH <= 0) {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels
            screenH = realHeight(dm)
        }
        if (screenW <= 0 || screenH <= 0) {
            onDone?.invoke()
            return false
        }

        val x = screenW / 2f
        // 起止点必须整段落在"抖音自己的"视频区里。
        // 1.0.13 之前用 0.78↔0.22：起点 y=0.78*3200=2496，而底栏顶边在 3200-268dp*3.5=2262，
        // 起点落在我们自己的底栏窗口里，注入事件被自家窗口吃掉（logcat 里 channel 是
        // "com.ran.eldervideo (server)"），抖音一个事件都没收到——所以画面永远不翻。
        // 现在收进 0.66 ↔ 0.18：底栏顶边 0.707、顶栏底边 0.094，两头都留出余量。
        val startY = if (up) screenH * 0.66f else screenH * 0.18f
        val endY = if (up) screenH * 0.18f else screenH * 0.66f

        // 整段构建都放在 try 里：这些手势对象一旦参数不合法就会抛异常，
        // 1.0.10 就是因为 continueStroke 的参数问题在 try 之外崩了整个进程，
        // 浮层还来不及挂回去，老人屏幕上直接裸奔。
        return try {
            // 单段、一次性甩到底，180ms 走完 0.66→0.18（末端约 8500px/s）。
            // 两段式 continueStroke 在真机上会被系统直接判 cancelled（1.0.11 实测），
            // 所以还是回到单段——这是唯一真实翻动过视频的形态，只是把速度再提一档。
            val path = Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 180L)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    Log.i(TAG, "gesture completed up=$up")
                    onDone?.invoke()
                }

                override fun onCancelled(g: GestureDescription?) {
                    Log.w(TAG, "gesture cancelled up=$up")
                    onDone?.invoke()
                }
            }, handler)
            Log.i(TAG, "dispatchGesture=$ok up=$up y=$startY->$endY screen=$screenW x $screenH")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "dispatchGesture exception", e)
            onDone?.invoke()
            false
        }
    }

    /** 粗暴但有效的开屏广告处理：找"跳过"字样的节点点掉 */
    private fun trySkipSplashAd() {
        try {
            val root = rootInActiveWindow ?: return
            val texts = arrayOf("跳过", "跳过广告", "关闭", "我知道了")
            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard < 400) {
                guard++
                val node = queue.removeFirst()
                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                for (k in texts) {
                    if (t.contains(k) || d.contains(k)) {
                        var clickable: android.view.accessibility.AccessibilityNodeInfo? = node
                        var depth = 0
                        while (clickable != null && !clickable.isClickable && depth < 4) {
                            clickable = clickable.parent
                            depth++
                        }
                        if (clickable != null && clickable.isClickable) {
                            clickable.performAction(
                                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                            )
                            Log.i(TAG, "clicked ad node")
                            return
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "skip ad failed", e)
        }
    }
}
