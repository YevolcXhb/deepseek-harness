package com.dsh.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent

/**
 * Main Activity: WebView container + startup flow.
 *
 * Startup flow:
 *   1. Show splash screen
 *   2. Launch proot container (DshSandboxBridge)
 *   3. Wait for dsh HTTP ready
 *   4. Start keep-alive foreground service
 *   5. WebView loads http://127.0.0.1:PORT
 *   6. Hide splash screen
 *
 * The WebView loads the full dsh Web UI including:
 *   ✅ index.html (with __DSH_BOOT__ injection)
 *   ✅ React + Vite built assets
 *   ✅ 45+ client UI plugins (dsh.client)
 *   ✅ SlotMap slot system
 *   ✅ HMR hot reload (SSE)
 *   ✅ Third-party dsh-plugin ecosystem
 */
class DshMainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashView: View
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private val sandboxBridge = DshSandboxBridge()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splashView = findViewById(R.id.splashView)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        // Apply window insets for edge-to-edge display (multi-device adaptation)
        applyWindowInsets()

        startContainer()
    }

    private fun applyWindowInsets() {
        // Handle notch / cutout / navigation bar across different devices
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val params = view.layoutParams
            // Let the WebView handle its own padding based on safe area
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(port: Int) {
        val settings = webView.settings
        // JavaScript: required for dsh client plugin system
        settings.javaScriptEnabled = true
        // DOM Storage: dsh client uses localStorage/IndexedDB
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // File access: load local resources
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        // Mixed content: allow http + https (dsh runs on localhost)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // Cache
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Viewport: critical for multi-device screen adaptation
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // Disable zoom (mobile UX)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        // Media: required for keep-alive audio
        settings.mediaPlaybackRequiresUserGesture = false
        // Text auto-sizing for different DPI
        settings.textZoom = 100

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host != "127.0.0.1" && url.host != "localhost") {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(DshWebInterface(this), "DshNative")
        webView.loadUrl("http://127.0.0.1:$port")
    }

    private fun startContainer() {
        Thread {
            try {
                runOnUiThread {
                    statusText.text = getString(R.string.status_initializing)
                    progressBar.progress = 10
                }

                val apiKey = getSharedPreferences(DshApplication.PREFS_NAME, MODE_PRIVATE)
                    .getString(DshApplication.PREF_API_KEY, "") ?: ""

                // 首次进入无需强制配置 API Key；用户可在设置中手动配置。
                // 即使 key 为空，也启动容器进入 Web UI。

                runOnUiThread {
                    statusText.text = getString(R.string.status_starting_engine)
                    progressBar.progress = 30
                }

                val result = sandboxBridge.launch(apiKey)

                runOnUiThread {
                    statusText.text = getString(R.string.status_waiting_ready)
                    progressBar.progress = 60
                }

                waitForDshReady(result.port)

                runOnUiThread {
                    statusText.text = getString(R.string.status_loading_ui)
                    progressBar.progress = 90
                }

                // Start keep-alive service
                startForegroundService(Intent(this, DshKeepAliveService::class.java).apply {
                    putExtra("pid", result.pid)
                })
                DshHealthCheckJob.schedule(this)
                DshKeepAliveBridge.setMonitoredPid(result.pid)
                DshKeepAliveBridge.setPort(result.port)

                runOnUiThread {
                    progressBar.progress = 100
                    configureWebView(result.port)
                    webView.postDelayed({
                        splashView.visibility = View.GONE
                        webView.visibility = View.VISIBLE
                    }, 2000)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = getString(R.string.status_failed, e.message)
                }
            }
        }.start()
    }

    private fun waitForDshReady(port: Int) {
        for (i in 0 until 30) {
            try {
                val conn = java.net.URL("http://127.0.0.1:$port/api").openConnection()
                        as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..404) return
            } catch (e: Exception) {
                Thread.sleep(1000)
            }
        }
        throw RuntimeException("dsh failed to start within 30 seconds")
    }

    /**
     * Prevent back button from exiting (send to background instead).
     */
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't stop container here; keep-alive service manages lifecycle
    }
}
