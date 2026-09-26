# Audio Blackbox

<p align="center">
  <img src="docs/design/store/feature_graphic_1024x500.png" alt="Audio Blackbox Banner" width="800">
</p>

<p align="center">
  <a href="https://github.com/alexandre-machado/audio-blackbox/actions/workflows/ci.yml"><img src="https://github.com/alexandre-machado/audio-blackbox/actions/workflows/ci.yml/badge.svg" alt="CI Status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-3DDC84.svg?logo=android&logoColor=white" alt="Android Platform"></a>
  <a href="https://alexandre.machado.cc/audio-blackbox/release/privacy-policy"><img src="https://img.shields.io/badge/Privacy-100%25%20Offline%20%7C%20Zero%20Network-success.svg" alt="Zero Network Permission"></a>
  <a href="https://m3.material.io"><img src="https://img.shields.io/badge/Design-Material%203-6750A4.svg" alt="Material 3"></a>
  <a href="https://play.google.com/store/apps/details?id=cc.machado.audioblackbox"><img src="https://img.shields.io/badge/Google_Play-Get_it_on_Google_Play-01875F.svg?logo=googleplay&logoColor=white" alt="Get it on Google Play"></a>
  <a href="https://alexandre.machado.cc/audio-blackbox/"><img src="https://img.shields.io/badge/Project_Site-alexandre.machado.cc-FF5722.svg" alt="Project Site"></a>
</p>

<p align="center">
  <b><a href="https://alexandre.machado.cc/audio-blackbox/">alexandre.machado.cc/audio-blackbox</a></b>
</p>

---

**Audio Blackbox** is a continuous memory audio recorder for Android that functions like a flight recorder or dashcam for sound: it keeps a rolling window of recent audio in device RAM (**at least 5 minutes, in 5-minute steps, with no fixed upper bound** -- the ceiling is sized automatically per device and per quality preset from how much memory it can safely hold) and writes to storage **only when you explicitly ask it to**.

Nothing touches your disk until you press save, and the app has no internet permission, so it never uploads anything; saved files go to your phone's `Recordings/Blackbox` folder, where you control them. It is made for the moments you only know mattered *after* they happened: the riff you just played, an idea you said out loud, what was just agreed in a meeting you were part of, or a family moment.

---

## 📸 Screenshots

| Dashboard (Live VU Meter & Buffer RAM) | Saved Recordings Gallery | Audio Engine & Privacy Specs |
| :---: | :---: | :---: |
| <img src="distribution/metadata/android/en-US/images/phoneScreenshots/1_dashboard.png" width="260" alt="Dashboard Screen"> | <img src="distribution/metadata/android/en-US/images/phoneScreenshots/2_gallery.png" width="260" alt="Gallery Screen"> | <img src="distribution/metadata/android/en-US/images/phoneScreenshots/3_settings.png" width="260" alt="Settings Screen"> |

---

## ✨ Key Features

- 🎯 **Two Primary Capture Modes**:
  - **Save Recent Past (Lookback)**: Write the buffered audio since your last save (the whole buffer the first time) from the memory ring buffer into an AAC (`.m4a`) file. One action, no window to pick -- the old fixed-window selector was retired in #121 because it promised windows the buffer might not hold. Since #410 a successful save advances the buffer's export floor, so the next save starts where the previous file ended and consecutive saves never overlap.
  - **Continuous Live Recording**: Start a forward live recording that automatically preserves the preceding buffer timeline so nothing is lost.
- 🎚️ **Selectable Audio Quality**: Three presets in Settings -- Voice (16 kHz mono), Balanced (32 kHz mono), and High Fidelity (44.1 kHz stereo) -- each trading sample rate/channels for its own device-derived retention ceiling.
- 📊 **Real-time Live VU Meter**: 20-capsule reactive microphone input level indicator built on Material 3 components, styled with the app's avionics/cockpit brand theme (see `AGENTS.md` §5).
- 💾 **Circular Buffer RAM Visualizer**: Live retention progress bar showing exact buffer saturation, duration, and memory utilization (at standard 16 kHz 16-bit PCM, 30 minutes uses just ~55 MB of RAM).
- 🏠 **Home-Screen Widget**: Start or stop capture straight from the launcher, no need to open the app first.
- 🛡️ **100% Local, Zero-Network Privacy**:
  - **Zero Network Permissions**: The `android.permission.INTERNET` permission is completely absent from the merged release manifest.
  - **Zero Telemetry / Crash SDKs**: No Firebase, no analytics, no third-party trackers.
  - **Local Persistence Only**: Files are saved directly to your device's standard music/recordings folder (`Recordings/Blackbox/` on Android 12+, `Music/Blackbox/` on Android 10-11).
- ⚡ **Seamless Interruption Handling**: Pauses gracefully during phone calls or third-party audio focus grabs, preserving silence gaps to ensure exported timestamps remain perfectly synced.
- 🔋 **Robust Background Survival**: Dedicated foreground capture service with persistent notifications and guided manufacturer battery-killer bypass.
- 🎵 **Integrated Audio Player**: Playback, seek, manage, and share your recordings directly inside the app with native Android sharesheets.

---

## 🔬 Audio Engine Specifications

