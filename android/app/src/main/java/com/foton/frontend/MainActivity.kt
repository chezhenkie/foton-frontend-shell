package com.foton.frontend

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        webView = WebView(this)
        setupWebView()
        val root = FrameLayout(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        applyBarAppearance()
        val saved = prefs.getString(KEY_URL, null)
        if (saved.isNullOrBlank()) {
            promptForUrl(null)
        } else {
            webView.loadUrl(saved)
        }
    }

    private fun applyBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = window.insetsController ?: return
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(if (night) 0 else lightBars, lightBars)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun fullscreen(on: Boolean) {
                runOnUiThread { setBarsHidden(on) }
            }
        }, "fotonHost")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                injectFullscreenShim(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                when (error.errorCode) {
                    WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
                    WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_BAD_URL,
                    WebViewClient.ERROR_UNSUPPORTED_SCHEME -> {
                        prefs.edit().remove(KEY_URL).apply()
                        promptForUrl(null)
                    }
                }
            }
        }
    }

    private fun injectFullscreenShim(view: WebView) {
        val shim = "" +
            "(function () {" +
            "  if (window.__fotonFullscreenShim) { return; }" +
            "  window.__fotonFullscreenShim = true;" +
            "  var active = false;" +
            "  function emitChange() {" +
            "    document.dispatchEvent(new Event('fullscreenchange'));" +
            "  }" +
            "  Element.prototype.requestFullscreen = function () {" +
            "    active = true;" +
            "    emitChange();" +
            "    fotonHost.fullscreen(true);" +
            "    return Promise.resolve();" +
            "  };" +
            "  Document.prototype.exitFullscreen = function () {" +
            "    active = false;" +
            "    emitChange();" +
            "    fotonHost.fullscreen(false);" +
            "    return Promise.resolve();" +
            "  };" +
            "  Object.defineProperty(document, 'fullscreenElement', {" +
            "    configurable: true," +
            "    get: function () { return active ? document.documentElement : null; }" +
            "  });" +
            "  Object.defineProperty(document, 'fullscreenEnabled', {" +
            "    configurable: true," +
            "    get: function () { return true; }" +
            "  });" +
            "})();"
        view.evaluateJavascript(shim, null)
    }

    private fun setBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            val flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hidden) flags else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun promptForUrl(current: String?) {
        val input = EditText(this)
        input.setText(current.orEmpty())
        AlertDialog.Builder(this)
            .setTitle("Server URL")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                val url = normalizeUrl(input.text.toString())
                if (url == null) {
                    Toast.makeText(this, "invalid URL", Toast.LENGTH_SHORT).show()
                    promptForUrl(input.text.toString())
                } else {
                    prefs.edit().putString(KEY_URL, url).apply()
                    webView.loadUrl(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalizeUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.any(Char::isWhitespace) || t.contains('\\')) return null
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        val uri = try { Uri.parse(withScheme) } catch (e: Exception) { return null }
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val host = uri.host
        if (host.isNullOrEmpty()) return null
        val port = try { uri.port } catch (e: NumberFormatException) { return null }
        if (port < 0 || port > 65535) return null
        return withScheme
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_URL, Menu.NONE, "Server URL...")
        menu.add(Menu.NONE, MENU_FULLSCREEN, Menu.NONE, if (fullscreen) "Exit fullscreen" else "Fullscreen")
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_URL -> {
                promptForUrl(prefs.getString(KEY_URL, null))
                true
            }
            MENU_FULLSCREEN -> {
                toggleFullscreen()
                invalidateOptionsMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val window = window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller: WindowInsetsController = window.insetsController ?: return
            if (fullscreen) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            val flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            @Suppress("DEPRECATION")
            webView.systemUiVisibility = if (fullscreen) flags else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "foton_prefs"
        private const val KEY_URL = "server_url"
        private const val MENU_URL = 1
        private const val MENU_FULLSCREEN = 2
    }
}