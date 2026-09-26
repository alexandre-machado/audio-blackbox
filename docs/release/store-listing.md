# Google Play Store Listing & Launch Metadata

Comprehensive store listing copy, graphic assets specification, and policy declaration answers for **Audio Blackbox** on Google Play Console (Issue #48).

---

## 1. Store Listing Copy (Bilingual)

The listing text lives in `distribution/metadata/android/{en-US,pt-BR}/` and is pushed to Play by `scripts/ci/sync-play-store-metadata.py`. Those files are the source of truth; this section only records the constraints and the current title and short description, so the long copy is not duplicated here and left to drift (issue #419 found the old copy here still promising a "5 to 60 minutes" window).

| Field | Play limit | `en-US` | `pt-BR` |
| :--- | :--- | :--- | :--- |
| Title (`title.txt`) | 30 | `Audio Blackbox: Past Recorder` (29) | `Audio Blackbox: Gravador` (24) |
| Short description (`short_description.txt`) | 80 | `Save the last minutes you just heard. Stays on your phone, no internet access.` (78) | `Salve os minutos que você acabou de ouvir. Fica no seu celular, sem internet.` (77) |
| Full description (`full_description.txt`) | 4000 | see file | see file |

Rules the copy must keep (issue #419):

- **Truthful to the shipped app.** The buffer is at least 5 minutes, in 5-minute steps, with no fixed maximum: the ceiling is computed per device and per quality preset (`DeviceMemoryBudget`, #298). Save always exports the whole buffer (#121), and the next save starts where the previous one ended (#410). Export is AAC (`.m4a`). The merged manifest has no `INTERNET` permission (`ManifestPermissionSecurityTest`). Never quote a fixed set of windows or a fixed maximum.
- **Use cases, in this order:** musicians and songwriters, ideas and thinking out loud, meetings and agreements the user is part of, family moments.
- **Never** use "secret", "stealth", "hidden", "spy" or "record without them knowing"; never suggest recording conversations the user is not part of; never claim "court-admissible", "legal evidence" or "legal everywhere".
- **Do** say "your own conversations and ideas", mention the visible notification while recording, say the audio stays on the phone, and tell users to check their local recording laws. Keep the consent disclaimer in both languages.
- Play format rules: no ranking claims ("#1", "best"), no emoji, no ALL CAPS, no keyword stuffing. EN and PT-BR are equivalent in content, not literal translations.

---

## 2. Play Store Graphical Assets

| Asset | Dimension & Format | Status & File Location |
| :--- | :--- | :--- |
| **App Icon (Store Listing)** | 512 x 512 px, 32-bit PNG (with alpha) | [`docs/design/store/ic_launcher_store_512.png`](docs/design/store/ic_launcher_store_512.png) |
| **Feature Graphic** | 1024 x 500 px, 24-bit PNG (RGB, no alpha) | [`docs/design/store/feature_graphic_1024x500.png`](docs/design/store/feature_graphic_1024x500.png) |
| **Phone Screenshots** | Min 2, up to 8 (1080x1920 or native) | Generated automatically by CI `ScreenshotCaptureTest` into `build/screen-captures/` |

---

## 3. Data Safety Form (Google Play Console)

Copy-pasteable questionnaire responses for Google Play Console:

1. **Does your app collect or share any user data?**
   - **Answer**: `Yes` (Audio recordings).
2. **Audio (Voice or sound recordings)**:
   - **Collected?**: `Yes`
   - **Shared with third parties?**: `No`
   - **Is this data processed ephemerally?**: `No` (Audio is buffered in RAM and saved to on-device storage upon user request).
   - **Is this data required or optional?**: `Required` (Core functionality of the app).
   - **Purposes**: `App functionality`.
3. **Data transfer and security practices**:
   - **Is data transferred over a secure connection?**: `N/A - Data is never transferred off the device (Zero network egress)`.
   - **Can users request data deletion?**: `Yes` (Users can delete exported files at any time via the in-app Gallery or standard file managers. In-memory buffer is discarded when recording is stopped or the app is uninstalled).

---

## 4. Foreground Service Declaration (`TYPE_MICROPHONE`)

Because `targetSdk` is 36, Google Play requires a specific declaration for `FOREGROUND_SERVICE_MICROPHONE`:

- **Use case selection**: `Background Audio Access / Voice recording`.
- **Functionality Description**:
  > Audio Blackbox provides a continuous rolling audio buffer in device RAM (user-configured in 5-minute steps from a 5-minute minimum up to a ceiling the app computes from the device's available memory), operating like an audio dashcam. The user explicitly controls the service via a prominent switch on the dashboard and persistent system notification. When the user taps 'Save recent audio', the recent in-memory buffer is exported to device storage. If the foreground service were stopped, the in-memory rolling buffer would be immediately lost, preventing the user from retrieving recently elapsed audio.
- **Demo Video Requirement**:
  > A short video recorded on a real device or emulator showing:
  > 1. User launching the app and toggling the recording engine ON.
  > 2. Persistent notification appearing in the system drawer.
  > 3. Tapping 'Save recent audio' and viewing the exported recording in the Gallery.

---

## 5. App Category & Target Audience

- **Category**: `Tools` / `Audio & Video`.
- **Target Audience**: `18 and over` (or `13 and over`).
- **Contains Ads**: `No`.
- **Pricing**: `Free`.

---

## 6. Advertising ID Declaration (ID de Publicidade / AD_ID)

Google Play Console requires all apps targeting Android 13+ (API 33+) to declare their Advertising ID usage under **Policy > App content > Advertising ID** (Política > Conteúdo do app > ID de publicidade).

- **Question**: *Does your app use an advertising ID?* (*O seu app usa o código de publicidade?*)
- **Answer**: **`No`** (**`Não`**)

### Rationale & Invariants
1. Audio Blackbox contains zero advertisements, zero monetization SDKs, and zero telemetry trackers.
2. The `com.google.android.gms.permission.AD_ID` permission is **not requested or merged** anywhere in the app.
3. The app is 100% offline with zero network permissions (`android.permission.INTERNET` is completely absent, as verified by `ManifestPermissionSecurityTest`).
