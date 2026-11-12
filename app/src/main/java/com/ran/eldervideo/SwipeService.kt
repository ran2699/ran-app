package com.ran.eldervideo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：只做两件事
 * 1. 监听前台应用，判断是不是抖音 -> 通知遮罩层显示/隐藏
 * 2. 执行"上一个/下一个"的滑动手势
 */
class SwipeService : AccessibilityService() {

    private var lastSwipeAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 无障碍一开，把遮罩服务拉起来
        OverlayService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = ev.packageName?.toString() ?: return
        when {
            pkg in TIKTOK_PACKAGES -> OverlayService.showOverlay()
            pkg != packageName -> OverlayService.hideOverlay()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        OverlayService.hideOverlay()
        super.onDestroy()
    }

    /** 下一个：手指从下往上滑（抖音的"下一条"） */
    fun next() = swipe(0.74f, 0.26f)

    /** 上一个：手指从上往下滑 */
    fun prev() = swipe(0.30f, 0.78f)

    private fun swipe(fromYFrac: Float, toYFrac: Float, retried: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!retried && now - lastSwipeAt < 500L) return
        lastSwipeAt = now

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)

        val x = dm.widthPixels * 0.5f
        val h = dm.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(x, h * fromYFrac)
            lineTo(x, h * toYFrac)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 200L))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {
                // 视频还在加载时偶尔会取消，重试一次
                if (!retried) swipe(fromYFrac, toYFrac, true)
            }
        }, null)
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    companion object {
        val TIKTOK_PACKAGES = setOf(
            "com.ss.android.ugc.aweme",        // 抖音
            "com.ss.android.ugc.aweme.lite"    // 抖音极速版
        )

        @Volatile
        var instance: SwipeService? = null
            private set
    }
}
