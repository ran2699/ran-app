package com.ran.agent

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
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
        if (splashView.parent == null) return
        splashView.animate().alpha(0f).setDuration(250).withEndAction {
            root.removeView(splashView)
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAt = System.currentTimeMillis()
        // 系统闪屏只挡 300ms 防白闪，随后交给我们的品牌启动页
        installSplashScreen().setKeepOnScreenCondition {
            System.currentTimeMillis() - startAt < 300
        }

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web)
        setContentView(web.parent as View? ?: web)

        // 品牌启动页：大 logo + 产品名 + 转圈
        splashView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_launcher)
            }, LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(TextView(context).apply {
                text = "RAN Agent"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF1A1A1C.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
            addView(ProgressBar(context).apply {
                indeterminateTintList = ColorStateList.valueOf(0xFF1A1A1C.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(28)
            }))
        }
        root.addView(splashView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

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

        // 8 秒兜底：服务器若假死（TCP 通但不响应），超时也放行，避免永远转圈
        Handler(Looper.getMainLooper()).postDelayed({
            if (!pageReady) dismissSplash()
        }, 8000)

        web.loadUrl("https://ran.ran-ai.pro")
    }
}
