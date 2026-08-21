# Audio Blackbox

A continuous audio recorder for Android that works like a dashcam: it always holds the
**last 30 minutes** of audio in RAM, and writes them to a file only when you ask it to.

Nothing is persisted until you press save. The engine keeps a rolling window in memory and
continuously overwrites the oldest audio, so you can capture something *after* it already
happened.

## Features

- **Rolling in-memory buffer** — a pre-allocated ring buffer holds the most recent audio and
  overwrites the oldest, with configurable quality (16 kHz mono through 44.1 kHz stereo) to
  trade audio fidelity against RAM.
- **Survives the background** — a microphone foreground service with a persistent
  notification keeps capturing with the app closed and the screen off, including a guided
  bypass of aggressive OEM battery killers.
- **Handles interruptions** — recording pauses during phone calls and resumes automatically,
  and the gap is preserved as real silence so the exported timeline stays accurate.
- **Save the past** — one action exports the last 5, 15 or 30 minutes as an AAC `.m4a` into
  `Recordings/Blackbox/` (falling back to `Music/Blackbox/` on API 29-30, where the platform's
  `Recordings/` root doesn't exist), while recording continues uninterrupted.
- **Play and share** — an in-app gallery lists your saved recordings with an embedded player
  and a share action to send them anywhere.

## Status

Early development — the MVP is being built module by module. Nothing here is released yet.

Work is tracked on the [Audio Blackbox board](https://github.com/users/alexandre-machado/projects/2).

## Stack

Kotlin · Jetpack Compose · Material 3 (stable 1.4.0) · Gradle Kotlin DSL
`minSdk 29` · `targetSdk 36`

## Documentation

The full feature specification lives in [`docs/`](docs/README.md), one document per module.

For running the app on a physical device, see
[Running on a physical device from WSL](docs/development/running-on-device.md).

## Recording laws

Recording conversations may require the consent of the participants depending on your
jurisdiction. You are responsible for how you use this app.
