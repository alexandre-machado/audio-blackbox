# Audio Blackbox

<p align="center">
  <img src="docs/design/store/feature_graphic_1024x500.png" alt="Audio Blackbox Banner" width="800">
</p>

<p align="center">
  <a href="https://github.com/alexandre-machado/audio-blackbox/actions/workflows/ci.yml"><img src="https://github.com/alexandre-machado/audio-blackbox/actions/workflows/ci.yml/badge.svg" alt="CI Status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-3DDC84.svg?logo=android&logoColor=white" alt="Android Platform"></a>
  <a href="docs/release/privacy-policy.md"><img src="https://img.shields.io/badge/Privacy-100%25%20Offline%20%7C%20Zero%20Network-success.svg" alt="Zero Network Permission"></a>
  <a href="https://m3.material.io"><img src="https://img.shields.io/badge/Design-Material%203-6750A4.svg" alt="Material 3"></a>
  <a href="https://play.google.com/apps/testing/cc.machado.audioblackbox"><img src="https://img.shields.io/badge/Google_Play-Beta_Testing-01875F.svg?logo=googleplay&logoColor=white" alt="Google Play Beta"></a>
  <a href="https://alexandre.machado.cc/audio-blackbox"><img src="https://img.shields.io/badge/Project_Site-alexandre.machado.cc-FF5722.svg" alt="Project Site"></a>
</p>

<p align="center">
  <b><a href="https://alexandre.machado.cc/audio-blackbox">alexandre.machado.cc/audio-blackbox</a></b>
</p>

---

**Audio Blackbox** is a continuous memory audio recorder for Android that functions like a flight recorder or dashcam for sound: it keeps a rolling window of recent audio (**5 to 45 minutes**, in 5-minute steps) in device RAM and writes to storage **only when you explicitly ask it to**.

Nothing touches your disk or leaves your phone until you press save. You can capture important conversations, ideas, or unexpected events *after* they have already happened.

---

## 📸 Screenshots

| Dashboard (Live VU Meter & Buffer RAM) | Saved Recordings Gallery | Audio Engine & Privacy Specs |
| :---: | :---: | :---: |
| <img src="distribution/metadata/android/en-US/images/phoneScreenshots/1_dashboard.png" width="260" alt="Dashboard Screen"> | <img src="distribution/metadata/android/en-US/images/phoneScreenshots/2_gallery.png" width="260" alt="Gallery Screen"> | <img src="distribution/metadata/android/en-US/images/phoneScreenshots/3_settings.png" width="260" alt="Settings Screen"> |

---

## ✨ Key Features

- 🎯 **Two Primary Capture Modes**:
  - **Save Recent Past (Lookback)**: Instantly snapshot everything currently buffered from the memory ring buffer into an AAC (`.m4a`) or lossless WAV file. One action, always the whole buffer -- the old 5/15/30 selector was retired in #121 because it promised windows the buffer might not hold.
  - **Continuous Live Recording**: Start a forward live recording that automatically preserves the preceding buffer timeline so nothing is lost.
- 📊 **Real-time Live VU Meter**: 18-capsule reactive microphone input level indicator designed with pure stock Material 3 components.
- 💾 **Circular Buffer RAM Visualizer**: Live retention progress bar showing exact buffer saturation, duration, and memory utilization (at standard 16 kHz 16-bit PCM, 30 minutes uses just ~55 MB of RAM).
- 🛡️ **100% Local, Zero-Network Privacy**:
  - **Zero Network Permissions**: The `android.permission.INTERNET` permission is completely absent from the merged release manifest.
  - **Zero Telemetry / Crash SDKs**: No Firebase, no analytics, no third-party trackers.
  - **Local Persistence Only**: Files are saved directly to your device's standard `Recordings/Blackbox/` folder.
- ⚡ **Seamless Interruption Handling**: Pauses gracefully during phone calls or third-party audio focus grabs, preserving silence gaps to ensure exported timestamps remain perfectly synced.
- 🔋 **Robust Background Survival**: Dedicated foreground capture service with persistent notifications and guided manufacturer battery-killer bypass.
- 🎵 **Integrated Audio Player**: Playback, seek, manage, and share your recordings directly inside the app with native Android sharesheets.
- 🔘 **Quick Settings Tile**: Start and stop capture with a single tap from Android's Quick Settings panel.

---

## 🔬 Audio Engine Specifications

| Parameter | Specification | Details |
| :--- | :--- | :--- |
| **Internal Buffer** | 16-bit Linear PCM | Pre-allocated circular ring buffer in RAM |
| **Sample Rate** | 16,000 Hz (Standard) / 44,100 Hz | Optimized for voice clarity and low memory footprint |
| **Channel Config** | Mono (1 Channel) | Maximizes retention duration per megabyte |
| **Export Formats** | AAC LC (`.m4a`) & Lossless WAV | Hardware-accelerated `MediaCodec` streaming encoder |
| **Storage Destination** | `Recordings/Blackbox/` | Standard Android `MediaStore` collection |
| **Thread Architecture** | Dedicated Single-Writer | Capture thread performs zero disk I/O and zero IPC |

---

## 📲 Download & Beta Testing

Audio Blackbox is available in open beta via Google Play:

1. **Join the Tester Group** $\rightarrow$ [Google Groups: ccmachadoaudioblackbox](https://groups.google.com/g/ccmachadoaudioblackbox)
2. **Accept the Web Test Invitation** $\rightarrow$ [Play Store Testing Portal](https://play.google.com/apps/testing/cc.machado.audioblackbox)
3. **Install on Device** $\rightarrow$ [Google Play Store](https://play.google.com/store/apps/details?id=cc.machado.audioblackbox)

---

## 🛠️ Developer & Build Guide

### Stack Binding
- **Language**: Kotlin 2.1+
- **UI Framework**: Jetpack Compose with Material 3 (1.4.0 stable)
- **Minimum SDK**: Android 10 (API 29)
- **Target SDK**: Android 16 (API 36 / 37 compile)
- **Build System**: Gradle with Kotlin DSL (`build.gradle.kts`)

### Local Build & Testing

Prerequisites: JDK 17 and Android SDK 37.

```bash
# Clone the repository
git clone https://github.com/alexandre-machado/audio-blackbox.git
cd audio-blackbox

# Run JVM Unit Tests & Localization Lints (Primary Pre-Merge Gate)
./gradlew testDebugUnitTest lintDebug

# Assemble Debug APK
./gradlew assembleDebug
```

For testing principles, non-vacuous mutation rules, and architecture invariants, see [`AGENTS.md`](AGENTS.md).

---

## ⚖️ Legal & Recording Regulations

Recording conversations may require one-party or all-party consent depending on your jurisdiction. Audio Blackbox is a tool; you are solely responsible for ensuring your use complies with local laws and privacy regulations.

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
