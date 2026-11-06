package com.ran.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

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

    private fun showError() {
        web.loadDataWithBaseURL("https://ran.ran-ai.pro/", errorHtml, "text/html", "utf-8", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
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

        web.loadUrl("https://ran.ran-ai.pro")
    }
}
