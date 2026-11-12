package com.ran.eldervideo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
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
    private val rowToday: TextView
    private val rowTomorrow: TextView
    private val rowAfter: TextView
    private val verdict: TextView
    private val status: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0B0B0B"))
        setPadding(dp(24), dp(46), dp(24), dp(205))

        addView(tv(CITY, 40f, Color.WHITE, Typeface.BOLD), lp())

        todayText = tv("—", 130f, Color.WHITE, Typeface.BOLD)
        addView(todayText, lp(top = 10))
        descText = tv("正在看天气…", 34f, Color.parseColor("#CFD8DC"), Typeface.NORMAL)
        addView(descText, lp(top = 2))

        val divider = LinearLayout(context).apply {
            setBackgroundColor(Color.parseColor("#2A2F36"))
        }
        addView(divider, LinearLayout.LayoutParams(-1, dp(2)).apply {
            topMargin = dp(20)
            bottomMargin = dp(18)
        })

        rowToday = tv("今天", 30f, Color.WHITE, Typeface.BOLD)
        rowTomorrow = tv("明天", 30f, Color.WHITE, Typeface.BOLD)
        rowAfter = tv("后天", 30f, Color.WHITE, Typeface.BOLD)
        addView(rowToday, lp(top = 6))
        addView(rowTomorrow, lp(top = 14))
        addView(rowAfter, lp(top = 14))

        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#14181D"))
                setStroke(dp(2), Color.parseColor("#39424D"))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        verdict = tv("…", 30f, Color.parseColor("#FFD54F"), Typeface.BOLD)
        box.addView(verdict, LinearLayout.LayoutParams(-1, -2))
        addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        status = tv("", 20f, Color.parseColor("#8D9AA5"), Typeface.NORMAL)
        addView(status, lp(top = 14))

        // 底部给右下角返回键留位置
        addView(LinearLayout(context), LinearLayout.LayoutParams(-1, dp(140)))

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
            var body: String? = null
            var err: String? = null
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                            "&current=temperature_2m,weather_code" +
                            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                            "&timezone=Asia%2FShanghai&forecast_days=7"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
            } catch (t: Throwable) {
                err = t.message
            }
            post { if (body != null) render(body) else fail(err) }
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

            todayText.text = if (curT.isNaN()) "—" else "${curT.toInt()}°"

            val hi0 = tmax.optDouble(0, Double.NaN)
            val lo0 = tmin.optDouble(0, Double.NaN)
            descText.text = "今天" + codeText(codes.optInt(0, curCode)) +
                    "，最冷 " + plainInt(lo0) + " 度，最热 " + plainInt(hi0) + " 度。"

            val sb = StringBuilder()
            for (i in 0 until 3) {
                if (i >= times.length()) break
                val hi = tmax.optDouble(i, Double.NaN)
                val lo = tmin.optDouble(i, Double.NaN)
                val line = "${label(i)}　${codeText(codes.optInt(i, 0))}　" +
                        "${plainInt(hi)}度 / ${plainInt(lo)}度"
                when (i) {
                    0 -> rowToday.text = line
                    1 -> rowTomorrow.text = line
                    2 -> rowAfter.text = line
                }
                sb.append(line)
            }
            rowToday.text = rowToday.text.toString().let { if (it == "今天") "今天" else it }

            verdict.text = buildVerdict(times, codes)
            status.text = "更新于 " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()) + " · " + CITY
        } catch (t: Throwable) {
            fail(t.message)
        }
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "—" else "${v.toInt()}°"

    private fun plainInt(v: Double): String = if (v.isNaN()) "—" else "${Math.round(v)}"

    private fun label(i: Int): String = when (i) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        3 -> "大后天"
        else -> "${i} 天后"
    }

    private fun buildVerdict(times: org.json.JSONArray, codes: org.json.JSONArray): String {
        val bad = mutableListOf<String>()
        for (i in 0 until minOf(codes.length(), 7)) {
            val c = codes.optInt(i, 0)
            when {
                isSnow(c) -> bad.add(label(i) + "下雪")
                isRain(c) -> bad.add(label(i) + "下雨")
            }
        }
        return if (bad.isEmpty()) "未来 7 天都是好天，不下雨也不下雪，放心出门。"
        else "未来 7 天里：" + bad.joinToString("，") + "。"
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
