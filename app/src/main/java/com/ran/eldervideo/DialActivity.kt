package com.ran.eldervideo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 极简拨号页。
 * 本应用是默认电话应用之后，桌面上「打电话」那颗键（ACTION_DIAL）会落到这里，
 * 所以这个页面必须存在——不然老人按了没反应。
 * 上面一个常用号码（王敬然），下面一整块大键盘，拨出去用系统电话通道。
 */
class DialActivity : Activity() {

    private lateinit var shown: TextView
    private var num = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extra = intent?.data?.schemeSpecificPart.orEmpty()
        if (extra.isNotEmpty()) num = extra.filter { it.isDigit() }
        buildUi()
        setupImmersive()
        update()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersive()
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


    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B0B0B"))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(190))
        }

        col.addView(label("打电话", 38f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2))

        shown = label("", 52f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        col.addView(shown, LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(14) })

        // 常用号码：一下就到
        val quick = label("王敬然　187 1296 1913", 32f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B3D2B"))
                setStroke(dp(3), Color.parseColor("#2E9E4F"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener { dialTo("18712961913") }
        }
        col.addView(quick, LinearLayout.LayoutParams(-1, dp(92)).apply { topMargin = dp(10) })

        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )
        for (r in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) {
                val b = label(k, 44f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C2530"))
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener {
                        if (num.length < 15) {
                            num += k
                            update()
                        }
                    }
                }
                line.addView(b, LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                    rightMargin = dp(6)
                    leftMargin = dp(6)
                    bottomMargin = dp(10)
                })
            }
            pad.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(pad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = label("退格", 32f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#37414D"))
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener {
                if (num.isNotEmpty()) {
                    num = num.substring(0, num.length - 1)
                    update()
                }
            }
        }
        bottom.addView(back, LinearLayout.LayoutParams(0, dp(112), 1f).apply { rightMargin = dp(8) })

        val call = label("拨出去", 40f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2E9E4F"))
                setStroke(dp(3), Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener {
                if (num.isBlank()) toast("先按号码") else dialTo(num)
            }
        }
        bottom.addView(call, LinearLayout.LayoutParams(0, dp(112), 2f).apply { leftMargin = dp(8) })
        col.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // 右下角常驻返回
        val backBall = label("回桌面", 30f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D3261B"))
                setStroke(dp(3), Color.WHITE)
                cornerRadius = dp(999).toFloat()
            }
            setOnClickListener { finish() }
        }
        root.addView(backBall, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        })

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun update() {
        shown.text = if (num.isEmpty()) "按号码" else CallBook.pretty(num)
        if (num.isEmpty()) shown.setTextColor(Color.parseColor("#5C6773")) else shown.setTextColor(Color.WHITE)
    }

    private fun dialTo(number: String) {
        try {
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number.filter { it.isDigit() }))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (t: Throwable) {
            toast("拨不出去：" + (t.message ?: "没有打电话权限"))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
