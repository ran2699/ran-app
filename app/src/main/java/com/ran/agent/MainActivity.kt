package com.ran.agent

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {

    // 品牌组垂直锚点：屏幕高度的比例（网页空状态 logo 的视觉中心约在中点偏上）
    private val ANCHOR = 0.45
    private lateinit var dbg: TextView
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var splashBg: View
    private lateinit var splashView: View
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var startAt = 0L
    private var renderCrashCount = 0
    private var softwareLayer = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val errorHtml = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#f7f7f8;font-family:sans-serif}
.box{text-align:center;color:#1a1a1c}
.logo{width:88px;height:88px;border-radius:24px;margin-bottom:18px}
h1{font-size:19px;margin:0 0 8px}
p{color:#8f8f95;font-size:14px;margin:0 0 22px;line-height:1.7}
a{display:inline-block;padding:12px 34px;background:#1a1a1c;color:#fff;border-radius:12px;text-decoration:none;font-size:15px}
</style></head><body><div class="box">
<img class="logo" src="https://ran.ran-ai.pro/logo.png" onerror="this.style.display='none'">
<h1>无法连接到 RAN</h1>
<p>网络不通，或服务还没启动。<br>确认联网、服务器和隧道都在线后重试。</p>
<a href="https://ran.ran-ai.pro/">重试</a>
</div></body></html>
""".trimIndent()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mark(s: String) {
        if (::dbg.isInitialized) runOnUiThread { dbg.text = dbg.text.toString() + "\n" + s }
    }

    private fun showError() {
        web.loadDataWithBaseURL("https://ran.ran-ai.pro/", errorHtml, "text/html", "utf-8", null)
    }

    private fun dismissSplash() {
        if (!::splashView.isInitialized) return
        if (splashView.parent == null) return
        val anim = splashView.animate().alpha(0f).setDuration(250)
        if (splashBg.parent != null) anim.withEndAction { root.removeView(splashBg) }
        anim.start()
        splashBg.animate().alpha(0f).setDuration(250).start()
    }

    private inner class SpinView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1C.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }
        private val rect = RectF()
        private var rot = 0f
        private val loop = object : Runnable {
            override fun run() {
                rot = (rot + 6f) % 360f
                invalidate()
                postDelayed(this, 16)
            }
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post(loop)
        }
        override fun onDetachedFromWindow() {
            removeCallbacks(loop)
            super.onDetachedFromWindow()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - paint.strokeWidth
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.save()
            canvas.rotate(rot, cx, cy)
            canvas.drawArc(rect, 0f, 270f, false, paint)
            canvas.restore()
        }
    }

    private fun showCrash(t: Throwable) {
        val tv = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            text = "\u542f\u52a8\u5d29\u6e83\uff1a\n\n" + t.toString() + "\n\n" +
                    (t.stackTraceToString()?.take(1200) ?: "")
            setPadding(dp(20), dp(60), dp(20), dp(20))
        }
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        setContentView(sv)
    }

    // 给容器环境的友好错误页：不甩堆栈，给一个能看懂的原因 + 重试按钮
    private fun showFatal(title: String, desc: String, t: Throwable? = null) {
        val tv = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF1A1A1C.toInt())
            text = title + "\n\n" + desc + (t?.let { "\n\n" + it.toString() } ?: "")
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val retry = android.widget.Button(this).apply {
            text = "重试"
            setOnClickListener { recreate() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(120), dp(20), dp(20))
            addView(tv)
            addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            })
        }
        val sv = android.widget.ScrollView(this)
        sv.addView(box)
        setContentView(sv)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try { super.onCreate(savedInstanceState) } catch (t: Throwable) { showCrash(t); return }
        try { buildUi() } catch (t: Throwable) { showCrash(t); return }
    }

    private fun buildUi() {
        // 关键：声明内容画到系统栏下方（缺这行，部分 ROM 透明色不生效）
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        mark("3 边到边声明")
        startAt = System.currentTimeMillis()
        try {
            installSplashScreen().setKeepOnScreenCondition {
                System.currentTimeMillis() - startAt < 300
            }
            mark("2 闪屏安装")
        } catch (t: Throwable) {
            // 卓易通等容器对 splashscreen 支持不全：装不上就跳过，不当崩溃处理
            mark("2 闪屏安装失败(跳过): " + t.javaClass.simpleName)
        }
        root = FrameLayout(this)
        try {
            web = WebView(this)
        } catch (t: Throwable) {
            showFatal("WebView 创建失败", "当前环境缺少可用的 WebView 组件（卓易通等容器常见问题）。请为容器安装/更新 Android System WebView 后重试。", t)
            return
        }
        root.addView(web)
        setContentView(root)
        dbg = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF00FF00.toInt())
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(8), dp(90), dp(8), dp(8))
        }
        root.addView(dbg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        mark("1 进程启动")
        mark("4 WebView 创建成功")

        // 白底层：占满全屏，与系统闪屏的白底连续
        splashBg = View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        root.addView(splashBg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 品牌内容组：与网页空状态同位置（锚点 ANCHOR）同构成（logo/产品名/提示/转圈）
        splashView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply {
                val tv = android.util.TypedValue()
                resources.getValue(R.drawable.ic_launcher, tv, true)
                val ins = resources.openRawResource(R.drawable.ic_launcher, tv)
                val bmp = BitmapFactory.decodeStream(ins)
                ins.close()
                if (bmp != null) {
                    val dr = RoundedBitmapDrawableFactory.create(resources, bmp)
                    dr.cornerRadius = 22f * resources.displayMetrics.density
                    setImageDrawable(dr)
                } else {
                    setImageResource(R.drawable.ic_launcher)
                }
            }, LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(TextView(context).apply {
                text = "RAN Agent"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1A1A1C.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            })
            addView(TextView(context).apply {
                text = "\u8f7d\u5165\u6570\u636e\u4e2d\u2026"
                textSize = 13f
                setTextColor(0xFF8F8F95.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(SpinView(context), LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                topMargin = dp(24)
            })
        }
        // 组高约 220dp：锚点 45% 处放置组的中心
        val groupTop = (resources.displayMetrics.heightPixels * ANCHOR).toInt() - dp(110)
        root.addView(splashView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = groupTop
        })

        splashView.alpha = 0f
        splashView.translationY = dp(14).toFloat()
        splashView.animate().alpha(1f).translationY(0f).setDuration(380)
            .setInterpolator(DecelerateInterpolator()).start()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        mark("5 设置完成")

        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                renderCrashCount++
                mark("10 渲染进程崩溃 reason=" + detail.reason + " 第" + renderCrashCount + "次")
                if (renderCrashCount >= 2 && !softwareLayer) {
                    // 卓易通容器 GPU 合成常出问题：第二次崩溃后降为软件渲染再试
                    softwareLayer = true
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    mark("10 降级软件渲染")
                }
                if (renderCrashCount > 3) {
                    // 不再死循环重载，给用户可重试的错误页
                    view.post { showFatal("页面渲染反复崩溃", "当前容器的 WebView 渲染进程不稳定。可点击重试重新载入；若反复出现，请更新容器内的 WebView 组件。") }
                    return true
                }
                view.post { view.reload() }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                mark("7 页面开始加载")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("https://ran.ran-ai.pro") ||
                    url.startsWith("http://100.103.73.74:8000")
                ) return false
                // 卓易通等容器常没有浏览器：startActivity 必须防御，否则一点外链就闪退
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        true
                    } else {
                        android.widget.Toast.makeText(this@MainActivity, "当前环境没有可打开外部链接的浏览器", android.widget.Toast.LENGTH_LONG).show()
                        true
                    }
                } catch (t: Throwable) {
                    android.widget.Toast.makeText(this@MainActivity, "当前环境无法打开外部链接", android.widget.Toast.LENGTH_LONG).show()
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                mark("8 页面加载完成")
                renderCrashCount = 0
                if (!pageReady) {
                    pageReady = true
                    dismissSplash()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                mark("9 网络错误 code=" + error.errorCode)
                if (request.isForMainFrame) showError()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) showError()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
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

        Handler(Looper.getMainLooper()).postDelayed({
            if (!pageReady) dismissSplash()
        }, 8000)

        mark("6 loadUrl 已发")
        web.loadUrl("https://ran.ran-ai.pro")
    }
}
