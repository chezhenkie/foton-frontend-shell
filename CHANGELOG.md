# Changelog

## 2026-09-26 (overflow overlay: portrait turn, purple gear)

The overflow overlay now reads the same way up as the page under it. The page
turns itself 90 degrees clockwise in portrait to fake landscape (`body.p-rot` in
the web app), so the native gear and its menu turned sideways next to it.

- **Overlay turns with the page (portrait only).** `overlayTurnsClockwise()` and
  `applyOverlayRotation()` are the single decision point; the gear and the menu
  panel get `rotation = 90f` in portrait and `0f` in landscape, re-applied from
  `onConfigurationChanged` (the manifest already absorbs the change, so the
  activity is never recreated). An open panel is dismissed on a turn instead of
  being re-placed with stale numbers.
- **The turn is fenced in.** Exactly two views are ever transformed:
  `overflow.rotation` and `panel.rotation`. The gear sits alone in its own
  `overlayBox` (the WebView is its sibling, never a child), the panel sits alone
  in its own popup window, and the root, the box, the popup host and the WebView
  never receive a transform. The URL prompt is still a plain upright
  `AlertDialog`; the fullscreen shim and the bridge are untouched. The popup
  window gets the panel's post-turn footprint (width and height swapped) and the
  panel is pushed half the difference, so its centre lands on the window centre
  and the drawn panel fills the window exactly. The panel is anchored on the
  box, which never turns, so no inverse-transform arithmetic is needed.
- **Purple gear, no background box.** The platform `ic_menu_more` glyph on a
  60% white scrim is gone. `res/drawable/ic_menu_gear.xml` is a vector gear in
  `#7C4DFF` (24dp, 8 teeth, centre hole) and the button carries no plate - only
  the theme ripple marks a touch. The panel is a rounded
  `colorBackgroundFloating` rect with a `colorOutline` hairline and
  `colorControlHighlight` row ripples, all resolved from the theme, so it
  follows light and dark.
- **`PopupMenu` replaced by `PopupWindow`.** `PopupMenu` gives no access to its
  content view, and there is no supported way to turn one. Same three items in
  the same order, same shared `handleMenuItem`, same handlers; the dead
  framework options menu still works off the same list. The panel is measured
  once at show time and clamped inside the display and the system bars. Because
  the panel is a focusable window of its own, `onBackPressed` now closes it
  first, which is what the old `PopupMenu` did on its own.

## 2026-09-26 (glib alert disposition)

The Dependabot medium alert on `glib` (`VariantStrIter` unsoundness, fixed in
0.20.0) is dismissed as tolerable risk. The bump is not available anywhere in
this tree: wry 0.57.0 pins `webkit2gtk ==2.0.2`, webkit2gtk 2.0.x requires
`glib ^0.18`, and Dependabot's wry/tao bump PRs do not change that. Verified
against the crates.io dependency API, not guessed. Reasoning in SECURITY.md
("Dependency alert policy"); reopen the alert when webkit2gtk moves past
glib 0.18.

## 2026-09-26 (audit fixes 11-20)

The remaining ten items from `foton-frontend-audit-report.md` chapter 7. The
first genuinely behavioural change of the audit: the native bridge is now
per-origin on both platforms, and an unreachable server is reported instead of
spinning.

- **Per-origin page-to-host bridge (S2, desktop + Android).** Only the origin
  the operator configured gets the fullscreen channel now. Desktop:
  `with_ipc_handler` compares the request's scheme + authority against
  `origin_of(&url)` and ignores anything else, fail-closed when the URL is not
  parseable. Android: `loadTrusted` sets `trustedOrigin` and injects
  `fotonHost` before `loadUrl`; `onPageStarted` detaches it for every other
  main-frame origin; main-frame load errors clear it too. Arbitrary servers
  stay fully reachable - they just stop carrying a native call channel. Same
  documented limitation on both: while the bridge is attached, any frame of the
  page can call it (webkit2gtk attributes every message to the main frame).
