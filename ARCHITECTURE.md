# Architecture

## What this repo is

Three frontends, one per platform, for the same remote web UI. The shell owns a
native window and a webview and a small platform bridge, and nothing else. All
UI, all state, all logic live in the foton web frontend served by the foton
server. The shell never spawns, embeds or proxies the server: it is the
FRONTEND process, pointed at an already-running one.

Shared responsibilities, identical on all three targets:

- resolve which URL to show (see URL resolution, per platform)
- hand the page a fullscreen API that actually reaches the native window
- keep the webview surface as large as the window and the OS allows
- show the foton icon, windowed (desktop) or on the launcher (Android)
- stay out of the way otherwise: no tabs, no history UI, no downloads, no
  devtools, no tray icon, no autostart, no server spawn

## The three targets

| Target | Language | Source | Window + webview engine | Build | Output |
| --- | --- | --- | --- | --- | --- |
| Windows x64 | Rust 2021 | `src/main.rs` (419 lines) + `build.rs` (16) | tao Win32 window + wry WebView2 | cargo, winres build script | `foton-frontend-shell.exe` |
| Linux x64 | Rust 2021 | same sources as Windows | tao GTK window + wry WebKitGTK 4.1 | cargo | `foton-frontend-shell` |
| Android | Kotlin | `android/app/src/main/java/com/foton/frontend/MainActivity.kt` (388 lines) | platform `Activity` + platform `WebView` (Chromium) | Gradle + AGP | `app-debug.apk` |

One Rust binary covers Windows and Linux; the split is compile-time, through
`cfg`, not runtime. The Android app is a separate codebase by necessity: it is
a platform WebView host, not a Rust binary, so it shares the design and the
JavaScript shim, not the code.

## Language inventory

| Language | Where | Size | Notes |
| --- | --- | --- | --- |
| Rust | `src/main.rs`, `build.rs` | 435 lines | edition 2021, deps `wry` 0.56 + `tao` 0.36, `winres` 0.1 as a Windows-only build dep; `#[cfg(test)]` unit tests included |
| Kotlin | `MainActivity.kt` | 388 lines | one file, one class, zero Java sources in the repo |
| Gradle Kotlin DSL | `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts` | 80 lines | no `dependencies {}` block anywhere |
| XML | manifest + 8 resource files | 97 lines | manifest, 2 themes (`values` + `values-v29`), strings, 2 launcher icon layers, adaptive icon, the overflow gear, network security config |
| Python | `tools/make_icon.py` | 163 lines | stdlib only (`math`, `os`, `struct`, `zlib`), pure-python 4x supersampled rasterizer |
| JavaScript | `tools/shim_test.js` | 211 lines | Node test harness for both shim strings (Android + desktop), not shipped in any app |

YAML (3 workflows + `dependabot.yml`, 270 lines) is GitHub Actions only. Line
counts are whole-file counts as of 2026-09-26.

No JavaScript ships in any app. The only JavaScript that reaches a device is
the fullscreen shim, injected as a string literal from the host (Rust const,
Kotlin string concatenation) into the page at runtime; `tools/shim_test.js`
exercises that same string in Node.

## Desktop architecture (Rust)

### Crate layout

- `src/main.rs` - the whole runtime: constants, `FULLSCREEN_SCRIPT`, `main()`,
  `origin_of()` / `from_origin()` (the IPC origin gate), `probe()`,
  `window_icon()`, `resolve_url()`, the error page, and a `#[cfg(test)]` module
  covering the gate, the socket probe and the error-page escaping.
- `build.rs` - Windows only. Compiles `assets/icon.ico` plus VERSIONINFO
  (ProductName, FileDescription, CompanyName, LegalCopyright, OriginalFilename,
  ProductVersion, FileVersion) into the exe with `winres`. On non-Windows the
  body is `cfg`-ed out, so the script compiles to nothing.
- `assets/icon.rgba` - 64x64 raw RGBA, embedded with `include_bytes!`, so the
  window icon costs zero runtime file IO.
- `assets/icon.ico` - 7 sizes (16, 24, 32, 48, 64, 128, 256) for the exe.
- `assets/icon.png` - 256x256, written by the same tool but not consumed by any
  build file; a raster reference for docs and the web frontend.
