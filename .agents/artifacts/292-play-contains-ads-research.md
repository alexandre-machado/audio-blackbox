# Research brief: Play listing shows "Contains ads" (issue #292)

- Attributed to: `@research`
- Source comment: https://github.com/alexandre-machado/audio-blackbox/issues/292#issuecomment-5518417591
- Issue: #292
- Persisted: 2026-09-02

The brief below is reproduced verbatim (citations, quotes, and confidence markers unchanged) from the linked comment.

---

## Research brief: why does the Play listing still show "Contains ads" when the app ships no ad SDK

**TL;DR** — Google's own help page for the Ads declaration says plainly that the "Contains ads" label comes from **both** the developer's Yes/No declaration on the App content page **and** Google's own automated verification, which can display the label "if appropriate" independent of what the developer declared. Declaration alone (option a) is therefore necessary but not provably sufficient. I found no official evidence tying the Data safety/advertising questionnaire to this specific label (option b looks like a dead end). I found real, but dated and framework-adjacent, developer reports that Firebase Analytics's transitive dependency on `play-services-ads-identifier` has triggered Google's automated "we found ad SDKs" detection for other apps — directly relevant given this app's pre-#119 history. Whether an old build still live on some track is what's keeping the badge on *this* app is not confirmed by any source I read, but it's consistent with everything Google's own docs say and is checkable in Console. Confidence: **medium** on the mechanism (well-sourced from Google's own page), **low** on which exact cause applies to this app (needs the user to actually look, which is the next step below).

### Findings

- **The declaration lives at Play Console → Policy and programs → App content → "Ads" section → Start (first time) / Manage (if already declared) → Yes/No.** [verified] — "Prepare your app for review", Play Console Help, https://support.google.com/googleplay/android-developer/answer/9859455?hl=en
  > "Open Play Console and go to the App content page (Policy and programs > App content)... Under 'Ads,' select Start" (or "Manage" if previously declared), then "select Yes or No."

- **Google states outright that this is a hybrid of declaration and automated verification, not declaration alone.** [reported, Google's own official page] — same source.
  > "While you're responsible for accurately declaring ad presence in your apps, Google may verify this at any time and display the 'Contains ads' label if appropriate."
  This is the single most decision-relevant sentence in the corpus: it directly answers sub-question 1 and rules against option (a) being guaranteed sufficient on its own, and against option (d)'s strict reading ("cannot be cleared by declaration alone") — declaration is still the primary lever, but Google reserves the right to override it.

- **What counts as "ads" for this label is defined broadly by Google, including third-party SDKs.** [verified] — same source.
  > "This includes ads delivered through third-party ad SDKs (Software Development Kit), display ads, native ads, and/or banner ads."
  There's also a named carve-out for benign cross-promotion ("More Apps" style menu links) that should NOT trigger the label — not relevant here since this app has no such feature, but useful to rule out a false-positive cause.

- **General Play Console app-change processing (not ads-specific) is documented as taking hours to days.** [reported — general timeline, not verified specific to the Ads declaration] — "Control when app changes are reviewed and published", Play Console Help, https://support.google.com/googleplay/android-developer/answer/9859654?hl=en
  > "All app changes need to be processed before they can be published. Processing can take a few hours or up to seven days (or longer in exceptional cases)."
  I could not find a page stating this exact number applies to the Ads declaration specifically, or that a new binary/release is required to make a declaration-only change take effect — treat the "few hours–7 days" figure as the best available proxy, not a confirmed number for this exact toggle.

- **Data safety section documentation shows no connection to the "Contains ads" label.** [verified — absence of a claim, not presence] — "Provide information for Google Play's Data safety section", Play Console Help, https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
  The page discusses data collection/sharing disclosures (including "Advertising or marketing" as a data-use purpose) but nowhere ties that questionnaire to the store-listing ads badge. This is negative evidence against option (b): fixing Data safety answers is good practice independently, but there's no documented mechanism by which it affects the "Contains ads" label.

- **"Latest releases and bundles" is the current official page for checking what's live on each track.** [verified] — "Prepare and roll out a release", Play Console Help, https://support.google.com/googleplay/android-developer/answer/9859348?hl=en
  > "your app's Latest releases and bundles page helps you monitor releases in one location. Use this page to monitor app availability across tracks, view country and region availability, and select individual releases to view details."
  Navigation: **Test and release → Latest releases and bundles**. This is the concrete tool for sub-question 4 — it lists what's live per track (internal/closed/open/production) and lets you drill into each release's details.

- **Firebase Analytics has, for other developers, pulled in `play-services-ads-identifier` as a transitive dependency and triggered Google's automated ad-SDK detection even with no ads and no direct AdMob usage.** [reported, developer account, dated 2022-03-25, Flutter/`firebase_analytics` ecosystem, not this repo's native Gradle stack] — GitHub issue, https://github.com/firebase/flutterfire/issues/8337
  > "I got a 'We found ad SDKs in your app' warning from Google Play, even though my app has no ads." — root cause identified as `firebase_analytics` depending on `play-services-ads-identifier`.
  Same report cross-posted, unresolved as of the last update: https://github.com/firebase/flutterfire/discussions/8289 ("Are there any new developments for issue?" — no reply recorded).
  This is corroboration only (not a Google source, not this app, 4+ years old, different framework) but it directly supports why this app's pre-#119 history matters: Firebase Analytics is not itself an ads SDK, but it has historically shipped a dependency chain that Google's scanner treats as ad-adjacent.

