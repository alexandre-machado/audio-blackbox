# Module 1 — Capture & Memory Core

*The foundational data structure and audio engine. Runs entirely under the hood.*

Tracked in [#2](https://github.com/alexandre-machado/audio-blackbox/issues/2).

## Ring Buffer Implementation
Create the core Kotlin data structure to pre-allocate RAM space and manage continuous
read/write pointers to overwrite older data efficiently.

Sizing depends entirely on the audio configuration:

| Configuration | Byte rate | 30 minutes |
| --- | --- | --- |
| 16 kHz · mono · 16-bit PCM | 32 KB/s | ~57.6 MB |
| 44.1 kHz · stereo · 16-bit PCM | 176.4 KB/s | ~317 MB |

The buffer is allocated once at construction and never grows; writes wrap around and
overwrite the oldest bytes rather than allocating.

## Raw Audio Capture (PCM)
Use Android's `AudioRecord` API to capture pure microphone byte streams and inject them
into the ring buffer in real time, on a dedicated capture thread with a reusable scratch
array — no allocation on the hot path.

## Parameterized Configuration
The audio engine initializes from an `AudioConfig` (sample rate, channel count, encoding,
buffer duration) so quality vs. memory consumption can be tuned without code changes.
Default: 16 kHz · mono · 16-bit · 30 minutes.
