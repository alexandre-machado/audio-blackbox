# Module 2 — Android Integration & Background Survival

*Mandatory system-level implementations to prevent the Android OS from killing the
application in the background.*

Tracked in [#3](https://github.com/alexandre-machado/audio-blackbox/issues/3)
(service, notification, interruptions) and
[#4](https://github.com/alexandre-machado/audio-blackbox/issues/4)
(permissions, battery optimization).

## Microphone Foreground Service
An ongoing `Service` declared with the mandatory `foregroundServiceType="microphone"`
flag, tied to the app's lifecycle. It owns the capture engine and the ring buffer, so the
buffer survives Activity destruction. This is the single highest-risk failure mode of the
product: without it, "the last minutes" silently becomes "the last few seconds".

## Persistent Notification & Home-Screen Widget
The required status bar notification indicating active recording runs on a dedicated
low-importance channel and shows the live capture state and buffered duration.

Quick action buttons on the notification let the user trigger `Save` (writes the buffered audio since the last save), `Live` (starts or stops a continuous forward recording that includes the buffered past), and `Stop` (stops the recording service) without opening the app. A one-tap home-screen widget (`#275`) also lets the user start or stop recording directly from the launcher.

## Audio Interruption Handling
`AudioManager.AudioRecordingCallback` gracefully pauses buffer writing during incoming
phone calls and resumes automatically afterward.

The pause window's **wall-clock duration** is recorded, not just a flag — the export gap
handler (Module 3) needs that duration to inject the right amount of silence. These two
features do not work independently.

## Runtime Permission Manager
The UI flow requesting `RECORD_AUDIO` and, on API 33+, `POST_NOTIFICATIONS`, with a
rationale shown before the system dialog and a recovery path out of permanent denial.

## Battery Optimization Bypass
A guided flow redirecting the user to `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to
prevent aggressive OEM task killers (Samsung, Xiaomi) from shutting the app down. Skippable
— the app stays usable, with a visible warning, for a user who declines.
