# Android signing and updates

How a new `app-debug.apk` reaches the phone as an *update* instead of a reinstall, where the signing key lives, and what to do when it goes wrong. Companion to `android/README.md` (what the app does) and `ARCHITECTURE.md` (how the three targets fit together).

## The short version

CI signs every build with one pinned development key. Because the certificate is identical on every run, Android accepts a new APK as an update of the installed app: app data survives, so the stored server URL survives, and no uninstall is needed.

| Thing | Value |
| --- | --- |
| Key type | RSA 2048, self-signed, PKCS12, alias `foton-dev`, SHA256withRSA |
| Certificate DN | `CN=Foton Dev, OU=Shell, O=Foton, C=NL` |
| Certificate SHA-256 | `12:C1:8E:FA:44:4E:4F:A0:53:4A:63:11:EF:52:8C:43:5D:05:06:38:1C:DB:42:81:A6:C0:44:17:B3:84:97:82` |
| Valid | 2026-09-26 until 2054-02-11 (10000 days) |
| Package | `com.foton.frontend`, minSdk 26, versionCode 4, versionName 0.1.1 |
| Secrets | `FOTON_KEYSTORE_B64`, `FOTON_KEYSTORE_PASSWORD` |
| Local key backup | `android/keystore/foton-dev.keystore` + `keystore.properties` (gitignored) |
| Encrypted local copy | `archive/secrets foton frontend shell.zip`, AES-256, sha256 alongside (gitignored) |

This key signs nothing but this debug APK. It is not a release key, it is not trusted by any store, and it must never be reused for anything real.

## Why every install used to need an uninstall

Android only accepts an update when the new APK carries the same signing certificate as the installed one. A mismatch is rejected as `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ("Existing package signatures do not match newer version"). Uninstalling clears the signature check, which is why a fresh install always worked, and it also wipes the app data, which is why the server URL prompt came back every time.

Nothing in the repo pinned a key. `rg "signingConfig|storeFile|keystore" android/ .github/` returned nothing, so AGP fell back to its default debug signing config, and that config *generates* `$HOME/.android/debug.keystore` on whichever machine runs the build. Every GitHub Actions runner is a throwaway VM, so every run minted a new key.

Measured, by parsing the APK Signing Block of three consecutive artifacts: each build carried a different certificate.

| Artifact | Signer certificate SHA-256 |
| --- | --- |
| `app-debug.apk.2026-09-19.bak` (0.1.0) | `633d1f696451418caad6ddd114ddd5ae19ae0d3a...` |
| `app-debug.apk.0.1.1.bak` (fullscreen + menu) | `827f60a7b643ce0bc268105a8fa7fc148bee5df6...` |
| `app-debug.apk.refresh.bak` (Refresh item) | `99:8D:9D:94:71:1A:DA:91:F9:C0:43:46:A8:F2:7D:97:22:1D:C1:08:36:AC:4F:5E:C5:5A:76:15:BE:03:39:2A` |
| `app-debug.apk` (current, pinned key) | `12:C1:8E:FA:44:4E:4F:A0:53:4A:63:11:EF:52:8C:43:5D:05:06:38:1C:DB:42:81:A6:C0:44:17:B3:84:97:82` |

Three different keys for three builds of the same app, hence three uninstalls. The `keystore.properties` password is deliberately not in this table or anywhere in git.

## Where the key lives

Three places, and you need the two local ones:

1. GitHub Actions secrets on `chezhenkie/foton-frontend-shell`: Settings > Secrets and variables > Actions. `FOTON_KEYSTORE_B64` is the keystore file as a single base64 line, `FOTON_KEYSTORE_PASSWORD` is the store and key password. Secrets are write-only: the UI never shows the value again, so this copy cannot be recovered from GitHub.
2. Local, gitignored: `android/keystore/foton-dev.keystore` (2670 bytes) and `android/keystore/keystore.properties` (`storePassword=...`, `alias=foton-dev`). This is the only copy that can restore the secrets. A fresh clone does not have it, so copy it somewhere outside the repo if this machine is not permanent.
3. Since 2026-09-26 a second local copy, AES-256 encrypted, in `archive/secrets foton frontend shell.zip` (gitignored, see `archive/RESTORE.txt`). This guards against git accidents and stray `clean` runs, **not** against losing the machine: it sits on the same disk as the original. A genuinely off-machine copy (cloud or USB) is still outstanding.

The keystore and its password are excluded from git by the `android/keystore/`, `archive/` and `password for zip secrets foton frontend shell - leesmij.txt` lines in `.gitignore`. Verify with `git status --short` (nothing under `android/keystore/`, `archive/` or that password file should ever appear) and `git log --all -- android/keystore` (should be empty).

## How a build gets signed

`FOTON_KEYSTORE_B64` and `FOTON_KEYSTORE_PASSWORD` are passed as step env vars in `.github/workflows/android.yml` to the `Build debug APK` step only. They are not job-level, not workflow-level, and not visible to the `build` (desktop) workflow or to `cache-cleanup.yml`.

In `android/app/build.gradle.kts` the top of the script:

- reads both variables and fails configuration if only one of them is set, so a half-configured signing setup can never silently fall back;
- base64-decodes the keystore into `android/app/build/` (a gitignored, `clean`-able scratch location, recreated on every configuration);
- registers a signing config named `pinned` with alias `foton-dev`;
- points the `debug` build type at `pinned` when it exists, otherwise at AGP's own `debug` config.

That last line is the fallback: a local `./gradlew :app:assembleDebug` with no secrets still works. Such an APK is signed with a random per-machine key and will *not* update a CI-installed app (see below).

## The fingerprint gate

The `Verify signer, applicationId and version` step runs after the build and
before the artifact upload. It checks four things, each in its own `if`:

1. `apksigner verify --print-certs` - the APK's signature is valid at all. The
   step sets `pipefail` first, so a failed verify cannot be hidden by the `tee`.
2. The keystore secret is decoded and read back with `keytool -list -v`; its
   `SHA256` must equal the certificate the APK carries. This is the check that
   catches the fallback: if the secrets are missing, gradle signs with the
   per-machine debug key and this comparison is what turns red (with an explicit
   "secrets missing" error before that).
3. The fingerprint must equal the pinned one below. This is the check that
   never changes: a key rotated for correctness (rotation runbook below) must
   be re-pinned here, and anything else is a loud failure.
4. `aapt2 dump badging` - the APK's `applicationId`, `versionCode` and
   `versionName` must equal what `app/build.gradle.kts` says, so a stale APK
   cannot be uploaded as if it were the current tree.

`apksigner --print-certs` prints the digest lowercase and unseparated, keytool
prints it colon-separated in CAPS, hence the (`s/[: ]//g`, `tr 'A-F' 'a-f'`)
normalisation on both sides. The expected value lives in the workflow file,
which is public: a fingerprint is not a secret.

