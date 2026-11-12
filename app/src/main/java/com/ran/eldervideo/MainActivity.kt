package com.ran.eldervideo

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 测试版引导页。三颗按钮：开遮罩权限 / 开辅助功能 / 进抖音。
 * 这个页面只是给家属配置用的，正式版会被极简桌面替代。
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(dp(22), dp(36), dp(22), dp(24))
        }

        root.addView(label("大 视 频", 30f, Color.WHITE, Gravity.CENTER))
        root.addView(
            label("给老人用的视频外壳 · 测试版 v0.1", 14f, Color.parseColor("#8A8A8A"), Gravity.CENTER),
            lp(bottom = dp(26))
        )

        statusView = label("", 17f, Color.parseColor("#FFC107"), Gravity.START).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#171717"))
        }
        root.addView(statusView, lp(bottom = dp(22)))

        root.addView(
            bigButton("① 允许盖在抖音上面", "#15569B") {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                }
            },
            lp(-1, dp(84), bottom = dp(12))
        )

        root.addView(
            bigButton("② 开启辅助功能（选“大视频控制”）", "#15569B") {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                }
            },
            lp(-1, dp(84), bottom = dp(12))
        )

        root.addView(
            bigButton("③ 进入抖音", "#1B6B3A") {
                if (!Settings.canDrawOverlays(this)) {
                    statusView.text = "还没给遮罩权限，请先点第①步。"
                    statusView.setTextColor(Color.parseColor("#FF7043"))
                    return@bigButton
                }
                OverlayService.start(this)
                openTikTok()
            },
            lp(-1, dp(96), bottom = dp(18))
        )

        root.addView(
            label(
                "说明：抖音里其余区域全部被黑框盖住，点不动；老人只需要看得见底下三颗大按钮。",
                13f, Color.parseColor("#6E6E6E"), Gravity.START
            )
        )

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B0B0B"))
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isA11yEnabled()
        statusView.text = buildString {
            append(if (overlayOk) "① 遮罩权限：已开 ✓" else "① 遮罩权限：未开 ✗")
            append("\n")
            append(if (a11yOk) "② 辅助功能：已开 ✓" else "② 辅助功能：未开 ✗")
            if (overlayOk && a11yOk) append("\n可以点③了")
        }
        statusView.setTextColor(
            if (overlayOk && a11yOk) Color.parseColor("#7BE495") else Color.parseColor("#FFC107")
        )
    }

    private fun isA11yEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openTikTok() {
        for (p in SwipeService.TIKTOK_PACKAGES) {
            val i = packageManager.getLaunchIntentForPackage(p) ?: continue
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
        statusView.text = "这台手机上没找到抖音，请先装好抖音。"
        statusView.setTextColor(Color.parseColor("#FF7043"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, sizeSp: Float, color: Int, gravity: Int): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            this.gravity = gravity
            includeFontPadding = false
        }

    private fun lp(w: Int = -1, h: Int = -2, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { bottomMargin = bottom }

    private fun bigButton(label: String, colorHex: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor(colorHex))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
}
