package com.ran.agent

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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
import com.example.liquidglass.ScrollEdgeBlurView
import org.json.JSONArray
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
    private lateinit var webWrap: FrameLayout
    private lateinit var edgeTop: ScrollEdgeBlurView
    private lateinit var edgeBottom: ScrollEdgeBlurView
    private lateinit var shadowRing: ShadowRingView
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
        "blurAmount" to 0.30f, "saturation" to 110f, "milk" to 0.93f,
        "corner" to 22f, "islandCorner" to 28f,
        "edgeBlur" to 12f, "edgeTopH" to 96f, "edgeBotH" to 132f,
        "shadowAlpha" to 34f, "shadowBlur" to 14f, "shadowDy" to 5f,
        "enableAdaptiveTint" to 1f, "enableSensorHighlight" to 1f,
        "enableEdgeHighlight" to 1f, "enableShadow" to 0f,
        "sideScrim" to 17f, "sideMilkBoost" to 0.08f,
        "probe" to 0f
    )
    private val DP_KEYS = setOf("bevelWidth", "refractionHeight", "corner", "islandCorner")
    /** 这些键由外壳自己消费，不反射到玻璃视图上 */
    private val LOCAL_KEYS = setOf(
        "probe", "milk", "edgeBlur", "edgeTopH", "edgeBotH",
        "shadowAlpha", "shadowBlur", "shadowDy", "sideScrim", "sideMilkBoost")
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
        // webWrap：只装 WebView + 上下渐进模糊条。玻璃挂 root、不在这一层里，
        // 所以模糊条捕获不到玻璃自己，不会把玻璃糊成一片。
        webWrap = FrameLayout(this)
        root.addView(webWrap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        web = WebView(this)
        webWrap.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        try {
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } catch (_: Throwable) {}
        setupWeb()

        // Scroll Edge Effect：内容滚到顶/底时从清晰渐变到模糊，而不是一条硬边
        edgeTop = ScrollEdgeBlurView(this)
        edgeTop.edge = ScrollEdgeBlurView.Edge.TOP
        webWrap.addView(edgeTop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        edgeBottom = ScrollEdgeBlurView(this)
        edgeBottom.edge = ScrollEdgeBlurView.Edge.BOTTOM
        webWrap.addView(edgeBottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))

        // ② 四块玻璃直接挂在 root（与 WebView 同父），必须写在 WebView 之后
        gBurger = glassBox(dp(22f)) { icon("\u2630") { openSidebar() } }
        gPill = glassBox(dp(22f)) {
            TextView(this).apply {
                text = "Orion"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(0xFF1A1A1C.toInt())
            }
        }
        gPlus = glassBox(dp(22f)) { icon("\uFF0B") { clickWeb("mnew") } }
        gDots = glassBox(dp(22f)) { icon("\u22EF") { openDotsPanel() } }

        // ③ 输入岛
        island = buildIsland()
        root.addView(island, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(H_COLLAPSE), Gravity.BOTTOM))

        // 阴影环：画在玻璃之上、但只在玻璃形状之外（内部 CLEAR 掉），
        // 因此看起来像"玻璃下方投影"，又不会污染玻璃的背景采样。
        shadowRing = ShadowRingView(this)
        root.addView(shadowRing, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        applyGlassParams()
        buildTuner()

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

        try {
            web.addJavascriptInterface(object : Any() {
                @android.webkit.JavascriptInterface
                fun edgePing() { runOnUiThread { invalidateEdges() } }
            }, "RAN")
        } catch (t: Throwable) { Log.w(TAG, "js interface", t) }

        try {
            web.setOnScrollChangeListener { _, _, _, _, _ -> invalidateEdges() }
        } catch (_: Throwable) {}

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
            if (k in LOCAL_KEYS) continue
            when (k) {
                "corner" -> for (t in top) setProp(t, "cornerRadius", v * d)
                "islandCorner" -> setProp(island, "cornerRadius", v * d)
                "material" -> for (t in all) (t as LiquidGlassView).material =
                    if (v > 0.5f) GlassMaterial.REGULAR else GlassMaterial.CLEAR
                else -> {
                    val raw = if (k in DP_KEYS) v * d else v
                    for (t in all) setProp(t, k, raw)
                }
            }
        }
        for ((k, v) in cfgInt) {
            if (k == "glassTint") for (t in all) setIntProp(t, "glassTint", v)
        }
        if (islandExpanded) setProp(island, "cornerRadius", dp(26f))
        applyMilk()
        applyEdgeBlur()
        applyShadow()
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

    /* ================= 调参面板 ================= */

    private lateinit var tunerHandle: TextView
    private lateinit var tunerPanel: View
    private var tunerOpen = false

    private fun buildTuner() {
        val h = TextView(this)
        h.text = "\u2699"
        h.textSize = 20f
        h.gravity = Gravity.CENTER
        h.setTextColor(Color.WHITE)
        h.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE61A1A1C.toInt())
        }
        h.elevation = dp(8f)
        val lp = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)
        lp.rightMargin = dp(6)
        root.addView(h, lp)
        tunerHandle = h

        // 拖动 + 点击（移动超过阈值算拖拽，否则算点击开关面板）
        var dx = 0f; var dy = 0f; var moved = false
        h.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX - v.x; dy = e.rawY - v.y; moved = false; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - v.x - dx) > 12 || Math.abs(e.rawY - v.y - dy) > 12) moved = true
                    if (moved) {
                        v.x = (e.rawX - dx).coerceIn(0f, (root.width - v.width).toFloat())
                        v.y = (e.rawY - dy).coerceIn(0f, (root.height - v.height).toFloat())
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) toggleTuner()
                    true
                }
                else -> false
            }
        }

        // 外层：固定标题栏 + 可滚动内容（保证「×」永远可见，不会滚出视野）
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.background = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(0xF7FFFFFF.toInt())
        }
        container.elevation = dp(16f)
        container.visibility = View.GONE
        container.setPadding(dp(14), dp(8), dp(14), dp(4))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "\u73bb\u7483\u53c2\u6570"
        title.textSize = 15f
        title.setTextColor(0xFF1A1A1C.toInt())
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(this)
        close.text = "\u00d7"
        close.textSize = 20f
        close.gravity = Gravity.CENTER
        close.setTextColor(0xFF55555C.toInt())
        close.setPadding(dp(10), 0, dp(4), dp(4))
        close.setOnClickListener { toggleTuner() }
        header.addView(close)
        container.addView(header)

        val sv = android.widget.ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(2), 0, dp(8))
        sv.addView(col)
        container.addView(sv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addSlider(col, "\u767d\u5ea6 milk", "milk", 0f, 1f)
        addSlider(col, "\u6a21\u7cca blur", "blurAmount", 0f, 2.5f)
        addSlider(col, "\u6298\u5c04 depth", "refractionHeight", 0f, 60f)
        addSlider(col, "\u8fb9\u7f18\u5e26 bevel", "bevelWidth", 0f, 40f)
        addSlider(col, "\u8272\u6563 disp", "dispersionStrength", 0f, 0.4f)
        addSlider(col, "\u5706\u89d2 corner", "corner", 0f, 34f)
        addSlider(col, "\u9971\u548c sat", "saturation", 60f, 200f)
        addSlider(col, "\u9634\u5f71 shadow", "shadowAlpha", 0f, 120f)
        addSlider(col, "\u4e0a\u4e0b\u6a21\u7cca edge", "edgeBlur", 0f, 30f)

        col.addView(sectionLabel("\u5e95\u666f"))
        val brow = LinearLayout(this)
        brow.orientation = LinearLayout.HORIZONTAL
        brow.addView(chip("\u767d\u5e95\u6587\u5b57") { applyBackdrop(1) })
        brow.addView(chip("\u5f69\u8272") { applyBackdrop(2) })
        brow.addView(chip("\u771f\u5b9e\u9875") { applyBackdrop(0) })
        col.addView(brow)

        col.addView(sectionLabel("\u7ba1\u7ebf / \u5c42"))
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(chip("\u900f\u955c") {
            cfg["useShaderPipeline"] = 1f
            applyGlassParams(); saveCfg()
        })
        row2.addView(chip("\u7ecf\u5178") {
            cfg["useShaderPipeline"] = 0f
            applyGlassParams(); saveCfg()
        })
        row2.addView(chip("\u6295\u5f71") {
            cfg["enableShadow"] = if ((cfg["enableShadow"] ?: 0f) > 0.5f) 0f else 1f
            applyGlassParams(); saveCfg()
        })
        col.addView(row2)

        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.setPadding(0, dp(10), 0, 0)
        foot.addView(chip("\u4fdd\u5b58") { saveCfg(); toast("\u5df2\u4fdd\u5b58") })
        foot.addView(chip("\u590d\u4f4d") { loadCfg(); applyGlassParams(); saveCfg() })
        foot.addView(chip("\u6536\u8d77") { toggleTuner() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(foot)

        val screenH = resources.displayMetrics.heightPixels
        val panelH = minOf(dp(600), screenH - dp(70) - dp(120))
        val plp = FrameLayout.LayoutParams(dp(330), panelH, Gravity.END or Gravity.TOP)
        plp.rightMargin = dp(8)
        plp.topMargin = dp(70)
        root.addView(container, plp)
        tunerPanel = container
    }

    private fun toggleTuner() {
        tunerOpen = !tunerOpen
        tunerPanel.visibility = if (tunerOpen) View.VISIBLE else View.GONE
        tunerHandle.text = if (tunerOpen) "\u00d7" else "\u2699"
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        setTextColor(0xFF8A8A90.toInt())
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun fmt(v: Float) = String.format("%.2f", v)

    private fun addSlider(col: LinearLayout, label: String, key: String, min: Float, max: Float) {
        val def = CFG_DEFAULTS[key] ?: min
        val cur = cfg[key] ?: def
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        val tv = TextView(this)
        tv.textSize = 12f
        tv.setTextColor(0xFF1A1A1C.toInt())
        tv.text = label + "  " + fmt(cur)
        row.addView(tv)
        val sb = android.widget.SeekBar(this)
        sb.max = 1000
        sb.progress = (((cur - min) / (max - min)) * 1000f).toInt().coerceIn(0, 1000)
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: android.widget.SeekBar?, pr: Int, user: Boolean) {
                val v = min + (max - min) * pr / 1000f
                cfg[key] = v
                tv.text = label + "  " + fmt(v)
                if (user) applyGlassParams()
            }
            override fun onStartTrackingTouch(b: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(b: android.widget.SeekBar?) { saveCfg() }
        })
        row.addView(sb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        col.addView(row)
    }

    private fun chip(t: String, onclick: () -> Unit): TextView {
        return TextView(this).apply {
            text = t
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(0xFFEDEDF0.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(6)
                topMargin = dp(4)
            }
            setOnClickListener { onclick() }
        }
    }

    private fun toast(t: String) {
        android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 底景：0=真实页面 1=白底密集文字 2=彩色渐变条纹 */
    private fun applyBackdrop(kind: Int) {
        val js = when (kind) {
            0 -> "(function(){var o=document.getElementById('ran-demo');if(o)o.remove();" +
                 "var s=document.getElementById('ran-demo-style');if(s)s.remove();return 'real'})()"
            1 -> """(function(){var o=document.getElementById('ran-demo');if(o)o.remove();
var S=document.getElementById('ran-demo-style');if(!S){S=document.createElement('style');S.id='ran-demo-style';document.head.appendChild(S);}
S.textContent='html,body{background:#F2F2F4!important}';
var b=document.createElement('div');b.id='ran-demo';
b.style.cssText='position:fixed;left:0;right:0;top:0;bottom:0;z-index:9999;pointer-events:none;overflow:hidden;background:#F2F2F4;font:400 15px/1.55 -apple-system,sans-serif;color:#15171b;';
var L=['这是一条消息用来检验玻璃下的文字是否被正确处理','The quick brown fox jumps 0123','液态玻璃在白底上要靠描边与投影立住','边缘折射带要窄内部要雾化'];
var h='';for(var i=0;i<34;i++){h+='<div style="position:absolute;left:16px;right:16px;top:'+(6+i*44)+'px;white-space:nowrap;overflow:hidden;">'+L[i%4]+'</div>';}
b.innerHTML=h;document.body.appendChild(b);return 'text'})()"""
            else -> """(function(){var o=document.getElementById('ran-demo');if(o)o.remove();
var S=document.getElementById('ran-demo-style');if(!S){S=document.createElement('style');S.id='ran-demo-style';document.head.appendChild(S);}
S.textContent='html,body{background:#F2F2F4!important}';
var b=document.createElement('div');b.id='ran-demo';
b.style.cssText='position:fixed;left:0;right:0;top:0;bottom:0;z-index:9999;pointer-events:none;overflow:hidden;';
var h='';
h+='<div style="position:absolute;left:0;right:0;top:0;height:620px;background:linear-gradient(100deg,#ff2d55,#ff9500,#ffe600,#34c759,#00c7be,#0a84ff,#5e5ce6);"></div>';
for(var i=0;i<18;i++){h+='<div style="position:absolute;left:'+(i*80)+'px;top:300px;width:40px;height:320px;background:#111;"></div>';}
h+='<div style="position:absolute;left:0;right:0;top:700px;height:800px;background:#F2F2F4;"></div>';
h+='<div style="position:absolute;left:0;right:0;top:1500px;height:1700px;background:linear-gradient(60deg,#5e5ce6,#0a84ff,#00c7be,#34c759,#ffcc00,#ff3b30,#ff2d55);"></div>';
b.innerHTML=h;document.body.appendChild(b);return 'color'})()"""
        }
        web.evaluateJavascript(js, null)
    }

    /** 把所有参数写回设备上的 glass.cfg，重启后仍生效，也便于我读取你的最终值 */
    private fun saveCfg() {
        try {
            val sb = StringBuilder()
            sb.append("# saved by tuner ").append(System.currentTimeMillis()).append("\n")
            for ((k, v) in cfg) sb.append(k).append('=').append(v).append('\n')
            for ((k, v) in cfgInt) sb.append(k).append("=0x")
                .append(Integer.toHexString(v).uppercase()).append('\n')
            File(cfgDir(), "glass.cfg").writeText(sb.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "saveCfg 失败", t)
        }
    }

    /** 上下渐进模糊：条带高度与最大模糊半径都由 cfg 控制，"别开太大"就调小这两项 */
    private fun applyEdgeBlur() {
        if (!this::edgeTop.isInitialized) return
        val d = resources.displayMetrics.density
        val r = (cfg["edgeBlur"] ?: 12f) * d
        try {
            edgeTop.maxBlurRadius = r
            edgeBottom.maxBlurRadius = r
        } catch (t: Throwable) {
            Log.w(TAG, "maxBlurRadius 写入失败: " + t.javaClass.simpleName)
        }
        edgeTop.visibility = if (r > 0.5f) View.VISIBLE else View.GONE
        edgeBottom.visibility = if (r > 0.5f) View.VISIBLE else View.GONE
    }

    /** 阴影参数变化 → 重画阴影片 */
    private fun applyShadow() {
        if (!this::shadowRing.isInitialized) return
        val d = resources.displayMetrics.density
        shadowRing.alphaPx = (cfg["shadowAlpha"] ?: 34f).toInt()
        shadowRing.blurPx = (cfg["shadowBlur"] ?: 14f) * d
        shadowRing.dyPx = (cfg["shadowDy"] ?: 5f) * d
        shadowRing.invalidateShadow()
    }

    private fun invalidateEdges() {
        if (this::edgeTop.isInitialized) { edgeTop.invalidate(); edgeBottom.invalidate() }
    }

    /**
     * 阴影环：为每块玻璃在"形状之外"画一圈柔和投影。
     * 做法：saveLayer → 画白色圆角矩形并附 setShadowLayer（阴影是矩形外扩的模糊）→
     * 用 CLEAR 把矩形内部挖掉 → 只剩外部阴影。因为内部被挖空，
     * 不会盖住玻璃、也不会进入玻璃的背景采样。
     */
    inner class ShadowRingView(ctx: Context) : View(ctx) {
        var alphaPx = 34
        var blurPx = 40f
        var dyPx = 14f

        private val cache = HashMap<Int, Bitmap>()
        private val pad: Int get() = (blurPx * 2f).toInt() + dp(10)

        init {
            // 只在独立位图上做阴影 + 遮罩，不依赖 View 画布的 saveLayer/CLEAR
            isClickable = false
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            val targets = ArrayList<View>()
            if (this@MainActivity::gBurger.isInitialized && this@MainActivity::island.isInitialized) {
                targets.add(gBurger); targets.add(gPill); targets.add(gPlus)
                targets.add(gDots); targets.add(island)
            }
            // 库的 BackdropLuminanceMeter 会把父视图缩绘成 24x24 估亮度，
            // 那次会被动触发本 View 的 onDraw —— 在缩略图上画阴影毫无意义且浪费，直接跳过。
            if (canvas.width < 100 || canvas.height < 100) return
            if (alphaPx <= 0 || blurPx <= 0f) return
            for (v in targets) {
                // 注意：不能看 visibility —— 玻璃在录制背景时会被库临时设为 INVISIBLE，
                // 而真实绘制恰好也会经过这个状态。只按几何判断。
                if (v.width <= 0 || v.height <= 0) continue
                val corner = if (v is LiquidGlassView) v.cornerRadius else 0f
                val key = (v.width * 31 + v.height) * 31 + corner.toInt()
                val bmp = cache[key] ?: render(v.width, v.height, corner)?.also { cache[key] = it }
                if (bmp == null) continue
                canvas.drawBitmap(bmp, (v.left - pad).toFloat(), (v.top - pad).toFloat(), null)
            }
        }

        /** 在独立位图上：画带阴影的圆角矩形 → 把矩形内部挖空 → 只剩形状外的投影 */
        private fun render(w: Int, h: Int, corner: Float): Bitmap? {
            val pd = pad
            val bw = w + pd * 2
            val bh = h + pd * 2
            if (bw <= 0 || bh <= 0 || bw > 4096 || bh > 4096) return null
            return try {
                val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val r = RectF(pd.toFloat(), pd.toFloat(), (pd + w).toFloat(), (pd + h).toFloat())
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.WHITE
                p.setShadowLayer(blurPx, 0f, dyPx, Color.argb(alphaPx, 0, 0, 0))
                c.drawRoundRect(r, corner, corner, p)
                // 位图上的 CLEAR 是可靠的：整块 alpha 归零
                val clr = Paint(Paint.ANTI_ALIAS_FLAG)
                clr.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                c.drawRoundRect(r, corner, corner, clr)
                bmp
            } catch (t: Throwable) {
                Log.e(TAG, "阴影位图生成失败", t)
                null
            }
        }

        fun invalidateShadow() {
            cache.clear()
            invalidate()
        }
    }

    /* ================= 原生浮层：玻璃面板 + 网页桥 ================= */

    class MenuItem(val title: String, val isGroup: Boolean = false, val onClick: (() -> Unit)? = null)

    private var menuLayer: FrameLayout? = null
    private var menuGlass: LiquidGlassView? = null

    /**
     * 读网页里的值并回传。用 base64 传 JSON，避开 evaluateJavascript 的转义地狱。
     */
    private fun evalJson(expr: String, cb: (String) -> Unit) {
        val wrap = "(function(){try{var v=($expr);" +
                "return btoa(unescape(encodeURIComponent(JSON.stringify(v))))}catch(e){return ''}})()"
        web.evaluateJavascript(wrap) { r ->
            val raw = (r ?: "").trim().trim('"')
            val out = try {
                String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } catch (t: Throwable) { "" }
            cb(out)
        }
    }

    private fun dismissMenu() {
        menuLayer?.let { root.removeView(it) }
        menuLayer = null
        menuGlass = null
    }

    /**
     * 原生玻璃浮层。外观是原生液态玻璃，内容项的点击一律转调网页原函数，
     * 所以功能逻辑零重写、不会退化。
     */
    private fun showGlassMenu(anchor: View, items: List<MenuItem>, above: Boolean) {
        dismissMenu()
        val layer = FrameLayout(this)
        val scrim = View(this)
        scrim.setOnClickListener { dismissMenu() }
        layer.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(7), dp(7), dp(7), dp(7))
        for (item in items) {
            if (item.isGroup) {
                val h = TextView(this)
                h.text = item.title
                h.textSize = 11.5f
                h.setTextColor(0xFF8A8A90.toInt())
                h.setPadding(dp(11), dp(9), dp(11), dp(3))
                col.addView(h)
            } else {
                val tv = TextView(this)
                tv.text = item.title
                tv.textSize = 15f
                tv.setTextColor(0xFF1A1A1C.toInt())
                tv.setPadding(dp(11), dp(11), dp(11), dp(11))
                tv.isClickable = true
                val bg = GradientDrawable()
                bg.cornerRadius = dp(12f)
                bg.setColor(0x00FFFFFF)
                tv.background = bg
                val act = item.onClick
                tv.setOnClickListener {
                    dismissMenu()
                    act?.invoke()
                }
                col.addView(tv)
            }
        }

        val glass = LiquidGlassView(this)
        glass.cornerRadius = dp(20f)
        glass.useShaderPipeline = true
        glass.material = GlassMaterial.REGULAR
        glass.enableDynamicBackground = true
        glass.enableEdgeHighlight = true
        glass.enableShadow = false
        glass.clipChildren = false
        // 白罩显式设置：不依赖 applyMilk 的时机，避免菜单玻璃偏灰
        val milkA = ((cfg["milk"] ?: 0.84f).coerceIn(0f, 1f) * 255).toInt()
        val mv = View(this)
        mv.background = GradientDrawable().apply {
            cornerRadius = dp(20f)
            setColor((milkA shl 24) or 0xFFFFFF)
        }
        glass.addView(mv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuGlass = glass
        // 滚动容器：模型多时不至于超出屏幕
        val sv = android.widget.ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(col)
        glass.addView(sv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = FrameLayout.LayoutParams(dp(236), ViewGroup.LayoutParams.WRAP_CONTENT)
        layer.addView(glass, lp)
        layer.elevation = dp(90f)
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menuLayer = layer

        // 布局完成后再定位，否则拿不到测量高度
        glass.post {
            val p = glass.layoutParams as FrameLayout.LayoutParams
            p.width = dp(236)
            // 手动测量内容：FrameLayout 里 MATCH_PARENT 的子视图会把 wrap_content 撑满整屏
            col.measure(
                View.MeasureSpec.makeMeasureSpec(dp(236), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            p.height = minOf(col.measuredHeight, maxH).coerceAtLeast(dp(48))
            if (above) {
                p.gravity = Gravity.BOTTOM or Gravity.END
                p.bottomMargin = maxOf(dp(10), root.height - anchor.top + dp(8))
                p.rightMargin = dp(10)
            } else {
                p.gravity = Gravity.TOP or Gravity.END
                p.topMargin = anchor.bottom + dp(6)
                p.rightMargin = dp(10)
            }
            glass.layoutParams = p
            applyMilk()
        }
    }

    /* ================= 原生侧栏（P1） ================= */

    class SessItem(val sid: String, val title: String, val pin: Boolean, val dot: Boolean, val cur: Boolean)

    private var sideLayer: FrameLayout? = null
    private var sideListBox: LinearLayout? = null
    private var sideSearch: EditText? = null
    private var sideUserLabel: TextView? = null
    private var sideSess: List<SessItem> = emptyList()

    private fun closeSidebar() {
        sideLayer?.let { root.removeView(it) }
        sideLayer = null
        sideListBox = null
        sideSearch = null
        sideUserLabel = null
    }

    private fun pill(bg: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius.toFloat())
            setColor(bg)
        }

    private fun roundBtn(txt: String): TextView {
        val tv = TextView(this)
        tv.text = txt
        tv.textSize = 15f
        tv.setTextColor(0xFF3C3C42.toInt())
        tv.gravity = Gravity.CENTER
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x14000000)
        }
        tv.isClickable = true
        return tv
    }

    /** 原生侧栏：外观全原生玻璃，点击一律转调网页原函数 */
    private fun openSidebar() {
        closeSidebar()
        val layer = FrameLayout(this)

        val scrim = View(this)
        val sa = ((cfg["sideScrim"] ?: 17f).coerceIn(0f, 60f) / 100f * 255).toInt()
        scrim.setBackgroundColor((sa shl 24))
        scrim.isClickable = true
        scrim.setOnClickListener { closeSidebar() }
        layer.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val glass = LiquidGlassView(this)
        glass.cornerRadius = dp(28f)
        glass.useShaderPipeline = true
        glass.material = GlassMaterial.REGULAR
        glass.enableDynamicBackground = true
        glass.enableEdgeHighlight = true
        glass.enableShadow = false
        glass.clipChildren = false

        // 侧栏比按钮再白一档：大面积面板需要更实的底，否则会显灰
        val boost = cfg["sideMilkBoost"] ?: 0.08f
        val milkA = ((cfg["milk"] ?: 0.84f) + boost).coerceIn(0f, 1f).let { (it * 255).toInt() }
        val mv = View(this)
        mv.background = GradientDrawable().apply {
            cornerRadius = dp(28f)
            setColor((milkA shl 24) or 0xFFFFFF)
        }
        glass.addView(mv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(12), dp(14), dp(12))

        // 标题行
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "RAN Agent"
        title.textSize = 17f
        title.setTextColor(0xFF1A1A1C.toInt())
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dotsB = roundBtn("\u22EF")
        dotsB.setOnClickListener { closeSidebar(); openDotsPanel() }
        head.addView(dotsB, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(6) })
        val closeB = roundBtn("\u00D7")
        closeB.setOnClickListener { closeSidebar() }
        head.addView(closeB, LinearLayout.LayoutParams(dp(34), dp(34)))
        col.addView(head)

        // 用户行
        val userRow = LinearLayout(this)
        userRow.orientation = LinearLayout.HORIZONTAL
        userRow.gravity = Gravity.CENTER_VERTICAL
        userRow.setPadding(dp(4), dp(12), dp(4), dp(8))
        userRow.isClickable = true
        userRow.setOnClickListener { closeSidebar(); evalJs("profileClick()") }
        val av = TextView(this)
        av.text = "R"
        av.textSize = 15f
        av.gravity = Gravity.CENTER
        av.setTextColor(0xFF77777D.toInt())
        av.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFDCDCE0.toInt())
        }
        userRow.addView(av, LinearLayout.LayoutParams(dp(34), dp(34)))
        val un = TextView(this)
        un.text = "未登录"
        un.textSize = 15f
        un.setTextColor(0xFF1A1A1C.toInt())
        un.setPadding(dp(10), 0, 0, 0)
        userRow.addView(un, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sideUserLabel = un
        col.addView(userRow)

        // 导航 chips
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        for ((label, call) in listOf(
            "记忆" to "profileClick('mem')", "知识库" to "profileClick('kb')",
            "收藏" to "profileClick('fav')", "任务" to "openTasks()"
        )) {
            val c = TextView(this)
            c.text = label
            c.textSize = 12.5f
            c.setTextColor(0xFF3C3C42.toInt())
            c.gravity = Gravity.CENTER
            c.setPadding(dp(11), dp(6), dp(11), dp(6))
            c.background = pill(0x14000000, 10)
            c.isClickable = true
            c.setOnClickListener { closeSidebar(); evalJs(call) }
            nav.addView(c, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }
        col.addView(nav)

        // 新对话
        val nb = TextView(this)
        nb.text = "+  新对话"
        nb.textSize = 14.5f
        nb.setTextColor(0xFF1A1A1C.toInt())
        nb.gravity = Gravity.CENTER
        nb.setPadding(0, dp(10), 0, dp(10))
        nb.background = pill(0x1F000000, 12)
        nb.isClickable = true
        nb.setOnClickListener { closeSidebar(); evalJs("newChat()") }
        col.addView(nb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // 搜索框（原生，本地过滤，零网络延迟）
        val se = EditText(this)
        se.hint = "搜索对话"
        se.textSize = 14f
        se.setSingleLine(true)
        se.setTextColor(0xFF1A1A1C.toInt())
        se.setHintTextColor(0xFF9A9AA0.toInt())
        se.setPadding(dp(12), dp(8), dp(12), dp(8))
        se.background = pill(0x14000000, 12)
        se.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { renderSideSess(e?.toString() ?: "") }
            override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        sideSearch = se
        col.addView(se, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // 会话列表
        val sv = android.widget.ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        sv.addView(box)
        sideListBox = box
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { topMargin = dp(8) })

        glass.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val lp = FrameLayout.LayoutParams(dp(316), ViewGroup.LayoutParams.MATCH_PARENT)
        lp.gravity = Gravity.START
        lp.leftMargin = dp(8)
        lp.topMargin = insetTop + dp(6)
        lp.bottomMargin = insetBottom + dp(6)
        layer.addView(glass, lp)

        // 右缘投影：让侧栏明确浮在内容之上，而不是与背景贴在一起
        val sh = View(this)
        sh.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x2A000000, 0x00000000)
        )
        val shp = FrameLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT)
        shp.gravity = Gravity.START
        shp.leftMargin = dp(8) + dp(316)
        shp.topMargin = insetTop + dp(6)
        shp.bottomMargin = insetBottom + dp(6)
        layer.addView(sh, shp)

        // 高层级：顶栏玻璃按钮与调参悬浮球（elevation=8dp）都会按 elevation 排序绘制，
        // 不抬高的话它们会浮在遮罩之上 —— 侧栏打开时按钮不"退后"，层次就乱了。
        layer.elevation = dp(100f)
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sideLayer = layer
        refreshSidebar()
    }

    /** 从网页读用户与会话列表 */
    private fun refreshSidebar() {
        evalJson("({user:(typeof ME!=='undefined'&&ME?ME:''),sid:(typeof sid!=='undefined'?sid:'')," +
                "sess:(typeof sessCache!=='undefined'?sessCache:[]).map(function(s){" +
                "return {sid:s.sid,title:s.title||'',pin:!!s.pin,dot:!!s.dot}})})") { raw ->
            runOnUiThread {
                if (sideLayer == null) return@runOnUiThread
                try {
                    val o = org.json.JSONObject(raw)
                    val user = o.optString("user", "")
                    sideUserLabel?.text = if (user.isBlank()) "未登录" else user
                    val cur = o.optString("sid", "")
                    val arr = o.optJSONArray("sess") ?: JSONArray()
                    val list = ArrayList<SessItem>()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val id = e.optString("sid")
                        if (id.isBlank()) continue
                        list.add(SessItem(id, e.optString("title"), e.optBoolean("pin"),
                            e.optBoolean("dot"), id == cur))
                    }
                    sideSess = list
                    renderSideSess(sideSearch?.text?.toString() ?: "")
                } catch (t: Throwable) {
                    Log.w(TAG, "侧栏数据解析失败", t)
                }
            }
        }
    }

    private fun sideRefreshLater() {
        root.postDelayed({ if (sideLayer != null) refreshSidebar() }, 600)
    }

    private fun renderSideSess(kw: String) {
        val box = sideListBox ?: return
        box.removeAllViews()
        val k = kw.trim().lowercase()
        var n = 0
        for (si in sideSess) {
            if (k.isNotEmpty() && !si.title.lowercase().contains(k)) continue
            n++
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(11), dp(10), dp(11), dp(10))
            row.background = pill(if (si.cur) 0x1F000000 else 0x00000000, 11)
            val t = TextView(this)
            t.text = if (si.title.isBlank()) "新对话" else si.title
            t.textSize = 13.5f
            t.setTextColor(if (si.cur) 0xFF1A1A1C.toInt() else 0xFF4A4A50.toInt())
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            if (si.cur) t.typeface = android.graphics.Typeface.DEFAULT_BOLD
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (si.pin) {
                val p = TextView(this)
                p.text = "\u2605"
                p.textSize = 11f
                p.setTextColor(0xFFB0B0B6.toInt())
                p.setPadding(dp(6), 0, 0, 0)
                row.addView(p)
            }
            if (si.dot) {
                val d = View(this)
                d.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF5B8DEF.toInt())
                }
                row.addView(d, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(6) })
            }
            row.isClickable = true
            row.setOnClickListener { pickSession(si.sid) }
            row.setOnLongClickListener { showSessMenu(row, si); true }
            box.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(2) })
        }
        if (n == 0) {
            val e = TextView(this)
            e.text = if (k.isEmpty()) "暂无对话" else "无匹配"
            e.textSize = 13f
            e.setTextColor(0xFF9A9AA0.toInt())
            e.gravity = Gravity.CENTER
            e.setPadding(0, dp(18), 0, dp(18))
            box.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun pickSession(sidv: String) {
        closeSidebar()
        evalJs("(function(){try{localStorage.setItem('orion_sid'," + jsStr(sidv) + ");" +
                "sid=" + jsStr(sidv) + ";closeMenu();markRead(" + jsStr(sidv) + ");" +
                "loadChat();refreshList();}catch(e){}})()")
    }

    private fun showSessMenu(anchor: View, s: SessItem) {
        showGlassMenu(anchor, listOf(
            MenuItem(if (s.pin) "取消置顶" else "置顶") {
                evalJs("fetch('/session/pin',{method:'POST',headers:{'Content-Type':'application/json'}," +
                        "body:JSON.stringify({sid:" + jsStr(s.sid) + ",pin:" + (!s.pin) + "})})" +
                        ".then(function(){refreshList()})")
                sideRefreshLater()
            },
            MenuItem("重命名") { renameSession(s) },
            MenuItem("删除") { confirmDeleteSession(s) }
        ), false)
    }

    private fun renameSession(s: SessItem) {
        val ed = EditText(this)
        ed.setText(s.title)
        ed.setSelection(ed.text.length)
        android.app.AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(ed)
            .setPositiveButton("确定") { _, _ ->
                val nt = ed.text.toString().trim()
                if (nt.isNotEmpty()) {
                    evalJs("fetch('/session/rename',{method:'POST',headers:{'Content-Type':'application/json'}," +
                            "body:JSON.stringify({sid:" + jsStr(s.sid) + ",title:" + jsStr(nt) + "})})" +
                            ".then(function(){refreshList()})")
                    sideRefreshLater()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteSession(s: SessItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除对话")
            .setMessage("删除「" + s.title + "」？")
            .setPositiveButton("删除") { _, _ ->
                evalJs("fetch('/session/delete',{method:'POST',headers:{'Content-Type':'application/json'}," +
                        "body:JSON.stringify({sid:" + jsStr(s.sid) + "})})" +
                        ".then(function(){refreshList()})")
                sideRefreshLater()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 附件菜单：对应网页 #pluspanel（拍照 / 相册 / 文件），点击转调网页 pickFile */
    private fun openPlusPanel() {
        evalJs("closeFloatPanels('')")
        showGlassMenu(island, listOf(
            MenuItem("拍照") { evalJs("pickFile(true,true)") },
            MenuItem("相册") { evalJs("pickFile(false,true)") },
            MenuItem("文件") { evalJs("pickFile(false,false)") }
        ), true)
    }

    /** ⋯ 菜单：读网页全局 menuCache，模型项点击走 /setmodel + setModelLabel */
    private fun openDotsPanel() {
        evalJs("closeFloatPanels('')")
        evalJson("({menu:(typeof menuCache!=='undefined'?menuCache:[])})") { raw ->
            runOnUiThread {
                val items = ArrayList<MenuItem>()
                items.add(MenuItem("随身备忘") { evalJs("toggleMemo()") })
                items.add(MenuItem("本会话文件") { evalJs("openSessFiles()") })
                // 解析结构：{menu:[{group,models:[{id,name}]}]}
                try {
                    val o = org.json.JSONObject(raw)
                    val menu = o.optJSONArray("menu")
                    if (menu != null) {
                        for (gi in 0 until menu.length()) {
                            val g = menu.optJSONObject(gi) ?: continue
                            items.add(MenuItem(g.optString("group"), isGroup = true))
                            val ms = g.optJSONArray("models") ?: continue
                            for (mi in 0 until ms.length()) {
                                val m = ms.optJSONObject(mi) ?: continue
                                val id = m.optString("id")
                                val nm = m.optString("name", id)
                                if (id.isBlank()) continue
                                items.add(MenuItem(nm) {
                                    evalJs("(async function(){try{" +
                                            "await fetch('/setmodel',{method:'POST',headers:" +
                                            "{'Content-Type':'application/json'}," +
                                            "body:JSON.stringify({id:" + jsStr(id) + "})});" +
                                            "setModelLabel(" + jsStr(nm) + ");" +
                                            "if(typeof menuCache!=='undefined'){for(var i=0;i<menuCache.length;i++){" +
                                            "var ms=menuCache[i].models||[];for(var j=0;j<ms.length;j++){" +
                                            "if(ms[j].id===" + jsStr(id) + "){ms[j].cur=1;}}}}" +
                                            "}catch(e){}})()")
                                })
                            }
                        }
                    }
                } catch (_: Throwable) {}
                showGlassMenu(gDots, items, false)
            }
        }
    }

    private fun evalJs(js: String) {
        web.evaluateJavascript(js, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sideLayer != null) { closeSidebar(); return }
        if (menuLayer != null) { dismissMenu(); return }
        super.onBackPressed()
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
        if (this::shadowRing.isInitialized) shadowRing.invalidate()
        val pillL = left0 + side + gap
        val pillR = w - right0 - side * 2 - gap * 2
        place(gPill, pillL, top, (pillR - pillL).coerceAtLeast(dp(80)))
    }

    private fun applyLayout() {
        layoutTopBar()
        if (this::edgeTop.isInitialized) {
            val th = dp((cfg["edgeTopH"] ?: 96f).toInt()) + insetTop
            val bh = dp((cfg["edgeBotH"] ?: 132f).toInt()) + insetBottom
            edgeTop.layoutParams = (edgeTop.layoutParams as FrameLayout.LayoutParams).apply {
                height = th; gravity = Gravity.TOP
            }
            edgeBottom.layoutParams = (edgeBottom.layoutParams as FrameLayout.LayoutParams).apply {
                height = bh; gravity = Gravity.BOTTOM
            }
            edgeTop.requestLayout(); edgeBottom.requestLayout()
            invalidateEdges()
        }
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
        plus.setOnClickListener { openPlusPanel() }
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
                injectEdgePing()
                root.postDelayed({ injectEdgePing() }, 900)
                root.postDelayed({ runDemoJs() }, 1400)
            }
        }
    }

    /** 页面滚动时通知原生重新捕获，否则模糊条会停在第一帧 */
    private fun injectEdgePing() {
        val js = "(function(){if(window.__ranEdgePing)return 1;window.__ranEdgePing=1;" +
                "var q=0;function ping(){if(q)return;q=1;requestAnimationFrame(function(){q=0;" +
                "try{window.RAN&&RAN.edgePing()}catch(e){}});}" +
                "window.addEventListener('scroll',ping,{passive:true,capture:true});" +
                "document.addEventListener('scroll',ping,{passive:true,capture:true});" +
                "setInterval(ping,600);return 1})()"
        web.evaluateJavascript(js, null)
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
        // 关键：不再 display:none 整个 #inputzone —— 那会连带杀死
        // #pluspanel/#permpanel/#attbar/#wakebar/#queuebar（实测点「+」仅 745 像素变化 = 无反应）。
        // 改为只隐藏输入框本体，其余状态条保留并上移到原生输入岛之上。
        val islandH = ((insetBottom + dp(56) + dp(14)) / d).toInt()
        val css = "#mhead{display:none!important}" +
                "#side{display:none!important}" +
                "#inwrap{display:none!important}" +
                "#sendbtn{display:none!important}" +
                "#pluspanel,#dotspanel,#agentpanel,#permpanel{display:none!important}" +
                "#inputzone{background:transparent!important;bottom:${islandH}px!important;" +
                "left:0!important;right:0!important}" +
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
