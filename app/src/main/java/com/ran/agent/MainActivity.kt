package com.ran.agent

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.liquidglass.LiquidGlassView
import org.json.JSONObject

/**
 * 原生液态玻璃外壳 V2
 *
 * 分工：
 *   - 顶栏（☰ / 标题 / 新建 / 更多）、底部输入岛  → 原生 LiquidGlassView（AGSL 真折射）
 *   - 聊天流、二级菜单、模态                      → 仍是 WebView 里的网页
 *
 * 网页一侧零改动：只是在运行时注入一段 CSS 把 #mhead / #inwrap 让位，
 * 并给 #chatinner 加上下内边距避开原生条。鸿蒙端与桌面端完全不受影响。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var dbg: TextView
    private lateinit var topBar: View
    private lateinit var island: LiquidGlassView
    private lateinit var input: EditText
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var insTop = 0
    private var insBottom = 0
    private val glassInfo = StringBuilder()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val errorHtml = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#f7f7f8;font-family:sans-serif}
.box{text-align:center;color:#1a1a1c}
h1{font-size:19px;margin:0 0 8px}
p{color:#8f8f95;font-size:14px;margin:0 0 22px;line-height:1.7}
a{display:inline-block;padding:12px 34px;background:#1a1a1c;color:#fff;border-radius:12px;text-decoration:none;font-size:15px}
</style></head><body><div class="box">
<h1>无法连接到 RAN</h1>
<p>网络不通，或服务还没启动。<br>确认联网、服务器和隧道都在线后重试。</p>
<a href="https://ran.ran-ai.pro/">重试</a>
</div></body></html>
""".trimIndent()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mark(s: String) {
        if (::dbg.isInitialized) runOnUiThread { dbg.text = dbg.text.toString() + "\n" + s }
    }

    private fun isAppUrl(u: String) =
        u.startsWith("https://ran.ran-ai.pro") || u.startsWith("http://100.103.73.74:8000")

    /* ---------------- 玻璃参数（反射设置，名字不确定也不会编译失败） ---------------- */

    private fun tryFloat(v: Any, vararg names: String, value: Float): String? {
        for (n in names) {
            try {
                v.javaClass.getMethod(n, Float::class.javaPrimitiveType).invoke(v, value)
                return n
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun tryBool(v: Any, vararg names: String): String? {
        for (n in names) {
            try {
                v.javaClass.getMethod(n, Boolean::class.javaPrimitiveType).invoke(v, true)
                return n
            } catch (_: Throwable) { }
        }
        return null
    }

    /**
     * 按库的真实 API 配置一块玻璃（v2.0.11 已反编译核实）：
     *   setBackdropSource(View)   显式指定采样对象 —— 不指定就只能取到窗口层，会渲染成黑块
     *   setOverLight(true)        声明玻璃压在浅色内容上（苹果的 regular material 语义）
     *   setBlurAmount / setCornerRadius / setGlassTint / setMaterial(REGULAR)
     */
    /** 反射调用：库是 Kotlin 写的，属性 setter 在 Kotlin 源码里不能按 setXxx() 调用 */
    private fun callM(obj: Any, name: String, types: Array<Class<*>>, args: Array<Any?>): Boolean {
        return try {
            obj.javaClass.getMethod(name, *types).invoke(obj, *args)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun setF(obj: Any, name: String, v: Float) =
        callM(obj, name, arrayOf<Class<*>>(Float::class.javaPrimitiveType!!), arrayOf<Any?>(v))

    private fun setB(obj: Any, name: String, v: Boolean) =
        callM(obj, name, arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!), arrayOf<Any?>(v))

    /**
     * 按库的真实 API 配置玻璃（v2.0.11 AAR 已反解析，方法名逐一核实）：
     *   setBackdropSource(View)  显式指定采样对象 —— 不指定就取不到 WebView，会渲染成黑块
     *   setOverLight(true)       声明玻璃压在浅色内容上（苹果 regular material 语义）
     */
    private fun makeGlass(radiusDp: Int = 26): LiquidGlassView {
        val g = LiquidGlassView(this)
        val ok = ArrayList<String>()
        try {
            if (this::web.isInitialized) {
                callM(g, "setBackdropSource",
                    arrayOf<Class<*>>(View::class.java), arrayOf<Any?>(web))
            }
            setB(g, "setEnableDynamicBackground", true)
            setB(g, "setEnableBackdropBlur", true)
            setB(g, "setOverLight", true)
            setB(g, "setEnableEdgeHighlight", true)
            setB(g, "setEnablePressEffect", true)
            setB(g, "setEnableSensorHighlight", true)
            setF(g, "setBlurAmount", dp(16).toFloat())
            setF(g, "setCornerRadius", dp(radiusDp).toFloat())
            setF(g, "setDispersionStrength", 0.10f)
            setF(g, "setBevelWidth", dp(radiusDp).toFloat() * 0.5f)
            setF(g, "setRefractionHeight", dp(6).toFloat())
            // 材质：REGULAR = 苹果的常规玻璃（CLEAR 更透）
            try {
                val mat = Class.forName("com.example.liquidglass.GlassMaterial")
                val reg = mat.getField("REGULAR").get(null)
                callM(g, "setMaterial", arrayOf<Class<*>>(mat), arrayOf<Any?>(reg))
                ok.add("mat")
            } catch (_: Throwable) {}
            // 着色（带强度重载）
            callM(g, "setGlassTint",
                arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!),
                arrayOf<Any?>(0x2EFFFFFF, 0.9f))
            glassInfo.setLength(0)
            glassInfo.append("bd=").append((g as Any).let {
                try {
                    it.javaClass.getMethod("getBackdropSource").invoke(it) != null
                } catch (e: Throwable) { false }
            }).append(" r=").append(radiusDp)
            if (ok.contains("mat")) glassInfo.append(" REGULAR")
        } catch (t: Throwable) {
            glassInfo.append("cfg-err:").append(t.javaClass.simpleName)
        }
        return g
    }

    /* ---------------- 顶部玻璃栏：三块独立玻璃，彼此留缝 ---------------- */

    private fun iconBtn(txt: String, desc: String, size: Int, onclick: () -> Unit): TextView {
        return TextView(this).apply {
            text = txt
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            contentDescription = desc
            setOnClickListener { onclick() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }

    private fun clickWeb(id: String) {
        web.evaluateJavascript(
            "(function(){var b=document.getElementById('" + id + "');if(b){b.click();return 1}return 0})()",
            null
        )
    }

    private fun buildTopBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }

        // ① 左侧：菜单，独立小玻璃
        val gMenu = makeGlass(24)
        gMenu.addView(iconBtn("\u2630", "菜单", 48) { clickWeb("burger") })
        wrap.addView(gMenu, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })

        // ② 中间：标题玻璃，占满剩余宽度，但不贴边
        val gTitle = makeGlass(24)
        val title = TextView(this).apply {
            text = "Orion"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        gTitle.addView(title)
        wrap.addView(gTitle, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })

        // ③ 右侧：新建 + 更多，两块图标共用一块玻璃（与参考图一致）
        val gRight = makeGlass(24)
        val rightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightRow.addView(iconBtn("\uFF0B", "新建会话", 44) { clickWeb("mnew") })
        rightRow.addView(iconBtn("\u22EF", "更多", 44) { clickWeb("dots") })
        gRight.addView(rightRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        wrap.addView(gRight, LinearLayout.LayoutParams(dp(92), dp(48)))
        return wrap
    }

    /* ---------------- 底部玻璃输入岛 ---------------- */

    private companion object {
        const val H_COLLAPSE = 56
        const val H_EXPAND = 152
    }

    private lateinit var btnRow: LinearLayout
    private var islandExpanded = false

    private fun circle(size: Int, color: Int, text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
    }

    private fun buildIsland(): LiquidGlassView {
        val g = makeGlass(28)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }

        input = EditText(this).apply {
            hint = "\u5c3d\u7ba1\u95ee"     // 「尽管问」——短，不铺满
            textSize = 15f
            setTextColor(0xFF1A1A1C.toInt())
            setHintTextColor(0xFF9A9AA0.toInt())
            background = null
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnFocusChangeListener { _, has -> if (has) expandIsland(true) }
        }
        col.addView(input)

        btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            visibility = View.GONE
            alpha = 0f
        }
        val plus = circle(38, 0xFFEFF0F2.toInt(), "\uFF0B").apply {
            setTextColor(0xFF1A1A1C.toInt())
            setOnClickListener { clickWeb("plusbtn") }
        }
        btnRow.addView(plus)
        btnRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val send = circle(38, 0xFF1A1A1C.toInt(), "\u2191").apply {
            setOnClickListener { doSend(); expandIsland(false) }
        }
        btnRow.addView(send)
        col.addView(btnRow)

        g.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return g
    }

    /** 聚焦展开：长条 → 更高的圆角矩形，两个圆钮落到下方 */
    private fun expandIsland(on: Boolean) {
        if (islandExpanded == on) return
        islandExpanded = on
        val from = island.layoutParams.height
        val to = dp(if (on) H_EXPAND else H_COLLAPSE)
        if (from != to) {
            val va = android.animation.ValueAnimator.ofInt(from, to)
            va.duration = 240
            va.interpolator = android.view.animation.PathInterpolator(0.2f, 0.9f, 0.2f, 1f)
            va.addUpdateListener { a ->
                (island.layoutParams as FrameLayout.LayoutParams).height = a.animatedValue as Int
                island.requestLayout()
            }
            va.start()
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
        setF(island, "setCornerRadius", dp(if (on) 26 else 28).toFloat())
        syncShellCss()
    }

    /** 把文字交给网页发送：写进 #in 再点 #sendbtn，不绕过网页自身的逻辑 */
    private fun doSend() {
        val t = input.text?.toString()?.trim() ?: ""
        if (t.isEmpty()) return
        val js = "(function(){var t=" + JSONObject.quote(t) + ";" +
                "var i=document.getElementById('in');if(!i)return 'no-in';" +
                "i.focus();i.value=t;" +
                "i.dispatchEvent(new Event('input',{bubbles:true}));" +
                "var b=document.getElementById('sendbtn');if(!b)return 'no-btn';" +
                "b.click();return 'ok';})()"
        web.evaluateJavascript(js) { r -> mark("send -> " + r) }
        input.setText("")
    }

    /* ---------------- 注入：只让网页让位，不改任何逻辑 ---------------- */

    private fun syncShellCss() {
        if (!this::web.isInitialized || !pageReady) return
        val islandH = if (this::island.isInitialized) island.layoutParams.height else dp(H_COLLAPSE)
        val topPx = dp(56) + insTop + dp(6)
        val botPx = islandH + dp(20) + insBottom
        val css = """
            html body #mhead{display:none!important}
            html body #inwrap{display:none!important}
            html body #sendbtn{display:none!important}
            html body #inputzone{background:transparent!important;padding-bottom:0!important}
            html body #chatinner{padding-top:${topPx}px!important;padding-bottom:${botPx}px!important}
        """.trimIndent()
        val js = "(function(){var s=document.getElementById('ran-shell');" +
                "if(!s){s=document.createElement('style');s.id='ran-shell';document.head.appendChild(s);}" +
                "s.textContent=" + JSONObject.quote(css) + ";return 'ok';})()"
        web.evaluateJavascript(js, null)
    }

    /* ---------------- 探针：把关键事实显示出来，便于定位 ---------------- */

    private fun addProbe() {
        val agsl = try {
            Class.forName("android.graphics.RuntimeShader"); "yes"
        } catch (t: Throwable) { "no" }
        val tv = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFF3A3A40.toInt())
            setBackgroundColor(0x55FFFFFF)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            text = "API " + android.os.Build.VERSION.SDK_INT + " · AGSL:" + agsl +
                    " · " + glassInfo.toString()
        }
        root.addView(tv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = dp(96)
        })
        tv.animate().alpha(1f).setDuration(200).withEndAction {
            tv.postDelayed({ tv.animate().alpha(0f).setDuration(400).start() }, 9000)
        }.start()
    }

    /* ---------------- 生命周期 ---------------- */

    private fun showCrash(t: Throwable) {
        val tv = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "启动崩溃：\n\n" + t.toString() + "\n\n" + (t.stackTraceToString().take(1200))
            setPadding(dp(20), dp(60), dp(20), dp(20))
        }
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        setContentView(sv)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前：manifest 的主题是 Theme.SplashScreen，
        // 不调用它，AppCompatActivity 会因为「不是 AppCompat 主题」直接崩。
        try {
            installSplashScreen()
        } catch (t: Throwable) { }
        try { super.onCreate(savedInstanceState) } catch (t: Throwable) { showCrash(t); return }
        try { buildUi() } catch (t: Throwable) { showCrash(t); return }
    }

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        root = FrameLayout(this)
        root.setBackgroundColor(0xFFF7F7F8.toInt())

        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        dbg = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(8), dp(90), dp(8), dp(8))
            visibility = View.GONE
        }
        root.addView(dbg, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        topBar = buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            gravity = Gravity.TOP
        })

        island = buildIsland()
        root.addView(island, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(H_COLLAPSE)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(10)
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val sb = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            insTop = sb.top
            // 键盘弹出时输入岛必须抬到键盘之上，否则被挡住
            insBottom = maxOf(sb.bottom, ime.bottom)
            (topBar.layoutParams as FrameLayout.LayoutParams).apply {
                height = dp(56) + insTop
            }
            topBar.setPadding(dp(8), insTop, dp(8), 0)
            (island.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = dp(10) + insBottom
            }
            topBar.requestLayout(); island.requestLayout()
            syncShellCss()
            ins
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                view.reload(); return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (isAppUrl(u)) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url)); return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!isAppUrl(url)) return
                pageReady = true
                syncShellCss()
                addProbe()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    web.loadDataWithBaseURL("https://ran.ran-ai.pro/", errorHtml, "text/html", "utf-8", null)
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    web.loadDataWithBaseURL("https://ran.ran-ai.pro/", errorHtml, "text/html", "utf-8", null)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                picker.launch(arrayOf("*/*"))
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl("https://ran.ran-ai.pro")
    }
}
