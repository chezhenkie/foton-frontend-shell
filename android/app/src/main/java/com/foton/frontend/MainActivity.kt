package com.foton.frontend

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var overflow: ImageButton

    // The overflow overlay is one self-contained box in the top-end corner: the
    // gear button is its only child and the only thing in it that ever turns.
    // The WebView is a sibling of the box, never a child, so nothing that
    // rotates here can reach the page.
    private lateinit var overlayBox: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var prefs: SharedPreferences

    // Open menu panel, if any. Held so an orientation change can drop it: its
    // geometry is measured once, at show time.
    private var menuPanel: PopupWindow? = null

    // Single source of truth for fullscreen. Written by the page bridge, the
    // overflow menu and the back gesture - all of them go through
    // setFullscreen, so the page and the system bars can never disagree.
    private var fullscreen = false

    // Origin the operator configured, and whether the native bridge is currently
    // injected. Both are per-load state, never per-app.
    private var trustedOrigin: String? = null
    private var bridgeAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The shell talks to operator-chosen servers over a tailnet and hands
        // them a native bridge, so an adb-attached DevTools session would be
        // arbitrary code execution on the device. Off unconditionally, even in
        // debug builds, because debuggable is injected by AGP.
        WebView.setWebContentsDebuggingEnabled(false)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        webView = WebView(this)
        setupWebView()
        root = FrameLayout(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val margin = (OVERFLOW_MARGIN_DP * resources.displayMetrics.density).toInt()
        val pad = dp(OVERFLOW_PAD_DP)
        overflow = ImageButton(this)
        overflow.setImageResource(R.drawable.ic_menu_gear)
        overflow.contentDescription = getString(R.string.menu_content_description)
        // No background box: the gear stands on the page on its own, only the
        // theme ripple (a borderless oval, not a plate) marks a touch.
        overflow.setPadding(pad, pad, pad, pad)
        overlayBox = FrameLayout(this)
        overlayBox.addView(
            overflow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            overlayBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(margin, margin, margin, margin) }
        )
        overflow.setOnClickListener { showOverflowMenu() }
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
        applyOverlayRotation()
        val saved = prefs.getString(KEY_URL, null)
        if (saved.isNullOrBlank()) {
            promptForUrl(null)
        } else {
            loadTrusted(saved)
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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // Anything the operator did not configure loses the bridge as
                // soon as it starts loading. Arbitrary servers stay reachable;
                // what they do not get is a native call channel.
                if (originOf(url.orEmpty()) == trustedOrigin) attachBridge() else detachBridge()
            }

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
                        trustedOrigin = null
                        detachBridge()
                        promptForUrl(null)
                    }
                }
            }
        }
    }

    // The only native call the page gets. addJavascriptInterface injects into
    // every frame of the page, so an iframe of another origin can also reach it
    // while the bridge is attached; the action it can perform is a fullscreen
    // toggle and nothing else.
    private val bridge = object {
        @JavascriptInterface
        fun fullscreen(on: Boolean) {
            runOnUiThread { setFullscreen(on) }
        }
    }

    // addJavascriptInterface only reaches pages loaded after the call, so the
    // bridge goes in before loadUrl and comes out on the first foreign page.
    private fun loadTrusted(url: String) {
        trustedOrigin = originOf(url)
        attachBridge()
        webView.loadUrl(url)
    }

    private fun attachBridge() {
        if (trustedOrigin != null && !bridgeAttached) {
            webView.addJavascriptInterface(bridge, BRIDGE_NAME)
            bridgeAttached = true
        }
    }

    private fun detachBridge() {
        if (bridgeAttached) {
            webView.removeJavascriptInterface(BRIDGE_NAME)
            bridgeAttached = false
        }
    }

    // scheme://host[:port] with the default port dropped, lowercased, no path.
    // Null for anything that is not an http(s) URL with a host.
    private fun originOf(url: String): String? {
        if (url.isEmpty()) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = try { uri.port } catch (e: NumberFormatException) { return null }
        val standard = if (scheme == "https") 443 else 80
        return if (port <= 0 || port == standard) "$scheme://$host" else "$scheme://$host:$port"
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
            "  function applyState(on) {" +
            "    active = on;" +
            "    emitChange();" +
            "  }" +
            "  window.__fotonSetFullscreen = function (on) {" +
            "    on = !!on;" +
            "    if (on === active) { return; }" +
            "    applyState(on);" +
            "  };" +
            "  Element.prototype.requestFullscreen = function () {" +
            "    applyState(true);" +
            "    fotonHost.fullscreen(true);" +
            "    return Promise.resolve();" +
            "  };" +
            "  Document.prototype.exitFullscreen = function () {" +
            "    applyState(false);" +
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
        // The callback runs once the shim exists, so a page that reloads while
        // fullscreen is healed instead of ending up with hidden bars and a
        // document that believes it is windowed.
        view.evaluateJavascript(shim) { pushFullscreenState() }
    }

    private fun setFullscreen(on: Boolean) {
        if (fullscreen != on) {
            fullscreen = on
            setBarsHidden(on)
        }
        updateOverflow()
        pushFullscreenState()
    }

    private fun toggleFullscreen() {
        setFullscreen(!fullscreen)
    }

    // Host -> page. Idempotent on the page side: the shim ignores a push that
    // matches its own state, so this can never bounce back into the bridge.
    private fun pushFullscreenState() {
        webView.evaluateJavascript(
            "window.__fotonSetFullscreen && window.__fotonSetFullscreen($fullscreen);",
            null
        )
    }

    private fun updateOverflow() {
        if (this::overflow.isInitialized) {
            overflow.visibility = if (fullscreen) View.GONE else View.VISIBLE
        }
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
            .setTitle(R.string.dialog_server_url)
            .setView(input)
            .setPositiveButton(R.string.dialog_open) { _, _ ->
                val url = normalizeUrl(input.text.toString())
                if (url == null) {
                    Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
                    promptForUrl(input.text.toString())
                } else {
                    prefs.edit().putString(KEY_URL, url).apply()
                    loadTrusted(url)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
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
        addMenuItems(menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return handleMenuItem(item.itemId) || super.onOptionsItemSelected(item)
    }

    // Portrait is the one case where the page itself is rendered 90 degrees
    // clockwise to fake landscape (the web app's body.p-rot). This is the
    // native half of that: the gear and the menu panel turn with the page so
    // the overlay reads the same way up as the content under it.
    private fun overlayTurnsClockwise(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    // Single place where the overlay rotation is decided and applied. The
    // transform lands on the gear button and, while it is open, on the menu
    // panel only. No container, no window, no page view is ever rotated.
    private fun applyOverlayRotation() {
        if (!this::overflow.isInitialized) return
        val turned = overlayTurnsClockwise()
        overflow.rotation = if (turned) OVERLAY_TURN else 0f
        // A shown panel was measured for the old orientation, so it goes away
        // rather than being re-placed with stale numbers.
        dismissMenuPanel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOverlayRotation()
    }

    private fun addMenuItems(menu: Menu) {
        menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, R.string.menu_refresh)
        menu.add(Menu.NONE, MENU_URL, Menu.NONE, R.string.menu_server_url)
        menu.add(
            Menu.NONE,
            MENU_FULLSCREEN,
            Menu.NONE,
            if (fullscreen) R.string.menu_exit_fullscreen else R.string.menu_fullscreen
        )
    }

    // The same three items in the same order as the options menu, built as one
    // panel. The panel is the only view that turns, and it lives in its own
    // popup window, so the rotation cannot travel into the activity.
    private fun showOverflowMenu() {
        val items = arrayOf(
            getString(R.string.menu_refresh) to MENU_REFRESH,
            getString(R.string.menu_server_url) to MENU_URL,
            getString(
                if (fullscreen) R.string.menu_exit_fullscreen else R.string.menu_fullscreen
            ) to MENU_FULLSCREEN
        )
        val rowWidth = dp(MENU_ROW_MIN_WIDTH_DP)
        val rowPad = dp(MENU_ROW_PAD_DP)
        val textColor = getColor(android.R.attr.textColorPrimary)
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = menuPanelBackground()
        panel.setPadding(rowPad / 2, rowPad / 2, rowPad / 2, rowPad / 2)
        for ((label, id) in items) {
            val row = TextView(this)
            row.text = label
            row.setTextColor(textColor)
            row.gravity = Gravity.CENTER_VERTICAL
            row.isSingleLine = true
            row.ellipsize = android.text.TextUtils.TruncateAt.END
            row.minimumWidth = rowWidth
            row.setPadding(rowPad, 0, rowPad, 0)
            // One instance per row: a shared Drawable keeps a single callback,
            // so only the last row would ripple.
            row.background = getDrawable(R.drawable.menu_row)
            row.setOnClickListener {
                dismissMenuPanel()
                handleMenuItem(id)
            }
            panel.addView(row, LinearLayout.LayoutParams(rowWidth, dp(MENU_ROW_HEIGHT_DP)))
        }
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val naturalWidth = panel.measuredWidth
        val naturalHeight = panel.measuredHeight

        // A turn never changes the box a view was measured into, so the window
        // is given the panel's post-turn footprint and the panel is pushed half
        // the difference: its centre lands exactly on the window centre, which
        // makes the drawn panel fill the window and nothing spill out of it.
        val turned = overlayTurnsClockwise()
        val windowWidth = if (turned) naturalHeight else naturalWidth
        val windowHeight = if (turned) naturalWidth else naturalHeight
        // setPivotX/Y are the public half of this: the javadoc says the pivot
        // is centred by default and that setting it makes it explicit, which is
        // the centre this geometry assumes. There is no public PIVOT_X_EXPLICIT.
        panel.pivotX = naturalWidth / 2f
        panel.pivotY = naturalHeight / 2f
        panel.rotation = if (turned) OVERLAY_TURN else 0f
        panel.translationX = (windowWidth - naturalWidth) / 2f
        panel.translationY = (windowHeight - naturalHeight) / 2f
        val host = FrameLayout(this)
        // The panel is laid out at its own size inside the smaller post-turn
        // window box, so the host must not clip what the turn pushes out.
        host.clipChildren = false
        host.addView(
            panel,
            FrameLayout.LayoutParams(naturalWidth, naturalHeight, Gravity.TOP or Gravity.START)
        )
        val popup = PopupWindow(windowWidth, windowHeight)
        popup.isFocusable = true
        popup.isOutsideTouchable = true
        popup.elevation = dp(MENU_PANEL_ELEVATION_DP).toFloat()
        popup.contentView = host
        popup.setOnDismissListener { menuPanel = null }
        menuPanel = popup

        // Anchored on the box, not on the gear: the box never turns, so its
        // position needs no inverse-transform arithmetic.
        val anchor = IntArray(2)
        overlayBox.getLocationInWindow(anchor)
        val metrics = resources.displayMetrics
        val left = (anchor[0] + overlayBox.width / 2f).toInt()
            .coerceAtMost((metrics.widthPixels - root.paddingRight - windowWidth).coerceAtLeast(0))
        val top = (anchor[1] + overlayBox.height).toInt()
            .coerceAtMost((metrics.heightPixels - root.paddingBottom - windowHeight).coerceAtLeast(0))
        popup.showAtLocation(root, Gravity.NO_GRAVITY, left, top)
    }

    private fun dismissMenuPanel() {
        val open = menuPanel ?: return
        menuPanel = null
        if (open.isShowing) open.dismiss()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun menuPanelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(MENU_PANEL_RADIUS_DP).toFloat()
        setColor(getColor(android.R.attr.colorBackgroundFloating))
    }

    private fun handleMenuItem(id: Int): Boolean {
        return when (id) {
            MENU_REFRESH -> {
                webView.reload()
                true
            }
            MENU_URL -> {
                promptForUrl(prefs.getString(KEY_URL, null))
                true
            }
            MENU_FULLSCREEN -> {
                toggleFullscreen()
                invalidateOptionsMenu()
                true
            }
            else -> false
        }
    }

    override fun onBackPressed() {
        // The panel is a focusable window of its own, so back has to be closed
        // explicitly here to keep the old menu behaviour: first back closes the
        // menu, it never exits fullscreen or the page behind it.
        if (menuPanel != null) {
            dismissMenuPanel()
            return
        }
        if (fullscreen) {
            setFullscreen(false)
            return
        }
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
        private const val BRIDGE_NAME = "fotonHost"
        private const val MENU_REFRESH = 3
        private const val MENU_URL = 1
        private const val MENU_FULLSCREEN = 2
        private const val OVERFLOW_MARGIN_DP = 8
        private const val OVERFLOW_PAD_DP = 10f
        private const val OVERLAY_TURN = 90f
        private const val MENU_ROW_HEIGHT_DP = 48f
        private const val MENU_ROW_MIN_WIDTH_DP = 200f
        private const val MENU_ROW_PAD_DP = 16f
        private const val MENU_PANEL_RADIUS_DP = 10f
        private const val MENU_PANEL_ELEVATION_DP = 8f
    }
}