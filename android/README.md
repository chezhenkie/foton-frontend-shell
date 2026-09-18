# foton frontend - Android

Native WebView shell for the foton web UI on Android. One plain android.platform Activity hosting a WebView pointed at the mock bridge behind Tailscale. Zero Google/AndroidX runtime dependencies (no Play Services, no Firebase, no analytics) - consistent with the foton zero-Google rule.

## Build

Local disk stays clean: the APK builds entirely on GitHub Actions.

- Push anything under `android/` or the workflow file -> `.github/workflows/android.yml` runs `:app:assembleDebug`.
- Artifact: Actions > run > Artifacts > `app-debug-apk/app-debug.apk` (debug-signed, sideload-installable).

Toolchain (as pinned): AGP 9.4.0, Gradle 9.6.0 (wrapper committed, sha256 pinned), JDK 17 (Temurin), compileSdk/targetSdk 36, minSdk 26, built-in Kotlin (AGP 9, no kotlin plugin block needed).

## Install on the phone

1. Download `app-debug.apk` from the artifact.
2. Sideload: `adb install app-debug.apk` (USB or wireless debugging), or copy the file to the phone and open it (allow unknown-sources once).
3. First launch prompts for the server URL. Three equivalent forms: `http://<tailscale-ip>:8765/`, `http://<host>.<tailnet>.ts.net:8765/` (full MagicDNS), or `http://<host>:8765/` (short MagicDNS).
4. Phone must run the Tailscale app so it can reach the bridge.

Menu (system overflow): change the server URL, toggle fullscreen. A URL is validated before it is saved, and a saved URL that fails to load (typo, unreachable host) is dropped automatically with a fresh prompt - a wrong URL can never stick.

## Fullscreen

Parity with the desktop shell shim. `injectFullscreenShim` (run on `onPageFinished`) overrides `Element.prototype.requestFullscreen` / `Document.prototype.exitFullscreen` and the `fullscreenElement` / `fullscreenEnabled` / `fullscreenchange` logic exactly like `FULLSCREEN_SCRIPT` in `src/main.rs`; the side effect goes through a `fotonHost` `addJavascriptInterface` bridge that toggles immersive mode (`hide` / `show` status + navigation bars). WebView's own fullscreen path (`onShowCustomView`, which Chromium serves through a separate `FullScreenView` layer) is NOT used - on WebGL/canvas pages that layer composites blank, so the page requests never reach Chromium's fullscreen machinery.

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
  settings.gradle.kts, build.gradle.kts, gradle.properties
  gradle/wrapper/              (jar + properties, sha256-pinned 9.6.0)
  gradlew, gradlew.bat
  dist/app-debug.apk           (pulled artifact, gitignored)
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml     (INTERNET only, allowBackup=false, back = goBack)
  app/src/main/java/com/foton/frontend/MainActivity.kt
  app/src/main/res/values/{strings,themes}.xml
  app/src/main/res/values/colors.xml                 (launcher icon palette)
  app/src/main/res/drawable/ic_launcher_foreground.xml
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml (adaptive favicon icon)
  app/src/main/res/xml/network_security_config.xml
```

See foton-frontend-shell-android-plan.md for the full plan, toolchain evidence and the Android research; foton-frontend-shell-android-manual.md is the operator manual.