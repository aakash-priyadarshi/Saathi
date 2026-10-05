# Android setup, signing and distribution

Open `apps/android` in Android Studio, not the research probe. The checked-in Gradle 9.4.1 wrapper uses AGP 9.2.1 and an explicit Kotlin/Compose 2.4.0 compiler, because Nearby 19.5.1 publishes Kotlin 2.4 metadata. SDK platform 37.0 and build tools 36.0.0 are required. Android Studio's bundled JDK works locally; CI uses JDK 25. Radio/Keystore work uses SDK/ADB directly; an Android Studio MCP connection is unnecessary.

For a local development backend, migrate/seed only fictional development data and use `scripts/service-config.mjs init development <ignored-private-directory>`. It generates separate configuration-root/receipt identities and refuses production key generation. Restrict directory access to the operator (owner-only ACLs on Windows); mode 0600 alone does not guarantee Windows ACL protection. Sign with `node scripts/service-config.mjs sign development <directory> http://127.0.0.1:4000 1`. Provide the generated API secret bundle through process environment or managed secrets, never print it. Re-sign with a higher version on metadata changes; keep the root private file out of the API/container.

```powershell
.\scripts\android-build.ps1 -BuildType debug -TrustDirectory .data/android-development
.\scripts\android-install.ps1 -Model SM-S921B -BuildType debug -ReverseDevelopmentApi
# Equivalent direct ADB operations after selecting an authorized serial:
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <authorized-device> install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <authorized-device> reverse tcp:4000 tcp:4000
```

The resulting `org.saathi.android.dev` development build uses loopback API through USB reverse. It is not a hosted QA deployment. A provisioned staging build uses `-BuildType staging`, a staging-signed service configuration with HTTPS endpoints and the separate package `org.saathi.android.qa`. Cleartext is permitted only in the debug manifest's loopback configuration. Inspect the visible environment before testing; never point QA at production databases, storage, email or keys.

Production requires external `SAATHI_RELEASE_KEYSTORE`, `SAATHI_RELEASE_STORE_PASSWORD`, `SAATHI_RELEASE_KEY_ALIAS`, `SAATHI_RELEASE_KEY_PASSWORD`, `SAATHI_CONFIG_ROOT_PUBLIC_JWK` and `SAATHI_CONFIG_BOOTSTRAP`. Create the production keystore under a controlled operator location using Android's documented [signing procedure](https://developer.android.com/studio/publish/app-signing), maintain encrypted offline backups and separate upload/app-signing ownership where Play App Signing is chosen. This task creates no production keystore. Passwords belong in a secure prompt/CI secret channel, not shell history or Git. Increment versionCode for every installed update; record versionName, source commit, signed configuration version and checksum.

```powershell
.\scripts\android-build.ps1 -BuildType release
# With the same externally provisioned environment, from apps/android:
.\gradlew.bat :app:bundleRelease
# SDK build-tools apksigner:
apksigner verify --verbose --print-certs app-release.apk
Get-FileHash -Algorithm SHA256 app-release.apk
```

Debug uses the local developer certificate. Staging supports an operator-owned persistent **QA** keystore through `SAATHI_QA_KEYSTORE`, `SAATHI_QA_STORE_PASSWORD`, `SAATHI_QA_KEY_ALIAS` and `SAATHI_QA_KEY_PASSWORD`; supplying only part of that configuration fails the build. The QA keystore must remain distinct from production signing. A local staging build without these variables uses the debug certificate for controlled testing only. Staging assembly refuses a missing trust anchor or non-HTTPS bootstrap.

Create and back up the QA keystore outside Git using the same documented Android signing procedure, with a separate alias and passwords. Keep the certificate stable across QA updates: a different certificate can require uninstalling the app and losing its private work. Preserve pending work before any uninstall or signing-key change. No production or QA keystore is committed.

The Android product workflow builds/lints/tests the native app and runs secure-storage instrumentation on an API 36 emulator. On `main` pushes/manual runs, it creates a seven-day QA artifact only when public repository variables `SAATHI_STAGING_CONFIG_ROOT_PUBLIC_JWK` / `SAATHI_STAGING_CONFIG_BOOTSTRAP` and all four repository secrets `SAATHI_QA_KEYSTORE_BASE64` / `SAATHI_QA_STORE_PASSWORD` / `SAATHI_QA_KEY_ALIAS` / `SAATHI_QA_KEY_PASSWORD` are provisioned. The first secret is the operator's QA keystore encoded as base64; the two variables are public trust data. A missing signer skips distribution while still running Android validation. CI decodes the signer into a permission-restricted temporary file and removes it afterward; pull requests do not receive signing material. API/browser interoperability fixture tests are opt-in and skipped in the standalone emulator job. The workflow follows the [emulator runner's acceleration setup](https://github.com/ReactiveCircus/android-emulator-runner). Production release signing remains outside this public QA workflow.

The public `/download` page displays a download action only after `ANDROID_QA_APK_URL` is set to an approved HTTPS artifact. Publish package/environment, signing-certificate fingerprint and APK SHA-256 alongside any release. Production provider, security and physical connectivity gates are documented in [deployment](deployment-resilience.md) and [testing](android-testing.md).