- **Unreachable server has a face (Q1, desktop).** wry's page-load handler has
  no failure event, so dead servers used to spin silently. `probe()` now spends
  up to 2 s on a TCP connect before the webview is built and shows a static
  offline page (with the intended URL and the three ways to re-point the shell)
  when nothing answers; a load still running after 10 s changes the window title
  to "still waiting for <origin>" until it finishes. The loop stays on
  `ControlFlow::Wait`/`WaitUntil`, so an idle shell never burns CPU.
- **Icon cannot drift silently (Q2).** `icon.rgba` is decoded with an
  `ICON_SIZE` const and a compile-time length assert, and `tools/make_icon.py`
  now reads `src/main.rs` and refuses to regenerate a size Rust disagrees with.
  Previously a bad icon file would have produced a window with no icon.
- **Both fullscreen shims are tested (Q3/Q6 of the plan).** `tools/shim_test.js`
  extracts and exercises the desktop `FULLSCREEN_SCRIPT` too (fresh vm realm,
  19 new assertions: prototype patching, ESC routing, promise returns, idempotent
  injection) - 40 assertions total, all green.
- **`cargo build --release --locked` in CI (S9).** The lock file is committed;
  CI must not resolve different versions. This exposed a real drift:
  `Cargo.lock` still said `0.1.3` after the version bump to `0.1.4`, which would
  have failed the build; fixed first, so the gate works.
- **All workflows have a concurrency group and a job `timeout-minutes` (P2/P3).**
  PR runs are superseded by a newer push and cancelled; main/dispatch runs are
  not interrupted mid-build. Timeouts: desktop 30 min, Android 45, cache
  cleanup 15.
- **`network_security_config.xml` stays globally cleartext, now as a documented
  decision (S3).** The shell's job is an operator-chosen server; a host
  allow-list in the config would break re-pointing it, and on-tailnet traffic is
  already WireGuard-wrapped. Rationale and residual open-network risk are in
  `android README.md` ("Cleartext").
- **`SECURITY.md` + `.github/CODEOWNERS` added (C9).** The policy states the
  privilege model in one table, names the reporting channel, and records the
  every-frame bridge limitation as a known limitation instead of pretending it
  is closed.
- **Android UI strings moved to `res/values/strings.xml` (Q6).** Dialog title and
  buttons, toast, the three menu items and the overflow content descriptor are
  resources now; no behaviour change.
- **APK verification is four checks, not one (S10).** The Android verify step
  gained `pipefail` (a failed `apksigner verify` used to be swallowed by
  `tee` - a tampered APK would have passed that step), a `keytool` read-back of
  the keystore secret cross-checked against the APK's certificate (catches the
  fallback-debug-key case), the pinned fingerprint check retained (verified
  against the committed keystore with `keytool` - it matches), and an
  `aapt2 dump badging` comparison of applicationId / versionCode / versionName
  against `app/build.gradle.kts`, so a stale APK cannot be uploaded.

## 2026-09-26 (audit fixes 1-10)

Ten items from `foton-frontend-audit-report.md` chapter 7, cheapest first.
No behaviour change on either app beyond the WebView debugging switch.

- Android WebView remote debugging is now off unconditionally:
  `WebView.setWebContentsDebuggingEnabled(false)` at the top of `onCreate`.
  AGP injects `android:debuggable="true"` into debug builds, which turned
  WebView debugging on for every APK this project has ever shipped, so any adb
  attach got arbitrary JS plus the `fotonHost` bridge.
- Signing key: a second copy, AES-256 encrypted, at
  `archive/secrets foton frontend shell.zip` with a sha256 sidecar and
  `archive/RESTORE.txt`. Round-trip verified byte-identical. A genuinely
  off-machine copy is still open.
- MIT `LICENSE` added, and `build.rs` `LegalCopyright` now carries a real
  copyright line instead of the word "MIT".
- The "zero third-party code" claim is corrected everywhere: there is no
  `dependencies {}` block, and the only third-party code in the APK is the
  JetBrains kotlin-stdlib that AGP 9 contributes by itself. The zero-Google
  claim was always true and is unchanged.
