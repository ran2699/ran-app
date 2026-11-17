package com.ran.agent

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.RuntimeShader
import android.graphics.RenderEffect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 原生液态玻璃外壳 · AGSL 折射版
 *
 * 玻璃由挂在 WebView 自身上的 RuntimeShader 绘制（RenderEffect），不经过
 * RenderNode 重放，因此 WebView 的 DrawFunctor 正常执行 —— 这是能折射到网页内容的原因。
 * 顶栏 / 输入岛的圆角矩形把屏幕分区，区内折射+模糊+色散+边缘高光，区外原样输出。
 *
 * 热调试：shader 与参数从设备目录读取，改动后广播重载，无需重新构建 APK。
 *   adb push glass.glsl  /sdcard/Android/data/com.ran.agent/files/glass.glsl
 *   adb push glass.cfg   /sdcard/Android/data/com.ran.agent/files/glass.cfg
 *   adb shell am broadcast -a com.ran.agent.RELOAD
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var island: FrameLayout
    private lateinit var input: EditText
    private lateinit var btnRow: LinearLayout

    private var islandExpanded = false
    private val glassTargets = ArrayList<Pair<View, Float>>()
    private var shader: RuntimeShader? = null
    private var cfg = GlassCfg()

    private var probe: TextView? = null

    private val reloader = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            applyConfig("reload")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        root.setBackgroundColor(0xFFF7F7F8.toInt())
        setContentView(root)

        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setupWeb()

        val layer = FrameLayout(this)
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        layer.addView(buildTopBar(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            gravity = Gravity.TOP
            topMargin = dp(26)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })

        island = buildIsland()
        layer.addView(island, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(H_COLLAPSE)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(18)
        })

        registerReceiver(reloader, IntentFilter("com.ran.agent.RELOAD"),
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)

        web.loadUrl("https://ran.ran-ai.pro")

        root.postDelayed({ applyConfig("init") }, 320)
        root.viewTreeObserver.addOnGlobalLayoutListener { applyRects() }
    }

    override fun onDestroy() {
        try { unregisterReceiver(reloader) } catch (_: Throwable) {}
        super.onDestroy()
    }

    /* ---------------- 热调试配置 ---------------- */

    private class GlassCfg {
        var bevel = 20f
        var refraction = 24f
        var dispersion = 1.4f
        var blurR = 6f
        var tintA = 0.06f
        var rimA = 0.55f

        override fun toString() =
            "bevel=${bevel.toInt()} refr=${refraction.toInt()} disp=$dispersion blur=${blurR.toInt()}"
    }

    private fun cfgDir() = getExternalFilesDir(null) ?: filesDir

    private fun readCfg(): GlassCfg {
        val c = GlassCfg()
        val f = File(cfgDir(), "glass.cfg")
        if (!f.exists()) return c
        for (raw in f.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trim().toFloatOrNull() ?: continue
            when (k) {
                "bevel" -> c.bevel = v
                "refraction" -> c.refraction = v
                "dispersion" -> c.dispersion = v
                "blurR" -> c.blurR = v
                "tintA" -> c.tintA = v
                "rimA" -> c.rimA = v
            }
        }
        return c
    }

    private fun readShaderSrc(): String {
        val f = File(cfgDir(), "glass.glsl")
        if (f.exists() && f.length() > 64) {
            try { return f.readText() } catch (_: Throwable) {}
        }
        return assets.open("glass.glsl").bufferedReader().use { it.readText() }
    }

    private fun applyConfig(why: String) {
        cfg = readCfg()
        if (Build.VERSION.SDK_INT < 33) {
            showProbe("API " + Build.VERSION.SDK_INT + " · 需要 API 33+ 才能折射")
            return
        }
        try {
            val s = RuntimeShader(readShaderSrc())
            shader = s
            val eff = RenderEffect.createRuntimeShaderEffect(s, "content")
            web.setRenderEffect(eff)
            applyRects()
            showProbe("玻璃已载入 · " + cfg.toString() + " · " + why)
        } catch (t: Throwable) {
            showProbe("shader 出错：" + t.javaClass.simpleName + " " + (t.message ?: ""))
        }
    }

    /** 把玻璃控件的位置换算成 WebView 本地坐标，逐个写入 shader 标量 uniform */
    private fun applyRects() {
        val s = shader ?: return
        val d = resources.displayMetrics.density
        val rects = ArrayList<FloatArray>()
        for ((v, r) in glassTargets) {
            if (rects.size >= 4) break
            if (v.width <= 0 || v.height <= 0 || v.visibility != View.VISIBLE) continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val wloc = IntArray(2)
            web.getLocationOnScreen(wloc)
            val l = (loc[0] - wloc[0]).toFloat()
            val t = (loc[1] - wloc[1]).toFloat()
            rects.add(floatArrayOf(l, t, l + v.width, t + v.height, r))
        }
        try {
            for (i in 0 until 4) {
                if (i < rects.size) {
                    val rc = rects[i]
                    s.setFloatUniform("r" + i + "x", rc[0])
                    s.setFloatUniform("r" + i + "y", rc[1])
                    s.setFloatUniform("r" + i + "z", rc[2])
                    s.setFloatUniform("r" + i + "w", rc[3])
                    s.setFloatUniform("r" + i + "r", rc[4])
                } else {
                    s.setFloatUniform("r" + i + "x", 0f)
                    s.setFloatUniform("r" + i + "y", 0f)
                    s.setFloatUniform("r" + i + "z", 0f)
                    s.setFloatUniform("r" + i + "w", 0f)
                    s.setFloatUniform("r" + i + "r", 0f)
                }
            }
            s.setFloatUniform("bevel", cfg.bevel * d)
            s.setFloatUniform("refraction", cfg.refraction * d)
            s.setFloatUniform("dispersion", cfg.dispersion * d)
            s.setFloatUniform("blurR", cfg.blurR * d)
            s.setFloatUniform("tintA", cfg.tintA)
            s.setFloatUniform("rimA", cfg.rimA)
            web.invalidate()
        } catch (t: Throwable) {
            showProbe("uniform 写入失败：" + t.javaClass.simpleName)
        }
    }

    /* ---------------- 顶栏 ---------------- */

    private fun icon(txt: String, size: Int, onclick: () -> Unit): TextView {
        return TextView(this).apply {
            text = txt
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            setOnClickListener { onclick() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }

    private fun clickWeb(id: String) {
        web.evaluateJavascript(
            "(function(){var b=document.getElementById('" + id + "');if(b){b.click();return 1}return 0})()",
            null)
    }

    /** 三块独立玻璃，中间留缝 */
    private fun buildTopBar(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val left = FrameLayout(this)
        left.addView(icon("\u2630", 48) { clickWeb("burger") })
        row.addView(left, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        glassTargets.add(Pair(left, dp(24).toFloat()))

        val mid = FrameLayout(this)
        mid.addView(TextView(this).apply {
            text = "Orion"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        glassTargets.add(Pair(mid, dp(24).toFloat()))

        val right = FrameLayout(this)
        val rr = LinearLayout(this)
        rr.orientation = LinearLayout.HORIZONTAL
        rr.gravity = Gravity.CENTER
        rr.addView(icon("\uFF0B", 44) { clickWeb("mnew") })
        rr.addView(icon("\u22EF", 44) { clickWeb("dots") })
        right.addView(rr, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        row.addView(right, LinearLayout.LayoutParams(dp(92), dp(48)))
        glassTargets.add(Pair(right, dp(24).toFloat()))

        return row
    }

    /* ---------------- 输入岛 ---------------- */

    private companion object {
        const val H_COLLAPSE = 56
        const val H_EXPAND = 152
    }

    private fun circle(size: Int, color: Int, text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (color == 0xFF1A1A1C.toInt()) Color.WHITE else 0xFF1A1A1C.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }

    private fun buildIsland(): FrameLayout {
        val g = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(2), dp(16), dp(2))

        input = EditText(this)
        input.hint = "\u5c3d\u7ba1\u95ee"
        input.textSize = 15f
        input.setTextColor(0xFF1A1A1C.toInt())
        input.setHintTextColor(0xFF9A9AA0.toInt())
        input.background = null
        input.setPadding(0, dp(10), 0, dp(10))
        input.maxLines = 1
        input.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        input.setOnFocusChangeListener { _, has -> expandIsland(has) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                doSend(); expandIsland(false); true
            } else false
        }
        col.addView(input)

        btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.CENTER_VERTICAL
        btnRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        btnRow.visibility = View.GONE
        btnRow.alpha = 0f

        val plus = circle(38, 0xFFEFF0F2.toInt(), "\uFF0B")
        plus.setOnClickListener { clickWeb("plusbtn") }
        btnRow.addView(plus)
        btnRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val send = circle(38, 0xFF1A1A1C.toInt(), "\u2191")
        send.setOnClickListener { doSend(); expandIsland(false) }
        btnRow.addView(send)
        col.addView(btnRow)

        g.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        glassTargets.add(Pair(g, dp(28).toFloat()))
        return g
    }

    private fun expandIsland(on: Boolean) {
        if (islandExpanded == on) return
        islandExpanded = on
        val from = island.layoutParams.height
        val to = dp(if (on) H_EXPAND else H_COLLAPSE)
        if (from != to) {
            val va = ValueAnimator.ofInt(from, to)
            va.duration = 240
            va.interpolator = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)
            va.addUpdateListener { a ->
                (island.layoutParams as FrameLayout.LayoutParams).height = a.animatedValue as Int
                island.requestLayout()
            }
            va.start()
        }
        val r = glassTargets.firstOrNull { it.first === island }
        if (r != null) {
            val idx = glassTargets.indexOf(r)
            glassTargets[idx] = Pair(island, dp(if (on) 26 else 28).toFloat())
        }
        if (on) {
            btnRow.visibility = View.VISIBLE
            btnRow.animate().alpha(1f).setDuration(200).start()
        } else {
            btnRow.animate().alpha(0f).setDuration(140).withEndAction {
                if (!islandExpanded) btnRow.visibility = View.GONE
            }.start()
        }
        input.maxLines = if (on) 4 else 1
        root.postDelayed({ applyRects() }, 260)
    }

    private fun doSend() {
        val t = input.text.toString()
        if (t.isBlank()) return
        val js = "(function(){try{var i=document.getElementById('in');" +
                "i.value=" + jsStr(t) + ";" +
                "i.dispatchEvent(new Event('input',{bubbles:true}));" +
                "var s=document.getElementById('sendbtn');if(s)s.click();" +
                "}catch(e){}})()"
        web.evaluateJavascript(js, null)
        input.setText("")
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /* ---------------- 探针 ---------------- */

    private fun showProbe(msg: String) {
        val tv = probe ?: TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF3A3A40.toInt())
            setBackgroundColor(0x66FFFFFF)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            root.addView(this, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(96)
            })
            probe = this
        }
        tv.text = msg
        tv.alpha = 1f
        tv.animate().cancel()
        tv.postDelayed({ tv.animate().alpha(0f).setDuration(400).start() }, 6000)
    }

    /* ---------------- 网页 ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val st = web.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.databaseEnabled = true
        st.loadWithOverviewMode = true
        st.useWideViewPort = true
        st.mediaPlaybackRequiresUserGesture = false
        st.userAgentString = st.userAgentString + " RANShell/1.0"

        web.setBackgroundColor(0x00FFFFFF)
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectShellCss()
                root.postDelayed({ applyRects() }, 400)
            }
        }
    }

    /**
     * 只隐藏网页自身的顶栏/输入区，给聊天流补 padding。
     * 全程运行时注入，网页源码零改动 —— 鸿蒙 / 桌面端不受影响。
     */
    private fun injectShellCss() {
        val css = """
            #mhead,#inwrap,#inputzone,#sendbtn{display:none!important}
            #chat{padding-bottom:96px!important}
            #chatinner{padding-top:86px!important}
            html,body{background:#F7F7F8!important}
        """.trimIndent()
        val js = "(function(){var s=document.getElementById('ran-shell-css');" +
                "if(!s){s=document.createElement('style');s.id='ran-shell-css';" +
                "document.head.appendChild(s);}s.textContent=" + jsStr(css) + ";})()"
        web.evaluateJavascript(js, null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
