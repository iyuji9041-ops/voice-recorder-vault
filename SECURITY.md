# Security Architecture & Threat Model

**Application:** Voice Recorder & Secure Vault (`com.my.seekapplication`)  
**Specification Version:** Phase 3 Hardened Security & Authentication Standard  
**Target Platform:** Android 7.0 (API 24) through Android 16 (API 36)  
**Network Capability:** Zero (100% Offline, no `android.permission.INTERNET`)

---

## 1. Executive Summary

Voice Recorder & Secure Vault employs a defense-in-depth security model combining:
- Authenticated, chunked symmetric encryption (**AES-256-GCM**).
- Dual-factor key wrapping via **PBKDF2-HMAC-SHA256 (600,000 iterations)** and **Android Keystore** hardware-backed master key.
- Strict isolation within Android's private internal sandbox.
- Plausible deniability via an anti-coercion **Decoy Vault** with constant-time verification.
- **In-memory zero-disk-leak streaming** for audio, video, and image playback.
- Password-protected, portable **offline backups (`.svb`)**.
- **Persistent hardware-monotonic exponential backoff lockout** resisting reboots and clock shifts.
- **Offline 16-character Master Recovery Code** slot unwrap without insecure question bypasses.
- **Asymmetric RSA-2048 hybrid encryption** for silent Camera2 intruder selfies taken while the vault is locked.
- **Automated session destruction (Auto-Lock)** on backgrounding, screen turn-off, and OS recents exemption.

---

## 2. Threat Model & Boundaries

### 2.1 Protected Threats
- **Lost or Stolen Device (Locked Screen):** An attacker without the user's PIN/Password cannot decrypt vault contents. Master keys are not stored on disk in plaintext; they are wrapped with dual factors.
- **Malicious Third-Party Applications:** On standard Android devices (unrooted), Android's Linux UID-based application sandbox ensures other apps cannot read `/data/data/com.my.seekapplication/files/`.
- **Public Storage Scrapers:** No hidden files or metadata are stored in public storage (`Documents`, `Downloads`, or external SD). External gallery scrapers and media scanners cannot discover vault files.
- **Physical Coercion (Rubber Hose Cryptanalysis):** If coerced into providing access, the user can reveal the Decoy credential. The decoy credential opens a separate, isolated repository (`files/vault/decoy`) through the exact same visual UI with full functional parity.
- **Timing Attacks:** The authentication pipeline executes balanced PBKDF2 derivation and `MessageDigest.isEqual` byte-level constant-time comparisons.
- **Brute-Force & Automated Guessing:** Hardware monotonic clock tracking (`SystemClock.elapsedRealtime`) combined with device boot count (`BOOT_COUNT`) enforces persistent exponential backoff delays (30s, 1m, 5m, 15m). Rebooting the device or tampering with the system clock does not bypass the lockout timer.
- **Data Tampering & Bit-Flipping:** AES-256-GCM enforces 128-bit authentication tags per 64 KB chunk. Any flipped bit in the ciphertext, IV, chunk counter, or length metadata triggers an immediate cryptographic validation error.
- **Disk Artifact Leaks:** Videos and audios are streamed directly into ExoPlayer via in-memory seekable data sources. Photos are streamed and downsampled in RAM. Plaintext files are never written to `cacheDir` or temporary disk storage.
- **OS Screenshot & Recents Snooping:** `FLAG_SECURE` prevents screenshots, screen recording, and system window previews. `android:excludeFromRecents="true"` prevents cached thumbnails in the Android Overview screen.

### 2.2 Out-of-Scope / Physical Limitations
- **Rooted Device / Kernel Exploitation:** If the host Android operating system is compromised by an adversary with root permissions, kernel memory or RAM inspection can inspect data in volatile process memory while the vault is active.
- **Hardware-Level RAM Extraction (Cold Boot Attacks):** When the vault is active, the decrypted master key resides temporarily in volatile memory. It is cleared upon lock, screen off, or activity exit.
- **App Data Clearing:** If the user manually chooses "Clear Data" in Android System Settings, the OS deletes the private application directory. To prevent permanent data loss, users must maintain an encrypted `.svb` backup.

---

## 3. Cryptographic Implementation Details

