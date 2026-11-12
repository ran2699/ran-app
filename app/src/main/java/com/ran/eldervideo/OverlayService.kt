package com.ran.eldervideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 遮罩层服务。
 * 一块全屏透明窗口盖在抖音上面，周边涂黑（挡住图标），中间留空（视频透出来），
 * 所有触摸由这一层接住，抖音收不到任何点击。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private var visible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundInternal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        hide()
        instance = null
        super.onDestroy()
    }

    fun show() {
        if (visible) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            val v = overlay ?: buildOverlay().also { overlay = it }
            wm.addView(v, windowParams())
            visible = true
        } catch (t: Throwable) {
            visible = false
        }
    }

    fun hide() {
        if (!visible) return
        try {
            overlay?.let { wm.removeView(it) }
        } catch (t: Throwable) {
        }
        visible = false
    }

    private fun buildOverlay(): View {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val root = FrameLayout(this)

        val absorber = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { }
        }
        root.addView(absorber, FrameLayout.LayoutParams(-1, -1))

        fun block(wf: Float, hf: Float, xf: Float, yf: Float) {
            val v = View(this).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true
                setOnClickListener { }
            }
            root.addView(
                v,
                FrameLayout.LayoutParams((w * wf).toInt(), (h * hf).toInt()).apply {
                    leftMargin = (w * xf).toInt()
                    topMargin = (h * yf).toInt()
                }
            )
        }

        block(1f, 0.125f, 0f, 0f)          // 顶部
        block(1f, 0.24f, 0f, 0.76f)        // 底部
        block(0.23f, 0.55f, 0.77f, 0.29f)  // 右侧

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(actionButton("上一个", "#15569B") { doSwipe(prev = true) })
        bar.addView(actionButton("下一个", "#15569B") { doSwipe(prev = false) })
        bar.addView(actionButton("返回桌面", "#3A3A3A") { goHome() })

        root.addView(
            bar,
            FrameLayout.LayoutParams(-1, (h * 0.17f).toInt()).apply {
                topMargin = (h * 0.785f).toInt()
            }
        )

        return root
    }

    private fun actionButton(label: String, colorHex: String, onClick: () -> Unit): TextView {
        val textPx = resources.displayMetrics.widthPixels * 0.072f
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor(colorHex))
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
            }
        }
    }

    private fun doSwipe(prev: Boolean) {
        val svc = SwipeService.instance
        if (svc == null) {
            Toast.makeText(this, "辅助功能没开，请回设置页开启", Toast.LENGTH_SHORT).show()
            return
        }
        if (prev) svc.prev() else svc.next()
    }

    private fun goHome() {
        val svc = SwipeService.instance
        if (svc == null) {
            hide()
            return
        }
        svc.goHome()
    }

    private fun windowParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 0
        p.y = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun startForegroundInternal() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel("overlay", "遮罩服务", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val n = Notification.Builder(this, "overlay")
                .setContentTitle("大视频")
                .setContentText("正在为视频加遮罩")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
            startForeground(1001, n)
        } catch (t: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var instance: OverlayService? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            ctx.startForegroundService(i)
        }

        fun showOverlay() {
            instance?.show()
        }

        fun hideOverlay() {
            instance?.hide()
        }
    }
}
