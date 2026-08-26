# Privacy Policy for Audio Blackbox

**Effective Date:** August 26, 2026  
**App:** Audio Blackbox (`cc.machado.audioblackbox`)

Audio Blackbox was built with a fundamental commitment to privacy: **your audio never leaves your device**. This Privacy Policy explains how Audio Blackbox handles data when you use the app.

---

## 1. Summary: Zero Network Egress & 100% On-Device

- **No Remote Servers:** Audio Blackbox contains no network code, no remote servers, and no third-party analytics SDKs.
- **No Internet Access:** The application does not request the `android.permission.INTERNET` permission. It is physically impossible for the app to send data over the network.
- **No User Accounts:** You do not need to create an account, log in, or provide any personal details (such as your name, email address, or phone number) to use Audio Blackbox.

---

## 2. Information Handled by the App

### Audio Data (Microphone)
- **Purpose:** The core function of Audio Blackbox is to maintain a temporary rolling buffer of recent ambient sound in memory (RAM), operating like a dashcam for audio.
- **In-Memory Retention:** Captured audio is held strictly in temporary RAM. As new sound is recorded, older sound is automatically overwritten.
- **Local Storage:** Audio is only written to device storage (as an `.m4a` / AAC file in your device's `Recordings` or `Music` directory) when you explicitly tap the save action or initiate forward recording.
- **Third-Party Access:** Audio Blackbox never shares, sells, or transmits your audio recordings to any third party.

---

## 3. Permissions Requested

1. `RECORD_AUDIO`: Required to capture ambient audio into the rolling buffer.
2. `POST_NOTIFICATIONS`: Required on Android 13+ to display a persistent foreground service notification while recording is actively running, ensuring you always know when audio capture is in progress.
3. `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE`: Required on modern Android versions to allow the rolling buffer to remain active in RAM while using other apps or with the screen turned off.

---

## 4. Data Storage and Deletion

- **Buffer Deletion:** Stopping the recording engine or closing the application immediately purges the in-memory rolling audio buffer.
- **Saved Recordings:** Audio files that you explicitly save remain in your device storage until you choose to delete them. You can delete recordings at any time using the in-app Gallery or your device's built-in file manager.
- **Uninstalling:** Uninstalling Audio Blackbox discards all application preferences.

---

## 5. Children's Privacy

Audio Blackbox does not collect, store, or transmit personal data from anyone, including children under the age of 13.

---

## 6. Changes to this Policy

If this Privacy Policy is updated, the revised version will be published in this repository with an updated effective date.

---

## 7. Contact & Source Code

Audio Blackbox is open source. You can inspect the code and verify our privacy guarantees at:  
[https://github.com/alexandre-machado/audio-blackbox](https://github.com/alexandre-machado/audio-blackbox)
