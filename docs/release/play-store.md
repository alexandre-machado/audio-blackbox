# Play Store launch prep (issue #48)

Groundwork for submitting Audio Blackbox to Google Play. This is documentation and
(where safe) build-config review — it does not set up automated publishing and does
not contain any secret material.

**Bottom line up front:** this is a legitimate, reviewable app, but it sits in a
policy category Google scrutinizes manually (background microphone foreground
service). Expect at least one round of human review with a required demo video, and
budget calendar time for that, not just engineering time. See section F for the
honest risk read.

All policy citations below were retrieved 2026-08-21. Play policy changes; re-check
before submission if this document is more than a few months old.

---

## A. Blocking human actions (owner only, ordered)

These cannot be done by an agent or from this repo. Nothing else in this checklist
can complete until these land.

1. **Play Console developer account verification.** New accounts go through an
   identity-verification step (individual or organization) before any app can be
   published, and Google has added phone/ID/D-U-N-S checks that can take days.
   Confirm this finished — a stuck verification silently blocks every later step.
   **Citation note:** I could not locate a live, correctly-numbered
   `support.google.com/googleplay/android-developer/answer/...` page for this
   specific claim — the URL previously cited here 404'd on re-check, and I was
   not able to find the correct one within this task's budget. Treat this item's
   existence as true (it is a well-known, current Play Console requirement) but
   verify the specifics directly in Play Console's own "Account details" /
   verification flow rather than trusting a citation here.
