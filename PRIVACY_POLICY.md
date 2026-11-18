# Privacy Policy for Voice Recorder & Secure Vault

**Effective Date:** November 2026  
**Package:** `com.my.seekapplication`  
**Status:** 100% Offline | Zero Data Collection Guarantee  

---

## 1. Overview & Privacy Principles

**Voice Recorder & Secure Vault** is built on an absolute **offline-first, zero-knowledge, zero-telemetry** architecture. The application functions as a high-fidelity voice recorder on the surface and conceals an encrypted personal media vault underneath. 

All user data—including voice recordings, hidden photos, videos, audio notes, and encryption keys—remains exclusively on your local device. No user data is ever collected, transmitted, tracked, or synchronized to any external server or third party.

---

## 2. Zero Network Connectivity & Zero Data Collection

- **Zero Cloud Infrastructure:** There are no remote servers, databases, or analytics endpoints.
- **No Internet Permission:** The application does not declare or request `android.permission.INTERNET` in its manifest. It is technically and architecturally impossible for the application to transmit data over the internet or communicate with external servers.
- **No Third-Party SDKs:** The application contains zero third-party advertising, analytics, or tracking frameworks (no Firebase, no AdMob, no Google Analytics, no Facebook SDK).
- **No Account Requirement:** The application does not require an email, phone number, username, or login account.

---

## 3. Storage Locations & Cryptographic Architecture

### 3.1 Public Voice Recordings
- **Storage Location:** Public voice recordings captured in the decoy voice recorder are stored locally on device storage. On Android 10+ (API 29+), recordings are written to the public media store under `Recordings/MyVoiceRecordings`. On Android 9 and below, recordings are stored in the app's external files directory under `Recordings/MyVoiceRecordings`.
- **Visibility:** These recordings are accessible to the user via standard file managers and audio players on the device, consistent with genuine voice recorder behavior.

### 3.2 Encrypted Vault Files
- **Storage Location:** All hidden media files (photos, videos, audio memos) reside strictly in the application's private internal sandbox:
  `/data/data/com.my.seekapplication/files/vault/real/` (or `files/vault/decoy/` for the decoy profile).
- **Encryption Standard:** Files are encrypted using chunked **AES-256-GCM** (Galois/Counter Mode) with 64 KB blocks and 128-bit authentication tags. Each chunk binds Associated Authenticated Data (AAD) containing the chunk index and file length to prevent truncation or reordering. Files are assigned opaque UUID identifiers (`<uuid>.enc`), with original filenames and metadata stored in an encrypted index.
- **Sandbox Isolation:** On standard (unrooted) Android devices, the operating system's Linux UID sandbox prevents any other installed application from accessing files in this directory.

### 3.3 Intruder Photos
- **Storage Location:** Captured intruder photos are saved in the private sandbox directory `/data/data/com.my.seekapplication/files/intruder_selfies/`.
- **Encryption Standard:** Photos are asymmetrically encrypted using **RSA-2048 hybrid AES-256-GCM**. This allows the camera service to securely encrypt photos while the vault is locked without the master decryption key being present in device memory. Intruder photos can only be decrypted and viewed after unlocking the real vault. Photos are capped at a maximum of 50 captures with automatic recycling of the oldest files.

### 3.4 Encryption Keys & Credential Storage
- **Storage Location:** Wrapped credential metadata is stored in `/data/data/com.my.seekapplication/files/vault_meta/auth.json`.
- **Hardware-Backed Protection:** Plaintext master keys and user passcodes are **never** stored on disk. Master keys are protected via a two-layer wrapping scheme:
  1. PBKDF2-HMAC-SHA256 key derivation with **600,000 iterations** and a 256-bit cryptographic salt.
  2. Android KeyStore hardware-backed AES-256-GCM envelope key (`StrongBox` / Trusted Execution Environment where supported).

---

## 4. Device Removal, "Clear Data", & Encrypted Backups

> [!WARNING]
> **Data Loss Warning on "Clear Data" or Uninstallation:**
> Because vault files and encryption keys are stored inside Android's private internal application sandbox, tapping **"Clear Data"** in Android System Settings or **uninstalling the application** will cause Android to permanently and irrecoverably delete all vault data.

### Portable Encrypted Backups (`.svb`)
To protect against device loss, operating system resets, or accidental app uninstallation, the app includes an offline backup utility:
- You can export a standalone **Encrypted Backup (`.svb`)** from Settings at any time.
- Backups are encrypted with AES-256-GCM using a user-specified passphrase derived via PBKDF2 (600,000 iterations).
- The `.svb` file can be safely stored on external storage, an SD card, a computer, or your preferred storage medium, and restored onto any installation of the app.

---

## 5. Permissions & Justifications

The app requests only the permissions strictly required to perform its functions:

| Permission | Purpose | Data Scope |
| :--- | :--- | :--- |
| `android.permission.RECORD_AUDIO` | Required to record voice notes in the voice recorder interface. | Audio recordings stored locally. Never transmitted. |
| `android.permission.FOREGROUND_SERVICE` | Enables ongoing audio recording while the app is minimized. | Operating system service state only. |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Android 14+ requirement to capture microphone audio in the background. | In-progress audio recording only. |
| `android.permission.POST_NOTIFICATIONS` | Android 13+ requirement to display the active recording notification with pause/resume/save controls. | Local system notification only. |
| `android.permission.CAMERA` | Used exclusively for the optional Intruder Selfie feature to capture an image when wrong passcodes are repeatedly entered. | Encrypted immediately on-device; never leaves device. |
| `android.permission.READ_EXTERNAL_STORAGE` *(API ≤ 32)* | Used only to import user-selected media into the vault or restore exported recordings. | Local file system access only. |
| `android.permission.VIBRATE` | Tactile haptic feedback during pattern entry and secret hold activation. | Device hardware vibration only. |

---

## 6. User Rights & Data Deletion

- **Immediate Deletion:** Deleting any recording, vault file, or intruder selfie from within the app immediately deletes the encrypted file from storage.
- **Restoration to Public Gallery:** Any file stored in the vault can be decrypted and restored to public device storage at any time.
- **Factory Reset / Vault Wipe:** Users can execute a complete destructive vault reset from Settings or the Emergency Recovery screen, securely purging all encrypted files, indexes, and keys.

---

## 7. Contact & Verification

Because this project is open source, its source code and cryptographic implementations are open for independent security review. For technical questions or vulnerability reports, refer to [SECURITY.md](SECURITY.md).
