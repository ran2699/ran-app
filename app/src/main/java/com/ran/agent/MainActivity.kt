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
    private lateinit var topBar: LiquidGlassView
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

    private fun makeGlass(): LiquidGlassView {
        val g = LiquidGlassView(this)
        // 关键：不开这个开关，玻璃不会去采样身后的内容，就只剩一层素色
        val b = tryBool(g, "setEnableDynamicBackground", "setDynamicBackgroundEnabled", "setBackdropEnabled")
        // 圆角/模糊：各版本命名不一，挨个试，成功就记下来
        val r = tryFloat(g, "setCornerRadius", "setGlassCornerRadius", "setRadius", value = dp(22).toFloat())
        val bl = tryFloat(g, "setBlurRadius", "setGlassBlurRadius", "setBlur", value = dp(18).toFloat())
        glassInfo.append("dyn=").append(b ?: "?").append(" r=").append(r ?: "?").append(" blur=").append(bl ?: "?")
        return g
    }

    /* ---------------- 顶部玻璃栏 ---------------- */

    private fun buildTopBar(): LiquidGlassView {
        val g = makeGlass()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun icon(txt: String, desc: String, onclick: () -> Unit): TextView {
            return TextView(this).apply {
                text = txt
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(0xFF1A1A1C.toInt())
                contentDescription = desc
                setOnClickListener { onclick() }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
        }
        row.addView(icon("\u2630", "菜单") { web.evaluateJavascript("(function(){var b=document.getElementById('burger');if(b){b.click();return 1}return 0})()", null) })
        val title = TextView(this).apply {
            text = "Orion"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1C.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        row.addView(title)
        row.addView(icon("\uFF0B", "新建会话") { web.evaluateJavascript("(function(){var b=document.getElementById('mnew');if(b){b.click();return 1}return 0})()", null) })
        row.addView(icon("\u22EF", "更多") { web.evaluateJavascript("(function(){var b=document.getElementById('dots');if(b){b.click();return 1}return 0})()", null) })
        g.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return g
    }

    /* ---------------- 底部玻璃输入岛 ---------------- */

    private fun buildIsland(): LiquidGlassView {
        val g = makeGlass()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(6), dp(4))
        }
        input = EditText(this).apply {
            hint = "输入任务，回车发送"
            textSize = 15f
            setTextColor(0xFF1A1A1C.toInt())
            setHintTextColor(0xFF9A9AA0.toInt())
            background = null
            isSingleLine = false
            maxLines = 4
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(input)
        val send = TextView(this).apply {
            text = "发送"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFF1A1A1C.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(40)).apply { marginStart = dp(8) }
            setOnClickListener { doSend() }
        }
        row.addView(send)
        g.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return g
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

    private fun injectShellCss() {
        val topPx = dp(56) + insTop
        val botPx = dp(76) + insBottom
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
            ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(10)
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val sb = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            insTop = sb.top
            insBottom = sb.bottom
            (topBar.layoutParams as FrameLayout.LayoutParams).apply {
                height = dp(56) + insTop
                topMargin = 0
            }
            (island.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = dp(10) + insBottom
            }
            topBar.setPadding(0, insTop, 0, 0)
            island.requestLayout(); topBar.requestLayout()
            if (pageReady) injectShellCss()
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
                injectShellCss()
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
