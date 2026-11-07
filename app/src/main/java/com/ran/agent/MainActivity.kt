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
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var splashBg: View
    private lateinit var splashView: View
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var startAt = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        try { super.onCreate(savedInstanceState) } catch (t: Throwable) { showCrash(t); return }
        try { buildUi() } catch (t: Throwable) { showCrash(t); return }
    }

    private fun buildUi() {
        startAt = System.currentTimeMillis()
        installSplashScreen().setKeepOnScreenCondition {
            System.currentTimeMillis() - startAt < 300
        }
        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web)
        setContentView(root)

        // 白底层：占满全屏，与系统闪屏的白底连续
        splashBg = View(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()) }
        root.addView(splashBg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 品牌内容组：与网页空状态同位置（锚点 ANCHOR）同构成（logo/产品名/提示/转圈）
        splashView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply {
                val bmp = BitmapFactory.decodeResource(resources, R.drawable.ic_launcher)
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

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("https://ran.ran-ai.pro") ||
                    url.startsWith("http://100.103.73.74:8000")
                ) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!pageReady) {
                    pageReady = true
                    dismissSplash()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
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

        web.loadUrl("https://ran.ran-ai.pro")
    }
}
