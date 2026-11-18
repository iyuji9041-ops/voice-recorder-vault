# Voice Recorder & Secure Vault

[![Android API](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-blue.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.9.1-02303A.svg)](https://gradle.org)
[![AGP](https://img.shields.io/badge/AGP-9.5.1-34A853.svg)](https://developer.android.com/studio/releases/gradle-plugin)
[![License](https://img.shields.io/badge/License-MIT-orange.svg)](LICENSE)
[![Zero Cloud](https://img.shields.io/badge/Cloud%20Data%20Collection-0%25%20(100%25%20Offline)-success.svg)](PRIVACY_POLICY.md)
[![Security Policy](https://img.shields.io/badge/Security-Hardened%20AES--256--GCM-red.svg)](SECURITY.md)

A next-generation privacy tool disguised as a fully functional, high-fidelity **Voice Recorder**. On the surface, it provides professional-grade audio recording, playback, sharing, and waveform visualization. Beneath the surface lies a military-grade, chunked **AES-256-GCM encrypted vault** for photos, videos, and private notes, secured with dual-factor hardware key wrapping, in-memory zero-disk-leak streaming, an anti-coercion decoy profile, and password-protected offline backups (`.svb`).

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
                      |  (Numeric PIN / Gate code)  |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |   Layer 2: Slide-to-Unlock  |
                      |  (Smooth tactile slider)    |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |  Layer 3: Master Vault Key  |
                      |   (PIN / Password / Pattern)|
                      +--------------+--------------+
                                     |
                                     v
              [Constant-Time PBKDF2 600,000 Iterations]
              [Dual-Factor Android Keystore Unwrapping]
                                     |
                   +-----------------+-----------------+
                   |                                   |
            [Real Master Key]                   [Decoy Fake Key]
                   v                                   v
        +---------------------+             +---------------------+
        |  Real Secure Vault  |             |  Decoy Empty Vault  |
        | - files/vault/real/ |             | - files/vault/decoy/|
        | - Encrypted Photos  |             | - Separate repo/key |
        | - Encrypted Videos  |             | - Zero files shown  |
        | - Media3 ExoPlayer  |             | - Normal UI illusion|
        | - Encrypted Backups |             +---------------------+
        +---------------------+
```

---

## Core Security Features (Phase 2 Hardened Standard)

### 1. Chunked AES-256-GCM Streaming Cipher
- **64 KB Chunk Streaming:** Files are encrypted into authenticated 64 KB chunks, each bearing its own 128-bit authentication tag.
- **AAD Chunk Binding:** Each chunk binds its chunk index, total plaintext size, and chunk size into Associated Authenticated Data (AAD) to prevent chunk reordering, truncation, or splicing.
- **O(1) Random Access Seeking:** Enables instantaneous video/audio seeking without decrypting the entire file into memory or writing plaintext to disk.
- **Instant Tamper Detection:** Any flipped bit in the header, ciphertext, nonce, or tag throws a cryptographic verification error immediately.

### 2. Dual-Factor Master Key Wrapping
- **PBKDF2-HMAC-SHA256:** Derived with **600,000 iterations** using a 256-bit cryptographic salt, adhering to OWASP guidelines.
- **Hardware Android Keystore:** The PBKDF2-wrapped key is further enveloped by a hardware-backed AES-256-GCM key inside Android KeyStore (`StrongBox` / TEE).
- **Zero Plaintext Storage:** Neither user passwords nor raw master keys are ever stored on disk.

### 3. Private Sandbox Storage & Anti-Exposure
- **Private Internal Storage:** All vault files and indexes reside strictly in the app's internal sandbox (`/data/data/com.my.seekapplication/files/vault/`).
- **No Public Leaks:** Public `Documents/.secure_vault_system` and plaintext `.vault_config` files have been permanently eliminated.
- **Opaque UUID Naming:** Files are renamed to random UUIDs (`<uuid>.enc`), with original metadata kept in an encrypted index.
- **Atomic Writes:** File imports write to temporary `.tmp_*.enc` files and atomically rename them upon successful encryption, preventing corruption during low-battery or process termination.

### 4. Plausible Deniability & Decoy Vault Parity
- **Anti-Coercion Mode:** Setting a secondary Decoy Key opens an independent Decoy Vault (`files/vault/decoy/`) with an independent master key.
- **Full Functional Parity:** Decoy mode allows file addition, encryption, viewing, and deletion identically into the decoy repository without any "Fake Vault" toast or visual leak.
- **Constant-Time Verification:** Unlock evaluation performs identical symmetric PBKDF2 work regardless of which PIN is entered, eliminating timing side channels.

### 5. Persistent Brute-Force Lockout (LockoutManager)
- **Monotonic Hardware Clock:** Hardware monotonic clock (`SystemClock.elapsedRealtime`) combined with device boot count (`BOOT_COUNT`) enforces persistent exponential backoff delays (30s, 1m, 5m, 15m).
- **Anti-Reboot & Anti-Clock-Shift:** Changing the device clock or rebooting the phone does not reset the lockout duration.
- **Independent Protection Layers:** Door Passcode, Vault Key, and Recovery Token have isolated lockout counters.

### 6. Offline Recovery Architecture (Zero Insecure Bypass)
- **16-Character Master Recovery Code:** Generates an offline token (`XXXX-XXXX-XXXX-XXXX`) wrapping the Real Master Key into an offline slot.
- **Zero Question Bypass:** Eliminated insecure bypasses; recovery code is required to unwrap master key offline.
- **Destructive Vault Wipe:** Provides a safe factory reset option if the recovery code is permanently lost.

### 7. Asymmetric Hybrid Intruder Camera (Camera2)
- **Asymmetric Hybrid Crypto:** An RSA-2048 public key encrypts an ephemeral AES-256-GCM key for each photo capture. The camera service securely encrypts intruder photos while the vault is locked without needing the master key in RAM.
- **Silent & Uncoerced:** Zero screen flash, zero camera shutter sound, zero permission prompts during unlock attempts.
- **Cap & Audit Trail:** Enforces a 50-photo storage cap and maintains a persistent audit log (`intruder_audit.json`).

### 8. Auto-Lock & Recents Exemption
- **Automated RAM Wipe:** Zeroizes master keys and finishes vault activities on backgrounding or screen turn-off (`ACTION_SCREEN_OFF`).
- **Configurable Grace Period:** Immediate, 15s, 30s, 1m, or 5m grace period.
- **Recents Exemption:** `android:excludeFromRecents="true"` and `FLAG_SECURE` prevent OS preview screenshots.

### 5. In-Memory Zero-Disk-Leak Media Streaming
- **Media3 ExoPlayer:** Custom `EncryptedVaultDataSource` decrypts directly into memory buffers on demand, eliminating insecure temporary decrypted files in `cacheDir`.
- **In-Memory Video Thumbnails:** Generated directly via `MediaMetadataRetriever` using an in-memory `EncryptedMediaDataSource`.
- **Photo Downsampling:** Images stream through `BitmapFactory.decodeStream` with downsampling, preventing `OutOfMemoryError` on high-resolution cameras.
- **Cache Eradication:** `VaultSession.wipeAppCache()` runs upon app start and every time the vault locks.

### 6. Portable Encrypted Backups (`.svb`)
- Export and import standalone password-protected archives (`.svb`) encrypted via AES-256-GCM with PBKDF2 (600,000 iterations).
- Allows users to securely migrate vaults across devices or preserve files before clearing device storage.

> [!IMPORTANT]
> **Data Loss Prevention Notice:** Because vault files are strictly stored in the application's private internal sandbox, Android permanently erases this sandbox if you tap "Clear Data" in system settings or uninstall the app. Always export an **Encrypted Backup (.svb)** to maintain long-term offline backups.

---

## Project Structure

```
wise-curie/
├── app/
│   ├── build.gradle.kts                 # Modern Kotlin DSL build configuration
│   ├── proguard-rules.pro               # ProGuard / R8 optimization rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # Manifest (Activities, Zero INTERNET permission)
│       │   ├── java/com/my/seekapplication/
│       │   │   ├── VoiceRecorderActivity.kt    # Main Decoy Activity & Audio Engine
│       │   │   ├── DashboardActivity.kt        # Gateway (Slide-to-unlock & Key Verification)
│       │   │   ├── VaultGalleryActivity.kt     # Vault Gallery (Photos, Videos, Files)
│       │   │   ├── MediaViewerActivity.kt      # Media3 ExoPlayer & Image Viewer
│       │   │   ├── IntruderListActivity.kt     # Intruder Selfie Gallery & Logs
│       │   │   ├── SettingsActivity.kt         # Security, Backup Export/Import & Settings
│       │   │   ├── PatternLockView.kt          # Custom Canvas 3x3 Pattern Lock View
│       │   │   ├── IntruderCamera.kt           # Background Front Camera Selfie Engine
│       │   │   ├── VaultSecurityManager.kt     # Central Security & Auth Facade
│       │   │   ├── VaultImageLoader.kt         # In-memory streaming photo & thumbnail loader
│       │   │   ├── crypto/
│       │   │   │   ├── VaultCipherStream.kt    # Chunked AES-256-GCM streaming cipher
│       │   │   │   ├── VaultKeyManager.kt      # Dual-factor wrapping & PBKDF2 600k engine
│       │   │   │   └── SafeBase64.kt           # Dual Android/JVM Base64 wrapper
│       │   │   ├── data/
│       │   │   │   ├── VaultRepository.kt      # UUID file manager & encrypted index
│       │   │   │   ├── VaultSession.kt         # Volatile RAM session & cache eradication
│       │   │   │   ├── VaultBackupManager.kt   # Encrypted backup export/import (.svb)
│       │   │   │   └── VaultMigrationHelper.kt # Non-destructive legacy migration
│       │   │   └── player/
│       │   │       ├── EncryptedVaultDataSource.kt # Media3 ExoPlayer custom DataSource
│       │   │       └── EncryptedMediaDataSource.kt # MediaMetadataRetriever DataSource
│       │   └── res/                             # Layouts, vector drawables & values
│       └── test/                                # JUnit 4 Unit Tests
│           └── java/com/my/seekapplication/
│               ├── crypto/
│               │   ├── VaultCipherStreamTest.kt # Round trip, seeking, tampering tests
│               │   └── VaultKeyManagerTest.kt   # Wrapping, decoy isolation, wrong PIN tests
│               └── data/
│                   └── VaultMigrationTest.kt    # Legacy decryption & migration tests
├── gradle/
│   └── libs.versions.toml                       # Gradle Version Catalog
├── .github/workflows/
│   └── build.yml                                # GitHub Actions CI (test, build, lint)
├── SECURITY.md                                  # Comprehensive Threat Model & Crypto Spec
├── PRIVACY_POLICY.md                            # 100% Offline Zero-Data Policy
└── README.md                                    # This document
```

---

## Build & Test

### Prerequisites
- JDK 21
- Android SDK 36 (Android 16 Platform & Build-Tools 35.0.0+)
- Gradle 9.9.1 (bundled via wrapper)

### Running Unit Tests
```bash
./gradlew test --stacktrace
```

### Debug Build
```bash
./gradlew assembleDebug --stacktrace
```
The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Release Build (Minified & ProGuard Optimized)
```bash
./gradlew assembleRelease --stacktrace
```
The release APK is generated at `app/build/outputs/apk/release/app-release.apk`.

### Code Quality & Android Lint
```bash
./gradlew lint --stacktrace
```

---

## Dependency & Tooling Upgrade Reference

| Component / Library | Previous Version | Upgraded Stable Version | Rationale |
| :--- | :--- | :--- | :--- |
| **Gradle** | 7.0.2 / 8.11 wrapper | **9.9.1** | Native Java 21 support, configuration cache |
| **Android Gradle Plugin (AGP)** | 8.11.0 | **9.5.1** | Built-in Kotlin compilation, Android 16 support |
| **Kotlin** | 1.9.22 | **2.4.21** | Built-in K2 compiler |
| **Java JDK** | 17 | **21 (via jvmToolchain)** | Long Term Support standard |
| **Target SDK / Compile SDK** | 36 | **36 (Android 16)** | Full compliance with latest Android platform |
| **Build Configuration** | Groovy DSL (`build.gradle`) | **Kotlin DSL + Version Catalog** | Type-safe dependencies and plugins |
| **Media3 ExoPlayer** | *(none / VideoView)* | **1.11.1** | Seekable in-memory streaming encryption |
| **AndroidX Core KTX** | 1.8.0 | **1.18.0** | Stable runtime for compileSdk 36 |
| **AndroidX AppCompat** | 1.6.1 | **1.8.0** | Material 3 & Edge-to-edge support |
| **Google Material Design** | 1.9.0 | **1.14.0** | Modern components & theme attributes |
| **AndroidX Activity KTX** | *(none)* | **1.13.0** | Edge-to-edge & SAF ActivityResult contracts |
| **Kotlinx Coroutines** | 1.7.1 | **1.11.0** | Async vault I/O & backup operations |
| **JUnit** | *(none)* | **4.13.2** | Automated cryptographic unit tests |

---

## Security Policy

For security vulnerability disclosures, threat model details, and cryptographic specs, see [SECURITY.md](SECURITY.md).

---

## License

Distributed under the MIT License. See `LICENSE` for details.
