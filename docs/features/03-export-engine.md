# Module 3 — Export Engine (RAM to Disk)

*The logic triggered when the user hits the "Save" button.*

Tracked in [#5](https://github.com/alexandre-machado/audio-blackbox/issues/5).

## Safe Buffer Reading
Extract the byte array representing the last *X* minutes **without dropping incoming write
frames** — capture continues uninterrupted while the file is written, and the copy-out does
not block the capture thread for a perceptible time.

## PCM to File Conversion
Encapsulate the raw memory bytes into a playable file format.

*MVP approach:* prepend a standard 44-byte `.WAV` RIFF header to the PCM data, with sample
rate, channel count, bits-per-sample, byte rate, block align and chunk sizes all derived
from the live `AudioConfig` — never hardcoded.

`.M4A`/AAC compression via `MediaCodec` is explicitly deferred to a future iteration.

## Gap/Silence Handler
Chronological gaps caused by system interruptions (like phone calls) are filled by injecting
silence (zero-bytes) of the exact wall-clock duration at the correct offset, so the exported
timeline matches real elapsed time. An export spanning an interruption has the full requested
duration, not a shortened one.

## Destination
Files are written to **MediaStore** under `Music/Recordings`, using `IS_PENDING` during the
write so no half-written file is ever visible. Naming scheme:
`blackbox_<date>_<time>_<window>.wav`.
