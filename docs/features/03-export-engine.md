# Module 3 — Export Engine (RAM to Disk)

*The logic triggered when the user hits the "Save" button.*

Tracked in [#5](https://github.com/alexandre-machado/audio-blackbox/issues/5).

## Safe Buffer Reading
Extract the byte array representing the last *X* minutes **without dropping incoming write
frames** — capture continues uninterrupted while the file is written, and the copy-out does
not block the capture thread for a perceptible time.

## PCM to File Conversion
Encapsulate the raw memory bytes into a playable file format, via a pluggable `PayloadEncoder`
(issue #32).

*Default (production):* AAC-LC in an MP4 container (`.m4a`, `audio/mp4`), via `MediaCodec` +
`MediaMuxer`, ~64 kbps mono — see issue #32 for the device evidence that motivated this (every
other recorder on the target device already writes a compressed format; WAV opened in none of
them). Encoding happens off the capture thread and never blocks it; a failed encode deletes the
pending MediaStore row exactly like a failed write always has.

*Also available, not wired into production:* the original 44-byte `.WAV` RIFF header
(`WavWriter`/`WavPayloadEncoder`), with every field derived from the live `AudioConfig` — kept
for a future user-facing "lossless" setting (`.m4a` default, `.wav` opt-in), since a black-box
recording used as evidence has a real argument for zero codec artefacts. Not the default: a
lossless file nothing plays is not usable evidence either — see issue #32.

## Gap/Silence Handler
Chronological gaps caused by system interruptions (like phone calls) are filled by injecting
silence (zero-bytes) of the exact wall-clock duration at the correct offset, so the exported
timeline matches real elapsed time. An export spanning an interruption has the full requested
duration, not a shortened one. This happens once, on raw PCM, *before* encoding — neither encoder
sees anything but a single already-correct timeline.

## Destination
Files are written to **MediaStore** under a per-app subfolder matching the platform's own
recorders (issue #33): `Recordings/Blackbox/` on API 31+, falling back to `Music/Blackbox/` on
API 29-30 (the top-level `Recordings/` root does not exist below API 31, and `minSdk` is 29
deliberately — see issue #3). Decided from the running OS (`Build.VERSION.SDK_INT`), never
`targetSdk`. `IS_PENDING` is used during the write so no half-written file is ever visible. Files
already sitting in the app's pre-issue-#33 `Music/Recordings/` location are not migrated or
deleted. Naming scheme: `blackbox_<date>_<time>_<window>.<extension>` (`.m4a` by default, `.wav`
if `WavPayloadEncoder` is used).