2. **Privacy policy hosting + URL.** Mandatory, no exception, for an app requesting
   `RECORD_AUDIO`. It must be at a stable, publicly reachable URL (not a Google Doc
   link with edit access, not a localhost/staging URL) and it must say, plainly,
   what issue #48 already establishes as true: audio is captured to a rolling
   in-memory buffer, is written to disk only when the user explicitly saves, never
   leaves the device, and there is no network transmission. I did not draft hosting
   for this (no CMS/domain decision to make on the owner's behalf) — draft copy is
   below in section D so the owner only has to decide *where* to host it (GitHub
   Pages off this repo's `docs/`, a static page, etc.), not *what* it says.
3. **Data safety form submission.** Owner-only, submitted directly in Play
   Console; draft answers are in section D below, but nobody except the account
   owner can click submit, and issue #48 treats this as a hard blocker in its own
   right (issue #48, item 2), not a nice-to-have. Must be internally consistent
   with the privacy policy from item 2 above — an inconsistency between the two is
   a common rejection cause.
4. **Signing key strategy decision** — confirm Play App Signing (recommended, see
   B) rather than self-managed signing before the first upload; this cannot be
   changed after the first production release without Play support intervention.
5. **Content rating questionnaire** — answered in Play Console directly; nobody but
   the account owner can submit it. Given this app records audio with no explicit
   mature content, expect a low rating, but the questionnaire itself asks about the
   app's own behavior (data collection, permissions) and must be answered
   consistently with the Data safety form (section D) — an owner task, not
   something to template blind.
6. **Foreground service justification video.** Play Console's "Foreground service
   permissions" declaration for `FOREGROUND_SERVICE_MICROPHONE` requires a short
   screen recording showing the feature being triggered by the user (see section
   F). This has to be recorded against a real build on a real device — it cannot be
   fabricated or skipped.
7. **Store listing locale decision**: category, target audience/age, countries,
   pricing (free vs. paid), and which locale(s) to publish the *store listing*
   in. Issue #44 (localization) has since **shipped and closed** (`#65`,
   `2415ae1`): the app itself now ships English as the default locale
   (`app/src/main/res/values/strings.xml`) with a complete Portuguese (Brazil)
   translation (`app/src/main/res/values-pt-rBR/strings.xml`), and
   `app/build.gradle.kts` now treats `MissingTranslation`/`ExtraTranslation` as
   lint errors, so the two string sets can't silently drift apart again. The
   remaining decision is narrower than "is the app localized" — it's a Play
   Console decision about which locale(s) to publish the *store listing text
   and screenshots* in (EN only, pt-BR only, or both), independent of the app
   itself already supporting both.
8. **Jurisdiction/consent legal review** (optional but flagged, as issue #48
   itself flags it) — whether to get actual legal advice on describing an ambient
   audio recorder given one-party vs. all-party consent laws vary by country. This
   is the owner's call, not an engineering one.

## B. Signing

**Recommendation: enroll in Play App Signing, do not self-manage the distribution
key.**

Rationale: Play App Signing lets Google hold the actual signing key used to sign
what users download, while the developer holds only an *upload key* used to
authenticate uploads to Play Console. If the upload key is ever lost or
compromised, Google Play support can reset it (a known, supported recovery path).
If a self-managed key is lost, there is no recovery — the app can never receive
another update under the same package ID. Given this is a fresh app with no
existing self-managed key in the field, there's no reason to take on the
self-managed key's single-point-of-failure risk. (https://support.google.com/googleplay/android-developer/answer/9842756, retrieved 2026-08-21)

Mechanism (describing where secrets live, not generating anything):

- Generate the upload keystore **locally on the owner's machine**, never inside
  this repo, this worktree, or a CI runner's working directory. `keytool
  -genkeypair` writes a `.jks`/`.keystore` file — that file and its two passwords
  (store password, key password) are the only secrets involved.
- The keystore file itself must never be committed, and its passwords must never
  appear in a Gradle file, a log line, or a CI echo. If CI ever needs to sign a
  release build (out of scope for this task — issue #48 groundwork only, no
  publishing automation is being wired up here), the standard pattern is:
  - Store the base64-encoded keystore and the two passwords as CI secrets
    (e.g. GitHub Actions repo/environment secrets), never as plain repo files or
    workflow-file literals.
  - Decode the keystore to a runner-local temp path at build time, reference it
    from `app/build.gradle.kts` via Gradle properties supplied by environment
    variables (`System.getenv(...)`), and never let those properties resolve to a
    logged value (`gradlew --info` / `--debug` can echo property values — avoid
    running signed release builds with verbose logging in CI).
  - Delete the decoded keystore file at the end of the job even on failure.
- `.gitignore` did **not** previously exclude keystore files or
  `keystore.properties` (only `local.properties`, `*.apk`, `*.aab`). Added
  `*.jks`, `*.keystore`, and `keystore.properties` in this PR as a guard rail,
  since no such file exists yet — cheaper to add the pattern before anyone runs
  `keytool` than after.
- Enroll the upload key with Play App Signing at first app creation in Play
  Console (Setup > App integrity). This is a one-time, Console-side action.

## C. Release build config

Current state (`app/build.gradle.kts`, checked against this branch):

```kotlin
defaultConfig {
    applicationId = "cc.machado.audioblackbox"
    minSdk = 29
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"
    ...
}
buildTypes {
    release {
        isMinifyEnabled = false
        proguardFiles(...)
    }
}
```

**Verified on this branch:**
- `./gradlew testDebugUnitTest lintDebug` — passes.
- `./gradlew bundleRelease` — **succeeds**, producing an unsigned
  `app/build/outputs/bundle/release/app-release.aab`. An AAB (unlike an installable
  release APK) does not require a signing config to build; Play Console accepts an
  unsigned upload for the *first* upload if you're enrolling in Play App Signing at
  creation time, or you sign it with the upload key before uploading. No signing
  failure to report — this is good news, not a gap.
- `release.isMinifyEnabled` is currently `false`. **I did not turn this on.**
  Reasoning below.

**Judgment calls left to the owner (not guessed at):**

1. **`versionCode`/`versionName` scheme.** Currently `versionCode = 1`,
   `versionName = "0.1"`. Play requires `versionCode` to strictly increase on every
   upload (including internal-testing-track builds) and never decrease. Two common
   schemes: (a) manual bump per release in `build.gradle.kts`, simple but relies on
   discipline; (b) derive `versionCode` from CI build number or commit count, more
   automatable but couples release numbering to CI history. Given this repo has no
   release automation yet and issue #48 explicitly defers publishing automation, I
   recommend starting manual (bump both fields by hand per release, e.g.
   `versionCode = 2`, `versionName = "0.2"` for first internal-testing upload) and
   revisiting automation once there's a release cadence to automate. Not changed in
   this PR — bumping now with no actual release imminent would just create noise.
2. **R8/minification.** Left `isMinifyEnabled = false`. The app uses
   `MediaCodec`/`MediaMuxer` (via the export path introduced in #37/#40) plus
   DataStore and Compose. None of these are unusually reflection-heavy from the
   app's own code (DataStore's own consumer ProGuard rules ship with the AAR;
   Compose's compiler-generated code is not reflection-based), so R8 is *probably*
   safe to enable with the default `proguard-android-optimize.txt`. But "probably"
   is exactly what issue #48 says not to guess on: turning on R8 changes what
   ships, and the repo's own instrumented tier (issue #35) — which is the tool that
   would actually prove it's safe against `MediaCodec`/`MediaMuxer` at runtime, not
   just at compile time — has not yet been run against a minified build. Per issue
   #48's own sequencing (device pass #29 first, R8 verification after signing is in
   place), I left this off and documented it rather than flip the flag and hope the
   unit tests catch a runtime-only ProGuard problem (they won't; `testDebugUnitTest`
   doesn't run against a minified artifact). Recommended sequence: enable
   `isMinifyEnabled = true` in a follow-up change, then run the instrumented tier
   (`connectedAndroidTest` equivalent from #35's CI job) against that release
   variant specifically, before shipping it to any testing track.
3. **`debuggable`** is already `false` for the `release` build type — this is
   AGP's default when a build type doesn't set `isDebuggable`, and `release` here
   doesn't touch it, so no change needed. Confirmed no `isDebuggable = true`
   anywhere in the build files.
4. **No debug-only code found shipping to release.** `debugImplementation` is
   correctly scoped for `androidx.ui.tooling`/`ui.test.manifest` (debug-only compose
   tooling), and a grep of `app/src/main` found no logging of absolute file paths
   or leftover test hooks. Nothing to change here, but worth re-checking after any
   future PR that touches the export path, since that's exactly where a stray
   `Log.d(path)` would land.
5. **`resourceShrinking`** — not enabled, tied to the same R8 decision above
   (`isShrinkResources` requires `isMinifyEnabled`); revisit together.

No `app/build.gradle.kts` edits were made in this PR — every candidate change
was a judgment call the owner should make with the instrumented-tier safety net
in place first, per issue #48's own stated sequencing.

## D. Data safety + permissions declarations

### Data safety form (draft answers)

- **Does your app collect or share any of the required user data types?** Yes —
  audio.
- **Audio (Audio recordings)**
  - Collected: **Yes.** (Play's own Data safety guidance has a "not in scope
    for data collection" carve-out for data "processed locally on the user's
    device and not sent off device," which, read literally, could arguably let
    this app answer "No" here, since audio never leaves the device. I chose
    the conservative **Yes** anyway: over-disclosing isn't a policy violation,
    under-disclosing is, and "Yes, collected, but never shared, never
    transmitted, on-device only" is a stronger and safer story to tell a
    reviewer than omitting the data type entirely. Flagging the alternative
    reading here so the owner isn't blindsided if a reviewer raises it.)
  - Shared with third parties: **No.**
  - Processed ephemerally: **No** (it's not ephemeral in the Play Console technical
    sense — the export feature persists it to device storage on user action — but
    it *is* on-device only; use the "processed ephemerally" toggle only if Play's
    form defines it strictly as "not stored," which the export feature violates by
    design once the user saves. Answer **No** to processed-ephemerally and instead
    rely on the purpose/storage answers below to convey the same "we don't keep it
    unless you ask" story).
  - Purpose: **App functionality.** (Not analytics, not advertising, not
    personalization — the sole reason audio is captured is that it's the app's
    entire function.)
  - Is this data required or optional: **Required** (the app cannot function
    without microphone access).
  - Data collected is **not transmitted off-device** — there is no network
    request in the codebase transmitting audio or any other data (verified: no
    networking library/dependency in `app/build.gradle.kts`, no `http`/`https`
    references in `app/src/main`). This is the strongest fact in the whole
    submission — state it identically in the Data safety form and the privacy
    policy so they can't be read as contradicting each other. Issue #48 flags
    inconsistency between the two as a common rejection cause; this is is the
    place to get it verbatim-consistent.
  - Is data encrypted in transit: **N/A — not transmitted.**
  - Can users request deletion: **Yes** — describe as: uninstalling the app
    deletes the in-memory buffer and any saved recordings remain in the user's own
    `Recordings/Blackbox/`/`Music/Blackbox/` folder (i.e. it's the user's own
    device storage, not app-controlled storage the developer needs a deletion
    mechanism for). Issue #7's in-app gallery has since merged (`#61`) and
    includes a delete action — that's the direct in-app path to mention on the
    form.
- **Security practices**: no data is transmitted, so most of this section
  (encryption in transit, independent security review) is not applicable — do not
  claim security practices for data flows that don't exist; select "data isn't
  transmitted" where the form allows it rather than over-claiming encryption.

### Permissions declared in the manifest (for reference, already correct in code)

- `RECORD_AUDIO` — core function.
- `POST_NOTIFICATIONS` — the persistent foreground-service notification.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` — required for a
  background audio-capturing service on API 29+, already declared correctly with
  `android:foregroundServiceType="microphone"` on `RecorderService` in
  `AndroidManifest.xml`.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — for the OEM battery-killer bypass flow
  mentioned in the README; this is not a "restricted" or "dangerous" permission
  requiring Play Console declaration, but be ready to explain it in the same
  functionality description used for the foreground service justification, since
  reviewers read them together.

### Foreground service permissions declaration (Play Console > App content)

Because `targetSdk = 36`, Play Console requires, for the `microphone`
foreground-service type:
- Functionality description (what the service does).
- User impact if the task were interrupted or deferred.
- A short screen-recorded video demonstrating the feature being started by the
  user.
- Selection from Google's provided use-case list for `TYPE_MICROPHONE`, which —
  as currently published — is a single option: **"Background Audio Access":
  "Capture audio input, for example, voice commands for virtual assistant without
  saving, voice recording."**
  (https://support.google.com/googleplay/android-developer/answer/13392821,
  retrieved 2026-08-21)

Draft functionality description for that form:

> Audio Blackbox continuously buffers a short rolling window (5, 15, 30, or 60
> minutes, user-configurable, per `AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES`)
> of ambient audio in device RAM, similar to a dashcam. Nothing
> is written to disk automatically. The user starts and stops capture explicitly
> via an in-app toggle, sees a persistent notification the entire time capture is
> active, and can export the recent buffer to a file on demand. If the foreground
> service were killed or the task deferred, the rolling buffer would be lost and
> the user would be unable to recover audio from the preceding minutes — the
> entire value of the app depends on the buffer surviving in the background
> uninterrupted.

Note the "voice recording" wording in Google's own use-case option is generic
enough to plausibly cover this, but Google's example phrasing ("voice commands for
virtual assistant") is visibly aimed at assistant-style apps, not standing
ambient-audio dashcams. See section F — this is a genuine gray area, not a settled
match.

### Prominent disclosure (separate from the Data safety form and from the OS
runtime permission dialog)

The existing onboarding screen already shows a legal/consent notice
(`onboarding_legal_title`/`onboarding_legal_body` in
`app/src/main/res/values/strings.xml`) before recording starts. Per Play's
disclosure policy, prominent disclosure must state, in-app, before the permission
is requested: **why** the capability is needed, **what** data is involved, and
**how** it's used — in plain, ~13-year-old reading-level language, with the user
able to explicitly accept or decline rather than just tapping through.
(https://support.google.com/googleplay/android-developer/answer/11150561,
retrieved 2026-08-21)

The current English-default copy (`app/src/main/res/values/strings.xml`,
`onboarding_legal_body`; the app now ships this bilingual — see section A.7 — and
the Portuguese `values-pt-rBR/strings.xml` copy carries the same content):

> "Audio Blackbox continuously records audio in the background. Recording
> conversations may require the consent of everyone involved, depending on the
> laws of your jurisdiction. You are responsible for using this app in
> accordance with applicable law."

This covers the legal/consent angle but **does not clearly state the "what" and
"how"** Play's disclosure policy asks for: that audio is kept only in a rolling
in-memory buffer, is never transmitted anywhere, and is written to disk only when
the user explicitly exports it. I'd recommend adding a sentence to that effect —
but `app/src/main/res/values/strings.xml` is explicitly out of scope for this PR
(contended with an open PR), so this is a write-up, not an edit. Suggested addition
for whoever next touches that string:

> "Audio stays only in this device's memory and is never sent anywhere. It's saved
> to a file only when you tap Save."

I did not verify whether the current onboarding screen requires an explicit
two-option accept/decline action (vs. a single "I understand, continue" /
"Entendi, continuar" button, per locale) — that's a UI-flow check, not a
strings check, and is listed as an open question in section F.

## E. Store listing

### Short description (draft, ≤80 characters)

> "Continuous ambient audio buffer. Save the last few minutes, on demand."

(79 characters — Play's short-description limit is 80.)

### Full description (draft, ≤4000 characters)

> Audio Blackbox works like a dashcam, but for sound. It continuously holds the
> last several minutes of ambient audio in memory — nothing is written to disk
> until you decide to keep it.
>
> Start capture, and the app keeps a rolling buffer of the most recent audio
> (5, 15, 30, or 60 minutes, your choice) in RAM, always overwriting the oldest
> audio as new audio comes in. If something worth keeping just happened, tap Save and the last few
> minutes are exported as an audio file you can play back or share — capturing the
> past, after the fact.
>
> A persistent notification shows whenever the app is actively capturing, and you
> control the engine with a single on-device switch. Capture pauses automatically
> during phone calls and resumes when the call ends, without losing track of time.
>
> Privacy: audio never leaves your device. There is no server, no account, no
> network connection of any kind — the entire buffer lives in your phone's memory
> and is only ever written to storage when you explicitly export it.
>
> Please note: recording conversations may require the consent of the people
> involved, depending on the laws where you are. You're responsible for using this
> app in accordance with applicable law.

This is a draft for the owner to approve and adjust in tone. It's written in
English only, matching the app's default locale. Since the app itself now
ships a complete Portuguese (Brazil) translation (`#65`, section A.7 above), a
pt-BR store listing is a reasonable pairing if the owner decides to publish
that locale — I did not draft one, since translating store-listing copy well
is a judgment call about tone and idiom that shouldn't be templated from a
machine translation of the paragraph above. It intentionally leads with the
privacy story per issue #48's guidance, since it's the strongest, most
verifiable claim available.

### Graphical assets — the 512x512 Play listing icon is already done; feature graphic and screenshots are not

Re-verified every row below directly against the live spec page
(https://support.google.com/googleplay/android-developer/answer/9866151,
retrieved 2026-08-21) after a prior version of this table swapped the icon and
feature-graphic alpha requirements — corrected here:

| Asset | Spec (verified against the live page) | Status |
|---|---|---|
| App icon (launcher, in-app) | 512x512, 32-bit PNG **with alpha** | **Exists** — issue #49/#58, merged. This is the adaptive-icon resource baked into the APK/AAB, not the separate Console upload below. |
| **Play Store listing icon** | **512x512 PNG, 32-bit, with alpha** — uploaded separately in Play Console | **Already exists**: `docs/design/store/ic_launcher_store_512.png`. Verified directly: 512×512, 8-bit, PNG colour type 6 (RGBA) — i.e. 32-bit with alpha, which matches the real spec above. (A prior draft of this doc incorrectly said this asset needed to be exported "with no alpha" and claimed it didn't exist yet — both wrong; see PR review history. The existing file already satisfies the correct spec.) Note `docs/design/store/ic_store_candidateA_not_shipped_512.png` also exists in the same directory but its filename says `not_shipped` — do not use it. |
| **Feature graphic** | **1024x500, JPEG or 24-bit PNG, no alpha** | **Does not exist.** No source art for this anywhere in the repo; needs original design work, not just a resize of the launcher icon. |
| Phone screenshots | Min 2, up to 8; each side between 320px and 3840px (max side ≤ 2× the min side) | **Does not exist.** Needs a working, populated app (ideally post-#7 gallery, post-#29 device-verified export) to screenshot meaningfully. |
| Short/full description | Text, see above | Draft above. |
| Promo video (optional) | YouTube link | Not required; skip for launch. |

So the actual gap is narrower than a prior version of this doc stated: the
512x512 Play Console listing icon is done and correct. What's still missing is
the feature graphic and phone screenshots.

## F. Open questions / honest rejection-risk assessment

**This is the section to read most carefully.**

1. **The foreground-service microphone use-case fit is a genuine gray area, not a
   settled match.** Google's own published use-case option for
   `FOREGROUND_SERVICE_TYPE_MICROPHONE` is titled "Background Audio Access" and
   its example text is "voice commands for virtual assistant without saving, voice
   recording" (https://support.google.com/googleplay/android-developer/answer/13392821,
   retrieved 2026-08-21). "Voice recording" as a phrase is broad enough to plausibly
   include this app, but every example Google gives alongside it (assistant voice
   commands) describes short, triggered, task-scoped microphone access, not a
   continuously-running ambient buffer with an always-visible notification for
   potentially hours at a time. There is no published Play policy text I could find
   that explicitly blesses or explicitly bans an "always-on ambient audio dashcam"
   category the way there's explicit policy for, say, call-recorder apps (see
   point 3). That absence cuts both ways: it's not pre-approved, but it's not
   pre-banned either. **This is decided case-by-case by a human reviewer, and the
   justification text plus demo video (section D) is doing real work — a vague or
   generic submission here has real rejection risk. A submission that leads with
   "user explicitly starts/stops it, sees a persistent notification the whole time,
   and nothing is saved without a separate explicit action" is the strongest
   framing available, and is what I drafted in section D.**
2. **This app is not, technically, a "call recorder"** under Play's Device and
   Network Abuse policy's SMS/Call Log restricted-permissions section, which lists
   "Call recorder" as an explicitly invalid use case for apps requesting
   `READ_CALL_LOG`/SMS permissions
   (https://support.google.com/googleplay/android-developer/answer/10208820,
   retrieved 2026-08-21). Audio Blackbox requests neither SMS nor Call Log
   permissions and does not target phone-call audio specifically — it's ambient
   microphone capture that happens to pause during calls (a courtesy/robustness
   behavior, not a call-recording feature). I read this as **not** falling under
   that specific ban, but it's close enough in spirit that I'd flag it explicitly
   in the Console submission notes rather than let a reviewer draw their own
   conclusion — silence here invites the wrong assumption.
3. **RECORD_AUDIO is not on Play's list of "restricted permissions"** requiring a
   separate Permissions Declaration Form. The correct source for this is Play's
   "Permissions and APIs that Access Sensitive Information" hub page
   (https://support.google.com/googleplay/android-developer/answer/9888170,
   retrieved 2026-08-21 — corrected citation; a prior version of this doc pointed
   here at `answer/10964491`, which is actually the single "Use of the
   AccessibilityService API" sub-article and does not itself enumerate the
   category list, so it didn't support this claim even though the claim was
   right). The hub page's enumerated categories are: SMS and Call Log
   Permissions, Location Permissions, All Files Access, Package (App) Visibility,
   Accessibility API, Request Install Packages, Body Sensor Permissions, Health
   Connect by Android Permissions, VPN Service, Exact Alarm, Full-Screen Intent,
   and the Age Signals API — RECORD_AUDIO/microphone appears in none of them.
   This is genuinely good news: it means the main review gate is the
   foreground-service-type declaration (point 1) and the general
   Sensitive-permissions/prominent-disclosure policy (section D), not an additional
   restricted-permission form.
4. **I could not verify, from documentation, whether Google's human review team
   applies extra scrutiny to "ambient recording" apps as a category beyond what's
   written in the foreground-service policy** — Play's public policy documentation
   doesn't have a dedicated page for this app archetype the way it does for VPNs,
   accessibility apps, or call recorders. Anecdotally (not cited, because I have no
   authoritative source for this and won't cite one I can't stand behind), apps
   that request always-on microphone access have a track record of slower review
   and higher rejection-on-first-submission rates industry-wide, even when
   ultimately approved. **Budget for at least one rejection-and-resubmission cycle
   as the expected case, not the worst case.**
5. **Not resolved by this document**: whether the current onboarding screen
   (issue #19) presents a true two-option accept/decline choice, or a single
   acknowledgment button — Play's prominent-disclosure policy specifically wants
   the former. This needs a UI walkthrough, not a strings-file read, and
   `app/src/main/res/values/strings.xml` is off-limits in this PR regardless.
6. **Not resolved**: privacy-policy hosting location and URL (section A.2) — owner
   decision, blocking everything downstream of it in Play Console.
7. **Not resolved**: launch locale(s) — the onboarding legal copy is Portuguese-only
   today; the store listing draft above is English. Pick one consistent set before
   submission (ties to issue #44).
8. **Not attempted in this task, per its own scope**: setting up
   `gradle-play-publisher` or any service-account-based publishing automation.
   That's explicitly deferred to a later, separately authorized step.

---

**Sources.** Retrieval dates below reflect the most recent re-check of each URL
(initial pass 2026-08-21; a second pass the same day re-fetched every citation
after review feedback, correcting one wrong citation and confirming the rest).
Play policy changes — re-verify before submission if this document is stale.

- Foreground service permissions / `TYPE_MICROPHONE` declaration — verified,
  quoted text matches the live page:
  https://support.google.com/googleplay/android-developer/answer/13392821
  (retrieved 2026-08-21)
- Permissions and APIs that Access Sensitive Information — the actual hub page
  that enumerates restricted-permission categories and confirms RECORD_AUDIO is
  not one of them (corrected citation, see point F.3):
  https://support.google.com/googleplay/android-developer/answer/9888170
  (retrieved 2026-08-21)
- SMS/Call Log restricted permissions, "Call recorder" invalid use case —
  verified:
  https://support.google.com/googleplay/android-developer/answer/10208820
  (retrieved 2026-08-21)
- Prominent disclosure & consent requirement — re-fetched and verified, quoted
  text matches:
  https://support.google.com/googleplay/android-developer/answer/11150561
  (retrieved 2026-08-21)
- Data safety form guidance — re-fetched and verified (audio-files data type,
  purposes list, and the "processed ephemerally" definition all confirmed to
  match the drafted answers in section D):
  https://support.google.com/googleplay/android-developer/answer/10787469
  (retrieved 2026-08-21)
- Play App Signing — re-fetched and verified (upload key vs. app signing key
  split, upload-key-reset recovery path both confirmed):
  https://support.google.com/googleplay/android-developer/answer/9842756
  (retrieved 2026-08-21)
- Developer account verification — **unresolved.** The previously cited
  `answer/13487100` 404s on re-check. I was not able to find the correct URL for
  this specific claim within this task's budget; see section A item 1. The
  underlying claim (identity verification is required before publishing) is a
  known, current Play Console requirement, but it is not backed by a working
  citation here — verify directly in Play Console rather than trusting this
  document on this one point.
- Graphic asset specifications — re-fetched and verified line-by-line against
  the icon/feature-graphic/screenshot rows in section E (this is the page whose
  icon-vs-feature-graphic alpha requirements were previously transposed; both
  rows are now corrected and match this page):
  https://support.google.com/googleplay/android-developer/answer/9866151
  (retrieved 2026-08-21)
