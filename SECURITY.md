# Security policy

## What this repo is

A thin webview shell: it points a native window (or Android WebView) at a URL
the operator configured, and forwards exactly one page-to-host action
(fullscreen on/off). There is no bundled frontend, no server-side component and
no updater in this repository.

## Reporting a vulnerability

Open a GitHub security advisory on this repository (tab: Security > Report a
vulnerability). Please include: which platform (Windows / Linux / Android), what
the shell was pointed at, and what a caller would have to control to trigger the
issue. Expect a first response within one week; fixes reach `main` as normal
commits and release notes in CHANGELOG.md.

Do not commit or attach the dev keystore or any operator URL in a report.

## Ownership

Who owns what (mirrors .github/CODEOWNERS):

```
/.github/workflows/*    @chezhenkie
/android/...            @chezhenkie
/src/main.rs            @chezhenkie
/tools/*                @chezhenkie
```

## The one privilege boundary, by platform

A reachable page gets exactly one native call. What is NOT reachable:

| Platform | Channel | Gate | Action |
| --- | --- | --- | --- |
| Windows / Linux (wry) | `with_ipc_handler` via `window.ipc.postMessage` | request URL scheme + authority must equal the configured origin (`origin_of` in `src/main.rs`) | `Window::set_fullscreen` |
| Android | `fotonHost.fullscreen(bool)` (`@JavascriptInterface`) | injected only for the origin saved in prefs; removed on any other main-frame origin (`onPageStarted` in `MainActivity.kt`) | hide/show of the system bars |

Closed-window rules for both: no file or content access (Android), `setWebContentsDebuggingEnabled(false)` (Android, all build types), no
`http://` local-file capabilities, IPC messages from non-http(s) origins are
rejected or never delivered.

Known limitations, same on both platforms:

- while the bridge is attached, any frame of that page can call it, including
  iframes of other origins (webkit2gtk reports the main frame's URL for every
  message, and Android injects the interface into every frame). What the
  attacker gets is a fullscreen toggle.
- the configured origin is trusted because the operator chose it. An operator
  who aims the shell at a hostile server has delegated to that server. This is
  by design; a shell pointing only at pre-hardcoded hosts would be a different
  tool.

## Cleartext, transport and TLS

Desktop does not restrict scheme: pointing at `http://` is the operator's
choice, and pages served that way are cleartext for whatever network sits
between. `https://` origins are enforced by the webview with no downgrade.

Android allows cleartext globally (its `network_security_config.xml`). Rationale
and residual risk: `android/README.md`, section "Cleartext".

## Supply chain

- GitHub Actions: third-party actions pinned by 40-character commit SHA with a
  version comment; `permissions: contents: read` or narrower.
- Desktop Rust: build with `cargo build --release --locked`; `Cargo.lock` is
  committed and Dependabot watches it. CI fails on a stale lock file instead of
  resolving different versions.
- Android Gradle: distribution sha256 pinned in `gradle-wrapper.properties`,
  no `dependencies {}` block at all (only the kotlin-stdlib AGP contributes).
- APK signer checked in CI against a pinned SHA-256, matched additionally
  against the keystore secret via `keytool` (android.yml).

## Secrets

`android/keystore/` (the dev signing key and its password file, gitignored) and
`archive/` (an AES-256 zip of them, gitignored). If you clone this repo, you
have neither: request them from the maintainer, or generate your own dev key as
described in ANDROID-SIGNING-AND-UPDATES.md.
