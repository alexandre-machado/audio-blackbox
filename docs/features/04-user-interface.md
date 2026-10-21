# Module 4 — User Interface

*The user-facing screens to operate the dashcam.*

Tracked in [#6](https://github.com/alexandre-machado/audio-blackbox/issues/6)
(dashboard) and [#7](https://github.com/alexandre-machado/audio-blackbox/issues/7)
(gallery, player, share).

The design system is Material 3 (stable 1.4.0 line, no alpha-only Material 3
Expressive components) themed with a US aviation/cockpit-avionics brand palette
(`FlightOrange`, annunciator green/amber/red, cockpit dark base), per
[#220](https://github.com/alexandre-machado/audio-blackbox/issues/220). This
supersedes the original stock-Material-3-only decision in
[#9](https://github.com/alexandre-machado/audio-blackbox/issues/9), which PR
[#186](https://github.com/alexandre-machado/audio-blackbox/pull/186) shipped without
updating this document. The design-system spec of record is the living prototype at
[`docs/design/index.html`](../design/index.html); [`ui/theme/Color.kt`](../../app/src/main/java/cc/machado/audioblackbox/ui/theme/Color.kt)
is its Compose implementation, not the other way around, and it does not yet match
the spec on every token (see `AGENTS.md` §5 for the known divergences). See `AGENTS.md` §5 for the semantic colour-role rules (green = OK/recording, amber =
paused/caution, red = error/warning, orange = brand CTA). Per
[#225](https://github.com/alexandre-machado/audio-blackbox/issues/225), the app is permanently dark on
the fixed cockpit ground (`CockpitSlate` / `CockpitPanel`), with dynamic wallpaper color disabled.

## Main Dashboard
The Cockpit dashboard shows the continuous-recording switch, the "REMOVE BEFORE FLIGHT" standby tag
(which can be dragged off or activated to arm the recorder, `#284`), a live 20-capsule VU meter in dBFS,
and the circular buffer retention bar showing how much audio is currently held in RAM. Paused
(microphone in use, such as during a phone call) and error states are visually and semantically distinct
from recording and standby.

## Main Action Buttons (`Save` & `Live`)
Inside the main engine card (`#335`), two avionics panel buttons control exports:
- **`Save` (Lookback):** writes the buffered audio since the last save (or the full buffer on the first save) to a local `.m4a` file and advances the export floor (`#121`, `#410`) so consecutive saves never overlap. Disabled with a clear "No audio yet" caption when nothing new is buffered.
- **`Live` (Continuous forward recording):** opens a new `.m4a` file that preserves the buffered past and keeps recording live until the user taps `Stop`.

## Local Gallery
A list view of previously saved audio files (`blackbox_*`), sorted by date and time, sourced from
MediaStore (`MediaStoreSink.queryRecordings`) so it cannot drift from what is actually on disk.

## Embedded Player
In-app playback of saved files, with play/pause, a scrub bar and elapsed/total time. Playback
requests audio focus and does not interfere with a recording still running in the background.

## Share Action
Integration with the Android Share `Intent` to send an exported file to messaging apps or
email, using the recording row's MIME type (`audio/mp4` for `.m4a` AAC exports) and granting temporary read permission to the receiver.
