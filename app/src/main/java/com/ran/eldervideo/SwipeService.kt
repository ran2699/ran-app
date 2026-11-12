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

        @Volatile
        var instance: SwipeService? = null

        fun alive(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var screenW = 0
    private var screenH = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // 关键修复：动态补齐手势能力，不再依赖 XML 是否被正确解析
        try {
            serviceInfo?.let { info ->
                var caps = info.capabilities
                caps = caps or AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
                info.capabilities = caps
                setServiceInfo(info)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "set capabilities failed", e)
        }

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        Log.i(TAG, "connected ${screenW}x${screenH} caps=${serviceInfo?.capabilities}")

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
            if (pkg.contains("aweme")) {
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
        val startY = if (up) screenH * 0.70f else screenH * 0.34f
        val endY = if (up) screenH * 0.30f else screenH * 0.74f

        val path = Path()
        path.moveTo(x, startY)
        path.lineTo(x, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0L, 240L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    onDone?.invoke()
                }

                override fun onCancelled(g: GestureDescription?) {
                    onDone?.invoke()
                }
            }, handler)
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
                            Log.i(TAG, "clicked ad node: ${t.ifEmpty { d }}")
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