- **No official Google documentation was found describing the scanning mechanism's exact scope** — which SDK signatures it checks for, whether it scans every live track's binary or just the latest production release, or how often it re-runs. This is a genuine gap, not an inference I'm willing to make. [insufficient public evidence]

### Implications for the decision

- **(a) Flip the declaration and nothing else** — necessary, not proven sufficient. Google's own wording ("Google may verify this at any time and display the label if appropriate") means a manual "No" can be overridden by automated detection. Do this step regardless, but don't stop here.
- **(b) Fix Data safety / advertising questionnaire** — no evidence this affects the badge at all. Worth doing for its own sake (accuracy), but not a probable fix for this specific issue; don't spend the propagation-delay budget on it expecting the badge to move.
- **(c) Ship a new release because an old build with an ads-adjacent SDK is still live on some track** — plausible and directly checkable, not confirmed for this app. This app carried Firebase Analytics before #119, and Firebase Analytics has a documented (elsewhere) history of tripping Google's ad-SDK scanner via a transitive dependency. If any track (especially internal/closed testing, which tend to lag behind production) is still serving a pre-#119 build, that build is a live candidate for the trigger. This is the one candidate with a concrete, cheap way to rule in or out: open Latest releases and bundles and check the version/commit live on every track.
- **(d) Automatic detection only, declaration is powerless** — too strong a claim; Google's page frames it as declaration-plus-override, not detection-only. Reject the strict form of (d), but keep its spirit: don't assume flipping the toggle is guaranteed to be the whole fix.

**Concrete next step for the user, in order:**
1. Play Console → Policy and programs → App content → Ads → Manage → confirm/set to "No."
2. Play Console → Test and release → Latest releases and bundles → check the version live on internal, closed, open, and production tracks; confirm every live build postdates the #119 Firebase Analytics removal (and has no `play-services-ads-identifier` or other ad-adjacent transitive dependency — this can be checked by inspecting the AAB/APK's merged manifest or dependency tree for that specific build, not just current `main`).
3. If any track is still serving a pre-#119 build, replace it (new release to that track) rather than assuming the declaration toggle alone will clear the badge.
4. Wait for the general app-changes processing window (documented as hours up to ~7 days, not ads-specific) before concluding it didn't work; if the label persists well past that and every track is confirmed clean, that's the point to contact Play Console support directly, per Google's own suggestion that a misapplied label can be raised with them.
5. Treat Data safety/advertising questionnaire corrections as a separate, independently-good task, not a fix for this badge.

### Coverage & gaps

