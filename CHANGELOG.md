# Changelog

All notable changes to the **Voice Recorder & Secure Vault** project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-11-18

### Initial Production Release

#### Decoy Voice Recorder Engine
- **Foreground Recording Service:** Full lifecycle background voice recording via `VoiceRecordingService` (`foregroundServiceType="microphone"`) with non-intrusive status notification.
- **Audio Controls:** Seamless `MediaRecorder.pause()` and `MediaRecorder.resume()` support (API 24+).
- **Dynamic Waveform Visualizer:** Custom `WaveformView` with real-time 75ms amplitude sampling.
- **Modern Public Storage:** Scoped `MediaStore.Audio` management with `IS_PENDING = 1` staging on Android 10+ and legacy file management on Android 9 and below.
- **Covert Access Interface:** Secret hold interaction with a neutral deadzone (500ms to holdDuration) to eliminate behavioral tells.
- **Protection Measures:** Automatic pause on telephone calls and audio focus loss; low disk storage protection (< 20MB).

#### Cryptography & Key Management
- **Chunked AES-256-GCM Streaming Cipher:** 64 KB blocks with 128-bit authentication tags and Associated Authenticated Data (AAD) binding chunk index and file length.
- **Dual-Factor Hardware Key Wrapping:** PBKDF2-HMAC-SHA256 (600,000 iterations) combined with hardware-backed Android KeyStore AES-256-GCM envelope encryption (`StrongBox` / TEE).
- **In-Memory Streaming:** Media3 ExoPlayer integration for encrypted video and audio playback directly in RAM without temporary disk cache leaks.
- **Zero Plaintext Storage:** Neither passcodes nor raw master keys are ever written to persistent storage.

#### Authentication & Coercion Resistance
- **Layered Security Architecture:** Door passcode, slide-to-unlock gateway, and vault credentials (PIN, Password, Pattern).
- **Plausible Deniability Decoy Vault:** Secondary credential unlocks a completely isolated decoy repository (`files/vault/decoy/`) with identical UI functionality.
- **Hardware-Monotonic Lockout Defense:** Monotonic hardware clock exponential backoff surviving device reboots and clock shifts.
- **Asymmetric Intruder Camera:** Silent Camera2 capture encrypting intruder photos via RSA-2048 hybrid cryptography while the vault is locked.
- **Offline Emergency Recovery:** 16-character offline recovery code unwrap mechanism.

#### Media Management & Scoped Restoration
- **Verified Photo/Video Import:** Multi-select photo picker and document import with pre-deletion SHA-256 integrity verification.
- **Truthful Original Deletion:** Uses `MediaStore.createDeleteRequest` on Android 11+ and direct removal on legacy OS.
- **Safe MediaStore Restoration:** Atomic restoration to public media collections with `IS_PENDING` staging and rollback protection.
- **Safe Photo Editing:** Atomic saves with format preservation (PNG/WEBP/JPEG) and EXIF orientation normalization.
- **Portable Backups:** Password-protected `.svb` standalone encrypted containers.
- **Strict Offline Guarantee:** Zero `android.permission.INTERNET` declaration.
