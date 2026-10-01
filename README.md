# Voice Recorder & Secure Vault

[![Android API](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
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

### Release Build (V1 & V2 Signed)
```bash
./gradlew assembleRelease
```
The release APK will be generated at `app/build/outputs/apk/release/app-release.apk`.

To verify APK signature schemes:
```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```
Expected output:
```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
```

---

## Publishing to GitHub

1. **Initialize Git & Commit**:
   ```bash
   git init
   git add .
   git commit -m "feat: complete voice recorder decoy vault with targetSdk 36, adaptive icon, and recovery"
   ```

2. **Add Remote & Push**:
   ```bash
   git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPOSITORY>.git
   git branch -M main
   git push -u origin main
   ```

3. **Enable GitHub Pages for Privacy Policy**:
   - In your GitHub repository, navigate to **Settings** > **Pages**.
   - Under **Source**, select `Deploy from a branch`.
   - Branch: `main`, Folder: `/docs`.
   - Click **Save**. Your privacy policy will be live at `https://<YOUR_USERNAME>.github.io/<YOUR_REPOSITORY>/`.

---

## Security Notice

The release keystore `release.keystore` and `keystore.properties` contain private cryptographic signing material and are strictly excluded via `.gitignore`. Always back up your keystore securely in an offline environment before releasing app updates.

---

## License

Distributed under the MIT License. See `LICENSE` for details.
