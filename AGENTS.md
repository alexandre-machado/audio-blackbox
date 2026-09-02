# AGENTS.md — Repository Conventions & Operational Invariants

This document records the hard-won conventions, testing principles, architecture invariants, and operational rules for `alexandre-machado/audio-blackbox`. Every convention here was established by a real incident, review cycle, or architectural decision in this repository.

For stack binding, project metadata, and canonical commands, see [`.agents/team.toml`](.agents/team.toml).

---

## 1. Stack Binding & Validation Command

- **Stack Context**: Defined authoritatively in [`.agents/team.toml`](.agents/team.toml) (Kotlin, Jetpack Compose, Material 3 1.4.0, Gradle Kotlin DSL, `minSdk 29`, `targetSdk 36`).
- **Primary Validation Command**:
  ```bash
  ./gradlew testDebugUnitTest lintDebug
  ```
  Every PR must pass this gate before merge.

---

## 2. The Vacuous-Test Rule & Mutation-Verified Non-Vacuity

### The Vacuous-Test Rule
Every test must state its **oracle**: the exact production behavior that would have to break for the test to fail. A test that passes when production code is broken is worse than no test.

Three specific vacuous-test traps have shipped in this repository and been caught in review:
1. **Asserting a pure function of its own arguments ([#30](https://github.com/alexandre-machado/audio-blackbox/issues/30))**:
   Testing state updates by recalculating the expected value using the exact same formula in the test instead of asserting against real production state transitions or side-effects. In [`PeriodicNotificationRefresherTest`](app/src/test/java/cc/machado/audioblackbox/service/PeriodicNotificationRefresherTest.kt), tests explicitly drive monotonic clock advances and saturation limits against a real [`RingBuffer`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt).
2. **Saturation masking an undercount or concurrency bug (PR [#28](https://github.com/alexandre-machado/audio-blackbox/pull/28) review finding 3)**:
   In concurrency tests, writing past buffer capacity causes `bufferedBytes()` to clamp at `capacityBytes`, masking dropped writes or missing synchronization locks. In [`CaptureContinuesDuringSnapshotTest`](app/src/test/java/cc/machado/audioblackbox/export/CaptureContinuesDuringSnapshotTest.kt), total writes are kept strictly below `capacityBytes` so `bufferedBytes() == totalWritten` is exact and load-bearing, paired with chunk-by-chunk payload verification.
3. **The natural-window-size trap in UI layout tests ([#78](https://github.com/alexandre-machado/audio-blackbox/issues/78), PR [#87](https://github.com/alexandre-machado/audio-blackbox/pull/87))**:
   Asserting layout at the emulator's natural window size passes even when a floating bar covers content because all content happens to fit on screen. Fixed compact viewports (360x320dp in [`CompactHarnessApp`](app/src/androidTest/java/cc/machado/audioblackbox/ui/LayoutHarness.kt)) force content to exceed available space, validating that the scroll viewport and content area end strictly above floating components (see [`ScreenLayoutTest`](app/src/androidTest/java/cc/machado/audioblackbox/ui/ScreenLayoutTest.kt)).

### Non-Vacuity Verified by Mutation
- Non-vacuity is **verified by mutation, not asserted** ([#51](https://github.com/alexandre-machado/audio-blackbox/issues/51), PR [#86](https://github.com/alexandre-machado/audio-blackbox/pull/86)).
- When introducing or modifying tests for critical logic (such as buffer boundaries, locking, or layout bounds), apply a targeted mutation to production code (e.g. invert a condition, drop a lock, or alter layout padding) and verify that the test fails with exactly the expected failure before reverting.

---

## 3. No Flaky-Test Escape Hatches

- **Zero Tolerance for Flaky Escapes ([#51](https://github.com/alexandre-machado/audio-blackbox/issues/51), [#78](https://github.com/alexandre-machado/audio-blackbox/issues/78), [#27](https://github.com/alexandre-machado/audio-blackbox/pull/27), PR [#28](https://github.com/alexandre-machado/audio-blackbox/pull/28))**:
  - No `Thread.sleep` or delay-based synchronization in tests.
  - No test retry runners or loops.
  - No `@FlakyTest` annotations.
- **Synchronization**:
  - Use explicit synchronization primitives (e.g. `CountDownLatch` handshakes between producer and consumer threads, such as `firstSnapshotLatch` in [`CaptureContinuesDuringSnapshotTest`](app/src/test/java/cc/machado/audioblackbox/export/CaptureContinuesDuringSnapshotTest.kt)).
  - Use framework-level idle synchronization in Compose tests (`createAndroidComposeRule` auto-sync and `waitForIdle`).
  - Poll bounded state changes with generous timeout guards that fail loudly with explicit assertion messages rather than hanging indefinitely.
- **Widening Race Windows**:
  - To widen race windows during concurrency testing, perform real work (e.g. `Arrays.fill` or repeated operations over large memory buffers), never inject artificial sleeps into production code.

---

## 4. Localization Lint Rules & String Constraints

- **Strict Two-Way Lint Enforcement ([#44](https://github.com/alexandre-machado/audio-blackbox/issues/44), [#65](https://github.com/alexandre-machado/audio-blackbox/pull/65))**:
  - `MissingTranslation` and `ExtraTranslation` are configured as fatal errors in `app/build.gradle.kts`.
  - Default English strings in `app/src/main/res/values/strings.xml` and Brazilian Portuguese strings in `app/src/main/res/values-pt-rBR/strings.xml` must remain 1:1 in sync.
- **String Length Awareness ([#89](https://github.com/alexandre-machado/audio-blackbox/issues/89))**:
  - Portuguese translations are typically 20% to 50% longer than English strings.
  - UI layouts must accommodate expanded text without unexpected multi-line wrapping, clipping, or pushing action controls off screen (e.g. the engine control switch wrap defect in [#89](https://github.com/alexandre-machado/audio-blackbox/issues/89)).

---

## 5. Design System Invariants

- **Avionics design system is the system of record ([#220](https://github.com/alexandre-machado/audio-blackbox/issues/220), superseding [#9](https://github.com/alexandre-machado/audio-blackbox/issues/9)/[#15](https://github.com/alexandre-machado/audio-blackbox/pull/15))**:
  - The "Stock Material 3 Only" rule recorded for [#9](https://github.com/alexandre-machado/audio-blackbox/issues/9)/[#15](https://github.com/alexandre-machado/audio-blackbox/pull/15) is **superseded, not deleted**. PR [#186](https://github.com/alexandre-machado/audio-blackbox/pull/186) ("apply US aviation and cockpit avionics styling to Dashboard, Gallery, and Settings", commit `7aea781`) shipped a themed brand palette across all three main screens without a doc update, and PR [#196](https://github.com/alexandre-machado/audio-blackbox/pull/196) (commit `eda6161`) retokenized the landing page against it. The owner ratified the code as the intended direction on 2026-08-29 (see [#220](https://github.com/alexandre-machado/audio-blackbox/issues/220)): the app is an **avionics/cockpit-instrument themed** design language (FED-STD-595 aviation orange, Korry annunciator colors, cockpit dark base), not a stock native-Android look.
  - **[`docs/design/model.html`](docs/design/model.html) is the design-system spec of record** — the living prototype's `:root` CSS custom properties are the canonical token definitions (colors, radii, text scale). **[`ui/theme/Color.kt`](app/src/main/java/cc/machado/audioblackbox/ui/theme/Color.kt) is the Compose implementation of that spec**, expected to match it, not the reference other code is reconciled against. Any new UI surface pulls its brand colors from `Color.kt`, and any new token in `Color.kt` should trace back to (or be added to) `model.html` first.
  - Material 3 (stable 1.4.0 line, see `[stack]` in [`.agents/team.toml`](.agents/team.toml)) remains the component library and interaction model underneath the theme — this supersession is about color/branding, not about swapping frameworks or adopting Material 3 Expressive.
- **Known token divergences between `model.html` and `Color.kt` ([#220](https://github.com/alexandre-machado/audio-blackbox/issues/220)), recorded honestly rather than glossed over:**
  - **Corner radius scale does not match.** `model.html:47-52` defines `--radius-rivet: 4px`, `--radius-sm: 8px`, `--radius-md: 14px`, `--radius-lg: 14px`, `--radius-full: 4px`, `--radius-pill: 9999px` (per PR [#200](https://github.com/alexandre-machado/audio-blackbox/pull/200)/[#198](https://github.com/alexandre-machado/audio-blackbox/issues/198): `--radius-pill` is the dedicated token for true capsule/circular shapes — physical hardware cutouts like the toggle track and VU capsule — while `--radius-lg`/`--radius-full` were deliberately capped down from `20px`/`9999px` to `14px`/`4px` for a squarer, rivet-hardware look, not a soft Material "fully rounded" corner). The Compose code does not follow this scale: `DashboardScreen.kt`, `SettingsScreen.kt` and `GalleryScreen.kt` use `RoundedCornerShape(16.dp)` and `RoundedCornerShape(20.dp)` for top-level cards, both larger than the spec's 14px ceiling. **Not reconciled** — a follow-up should either shrink the Compose card radii to match `--radius-lg`/`--radius-md`, or the spec should be updated if 16–20dp is the intended card radius; do not assume either resolution here.
  - **`Color.kt` has no text-color-scale constants at all.** ~~`model.html:43-45` defines `--color-text-stencil: #F8FAFC`, `--color-text-muted: #94A3B8`, `--color-text-dim: #64748B` as named text tokens; Compose text colors are not centralized to equivalents of these (they fall back to the Material `colorScheme`'s `onSurface`/`onSurfaceVariant`, which are dynamic-color-derived, not the fixed stencil/muted/dim values the spec defines). This is an open gap, not yet closed.~~ **Closed by [#225](https://github.com/alexandre-machado/audio-blackbox/issues/225):** `Color.kt` now defines `TextStencil`/`TextMuted`/`TextDim` matching `model.html:43-45` exactly, and `Theme.kt` maps `onBackground`/`onSurface` to `TextStencil` and `onSurfaceVariant` to `TextMuted` — since the `ColorScheme` is now fixed (no dynamic color), those roles are the spec's fixed values, not a wallpaper-derived approximation of them.
  - **The base ground/colour scheme is decided: the app is permanently dark, on the `model.html` cockpit ground ([#225](https://github.com/alexandre-machado/audio-blackbox/issues/225)).** `model.html:37-41` defines a fixed dark cockpit ground (`--color-cockpit-bg: #0A0E17`, `--color-cockpit-panel: #111827`, plus `--color-cockpit-panel-raised: #1A2234` and translucent `--color-cockpit-border(-strong)`) — the living prototype is always-dark by design. The owner has ratified this as the target: no light scheme, no dynamic (wallpaper-derived) color; the app always renders the fixed cockpit ground and brand palette. **Implemented by [#225](https://github.com/alexandre-machado/audio-blackbox/issues/225).** `ui/theme/Theme.kt` now builds a single fixed `darkColorScheme(...)` from these tokens (`background` = `CockpitSlate`, `surface`/`surfaceVariant` = `CockpitPanel`, `surfaceContainerHigh`/`surfaceContainerHighest` = `CockpitPanelRaised`, `outline`/`outlineVariant` = `CockpitBorderStrong`) — no `dynamicLightColorScheme`/`dynamicDarkColorScheme`, no `isSystemInDarkTheme()` branch, no light scheme.
  - **Other color-token deltas found by direct comparison** (values checked field-by-field): `model.html`'s `-hover`/`-glow` variants (`--color-flight-orange-hover: #F4511E`, and every `*-glow` rgba token) have no `Color.kt` counterpart at all — Compose only has the flat/dark/light/container variants. `FlightOrangeContainer` (`0x33FF5722`, ~20% alpha) is the closest analog to `--color-flight-orange-glow` (`rgba(…, 0.35)`) but the alpha differs and it is not derived from the spec value. `--color-cockpit-panel-raised` (`#1A2234`) and `--color-cockpit-border`/`-strong` (translucent white) have no `Color.kt` counterpart; `CockpitRivetBorder` (`0xFF334155`, opaque slate) is a different color model (opaque vs. translucent-white overlay) for a similar role. `CautionAmberDim` (`0xFF78350F`) has no `model.html` counterpart. All other flat brand colors that do exist in both (`FlightOrange`/`--color-flight-orange`, `FlightOrangeLight`/`-light`, `FlightOrangeDark`/`-dark`, `AvionicsGreen`/`--color-nvis-green`, `AvionicsGreenDim`/`-dim`, `CautionAmber`/`--color-caution-amber`, `WarningRed`/`--color-warning-red`, `TelemetryCyan`/`--color-telemetry-cyan`, `CockpitSlate`/`--color-cockpit-bg`, `CockpitPanel`/`--color-cockpit-panel`, `SafetyRedTag` `0xFFB91C1C`/the hardcoded `#B91C1C` "Remove Before Flight" tag fill) match exactly.
- **Semantic colour-role rules (new, [#220](https://github.com/alexandre-machado/audio-blackbox/issues/220))**:
  - The palette encodes a de-facto annunciator progression already used across Dashboard/Gallery/Settings. It is now a documented rule, not an implicit convention:
    - **Green (`AvionicsGreen`)** — OK / actively recording.
    - **Amber (`CautionAmber`)** — paused / caution state (e.g. phone-call interruption).
    - **Red (`WarningRed`)** — error / warning state.
    - **Orange (`FlightOrange`)** — brand color, reserved for the card-level primary call-to-action (the "salvar o passado" / forward-recording action and equivalent primary buttons).
  - **A card-level primary action must use `FlightOrange`, never a state color or the dynamic-theme accent.** This ambiguity — a primary CTA rendering in the wallpaper-derived dynamic color instead of the brand orange — is exactly what let the forward-recording button drift off-brand (see [#221](https://github.com/alexandre-machado/audio-blackbox/issues/221)). State colors (green/amber/red) are reserved for signalling engine/session state, never for a plain navigational or CTA button.
- **Dynamic color is superseded, not deleted — the app goes permanently dark instead ([#225](https://github.com/alexandre-machado/audio-blackbox/issues/225), superseding the "Dynamic Color" rule previously recorded at `AGENTS.md:70-71`)**:
  - The old rule read: dynamic color is enabled on Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`) in `AudioBlackboxTheme`, and all UI components must render legibly across wallpaper-derived system color schemes. That rule is superseded by the owner's [#225](https://github.com/alexandre-machado/audio-blackbox/issues/225) decision: the app is dark-always on the fixed `model.html` cockpit ground, with no dynamic color and no light scheme.
  - **Why this closes, rather than merely relocates, the previously-recorded contrast debt**: the earlier finding — that `FlightOrange`/`Color.White` and the fixed `surfaceVariant`-based card fills were not proven legible against an unpredictable wallpaper-derived scheme — was a risk specifically because a fixed brand palette was being painted over a variable dynamic scheme. Once dynamic color is dropped and the fixed cockpit ground/brand palette is the only scheme that ever renders, that specific hazard no longer exists; contrast only needs to be proven once, against a scheme that is now fixed and known, not against arbitrary wallpapers. **Implemented by [#225](https://github.com/alexandre-machado/audio-blackbox/issues/225):** `Theme.kt` no longer has a `dynamicColor`/`darkTheme` parameter at all — `AudioBlackboxTheme` takes only a `content` slot and always applies the same fixed `ColorScheme`, so this is resolved in the running code, not merely recorded as a target.
- **Iconography ([#220](https://github.com/alexandre-machado/audio-blackbox/issues/220), supersedes the strict reading of PR [#74](https://github.com/alexandre-machado/audio-blackbox/pull/74))**:
  - The repo ships custom local vector drawables using stock Material glyph shapes (`ic_waveform_mic`, `ic_ram_memory`, `ic_bookmark_save`, `ic_continuous_record`, `ic_settings_gear`, `ic_pause`, `ic_audio_specs`, `ic_privacy_shield`) instead of, or alongside, `androidx.compose.material:material-icons-core`. This is a real divergence from PR [#74](https://github.com/alexandre-machado/audio-blackbox/pull/74)'s literal "library artifact only" wording, which this rule now blesses: bundled local vector drawables using stock Material glyph designs are permitted (e.g. to control tint/size for notification-bar rules, or to avoid pulling in icon-font sets not needed elsewhere). Third-party icon packs and genuinely novel (non-Material-derived) glyphs are still out of scope.

### Rules carried forward unchanged

The following predate the avionics rewrite and remain in force; they are layout/architecture/behavioral invariants, not color rules, so [#220](https://github.com/alexandre-machado/audio-blackbox/issues/220) does not touch them:

- **One Scaffold / single `innerPadding`** ([#73](https://github.com/alexandre-machado/audio-blackbox/issues/73), [#78](https://github.com/alexandre-machado/audio-blackbox/issues/78)): each screen owns exactly one `Scaffold`, and content consumes the single `innerPadding` it provides.
- **Route/Screen purity seam**: `*Route` composables own state/ViewModel wiring; `*Screen` composables are pure functions of their parameters, testable without a ViewModel.
- **PT-BR string expansion** ([#89](https://github.com/alexandre-machado/audio-blackbox/issues/89)): Portuguese strings run 20–50% longer than English; layouts must not clip, unexpectedly wrap, or push action controls off screen.
- **No fixed heights on action buttons** (PR [#192](https://github.com/alexandre-machado/audio-blackbox/pull/192)): action buttons size to content/padding, not a hardcoded `height()`, so they survive text-length and font-scale variation.
- **Never fake a signal in the UI** ([#175](https://github.com/alexandre-machado/audio-blackbox/issues/175)): displayed state (recording/paused/error, buffered duration, etc.) must reflect real production state, never a placeholder or optimistic guess.
- **Live-region semantics** ([#66](https://github.com/alexandre-machado/audio-blackbox/issues/66), [#73](https://github.com/alexandre-machado/audio-blackbox/issues/73)): state changes that matter for accessibility are exposed via Compose semantics live regions, not silently updated visuals only.

---

## 6. Test Tier Blind Spots

The repository uses three distinct test tiers. Cheaper tiers serve as the primary pre-merge gate; more expensive tiers cover what cheaper tiers structurally cannot see (see [`docs/testing/tiers.md`](docs/testing/tiers.md)).

| Test Tier | Execution Environment | What It Covers | What It Cannot See / Blind Spots |
| :--- | :--- | :--- | :--- |
| **Tier 0: JVM Unit Tests**<br>`testDebugUnitTest` | Host JVM (x86_64), large heap (`maxHeapSize = "4g"`) | Fast deterministic coverage: [`RingBuffer`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt) math and concurrency, [`GapFiller`](app/src/main/java/cc/machado/audioblackbox/export/GapFiller.kt), WAV/AAC encoders, ViewModels, permission resolvers. Also, as of [#272](https://github.com/alexandre-machado/audio-blackbox/issues/272): the *refusal logic* of `RingBuffer.resize`'s memory-budget check, via an injected `MemoryBudget` fake the test fully controls -- this is genuinely non-vacuous here because the question ("given these exact heap numbers, does the guard fire?") does not depend on how big the host JVM's own heap actually is. | **Cannot reproduce Dalvik OOM limits** ([#72](https://github.com/alexandre-machado/audio-blackbox/issues/72): real devices enforce a 256MB Dalvik heap growth limit; JVM heap masks this). No Android framework runtime, no real `AudioRecord`, no telephony interruptions, no real layout bounds. Verifying that the real 256 MB ceiling is what production actually hits (as opposed to the refusal logic being correct in the abstract) is out of reach for every tier below Tier 2 -- confirmed on-device for [#272](https://github.com/alexandre-machado/audio-blackbox/issues/272) via `dumpsys dropbox --print`, not any automated tier. |
| **Tier 1: Instrumented Tests**<br>`connectedDebugAndroidTest` | CI-only headless emulator (API 30 `google_apis` x86_64) | Real framework integration: telephony interruption handling ([#34](https://github.com/alexandre-machado/audio-blackbox/issues/34), [#35](https://github.com/alexandre-machado/audio-blackbox/pull/35)), real `MediaStoreSink`, foreground service lifecycle, edge-to-edge layout bounds ([#78](https://github.com/alexandre-machado/audio-blackbox/issues/78), [#87](https://github.com/alexandre-machado/audio-blackbox/pull/87)). | **CI-only in local dev environment** (no local emulator available). Headless emulator has no virtual mic host audio backend. Measures synthetic x86 copy throughput rather than ARM performance ([#22](https://github.com/alexandre-machado/audio-blackbox/issues/22)). Does not simulate physical hardware cutouts or OEM system insets ([#78](https://github.com/alexandre-machado/audio-blackbox/issues/78), [#80](https://github.com/alexandre-machado/audio-blackbox/issues/80)). |
| **Tier 2: Physical Device Smoke**<br>`scripts/device-smoke.sh` | Real hardware (Samsung S25 / SM-S931B, arm64-v8a, Android 16) | True end-to-end device validation: real microphone arbitration, OEM background battery killer behavior, real storage layout, hardware insets. | Manual and on-demand only. Cannot run automated destructive force-stops safely without user presence. |

---

## 7. Concurrency & Audio Capture Thread Rules

- **Single-Writer Invariant ([#20](https://github.com/alexandre-machado/audio-blackbox/pull/20), [#51](https://github.com/alexandre-machado/audio-blackbox/issues/51))**:
  - [`AudioCaptureEngine`](app/src/main/java/cc/machado/audioblackbox/audio/AudioCaptureEngine.kt)'s dedicated capture thread is the **sole writer** to [`RingBuffer`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt).
  - Exporters, ViewModels, and UI drain loops are strictly readers.
- **Zero Disk / Zero IPC on Capture Thread**:
  - The capture loop hot path must never perform disk I/O, IPC calls, or runtime memory allocations that trigger GC pauses.
- **Allocation & Locking Model ([#20](https://github.com/alexandre-machado/audio-blackbox/pull/20), [#22](https://github.com/alexandre-machado/audio-blackbox/issues/22)), amended by [#223](https://github.com/alexandre-machado/audio-blackbox/issues/223) and [#272](https://github.com/alexandre-machado/audio-blackbox/issues/272)**:
  - [`RingBuffer`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt) allocates its backing byte array and marker rings at construction, **and can subsequently be resized in-place** via `RingBuffer.resize` (issue #223) without discarding buffered audio -- "once at construction" no longer holds literally as of #223 and this line was stale until #272 caught the mismatch (the resize path itself had no OOM guard, and shipped a fatal crash to production three times before this was corrected).
  - `resize`'s copy needs the old and new backing arrays to coexist, so it allocates a **second** full-size array on top of whatever is already resident, not a replacement for it -- a real memory peak, not a bookkeeping detail. `resize` therefore samples an injectable `MemoryBudget` (real source: `Runtime.getRuntime()`) before allocating anything and refuses (`ResizeOutcome.Refused`, no allocation, capacity and buffered audio completely unchanged) if the projected peak would not fit inside `DeviceMemoryBudget.SAFE_HEAP_UTILISATION` of the reported heap ceiling. `AudioCaptureEngine.switchConfig` propagates a refusal as `SwitchConfigResult.BufferResizeRefused` rather than letting an `OutOfMemoryError` escape, and `RecorderService.switchSettings`/`SettingsViewModel` only commit the new setting when the switch actually applied, surfacing a refusal as a real, visible error instead of drifting the committed setting out of sync with the engine's actual capacity.
  - All state mutations and reads are protected by a single intrinsic `lock`. No nested or cross-locking exists, eliminating deadlock by construction.
  - Incremental drain reads via `RingBuffer.readSince(cursor, maxBytes)` must specify an explicit `maxBytes` bound to avoid capacity-sized allocations under the lock, and consumers must explicitly handle [`ReadSinceResult.Lapped`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt) and [`ReadSinceResult.StreamReset`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt) ([#51](https://github.com/alexandre-machado/audio-blackbox/issues/51), PR [#86](https://github.com/alexandre-machado/audio-blackbox/pull/86)).

---

## 8. Git & Worktree Conventions

- **Explicit-Path Staging**:
  - Always stage specific files with `git add <file1> <file2>`; never use `git add -A` or `git add .` to avoid committing unwanted artifacts or scratch files.
- **Worktrees for Concurrent Specialists**:
  - Specialists working on concurrent tasks operate in separate git worktrees branched from `origin/main`.
- **Local SDK Configuration (`local.properties`)**:
  - Fresh git worktrees do not inherit `local.properties`. A `local.properties` file with `sdk.dir=<path-to-sdk>` must be present locally for Gradle builds (gitignored, never committed).
- **Tool Discipline**:
  - Maintainer environments filter file dump commands (`cat`, `head`, `sed`). Inspect file contents using structured read/view/grep tools; execute shell commands only for build, testing, and git operations.
- **Closing Keywords Are Checked Across Every Commit Body, Not Just the PR Body**:
  - This repository squash-merges, and the squash commit body **concatenates the body of every
    commit on the branch**. A `Fixes #NNN` left in an early commit therefore closes that issue on
    merge even when the PR body was deliberately repointed elsewhere.
  - Before merging, grep the whole branch, not the PR description:
    `git log origin/main..HEAD --format='%B' | grep -inE '\b(close[sd]?|fix(e[sd])?|resolve[sd]?)\b[[:space:]]*#[0-9]+'`
  - The regex is a **heuristic, not an exhaustive gate**: it misses colon-separated forms
    (`Closes: #123`) and cross-repo references (`Resolves org/repo#12`). Read the branch's commit
    list too; a clean grep is not proof.
  - When repointing a PR at a different issue, rewrite the offending **commit messages** too;
    editing the PR body alone is not enough. Verify issue states immediately after any merge.
  - Origin: [#270](https://github.com/alexandre-machado/audio-blackbox/pull/270) closed the
    still-live [#267](https://github.com/alexandre-machado/audio-blackbox/issues/267) this way
    on 2026-09-01, after a reviewer had correctly verified the PR body and nothing else.

---

## 9. Committed Screenshots Are CI-Generated

- **Never hand-edit or hand-update the Play Store listing images or hotsite screenshots** ([#228](https://github.com/alexandre-machado/audio-blackbox/issues/228), [#231](https://github.com/alexandre-machado/audio-blackbox/issues/231)):
  - `distribution/metadata/android/{en-US,pt-BR}/images/phoneScreenshots/*.png` and `docs/assets/screenshot_*.png` are produced by `ScreenshotCaptureTest` on the CI emulator and copied onto their committed destinations by [`scripts/ci/refresh-store-captures.sh`](scripts/ci/refresh-store-captures.sh), not authored or retouched by hand.
  - The `instrumented-tests` CI job runs that script every build, diffs the nine destinations byte-for-byte against this run's captures, and auto-commits any drift onto a pull request's own branch (failing instead on a direct push to `main`, where auto-committing has nowhere safe to land). Twice before ([#227](https://github.com/alexandre-machado/audio-blackbox/pull/227), [#228](https://github.com/alexandre-machado/audio-blackbox/issues/228)) these images silently fell out of sync with the UI they claim to show because nothing checked them; this gate is what closes that gap.

---

## 10. Pre-Merge Gate: `@sec` and `@rev` Markers

Every pull request must pass a dual specialist review before merging:
1. **Security Specialist (`@sec`)**: Verifies permissions, IPC surface, data exposure, and security boundaries.
2. **Code Review Specialist (`@rev`)**: Verifies correctness, concurrency invariants, non-vacuous testing, and performance contracts.

Both specialists must post explicit, grep-verifiable approval markers in PR review comments prior to merge.

### The marker comment is the deliverable, not the returned text

A review that was performed but not posted has not passed the gate. Findings returned to
whoever dispatched the reviewer are a convenience; the PR comment beginning with the fixed
string `## @sec review` / `## @rev review` is the artifact. Whoever merges verifies it exists
with `gh pr view <N> --json comments`, never by trusting a reviewer's own report that it was
posted.

The reverse failure is worse and has happened here:
[#207](https://github.com/alexandre-machado/audio-blackbox/pull/207) merged at `14:26:35Z`
with **neither marker posted yet** — `@sec`'s landed 13 seconds later, `@rev`'s two minutes
later, and `@rev`'s was an explicit **BLOCK**. The gate did not fail to produce artifacts; the
merge simply did not wait for them. **Absence of a marker is not a pass, and it is not a
signal to proceed — it means the gate has not run.**

### Every marker names the SHA it reviewed, and the CI conclusion for that SHA

- **Name the SHA.** A verdict is about one commit. When a PR is updated after a review, the
  existing markers no longer cover it and the gate must run again at the new HEAD.
- **State the CI conclusion for that same SHA, checked directly (`gh pr checks`), not assumed.**
  On [#278](https://github.com/alexandre-machado/audio-blackbox/pull/278) (2026-09-02) both
  specialists issued PASS at a SHA whose `instrumented-tests` job was failing; neither had
  looked. That failure turned out to be environmental, but nothing in the process would have
  distinguished it from a real regression, and two PASS verdicts stood in front of it. A green
  suite is not sufficient for a PASS, but a red one forbids issuing one silently.
- **A stated CI conclusion is a snapshot, not a live guarantee — cite the run ID.** Checks can be
  rerun on the same SHA, and `gh pr checks` shows only the latest attempt. That is not
  hypothetical: the #278 failure above was attempt 1 of run `33637986231`; attempt 2 went green,
  so today the very command this rule prescribes shows nothing wrong with the SHA two reviewers
  passed blind. Name the run ID you checked, and re-check immediately before merging rather than
  trusting an older marker's snapshot.
- **If CI has not completed, do not issue a verdict on it.** Wait, or post the marker saying
  explicitly which jobs were still running and that the verdict is withheld pending them. A
  verdict may rest on a pending pipeline only when it says so; never on an unexamined one.

### Verify the artifact, not the report

The recurring failure mode behind the four incidents cited in this section is the same shape:
someone verified a true thing that was not the thing that mattered. A correct PR body while
a stale commit body carried the closing keyword; a reviewer's accurate summary of a comment
it never posted; a skill's empty output read as an all-clear; a thorough review issued blind
to a red pipeline. Before relying on any claim that an action was *performed*, check the
artifact it would have produced — the comment, the run, the commit, the issue state.

A related trap is asserting **absence**. "I did not see X" is not "X is not there": a `Read` of
this very file once returned 156 of its 168 lines with no error and no truncation notice, and the
agent reported an existing section as missing. Cross-check length (`wc -l`) before reporting
anything absent — see §8's Tool Discipline for the general habit.

---

## 11. Foreground Service Start Eligibility Is Not the Same Gate as Background-Start Exemption

- **A `TileService` tap cannot start a `microphone`-type foreground service on Android 14+ (targetSdk 34+), and a Quick Settings tile was removed for exactly this reason ([#267](https://github.com/alexandre-machado/audio-blackbox/issues/267), [#273](https://github.com/alexandre-machado/audio-blackbox/issues/273) · 2026-09-01)**:
  - Android enforces **two distinct gates**, and a `TileService.onClick()` only clears the first:
    1. The Android 12+ *background FGS-start exemption* list includes generic UI-element interaction, so `startForegroundService()` called from `TileService.onClick()` succeeds at the call site.
    2. The separate, narrower *while-in-use permission eligibility* list (targetSdk 34+, required for a `microphone`-typed FGS's `startForeground()` call) covers only: system-component start, app-widget interaction, notification interaction, a `PendingIntent` from another visible app, device-policy-controller, `VoiceInteractionService`, and `START_ACTIVITIES_FROM_BACKGROUND`. **`TileService` is absent from this second list.**
  - The result: the tile's `startForegroundService()` call succeeds, then the service's own `startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` a moment later throws `SecurityException` — confirmed 14 times in production crash data. Because the crash happened before the process-static capture state could transition away from `Idle`, every subsequent tap re-entered the same start branch and crashed again; the tile's stop path was never reachable.
  - **[#79](https://github.com/alexandre-machado/audio-blackbox/issues/79) recorded the analogous lesson for the boot-completed path** (a `BroadcastReceiver`-initiated restart cannot reopen the mic from the background either) but that lesson was never generalized to the tile, and the tile shipped with the same defect. Treat this as one rule with two instances: **any caller that starts a `microphone`-typed foreground service on Android 14+ must be on the while-in-use eligibility list above, not merely on the broader background-start exemption list.** Before wiring a new trigger (tile, app widget, broadcast receiver, `PendingIntent`) to start recording, check it against the eligibility list first, not just "does `startForegroundService()` not throw."
  - The Quick Settings tile (`AudioBlackboxTileService`) was removed rather than repaired: its stop path was unreachable and it delivered zero working function, so nothing was lost. An app widget is a valid replacement trigger (app-widget interaction *is* on the eligibility list) and is tracked separately.
