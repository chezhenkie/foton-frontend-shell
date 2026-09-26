# foton-frontend-shell

Native frontend container for the foton web UI. One native window, one webview
(WebView2 on Windows, WebKitGTK on Linux, platform WebView on Android) pointed
at a running foton server. Release exe shows no console window. The Android
front lives in `android/` and builds only on GitHub Actions. This crate and the
shell are the FRONTEND process - it never spawns or embeds the server.

Three targets, two languages:

| Target | Language | Build | Output |
| --- | --- | --- | --- |
| Windows x64 | Rust (tao + wry) | `cargo build --release` | `foton-frontend-shell.exe` |
| Linux x64 | Rust (tao + wry, GTK/WebKitGTK) | `cargo build --release` | `foton-frontend-shell` |
| Android | Kotlin (plain `Activity` + `WebView`, zero AndroidX) | `./gradlew :app:assembleDebug` | `app-debug.apk` |

Full design, per-platform internals and the language inventory:
[ARCHITECTURE.md](ARCHITECTURE.md). Android detail: [android/README.md](android/README.md).
How a new APK updates the installed app instead of needing a reinstall:
[ANDROID-SIGNING-AND-UPDATES.md](ANDROID-SIGNING-AND-UPDATES.md).

## Build

Desktop: `cargo build --release`. Linux needs `libwebkit2gtk-4.1-dev`,
`libgtk-3-dev`, `librsvg2-dev`, `patchelf`. CI (`build.yml`) builds
windows-latest MSVC and ubuntu-24.04 on push; artifacts
`foton-frontend-shell-windows-x64` and `foton-frontend-shell-linux-x64`.
Android: workflow `android-build` (paths `android/**`) -> `app-debug.apk`
artifact, signed with one pinned dev key from a secret, so a new APK updates the
installed app in place instead of needing an uninstall. Android toolchain is
AGP 9.4.0, Gradle 9.6.0 (wrapper committed,
sha256 pinned), JDK 17 (Temurin), compileSdk/targetSdk 36, minSdk 26, with
AGP's built-in Kotlin - there is no `dependencies {}` block and no kotlin
plugin block, so the APK ships zero libraries.

## Run

1. Start the foton server first (mock-bridge on 0.0.0.0:8765).
2. Launch `foton-frontend-shell.exe`. URL resolution order: `--url <url>`, then
   `--port <n>` -> `http://127.0.0.1:<n>/`, then `foton-api-bridge.port` next to
   the exe, then default `http://127.0.0.1:8765/`.
3. A loopback origin is a trusted client: no token, no 2FA prompt.

Android asks for the server URL on first launch and stores it; a saved URL that
fails to load is dropped and re-prompted, so a wrong URL can never stick. The
phone needs Tailscale running to reach the bridge. The overflow button
top-right changes the URL or toggles fullscreen; it disappears while fullscreen,
and the back gesture leaves fullscreen first.

## Fullscreen

`wry` does not forward the DOM Fullscreen API to the native window
(tauri-apps/wry#1113), so `src/main.rs` injects `FULLSCREEN_SCRIPT`: it shims
`Element.prototype.requestFullscreen` / `Document.prototype.exitFullscreen`,
keeps `document.fullscreenElement` / `fullscreenchange` working, and posts
`foton-fullscreen:on`/`off` over wry IPC; the handler calls tao
`set_fullscreen(Borderless)` / `set_fullscreen(None)`. Zero frontend changes.
The same injected shim listens for the ESC key in the page and exits fullscreen
through the same off path - reliable because the webview holds keyboard focus
(the tao window never sees the key on Windows). It works even after a kill
feature blanks the page, since the shim is attached to the DOM prototypes, not
page content. Android uses a separate `injectFullscreenShim` over a `fotonHost`
`addJavascriptInterface` bridge, hides the system bars instead of resizing a
window, and exits fullscreen on the back gesture. Android additionally pushes
fullscreen state back into the page, because there the host can toggle it too;
`node tools/shim_test.js` covers that logic without a device.

## CI hygiene

Dependabot (`dependabot.yml`) opens weekly github-actions version bumps.
Actions pinned to latest major (checkout@v7, cache@v6, upload-artifact@v7,
setup-java@v6). `cache-cleanup.yml` runs weekly (Mon 03:00) plus manual
dispatch: deletes branch caches older than 7 days and keeps only the newest
cache per key-prefix on main (cargo/gradle caches are lockfile-hashed, so old
keys die without cost until a lockfile change forces a budget-conscious purge
under the 10 GB public-repo cap).
