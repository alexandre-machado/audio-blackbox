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
[`docs/design/model.html`](../design/model.html); [`ui/theme/Color.kt`](../../app/src/main/java/cc/machado/audioblackbox/ui/theme/Color.kt)
is its Compose implementation, not the other way around, and it does not yet match
the spec on every token (see `AGENTS.md` §5 for the known divergences). See
`AGENTS.md` §5 for the semantic colour-role rules (green = OK/recording, amber =
paused/caution, red = error/warning, orange = brand CTA) and the known dynamic-color
contrast risk.

## Main Dashboard
A clear "Iniciar/Parar motor" toggle plus a visual indicator (animation and timer) showing
that the buffer is actively rolling, and how much audio is currently held in memory. Paused
(phone call) and error states are visually distinct from recording and idle.

## Main Action Button
The core "salvar o passado" button, letting the user choose the interval — the last 5, 15 or
30 minutes. Windows longer than what is currently buffered are disabled or clearly labeled,
never silently producing a shorter file than requested.

## Local Gallery
A list view of previously saved audio files, sorted by date and time, sourced from MediaStore
so it cannot drift from what is actually on disk.

## Embedded Player
In-app playback of saved files, with play/pause, a scrub bar and elapsed/total time. Playback
requests audio focus and does not interfere with a recording still running in the background.

## Share Action
Integration with the Android Share `Intent` to send an exported file to messaging apps or
email, with the correct `audio/wav` MIME type and read permission granted to the receiver.
