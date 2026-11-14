package com.ran.eldervideo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 天气数据仓库：三级源 + 当天缓存。
 * 对外只吐 open-meteo 结构，WeatherPage 的渲染代码不用改。
 *
 * 1) 中国天气网 d1（国内，0.1~0.3 秒）—— 主用
 * 2) open-meteo（HTTPS，7 天，海外线路不通时会超时）
 * 3) wttr.in（HTTPS，3 天）—— 最后一道
 *
 * 缓存规则：同一天取到过就不再联网，直接渲染缓存。
 */
object WeatherRepo {

    private const val PREF = "eldervideo_weather"
    private const val K_BODY = "body"
    private const val K_DATE = "date"
    private const val K_TIME = "time"

    private const val CITY_CODE = "101090101"      // 石家庄
    private const val LAT = 38.04
    private const val LON = 114.51

    private fun dayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun read(ctx: Context, k: String): String? =
        runCatching { prefs(ctx).getString(k, null) }.getOrNull()

    /** 今天已经取到的数据；不是今天的返回 null（当天不再联网） */
    fun cachedToday(ctx: Context): String? {
        val d = read(ctx, K_DATE) ?: return null
        if (d != dayKey()) return null
        return read(ctx, K_BODY)?.takeIf { it.length > 20 }
    }

    /** 最近一次成功的数据（不限日期），联网失败时先给老人看旧的 */
    fun lastAny(ctx: Context): String? = read(ctx, K_BODY)?.takeIf { it.length > 20 }

    /** 缓存是几点存的 */
    fun savedAt(ctx: Context): String = read(ctx, K_TIME) ?: "—"

