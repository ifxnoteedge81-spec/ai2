# Build

## In CI (recommended)
Push to `main` or a `v*` tag. The workflow `.github/workflows/android-build.yml` runs lint + unit tests, builds the APK,
verifies it (zip integrity, manifest present), writes `PersonalAIComputer.apk.sha256` and `version.json`, uploads artifacts,
creates a GitHub Release for tags, and deploys the Netlify download site.

## Release signing
Create a keystore once (keep it safe; losing it means you cannot update installed apps):

```
keytool -genkeypair -v -keystore release.keystore -alias pac -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore      # paste into the ANDROID_KEYSTORE_BASE64 secret
```
Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Never commit the keystore (`.gitignore` blocks it). If any secret is missing, CI builds a **debug-signed** APK, prints a warning,
marks it `signed:false` in `version.json`, and the website and GitHub Release say so.

## Locally
JDK 17 + Android SDK 35: `./gradlew testDebugUnitTest assembleDebug` → `app/build/outputs/apk/debug/`.
Versions come from `-PversionName=… -PversionCode=…` (CI sets them).

## Known first-build risks
The Android/Compose code has not been compiled yet (see README). Expect to fix a few compile errors on the first CI run.
Dependency versions are pinned in `gradle/libs.versions.toml` (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01). R8 is off in v1.