## Installing an update on the phone

1. Download the artifact: `gh run download <run-id> -n app-debug-apk -D android\dist`, or Actions > the run > Artifacts.
2. Sanity-check it before installing, see below.
3. Install. Either
   - `adb install -r android\dist\app-debug.apk` (add `-d` only if you deliberately go backwards), or
   - copy the APK to the phone and open it, allowing unknown sources once.

What is preserved: the app's data directory, so `SharedPreferences` `foton_prefs/server_url` and every other preference stay put and the app does not re-prompt for the URL. What is replaced: the code and resources of the app itself.

Two rules Android enforces on top of the signature match:

- `versionCode` must not go backwards; a lower one is rejected as a downgrade (`INSTALL_FAILED_VERSION_DOWNGRADE`). Every build bumps it, so this never bites in practice.
- A different signer is rejected outright, whatever the versionCode is. That is the failure this document exists to prevent.

## Verifying an APK before you install it

In CI, the verify step already did it and the job would have been red. Locally, with no Android SDK installed, `apksigner` is not available, so either read it off the CI log of the run that produced the artifact:

```bash
gh run view <run-id> --log | Select-String "SHA-256 digest"
```

or check the keystore directly and compare with the table at the top of this file:

```bash
keytool -list -v -keystore android\keystore\foton-dev.keystore -storepass <password> -alias foton-dev
```

The password is in `android/keystore/keystore.properties`. `keytool` comes with the JDK; in WSL it is `/usr/bin/keytool`.

## Runbook: replacing or rotating the key

Do this only when the key is lost or must change. Either way it costs exactly one uninstall on the phone, once.

1. Generate a new key (WSL, or any JDK):

```bash
keytool -genkeypair -keystore android/keystore/foton-dev.keystore \
  -storepass <new-password> -keypass <new-password> -alias foton-dev \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname 'CN=Foton Dev, OU=Shell, O=Foton, C=NL' -storetype PKCS12
```

2. Read the new fingerprint: `keytool -list -v ... | grep SHA256:`.
3. Update the `want=` fingerprint in the `Verify signer, applicationId and version` step of `.github/workflows/android.yml` (and the certificate line in `android/README.md`). The step cross-checks the APK certificate against the keystore secret with keytool, and against this pinned fingerprint, so both must move together.
4. Re-upload both secrets. `gh secret set` reads the value from stdin, which keeps a 3.5 KB base64 line off the command line:

```powershell
$b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes("android\keystore\foton-dev.keystore"))
[System.IO.File]::WriteAllText("android\keystore\keystore.b64", $b64)
Start-Process -FilePath "C:\Program Files\GitHub CLI\gh.exe" `
  -ArgumentList 'secret','set','FOTON_KEYSTORE_B64','-R','chezhenkie/foton-frontend-shell' `
  -RedirectStandardInput "android\keystore\keystore.b64" -NoNewWindow -Wait
```

Do the same for `FOTON_KEYSTORE_PASSWORD`, then delete the temporary `keystore.b64`. Never commit it, and do not use `Set-Content -NoNewline` for that file; use `[System.IO.File]::WriteAllText` as above.
5. Push the fingerprint change. The next run either passes the verify step (new key in place) or fails loudly, which is the point.
6. On the phone: uninstall once, install the new APK, done.

The old secret values cannot be read back from GitHub, so step 1 is the only moment the new key exists outside your machine. If you skip step 4 the next build fails at configuration time with the "must be set together" error rather than shipping an unsigned APK.

## Runbook: the local fallback produced an APK

If you built with `./gradlew :app:assembleDebug` and no secrets, the APK carries a random key. It cannot update a CI-installed app; installing it means one uninstall. CI is the supported path, which is also why `android/README.md` says local disk stays clean: the APK is built on GitHub Actions, not on a developer machine.

## Related files

| Path | Role |
| --- | --- |
| `.github/workflows/android.yml` | passes the secrets, runs the signer/applicationId/version verify step, uploads the artifact |
| `android/app/build.gradle.kts` | decodes the secret, `signingConfigs.pinned`, debug build type |
| `.gitignore` | `android/keystore/` keeps the key and password out of git |
| `android/README.md` | the app itself: menu, fullscreen, install steps, known gaps |
| `CHANGELOG.md` | when the key was pinned, under Android 0.1.1 |