- **Searched:** Play Console ads declaration mechanics; "Contains ads" label cause; Data safety/advertising ID relationship to the label; propagation/review timing for content declarations; automated ad-SDK detection scope; checking live builds per track; Firebase Analytics and ad-SDK detection history.
- **Read (fetched and content-verified):** Play Console Help "Prepare your app for review" (answer/9859455, ads section + verification quote), "Control when app changes are reviewed and published" (answer/9859654), "Provide information for Google Play's Data safety section" (answer/10787469), "Prepare and roll out a release" (answer/9859348, Latest releases and bundles), GitHub `firebase/flutterfire` issue #8337 and discussion #8289.
- **Fetched but unproductive / could not confirm claims from:** Play Console Help "Ads" policy page (answer/9857753) — fetched twice, did not contain the quotes attributed to it by search snippets, so nothing from it is cited above; answer/6071007 — returned only navigation chrome, no substantive content.
- **Not read (blocked):** Two Google Play Developer Community threads directly on point — "Listing says 'Contains ads' we don't" and "App says `contains ads` but it doesn't!" — both returned only truncated navigation/header content through the fetch tool (likely JS-rendered), so any first-hand Google-staff responses in those threads are unread. If the user has Play Console access, these are worth opening directly.
- **NOT found (insufficient public evidence):**
  - No official doc detailing the automated scanner's exact scope (which SDK signatures, which tracks/builds it evaluates, refresh cadence).
  - No official, ads-specific number for how long a declaration change takes to reflect on the live storefront (only the general app-changes figure above).
  - No official statement on whether the App content → Ads declaration is a single app-wide setting independent of which build is live per track, or whether it's re-evaluated per release. Not confirmed either way.
  - No first-party Google confirmation that a stale build with an old dependency, sitting inactive on a testing track, is what's keeping this specific app's badge on — this is the strongest remaining gap and exactly what step 2 above is for.
- **Staleness / bias notes:** The Firebase/`play-services-ads-identifier` corroboration is from 2022, in the Flutter/`firebase_analytics` package ecosystem rather than this repo's native Android Gradle stack — cited as an existence proof that this dependency chain has tripped Google's detector, not as proof it explains this app's badge. All Play Console Help pages I read did not expose a visible "last updated" date in the fetched content, so their currency relative to the console UI's repeated reorganizations could not be independently confirmed beyond the fact they are the current live URLs as of this research pass (2026-09-02).

---

## Dependency-graph verification (`@dev`)

The brief above correctly notes that grepping build files for an ad SDK is not sufficient evidence, since a dependency like `play-services-ads-identifier` can arrive transitively (e.g. via Firebase Analytics, removed in #119) without ever appearing in `app/build.gradle.kts`. This section records the actual, resolved release dependency graph for current `main`, to close that gap for *this* codebase's current state.

**Command run** (offline, from repo root):

```
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --offline
```

Result: `BUILD SUCCESSFUL` — Gradle resolved the full `releaseRuntimeClasspath` configuration for `:app` offline (no network resolution needed; all artifacts were already present locally), and printed the complete tree (724 lines, ending in the standard Gradle legend and `BUILD SUCCESSFUL in 9s`, i.e. not truncated).

**Search terms applied to the full report** (case-insensitive):

- `ads`
- `ads-identifier`
- `play-services-ads`
- `admob`
- `firebase`
- `play-services-measurement`

Combined as a single pass: `ads-identifier|play-services-ads|admob|firebase|play-services-measurement` (case-insensitive) — **zero matches** in the 724-line report.

A broader `google` search (case-insensitive) was also run as a cross-check, since any ads/Firebase artifact would necessarily be under the `com.google.*` group: it returned exactly one hit, `com.google.guava:listenablefuture:1.0`, which is a Guava utility artifact unrelated to ads or Firebase, and no other `com.google.*` group appears anywhere on the classpath.

**Verdict:** as of this pass, on current `main`, no ads-related artifact (`play-services-ads-identifier`, `play-services-ads`, AdMob, Firebase, or `play-services-measurement`) is present anywhere on the app's release runtime classpath, transitively or otherwise. This is evidence about the *current build*, not about what is live on any Play Console track — per the brief's own step 2, a pre-#119 build could still be live on some track (most likely internal/closed testing) independent of what `main` resolves to today, and this dependency report cannot speak to that; only inspecting the actual live AAB/APK per track in Play Console can.
