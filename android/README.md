# foton frontend - Android

Native WebView shell for the foton web UI on Android. One plain
`android.app.Activity` hosting a WebView pointed at the mock bridge behind
Tailscale. Zero Google/AndroidX runtime dependencies (no Play Services, no
Firebase, no analytics) - consistent with the foton zero-Google rule.

## What it is made of

| Language | Where | Size |
| --- | --- | --- |
| Kotlin | `app/src/main/java/com/foton/frontend/MainActivity.kt` | 325 lines, one file, one class |
| Gradle Kotlin DSL | `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts` | 47 lines, no `dependencies {}` block anywhere |
| XML | `AndroidManifest.xml` + 7 resource files | 59 lines |

There is no Java source in the repo, no layout XML (the view tree is built in
code), no `res/menu` (menu items are added programmatically), and no raster
drawables (the launcher icon is a vector plus an adaptive icon). The Kotlin
compiler comes from AGP 9's built-in Kotlin, so there is no kotlin plugin block
and no kotlin-stdlib dependency line either: `assembleDebug` produces an APK
whose only code is `MainActivity` plus the platform's own classes.

## App architecture

### View tree

Built in `onCreate`, no layout resource:

```
Activity (com.foton.frontend.MainActivity, FotonTheme)
  FrameLayout (root, setContentView)
    WebView (MATCH_PARENT x MATCH_PARENT)
    ImageButton (overflow, TOP|END, 8dp margin, GONE while fullscreen)
```

The overflow button is the only chrome the shell draws: the platform glyph
`android.R.drawable.ic_menu_more` on a 60% white scrim, so it stays readable
over a light and a dark page. It opens a `PopupMenu` with the same two items
the (framework) options menu would carry, and it is the reason those items are
reachable at all - see Known gaps history. It is hidden while fullscreen, so
immersive really is immersive.

On API 30+ the root gets `setOnApplyWindowInsetsListener` and is padded by the
`systemBars()` insets while `window.setDecorFitsSystemWindows(false)` lets the
WebView draw edge to edge. So the page fills the display, but content is not
hidden under the status or navigation bars when they are shown, and the
overflow button sits inside the safe area.

### WebView configuration

- enabled: `javaScriptEnabled`, `domStorageEnabled`, `databaseEnabled`
- disabled: `allowFileAccess`, `allowContentAccess` - there is no local content
  to load, so both are off
- `webChromeClient = WebChromeClient()`: the bare platform instance, no
  callbacks overridden. In particular `onShowCustomView` is left unused, see
  Fullscreen shim below.

### JavaScript bridge (page -> host)

```kotlin
webView.addJavascriptInterface(object {
    @JavascriptInterface
    fun fullscreen(on: Boolean) { runOnUiThread { setFullscreen(on) } }
}, "fotonHost")
```

One method, one channel name, mirrored on the desktop side by wry's
`window.ipc`. `@JavascriptInterface` is what makes it reachable from JS on
API 17+; the `runOnUiThread` hop is mandatory because `addJavascriptInterface`
calls arrive on a private WebView thread, not the UI thread.

### Fullscreen state: one owner, two directions

`setFullscreen(on)` is the only function that mutates the `fullscreen` flag, and
the flag is the only source of truth. Three callers:

- the page, via `fotonHost.fullscreen(on)`
- the overflow menu, via `toggleFullscreen()`
- the back gesture, which now exits fullscreen before touching webview history

`setFullscreen` applies the system bars, updates the overflow button, and then
pushes the new state back into the page:

```kotlin
webView.evaluateJavascript("window.__fotonSetFullscreen && window.__fotonSetFullscreen($fullscreen);", null)
```

`__fotonSetFullscreen` sets the page's `active` flag and dispatches
`fullscreenchange`, but never calls `fotonHost` back, and it returns early when
the pushed state already matches. So the two sides cannot ping-pong, and a
redundant state never produces a duplicate `fullscreenchange`.

That direction is what removes the desync the shell used to have: the page
entering fullscreen from its own code used to hide the bars without telling the
host, so the host's label and the host's idea of the state were wrong, and the
next menu tap showed the bars while the page still believed it was fullscreen.
Now every host-driven change (menu, back) is visible to the page, and every
page-driven change is visible to the host.

`setBarsHidden` is the single implementation of the bar work. It used to be
copy-pasted into the menu path with a different target view on pre-R
(`webView.systemUiVisibility` there vs `window.decorView.systemUiVisibility`
here), so on API 26-29 the two paths disagreed; the copy is gone.

### Fullscreen shim

`injectFullscreenShim` (run on `onPageFinished` via `evaluateJavascript`)
overrides `Element.prototype.requestFullscreen` /
`Document.prototype.exitFullscreen` and the `fullscreenElement` /
`fullscreenEnabled` / `fullscreenchange` logic exactly like
`FULLSCREEN_SCRIPT` in `src/main.rs`; the page-driven half of the shim is
byte-for-byte the old behaviour, the only addition is `__fotonSetFullscreen`.
The side effect goes through the `fotonHost` bridge, which toggles immersive
mode (`hide` / `show` status + navigation bars). WebView's own fullscreen path
(`onShowCustomView`, which Chromium serves through a separate `FullScreenView`
layer) is NOT used - on WebGL/canvas pages that layer composites blank, so the
page requests never reach Chromium's fullscreen machinery.

