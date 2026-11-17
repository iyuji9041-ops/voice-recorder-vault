# Voice Recorder & Secure Vault

[![Android API](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)
[![Zero Cloud](https://img.shields.io/badge/Cloud%20Data%20Collection-0%25%20(100%25%20Offline)-success.svg)](PRIVACY_POLICY.md)

A next-generation privacy tool disguised as a fully functional, high-fidelity **Voice Recorder**. On the surface, it provides professional-grade audio recording, playback, sharing, and waveform visualization. Beneath the surface lies an AES-256 encrypted hidden vault for photos, videos, and private notes, secured with a multi-factor authentication gateway and anti-coercion decoy mode.

---

## Architecture Overview

```
                      +-----------------------------+
                      |   Voice Recorder (Decoy)    |
                      |  - Real audio recording     |
                      |  - Waveform visualizer      |
                      |  - In-app playback & share  |
                      +--------------+--------------+
                                     |
                       (5-sec covert hold on Record)
                                     v
                      +-----------------------------+
                      |   Layer 1: Door Passcode    |
                      |  (Numeric PIN / Fingerprint)|
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |   Layer 2: Slide-to-Unlock  |
                      |  (Bi-directional gesture)   |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |  Layer 3: Master Vault Key  |
                      |   (PIN / Password / Pattern)|
                      +--------------+--------------+
                                     |
                   +-----------------+-----------------+
                   |                                   |
            [Real Master Key]                   [Decoy Fake Key]
                   v                                   v
        +---------------------+             +---------------------+
        |  Real Secure Vault  |             |  Decoy Empty Vault  |
        | - Encrypted Photos  |             | - Zero files shown  |
        | - Encrypted Videos  |             | - Normal UI illusion|
        | - Intruder Selfies  |             +---------------------+
        | - Vault Settings    |
        +---------------------+
```

---

## Key Features

### 1. Authentic Voice Recorder (Decoy Layer)
- **High-Quality Audio Capture**: Records AAC/M4A standard audio via Android `MediaRecorder`.
- **Dynamic Waveform Visualizer**: Real-time sound amplitude visualizer during active recording.
- **Audio Manager**: Play recordings inline, scrub via seekbar, rename, share via Android Sharesheet, or multi-select to delete.
- **Modern Glassmorphic UI**: Material 3 styling with adaptive edge-to-edge layout, responsive across all screen densities.
- **Android 16 Ready**: Strict WindowInsets handling with zero navigation or status bar overlap.

### 2. Covert Gateway & Triple-Layer Authentication
- **Covert Trigger**: Long-press the Record button for 5 seconds (configurable from 5s up to 15s in Settings).
- **Layer 1 (Door Passcode)**: Independent gate passcode before accessing vault doors.
- **Layer 2 (Slide-to-Unlock)**: Tactile slider preventing accidental entry or brute-force automation.
- **Layer 3 (Vault Master Key)**: Choose between 4-6 digit numeric PIN, alphanumeric password, or 3x3 pattern lock.

### 3. Anti-Coercion Decoy Profile
- Define a secondary **Fake Passcode**.
- If coerced into opening the vault, entering the decoy passcode presents a completely empty vault interface with functional menus, ensuring zero suspicion without exposing real private data.

### 4. Media Viewer & On-Device Editing
- **Interactive Photo Viewer**: Multi-touch pinch-to-zoom and pan.
- **In-App Image Editor**: Adjust brightness, contrast, and 90-degree lossless rotation right inside the encrypted environment.
- **Private Video Player**: Seamless video streaming directly from encrypted local storage without leaving temporary unencrypted artifacts on disk.

### 5. Intruder Selfie Protection
- Automatically captures a silent front-facing camera selfie whenever an incorrect password or pattern is entered.
- Intruder logs include exact timestamps and are stored in an isolated, private intruder gallery.

### 6. Emergency Recovery (Pre-Unlock & In-Vault)
- **Zero-Lockout Guarantee**: If passwords are forgotten, recovery is accessible without needing vault access:
  - Covert **5-tap** or **3-second hold** on the top header title (`Voice Recorder`).
  - Dedicated **"Forgot Key"** buttons on all lock screens.
  - Automatic recovery prompt triggered after 3 consecutive failed unlock attempts.
- Protected by a user-configured Security Recovery Question & Answer with salt hashing.

### 7. Zero Cloud & Total Privacy
- **No Internet Permission**: The app manifest strictly omits `android.permission.INTERNET`. Zero data can leave your device.
- **Play Protect Compliance**: Pre-configured with dual APK signature schemes (v1 + v2/v3) using a dedicated release keystore to prevent Play Protect warnings.
- **Anti-Screenshot Security**: `FLAG_SECURE` enabled on sensitive activities to block Android system screenshots and task switcher previews.

---

## Project Structure

```
gentle-ramanujan/
├── app/
│   ├── build.gradle                     # App configuration (SDK 36, Signing, Dependencies)
│   ├── proguard-rules.pro               # ProGuard / R8 optimization rules
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml      # Manifest (Activities, Permissions, App Icon)
│           ├── java/com/my/seekapplication/
│           │   ├── VoiceRecorderActivity.kt    # Main Decoy Activity & Audio Engine
│           │   ├── DashboardActivity.kt        # Gateway (Slide-to-unlock & Key Verification)
│           │   ├── VaultGalleryActivity.kt     # Vault Gallery (Photos, Videos, Files)
│           │   ├── MediaViewerActivity.kt      # Encrypted Photo/Video Viewer & Editor
│           │   ├── IntruderSelfieActivity.kt   # Intruder Selfie Gallery & Management
│           │   ├── SettingsActivity.kt         # Security, Trigger Time & Vault Settings
│           │   ├── PatternLockView.kt          # Custom Canvas 3x3 Pattern Lock View
│           │   ├── WaveformView.kt             # Real-time Voice Waveform Visualizer
│           │   ├── IntruderCamera.kt           # Background Camera Capture Engine
│           │   ├── VaultSecurityManager.kt     # Encryption, Key Derivation & Migration
│           │   └── SecureStorageHelper.kt      # Safe SharedPreferences & Storage I/O
│           └── res/
│               ├── drawable/            # Material vector icons & glassmorphic backgrounds
│               ├── layout/              # XML layouts (Edge-to-edge Material 3)
│               ├── mipmap-*/            # App icons (Voice Recorder adaptive mic)
│               └── values/              # Strings, colors, styles, themes
├── docs/
│   └── index.html                       # Public Privacy Policy web page (GitHub Pages ready)
├── PRIVACY_POLICY.md                    # Privacy Policy in Markdown format
├── release.keystore                     # 2048-bit RSA Release Keystore (gitignored)
├── keystore.properties                  # Keystore credentials reference (gitignored)
├── build.gradle                         # Root Gradle build script
├── settings.gradle                      # Project settings & module mapping
└── .gitignore                           # Strict ignore rules for keys, builds & local caches
```

---

## Build & Run

### Prerequisites
- JDK 17
- Android SDK 36 (Android 16 Platform & Build-Tools 35.0.0+)
- Gradle 8.x+

### Debug Build
```bash
./gradlew assembleDebug
```
The debug APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Generating a Release Keystore

To create a secure release keystore for production signing:

```bash
keytool -genkey -v -keystore release.keystore -alias voicerecorder -keyalg RSA -keysize 2048 -validity 10000
```

Next, create a `keystore.properties` file in the root project directory (this file is excluded from Git tracking via `.gitignore`):

```properties
storeFile=release.keystore
storePassword=YourKeystorePasswordHere
keyAlias=voicerecorder
keyPassword=YourKeyPasswordHere
```

Alternatively, set the corresponding environment variables:
- `KEYSTORE_FILE`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

> [!NOTE]
> If `keystore.properties` or environment variables are missing, the release build will gracefully fall back to debug signing with a warning, ensuring local builds and CI jobs run without breaking.

### Release Build (Minified & ProGuard Optimized)
```bash
./gradlew assembleRelease
```
The release APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

To run code quality and lint checks:
```bash
./gradlew lint
```

---

## Dependency & Tooling Upgrade Reference

| Component / Library | Previous Version | Upgraded Stable Version |
| :--- | :--- | :--- |
| **Gradle** | 7.0.2 / 8.11 wrapper | **9.9.1** |
| **Android Gradle Plugin (AGP)** | 8.11.0 | **9.5.1** |
| **Kotlin** | 1.9.22 | **2.4.21** |
| **Java JDK** | 17 | **21 (via jvmToolchain)** |
| **Target SDK / Compile SDK** | 36 | **36 (Android 16)** |
| **Build Configuration** | Groovy DSL (`build.gradle`) | **Kotlin DSL (`build.gradle.kts` + `libs.versions.toml`)** |
| **AndroidX Core KTX** | 1.8.0 | **1.18.0** |
| **AndroidX AppCompat** | 1.6.1 | **1.8.0** |
| **Google Material Design** | 1.9.0 | **1.14.0** |
| **AndroidX ConstraintLayout** | 2.1.4 | **2.2.2** |
| **AndroidX RecyclerView** | 1.1.0 | **1.4.0** |
| **AndroidX ViewPager2** | 1.0.0 | **1.1.0** |
| **AndroidX Activity KTX** | *(implicit / none)* | **1.13.0** |
| **Kotlinx Coroutines Android** | 1.7.1 | **1.11.0** |

---

## Publishing to GitHub

1. **Commit changes**:
   ```bash
   git add .
   git commit -m "chore: modernize build to Kotlin DSL, latest stable dependencies, and secure signing"
   ```

2. **Push to Remote**:
   ```bash
   git push origin main
   ```

3. **Verify CI Status**:
   GitHub Actions automatically builds Debug and Release APKs and executes Android Lint on push.

---

## Security Notice

The release keystore `release.keystore` and `keystore.properties` contain private cryptographic signing material and are strictly excluded via `.gitignore`. Always back up your keystore securely in an offline environment before releasing app updates.

---

## License

Distributed under the MIT License. See `LICENSE` for details.
