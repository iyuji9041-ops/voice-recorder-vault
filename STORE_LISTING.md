# Google Play Store Listing & Compliance Disclosure

**App Name:** Voice Recorder & Secure Vault  
**Package:** `com.my.seekapplication`  
**Category:** Productivity / Tools  
**Content Rating:** Everyone  

---

## 1. Store Descriptions

### Short Description (Max 80 Characters)
> Dual-purpose utility: professional voice recorder and encrypted media vault.

*(Character count: 77 characters)*

---

### Full Description (Max 4,000 Characters)

```markdown
Voice Recorder & Secure Vault combines a high-fidelity, everyday voice recorder with a private, military-grade encrypted media vault. 

ON THE SURFACE: A FULL-FEATURED VOICE RECORDER
Capture crystal-clear voice notes, lectures, and meetings with a high-performance audio engine:
• Foreground Service Recording: Audio capture runs uninterrupted in the background while multitasking or with your screen turned off.
• Real-Time Dynamic Waveform: Visual audio monitor responds live to voice volume.
• Pause & Resume: Pause recordings when interrupted and seamlessly resume anytime.
• Standard Storage: Public recordings are saved to your device's audio library for easy sharing and listening.
• Low Storage Protection: Automatically safeguards and saves in-progress recordings before your device runs low on disk space.

BENEATH THE SURFACE: A PRIVATE, ENCRYPTED MEDIA VAULT
Behind the voice recorder interface is a secure, offline personal vault to store private photos, videos, and confidential audio files:
• Chunked AES-256-GCM Encryption: Files are protected with military-grade authenticated encryption.
• Dual-Factor Key Protection: Master keys are protected by PBKDF2 key derivation (600,000 iterations) and Android Keystore hardware security.
• Zero-Disk-Leak In-Memory Streaming: Encrypted videos and audios play directly in memory via an integrated media player without writing decrypted files to temporary disk caches.
• Anti-Coercion Decoy Mode: Set an alternate passcode to open an independent decoy vault if pressured to unlock your device.
• Silent Intruder Camera: Captures an encrypted photograph using the front camera when unauthorized passcode attempts occur.
• Brute-Force Lockout Defense: Monotonic hardware timer enforces exponential delays against repeated guessing attacks.
• Portable Encrypted Backups: Export password-protected .svb archives to safely preserve your private media across devices.

100% OFFLINE & PRIVACY-FIRST
• Zero Internet Permission: The application does not contain or request internet access.
• Zero Data Collection: No tracking, no analytics, no accounts, and no cloud servers. All data stays strictly on your physical device.

IMPORTANT BACKUP NOTICE:
Vault files are stored in Android's private app sandbox. Uninstalling the app or clearing app data will erase vault contents. Use the built-in Encrypted Backup feature to export backups before uninstalling or resetting your device.
```

---

## 2. Google Play Policy Disclosure Statement

**Policy Alignment (Deceptive Behavior & Undisclosed Hidden Features):**
Google Play Developer Program Policies prohibit apps with undisclosed hidden features or deceptive camouflage. This store listing explicitly discloses both functional components of the app:
1. The **Voice Recorder** utility.
2. The integrated **Encrypted Media Vault**.

The user is fully informed upon installation that the application contains both a functional voice recorder and a secure media vault activated by a custom hold interaction on the record button.

---

## 3. Data Safety Form Answers

| Field | Answer | Details |
| :--- | :--- | :--- |
| **Data Shared** | **No** | No user data is shared with any third party or company. |
| **Data Collected** | **No** | The app collects zero user data. No information is transmitted off the device. |
| **Data Encrypted in Transit** | **N/A** | The app does not transmit data over networks (zero internet permission). |
| **Data Encrypted at Rest** | **Yes** | Vault files are encrypted using authenticated chunked AES-256-GCM. |
| **Data Deletion Mechanism** | **Yes** | Users can delete individual items, restore them, or perform a complete factory wipe. |

---

## 4. Permission Justifications

| Permission | Declaration | Purpose & User Justification |
| :--- | :--- | :--- |
| `android.permission.RECORD_AUDIO` | Runtime | Essential for capturing voice audio notes in the voice recorder. |
| `android.permission.FOREGROUND_SERVICE` | Install-time | Enables the recording service to remain active while the user switches apps or locks the screen. |
| `android.permission.FOREGROUND_SERVICE_MICROPHONE` | Runtime / Service | Required on Android 14+ to access the microphone from a foreground service during background recording. |
| `android.permission.POST_NOTIFICATIONS` | Runtime (API 33+) | Displays an ongoing recording notification showing recording state with pause, resume, and save controls. |
| `android.permission.CAMERA` | Runtime | Used only on-device for the optional Intruder Selfie feature upon failed unlock attempts. |
| `android.permission.READ_EXTERNAL_STORAGE` | Runtime (API ≤ 32) | Allows user-directed import of photos, videos, and audio files into the encrypted vault on older Android releases. |
| `android.permission.VIBRATE` | Normal | Provides haptic tactile feedback when entering credentials or activating controls. |

---

## 5. Target Audience & Content Classification

- **Target Audience:** Adults (18+) and general consumers requiring voice recording tools alongside private, local-only encrypted file storage.
- **Children's Policy:** Does not target children under the age of 13.
- **Ads Policy:** Contains **zero** ads and no ad-network SDKs.
