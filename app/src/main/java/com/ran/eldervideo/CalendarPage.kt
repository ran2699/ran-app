package com.ran.eldervideo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

/**
 * 老月份牌样式的万年历页。
 * 纸色底 + 朱红字 + 宋体，顶部红色横条，中间超大红字日，下面星期、农历、干支生肖，
 * 方框里放几行实在的数。返回键由主界面右下角那颗统一提供。
 */
class CalendarPage(ctx: Context, private val onBack: () -> Unit) : LinearLayout(ctx) {

    private val d = resources.displayMetrics.density

    private val RED = Color.parseColor("#C62828")
    private val DEEP_RED = Color.parseColor("#8E1B1B")
    private val PAPER = Color.parseColor("#F7F2E7")
    private val BOX = Color.parseColor("#FBF7EC")
    private val BROWN = Color.parseColor("#6D4C41")

    private val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private lateinit var content: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(PAPER)
        // 整页可滚动：内容比屏幕高时不再把最下面一行裁掉
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(PAPER)
        }
        content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(240))
        }
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        build()
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun label(text: String, sp: Float, color: Int, gravity: Int = Gravity.CENTER): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = serif
            this.gravity = gravity
            includeFontPadding = false
        }

    private fun build() {
        val cal = GregorianCalendar()
        cal.time = Date()
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val dy = cal.get(Calendar.DAY_OF_MONTH)
        val ld = Lunar.fromSolar(y, mo, dy)
        val weekCn = SimpleDateFormat("EEEE", Locale.CHINA).format(cal.time)

        // ① 顶部红条
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(RED)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val left = label("${Lunar.monthName(ld.month)}月", 38f, Color.WHITE, Gravity.START)
        val right = label("$y.$mo", 30f, Color.WHITE, Gravity.END)
        titleBar.addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        titleBar.addView(right, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ② 超大红字：阳历日
        val bigDay = label("$dy", 158f, RED)
        bigDay.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        // 高度自适应：190sp 的字塞进 weight=1 的格子里会被裁掉上下边
        content.addView(bigDay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        // ③ 星期 + 阳历
        val weekRow = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        weekRow.addView(label(weekCn, 48f, RED), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        weekRow.addView(
            label("$mo 月 $dy 日", 24f, BROWN),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )
        content.addView(weekRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // ④ 农历 + 干支生肖
        val lunarRow = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        lunarRow.addView(
            label("农历 ${Lunar.monthText(ld)}${Lunar.dayText(ld)}", 40f, DEEP_RED),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        lunarRow.addView(
            label("${Lunar.ganZhi(ld.year)}年 · 属${Lunar.zodiac(ld.year)}", 26f, BROWN),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        )
        content.addView(lunarRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ⑤ 方框：几行实在的数
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(BOX)
                setStroke(dp(3), RED)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }

        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val monthDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val nyThis = Lunar.newYear(ld.year)
        val nyNext = Lunar.newYear(ld.year + 1)
        val target = if (cal.timeInMillis < nyThis.timeInMillis) nyThis else nyNext
        val toSpring = ((target.timeInMillis - cal.timeInMillis) / 86400000L).toInt()

        box.addView(label("今年第 $dayOfYear 天", 28f, DEEP_RED, Gravity.CENTER))
        box.addView(
            label("还有 $toSpring 天过春节", 28f, DEEP_RED, Gravity.CENTER),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        )
        box.addView(
            label("$mo 月共 $monthDays 天", 28f, DEEP_RED, Gravity.CENTER),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        )
        content.addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // 剩下的空白吃掉，内容整块靠上，不再挤压大数字
        content.addView(android.view.View(context), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
}
