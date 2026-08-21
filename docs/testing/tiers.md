# Test tiers: what runs where, and why

Three tiers exist, in increasing cost and decreasing reach into real Android behavior. Cheaper
tiers stay the primary gate; the point of the more expensive ones is to cover exactly what the
cheaper ones structurally cannot reach, not to duplicate them.

## Tier 0 — JVM unit tests (the primary gate, every PR)

```bash
export JAVA_HOME="$HOME/android-sdk-tools/jdk-17.0.20+8"
export ANDROID_SDK_ROOT="$HOME/android-sdk-tools/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
./gradlew testDebugUnitTest lintDebug
```

Runs on the JVM, no device or emulator, via `app/src/test/`. Covers everything expressible as
plain Kotlin/Mockito against a seam (`audioRecordFactory`, `ExportSink`, fake clocks): ring
buffer arithmetic, gap-fill/WAV byte layout with synthetic multi-gap fixtures, permission
resolution, state machines. This is the fast, deterministic majority of the coverage and is
required green on every PR (`.github/workflows/ci.yml`, job `build`).

## Tier 1 — instrumented tests on an emulator, in CI on every PR

```bash
./scripts/run-instrumented-tests.sh
```

Runs on a real (emulated) Android OS via `app/src/androidTest/`, exercising code the JVM tier
cannot: real `AudioRecord`/`AudioManager` behavior, real `MediaStore`, real foreground-service
lifecycle, and — the reason this tier exists — a **real telephony interruption**
(`adb emu gsm call`), which is the exact mechanism `RecorderService` listens for via
`AudioManager.AudioRecordingCallback.isClientSilenced` and the exact scenario PR #28's critical
bug (audio mis-spliced after the *second* interruption) lived in.