- Stale line counts refreshed (Kotlin 332, Gradle DSL 83, XML 81, Python 149,
  YAML 190) and the `dist/` paragraph corrected: artifacts are downloaded CI
  uploads in per-target folders, there is no `shell.pid` and no shipped
  fixed-version WebView2 runtime.
- Dependabot now also watches `cargo`, so the 263 crates in `Cargo.lock` are
  covered.
- `build.yml` gained the same `paths:` filter `android.yml` already had, so
  docs-only commits stop paying for a two-leg LTO release build.
- All eight third-party Action references are SHA-pinned, with the tag as a
  trailing comment. Dependabot bumps them.
- `permissions: contents: read` on `build.yml` and `android.yml`.
- `android.yml` push is scoped to `branches: [main]`, so the keystore secrets
  are not handed to a branch build.

## 2026-09-26 (new app icon)

New icon on every target, from one SVG: `foton-app-icon.svg` in the repo root,
copied byte-identical to `assets/favicon.svg`. Mint `#A8E6CF` rounded-square
background (rx 32), solid, no cutout, with four square-cornered blocks hatched
in `#1A1A1A` (45 degrees, the wide block 18 degrees from vertical),
marker-weight 6px outlines. Desktop 0.1.4:
`tools/make_icon.py` rasters the new geometry into `icon.ico` / `icon.png` /
`icon.rgba` - verified corners transparent, block interiors dark on
green gaps, ico still 7 sizes (16..256). Android: the adaptive foreground vector
carries the blocks as explicit stroke paths in a 512 viewport scaled into the
66dp safe zone (VectorDrawable has no SVG patterns or masks), generated by
`tools/make_android_icon.py`; `ic_launcher_background.xml` is the solid green
layer. versionCode 4, versionName 0.1.1.
`colors.xml` dropped: its background color moved into the drawable.

## Android 0.1.1 (2026-09-25)

Fullscreen state fixed, menu made reachable, one pinned signing key. versionCode
3, versionName 0.1.1. Desktop unaffected: the Rust shim is untouched (it has no
host-side fullscreen trigger, so it never had this class of bug) and the exe
stays at 0.1.3.

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
  opens a PopupMenu with the same items through a shared handleMenuItem. The
  button is GONE while fullscreen. No new dependency; the APK still pulls in no
  third-party library beyond the kotlin-stdlib AGP 9 contributes. Items:
  Refresh (webView.reload()), Server URL...,
  Fullscreen / Exit fullscreen.
- One pinned dev signing key, so APKs update the installed app in place. Before,
  AGP generated a debug key per machine and every CI runner had its own, so each
  APK carried a different certificate (measured: three consecutive builds, three
  signer SHA-256 values) and Android rejected every update - one uninstall per
  build, which also wiped the saved server URL. The keystore now lives in the
  secrets FOTON_KEYSTORE_B64 + FOTON_KEYSTORE_PASSWORD, the local copy is
  gitignored, and a new CI step runs apksigner verify --print-certs and fails the
  job if the certificate is not the pinned
  12:C1:8E:FA:44:4E:4F:A0:53:4A:63:11:EF:52:8C:43:5D:05:06:38:1C:DB:42:81:A6:C0:44:17:B3:84:97:82.
  Builds without the secrets still work and fall back to a throwaway key.
  Full procedure, secrets location and rotation runbook:
  ANDROID-SIGNING-AND-UPDATES.md.
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
now a short entry point that links it. android README.md gained the same Kotlin
detail, a toolchain table and a Known gaps section. Desktop exe unchanged at
0.1.3, APK unchanged at versionCode 1 / versionName 0.1.0.

## Android 0.1.0 (2026-09-19)

First Android front (separate codebase from the Rust shell: a platform
WebView host, not a Rust binary). One android.app.Activity + WebView pointed at
the mock bridge behind Tailscale, with zero Google/AndroidX runtime
dependencies - no Play Services, no Firebase, no analytics, and no Gradle
dependencies block at all, so the only third-party code in the APK is the
kotlin-stdlib that AGP 9's built-in Kotlin contributes itself.

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