package com.ran.eldervideo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class SwipeService : AccessibilityService() {

    private var lastSwipeAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OverlayService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = ev.packageName?.toString() ?: return
        when {
            pkg in TIKTOK_PACKAGES -> OverlayService.showOverlay()
            // 自己的界面、系统音量条/通知栏，都不要收起遮罩
            pkg == packageName -> Unit
            pkg == "com.android.systemui" -> Unit
            else -> OverlayService.hideOverlay()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        OverlayService.hideOverlay()
        super.onDestroy()
    }

    fun next() = swipe(0.72f, 0.30f)
    fun prev() = swipe(0.30f, 0.72f)

    private fun swipe(fromYFrac: Float, toYFrac: Float, retried: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!retried && now - lastSwipeAt < 400L) return
        lastSwipeAt = now

        // 关键：手势注入会落在遮罩层上，先让遮罩暂时不可触摸（触摸穿透到抖音）
        OverlayService.setTouchable(false)
        handler.postDelayed({ OverlayService.setTouchable(true) }, 350L)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)

        val x = dm.widthPixels * 0.45f
        val h = dm.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(x, h * fromYFrac)
            lineTo(x, h * toYFrac)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 180L))
            .build()

        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (!retried) {
                    handler.postDelayed({ swipe(fromYFrac, toYFrac, true) }, 450L)
                }
            }
        }, null)
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    companion object {
        val TIKTOK_PACKAGES = setOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite"
        )

        @Volatile
        var instance: SwipeService? = null
            private set
    }
}
