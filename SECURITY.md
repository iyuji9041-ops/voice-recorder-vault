# Security Architecture & Threat Model

**Application:** Voice Recorder & Secure Vault (`com.my.seekapplication`)  
**Specification Version:** Phase 2 Hardened Security Standard  
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

---

## 2. Threat Model & Boundaries

### 2.1 Protected Threats
- **Lost or Stolen Device (Locked Screen):** An attacker without the user's PIN/Password cannot decrypt vault contents. Master keys are not stored on disk in plaintext; they are wrapped with dual factors.
- **Malicious Third-Party Applications:** On standard Android devices (unrooted), Android's Linux UID-based application sandbox ensures other apps cannot read `/data/data/com.my.seekapplication/files/`.
- **Public Storage Scrapers:** No hidden files or metadata are stored in public storage (`Documents`, `Downloads`, or external SD). External gallery scrapers and media scanners cannot discover vault files.
- **Physical Coercion (Rubber Hose Cryptanalysis):** If coerced into providing access, the user can reveal the Decoy credential. The decoy credential opens a separate, empty repository through the exact same visual UI and code path.
- **Timing Attacks:** The authentication pipeline executes balanced PBKDF2 derivation regardless of whether a real, decoy, or invalid credential is submitted, mitigating timing side-channel leaks.
- **Data Tampering & Bit-Flipping:** AES-256-GCM enforces 128-bit authentication tags per 64 KB chunk. Any flipped bit in the ciphertext, IV, chunk counter, or length metadata triggers an immediate cryptographic validation error.
- **Disk Artifact Leaks:** Videos and audios are streamed directly into ExoPlayer via in-memory seekable data sources. Photos are streamed and downsampled in RAM. Plaintext files are never written to `cacheDir` or temporary disk storage.

### 2.2 Out-of-Scope / Physical Limitations
- **Rooted Device / Kernel Exploitation:** If the host Android operating system is compromised by an adversary with root permissions, kernel memory or RAM inspection can inspect data in volatile process memory while the vault is active.
- **Hardware-Level RAM Extraction (Cold Boot Attacks):** When the vault is active, the decrypted master key resides temporarily in volatile memory. It is cleared upon lock or activity exit.
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
  └── vault_meta/
      └── auth.json                  # Salt, wrapped key headers (no plaintext keys)
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

## 4. Decoy Isolation & Anti-Coercion

- **Independent Keys:** The decoy vault maintains a separate 256-bit master key wrapped under the user's decoy passcode.
- **Filesystem Separation:** The decoy vault files are located at `files/vault/decoy/`, completely physically isolated from `files/vault/real/`.
- **Indistinguishable UI:** Both vaults load using the same activity (`VaultGalleryActivity`), adapter, and media viewer. Decoy mode presents an empty repository with fully functional addition and browsing capabilities.
- **Constant-Time Verification:** When testing an entered credential, PBKDF2 derivation is performed for both real and decoy profiles with equal computation work, preventing timing leaks.

---

## 5. Streaming In-Memory Playback (Zero Disk Leaks)

- **Media3 ExoPlayer:** Custom `EncryptedVaultDataSource` decrypts 64 KB chunks directly into RAM buffers upon ExoPlayer request. Random-access seek operations compute the target chunk index in $O(1)$ time, decrypt only the requested chunk, and stream audio/video without ever writing decrypted bytes to disk.
- **Video Thumbnails:** Extracted via `EncryptedMediaDataSource` connected directly to `MediaMetadataRetriever` in RAM.
- **Photo Rendering:** Photos are decrypted via `BitmapFactory.decodeStream` with downsampling (`inSampleSize`) directly into Bitmaps, without intermediate temporary files.
- **Cache Eradication:** `VaultSession.wipeAppCache(context)` is invoked on app startup, vault lock, and session termination to ensure no transient files remain in `cacheDir`.

---

## 6. Encrypted Backup Specification (`.svb`)

To protect user data against Android "Clear Data" actions or device migration, the app provides standalone encrypted archives:
- **Format:** Single `.svb` file.
- **Header:**
  - Magic identifier: `0x5356424B` ("SVBK")
  - Version: `0x01`
  - Salt: 32 bytes
- **Cipher:** AES-256-GCM chunked stream with key derived via `PBKDF2-HMAC-SHA256` (600,000 iterations) using the user's custom backup passphrase.
- **Payload:** Encrypted ZIP archive containing `manifest.json` (metadata and original filenames) and `files/<id>.dat`.
- **Restoration Safety:** Backup imports run in background coroutines, decrypting each item directly into the active vault repository.
