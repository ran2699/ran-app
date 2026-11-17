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
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
import android.app.Activity
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
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var island: FrameLayout
    private lateinit var input: EditText
    private lateinit var btnRow: LinearLayout

    private var islandExpanded = false
    private val glassTargets = ArrayList<Pair<View, Float>>()
    private var shader: RuntimeShader? = null
    private var shaderSrc = ""
    private var cfg = GlassCfg()

    private var probe: TextView? = null

    private val TAG = "RanGlass"

    private val reloader = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            applyConfig("reload")
            runDemoJs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前，否则无效 —— 这是 "RAN Glass" 标题栏的成因
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        // 沉浸式：玻璃延伸到状态栏/导航栏下面（苹果的做法）
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }

        root = FrameLayout(this)
        root.setBackgroundColor(0xFFF7F7F8.toInt())
        setContentView(root)

        // insetsController 依赖 DecorView，必须在 setContentView 之后取，否则 NPE
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.insetsController?.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            } catch (_: Throwable) {}
        }

        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setupWeb()

        val tb = buildTopBar()
        topBar = tb
        root.addView(tb, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        island = buildIsland()
        root.addView(island, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(H_COLLAPSE)))

        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sb = ins.getInsets(WindowInsets.Type.statusBars())
                val nb = ins.getInsets(WindowInsets.Type.navigationBars())
                val im = ins.getInsets(WindowInsets.Type.ime())
                insetTop = sb.top
                insetBottom = nb.bottom
                imeBottom = maxOf(0, im.bottom - nb.bottom)
            } else {
                insetTop = ins.systemWindowInsetTop
                insetBottom = ins.systemWindowInsetBottom
                imeBottom = 0
            }
            applyLayout()
            Log.i(TAG, "insets top=" + insetTop + " bottom=" + insetBottom +
                    " ime=" + imeBottom + " density=" + resources.displayMetrics.density)
            ins
        }

        val filter = IntentFilter("com.ran.agent.RELOAD")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reloader, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(reloader, filter)
        }

        web.loadUrl("https://ran.ran-ai.pro")

        // 启动不抢焦点、不弹键盘
        root.isFocusableInTouchMode = true
        root.requestFocus()

        root.postDelayed({ applyConfig("init") }, 300)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRects() }
    }

    private var topBar: View? = null
    private var insetTop = 0
    private var insetBottom = 0
    private var imeBottom = 0

    /** 位置跟随系统栏与输入法：顶栏贴状态栏下，输入岛浮在键盘上方 */
    private fun applyLayout() {
        topBar?.let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != insetTop + dp(6)) {
                lp.topMargin = insetTop + dp(6)
                lp.leftMargin = dp(6)
                lp.rightMargin = dp(6)
                v.layoutParams = lp
            }
        }
        if (this::island.isInitialized) {
            val v: FrameLayout = island
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val want = insetBottom + imeBottom + dp(14)
            if (lp.bottomMargin != want) {
                lp.bottomMargin = want
                lp.leftMargin = dp(12)
                lp.rightMargin = dp(12)
                v.layoutParams = lp
            }
        }
        root.postDelayed({ applyRects() }, 60)
        if (this::web.isInitialized) root.postDelayed({ injectShellCss() }, 80)
    }

    override fun onDestroy() {
        try { unregisterReceiver(reloader) } catch (_: Throwable) {}
        super.onDestroy()
    }

    /* ---------------- 热调试配置 ---------------- */

    private class GlassCfg {
        var bevel = 14f
        var refraction = 12f
        var dispersion = 1.0f
        var blurR = 20f
        var tintA = 0.10f
        var rimA = 0.70f
        var falloff = 26f

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
                "falloff" -> c.falloff = v
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
            shaderSrc = readShaderSrc()
            lastRectKey = ""
            val s = RuntimeShader(shaderSrc)
            shader = s
            val eff = RenderEffect.createRuntimeShaderEffect(s, "content")
            web.setRenderEffect(eff)
            applyRects()
            Log.i(TAG, "shader OK why=" + why + " " + cfg.toString())
            showProbe("玻璃已载入 · " + cfg.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "shader FAIL " + (t.message ?: ""), t)
            showProbe("shader 出错：" + t.javaClass.simpleName)
        }
    }

    /**
     * 安全赋值：给 shader 里不存在的 uniform 赋值会触发 libhwui 的 native abort（进程直接死），
     * 不是 Java 异常，catch 不住。所以先查源码里有没有声明。
     */
    private fun setF(s: RuntimeShader, name: String, v: Float) {
        if (shaderSrc.contains("uniform float " + name)) s.setFloatUniform(name, v)
    }

    private var lastRectKey = ""

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
        // 位置没变就什么都不做：否则 setRenderEffect 会触发重绘，重绘又触发布局监听 → ANR
        val key = StringBuilder()
        for (rc in rects) {
            for (f in rc) key.append(f.toInt()).append(',')
        }
        if (key.toString() == lastRectKey) return
        lastRectKey = key.toString()

        try {
            for (i in 0 until 4) {
                if (i < rects.size) {
                    val rc = rects[i]
                    setF(s, "r" + i + "x", rc[0])
                    setF(s, "r" + i + "y", rc[1])
                    setF(s, "r" + i + "z", rc[2])
                    setF(s, "r" + i + "w", rc[3])
                    setF(s, "r" + i + "r", rc[4])
                } else {
                    setF(s, "r" + i + "x", 0f)
                    setF(s, "r" + i + "y", 0f)
                    setF(s, "r" + i + "z", 0f)
                    setF(s, "r" + i + "w", 0f)
                    setF(s, "r" + i + "r", 0f)
                }
            }
            setF(s, "bevel", cfg.bevel * d)
            setF(s, "refraction", cfg.refraction * d)
            setF(s, "dispersion", cfg.dispersion * d)
            setF(s, "blurR", cfg.blurR * d)
            setF(s, "tintA", cfg.tintA)
            setF(s, "rimA", cfg.rimA)
            setF(s, "falloff", cfg.falloff * d)
            // 关键：uniform 必须在创建 RenderEffect 之前写好，否则 effect 里拿到的是全 0。
            // 所以每次改参数都重建一次 effect（开销很小，只在参数变化时发生）。
            web.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"))
            web.invalidate()
            if (rects.isNotEmpty()) {
                val sb = StringBuilder("rects n=" + rects.size + " web=" +
                        web.width + "x" + web.height + " |")
                for (rc in rects) {
                    sb.append(" [").append(rc[0].toInt()).append(',').append(rc[1].toInt())
                        .append(' ').append(rc[2].toInt()).append(',').append(rc[3].toInt())
                        .append(']')
                }
                Log.i(TAG, sb.toString())
            } else {
                Log.w(TAG, "rects 为空（玻璃控件还没测量）")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "uniform FAIL", t)
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
        row.setPadding(0, dp(4), 0, dp(4))

        val left = FrameLayout(this)
        val ic = icon("\u2630", 44) { clickWeb("burger") }
        left.addView(ic, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        row.addView(left, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        glassTargets.add(Pair(left, dp(22).toFloat()))

        val mid = FrameLayout(this)
        mid.addView(TextView(this).apply {
            text = "Orion"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        glassTargets.add(Pair(mid, dp(22).toFloat()))

        val right = FrameLayout(this)
        val rr = LinearLayout(this)
        rr.orientation = LinearLayout.HORIZONTAL
        rr.gravity = Gravity.CENTER
        rr.addView(icon("\uFF0B", 44) { clickWeb("mnew") })
        rr.addView(icon("\u22EF", 44) { clickWeb("dots") })
        right.addView(rr, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        row.addView(right, LinearLayout.LayoutParams(dp(92), dp(44)))
        glassTargets.add(Pair(right, dp(22).toFloat()))

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
        input.isSingleLine = true
        input.isFocusableInTouchMode = false
        input.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        input.setSelectAllOnFocus(false)
        input.setOnClickListener {
            input.isFocusableInTouchMode = true
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        input.setOnFocusChangeListener { _, has ->
            expandIsland(has)
            if (!has) {
                val imm = getSystemService(INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
            }
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
        if (on) {
            input.isSingleLine = false
            input.maxLines = 4
        } else {
            input.isSingleLine = true
            input.maxLines = 1
            input.isFocusableInTouchMode = false
        }
        lastRectKey = ""
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
                root.postDelayed({ injectShellCss() }, 1200)
                root.postDelayed({ logWebViewport() }, 1500)
                root.postDelayed({ runDemoJs() }, 1800)
            }
        }
    }

    /**
     * 只隐藏网页自身的顶栏/输入区，给聊天流补 padding。
     * 全程运行时注入，网页源码零改动 —— 鸿蒙 / 桌面端不受影响。
     */
    private var lastPad = -1

    /** 调试通道：执行设备目录里的 demo.js，用于造测试内容验证折射，零构建迭代 */
    private fun runDemoJs() {
        val f = File(cfgDir(), "demo.js")
        if (!f.exists() || f.length() < 8) return
        try {
            val js = f.readText()
            web.evaluateJavascript(js, android.webkit.ValueCallback<String> { v ->
                Log.i(TAG, "demo.js -> " + v)
            })
            Log.i(TAG, "demo.js 已执行，长度=" + js.length)
        } catch (t: Throwable) {
            Log.e(TAG, "demo.js 失败", t)
        }
    }

    private fun logWebViewport() {
        web.evaluateJavascript(
            "(function(){return document.documentElement.clientWidth+'|'+screen.width+'|'" +
            "+window.devicePixelRatio})()",
            android.webkit.ValueCallback<String> { v -> Log.i(TAG, "viewport=" + v) })
    }

    private fun injectShellCss() {
        val d = resources.displayMetrics.density
        val topPad = ((insetTop + dp(52) + dp(10)) / d).toInt()
        val botPad = ((insetBottom + dp(56) + dp(26)) / d).toInt()
        val key = topPad * 1000 + botPad
        if (key == lastPad) return
        lastPad = key
        val css = """
            #mhead,#inwrap,#inputzone,#sendbtn{display:none!important}
            #chat{padding-bottom:${botPad}px!important}
            #chatinner{padding-top:${topPad}px!important}
            html,body{background:#F7F7F8!important}
        """.trimIndent()
        val js = "(function(){var s=document.getElementById('ran-shell-css');" +
                "if(!s){s=document.createElement('style');s.id='ran-shell-css';" +
                "document.head.appendChild(s);}s.textContent=" + jsStr(css) + ";})()"
        web.evaluateJavascript(js, null)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
