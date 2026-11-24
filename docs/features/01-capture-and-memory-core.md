# Module 1 — Capture & Memory Core

*The foundational data structure and audio engine. Runs entirely under the hood.*

Tracked in [#2](https://github.com/alexandre-machado/audio-blackbox/issues/2).

## Ring Buffer Implementation
`RingBuffer.kt` pre-allocates 16-bit PCM memory in 1 MiB chunks (`CHUNK_BYTES = 1_048_576`) and manages continuous read/write cursors that wrap around and overwrite the oldest audio once the window is full.

All chunks for the configured window are allocated up front when the buffer is constructed, so the capture thread never allocates on the hot path. When the user changes the retention window or quality preset in Settings while recording (`#223`, `#272`), `RingBuffer.resize` checks `DeviceMemoryBudget` against the net growth (plus two chunks of slack) and copies the retained audio into the new layout one 1 MiB chunk at a time, releasing each old chunk as it goes so peak memory stays near `max(old, new) + 1 chunk` instead of `old + new` (`#277`). If the device does not have enough headroom for the resize, `RingBuffer.resize` returns `ResizeOutcome.Refused`, leaves the existing buffer untouched, and surfaces a visible "Setting not applied" notice in the UI.

## Raw Audio Capture (PCM)
`AudioCaptureEngine.kt` uses Android's `AudioRecord` API to read 16-bit PCM microphone frames on a dedicated single-writer capture thread with a reusable scratch array, performing zero object allocations, zero disk I/O, and zero IPC on the capture loop.

## Quality Presets & Device-Sized Retention Window
`AudioConfig.kt` and `QualityPreset.kt` define three selectable audio quality presets:

| Preset | Format | Byte rate | 30 minutes in RAM |
| --- | --- | --- | --- |
| `Voice` (default) | 16,000 Hz · mono · 16-bit PCM | ~31.3 KB/s (32,000 B/s) | ~54.9 MB (~55 MB) |
| `Balanced` | 32,000 Hz · mono · 16-bit PCM | ~62.5 KB/s (64,000 B/s) | ~109.9 MB (~110 MB) |
| `High fidelity` | 44,100 Hz · stereo · 16-bit PCM | ~172.3 KB/s (176,400 B/s) | ~302.8 MB (~303 MB) |

The retention window is at least 5 minutes and is adjusted in 5-minute steps, with no fixed upper bound (`#298`). `DeviceMemoryBudget.kt` computes the maximum safe window on the device for the selected preset: it caps total heap use at 85% of `Runtime.getRuntime().maxMemory()`, subtracts the app's current heap use, also caps against 85% of available system memory (`ActivityManager.MemoryInfo.availMem`), and divides the remaining byte budget by the 1.15x export peak-to-buffer ratio, floored to 5-minute steps and never below 5 minutes.