- `assets/favicon.svg` - the icon geometry, a byte-identical copy of
  `foton-app-icon.svg` in the repo root. `tools/make_icon.py` traces it; the
  Android vector drawables mirror it with precomputed hatch segments
  (VectorDrawable has no SVG patterns or masks).

### Release profile

`opt-level = "z"` (optimize for size), `lto = true`, `codegen-units = 1`,
`strip = true`, `panic = "abort"`. `#![cfg_attr(not(debug_assertions,
windows_subsystem = "windows")]` hides the console window in release, keeps it
in debug.

### Startup sequence

1. `resolve_url()` - see below.
2. `origin_of()` parses the URL once; the (scheme, authority) pair is the IPC
   gate below, and its text form is what the window title shows while a load is
   slow.
3. `EventLoop::new()`, then `WindowBuilder` with title "foton frontend shell",
   inner size 1280x720 logical, minimum 640x360, window icon from `icon.rgba`
   (a `const` assert ties `icon.rgba`'s byte count to `ICON_SIZE`, so a wrong
   icon file is a compile error, not an iconless window at runtime).
4. `window.set_background_color(Some((0, 0, 0, 255)))` - opaque black, so no
   white flash before the page paints.
5. `probe()` - a TCP connect with a 2 s budget across the resolved addresses.
   wry reports page loads as Started/Finished only (there is no failure event),
   so an unreachable server would otherwise leave the webview on its blank,
   unexplained default error page. Unreachable builds the offline error HTML
   instead of navigating.
6. `WebViewBuilder::new()` with `with_initialization_script(FULLSCREEN_SCRIPT)`,
   `with_on_page_load_handler` (Started/Finished feed the slow-load timer),
   `with_ipc_handler` gated on `from_origin` and mapping the two fullscreen
   messages to `tao` fullscreen calls, and either `with_url` (reachable) or
   `with_html` (the unreachable error page).
7. Build. Non-Linux: `builder.build(&*window)`. Linux: `build_gtk(
   window.gtk_window())` through `tao::platform::unix::WindowExtUnix` and
   `wry::WebViewBuilderExtUnix`.
8. `event_loop.run` - `ControlFlow::Wait`, `CloseRequested` -> `Exit`, plus the
   slow-load timer below. That is the entire event handling: no menu, no
   accelerators, no resize logic (the webview tracks the window by itself).

### Slow-load title (audit Q1)

wry's `on_page_load_handler` reports `Started` and `Finished` and nothing else;
there is no failure event, so a server that accepts TCP but never answers would
otherwise spin silently. Two mitigations:

- before the webview is created, `probe()` spends at most `PROBE_BUDGET` (2 s)
  doing `TcpStream::connect_timeout` on the URL's host:port. Nothing listening ->
  the shell shows a static error page instead of a blank screen, with the
  configured URL in it and the three ways to point the shell elsewhere. This is
  best effort: it proves a socket is open, not that HTTP will be answered.
- a page load still not `Finished` after `SLOW_LOAD_AFTER` (10 s) changes the
  window title to "foton frontend shell - still waiting for <origin>", and it
  reverts when the load lands. The loop uses `WaitUntil`, not `Poll`: between
  wake-ups the process sleeps, an idle shell costs nothing.

### IPC is per-origin (audit S2)

The handler receives messages with the sending document's URL attached, and wry
passes whatever arrives, so the handler first compares the request's scheme +
authority with the configured origin (`from_origin`); anything else is ignored.
The origin is whatever the operator configured - there is no host allow-list, by
design. Senders with no authority at all (`file:`, `data:`) cannot even be built
into a `Request` by wry, so they are dropped before the handler runs;
`src/main.rs` asserts that in a unit test so a http-crate upgrade cannot turn it
into a silent gap.

