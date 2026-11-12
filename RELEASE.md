# DASMO Keyboard — End-to-End Release & Distribution Guide

This document outlines the professional end-to-end release process for **DASMO Keyboard**. It serves as an authoritative guide for human developers, maintainers, and AI assistants to build, sign, verify, and distribute updates across GitHub, F-Droid, Google Play, and direct APK channels.

---

## Table of Contents
1. [Release Philosophy & Privacy Architecture](#1-release-philosophy--privacy-architecture)
2. [Versioning Strategy](#2-versioning-strategy)
3. [Building Production & Debug APKs](#3-building-production--debug-apks)
4. [Keystore Generation & Code Signing](#4-keystore-generation--code-signing)
5. [GitHub Releases & CI/CD Workflows](#5-github-releases--cicd-workflows)
6. [Store Metadata & Fastlane Distribution](#6-store-metadata--fastlane-distribution)
7. [In-App Updates & Delivery Channels](#7-in-app-updates--delivery-channels)
8. [Pre-Flight Release Checklist](#8-pre-flight-release-checklist)

---

## 1. Release Philosophy & Privacy Architecture

DASMO Keyboard is an open-source, privacy-first input method (IME) for Android.

> [!IMPORTANT]
> **Privacy Invariant**: As a keyboard handles sensitive user interactions (passwords, private messages, personal notes), it must never compromise user trust. Network connectivity is strictly limited to user-initiated on-demand features (such as the optional Voice-to-Text & Real-time Translation). The core keyboard engine, custom layouts, dictionaries, and user history are strictly processed and stored locally on the device.

Key principles for releases:
* **Deterministic, Reproducible Builds**: Anyone building from source with the same commit, JDK, and NDK should produce functionally identical binaries.
* **Security & Sandboxing**: Native binaries (`.so` libraries) compiled via Android NDK are verified and packaged cleanly.
* **Zero Hidden Tracking**: No telemetry, analytics SDKs, or background network pollers are packaged into production builds.

---

## 2. Versioning Strategy

### 2.1 Configuration in `app/build.gradle.kts`
Versioning is governed in [app/build.gradle.kts](file:///c:/CODING/coading/DASMO%20CLIENTS/DASMO%20KEYBOARD/KEYBOARD%20OF%20DASMO/app/build.gradle.kts):

```kotlin
android {
    defaultConfig {
        applicationId = "helium314.keyboard"
        minSdk = 21
        targetSdk = 37
        versionCode = 4300
        versionName = "4.3-beta1"
        // ...
    }
}
```

### 2.2 Semantic Versioning (`versionName`)
We adhere to **Semantic Versioning (SemVer 2.0.0)** formatted as `MAJOR.MINOR.PATCH[-PRERELEASE]`:

* **`MAJOR`** (e.g., `4.0.0` &rarr; `5.0.0`):
  * Architectural overhauls or major framework updates.
  * Incompatible data storage or preference migrations.
  * Fundamental UX redesigns.
* **`MINOR`** (e.g., `4.2.0` &rarr; `4.3.0`):
  * Backward-compatible new features (e.g., Bengali Voice Translation toolbar integration, new language layouts, gesture enhancements).
  * Major performance improvements.
* **`PATCH`** (e.g., `4.3.0` &rarr; `4.3.1`):
  * Backward-compatible bug fixes, security patches, crash fixes.
  * Dictionary updates or translation text fixes.
* **Pre-release Suffixes**:
  * `-alpha`, `-beta1`, `-rc1` for public testing releases before promoting to general release.

### 2.3 Monotonic Version Code (`versionCode`)
Android's package manager and Google Play require each upgrade to have a strictly greater integer `versionCode`.

**Standard Formula**:
$$\text{versionCode} = \text{MAJOR} \times 1000 + \text{MINOR} \times 100 + \text{PATCH}$$

* Example: `4.0.0` &rarr; `4000`
* Example: `4.2.1` &rarr; `4201`
* Example: `4.3.0` &rarr; `4300`

> [!WARNING]
> Never decrement or reuse a `versionCode`. Once an APK with a higher `versionCode` is installed or uploaded to an app repository, devices cannot downgrade without uninstalling the app (which wipes user data).

---

## 3. Building Production & Debug APKs

### 3.1 Build Environment Prerequisites
Ensure your build environment has:
* **Java Development Kit (JDK)**: JDK 17 (OpenJDK or Eclipse Temurin).
* **Android SDK**: `compileSdk = 37`, `targetSdk = 37`, `minSdk = 21`.
* **Android NDK**: Version `28.0.13004108` (specified in `app/build.gradle.kts`).
* **C/C++ Build Tools**: CMake / Make for `src/main/jni/Android.mk`.

### 3.2 Build Types & Flavors
DASMO Keyboard defines four primary build variants in [app/build.gradle.kts](file:///c:/CODING/coading/DASMO%20CLIENTS/DASMO%20KEYBOARD/KEYBOARD%20OF%20DASMO/app/build.gradle.kts):

| Build Variant | Command | Description |
| :--- | :--- | :--- |
| **`release`** | `./gradlew assembleRelease` | Production build. Full R8 code shrinking and optimization (`isMinifyEnabled = true`), JNI debug symbols stripped. Supports user-provided custom native libraries if enabled. |
| **`nouserlib`** | `./gradlew assembleNouserlib` | High-security release variant. Hardened for strict environments: does not allow loading custom native libraries provided by the user. Recommended for security-sensitive users. |
| **`debug`** | `./gradlew assembleDebug` | Production-like debug APK (`applicationIdSuffix = ".debug"`). Minified with custom dictionary filtering (`main_ro.dict` omitted) to keep file size under 25 MB for GitHub Actions artifacts. |
| **`debugNoMinify`** | `./gradlew assembleDebugNoMinify` | Fast development build for Android Studio. Skips R8 minification for instantaneous compilation and step debugging. |

### 3.3 Output Locations & Artifact Naming
All APK files are placed by Gradle into `app/build/outputs/apk/<buildType>/`.

Gradle variant configuration dynamically names artifacts based on `versionName` and `buildType`:
```kotlin
output.outputFileName = "DASMO_Keyboard_${defaultConfig.versionName}-${variant.buildType}.apk"
```

**Artifact Paths**:
* **Release APK**:
  `app/build/outputs/apk/release/DASMO_Keyboard_4.3-release.apk`
* **Hardened No-User-Lib APK**:
  `app/build/outputs/apk/nouserlib/DASMO_Keyboard_4.3-nouserlib.apk`
* **Debug APK**:
  `app/build/outputs/apk/debug/DASMO_Keyboard_4.3-debug.apk`

---

## 4. Keystore Generation & Code Signing

Android requires all APKs to be cryptographically signed with a private key before they can be installed on physical devices.

### 4.1 Generating a Production Keystore
Generate a 4096-bit RSA key pair stored in a PKCS12 keystore using JDK `keytool`:

```bash
keytool -genkeypair -v \
  -keystore dasmo-release.jks \
  -alias dasmo-key-alias \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

> [!CAUTION]
> **Backup Keystore & Passwords Securely**: If you lose the release keystore or password, you will **never** be able to release updates under the same package name on existing installs or app stores. Keep encrypted offline backups.

### 4.2 Secure Gradle Signing Configuration

> [!WARNING]
> **DO NOT** commit keystore files (`*.jks`, `*.keystore`) or passwords to Git. Ensure `.gitignore` includes `*.jks`, `*.keystore`, and `keystore.properties`.

#### Approach A: Environment Variables (Recommended for CI/CD)
Configure your CI/CD runner (e.g., GitHub Secrets) with the following environment variables:
* `KEYSTORE_FILE`: Absolute path to the keystore file.
* `KEYSTORE_PASSWORD`: Keystore store password.
* `KEY_ALIAS`: Key alias name.
* `KEY_PASSWORD`: Key password.

#### Approach B: Local `keystore.properties` (Recommended for Local Dev)
Create `keystore.properties` in the project root:
```properties
storeFile=C:/path/to/dasmo-release.jks
storePassword=YourKeystorePasswordHere
keyAlias=dasmo-key-alias
keyPassword=YourKeyPasswordHere
```

#### Recommended `app/build.gradle.kts` Integration:
```kotlin
import java.io.FileInputStream
import java.util.Properties

android {
    // ...
    signingConfigs {
        create("release") {
            val keystorePropFile = rootProject.file("keystore.properties")
            if (keystorePropFile.exists()) {
                val props = Properties().apply { load(FileInputStream(keystorePropFile)) }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else if (System.getenv("KEYSTORE_FILE") != null) {
                storeFile = file(System.getenv("KEYSTORE_FILE")!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
        named("nouserlib") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### 4.3 Manual Post-Build Signing with `apksigner`
If building an unsigned release APK, align and sign using Android SDK build-tools:

```bash
# 1. 4-byte align the APK
zipalign -v -p 4 \
  app/build/outputs/apk/release/DASMO_Keyboard_4.3-release-unsigned.apk \
  DASMO_Keyboard_4.3-release-aligned.apk

# 2. Sign with apksigner (v2, v3, v4 signature schemes)
apksigner sign \
  --ks dasmo-release.jks \
  --ks-key-alias dasmo-key-alias \
  --out DASMO_Keyboard_4.3-release.apk \
  DASMO_Keyboard_4.3-release-aligned.apk

# 3. Verify signature
apksigner verify --verbose DASMO_Keyboard_4.3-release.apk
```

---

## 5. GitHub Releases & CI/CD Workflows

### 5.1 Existing Debug Build Workflow
DASMO Keyboard includes [.github/workflows/build-debug-apk.yml](file:///c:/CODING/coading/DASMO%20CLIENTS/DASMO%20KEYBOARD/KEYBOARD%20OF%20DASMO/.github/workflows/build-debug-apk.yml) for automated debug artifacts:
* Triggered manually via `workflow_dispatch`.
* Spins up `ubuntu-latest`, provisions JDK 17, and runs `./gradlew assembleDebug`.
* Archives and uploads the `DASMO-Keyboard-debug` artifact.

### 5.2 Release Tagging Workflow
When preparing an official public release:

1. **Update Version**:
   * Bump `versionCode` and `versionName` in [app/build.gradle.kts](file:///c:/CODING/coading/DASMO%20CLIENTS/DASMO%20KEYBOARD/KEYBOARD%20OF%20DASMO/app/build.gradle.kts).
   * Add a changelog entry in `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`.
2. **Commit and Tag**:
   ```bash
   git checkout main
   git pull origin main
   git commit -am "chore(release): prepare v4.3.0 release"
   git tag -a v4.3.0 -m "Release DASMO Keyboard v4.3.0"
   git push origin main
   git push origin v4.3.0
   ```

### 5.3 Automated Release CI Workflow (`.github/workflows/release-apk.yml`)
Add an automated release workflow that triggers when a tag `v*` is pushed:

```yaml
name: Build & Publish Release

on:
  push:
    tags:
      - 'v*'

jobs:
  build-release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: gradle/actions/setup-gradle@v3

      - name: Decode Release Keystore
        if: env.RELEASE_KEYSTORE_BASE64 != ''
        env:
          RELEASE_KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: |
          echo "$RELEASE_KEYSTORE_BASE64" | base64 --decode > /tmp/release.keystore

      - name: Assemble Release APKs
        env:
          KEYSTORE_FILE: /tmp/release.keystore
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: |
          chmod +x gradlew
          ./gradlew assembleRelease assembleNouserlib

      - name: Generate Checksums
        run: |
          cd app/build/outputs/apk/release/
          sha256sum DASMO_Keyboard_*-release.apk > SHA256SUMS.txt
          cd ../nouserlib/
          sha256sum DASMO_Keyboard_*-nouserlib.apk >> ../release/SHA256SUMS.txt

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: |
            app/build/outputs/apk/release/DASMO_Keyboard_*-release.apk
            app/build/outputs/apk/nouserlib/DASMO_Keyboard_*-nouserlib.apk
            app/build/outputs/apk/release/SHA256SUMS.txt
          draft: false
          prerelease: false
          generate_release_notes: true
```

### 5.4 Release Notes Template
When authoring the GitHub Release:
```markdown
## DASMO Keyboard vX.Y.Z

### 🚀 Highlights & New Features
* **Bengali Voice-to-Text & Real-time Translation**: Tap the toolbar icon to transcribe Bengali speech and translate directly into English or Hindi in real time.
* **Layout Enhancements**: Improved space bar behavior and multilingual dictionary suggestions.

### 🐛 Bug Fixes & Refinements
* Resolved split keyboard layout glitch on tablets.
* Updated gesture typing dictionary pruning.

### 📦 Verify Downloads
Verify the cryptographic integrity of your download:
```bash
sha256sum DASMO_Keyboard_X.Y-release.apk
```
```

---

## 6. Store Metadata & Fastlane Distribution

### 6.1 Fastlane Directory Structure
Store metadata is centralized under `fastlane/metadata/android/`:

```
fastlane/metadata/android/
├── en-US/
│   ├── title.txt                  # App name (max 30 characters: "DASMO Keyboard")
│   ├── short_description.txt      # Brief tagline (max 80 characters)
│   ├── full_description.txt       # Detailed store description (max 4000 characters)
│   ├── changelogs/
│   │   ├── 4200.txt               # Notes for versionCode 4200
│   │   └── 4300.txt               # Notes for versionCode 4300
│   └── images/                    # Screenshots, feature graphic, app icon
├── bn/                            # Bengali metadata & localized strings
├── hi/                            # Hindi metadata
├── de-DE/                         # German metadata
└── ... (30+ supported locales)
```

### 6.2 Managing Changelogs
* For every release, create a text file named `<versionCode>.txt` under `fastlane/metadata/android/<locale>/changelogs/`.
* **Character Limit**: Google Play enforces a strict 500-character limit per changelog file.
* **Formatting**: Keep bullet points concise and human-readable:
  ```text
  * Added on-demand Bengali Voice-to-Text with English and Hindi translation
  * Improved gesture typing responsiveness
  * Updated localized layout definitions
  * General performance and memory optimizations
  ```

---

## 7. In-App Updates & Delivery Channels

### 7.1 Privacy-Respecting Update Model
Unlike ordinary applications with aggressive background telemetry, **DASMO Keyboard deliberately avoids persistent background update polling**. Running background network daemons inside an input method risks user privacy and increases battery consumption.

### 7.2 Supported Distribution Channels
1. **Google Play Store**:
   * Updates are automatically handled by the Google Play Store client.
   * Staged rollouts (e.g., 10% &rarr; 25% &rarr; 50% &rarr; 100%) are recommended for major releases to catch edge-case bugs early.
2. **F-Droid & Third-Party Repositories**:
   * F-Droid tracks GitHub release tags and builds from source using reproducible recipes.
   * `fastlane/metadata/android/` provides the titles, summaries, and changelogs automatically consumed by F-Droid.
3. **GitHub Direct Releases & Obtainium**:
   * Users who install directly from GitHub can use update managers like [Obtainium](https://github.com/ImranR98/Obtainium) to automatically query the GitHub Releases API and install updates without background app services.
4. **In-App About Screen**:
   * Users can inspect their currently installed version at `Settings -> About`.
   * Tapping "View on GitHub" opens the official repository to check for the latest releases.

---

## 8. Pre-Flight Release Checklist

Before tagging or distributing an update, complete each step:

- [ ] **Code Hygiene**:
  - Run `./gradlew lint` and ensure 0 fatal errors.
  - Run `./gradlew testRunTestsUnitTest` to execute the automated test suite.
- [ ] **Version Synchronization**:
  - [ ] Increment `versionCode` in `app/build.gradle.kts` (e.g., `4300` &rarr; `4301`).
  - [ ] Update `versionName` in `app/build.gradle.kts` (e.g., `"4.3-beta1"` &rarr; `"4.3.0"`).
- [ ] **Fastlane Metadata**:
  - [ ] Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
  - [ ] Verify changelog does not exceed 500 characters.
  - [ ] Update other locale changelogs as needed.
- [ ] **Clean Build**:
  - [ ] `./gradlew clean`
  - [ ] `./gradlew assembleRelease assembleNouserlib`
- [ ] **Signing & Verification**:
  - [ ] Verify signature scheme: `apksigner verify --verbose app/build/outputs/apk/release/DASMO_Keyboard_*.apk`
  - [ ] Verify V2, V3, and V4 signatures are valid.
- [ ] **Smoke Testing on Device**:
  - [ ] Install APK: `adb install -r app/build/outputs/apk/release/DASMO_Keyboard_*.apk`
  - [ ] Test standard typing across multiple text fields.
  - [ ] Test language switching and emojis.
  - [ ] Test voice translation toolbar feature (if permissions granted).
  - [ ] Verify `Settings -> About` displays the expected `versionName`.
- [ ] **Git Tagging & Publishing**:
  - [ ] Create signed git tag: `git tag -a vX.Y.Z -m "Release vX.Y.Z"`
  - [ ] Push tag: `git push origin vX.Y.Z`
  - [ ] Publish GitHub Release with release notes and SHA-256 checksums.
