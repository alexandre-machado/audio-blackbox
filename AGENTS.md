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
  - **`Color.kt` has no text-color-scale constants at all.** `model.html:43-45` defines `--color-text-stencil: #F8FAFC`, `--color-text-muted: #94A3B8`, `--color-text-dim: #64748B` as named text tokens; Compose text colors are not centralized to equivalents of these (they fall back to the Material `colorScheme`'s `onSurface`/`onSurfaceVariant`, which are dynamic-color-derived, not the fixed stencil/muted/dim values the spec defines). This is an open gap, not yet closed.
  - **The base ground/ colour scheme is the largest divergence and is an open question, not a decided one.** `model.html:37-41` defines a fixed dark cockpit ground (`--color-cockpit-bg: #0A0E17`, `--color-cockpit-panel: #111827`, plus `--color-cockpit-panel-raised: #1A2234` and translucent `--color-cockpit-border(-strong)`), i.e. the spec's living prototype is always-dark. `Theme.kt:28-33` instead resolves `AudioBlackboxTheme`'s color scheme from `isSystemInDarkTheme()` plus the platform dynamic color scheme on API 31+, i.e. light/dark and wallpaper-derived, not fixed-cockpit-dark. Reconciling this may be a much larger change than a token rename (potentially dropping dynamic color and/or light theme entirely for the cockpit surfaces). **This is flagged for the owner to decide, not decided here.**
  - **Other color-token deltas found by direct comparison** (values checked field-by-field): `model.html`'s `-hover`/`-glow` variants (`--color-flight-orange-hover: #F4511E`, and every `*-glow` rgba token) have no `Color.kt` counterpart at all — Compose only has the flat/dark/light/container variants. `FlightOrangeContainer` (`0x33FF5722`, ~20% alpha) is the closest analog to `--color-flight-orange-glow` (`rgba(…, 0.35)`) but the alpha differs and it is not derived from the spec value. `--color-cockpit-panel-raised` (`#1A2234`) and `--color-cockpit-border`/`-strong` (translucent white) have no `Color.kt` counterpart; `CockpitRivetBorder` (`0xFF334155`, opaque slate) is a different color model (opaque vs. translucent-white overlay) for a similar role. `CautionAmberDim` (`0xFF78350F`) has no `model.html` counterpart. All other flat brand colors that do exist in both (`FlightOrange`/`--color-flight-orange`, `FlightOrangeLight`/`-light`, `FlightOrangeDark`/`-dark`, `AvionicsGreen`/`--color-nvis-green`, `AvionicsGreenDim`/`-dim`, `CautionAmber`/`--color-caution-amber`, `WarningRed`/`--color-warning-red`, `TelemetryCyan`/`--color-telemetry-cyan`, `CockpitSlate`/`--color-cockpit-bg`, `CockpitPanel`/`--color-cockpit-panel`, `SafetyRedTag` `0xFFB91C1C`/the hardcoded `#B91C1C` "Remove Before Flight" tag fill) match exactly.
- **Semantic colour-role rules (new, [#220](https://github.com/alexandre-machado/audio-blackbox/issues/220))**:
  - The palette encodes a de-facto annunciator progression already used across Dashboard/Gallery/Settings. It is now a documented rule, not an implicit convention:
    - **Green (`AvionicsGreen`)** — OK / actively recording.
    - **Amber (`CautionAmber`)** — paused / caution state (e.g. phone-call interruption).
    - **Red (`WarningRed`)** — error / warning state.
    - **Orange (`FlightOrange`)** — brand color, reserved for the card-level primary call-to-action (the "salvar o passado" / forward-recording action and equivalent primary buttons).
  - **A card-level primary action must use `FlightOrange`, never a state color or the dynamic-theme accent.** This ambiguity — a primary CTA rendering in the wallpaper-derived dynamic color instead of the brand orange — is exactly what let the forward-recording button drift off-brand (see [#221](https://github.com/alexandre-machado/audio-blackbox/issues/221)). State colors (green/amber/red) are reserved for signalling engine/session state, never for a plain navigational or CTA button.
- **Dynamic color and contrast debt (known open risk, [#220](https://github.com/alexandre-machado/audio-blackbox/issues/220))**:
  - `AudioBlackboxTheme` (`Theme.kt:28-33`) still enables the platform dynamic color scheme on Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`) for the base Material color scheme, while the avionics brand colors (`FlightOrange`, `Color.White` text on it, and the fixed `surfaceVariant`-based cockpit card fills) are hardcoded, not derived from the wallpaper.
  - This means legibility across arbitrary wallpaper-derived system color schemes is **not currently guaranteed** for the avionics-themed surfaces — a fixed-orange-on-fixed-dark card can end up low-contrast against a dynamic scheme it was never tested against. Treat this as an open risk, not a solved problem, until a follow-up locks the cockpit surfaces to the fixed avionics palette (or otherwise proves contrast) instead of layering them over dynamic color.
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
| **Tier 0: JVM Unit Tests**<br>`testDebugUnitTest` | Host JVM (x86_64), large heap (`maxHeapSize = "4g"`) | Fast deterministic coverage: [`RingBuffer`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt) math and concurrency, [`GapFiller`](app/src/main/java/cc/machado/audioblackbox/export/GapFiller.kt), WAV/AAC encoders, ViewModels, permission resolvers. | **Cannot reproduce Dalvik OOM limits** ([#72](https://github.com/alexandre-machado/audio-blackbox/issues/72): real devices enforce a 256MB Dalvik heap growth limit; JVM heap masks this). No Android framework runtime, no real `AudioRecord`, no telephony interruptions, no real layout bounds. |
| **Tier 1: Instrumented Tests**<br>`connectedDebugAndroidTest` | CI-only headless emulator (API 30 `google_apis` x86_64) | Real framework integration: telephony interruption handling ([#34](https://github.com/alexandre-machado/audio-blackbox/issues/34), [#35](https://github.com/alexandre-machado/audio-blackbox/pull/35)), real `MediaStoreSink`, foreground service lifecycle, edge-to-edge layout bounds ([#78](https://github.com/alexandre-machado/audio-blackbox/issues/78), [#87](https://github.com/alexandre-machado/audio-blackbox/pull/87)). | **CI-only in local dev environment** (no local emulator available). Headless emulator has no virtual mic host audio backend. Measures synthetic x86 copy throughput rather than ARM performance ([#22](https://github.com/alexandre-machado/audio-blackbox/issues/22)). Does not simulate physical hardware cutouts or OEM system insets ([#78](https://github.com/alexandre-machado/audio-blackbox/issues/78), [#80](https://github.com/alexandre-machado/audio-blackbox/issues/80)). |
| **Tier 2: Physical Device Smoke**<br>`scripts/device-smoke.sh` | Real hardware (Samsung S25 / SM-S931B, arm64-v8a, Android 16) | True end-to-end device validation: real microphone arbitration, OEM background battery killer behavior, real storage layout, hardware insets. | Manual and on-demand only. Cannot run automated destructive force-stops safely without user presence. |

---

## 7. Concurrency & Audio Capture Thread Rules

- **Single-Writer Invariant ([#20](https://github.com/alexandre-machado/audio-blackbox/pull/20), [#51](https://github.com/alexandre-machado/audio-blackbox/issues/51))**:
  - [`AudioCaptureEngine`](app/src/main/java/cc/machado/audioblackbox/audio/AudioCaptureEngine.kt)'s dedicated capture thread is the **sole writer** to [`RingBuffer`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt).
  - Exporters, ViewModels, and UI drain loops are strictly readers.
- **Zero Disk / Zero IPC on Capture Thread**:
  - The capture loop hot path must never perform disk I/O, IPC calls, or runtime memory allocations that trigger GC pauses.
- **Allocation & Locking Model ([#20](https://github.com/alexandre-machado/audio-blackbox/pull/20), [#22](https://github.com/alexandre-machado/audio-blackbox/issues/22))**:
  - [`RingBuffer`](app/src/main/java/cc/machado/audioblackbox/audio/RingBuffer.kt) allocates its backing byte array and marker rings once at construction.
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

---

## 9. Pre-Merge Gate: `@sec` and `@rev` Markers

Every pull request must pass a dual specialist review before merging:
1. **Security Specialist (`@sec`)**: Verifies permissions, IPC surface, data exposure, and security boundaries.
2. **Code Review Specialist (`@rev`)**: Verifies correctness, concurrency invariants, non-vacuous testing, and performance contracts.

Both specialists must post explicit, grep-verifiable approval markers in PR review comments prior to merge.