    private fun save(ctx: Context, body: String) {
        runCatching {
            prefs(ctx).edit()
                .putString(K_BODY, body)
                .putString(K_DATE, dayKey())
                .putString(K_TIME, SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()))
                .apply()
        }
    }

    /** 联网：先国内源，失败逐级兜底 */
    fun fetchOnline(ctx: Context): String? {
        fromCnWeather()?.let { save(ctx, it); return it }
        fromOpenMeteo()?.let { save(ctx, it); return it }
        fromWttr()?.let { save(ctx, it); return it }
        return null
    }

    // ---------------- 网络 ----------------

    private fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectMs: Int = 6000,
        readMs: Int = 9000
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val text = if (code in 200..299)
                conn.inputStream.bufferedReader().use(BufferedReader::readText) else null
            text
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ---------------- 源 1：中国天气网 ----------------

    private fun fromCnWeather(): String? {
        val raw = httpGet(
            "https://d1.weather.com.cn/weather_index/$CITY_CODE.html",
            mapOf("Referer" to "https://www.weather.com.cn/"),
            connectMs = 5000, readMs = 7000
        ) ?: return null

        val sk = jsVar(raw, "dataSK")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val dz = jsVar(raw, "cityDZ")?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("weatherinfo")

        val curTemp = sk?.optString("temp")?.toDoubleOrNull()
            ?: dz?.optString("temp")?.toDoubleOrNull()
            ?: Double.NaN
        val curCode = mapCode(dz?.optString("weathercode") ?: sk?.optString("weathercode") ?: "00")

        val fcRaw = jsVar(raw, "fc") ?: return null
        val f = runCatching { JSONObject(fcRaw).optJSONArray("f") }.getOrNull() ?: return null

        val time = JSONArray()
        val codes = JSONArray()
        val tmax = JSONArray()
        val tmin = JSONArray()
        val pr = JSONArray()

        for (i in 0 until f.length()) {
            val o = f.optJSONObject(i) ?: continue
            time.put(isoDate(o.optString("fi")))
            val c = mapCode(o.optString("fa"))
            codes.put(c)
            tmax.put(o.optString("fc").toDoubleOrNull() ?: 0.0)
            tmin.put(o.optString("fd").toDoubleOrNull() ?: 0.0)
            pr.put(rainProb(c))
        }
        if (codes.length() == 0) return null

        val root = JSONObject()
        root.put("current", JSONObject().apply {
            put("temperature_2m", curTemp)
            put("weather_code", curCode)
        })
        root.put("daily", JSONObject().apply {
            put("time", time)
            put("weather_code", codes)
            put("temperature_2m_max", tmax)
            put("temperature_2m_min", tmin)
            put("precipitation_probability_max", pr)
        })
        return root.toString()
    }

    /** 从 "var xx ={...};var yy" 里取出这一段 JSON */
    private fun jsVar(raw: String, name: String): String? {
        val i = raw.indexOf("var $name")
        if (i < 0) return null
        val eq = raw.indexOf('=', i)
        if (eq < 0) return null
        var s = raw.substring(eq + 1).trim()
        val next = s.indexOf(";var ")
        if (next >= 0) s = s.substring(0, next)
        s = s.trim().trimEnd(';').trim()
        return s.takeIf { it.startsWith("{") }
    }

    /** 中国天气网代码（00/01/07…，可能带 d/n 前缀）→ WMO */
    private fun mapCode(code: String): Int {
        val c = code.trim().removePrefix("d").removePrefix("n")
        return when (c) {
            "00" -> 0
            "01" -> 2
            "02" -> 3
            "03" -> 80
            "04" -> 95
            "05" -> 96
            "06" -> 66
            "07" -> 61
            "08" -> 63
            "09" -> 65
            "10", "11", "12" -> 65
            "13", "14" -> 71
            "15" -> 73
            "16", "17" -> 75
            "18" -> 45
            "19" -> 66
            "20", "29", "30", "31" -> 31
            "53" -> 48
            else -> 2
        }
    }

    private fun rainProb(code: Int): Int = when (code) {
        61, 71 -> 50
        63, 73, 80, 66 -> 60
        65, 75, 81, 82, 95, 96, 99 -> 80
        45, 48 -> 10
        else -> 0
    }

    /** "11/13" → "2026-11-13"（按今天推年份） */
    private fun isoDate(md: String): String {
        return try {
            val parts = md.split("/")
            val m = parts[0].toInt()
            val d = parts[1].toInt()
            val cal = java.util.Calendar.getInstance()
            val nowM = cal.get(java.util.Calendar.MONTH) + 1
            var y = cal.get(java.util.Calendar.YEAR)
            if (m < nowM - 6) y += 1        // 12 月里显示 1 月，属来年
            String.format(Locale.CHINA, "%04d-%02d-%02d", y, m, d)
        } catch (t: Throwable) {
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        }
    }

    /**
     * 只拉"现在多少度"：每次点开天气页都调用（1.0.40）。
     * 国内源 dataSK 0.2 秒级返回；失败逐级兜底。
     * 返回 {"current":{temperature_2m,weather_code}}，供 mergeCurrent 覆盖进完整数据。
     */
    fun fetchCurrentOnly(ctx: Context): String? {
        currentFromCn()?.let { return it }
        currentFromOpenMeteo()?.let { return it }
        currentFromWttr()?.let { return it }
        return null
    }

    private fun currentFromCn(): String? {
        val raw = httpGet(
            "https://d1.weather.com.cn/weather_index/$CITY_CODE.html",
            mapOf("Referer" to "https://www.weather.com.cn/"),
            connectMs = 5000, readMs = 7000
        ) ?: return null
        val sk = jsVar(raw, "dataSK")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val temp = sk.optString("temp").toDoubleOrNull() ?: return null
        val code = mapCode(sk.optString("weathercode", "02"))
        return JSONObject().put("current", JSONObject()
            .put("temperature_2m", temp)
            .put("weather_code", code)).toString()
    }

    private fun currentFromOpenMeteo(): String? {
        val raw = httpGet(
            "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                    "&current=temperature_2m,weather_code&forecast_days=1",
            connectMs = 6000, readMs = 10000
        ) ?: return null
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val cur = j.optJSONObject("current") ?: return null
        if (!cur.has("temperature_2m")) return null
        return JSONObject().put("current", cur).toString()
    }

    private fun currentFromWttr(): String? {
        val raw = httpGet("https://wttr.in/Shijiazhuang?format=j1",
            connectMs = 6000, readMs = 10000) ?: return null
        val cc = runCatching { JSONObject(raw) }
            .getOrNull()?.optJSONArray("current_condition")?.optJSONObject(0) ?: return null
        val temp = cc.optString("temp_C").toDoubleOrNull() ?: return null
        val desc = cc.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value") ?: ""
        return JSONObject().put("current", JSONObject()
            .put("temperature_2m", temp)
            .put("weather_code", mapText(desc))).toString()
    }

    // ---------------- 源 2：open-meteo ----------------

    private fun fromOpenMeteo(): String? = httpGet(
        "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=Asia%2FShanghai&forecast_days=7",
        connectMs = 6000, readMs = 10000
    )

    // ---------------- 源 3：wttr.in ----------------

    private fun fromWttr(): String? {
        val raw = httpGet("https://wttr.in/Shijiazhuang?format=j1",
            connectMs = 6000, readMs = 10000) ?: return null
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        val cc = j.optJSONArray("current_condition")?.optJSONObject(0)
        val curTemp = cc?.optString("temp_C")?.toDoubleOrNull() ?: Double.NaN
        val curCode = mapText(cc?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value") ?: "")

        val days = j.optJSONArray("weather") ?: return null
        if (days.length() == 0) return null

        val time = JSONArray()
        val codes = JSONArray()
        val tmax = JSONArray()
        val tmin = JSONArray()
        val pr = JSONArray()

        for (i in 0 until days.length()) {
            val o = days.optJSONObject(i) ?: continue
            time.put(o.optString("date"))
            val hourly = o.optJSONArray("hourly")
            val desc = hourly?.optJSONObject(minOf(4, (hourly.length() - 1).coerceAtLeast(0)))
                ?.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value") ?: ""
            val c = mapText(desc)
            codes.put(c)
            tmax.put(o.optString("maxtempC").toDoubleOrNull() ?: 0.0)
            tmin.put(o.optString("mintempC").toDoubleOrNull() ?: 0.0)
            pr.put(rainProb(c))
        }

        val root = JSONObject()
        root.put("current", JSONObject().apply {
            put("temperature_2m", curTemp)
            put("weather_code", curCode)
        })
        root.put("daily", JSONObject().apply {
            put("time", time)
            put("weather_code", codes)
            put("temperature_2m_max", tmax)
            put("temperature_2m_min", tmin)
            put("precipitation_probability_max", pr)
        })
        return root.toString()
    }

    private fun mapText(s: String): Int {
        val t = s.lowercase(Locale.CHINA)
        return when {
            t.contains("thunder") -> 95
            t.contains("snow") || t.contains("sleet") || t.contains("blizzard") -> 71
            t.contains("rain") || t.contains("drizzle") || t.contains("shower") -> 61
            t.contains("fog") || t.contains("mist") -> 45
            t.contains("haze") || t.contains("smoke") || t.contains("dust") || t.contains("sand") -> 48
            t.contains("overcast") -> 3
            t.contains("cloud") || t.contains("partly") -> 2
            t.contains("clear") || t.contains("sunny") -> 0
            else -> 2
        }
    }
}
