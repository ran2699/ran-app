package com.ran.eldervideo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
 * 打电话三步走，每一步只问一件事：
 *   1  你是不是想要给别人打电话      → 我要打电话 / 我不要打电话
 *   2  你要给谁打电话？              → 六个名字（一排两个）+ 我不打电话
 *   3  你是不是要给 XXX 打去电话     → 确定打电话 / 不打电话
 * 按钮都往里收，离屏幕边和底边留出空档，防误触。
 */
class DialActivity : Activity() {

    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0B0B")) }
        setContentView(root)
        setupImmersive()

        val tel = intent?.data?.schemeSpecificPart.orEmpty()
        if (tel.isNotEmpty()) {
            val digits = tel.filter { it.isDigit() }
            val name = CallBook.nameFor(digits).ifEmpty { CallBook.pretty(digits) }
            showConfirm(name, digits)
        } else {
            showAsk()
        }
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

    private fun label(text: String, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

    private fun bigButton(
        text: String,
        face: Int,
        dark: Int,
        icon: Int,
        height: Int,
        textSp: Float,
        onClick: () -> Unit
    ): BigCallButton = BigCallButton(this, text, face, dark, icon).apply {
        textSizeSp = textSp
        iconSizeDp = if (icon == BigCallButton.ICON_NONE) 0f else 54f
        setOnPressListener(onClick)
    }

    private fun column(top: Int, bottom: Int, side: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(side), dp(top), dp(side), dp(bottom))
        }

    private fun switchTo(col: LinearLayout) {
        root.removeAllViews()
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
    }

    // ---------- 第一步：要不要打电话 ----------
    private fun showAsk() {
        val col = column(120, 60, 34)
        col.addView(
            label("你是不是\n想要给别人打电话", 44f, Color.WHITE, true).apply { maxLines = 2 },
            LinearLayout.LayoutParams(-1, -2)
        )
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        col.addView(
            bigButton("我要打电话", Color.parseColor("#2E9E4F"), Color.parseColor("#14501F"),
                BigCallButton.ICON_NONE, dp(180), 44f) { showPicker() },
            LinearLayout.LayoutParams(-1, dp(180))
        )
        col.addView(
            bigButton("我不要打电话", Color.parseColor("#C62828"), Color.parseColor("#6E0A0A"),
                BigCallButton.ICON_NONE, dp(180), 44f) { finish() },
            LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(30) }
        )
        switchTo(col)
    }

    // ---------- 第二步：给谁打 ----------
    private fun showPicker() {
        val col = column(50, 34, 26)
        col.addView(label("你要给谁打电话？", 42f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this), LinearLayout.LayoutParams(-1, dp(22)))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cells = 8
        var index = 0
        for (r in 0 until (cells / 2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 2) {
                val i = index++
                val contact = CallBook.contacts.getOrNull(i)
                val cell: View
                if (contact != null) {
                    cell = label(contact.first, 40f, Color.WHITE, true).apply {
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#1E3A5F"))
                            setStroke(dp(3), Color.parseColor("#5B8FD6"))
                            cornerRadius = dp(18).toFloat()
                        }
                        setOnClickListener { showConfirm(contact.first, contact.second) }
                    }
                } else {
                    cell = label("空着", 34f, Color.parseColor("#59636E"), false).apply {
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#15191F"))
                            setStroke(dp(2), Color.parseColor("#2A3138"))
                            cornerRadius = dp(18).toFloat()
                        }
                    }
                }
                line.addView(cell, LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                    leftMargin = dp(6)
                    rightMargin = dp(6)
                    bottomMargin = dp(12)
                })
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        col.addView(
            bigButton("我不打电话", Color.parseColor("#C62828"), Color.parseColor("#6E0A0A"),
                BigCallButton.ICON_NONE, dp(130), 42f) { finish() },
            LinearLayout.LayoutParams(-1, dp(130)).apply { bottomMargin = dp(20) }
        )
        switchTo(col)
    }

    // ---------- 第三步：给 XXX 打吗 ----------
    private fun showConfirm(name: String, number: String) {
        val col = column(130, 70, 34)

        val text = SpannableString("你是不是要给" + name + "打去电话")
        val start = 6
        val end = start + name.length
        text.setSpan(ForegroundColorSpan(Color.parseColor("#FFD54F")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(1.25f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        col.addView(
            TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 3
            },
            LinearLayout.LayoutParams(-1, -2)
        )
        col.addView(
            label(CallBook.pretty(number), 34f, Color.parseColor("#CFD8DC"), false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        )
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        col.addView(
            bigButton("确定打电话", Color.parseColor("#2E9E4F"), Color.parseColor("#14501F"),
                BigCallButton.ICON_TICK, dp(170), 42f) { dial(number) },
            LinearLayout.LayoutParams(-1, dp(170))
        )
        col.addView(
            bigButton("不打电话", Color.parseColor("#C62828"), Color.parseColor("#6E0A0A"),
                BigCallButton.ICON_CROSS, dp(170), 42f) { finish() },
            LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(28) }
        )
        switchTo(col)
    }

    private fun dial(number: String) {
        try {
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number.filter { it.isDigit() }))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            finish()
        } catch (t: Throwable) {
            Toast.makeText(this, "拨不出去：" + (t.message ?: "没有打电话权限"), Toast.LENGTH_LONG).show()
        }
    }
}
