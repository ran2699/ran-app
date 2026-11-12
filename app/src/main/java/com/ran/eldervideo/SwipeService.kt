package com.ran.eldervideo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
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

        /** 直接问无障碍服务：系统认为现在最上层的那个窗口属于谁 */
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
                val actual = realActivePackage()
                val want: Boolean
                if (actual.isNotEmpty()) {
                    if (actual == self || actual.startsWith("$self.")) {
                        Log.i(TAG, "sync overlay 实际窗口是自家桌面 actual=$actual，隐藏")
                        OverlayService.setVisible(false)
                        return
                    }
                    want = TIKTOK_PACKAGES.any { actual.startsWith(it) }
                } else {
                    // 拿不到活动窗口时退回事件包名
                    if (fgPkg == self || fgPkg.startsWith("$self.")) {
                        Log.i(TAG, "sync overlay 事件包名是自家桌面 fg=$fgPkg，隐藏")
                        OverlayService.setVisible(false)
                        return
                    }
                    want = isTiktokForeground()
                }
                // 服务被系统回收过，实例没了就没人能开浮层——这里补一刀
                if (want && !OverlayService.alive()) {
                    instance?.let { OverlayService.start(it) }
                }
                Log.i(TAG, "sync overlay fg=$fgPkg actual=$actual want=$want")
                OverlayService.setVisible(want)
            } catch (e: Throwable) {
                Log.e(TAG, "sync overlay failed", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenW = 0
    private var screenH = 0

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
            if (pkg.isNotEmpty()) {
                // 系统层弹窗（权限框、状态栏、系统壳）不代表真的离开了抖音，跳过不记
                val noise = NOISE_PKGS.any { pkg == it || pkg.startsWith("$it.") }
                if (!noise) fgPkg = pkg
                // 浮层跟着抖音走：抖音在前台才出现，回到任何别的界面收掉。
                syncOverlay()
            }
            if (TIKTOK_PACKAGES.any { pkg.contains(it) }) {
                handler.postDelayed({ trySkipSplashAd() }, 1200)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        try {
            OverlayService.stop(this)
        } catch (e: Throwable) {}
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
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
        // 起点和终点都落在中间视频区：0.80 ↔ 0.20，避开顶栏和底栏
        val startY = if (up) screenH * 0.80f else screenH * 0.20f
        val kneeY = if (up) screenH * 0.56f else screenH * 0.44f
        val endY = if (up) screenH * 0.20f else screenH * 0.80f

        // 两段式：前半段慢、后半段快。
        // 抖音的上下翻页看的是"手指抬起那一瞬的速度"（ViewPager2 的 fling 判定），
        // 匀速划过去的注入手势它不认——1.0.6/1.0.9 实测都是 dispatchGesture=true、
        // gesture completed 齐全，画面一动不动。末端速度拉到 7000px/s 才翻得动。
        val bend = screenW * 0.015f
        val path1 = Path().apply {
            moveTo(x, startY)
            cubicTo(x + bend, startY - (startY - kneeY) * 0.5f, x - bend, kneeY + (startY - kneeY) * 0.2f, x, kneeY)
        }
        val path2 = Path().apply {
            moveTo(x, kneeY)
            lineTo(x, endY)
        }
        val s1 = GestureDescription.StrokeDescription(path1, 0L, 150L)
        val stroke = s1.continueStroke(path2, 0L, 120L, false)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return try {
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