### 3.1 Vault Storage & File Format (`.enc`)
Each file in the vault is stored under an opaque UUID filename (`<uuid>.enc`) inside the app's private sandbox:
```
/data/data/com.my.seekapplication/files/
  ├── vault/
  │   ├── real/
  │   │   ├── index.enc              # Encrypted manifest metadata (JSON)
  │   │   └── <uuid>.enc             # Encrypted media files
  │   └── decoy/
  │       ├── index.enc
  │       └── <uuid>.enc
  ├── vault_meta/
  │   ├── auth.json                  # Salt, wrapped key headers (no plaintext keys)
  │   ├── intruder_pub.der           # RSA-2048 public key for locked selfie encryption
  │   ├── intruder_priv.enc          # RSA-2048 private key encrypted with Real Master Key
  │   └── intruder_audit.json        # Persistent security audit log
  └── intruder_selfies/
      └── selfie_<timestamp>.enc     # Asymmetrically encrypted intruder captures
```

Each `<uuid>.enc` file contains:
```
+---------------------------------------------------------------------------------+
| Header (29 bytes)                                                               |
| - Magic: 'SVC1' (4 bytes: 0x53564331)                                           |
| - Version: 0x01 (1 byte)                                                        |
| - Chunk Size: 65,536 (4 bytes, Big-Endian)                                      |
| - Plaintext Size: Long (8 bytes, Big-Endian)                                    |
| - Base Nonce: 12 bytes cryptographic random bytes                               |
+---------------------------------------------------------------------------------+
| Chunks (Repeated for N chunks)                                                  |
| - Chunk 0: Encrypted Payload (up to 64 KB) + 16-byte GCM Tag                    |
| - Chunk 1: Encrypted Payload (up to 64 KB) + 16-byte GCM Tag                    |
| ...                                                                             |
| - Chunk N-1: Final Encrypted Payload (<= 64 KB) + 16-byte GCM Tag               |
+---------------------------------------------------------------------------------+
```

#### Nonce Construction & AAD Binding
- **Nonce Derivation:** For each chunk `i`, the 12-byte GCM nonce is generated by taking the 12-byte base nonce and XORing its last 8 bytes with chunk index `i`. This guarantees unique nonces across all chunks.
- **Associated Authenticated Data (AAD):** Each chunk binds the file's plaintextSize, chunkSize, and chunk index into AAD:
  `AAD = [ChunkIndex (8 bytes)][PlaintextSize (8 bytes)][ChunkSize (4 bytes)]`
  This prevents chunk reordering, truncation, or chunk substitution from other files.

### 3.2 Dual-Factor Master Key Architecture
The Vault Master Key is a cryptographically secure 256-bit random key (`AES-256`). It is protected via a two-layer key wrapping scheme:
1. **User Key Derivation (PBKDF2):**
   - Algorithm: `PBKDF2WithHmacSHA256`
   - Iterations: `600,000` (OWASP-recommended benchmark for high security)
   - Salt: 256 bits (32 bytes) cryptographically random per vault
   - Derived Key: 256-bit AES key
2. **Hardware Keystore Protection:**
   - Key: 256-bit AES-GCM key generated inside Android KeyStore (`StrongBox` / TEE where available)
   - Alias: `SecureVaultHardwareMasterKey_v2`
3. **Double Wrapping Process:**
   - Step 1: `InnerCiphertext = AES-256-GCM(Key = PBKDF2_Key, MasterKey)`
   - Step 2: `OuterCiphertext = AES-256-GCM(Key = Keystore_Key, InnerCiphertext)`
   - The wrapped key blob and IVs are stored in private `auth.json`.

---

## 4. Brute-Force Defense: Hardware-Monotonic Exponential Backoff

Authentication inputs across all three layers (Door Passcode, Vault Key, Recovery Token) are guarded by `LockoutManager`:
- **Hardware Monotonic Clock:** Backoff duration is measured using `SystemClock.elapsedRealtime()`, which advances strictly based on hardware oscillation and cannot be modified by setting the device clock forward or backward.
- **Reboot Evasion Detection:** `LockoutManager` records the system boot count (`Settings.Global.BOOT_COUNT`) and elapsed start point. If the device reboots while a lockout penalty is active, the remaining penalty duration is carried forward, resetting the countdown start to zero on the new boot.
- **Exponential Backoff Schedule:**
  - 1 to 4 failed attempts: Zero delay.
  - 5 failed attempts: 30 seconds delay.
  - 6 failed attempts: 1 minute delay.
  - 7 failed attempts: 5 minutes delay.
  - 8+ failed attempts: 15 minutes delay.
