# Play Console — Foreground service (microphone) justification (draft)

**Draft for the owner to paste into the Play Console's foreground service
declaration form.** Not legal advice, not counsel-reviewed.

*Last drafted: 2026-08-25, against commit `24cc125`.*

## Declared type

`FOREGROUND_SERVICE_TYPE_MICROPHONE`, declared on `RecorderService`
(*verified*: `app/src/main/AndroidManifest.xml:57-60`).

## Use case

Audio Blackbox is a "black box" for audio: while the user has turned
recording on, it continuously records microphone audio into a short rolling
buffer, so that when something worth keeping just happened, the user can
save the last N minutes retroactively instead of having had to hit "record"
in advance. That retroactive-save function is the entire point of the app,
and it is only possible if capture keeps running continuously, including
while the app is not the foreground activity (screen off, another app open,
etc.) — which is exactly the scenario `FOREGROUND_SERVICE_TYPE_MICROPHONE`
exists for.

## Why this is user-initiated

- Recording is controlled by an explicit switch on the app's main dashboard
  (`EngineToggle`, *verified*:
  `app/src/main/java/cc/machado/audioblackbox/ui/dashboard/DashboardScreen.kt:100`
  wires the switch to `onToggleEngine`; the `Switch` composable itself is at
  `DashboardScreen.kt:265`). The service is never started by any component
  other than this app: `RecorderService` is declared
  `android:exported="false"` and is only reached through explicit `Intent`s
  built by the service's own `startIntent()`/`stopIntent()`/`saveIntent()`
  helpers (*verified*: `AndroidManifest.xml:52-60` inline comment, and
  `RecorderService.kt:614` for `saveIntent`).
- The one automatic-looking path — resuming after a device reboot — does
  **not** silently restart the microphone. On `ACTION_BOOT_COMPLETED` /
  `ACTION_MY_PACKAGE_REPLACED`, `BootReceiver.handleBootOrReplaced` only
  posts a notification prompt if recording had been desired before the
  reboot; it does not itself start `RecorderService` (*verified*:
  `app/src/main/java/cc/machado/audioblackbox/service/BootReceiver.kt:38-45`).
  The service is only started once the user taps that notification's Resume
  action, which dispatches `ACTION_RESUME` back into the same receiver and
  calls `ContextCompat.startForegroundService(...)` from that explicit tap
  (*verified*: `BootReceiver.kt:47-58, 98-100`). The class's own doc comment
  states this is deliberate: starting a microphone foreground service
  directly from a boot broadcast throws
  `ForegroundServiceStartNotAllowedException` on Android 14+, so the prompt
  is both a legibility choice and a platform requirement (*verified*:
  `BootReceiver.kt:17-25`).

## Why this is user-visible

- **Persistent notification.** While the service is in the foreground, it
  shows an ongoing (non-dismissable-by-swipe) notification via
  `NotificationCompat.Builder(...).setOngoing(true)` (*verified*:
  `app/src/main/java/cc/machado/audioblackbox/service/RecorderNotification.kt:126-130`).
  The notification's content text reflects live state — buffered duration,
  and any in-progress forward-recording/export status
  (*verified*: `RecorderNotification.kt:100-124`) — and includes a direct
  Save action (*verified*: `RecorderNotification.kt:133-137`,
  `addAction(...)` wired to a save `Intent`). `startForeground()` is called
  unconditionally and immediately on service start specifically so this
  notification is never absent while recording is active (*verified*:
  `RecorderService.kt:242`, with the surrounding comment at
  `RecorderService.kt:235-242` citing the OS's 5-second
  `startForegroundService()`→`startForeground()` deadline as the reason it
  is unconditional).
- **VPN-style persistent switch, not a fire-and-forget button.** The
  dashboard's primary control is a Material 3 `Switch` whose position always
  reflects the real service state — including after the app process is
  killed and reopened while the service is still alive — rather than a
  stateless button (*verified*: `DashboardScreen.kt:232-265`, `EngineToggle`
  composable; issue #46's design brief, which this implements, is explicit
  that the point of the switch metaphor is to make an always-on background
  process legible as "you are currently in this mode," the same way a VPN
  toggle communicates that its connection is active). This is the design
  answer to why the user always knows the microphone-using service is
  running: the on/off/paused/error states are always visible from the app's
  home screen, not just from the notification shade.

## Why this cannot be done without a foreground service

Recording must continue while the screen is off or another app is in the
foreground, because the app's core value (retroactive save of the last N
minutes) requires the buffer to keep filling continuously, not just while
the app's own UI is visible. A regular (non-foreground) background service
would be subject to Android's background execution limits and could be
killed by the OS at any time without the persistent, user-visible indication
that `FOREGROUND_SERVICE_TYPE_MICROPHONE` requires and provides.

## What this service does not do

It does not access the microphone opportunistically or outside the window
the user turned the switch on; it does not transmit audio anywhere
(*verified*: no network permission, no networking code touching the audio
path — see the privacy policy's verification appendix); and it does not
restart itself silently after a reboot (see above, flagged as assumed and
pending re-confirmation).
