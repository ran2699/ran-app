package com.ran.eldervideo

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自绘锁屏（1.0.44）：每次解锁亮屏后自动出现。
 * 超大时间 + 日期星期 + 大电池；充电时不显示时间，直接转去充电锁屏（和插电动画那页一致）。
 * 点一下任意处进桌面。
 * 注意边界：系统 Keyguard 替换不掉——小米自己的锁屏会先出现，这页是解锁之后、桌面之前的一层。
 */
class LockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var timeText: TextView? = null
    private var dateText: TextView? = null
    private var batteryView: BatteryView? = null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun readBattery(): Pair<Int, Boolean> {
        val i = try {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            null
        } ?: return -1 to false
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val chg = st == BatteryManager.BATTERY_STATUS_CHARGING ||
            st == BatteryManager.BATTERY_STATUS_FULL
        val p = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
        return p to chg
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#FF06080C")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(96f), 0, 0)
        }
        val time = TextView(this).apply {
            textSize = 150f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val date = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.parseColor("#B8C2CE"))
            gravity = Gravity.CENTER
        }
        val bv = BatteryView(this)
        col.addView(
            time, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        col.addView(
            date, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
        )
        col.addView(bv, LinearLayout.LayoutParams(dp(150f), dp(70f)).apply { topMargin = dp(38f) })
        root.addView(
            col, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            TextView(this).apply {
                text = "点一下任意处，进入桌面"
                textSize = 30f
                setTextColor(Color.parseColor("#7E8A99"))
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(88f) }
        )
        timeText = time
        dateText = date
        batteryView = bv
        setContentView(root)
        root.setOnClickListener { enterHome() }
    }

    private fun refresh() {
        val now = Date()
        val (p, charging) = readBattery()
        if (charging) {
            // 充电就交给充电锁屏：时间不再显示，和插电弹的那页保持一致
            handler.removeCallbacks(ticker)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("show_charge", true)
            )
            finish()
            return
        }
        timeText?.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        dateText?.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
        batteryView?.percent = p
        batteryView?.charging = false
    }

    private fun enterHome() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
