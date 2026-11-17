package com.ran.agent

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import java.io.File

/**
 * 原生液态玻璃外壳 · 使用 LiquidGlassView（QWEA0/Liquid-Glass-Android，MIT）
 *
 * 架构（库文档明确要求的两条）：
 *  1. 玻璃视图与 WebView 必须在同一个 FrameLayout 里，玻璃写在 WebView 之后 ——
 *     玻璃捕获的是"直接父容器"已绘制的内容。
 *  2. 覆盖在 WebView 上时必须给 WebView 加硬件层（LAYER_TYPE_HARDWARE），
 *     否则背景捕获取不到 WebView 的像素（会得到恒定灰）。
 *  3. 背景会变（滚动）时必须 enableDynamicBackground = true，否则玻璃冻结在首帧。
 *
 * 折射/色散/高光/自适应染色全部由库的 AGSL 透镜管线完成（API 33+），不再自绘 shader。
 *
 * 热调试（不重新出包）：参数从设备目录读取，改完广播重载
 *   adb push glass.cfg /sdcard/Android/data/com.ran.agent/files/glass.cfg
 *   adb shell am broadcast -a com.ran.agent.RELOAD
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var island: LiquidGlassView
    private lateinit var input: EditText
    private lateinit var btnRow: LinearLayout

    private lateinit var gBurger: LiquidGlassView
    private lateinit var gPill: LiquidGlassView
    private lateinit var gPlus: LiquidGlassView
    private lateinit var gDots: LiquidGlassView

    /** 玻璃内部的奶白层：子 View 画在玻璃之上，用它做确定性的白纱浓度（0-1） */
    private val milks = LinkedHashMap<LiquidGlassView, View>()

    private var islandExpanded = false
    private var insetTop = 0
    private var insetBottom = 0
    private var imeBottom = 0
    private var lastPad = -1

    private val TAG = "RanGlass"

    /* cfg key -> 库属性名。dp 单位换算在 applyGlassParams 里做；
       任何库属性只要在 CFG_DEFAULTS/PROP 里登记就能热调，不需要重新出包。 */
    /** key = 库属性的真实名字（驼峰），值原样写入；dp 的键在写入前乘 density */
    private val CFG_DEFAULTS = linkedMapOf(
        "bevelWidth" to 16f, "refractionHeight" to 22f, "refractionFalloff" to 2.2f,
        "dispersionStrength" to 0.06f, "useShaderPipeline" to 1f,
        "blurAmount" to 0.30f, "saturation" to 110f, "milk" to 0.35f,
        "enableAdaptiveTint" to 1f, "enableSensorHighlight" to 1f,
        "enableEdgeHighlight" to 1f, "enableShadow" to 0f,
        "probe" to 0f
    )
    private val DP_KEYS = setOf("bevelWidth", "refractionHeight", "corner", "islandCorner")
    private val cfg = LinkedHashMap<String, Float>()
    private val cfgInt = LinkedHashMap<String, Int>()

    private val reloader = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadCfg()
            applyGlassParams()
            runDemoJs()
            Log.i(TAG, "reload pipeline=" + cfg["useShaderPipeline"] +
                    " bevel=" + cfg["bevelWidth"] + " refr=" + cfg["refractionHeight"] +
                    " blur=" + cfg["blurAmount"] + " keys=" + cfg.size)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)

        root = FrameLayout(this)
        root.setBackgroundColor(0xFFF7F7F8.toInt())
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            } catch (_: Throwable) {}
        }

        loadCfg()

        // ① WebView 先进，且加硬件层（玻璃捕获的前提）
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } catch (_: Throwable) {}
        setupWeb()

        // ② 四块玻璃直接挂在 root（与 WebView 同父），必须写在 WebView 之后
        gBurger = glassBox(dp(22f)) { icon("\u2630") { clickWeb("burger") } }
        gPill = glassBox(dp(22f)) {
            TextView(this).apply {
                text = "Orion"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(0xFF1A1A1C.toInt())
            }
        }
        gPlus = glassBox(dp(22f)) { icon("\uFF0B") { clickWeb("mnew") } }
        gDots = glassBox(dp(22f)) { icon("\u22EF") { clickWeb("dots") } }

        // ③ 输入岛
        island = buildIsland()
        root.addView(island, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(H_COLLAPSE), Gravity.BOTTOM))

        applyGlassParams()

        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                insetTop = ins.getInsets(WindowInsets.Type.statusBars()).top
                insetBottom = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                imeBottom = maxOf(0, ins.getInsets(WindowInsets.Type.ime()).bottom - insetBottom)
            } else {
                @Suppress("DEPRECATION")
                insetTop = ins.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetBottom = ins.systemWindowInsetBottom
                imeBottom = 0
            }
            applyLayout()
            Log.i(TAG, "insets top=$insetTop bottom=$insetBottom ime=$imeBottom dens=" +
                    resources.displayMetrics.density)
            ins
        }

        val filter = IntentFilter("com.ran.agent.RELOAD")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(reloader, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(reloader, filter)

        web.loadUrl("https://ran.ran-ai.pro")

        root.isFocusableInTouchMode = true
        root.requestFocus()

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutTopBar() }
    }

    /* ---------------- 玻璃 ---------------- */

    private fun glassBox(radius: Float, content: (FrameLayout) -> View): LiquidGlassView {
        val g = LiquidGlassView(this)
        g.cornerRadius = radius
        g.useShaderPipeline = true
        g.material = GlassMaterial.REGULAR
        g.enableDynamicBackground = true      // 页面滚动/变化时玻璃跟着刷新，不加就是冻结的
        g.enableEdgeHighlight = true
        g.enableShadow = false                // 不描黑边
        g.clipChildren = false
        val mv = View(this)
        g.addView(mv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        milks[g] = mv
        val c = content(g)
        g.addView(c, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(g, FrameLayout.LayoutParams(dp(44), dp(44)))
        return g
    }

    /**
     * 按 cfg 逐项写进库属性（反射调用 setter）。
     * 这样以后无论想调哪个属性，只改设备上的 glass.cfg + 广播，不用重新出包。
     * 单位：bevel/refr/corner/islandCorner 是 dp，这里乘 density 换成 px；其余无量纲。
     */
    private fun applyGlassParams() {
        val d = resources.displayMetrics.density
        val top = listOf<Any>(gBurger, gPill, gPlus, gDots)
        val all = listOf<Any>(gBurger, gPill, gPlus, gDots, island)
        for ((k, v) in cfg) {
            if (k == "probe") continue
            val raw = if (k in DP_KEYS) v * d else v
            val targets = when (k) {
                "corner" -> top
                "islandCorner" -> listOf<Any>(island)
                "material" -> {                 // 0=CLEAR 1=REGULAR，枚举没法用数值反射
                    for (t in all) (t as LiquidGlassView).material =
                        if (v > 0.5f) GlassMaterial.REGULAR else GlassMaterial.CLEAR
                    continue
                }
                else -> all
            }
            for (t in targets) setProp(t, k, raw)
        }
        for ((k, v) in cfgInt) {
            if (k == "glassTint") for (t in all) setIntProp(t, "glassTint", v)
        }
        if (islandExpanded) setProp(island, "cornerRadius", dp(26f))
        applyMilk()
        if ((cfg["probe"] ?: 0f) > 0.5f) {
            showProbe("pipeline=" + (cfg["useShaderPipeline"] ?: 0f) +
                    " milk=" + cfg["milk"] + " blur=" + cfg["blurAmount"] +
                    " sat=" + cfg["saturation"] + " tint=" + cfgInt["glassTint"])
        }
    }

    /** 奶白层：白色 + 可调 alpha + 跟随各自圆角。milk=0 完全关掉保持纯玻璃。 */
    private fun applyMilk() {
        val a = ((cfg["milk"] ?: 0f).coerceIn(0f, 1f) * 255).toInt()
        for ((g, v) in milks) {
            if (a <= 0) { v.visibility = View.GONE; continue }
            v.visibility = View.VISIBLE
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.RECTANGLE
            gd.cornerRadius = g.cornerRadius
            gd.setColor((a shl 24) or 0xFFFFFF)
            v.background = gd
        }
    }

    private fun setter(name: String) = "set" + name.replaceFirstChar { it.uppercase() }

    /** 通用写入：按 Boolean -> Float -> Int 依次尝试同名 setter（属性名即 cfg key） */
    private fun setProp(target: Any, prop: String, f: Float) {
        val m = setter(prop)
        val cls = target.javaClass
        runCatching { cls.getMethod(m, java.lang.Boolean.TYPE).invoke(target, f > 0.5f) }
            .recoverCatching { cls.getMethod(m, java.lang.Float.TYPE).invoke(target, f) }
            .recoverCatching { cls.getMethod(m, java.lang.Integer.TYPE).invoke(target, f.toInt()) }
            .onFailure { Log.w(TAG, "no setter: " + m) }
    }

    private fun setIntProp(target: Any, prop: String, v: Int) {
        try {
            target.javaClass.getMethod(setter(prop), java.lang.Integer.TYPE).invoke(target, v)
        } catch (_: Throwable) {
            Log.w(TAG, "no int setter: " + setter(prop))
        }
    }

    /* ---------------- 布局 ---------------- */

    /** 四块玻璃直接挂在 root 上，宽度靠边距算（FrameLayout 没有 weight） */
    private fun layoutTopBar() {
        val w = root.width
        if (w <= 0) return
        val gap = dp(8)
        val side = dp(44)
        val h = dp(44)
        val top = insetTop + dp(6)
        val left0 = dp(12)
        val right0 = dp(12)

        fun place(v: View, l: Int, t: Int, ww: Int) {
            val p = v.layoutParams as FrameLayout.LayoutParams
            if (p.leftMargin == l && p.topMargin == t && p.width == ww && p.height == h) return
            p.gravity = Gravity.TOP or Gravity.LEFT
            p.leftMargin = l
            p.topMargin = t
            p.width = ww
            p.height = h
            v.layoutParams = p
        }

        place(gBurger, left0, top, side)
        place(gDots, w - right0 - side, top, side)
        place(gPlus, w - right0 - side * 2 - gap, top, side)
        val pillL = left0 + side + gap
        val pillR = w - right0 - side * 2 - gap * 2
        place(gPill, pillL, top, (pillR - pillL).coerceAtLeast(dp(80)))
    }

    private fun applyLayout() {
        layoutTopBar()
        if (this::island.isInitialized) {
            val lp = island.layoutParams as FrameLayout.LayoutParams
            val want = insetBottom + imeBottom + dp(14)
            if (lp.bottomMargin != want || lp.leftMargin != dp(12) || lp.rightMargin != dp(12)) {
                lp.gravity = Gravity.BOTTOM
                lp.bottomMargin = want
                lp.leftMargin = dp(12)
                lp.rightMargin = dp(12)
                island.layoutParams = lp
            }
        }
    }

    /* ---------------- 控件 ---------------- */

    private fun icon(txt: String, onclick: () -> Unit): TextView {
        return TextView(this).apply {
            text = txt
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            setOnClickListener { onclick() }
        }
    }

    private fun clickWeb(id: String) {
        web.evaluateJavascript(
            "(function(){var b=document.getElementById('$id');if(b){b.click();return 1}return 0})()",
            null)
    }

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

    private fun buildIsland(): LiquidGlassView {
        val g = LiquidGlassView(this)
        g.cornerRadius = dp(28f)
        g.useShaderPipeline = true
        g.material = GlassMaterial.REGULAR
        g.enableDynamicBackground = true
        g.enableEdgeHighlight = true
        g.enableShadow = false
        g.clipChildren = false

        val mv = View(this)
        g.addView(mv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        milks[g] = mv

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
        input.isSingleLine = true
        input.isFocusableInTouchMode = false
        input.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        input.setSelectAllOnFocus(false)
        input.setOnClickListener {
            input.isFocusableInTouchMode = true
            input.requestFocus()
            showIme()
        }
        input.setOnFocusChangeListener { _, has ->
            expandIsland(has)
            if (!has) hideIme()
        }
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
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER_VERTICAL))
        return g
    }

    private fun showIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
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
        island.cornerRadius = dp(if (on) 26f else 28f)
        if (on) {
            btnRow.visibility = View.VISIBLE
            btnRow.animate().alpha(1f).setDuration(200).start()
            input.isSingleLine = false
            input.maxLines = 4
        } else {
            btnRow.animate().alpha(0f).setDuration(140).withEndAction {
                if (!islandExpanded) btnRow.visibility = View.GONE
            }.start()
            input.isSingleLine = true
            input.maxLines = 1
            input.isFocusableInTouchMode = false
        }
    }

    private fun doSend() {
        val t = input.text.toString()
        if (t.isBlank()) return
        val js = "(function(){try{var i=document.getElementById('in');i.value=" + jsStr(t) + ";" +
                "i.dispatchEvent(new Event('input',{bubbles:true}));" +
                "var s=document.getElementById('sendbtn');if(s)s.click();}catch(e){}})()"
        web.evaluateJavascript(js, null)
        input.setText("")
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /* ---------------- cfg ---------------- */

    private fun cfgDir() = getExternalFilesDir(null) ?: filesDir

    private fun loadCfg() {
        cfg.clear(); cfgInt.clear()
        cfg.putAll(CFG_DEFAULTS)
        val f = File(cfgDir(), "glass.cfg")
        if (!f.exists() || f.length() < 8) return
        try {
            for (raw in f.readLines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val i = line.indexOf('=')
                if (i <= 0) continue
                val k = line.substring(0, i).trim()
                val raw = line.substring(i + 1).trim()
                if (raw.startsWith("0x") || raw.startsWith("0X")) {
                    raw.substring(2).toLongOrNull(16)?.let { cfgInt[k] = it.toInt() }
                    continue
                }
                val v = raw.toFloatOrNull() ?: continue
                cfg[k] = v
            }
        } catch (_: Throwable) {}
    }

    private var probe: TextView? = null

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
        Log.i(TAG, "probe: " + msg)
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
        st.userAgentString = st.userAgentString + " RANShell/2.0"

        web.setBackgroundColor(0x00FFFFFF)
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectShellCss()
                root.postDelayed({ injectShellCss() }, 900)
                root.postDelayed({ runDemoJs() }, 1400)
            }
        }
    }

    private fun runDemoJs() {
        val f = File(cfgDir(), "demo.js")
        if (!f.exists() || f.length() < 8) return
        try {
            val js = f.readText()
            web.evaluateJavascript(js, android.webkit.ValueCallback<String> { v ->
                Log.i(TAG, "demo.js -> " + v)
            })
        } catch (t: Throwable) {
            Log.e(TAG, "demo.js 失败", t)
        }
    }

    private fun injectShellCss() {
        val d = resources.displayMetrics.density
        val topPad = ((insetTop + dp(44) + dp(24)) / d).toInt()
        val botPad = ((insetBottom + dp(56) + dp(26)) / d).toInt()
        val key = topPad * 1000 + botPad
        if (key == lastPad) return
        lastPad = key
        val css = "#mhead,#inwrap,#inputzone,#sendbtn{display:none!important}" +
                "#chat{padding-bottom:${botPad}px!important}" +
                "#chatinner{padding-top:${topPad}px!important}" +
                "html,body{background:#F7F7F8!important}"
        val js = "(function(){var s=document.getElementById('ran-shell-css');" +
                "if(!s){s=document.createElement('style');s.id='ran-shell-css';" +
                "document.head.appendChild(s);}s.textContent=" + jsStr(css) + ";})()"
        web.evaluateJavascript(js, null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
