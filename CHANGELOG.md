# Changelog

## Android 0.1.1 (2026-09-25)

Fullscreen state fixed, menu made reachable. versionCode 2, versionName 0.1.1.
Desktop unaffected: the Rust shim is untouched (it has no host-side fullscreen
trigger, so it never had this class of bug) and the exe stays at 0.1.3.

- One owner for fullscreen. `fullscreen` is mutated only by `setFullscreen`,
  called from the page bridge, the overflow menu and the back gesture. Before,
  the page's requestFullscreen hid the bars without telling the host, so the
  host flag went stale and the next menu tap showed the bars while the page
  still believed it was fullscreen.
- Host pushes state back into the page via `window.__fotonSetFullscreen`
  (guarded, never calls back, so no ping-pong and no duplicate
  fullscreenchange). The page-driven half of the shim is unchanged.
- Reload while fullscreen is healed: the shim re-asserts the host state from the
  evaluateJavascript callback, instead of leaving hidden bars over a document
  that thinks it is windowed.
- The bar work is no longer copy-pasted. The old menu path set
  webView.systemUiVisibility on pre-R while the bridge path set
  window.decorView.systemUiVisibility, so on API 26-29 the two paths disagreed.
- Back gesture now exits fullscreen first, then walks webview history, then
  closes. Chromium is never asked for real fullscreen, so nothing in the
  platform was doing this.
- The dead options menu is now reachable: an ImageButton (platform
  android.R.drawable.ic_menu_more on a 60% white scrim, 8dp margin, top-end)
  opens a PopupMenu with the same two items through a shared handleMenuItem. The
  button is GONE while fullscreen. No new dependency; the APK still ships zero
  third-party libraries.
- tools/shim_test.js: extracts the shim string from MainActivity.kt and runs it
  against a DOM stub in Node, 21 assertions covering page-driven request/exit,
  the host push, both idempotency guards, the reload heal and the no-bounce
  rule. It cannot drift from the source because it reads the source.

## 2026-09-25 (docs)

Docs only, no shell change, no version bump. New ARCHITECTURE.md covers the
whole project shape: the three targets with their languages (Rust for
Windows/Linux, Kotlin for Android), a language inventory with file sizes, the
desktop Rust startup sequence and URL resolution order, the per-OS split
(WebView2 + winres resources on Windows, WebKitGTK + build_gtk on Linux), the
Kotlin app architecture (view tree, WebView config, fotonHost bridge,
immersive fullscreen, URL lifecycle, manifest configChanges), a desktop-vs-
Android shim diff table, shared concepts and the build/CI matrix. README.md is
now a short entry point that links it. android/README.md gained the same Kotlin
detail, a toolchain table and a Known gaps section. Desktop exe unchanged at
0.1.3, APK unchanged at versionCode 1 / versionName 0.1.0.

## Android 0.1.0 (2026-09-19)

First Android front (separate codebase from the Rust shell: a platform
WebView host, not a Rust binary). One android.app.Activity + WebView pointed at
the mock bridge behind Tailscale, with zero Google/AndroidX runtime
dependencies - no Play Services, no Firebase, no analytics, and no Gradle
dependencies block at all, so the APK ships no third-party code.

- URL: prompted on first launch into SharedPreferences foton_prefs/server_url,
  normalized and validated before saving (bare hosts get http:// prepended,
  whitespace, backslashes, bad scheme, empty host and out-of-range ports are
  rejected). A saved URL that fails to load - host lookup, connect, timeout,
  bad url, unsupported scheme, main frame only - is removed and re-prompted, so
  a wrong URL can never stick.
- Fullscreen parity with the desktop shim: injectFullscreenShim on
  onPageFinished, side effects over a fotonHost addJavascriptInterface bridge
  that hides/shows the system bars. WebView's own onShowCustomView path is
  deliberately unused (Chromium's separate FullScreenView layer composites
  blank on WebGL/canvas pages); system back-swipe is the exit.
- System chrome follows the system: DayNight theme in res/values-v29 with
  windowActionBar=false, night-aware setSystemBarsAppearance, nav bar contrast
  enforcement off.
- Manifest: INTERNET permission only, allowBackup=false,
  enableOnBackInvokedCallback=false (legacy onBackPressed stays authoritative
  and prefers webView history), configChanges so rotation does not recreate the
  Activity.
- Build: android-build workflow on any push touching android/** ->
  :app:assembleDebug on ubuntu-latest, artifact app-debug.apk. Toolchain AGP
  9.4.0, Gradle 9.6.0 (sha256-pinned wrapper), JDK 17 Temurin,
  compileSdk/targetSdk 36, minSdk 26, AGP built-in Kotlin.
- Known gap: the overflow menu (Server URL..., Fullscreen) is unreachable -
  both themes are NoActionBar and there is no Toolbar without AppCompat, so the
  platform never shows it. The first-launch prompt and the shim are the only
  working paths today.

## 2026-09-21 (frontend status note)
Docs only, no shell change, no version bump. Main web frontend
(fotonfrontend-proj, served by the mock bridge this shell points at) reached
its interface polish milestone, user-verified: startup mode now follows the
system prefers-color-scheme (a ?mode= URL switch still overrides), dark start
is the blue-gray gradient #081a38->#232d3a, light start is the l2->l7
gradient #215a77->#ca9673, and the color picker's gradient chip / pick-pair
state is now strictly per dark/light mode. The shell is unaffected - it only
points at the running server. Next frontend work: populating main slab two
with config + info button containers.

## 2026-09-21 (CI housekeeping)
Dependabot enabled for github-actions (checkout/cache/upload-artifact/setup-java
bumped to latest major, 4 stale PRs closed). Added cache-cleanup.yml weekly
cleanup workflow. Docs only, no version bump; desktop exe unchanged at 0.1.3.

## 0.1.3 (2026-09-20)
Desktop: ESC now exits native fullscreen reliably. The injected FULLSCREEN_SCRIPT
catches the ESC keydown in the page and routes it through the same
foton-fullscreen:off IPC that drops the tao window out of borderless fullscreen.
v0.1.2 tried catching ESC in the tao event loop, but on Windows the WebView2
control holds keyboard focus, so the top-level window never receives the key
event (user-verified broken, Alt+F4 required). The page-shim path also works
after a kill feature blanks the page, because the shim lives on the DOM
prototypes, not in page content. Android unaffected: system back-swipe already
exits fullscreen.

## 0.1.2 (2026-09-20)
Desktop: first ESC exit attempt via tao KeyboardInput + webview evaluate_script.
BROKEN on Windows (key never reaches the tao window) - superseded by 0.1.3 in
the same session.

## 0.1.1 (2026-09-18)
Desktop fullscreen shim (M4): injected JS intercepts Element.prototype
requestFullscreen / Document.prototype exitFullscreen, keeps
document.fullscreenElement and fullscreenchange working, posts
foton-fullscreen:on/off over wry IPC, and the handler flips tao to
Fullscreen::Borderless or back. Zero frontend changes. Linux CI on
ubuntu-24.04.

## 0.1.0 (2026-09-17)
Initial crate (M1-M3): wry/tao window + webview, 1280x720 (min 640x360), URL
resolution (--url > --port > foton-api-bridge.port > default 8765), favicon
window icon, Windows exe icon + VERSIONINFO, prebuilt exes in dist.