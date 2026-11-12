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

        fun foregroundPackage(): String = fgPkg

        fun isTiktokForeground(): Boolean =
            TIKTOK_PACKAGES.any { fgPkg.startsWith(it) }

        /** 浮层只该在抖音出现，别的界面一律关掉（含老人桌面） */
        private fun syncOverlay() {
            try {
                if (OverlayService.forceHidden) {
                    OverlayService.setVisible(false)
                    return
                }
                OverlayService.setVisible(isTiktokForeground())
            } catch (e: Throwable) {
                Log.e(TAG, "sync overlay failed", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenW = 0
    private var screenH = 0

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
        } catch (e: Throwable) {
            Log.e(TAG, "set capabilities failed", e)
        }

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        Log.i(TAG, "connected ${screenW}x${screenH}")

        // 无障碍一旦可用，就把浮层拉起来
        try {
            OverlayService.start(this)
        } catch (e: Throwable) {
            Log.e(TAG, "start overlay failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty()) {
                fgPkg = pkg
                // 浮层跟着抖音走：抖音在前台才出现，回到任何别的界面立刻收掉。
                // 这样"回到老人桌面后屏蔽层还挡着按钮点不动"的问题不会再出现。
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
    fun swipe(up: Boolean, onDone: (() -> Unit)? = null): Boolean {
        if (screenH <= 0) {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
        if (screenW <= 0 || screenH <= 0) {
            onDone?.invoke()
            return false
        }

        val x = screenW / 2f
        // 起止点放在画面中部，避开顶部信息条和底部按钮区
        val startY = if (up) screenH * 0.80f else screenH * 0.26f
        val endY = if (up) screenH * 0.20f else screenH * 0.86f

        val path = Path()
        path.moveTo(x, startY)
        path.lineTo(x, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
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