| Parameter | Specification | Details |
| :--- | :--- | :--- |
| **Internal Buffer** | 16-bit Linear PCM | Pre-allocated circular ring buffer in RAM |
| **Sample Rate** | 16,000 Hz (Voice) / 32,000 Hz (Balanced) / 44,100 Hz (High Fidelity) | Optimized for voice clarity and low memory footprint |
| **Channel Config** | Mono (Voice / Balanced) or Stereo (High Fidelity) | Maximizes retention duration per megabyte |
| **Export Formats** | AAC LC (`.m4a`) | Hardware-accelerated `MediaCodec` streaming encoder |
| **Storage Destination** | `Recordings/Blackbox/` (Android 12+) or `Music/Blackbox/` (Android 10-11) | Standard Android `MediaStore` collection |
| **Thread Architecture** | Dedicated Single-Writer | Capture thread performs zero disk I/O and zero IPC |

---

## ⚡ Hardware Efficiency & Power Benchmarks (Samsung Galaxy S25)

Measured live on physical **Samsung Galaxy S25 (`SM-S931B`, Android 16 / API 36)** during continuous recording (16 kHz Mono, Voice Preset):

| Metric / Resource | Background Capture (Screen Off) | Active Foreground (Dashboard UI) | Operational Invariant |
| :--- | :--- | :--- | :--- |
| **Battery Drain Rate** | **~1.0% – 1.5% / hour** (~45–60 mA) | ~7.0% – 9.0% / hour (display-bound) | Over **65+ hours** continuous recording autonomy |
| **Volatile Audio Buffer RAM** | **54.9 MB** (30 min retention window) | **54.9 MB** (30 min retention window) | Pre-allocated up front; resized only on a settings change, behind a memory-budget check (#223, #272) |
| **JVM Heap Footprint** | **~7.3 MB resident** (256 MB max budget) | **~16.3 MB resident** (256 MB max budget) | Minimal GC pressure; ring buffer writer allocates zero objects |
| **Storage Disk I/O** | **0 KB/s** (Zero disk writes) | **0 KB/s** (Zero disk writes) | Pure volatile RAM; zero flash memory wear |
| **CPU Utilization** | **< 1.0% CPU** | ~3.5% – 4.5% CPU (60fps VU meter) | Blocking native `AudioRecord` thread with zero busy-waiting |

---


## 📚 Engineering Studies: Device-Derived Memory Limits

The buffer's ceiling is computed from the device's own memory numbers rather than reacting to Android's memory-pressure callbacks (`onTrimMemory`), and the buffer is never grown behind the user's back. This is a deliberate engineering decision:

1. **Memory Warnings are Blind to Process Limits**: The OS broadcasts memory pressure warnings when the *entire system* is low on RAM. However, every Android application operates under a strict per-process limit (the Dalvik Heap Limit). If the app suddenly exceeds its own quota, the runtime immediately throws an `OutOfMemoryError` and crashes the app, without ever broadcasting an `onTrimMemory` warning.
2. **A Per-Device, Per-Preset Ceiling**: `DeviceMemoryBudget` (#298) caps total heap use at **85%** of `Runtime.getRuntime().maxMemory()`, subtracts the app's live heap footprint, also caps against 85% of the system's available memory, and divides what is left by a measured **1.15x** export peak-to-buffer ratio. The result, floored to 5-minute steps and never below 5 minutes, is the largest window the stepper offers for the chosen quality preset. It is recomputed on every read, so a heavier release or a tighter device clamps a stored value down (with a visible notice) instead of crashing.
3. **Chunked Store, Resized Chunk by Chunk**: Growing one flat array means allocating the new, larger array while the old one is still alive, so stretching a 100 MB buffer to 150 MB would briefly need 250 MB. `RingBuffer` avoids that by keeping its audio in 1 MiB chunks, all allocated when the buffer is built. When you change the window or preset during capture, `RingBuffer.resize` (#223) keeps the audio already buffered and copies it chunk by chunk, dropping each old chunk as soon as it has been copied out, so the peak is about the larger of the two sizes plus a chunk, not old plus new (#277). Before allocating anything it still checks an injected `MemoryBudget` against the real net growth (new minus old, plus two chunks of slack) and refuses (`ResizeOutcome.Refused`, nothing allocated, the current buffer untouched) when that would not fit (#272). A refusal surfaces as a visible "Setting not applied" error, never a crash and never a silently changed setting.
4. **No Allocation on the Capture Path**: Chunks are allocated up front (at construction, or inside an explicit resize), never on the capture hot path. Allocating while capturing would wake the Garbage Collector, causing unpredictable thread pauses that lead to hardware buffer overflows and permanently dropped audio frames.

The result: the capture loop itself never allocates, a reallocation only ever happens on an explicit settings change and only when the memory budget allows it, and the save path keeps the headroom it needs at the exact moment the user presses "Save".

## 📲 Download

Audio Blackbox is free on Google Play: **[Get it on Google Play](https://play.google.com/store/apps/details?id=cc.machado.audioblackbox)**. It runs on Android 10 (API 29) and newer.

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

Use Audio Blackbox for your own conversations and ideas. Recording conversations may require one-party or all-party consent depending on your jurisdiction, so check your local recording laws before you record. Audio Blackbox is a tool; you are solely responsible for ensuring your use complies with local laws and privacy regulations.

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