Two backend facts stand behind the comparison: the project ships Windows and
Linux, so the request URL is WebView2's `WebMessageReceivedEventArgs::Source`
(the sending document's URL) or WebKitGTK's main frame URL. On webkit2gtk the
main-frame URL is what lands in the request: an iframe of another origin is
attributed to the main frame, so the gate sees it as the trusted origin. That is
a real, documented wry limitation ("The request URL is not supported on iframes
and the main frame URL is used instead"); the action an iframe can trigger is a
fullscreen toggle.

The window is `Rc<tao::Window>` and is cloned into the IPC handler, so the
handler can flip fullscreen without reaching back through the event loop.

### URL resolution (desktop)

Strict order, first hit wins:

1. `--url <url>` - used verbatim, no validation.
2. `--port <n>` - parsed as `u16`, becomes `http://127.0.0.1:<n>/`.
3. `foton-api-bridge.port` - a text file next to the exe, trimmed, parsed as
   `u16`. This is how the shell finds a server the user started by other means.
4. Default `http://127.0.0.1:8765/`.

All fallbacks are loopback, so the default deployment is a trusted client
origin: no token, no 2FA prompt.

### Fullscreen bridge (page -> host)

`wry` does not propagate the DOM Fullscreen API to the native window
(tauri-apps/wry#1113), so the page's `requestFullscreen()` would silently do
nothing. `FULLSCREEN_SCRIPT` shims it:

- overrides `Element.prototype.requestFullscreen` and
  `Document.prototype.exitFullscreen`
- defines `document.fullscreenElement` (returns `documentElement` while active)
  and `document.fullscreenEnabled` (always true), so page-side fullscreen
  layout logic keeps working unchanged
- dispatches a synthetic `fullscreenchange` event on every toggle, so page
  listeners fire as they normally would
- posts `foton-fullscreen:on` / `foton-fullscreen:off` over `window.ipc`
- listens for `keydown` on `document` and routes ESC through the same off path

The IPC handler maps those to `win.set_fullscreen(Some(Fullscreen::Borderless(
None)))` and `win.set_fullscreen(None)`.

Two design details worth keeping:

- **The shim lives on the DOM prototypes, not in page content.** It survives a
  navigation, a reload, and a feature that blanks the page.
- **ESC is handled inside the page.** v0.1.2 tried `tao` keyboard events and it
  was user-verified broken on Windows: the WebView2 control holds keyboard
  focus, so the top-level window never sees the key. The page shim does see it.
  That is what 0.1.3 shipped.

### Windows specifics

- Webview engine is WebView2 (Edge runtime), at whatever version the machine has
  installed. The project ships no fixed-version runtime: `build.yml` is a plain
  `cargo build --release` with no runtime packaging step, so there is no
  `dist/*.WebView2/` folder to place next to the exe. The `*.WebView2/` rule in
  `.gitignore` is left in place for when one is ever added.
- `build.rs` embeds the icon and VERSIONINFO; the cargo target is MSVC in CI.
- `dist/` is where downloaded CI artifacts land, one folder per upload:
  `dist/foton-frontend-shell-windows-x64/foton-frontend-shell.exe` and
  `dist/foton-frontend-shell-linux-x64/foton-frontend-shell`. There is no
  `shell.pid`; nothing in the tree writes one.

### Linux specifics

- Webview engine is WebKitGTK 4.1, so the build needs
  `libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev patchelf` (installed in CI).
- `build_gtk` needs the `GtkWindow` handle out of the tao window, hence the
  `gtk_window()` call; the builder is otherwise identical to Windows.
- No exe icon and no VERSIONINFO: `build.rs` is Windows-gated.
- The 64x64 window icon from `icon.rgba` still applies.

## Android architecture (Kotlin)

Full detail in `android README.md`. Shape of it:

- one `android.app.Activity` subclass, built entirely in code: a `FrameLayout`
  root holding a full-size `WebView` and one `ImageButton` overflow button, no
  layout XML
- no AndroidX, no AppCompat, no Material, no Play Services, no Firebase, no
  analytics: `app/build.gradle.kts` has no `dependencies {}` block at all, so
  the only third-party code in the APK is the JetBrains kotlin-stdlib that AGP
  9's built-in Kotlin contributes by itself. `gradle.properties` still sets
  `android.useAndroidX=true` and `android.nonTransitiveRClass=true`; those are
  opt-in switches for tooling, not a pull, so they do not contradict the
  zero-Google rule.
- JavaScript bridge `fotonHost` (`@JavascriptInterface fun fullscreen(on:
  Boolean)`) replaces the desktop `window.ipc` channel; calls arrive off the UI
  thread, so it hops through `runOnUiThread`. The bridge is injected only for
  the configured origin (`loadTrusted` before `loadUrl`, `onPageStarted`
  compares and detaches) - the same gate the desktop handler enforces.
- an unreachable server is a first-class case, not a blank screen: the desktop
  shows a static error page for 2 s-unreachable hosts, and a load stuck past
  10 s changes the window title until it finishes. Android self-heals a failed
  URL: the saved entry is dropped and the dialog reopens.
- fullscreen has exactly one owner: the `fullscreen` flag, mutated only by
  `setFullscreen`, which is called from the page bridge, the overflow menu and
  the back gesture. The host pushes state back into the page through
  `window.__fotonSetFullscreen`, so neither side can be wrong about the other.
- URL persistence is `SharedPreferences` (`foton_prefs` / `server_url`) with
  validation on save and self-healing on load failure.
- `res/` is 7 small files; the launcher icon is a vector drawable plus an
  adaptive icon, no raster assets.
- Only permission is `android.permission.INTERNET`; `allowBackup=false`.
- `WebView.setWebContentsDebuggingEnabled(false)` in `onCreate`, unconditionally.
  The platform default follows `android:debuggable`, and AGP injects that into
  every debug build, so without this line any adb attach reached arbitrary JS
  plus the `fotonHost` bridge above.

### Why the host pushes fullscreen state into the page

The page and the host each hold a fullscreen flag, and either side can be the
one that acts first: the page calls `requestFullscreen()` from its own code, or
the user taps the overflow button / swipes back. With only a page -> host
channel the host's flag silently goes stale, and the next host-driven toggle
does the opposite of what the user sees on screen. So the channel runs both
ways, and `__fotonSetFullscreen` is deliberately one-way and guarded: it never
calls `fotonHost`, and it returns early when the pushed state already matches,
so there is no feedback loop and no duplicate `fullscreenchange`.

The same push heals a reload: a fresh document starts with `active = false`
while the bars are still hidden, so `injectFullscreenShim` re-asserts the host
state in the `evaluateJavascript` callback, after the shim exists.

The desktop shim has no equivalent push and does not need one: nothing on the
desktop can enter or leave fullscreen except the page itself, so the host never
holds a competing flag.

### Why Android does not use the WebView fullscreen API

WebView's own fullscreen path is `WebChromeClient.onShowCustomView`, which
Chromium serves through a separate `FullScreenView` layer. On WebGL and canvas
pages that layer composites blank, so the page's request never reaches
Chromium's fullscreen machinery at all. Hence the same shim as desktop, but
the host side hides the status and navigation bars instead of changing a
window mode. Because Chromium is never asked, nothing in the platform maps the
back gesture to fullscreen either, so the app does it: `onBackPressed` exits
fullscreen first, then walks webview history, then closes.

### What differs from the desktop shim, exactly

| | Desktop (`FULLSCREEN_SCRIPT`) | Android (`injectFullscreenShim`) |
| --- | --- | --- |
| Injected by | `with_initialization_script` (before page scripts) | `evaluateJavascript` on `onPageFinished` |
| Channel | `window.ipc.postMessage` | `fotonHost.fullscreen(bool)` |
| Host action | `tao` `set_fullscreen` | hide/show system bars, immersive |
| Exit trigger | ESC keydown in the page | system back gesture (host side) |
| Host -> page push | none, nothing else can toggle it | `window.__fotonSetFullscreen` |
| Idempotency guard | `window.__fotonFullscreenShim` | same, plus one on the state push |

Everything else - the two prototype overrides, the `fullscreenElement` and
`fullscreenEnabled` definitions, the synthetic `fullscreenchange` dispatch, the
`Promise.resolve()` return - is deliberately identical, so one page works
unchanged on both.

## Shared concepts

- **One shim contract.** The page always sees a working
  `document.fullscreenElement` / `fullscreenchange` pair. What "fullscreen"
  physically means is a host decision: borderless window on desktop, immersive
  system bars on Android.
- **One icon lineage.** `foton-app-icon.svg` (= `assets/favicon.svg`, identical
  bytes) -> `tools/make_icon.py` -> `icon.ico` / `icon.png` / `icon.rgba` for the
  desktop, and `tools/make_android_icon.py` mirrors the same geometry into
  `res/drawable/ic_launcher_foreground.xml`
  (hatch as explicit stroke paths) plus `res/drawable/ic_launcher_background.xml`,
  the solid `#A8E6CF` layer. The desktop rasterizer constants (`GREEN = 0xA8, 0xE6, 0xCF`,
  `DARK = 0x1A, 0x1A, 0x1A`) are the same colors the Android vectors use.
- **One trust rule.** A loopback origin is a trusted client: no token, no 2FA
  prompt. Android deviates only because it must reach a tailnet host, and the
  tailnet is the trust boundary there.
- **One gate rule (audit S2).** Whatever origin the operator configured is the
  one that gets the native bridge, and no other page gets it: desktop compares
  the IPC request's scheme + authority, Android injects/removes
  `fotonHost` around main-frame navigations. The origin is a runtime choice, so
  the gate tracks the configuration rather than a host allow-list.
- **Builds stay in CI.** Neither the desktop exes nor the APK are built on a
  developer machine; every artifact comes from a workflow run.

## Repo layout

```
Cargo.toml, Cargo.lock      Rust crate (desktop)
build.rs                    Windows icon + VERSIONINFO
src/main.rs                 the entire desktop runtime
assets/                     favicon.svg, icon.ico, icon.png, icon.rgba
tools/make_icon.py          pure-python icon rasterizer
tools/shim_test.js          Node test of both fullscreen shim strings
dist/                       downloaded CI artifacts, one folder per target
                             (windows-x64, linux-x64) plus android/dist for the APK
android/                    the Kotlin app, a separate Gradle project
  settings.gradle.kts, build.gradle.kts, gradle.properties
  gradle/wrapper/           jar + properties, sha256-pinned Gradle 9.6.0
  gradlew, gradlew.bat
  app/build.gradle.kts      the only module, no dependencies
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/foton/frontend/MainActivity.kt
  app/src/main/res/         values, values-v29, drawable, mipmap-anydpi-v26, xml
.github/workflows/          build.yml (desktop), android.yml (apk), cache-cleanup.yml
.github/dependabot.yml      weekly cargo and github-actions bumps
.github/CODEOWNERS          review routing, mirrored in SECURITY.md
SECURITY.md                 security policy: how to report, privilege model, supply chain
```

## Build and CI summary

| | Desktop | Android |
| --- | --- | --- |
| Toolchain | Rust stable (MSVC on Windows) | JDK 17 Temurin, Gradle 9.6.0 wrapper, AGP 9.4.0 |
| Command | `cargo build --release --locked` | `./gradlew :app:assembleDebug` |
| Runners | windows-latest, ubuntu-24.04 | ubuntu-latest |
| Triggers | push to main/master touching `Cargo.*`, `build.rs`, `src/**`, `assets/**` or the workflow; any PR; dispatch | push to main touching `android/**` or the workflow; dispatch |
| Concurrency | one run per ref; PR runs cancelled on newer push, main/dispatch runs not interrupted | same |
| Job timeout | 30 min | 45 min |
| Cache | `actions/cache` (SHA-pinned) keyed on `hashFiles('Cargo.lock')` | `setup-java` gradle cache |
| Artifact | `foton-frontend-shell-{windows,linux}-x64` | `app-debug-apk` |
| Signing | none (CI-built, unsigned) | one pinned dev key from a secret, so APKs update in place; fingerprint gated in CI |
| Token scope | `permissions: contents: read` | `permissions: contents: read` |

Every third-party Action is pinned to a commit SHA with the tag kept as a
trailing comment, so a moved major tag cannot become code execution in the
build. Dependabot opens the bump PRs.

`cache-cleanup.yml` runs weekly (Mon 03:00 UTC) and on dispatch: it drops
branch caches older than 7 days and keeps only the newest cache per key prefix
on main, which is what keeps the repo under the 10 GB public-repo cap.
