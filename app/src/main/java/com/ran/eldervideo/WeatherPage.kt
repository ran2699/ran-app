package com.ran.eldervideo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自己写的天气页，不用第三方 App。
 * 数据来自 open-meteo（免费、无需 key）：当前温度 + 今天/明天/后天 + 未来 7 天雨雪结论。
 * 只显示结论，不铺图表。
 */
class WeatherPage(ctx: Context) : LinearLayout(ctx) {

    private val d = resources.displayMetrics.density
    private val CITY = "石家庄"
    private val LAT = 38.04
    private val LON = 114.51

    private val todayText: TextView
    private val descText: TextView
    private val rowTomorrow: TextView
    private val rowAfter: TextView
    private lateinit var content: LinearLayout
    private val verdict: TextView
    private val status: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0B0B0B"))
        // 整页可滚：内容比屏幕高时不再把最下面那句话裁掉
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0B0B0B"))
        }
        content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(24), dp(34), dp(24), dp(188))
        }
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 底部 176dp 是那条固定底栏的地盘（关掉手机 + 红球），内容滚到这里就收进去。
        // 不放这条实心底边的话，天气内容会一直钻到按钮底下（1.0.8 实测「更新于」正好压在红球下面）。
        val frame = FrameLayout(context)
        frame.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val footer = android.view.View(context).apply { setBackgroundColor(Color.parseColor("#0B0B0B")) }
        frame.addView(footer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(176), Gravity.BOTTOM
        ))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        content.addView(tv(CITY, 40f, Color.WHITE, Typeface.BOLD), lp())

        todayText = tv("—", 108f, Color.WHITE, Typeface.BOLD)
        content.addView(todayText, lp(top = 10))
        descText = tv("正在看天气…", 30f, Color.parseColor("#CFD8DC"), Typeface.NORMAL)
        content.addView(descText, lp(top = 2))

        val divider = LinearLayout(context).apply {
            setBackgroundColor(Color.parseColor("#2A2F36"))
        }
        content.addView(divider, LinearLayout.LayoutParams(-1, dp(2)).apply {
            topMargin = dp(14)
            bottomMargin = dp(10)
        })

        // 今天的信息在上面 descText 里已经有了，这里只列明天/后天，避免"今天"写两遍
        rowTomorrow = tv("明天", 28f, Color.WHITE, Typeface.BOLD).apply {
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        rowAfter = tv("后天", 28f, Color.WHITE, Typeface.BOLD).apply {
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        content.addView(rowTomorrow, lp(top = 6))
        content.addView(rowAfter, lp(top = 10))

        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#14181D"))
                setStroke(dp(2), Color.parseColor("#39424D"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        verdict = tv("…", 26f, Color.parseColor("#FFD54F"), Typeface.BOLD).apply {
            isSingleLine = false
            maxLines = 6
        }
        box.addView(verdict, LinearLayout.LayoutParams(-1, -2))
        content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        status = tv("", 20f, Color.parseColor("#8D9AA5"), Typeface.NORMAL)
        content.addView(status, lp(top = 10))

        // 底部给右下角返回键留位置
        content.addView(LinearLayout(context), LinearLayout.LayoutParams(-1, dp(20)))

        load()
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun tv(text: String, sp: Float, color: Int, style: Int): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.create(Typeface.DEFAULT, style)
            includeFontPadding = false
        }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    // ---------------- 数据 ----------------
    private fun load() {
        Thread {
            // ① 当天已经取到过：直接渲染缓存，一次网络都不发
            val cached = WeatherRepo.cachedToday(context)
            if (cached != null) {
                post {
                    render(cached)
                    status.text = "今天已更新过 · " + WeatherRepo.savedAt(context) +
                            " · 同一天不再联网 · " + CITY
                }
                return@Thread
            }

            // ② 联网：国内源优先，逐级兜底
            var body: String? = null
            var err: String? = null
            try {
                body = WeatherRepo.fetchOnline(context)
            } catch (t: Throwable) {
                err = t.message
            }

            val got = body
            post {
                if (got != null) {
                    render(got)
                    status.text = "更新于 " + WeatherRepo.savedAt(context) + " · " + CITY
                } else {
                    // ③ 三个源都没通：有旧数据就先给旧的，别让人看见一片空白
                    val old = WeatherRepo.lastAny(context)
                    if (old != null) {
                        render(old)
                        status.text = "现在连不上，先看上次的天气 · " + WeatherRepo.savedAt(context)
                    } else {
                        fail(err)
                    }
                }
            }
        }.start()
    }

    private fun fail(err: String?) {
        descText.text = "没拿到"
        status.text = "天气没拿到，检查手机能不能上网。" + (err ?: "")
    }

    private fun render(body: String) {
        try {
            val j = JSONObject(body)
            val cur = j.getJSONObject("current")
            val curT = cur.optDouble("temperature_2m", Double.NaN)
            val curCode = cur.optInt("weather_code", 0)

            val daily = j.getJSONObject("daily")
            val times = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val tmax = daily.getJSONArray("temperature_2m_max")
            val tmin = daily.getJSONArray("temperature_2m_min")

            // "现在"只是提示，不能和温度一样大：把前两个字缩到 32%
            if (curT.isNaN()) {
                todayText.text = "—"
            } else {
                val ss = android.text.SpannableString("现在 ${curT.toInt()}°")
                ss.setSpan(
                    android.text.style.RelativeSizeSpan(0.32f),
                    0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                todayText.text = ss
            }

            val hi0 = tmax.optDouble(0, Double.NaN)
            val lo0 = tmin.optDouble(0, Double.NaN)
            descText.text = "今天" + codeText(codes.optInt(0, curCode)) +
                    "，最高 " + plainInt(hi0) + " 度，最低 " + plainInt(lo0) + " 度。"

            val prArr = daily.optJSONArray("precipitation_probability_max")
            for (i in 1 until 3) {
                if (i >= times.length()) break
                val hi = tmax.optDouble(i, Double.NaN)
                val lo = tmin.optDouble(i, Double.NaN)
                // 主行：明天　多云　4～17 度
                val head = "${label(i)}　${codeText(codes.optInt(i, 0))}　" +
                        "${plainInt(lo)}～${plainInt(hi)} 度"
                // 副行：雨 20%　比今天冷 3 度（灰字小一号）
                val sub = subLine(i, hi, lo, hi0, prArr)
                val ss = android.text.SpannableString(if (sub.isEmpty()) head else head + "\n" + sub)
                if (sub.isNotEmpty()) {
                    val st = head.length + 1
                    ss.setSpan(
                        android.text.style.AbsoluteSizeSpan(22, true), st, ss.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    ss.setSpan(
                        android.text.style.ForegroundColorSpan(Color.parseColor("#9AA5B1")), st, ss.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                when (i) {
                    1 -> rowTomorrow.text = ss
                    2 -> rowAfter.text = ss
                }
            }

            verdict.text = buildVerdict(times, codes)
            status.text = "更新于 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()) + " · " + CITY
        } catch (t: Throwable) {
            fail(t.message)
        }
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "—" else "${v.toInt()}°"

    private fun plainInt(v: Double): String = if (v.isNaN()) "—" else "${Math.round(v)}"

    /** 明天的第二行：降水概率 + 和今天比冷暖，控制在两行之内 */
    private fun subLine(i: Int, hi: Double, lo: Double, hi0: Double, pr: JSONArray?): String {
        val parts = ArrayList<String>()
        val p = pr?.optInt(i, -1) ?: -1
        if (p in 0..99) parts.add(if (p <= 5) "不下雨" else "雨 $p%")
        if (!hi.isNaN() && !hi0.isNaN()) {
            val d = Math.round(hi).toInt() - Math.round(hi0).toInt()
            when {
                d >= 2 -> parts.add("比今天暖 $d 度")
                d <= -2 -> parts.add("比今天冷 ${-d} 度")
                else -> parts.add("跟今天差不多")
            }
            val gap = Math.round(hi).toInt() - Math.round(lo).toInt()
            if (gap >= 10) parts.add("早晚差 $gap 度")
        }
        return parts.joinToString("　")
    }

    private fun label(i: Int): String = when (i) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        3 -> "大后天"
        else -> "${i} 天后"
    }

    private fun buildVerdict(times: org.json.JSONArray, codes: org.json.JSONArray): String {
        // 天数按实际拿到的算：国内源给 5 天，open-meteo 给 7 天，不能写死
        val n = minOf(codes.length(), 7)
        val bad = mutableListOf<String>()
        for (i in 0 until n) {
            val c = codes.optInt(i, 0)
            when {
                isSnow(c) -> bad.add(label(i) + "下雪")
                isRain(c) -> bad.add(label(i) + "下雨")
            }
        }
        return if (bad.isEmpty()) "未来 $n 天都是好天，不下雨也不下雪，放心出门。"
        else "未来 $n 天里：" + bad.joinToString("，") + "。"
    }

    private fun isRain(c: Int): Boolean =
        c in 51..67 || c in 80..82 || c == 95 || c == 96 || c == 99

    private fun isSnow(c: Int): Boolean = c == 71 || c == 73 || c == 75 || c == 77 || c == 85 || c == 86

    /** 大白话：老人一眼就懂 */
    private fun codeText(c: Int): String = when (c) {
        0 -> "是晴天"
        1 -> "是晴天，有点云"
        2 -> "是多云"
        3 -> "是阴天"
        45, 48 -> "有雾"
        51, 53, 55 -> "下毛毛雨"
        56, 57 -> "下冻雨"
        61 -> "下小雨"
        63 -> "下中雨"
        65 -> "下大雨"
        66, 67 -> "下冻雨"
        71 -> "下小雪"
        73 -> "下中雪"
        75 -> "下大雪"
        77 -> "下雪粒"
        80 -> "有点阵雨"
        81 -> "下阵雨"
        82 -> "下大雨"
        85 -> "下阵雪"
        86 -> "下大雪"
        95 -> "打雷下雨"
        96, 99 -> "打雷下雨，还下冰雹"
        else -> "是多云"
    }

}
