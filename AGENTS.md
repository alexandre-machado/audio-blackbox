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

- **Stock Material 3 Only ([#9](https://github.com/alexandre-machado/audio-blackbox/issues/9), [#15](https://github.com/alexandre-machado/audio-blackbox/pull/15))**:
  - Built on stable Material 3 (1.4.0 line via Compose BOM; see `[stack]` in [`.agents/team.toml`](.agents/team.toml)).
  - No Material 3 Expressive, no custom brand design languages or themed widgets. The app maintains a native Android system look and feel.
- **Dynamic Color**:
  - Dynamic color is enabled on Android 12+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`) in `AudioBlackboxTheme`. All UI components must render legibly across wallpaper-derived system color schemes.
- **Iconography (PR [#74](https://github.com/alexandre-machado/audio-blackbox/pull/74))**:
  - Use stock Material vector icons (`androidx.compose.material:material-icons-core`), not third-party icon packs or custom glyphs.

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
