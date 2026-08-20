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

Runs in CI on every PR (`.github/workflows/ci.yml`, job `instrumented-tests`), with the AVD boot
cached (two-step snapshot pattern) so the added wall-clock is bounded — see that job's comments,
and the PR that introduced it (#34) for the measured number.

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

**A flaky instrumented test is a blocking defect, not a nuisance** (see AGENTS.md and issue #26 /
PR #28 round 4). None of these tests use a retry wrapper, a bare `sleep` standing in for an
assertion, or `@FlakyTest` — they poll real, bounded state (`CaptureState`, `PauseGap` count, a
committed `MediaStore` row) with generous timeouts instead.

### What's covered here vs. deferred to Tier 2

| Candidate test (issue #34) | Where |
| --- | --- |
| Two simulated calls, then export — splice placement | `InterruptionSpliceTest` (Tier 1) |
| Notification buffered duration advances + pins at saturation (#30) | `NotificationBufferedDurationTest` (Tier 1) |
| Export lands at expected MediaStore path, `IS_PENDING` cleared, duration matches | `InterruptionSpliceTest` (Tier 1, folded into the same run) |
| Foreground service declares `FOREGROUND_SERVICE_TYPE_MICROPHONE`; notification not dismissible | `ForegroundServiceDeclarationTest` (Tier 1) |
| Force-stop releases the mic; service does not restart (`START_NOT_STICKY`) | **Tier 2 only** — see below for why |

Force-stop is not testable from `androidTest`: instrumentation shares the target app's process,
so force-stopping the package under test kills the test process running the assertion before it
can observe anything. It needs an external actor (a human, or a script driving a *different*
process) issuing `am force-stop` and then checking from outside — that is Tier 2's job on the
S25, done deliberately by hand per this task's device-safety constraints (an agent must not
force-stop a device that may be mid-session).

Mic content injection (the `ToneGenerator`/`GoertzelDetector` pair from #21, driving a genuine
tone-in/tone-out assertion) was not wired into Tier 1: GitHub-hosted runners are headless with no
host audio device behind the emulator's virtual microphone, so `AudioRecord` reads silence
regardless of what a "virtual mic" config claims to route — an end-to-end frequency assertion in
CI would not actually be testing anything. `InterruptionSpliceTest` gets its value instead from
proving the *real interruption trigger path* fires correctly (real GSM call →
`isClientSilenced` → `pause()`/`resume()` → correct gap count/ordering/duration), which is what
had never run before this issue, independent of what's in the audio.

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