Differences from the desktop shim, and only these:

| | Desktop | Android |
| --- | --- | --- |
| Inject | `with_initialization_script` (pre-page) | `evaluateJavascript` on `onPageFinished` |
| Channel | `window.ipc.postMessage` | `fotonHost.fullscreen(bool)` |
| Host action | tao `set_fullscreen` | immersive system-bar hide/show |
| ESC keydown branch | yes | no, the back gesture exits instead |
| Host -> page push | none needed, the host has no trigger | `window.__fotonSetFullscreen` |
| Idempotency guard | `window.__fotonFullscreenShim` | same, plus one for the state push |

Immersive mode itself is two paths: on API 30+ `WindowInsetsController.hide(
statusBars() or navigationBars())` with
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; below that, the deprecated
`SYSTEM_UI_FLAG_IMMERSIVE_STICKY` set on `decorView.systemUiVisibility`.

Because the shim never enters Chromium's real fullscreen, nothing in the
platform maps the back gesture to it. The app does that itself: `onBackPressed`
checks `fullscreen` first, so back leaves fullscreen, and only then walks
webview history, and only then closes the app.


### System bar appearance

`applyBarAppearance` reads `Configuration.uiMode` and sets or clears
`APPEARANCE_LIGHT_STATUS_BARS` / `APPEARANCE_LIGHT_NAVIGATION_BARS` so the bar
icons contrast with the current night mode, and disables
`isNavigationBarContrastEnforced` on API 29+. Paired with the v29 DayNight
theme, the chrome follows the system.

### URL lifecycle

- stored in `SharedPreferences("foton_prefs")` under key `server_url`
- missing or blank on first launch -> `promptForUrl(null)`
- `normalizeUrl` rejects empty input, embedded whitespace, backslashes, a
  non-http(s) scheme, an empty host, and a port outside 0..65535; a bare host
  gets `http://` prepended. An invalid entry re-prompts with the bad text
  still in the field.
- `onReceivedError`, main frame only, for `ERROR_HOST_LOOKUP`,
  `ERROR_CONNECT`, `ERROR_TIMEOUT`, `ERROR_BAD_URL`,
  `ERROR_UNSUPPORTED_SCHEME`: the saved key is removed and the dialog is shown
  again, so an unreachable URL cannot stick

### Navigation and lifecycle

- manifest `configChanges` covers orientation, screenSize, keyboardHidden,
  smallestScreenSize, screenLayout: rotation does not recreate the Activity, so
  the WebView and its page state survive it
- `enableOnBackInvokedCallback="false"`: the legacy `onBackPressed` override
  stays authoritative, and there it exits fullscreen, then prefers
  `webView.canGoBack()` -> `goBack()`, before letting the system close the app
- `onDestroy` calls `webView.destroy()`

## Build

Local disk stays clean: the APK builds entirely on GitHub Actions.

- Push anything under `android/` or the workflow file -> `.github/workflows/android.yml` runs `:app:assembleDebug`.
- Artifact: Actions > run > Artifacts > `app-debug-apk/app-debug.apk` (debug-signed, sideload-installable).

Toolchain (as pinned): AGP 9.4.0, Gradle 9.6.0 (wrapper committed, sha256 pinned), JDK 17 (Temurin), compileSdk/targetSdk 36, minSdk 26, built-in Kotlin (AGP 9, no kotlin plugin block needed).

| Setting | Value | Where |
| --- | --- | --- |
| AGP | 9.4.0, `apply false` at root, applied in `:app` | `build.gradle.kts` |
| Gradle | 9.6.0, `distributionSha256Sum` pinned | `gradle/wrapper/gradle-wrapper.properties` |
| namespace / applicationId | `com.foton.frontend` | `app/build.gradle.kts` |
| version | versionCode 2, versionName 0.1.1 | `app/build.gradle.kts` |
| SDK | compileSdk 36, targetSdk 36, minSdk 26 | `app/build.gradle.kts` |
| Java | source/target 17 | `compileOptions` |
| release buildType | `isMinifyEnabled = false` | `app/build.gradle.kts` |
| repositories | google(), mavenCentral(), gradlePluginPortal() | `settings.gradle.kts` |
| flags | `-Xmx2g`, `useAndroidX=true`, `nonTransitiveRClass=true` | `gradle.properties` |

`useAndroidX` is an opt-in switch for tooling, not a dependency pull: with no
`dependencies {}` block the APK still contains no third-party code. The CI job
gets its SDK from `android-actions/setup-android@v4`, and `local.properties` is
gitignored, so no machine path is committed.

## Install on the phone

