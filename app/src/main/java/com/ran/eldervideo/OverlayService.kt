package com.ran.eldervideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private var curParams: WindowManager.LayoutParams? = null
    private var visible = false

    private var timeView: TextView? = null
    private var sideView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            timeView?.text = hhmm()
            sideView?.text = dateLine()
            handler.postDelayed(this, 30000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundInternal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        hide()
        instance = null
        super.onDestroy()
    }

    fun show() {
        if (visible) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            val v = overlay ?: buildOverlay().also { overlay = it }
            val p = windowParams()
            curParams = p
            wm.addView(v, p)
            visible = true
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } catch (t: Throwable) {
            visible = false
        }
    }

    fun hide() {
        if (!visible) return
        handler.removeCallbacks(ticker)
        try {
            overlay?.let { wm.removeView(it) }
        } catch (t: Throwable) {
        }
        visible = false
    }

    /** 手势注入期间临时让遮罩不接收触摸 */
    fun setTouchable(b: Boolean) {
        if (!visible) return
        val v = overlay ?: return
        val p = curParams ?: return
        try {
            p.flags = if (b) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            wm.updateViewLayout(v, p)
        } catch (t: Throwable) {
        }
    }

    // ---------------- 界面 ----------------

    private fun buildOverlay(): View {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val W = dm.widthPixels
        val H = dm.heightPixels

        val root = FrameLayout(this)

        // 吃掉所有落空的触摸
        val absorber = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { }
        }
        root.addView(absorber, FrameLayout.LayoutParams(-1, -1))

        // ---- 顶部：超大时间 ----
        val topH = (H * 0.135f).toInt()
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding((W * 0.035f).toInt(), 0, (W * 0.035f).toInt(), 0)
            isClickable = true
            setOnClickListener { }
        }

        timeView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.115f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            text = hhmm()
        }
        sideView = TextView(this).apply {
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, W * 0.038f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            text = dateLine()
        }
        topBar.addView(timeView, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(sideView, LinearLayout.LayoutParams(-2, -2))

        root.addView(topBar, FrameLayout.LayoutParams(-1, topH))

        // ---- 底部黑底 ----
        val bottomBlock = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        root.addView(
            bottomBlock,
            FrameLayout.LayoutParams(-1, (H * 0.27f).toInt()).apply {
                topMargin = (H * 0.73f).toInt()
            }
        )

        // ---- 右侧遮挡（点赞/评论/分享一列）----
        val rightBlock = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        root.addView(
            rightBlock,
            FrameLayout.LayoutParams((W * 0.215f).toInt(), (H * 0.62f).toInt()).apply {
                leftMargin = (W * 0.785f).toInt()
                topMargin = (H * 0.245f).toInt()
            }
        )

        // ---- 三颗按钮 ----
        val btnW = (W * 0.275f).toInt()
        val btnH = (W * 0.285f).toInt()
        val btnY = (H * 0.785f).toInt()
        val gap = (W * 0.025f).toInt()
        val leftPad = (W * 0.035f).toInt()

        val prev = squareBtn("上一个", btnW) { doSwipe(true) }
        root.addView(prev, FrameLayout.LayoutParams(btnW, btnH).apply {
            leftMargin = leftPad
            topMargin = btnY
        })

        val next = squareBtn("下一个", btnW) { doSwipe(false) }
        root.addView(next, FrameLayout.LayoutParams(btnW, btnH).apply {
            leftMargin = leftPad + btnW + gap
            topMargin = btnY
        })

        val home = CircleHomeButton(this)
        home.setOnClickListener { goHome() }
        root.addView(home, FrameLayout.LayoutParams(btnH, btnH).apply {
            leftMargin = W - btnH - leftPad
            topMargin = btnY
        })

        return root
    }

    private fun squareBtn(label: String, w: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, w * 0.27f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = w * 0.18f
                setColor(Color.parseColor("#0B5FC4"))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 红色圆形 + 白色圆环：返回桌面 */
    private class CircleHomeButton(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f

            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#D32F2F")
            canvas.drawCircle(cx, cy, r, p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.17f
            p.color = Color.WHITE
            canvas.drawCircle(cx, cy, r * 0.52f, p)
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

    // ---------------- 窗口参数 ----------------

    private fun windowParams(): WindowManager.LayoutParams {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val p = WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels,
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
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                p.fitInsetsTypes = 0
            } catch (t: Throwable) {
            }
        }
        return p
    }

    // ---------------- 时间 / 电量 ----------------

    private fun hhmm(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun dateLine(): String {
        val c = Calendar.getInstance()
        val week = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[c.get(Calendar.DAY_OF_WEEK) - 1]
        val m = c.get(Calendar.MONTH) + 1
        val d = c.get(Calendar.DAY_OF_MONTH)
        return "$m月$d日 $week\n电量 $battery()%"
    }

    private fun battery(): Int = try {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (t: Throwable) {
        0
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

        fun setTouchable(b: Boolean) {
            instance?.setTouchable(b)
        }
    }
}
