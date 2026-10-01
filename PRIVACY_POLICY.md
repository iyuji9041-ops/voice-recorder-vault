# Privacy Policy for Voice Recorder & Secure Vault

**Last Updated:** October 2026  
**Status:** 100% Offline | Zero Data Collection Guarantee  

---

## 1. Introduction
**Voice Recorder & Secure Vault** is designed with a strict **Privacy-First, Offline-First** philosophy. We firmly believe that your personal memories, documents, and voice recordings should remain solely on your personal hardware, immune to data tracking, breaches, or unauthorized cloud storage.

---

## 2. Zero Data Collection & Zero Cloud Communication
* **No Cloud Servers:** The app does not maintain any remote databases, user accounts, or cloud synchronization servers.
* **No Telemetry or Analytics:** No third-party analytics libraries (such as Firebase Analytics, Mixpanel, or Google AdMob) are integrated.
* **No Network Transmission:** All cryptographic operations, media rendering, audio processing, and file management take place 100% locally on your device processor.

---

## 3. Cryptographic Security Standards
* **AES-256-GCM Encryption:** Photos, videos, and confidential audio files stored inside the vault are encrypted with Galois/Counter Mode (AES-256-GCM) with random IVs before being written to disk.
* **PBKDF2-HMAC-SHA256 Key Derivation:** Passwords, PINs, and Pattern sequences are hashed with 10,000 iterations using cryptographic random salts. Raw passwords are never stored anywhere in plaintext.
* **Anti-Wipe Persistence:** Vault files are preserved in a secure local storage directory equipped with `.nomedia` indexing shields, ensuring that device cache wipes or accidental data cleanups do not wipe your encrypted memories.

---

## 4. Permissions & Usage
The application requests the minimal set of runtime permissions necessary for its authentic decoy utility and security features:

| Permission | Purpose | Data Location |
| :--- | :--- | :--- |
| `RECORD_AUDIO` | Enables high-fidelity voice recording in the decoy app. | Saved locally to device storage (`Recordings/MyVoiceRecordings`). Never sent online. |
| `READ/WRITE_EXTERNAL_STORAGE` | Allows importing files into the vault and exporting restored files back to public storage. | Stored encrypted locally on device. |
| `CAMERA` | Front-camera photo capture during unauthorized unlock attempts (Intruder Selfie). | Stored locally in private app sandbox. |
| `USE_BIOMETRIC` | Optional biometric authentication for swift vault unlocking. | Handled strictly by Android BiometricPrompt hardware keystore. |

---

## 5. User Data Control
* **Permanent Deletion:** When you delete a recording, hidden photo, video, or intruder selfie from within the app, it is deleted immediately from disk.
* **Unhiding Media:** You can decrypt and restore any file back to public device storage at any time.
* **Sharing:** Voice recordings are only shared when you explicitly tap the Share icon via Android's native system share sheet.

---

## 6. Regulatory Compliance
* **GDPR & CCPA Compliant:** Because we collect zero personally identifiable information (PII), your rights under GDPR and CCPA are inherently protected.
* **COPPA Compliant:** The application does not collect data from children or any age demographic.

---

## 7. Open Source & Transparency
The full source code of this application is open for inspection to verify our privacy and security assurances.
