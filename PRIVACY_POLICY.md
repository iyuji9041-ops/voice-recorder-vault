# Privacy Policy for Voice Recorder & Secure Vault

**Last Updated:** November 2026  
**Status:** 100% Offline | Zero Data Collection Guarantee  

---

## 1. Introduction
**Voice Recorder & Secure Vault** (`com.my.seekapplication`) is engineered with a strict **Privacy-First, Offline-First** philosophy. Your personal memories, media files, and voice recordings remain solely on your local device, completely isolated from data tracking, cloud breaches, or third-party monitoring.

---

## 2. Zero Data Collection & Zero Cloud Communication
* **No Cloud Servers:** The application operates without remote databases, servers, or external synchronization endpoints.
* **No Telemetry or Tracking:** Zero third-party analytics libraries (such as Firebase, AdMob, or Mixpanel) are included in the app.
* **No Network Permission:** The Android system permission `android.permission.INTERNET` is strictly excluded from `AndroidManifest.xml`. The app is technically incapable of sending data over the network.
* **On-Device Cryptography:** All encryption, decryption, media streaming, and audio recordings execute 100% on your device's local processor.

---

## 3. Cryptographic Security & Storage Architecture
* **AES-256-GCM Chunked Encryption:** All vault files are encrypted with chunked Galois/Counter Mode (AES-256-GCM) with 128-bit authentication tags and cryptographically bound chunk headers.
* **Dual-Factor Key Protection:** Master keys are wrapped with Android Keystore hardware keys and PBKDF2-HMAC-SHA256 (600,000 iterations). Raw user credentials and master keys are never stored in plaintext.
* **Private Sandbox Isolation:** Vault files are held in the application's private internal sandbox (`filesDir/vault/`), inaccessible to other apps on standard (unrooted) Android devices.
* **Important Notice Regarding "Clear Data":** Vault data is stored in Android's private app sandbox. If you tap "Clear Data" in Android System Settings or uninstall the application, Android permanently erases the app's internal sandbox. To preserve your vault files across device resets or uninstalls, you can export a password-protected **Encrypted Backup (`.svb`)** from Vault Settings at any time.

---

## 4. Permissions & Usage
The application requests only the essential runtime permissions required for its voice recording and security capabilities:

| Permission | Purpose | Data Location |
| :--- | :--- | :--- |
| `RECORD_AUDIO` | Enables high-fidelity voice recording in the decoy app. | Saved locally to device storage (`Recordings/MyVoiceRecordings`). Never sent online. |
| `READ_MEDIA_*` / `READ_EXTERNAL_STORAGE` | Allows importing photos, videos, and audios into the vault and exporting restored files back to public storage. | Stored encrypted locally on device. |
| `CAMERA` | Front-camera photo capture during unauthorized unlock attempts (Intruder Selfie). | Stored locally in private app sandbox. |
| `VIBRATE` | Tactile haptic feedback during pattern drawing and slider interaction. | On-device only. |

---

## 5. User Data Control
* **Permanent Deletion:** When you delete a recording, hidden photo, video, or intruder selfie from within the app, it is deleted immediately from disk.
* **Restoring Media:** You can decrypt and restore any file back to public device storage at any time.
* **Encrypted Backups:** You can generate an encrypted `.svb` archive with a custom passphrase to securely store backups on an external drive, USB, or cloud storage of your choice.

---

## 6. Open Source & Transparency
The full source code of this application is open for inspection to verify our privacy, offline architecture, and security assurances.