Runs in CI on every PR (`.github/workflows/ci.yml`, job `instrumented-tests`), with a fresh AVD
created and booted from cold on every run — no boot-snapshot cache. An earlier version of this
job cached the AVD boot snapshot across runs to bound the added wall-clock, but every run that
restored that cached snapshot failed to boot (`adb: device offline`, then a boot timeout, before
any test executed); only a from-scratch AVD ever booted successfully. A restored snapshot's
reliability across runner instances turned out not to hold on this runner pool, so the cache was
removed (PR #35 review) rather than kept as a source of unrelated, intermittent red — do not
reintroduce it without new evidence it boots reliably here.

AVD parameters live in one place, `scripts/ci/avd.env`, read by both the local script above and
the CI job:

- **API 30** ("R") — the floor at which `isClientSilenced` exists at all; testing at that floor
  is the strictest fence available for every supported device above it, and boots faster than a
  34/36 image.
- **google_apis / x86_64** — `google_apis` ships the telephony/radio stack `adb emu gsm call`
  needs; `x86_64` gets KVM-accelerated boot on GitHub's `ubuntu-latest` runners.

Local runs need hardware acceleration (`/dev/kvm` readable/writable by your user — see the
script's own comments for the one-time `usermod`/re-login step it deliberately does not attempt
itself). Without that, CI is the verification loop.

**A flaky instrumented test is a blocking defect, not a nuisance** (established on issue #26 /
PR #28 round 4). None of these tests use a retry wrapper, a bare `sleep` standing in for an
assertion, or `@FlakyTest` — they poll real, bounded state (`CaptureState`, `PauseGap` count, a
committed `MediaStore` row) with generous timeouts instead.

### What's covered here vs. deferred to Tier 2

| Candidate test (issue #34) | Where |
| --- | --- |
| Two real interruptions detected (correct gap count/ordering), then export commits | `InterruptionSpliceTest` (Tier 1) |
| Posted notification text keeps refreshing with no `CaptureState` transition (#30) | `NotificationBufferedDurationTest` (Tier 1) |
| Export lands at expected MediaStore path, `IS_PENDING` cleared, duration matches | `InterruptionSpliceTest` (Tier 1, folded into the same run) |
| Foreground service declares `FOREGROUND_SERVICE_TYPE_MICROPHONE`; notification not dismissible | `ForegroundServiceDeclarationTest` (Tier 1) |
| Force-stop releases the mic; service does not restart (`START_NOT_STICKY`) | **Tier 2 only** — see below for why |

Force-stop is not testable from `androidTest`: instrumentation shares the target app's process,
so force-stopping the package under test kills the test process running the assertion before it
can observe anything. It needs an external actor (a human, or a script driving a *different*
process) issuing `am force-stop` and then checking from outside — that is Tier 2's job on the
S25, done deliberately by hand per this task's device-safety constraints (an agent must not
force-stop a device that may be mid-session).

**Two empirical findings from getting this running, recorded here rather than assumed:**

- **Mic content injection did not work, and was not pursued further.** Confirmed by observation
  (the booted AVD logs `Could not init 'pa' audio driver` on start): this headless emulator has
  no host audio backend behind its virtual microphone, so `AudioRecord` reads silence regardless
  of what a "virtual mic" config claims to route. Wiring the `ToneGenerator`/`GoertzelDetector`
  pair from #21 into a tone-in/tone-out assertion here would not actually be testing anything, so
  it was not attempted for CI. It stays useful for a human running the JVM-fixture-driven
  `GoertzelDetectorTest` or a future manual pass on hardware with a real mic.
- **The interruption trigger itself does not need real audio content to be genuine, and getting
  it to fire took one non-obvious step.** `adb emu gsm call` alone only rings a simulated call —
  confirmed via `dumpsys audio`'s `RecordActivityMonitor` line for the app's session, it stays
  `silenced:false` through ringing. The call has to actually be answered before
  `AudioManager.AudioRecordingCallback.isClientSilenced` flips to `true` (and back to `false` on
  hangup) for another app's mic session. The emulator console has no "answer an inbound call"
  subcommand, but the stock `google_apis` image ships a real Telecom/Dialer stack, and
  `adb shell input keyevent KEYCODE_CALL` answers a ringing call through it exactly like a
  hardware call button would — see `scripts/ci/run-instrumented-tier.sh`'s call schedule for
  where this is used. `InterruptionSpliceTest` gets its value from proving this real trigger path
  fires correctly, twice (real GSM call → answered → `isClientSilenced` →
  `pause()`/`resume()` → correct gap count/ordering/duration), independent of what's in the
  audio — that path had never run before this issue.

**What `InterruptionSpliceTest` proves and does not prove, stated precisely (`@rev` finding,
PR #35):** it proves a real interruption is detected, that exactly two `PauseGap`s are recorded
in the correct order with no overlap, and that the subsequent export commits a non-pending
MediaStore row with a positive declared duration. It does **not** prove the exported audio is
correctly spliced — a mis-ordering or mis-placement of segments that still adds up to the same
total length would satisfy every assertion here unnoticed. That byte-level placement claim is
`GapFillerTest`'s job (a JVM unit test with a synthetic multi-gap fixture it can assert exact
segment content against); this tier cannot make the same claim about real captured audio because
the headless CI emulator has no host audio backend behind its virtual microphone (see the
mic-injection finding above), so there is no distinguishable content here to check placement
against in the first place.

## Tier 2 — scripted smoke run against the physical S25, on demand

```bash
./scripts/device-smoke.sh
```

Checks what only real hardware and a real OS/app ecosystem can reveal: `appops` for genuine mic
access, `dumpsys` for the live foreground-service state, a MediaStore `content query` for
path/`IS_PENDING`/duration, and pulling the actual export to verify the WAV header numerically.
Does not drive the app itself (start/stop recording, tap Save) — see the script's own header
comment for why, and for the wireless-debugging port-rediscovery step it needs because mDNS does
not traverse WSL.

Two things stay human-only even after this script exists, because they need a real app ecosystem
or a person's judgment, not just `adb`:
- the exported WAV actually opening in the apps a person uses day to day;
- Samsung-specific OS behavior around notifications, mic arbitration, and storage layout.
