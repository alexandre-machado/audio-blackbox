# Audio Blackbox

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

A continuous audio recorder for Android that works like a dashcam: it always holds the
**last 30 minutes** of audio in RAM, and writes them to a file only when you ask it to.

Nothing is persisted until you press save. The engine keeps a rolling window in memory and
continuously overwrites the oldest audio, so you can capture something *after* it already
happened.

## Beta testing

The app is in open beta on Google Play. Testing is gated behind a Google Group: Play only
serves the beta build to accounts that are members, so **join the group first** — installing
before that gets you the "not available for your account" page.

1. **Join the tester group** — <https://groups.google.com/g/ccmachadoaudioblackbox>
2. **Accept the test** — <https://play.google.com/apps/testing/cc.machado.audioblackbox>
3. **Install** — <https://play.google.com/store/apps/details?id=cc.machado.audioblackbox>

Membership can take a few minutes to propagate to Play. If step 3 still says the app is
unavailable, give it a while before assuming something is broken.

Use the same Google account throughout. The account that joins the group must be the one
signed in to the Play Store on the device.

### What is most useful to report

The app records; the risk is that it records *wrongly* and you only find out later. Reports
about the following are worth more than general impressions:

- **Audio that is missing, truncated, or silent** — especially after a phone call, an alarm,
  another app grabbing the microphone, or the screen being off for a long stretch.
- **The service dying in the background.** Which manufacturer and Android version, and what
  the device was doing when the notification disappeared. OEM battery managers are the usual
  cause and they differ wildly between brands.
- **A saved file whose length or size looks wrong** in the gallery.
- **Anything the app tells you that you could not act on** — an error message that does not
  say what to do next is a bug in its own right.

Please include your device model and Android version. Open an issue here, or reply on the
group thread if you would rather not have a GitHub account.

### What the app does not do

Worth knowing before you install something that listens continuously:

- It has **no network permission at all**. Not "does not send data" as a promise — the
  capability is absent from the manifest, so there is no code path that could.
- There is **no analytics or crash reporting SDK**. That is deliberate ([#119]), and it is why
  the bug reports above matter: the app cannot tell us anything you do not.
- Audio stays in RAM until you press save, and saved files go to your own storage. Nothing is
  uploaded anywhere.

Full details in the [privacy policy](docs/release/privacy-policy.md).

[#119]: https://github.com/alexandre-machado/audio-blackbox/issues/119

## Features

- **Rolling in-memory buffer** — a pre-allocated ring buffer holds the most recent audio and
  overwrites the oldest, with configurable quality (16 kHz mono through 44.1 kHz stereo) to
  trade audio fidelity against RAM.
- **Survives the background** — a microphone foreground service with a persistent
  notification keeps capturing with the app closed and the screen off, including a guided
  bypass of aggressive OEM battery killers.
- **Handles interruptions** — recording pauses during phone calls and resumes automatically,
  and the gap is preserved as real silence so the exported timeline stays accurate.
- **Save the past** — one action exports the buffered audio as an AAC `.m4a` into
  `Recordings/Blackbox/` (falling back to `Music/Blackbox/` on API 29-30, where the platform's
  `Recordings/` root doesn't exist), while recording continues uninterrupted.
- **Keep recording forward** — start a continuous recording to a new file while the circular
  buffer keeps rolling behind it.
- **Play and share** — an in-app gallery lists your saved recordings with an embedded player
  and a share action to send them anywhere.

## Status

Open beta on Google Play. See [Beta testing](#beta-testing) above to get in.

Work is tracked on the [Audio Blackbox board](https://github.com/users/alexandre-machado/projects/2).

## Stack

Kotlin · Jetpack Compose · Material 3 (stable 1.4.0) · Gradle Kotlin DSL
`minSdk 29` · `targetSdk 36`

## Documentation

The full feature specification lives in [`docs/`](docs/README.md), one document per module.

For developer and agent conventions, operational invariants, and testing rules, see [`AGENTS.md`](AGENTS.md).

For running the app on a physical device, see
[Running on a physical device from WSL](docs/development/running-on-device.md).

## Recording laws

Recording conversations may require the consent of the participants depending on your
jurisdiction. You are responsible for how you use this app.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE) — see the [LICENSE](LICENSE) file for details.