1. Download `app-debug.apk` from the artifact.
2. Sideload: `adb install app-debug.apk` (USB or wireless debugging), or copy the file to the phone and open it (allow unknown-sources once).
3. First launch prompts for the server URL. Three equivalent forms: `http://<tailscale-ip>:8765/`, `http://<host>.<tailnet>.ts.net:8765/` (full MagicDNS), or `http://<host>:8765/` (short MagicDNS).
4. Phone must run the Tailscale app so it can reach the bridge.

Overflow button, top-right of the WebView: change the server URL, toggle
fullscreen. A URL is validated before it is saved, and a saved URL that fails to
load (typo, unreachable host) is dropped automatically with a fresh prompt - a
wrong URL can never stick.

## Server config on the bridge

Dev runs disable 2FA so the phone loads straight in. `mock-bridge/server.js` bakes `const TWOFA_OFF = true;` - a plain `node server.js` start skips all 2FA/session gating and reports 2FA disabled; flip the line to `false` to re-enable for acceptance/sniff runs. All 2FA/TOTP/session machinery stays intact. While 2FA is off, `twofa.js` removes the 2FA UI from the page entirely for remote clients (no gate, no demo pill).

## Cleartext (dev-permissive by design)

`res/xml/network_security_config.xml` currently allows cleartext globally because the tailnet host is not fixed yet. Tighten by replacing the base-config with a scoped domain-config once the host + TLS settle:

```xml
<domain-config cleartextTrafficPermitted="true">
  <domain includeSubdomains="false">laptop.tailnet-name.ts.net</domain>
</domain-config>
```

## Zero-Google verification

On-device lane reuses the desktop boundary (as-is): capture with PCAPdroid v1.9.0 (no-root VPN capture) with App Filter set to the foton app only, export the pcap, then run the in-repo analyze.py against sniff/google-ipv4-prefixes.txt:

```
python sniff/analyze.py --input <phone.pcap> --type pcap --google-prefixes sniff/google-ipv4-prefixes.txt
```

analyze.py reads pcap natively (pure python), so this works unchanged from the shell lane. Expect 0.00% to Google ASN. Exclusions identical to desktop: OS-level WebView updates and Play traffic are out of scope by the refactor-doc boundary; app-process sockets (including WebView engine traffic, which runs in our process) are in scope.

## Layout

```
android/
  settings.gradle.kts        repos, rootProject.name, include(":app")
  build.gradle.kts           AGP 9.4.0 (apply false)
  gradle.properties          jvmargs, useAndroidX, nonTransitiveRClass
  gradle/wrapper/            (jar + properties, sha256-pinned 9.6.0)
  gradlew, gradlew.bat
  dist/app-debug.apk         (pulled artifact, gitignored)
  app/build.gradle.kts       the only module; namespace, SDK levels, no deps
  app/src/main/AndroidManifest.xml     (INTERNET only, allowBackup=false, back = goBack)
  app/src/main/java/com/foton/frontend/MainActivity.kt   the whole app
  app/src/main/res/values/{strings,themes}.xml
  app/src/main/res/values-v29/themes.xml                DayNight theme, no action bar
  app/src/main/res/values/colors.xml                     (launcher icon palette #A8E6CF)
  app/src/main/res/drawable/ic_launcher_foreground.xml
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml     (adaptive favicon icon)
  app/src/main/res/xml/network_security_config.xml
```

Icon lineage: the same favicon geometry as the desktop, hand-mirrored. The
desktop rasterizer `tools/make_icon.py` writes `GREEN = 0xA8, 0xE6, 0xCF` and
`DARK = 0x1A, 0x1A, 0x1A`; here the adaptive icon background is that green and
the vector foreground strokes are that dark, in a 108x108 viewport. No rasters
ship in the APK.

## Testing the shim

`tools/shim_test.js` pulls the shim string straight out of `MainActivity.kt`
(so it can never drift from the source) and runs it against a minimal DOM stub
in Node: 21 assertions covering page-driven request/exit, the host push, the
idempotency guard, the reload-while-fullscreen heal, and the no-bounce rule.

```
node tools/shim_test.js
```

No device needed. Visual and OS-level behaviour (bars, gesture nav, the
overflow button) is on-device and not covered by it.

## Known gaps

- The framework options menu is still dead code. `onCreateOptionsMenu` /
  `onOptionsItemSelected` exist and are correct, but both themes are
  `NoActionBar` and there is no Toolbar (no AppCompat or Material on the
  classpath), so the platform never shows it. The overflow button covers the
  same two items through the shared `handleMenuItem`; if an action bar is ever
  added, the platform path lights up for free.
- The overflow button overlays the page's top-right corner. If the web UI puts
  something interactive there, the button has to move.
- Release signing does not exist: CI runs `assembleDebug`, so the APK carries
  the AGP debug key and is sideload-only.
- Cleartext is permitted globally (see above).

## See also

`../ARCHITECTURE.md` for the cross-platform design and the desktop side,
`../CHANGELOG.md` for history. The original plan and the operator manual live
outside this repo: foton-frontend-shell-android-plan.md and
foton-frontend-shell-android-manual.md.
