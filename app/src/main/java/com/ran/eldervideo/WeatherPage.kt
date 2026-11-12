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
        setPadding(dp(24), dp(46), dp(24), dp(30))

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
            descText.text = "现在 " + codeText(curCode)

            val sb = StringBuilder()
            for (i in 0 until 3) {
                if (i >= times.length()) break
                val hi = tmax.optDouble(i, Double.NaN)
                val lo = tmin.optDouble(i, Double.NaN)
                val line = "${label(i)}   ${fmt(hi)} / ${fmt(lo)}   ${codeText(codes.optInt(i, 0))}"
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
                isRain(c) -> bad.add(label(i) + "有雨")
            }
        }
        return if (bad.isEmpty()) "未来 7 天没有雨雪" else "未来 7 天：" + bad.joinToString("，")
    }

    private fun isRain(c: Int): Boolean =
        c in 51..67 || c in 80..82 || c == 95 || c == 96 || c == 99

    private fun isSnow(c: Int): Boolean = c == 71 || c == 73 || c == 75 || c == 77 || c == 85 || c == 86

    private fun codeText(c: Int): String = when (c) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85 -> "阵雪"
        86 -> "强阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "多云"
    }
}