- **Target Independence:** Failures on the entrance door passcode do not pollute the vault master key lockout, preserving independent rate limits per boundary.

---

## 5. Offline Recovery Architecture (Zero Bypass)

The legacy insecure bypass model (`|| !hasSecurityQuestion`) has been completely eradicated.
- **Offline Recovery Slot:** During initial vault setup, a 16-character cryptographically random recovery code (`XXXX-XXXX-XXXX-XXXX`) is generated and presented to the user.
- **Master Key Recovery Slot Wrapping:** The master key is wrapped using a 256-bit key derived from the normalized recovery code via `PBKDF2-HMAC-SHA256 (600,000 iterations)` and saved into `recovery_wrapped_key`, `recovery_salt`, and `recovery_iv` inside `auth.json`.
- **Zero Cloud Requirement:** The user can unlock and reset their master key entirely offline without external network communication.
- **Destructive Vault Wipe:** If the recovery code is lost and the master password is forgotten, no backdoor exists. Users can invoke a complete factory reset that securely purges all encrypted files, keys, and security audit logs.

---

## 6. Asymmetric Hybrid Intruder Camera (Camera2)

Failed authentication attempts trigger silent background photograph capture using the Android Camera2 API:
- **Zero Memory Key Leakage:** During failed unlock attempts, the vault is locked and the Master Key does not exist in RAM.
- **Hybrid Public-Key Crypto:** An **RSA-2048** keypair is generated during setup:
  - The public key (`intruder_pub.der`) is stored in app private storage and used by the camera service to encrypt an ephemeral AES-256-GCM key per photo.
  - The private key (`intruder_priv.enc`) is encrypted with the Real Master Key.
- **Locked Capture:** Intruder selfies are encrypted on disk (`selfie_<timestamp>.enc`) without needing the Master Key or private key in memory.
- **Decryption:** The photos can only be decrypted and viewed after a successful Real Vault unlock.
- **Decoy Concealment:** In Decoy mode, intruder photos and audit logs are hidden to preserve plausible deniability.
- **Storage Cap:** Stored selfies are capped at 50 photos (oldest automatically deleted upon reaching cap).
- **Persistent Audit Trail:** Every event is recorded in `intruder_audit.json` (layer, timestamp, attempt number, status).

---

## 7. Decoy Isolation & Anti-Coercion Parity

- **Independent Keys:** The decoy vault maintains a separate 256-bit master key wrapped under the user's decoy passcode.
- **Filesystem Separation:** The decoy vault files are located at `files/vault/decoy/`, completely physically isolated from `files/vault/real/`.
- **Full Functional Parity:** Decoy mode allows file addition, encryption, viewing, and deletion into the decoy storage without any "Fake Vault" toast or visual leak.
- **Constant-Time Verification:** When testing an entered credential, PBKDF2 derivation is performed for both real and decoy profiles with equal computation work, preventing timing leaks.

---

## 8. Streaming In-Memory Playback (Zero Disk Leaks)

- **Media3 ExoPlayer:** Custom `EncryptedVaultDataSource` decrypts 64 KB chunks directly into RAM buffers upon ExoPlayer request. Random-access seek operations compute the target chunk index in $O(1)$ time, decrypt only the requested chunk, and stream audio/video without ever writing decrypted bytes to disk.
- **Video Thumbnails:** Extracted via `EncryptedMediaDataSource` connected directly to `MediaMetadataRetriever` in RAM.
- **Photo Rendering:** Photos are decrypted via `BitmapFactory.decodeStream` with downsampling (`inSampleSize`) directly into Bitmaps, without intermediate temporary files.
- **Cache Eradication:** `VaultSession.wipeAppCache(context)` is invoked on app startup, vault lock, and session termination to ensure no transient files remain in `cacheDir`.

---

## 9. Encrypted Backup Specification (`.svb`)

- **Format:** Standalone `.svb` encrypted container.
- **Header:** Magic identifier `0x5356424B` ("SVBK"), Version `0x01`, Salt 32 bytes.
- **Cipher:** AES-256-GCM chunked stream derived via `PBKDF2-HMAC-SHA256` (600,000 iterations) using a custom backup passphrase.
- **Payload:** Encrypted ZIP archive containing `manifest.json` and `files/<id>.dat`.
- **Restoration Safety:** Backup imports decrypt each item directly into the active vault sandbox.
