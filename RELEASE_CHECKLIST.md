# Production Release & Deployment Checklist

This document details the step-by-step procedures to build, sign, verify, and publish a production release of **Voice Recorder & Secure Vault** (`com.my.seekapplication`).

---

## 1. Keystore Generation & Key Management

Generate a dedicated, production-grade release signing keystore if one does not already exist:

```bash
keytool -genkeypair -v \
  -keystore release-key.jks \
  -alias voicerecorder-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype JKS
```

Store `release-key.jks` in a secure location outside version control. Store the passwords in a secure password manager.

---

## 2. Environment & Version Configuration

Verify `app/build.gradle.kts`:
- [ ] `compileSdk` set to `36`
- [ ] `minSdk` set to `24`
- [ ] `targetSdk` set to `36`
- [ ] `versionCode` set to `1`
- [ ] `versionName` set to `"1.0.0"`
- [ ] ProGuard / R8 minification enabled for release builds (`isMinifyEnabled = true`, `isShrinkResources = true`)

---

## 3. Merged Manifest & Permission Verification

Confirm that no unwanted permissions (especially `INTERNET`) exist in the merged manifest:

```bash
./gradlew processReleaseManifest
```

Inspect the output at `app/build/intermediates/merged_manifest/release/processReleaseManifest/AndroidManifest.xml`:
- [ ] Verify `android.permission.INTERNET` is **strictly absent**.
- [ ] Verify `android.permission.RECORD_AUDIO` is present.
- [ ] Verify `android.permission.FOREGROUND_SERVICE` and `android.permission.FOREGROUND_SERVICE_MICROPHONE` are present.
- [ ] Verify `android.permission.POST_NOTIFICATIONS` is present.
- [ ] Verify `android.permission.CAMERA` is present.
- [ ] Verify `<files-path name="all_files" path="." />` is NOT in `res/xml/file_paths.xml`.

Alternatively, verify using `aapt2`:
```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release-unsigned.apk | grep -i "permission"
```

---

## 4. Build Signed Android App Bundle (AAB)

Build the production release bundle:

```bash
./gradlew bundleRelease --stacktrace
```

Sign the generated bundle with jarsigner or Android SDK `bundletool`:

```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore release-key.jks \
  app/build/outputs/bundle/release/app-release.aab voicerecorder-release
```

---

## 5. Multi-Version OS Compatibility Testing (Release Build)

Install the signed release build onto physical devices or emulators across target Android versions:

| Android Version | API Level | Key Areas to Verify | Status |
| :--- | :--- | :--- | :--- |
| **Android 10** | API 29 | MediaStore scoped storage, `IS_PENDING` behavior, legacy file paths. | [ ] Tested |
| **Android 13** | API 33 | `POST_NOTIFICATIONS` runtime permission, photo picker integration. | [ ] Tested |
| **Android 14** | API 34 | `FOREGROUND_SERVICE_MICROPHONE` runtime constraint, receiver export flags. | [ ] Tested |
| **Android 15 / 16** | API 35 / 36 | Edge-to-edge layout, predictive back gestures, 16 KB page size compatibility. | [ ] Tested |

---

## 6. Functional & Security Smoke Test Matrix

- [ ] **Decoy Voice Recorder:**
  - Start, pause, resume, and stop an audio recording.
  - Verify audio continues recording when screen is locked or home button is pressed.
  - Verify recording status notification is displayed.
  - Verify saved recording plays back and appears in `Recordings/MyVoiceRecordings`.
- [ ] **Covert Secret Door Entry:**
  - Short tap (< 500ms): verifies recording toggle.
  - Mid-duration hold (500ms to 4s): verifies neutral deadzone (no tell, no action).
  - Long hold (≥ 5s): verifies haptic buzz and secret door passcode prompt.
  - Verify background recording is NOT killed or discarded when door opens.
- [ ] **Setup Wizard & Recovery Code:**
  - Complete first-run wizard: setup door PIN, pattern/PIN vault key, decoy PIN, and 16-character recovery code.
- [ ] **Decoy Vault Isolation:**
  - Enter decoy PIN: verify separate empty vault opens with full file import/export parity.
- [ ] **Brute-Force Lockout:**
  - Enter wrong credentials 5 times: verify 30-second lockout.
  - Reboot device during lockout: verify remaining penalty carries forward.
- [ ] **Media Import & Verified Restoration:**
  - Import photos, videos, and audio files.
  - Verify in-memory ExoPlayer playback and photo pinch-to-zoom.
  - Restore file back to public gallery and verify size/checksum match.
- [ ] **Encrypted Backup & Reinstall Test:**
  - Export `.svb` backup to external storage.
  - Uninstall the app (or clear app data).
  - Reinstall the release build.
  - Complete setup and import the `.svb` backup.
  - Verify all restored media files are decrypted and intact.
