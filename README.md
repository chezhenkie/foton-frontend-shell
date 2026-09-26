# foton-frontend-shell

Native frontend container for the foton web UI. One native window, one webview
(WebView2 on Windows, WebKitGTK on Linux, platform WebView on Android) pointed
at a running foton server. Release exe shows no console window. The Android
front lives in `android/` and builds only on GitHub Actions. This crate and the
shell are the FRONTEND process - it never spawns or embeds the server.

Three targets, two languages:

| Target | Language | Build | Output |
| --- | --- | --- | --- |
| Windows x64 | Rust (tao + wry) | `cargo build --release --locked` | `foton-frontend-shell.exe` |
| Linux x64 | Rust (tao + wry, GTK/WebKitGTK) | `cargo build --release --locked` | `foton-frontend-shell` |
| Android | Kotlin (plain `Activity` + `WebView`, zero AndroidX) | `./gradlew :app:assembleDebug` | `app-debug.apk` |

Full design, per-platform internals and the language inventory:
[ARCHITECTURE.md](ARCHITECTURE.md). Android detail: [android/README.md](android/README.md).
How a new APK updates the installed app instead of needing a reinstall:
[ANDROID-SIGNING-AND-UPDATES.md](ANDROID-SIGNING-AND-UPDATES.md).

## Build

Desktop: `cargo build --release --locked` (the committed `Cargo.lock` is
authoritative; CI fails on a stale lock instead of silently resolving different
versions). Linux needs `libwebkit2gtk-4.1-dev`, `libgtk-3-dev`, `librsvg2-dev`,
`patchelf`. CI (`build.yml`) builds
windows-latest MSVC and ubuntu-24.04 on push, gated by a `paths:` filter so
docs-only commits do not pay for a release build; artifacts
`foton-frontend-shell-windows-x64` and `foton-frontend-shell-linux-x64`.
Android: workflow `android-build` (paths `android/**`) -> `app-debug.apk`
artifact, signed with one pinned dev key from a secret, so a new APK updates the
installed app in place instead of needing an uninstall. Android toolchain is
AGP 9.4.0, Gradle 9.6.0 (wrapper committed,
sha256 pinned), JDK 17 (Temurin), compileSdk/targetSdk 36, minSdk 26, with
AGP's built-in Kotlin - there is no `dependencies {}` block and no kotlin
plugin block, so the only third-party code in the APK is the JetBrains
kotlin-stdlib that AGP 9 contributes on its own. Zero Google code ships.

## Run

1. Start the foton server first (mock-bridge on 0.0.0.0:8765).
2. Launch `foton-frontend-shell.exe`. URL resolution order: `--url <url>`, then
   `--port <n>` -> `http://127.0.0.1:<n>/`, then `foton-api-bridge.port` next to
   the exe, then default `http://127.0.0.1:8765/`.
3. A loopback origin is a trusted client: no token, no 2FA prompt.

If the server is not there yet: the desktop shell probes the socket (2 s
budget) before creating its webview and shows an offline page with the URL it
was given; a load that starts but stays unfinished past 10 s marks the window
title "still waiting for <origin>". On Android a saved URL that fails to load
is dropped and re-prompted, so a wrong URL can never stick. The
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
`node tools/shim_test.js` covers both shim strings without a device (40
assertions). On both platforms the channel is origin-gated: only the origin
typed into the shell gets it, on desktop by comparing the IPC request's
scheme + authority, on Android by injecting/removing the interface around
main-frame navigations.

## CI hygiene

Dependabot (`dependabot.yml`) opens weekly `cargo` and `github-actions` version
bumps, so the 263 crates in `Cargo.lock` are covered too. Every third-party
Action is pinned to a commit SHA with the tag in a trailing comment (checkout
v7, cache v6, upload-artifact v7, setup-java v6, dtolnay/rust-toolchain stable,
setup-android v4), and both build workflows declare `permissions: contents:
read`, plus a concurrency group (PR runs are cancelled when a newer push
supersedes them) and per-job `timeout-minutes`. `cache-cleanup.yml` runs weekly
(Mon 03:00) plus manual dispatch: deletes branch caches older than 7 days and
keeps only the newest cache per key-prefix on main (cargo/gradle caches are
lockfile-hashed, so old keys die without cost until a lockfile change forces a
budget-conscious purge under the 10 GB public-repo cap).

## Security

Reporting, the privilege model and the supply-chain rules live in
[SECURITY.md](SECURITY.md). Short version: a reachable page gets exactly one
native call (fullscreen on/off), gated to the operator-configured origin; the
dev keystores are not in the repo.

## License

MIT, see [LICENSE](LICENSE).
