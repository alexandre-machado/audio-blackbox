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

---

## Second research pass: navigation path, tab split, and permission gaps (`@research`)

- Attributed to: `@research`
- Source comment: https://github.com/alexandre-machado/audio-blackbox/issues/292#issuecomment-5530439043
- Issue: #292
- Persisted: 2026-09-03

The section below is reproduced verbatim (citations, quotes, and confidence markers unchanged) from the linked comment.

### Research brief: where does the Ads declaration live in today's Play Console, and why is the owner not finding it

**TL;DR** — Google's own current help pages still describe the Ads Yes/No toggle at Play Console → App content → "Ads" section, but the two official pages disagree on the parent label ("Policy and programs" vs plain "Policy"), and neither states an app-release-track prerequisite. The most likely reason the owner can't find it isn't that it moved: the App content page has two tabs, "Needs attention" and "Actioned," and a previously-made declaration sits under **Actioned**, not the default "Needs attention" view — if they're only looking at the front tab, an existing "Yes" answer looks invisible rather than missing. Separately, Google states plainly that the "Contains ads" label can come from its own automated verification "if appropriate," independent of the developer's declared answer, and neither the exact scanning scope nor an ads-specific propagation time for label changes is documented anywhere I found. Confidence: **medium** on navigation/tabs (two independent official pages agree on substance), **low** on permissions specifics (the permission descriptions don't name "Ads" at all — genuine gap), **low** on timing (no ads-specific figure exists).

#### Findings

1. **Current official navigation path, confirmed by fetching the live page's HTML directly (not just a search snippet).** [reported — official Google doc, fetched 2026-09-03, no visible revision date on the page itself] — "Prepare your app for review," Play Console Help, https://support.google.com/googleplay/android-developer/answer/9859455?hl=en
   > "Open Play Console and go to the App content page (Policy and programs > App content). Under "Ads," select Start. Note: If you've previously declared whether or not your app contains ads and you want to make changes, you'll see and select Manage instead of Start."

2. **A second, currently-live official page gives a different label for the same parent nav item.** [reported — official Google doc, fetched 2026-09-03] — "Manage target audience and app content settings," Play Console Help, https://support.google.com/googleplay/android-developer/answer/9867159?hl=en
   > "Open Play Console and go to the App content page (Policy > App content). Under "Target audience and content," click Start."
   Both pages point at the same "App content" destination page, but one says the parent is "Policy and programs" and the other just "Policy." I cannot resolve which string is literally correct in the current UI without console access — flagging this as a real, sourced discrepancy in Google's own documentation, not a guess on my part. If the owner is scanning the left nav for an exact string match on either label and the console currently shows a third wording, that alone would explain "couldn't find it."

3. **The App content page has two tabs, and a previously-completed declaration is filed under the tab a first-time visitor wouldn't default to.** [reported — official Google doc, fetched 2026-09-03] — same source as #1 (answer/9859455).
   > "The App content page has two tabs: Needs attention: Policy declarations that require your attention are shown here... Actioned: Policy declarations that you've actioned are shown here."
   This is the single most actionable finding for "I can't find the option": if Ads was declared at some point (even accidentally, or inherited from an early submission before the app's ad SDK history was cleaned up per #119), it will show "Manage" under **Actioned**, not present itself as a fresh "Start" task under "Needs attention." A search snippet (community-sourced, unverified by me via direct fetch) independently describes the same Actioned/Needs-attention split as the fix for "can't find the option," which is consistent corroboration but not an independent citation. This is currently the leading explanation for the option appearing absent.

4. **Google states the "Contains ads" label is not fully controlled by the developer's declaration.** [reported — official Google doc, fetched 2026-09-03] — same source (answer/9859455).
   > "While you're responsible for accurately declaring ad presence in your apps, Google may verify this at any time and display the "Contains ads" label if appropriate. If you think your app has been incorrectly labeled by our system, contact our support team for help."
   No page I found documents the scanning mechanism's scope (which SDK signatures, which tracks/builds, refresh cadence). This is unchanged from the team's prior research pass on this issue and remains an open gap, not something I'm willing to infer.

5. **Play Console access is governed by account owner/admin/user roles with separate account-level and app-level permissions, but the two permissions closest to "App content" don't name Ads explicitly in their official descriptions.** [reported — official Google doc, fetched 2026-09-03] — "Add developer account users and manage permissions," Play Console Help, https://support.google.com/googleplay/android-developer/answer/9844686?hl=en
   > "Manage policy declarations ... Edit and submit policy declarations, for example, the Data safety section and permission declarations."
   > "Manage store presence ... Edit store listing information (including text and images) ... Edit distribution information, including content rating."
   Neither permission's description mentions the Ads Yes/No declaration by name. Since the "Contains ads" label lives on the store listing, "Manage store presence" is the more plausible gate by inference, but I could not find a sentence anywhere that states this outright — **treat this as an open gap, not a confirmed permission mapping.**

6. **A read-only permission exists and would plausibly explain a greyed-out control, though the page never uses the word "grey."** [reported — same source as #5]
   > "View app information (read-only) ... Read-only access to all app information ... This permission is available to users with 'App' access only."
   A user granted only this permission (no "Manage policy declarations" and no "Manage store presence") would, per the described rights, be able to see the App content page's declarations but not submit changes to them — which is the closest documented mechanism I found for "the option is there but I can't interact with it." This is [inference — my own reasoning] built on the stated rights table, not a direct quote saying declarations are disabled/greyed for this role.

7. **No official page states whether the App content/Ads section requires the app to have at least one release on some track.** [insufficient public evidence] Searched specifically for this; found none of the pages I read (answer/9859455, answer/9867159, answer/9844686) address app-release-state as a precondition for the Ads section appearing. I am not willing to assert this either way.

8. **No evidence that the "Designed for Families" program or any other developer program relocates the base Ads Yes/No control.** [reported — official Google doc, fetched 2026-09-03] — "Manage target audience and app content settings," same source as #2, plus "Families Self-Certified Ads SDK Program," Play Console Help, https://support.google.com/googleplay/android-developer/answer/9900633?hl=en
   Family-targeted apps get an *additional*, nested ads-SDK certification question inside the separate "Target audience and content" section — which itself is gated on having already answered the base Ads question — but nothing suggests the base Ads toggle itself moves for these apps. This is additive complexity, not relocation.

9. **No ads-specific propagation/processing time exists; the only documented figure is for app binary changes in general.** [reported — official Google doc, fetched 2026-09-03] — "Control when app changes are reviewed and published," Play Console Help, https://support.google.com/googleplay/android-developer/answer/9859654?hl=en
   > "All app changes need to be processed before they can be published. Processing can take a few hours or up to seven days (or longer in exceptional cases), as it depends on the review time that your app is subject to."
   Nothing on this page or on answer/9859455 ties this figure to a declaration-only change (no new binary). Treat "hours to 7 days" as the best available proxy, not a confirmed number for the Ads toggle.

10. **Two community threads directly on point ("Listing says 'Contains ads' we don't," and a related thread on the developer community) could not be read.** [insufficient public evidence — tooling limitation, not a finding] Both https://support.google.com/googleplay/android-developer/thread/263523990/listing-says-contains-ads-we-don-t and a related thread render their substantive content via client-side JS; both direct fetch and the fetch-tool summarizer returned only page chrome (fonts, analytics, nav) with no thread body. If the owner has Play Console/browser access, these are worth opening directly — any first-hand Google-staff replies in them are unread by me.

#### Implications for the decision

- **"The exact current navigation path"**: use Play Console → App content page, reached via a left-nav item Google's own docs disagree on labeling ("Policy and programs" vs "Policy") → "Ads" section → "Manage" (not "Start," since this app has presumably been declared before). **First move: check the "Actioned" tab on the App content page**, since "Manage" only appears there or wherever the existing answer is filed — this is more likely why the owner "couldn't find" it than a UI relocation.
- **"Why the option can be absent or greyed out"**: the two documented permissions in this territory ("Manage policy declarations," "Manage store presence") don't name Ads explicitly — verify the owner's own Play Console role has at least one of these, ideally "Manage store presence" by inference, and rule out being limited to "View app information (read-only)." No evidence found either way on whether zero released builds hides the section.
- **"What else can put the badge on a listing"**: (1) an existing "Yes" answer sitting in Actioned from before #119's Firebase-Analytics cleanup — cheap to check, matches this option's own investigation history; (2) Google's own automated scanner overriding a "No" declaration, scope undocumented, only remedy is Google support contact per their own suggested path; (3) a stale build with an ad-adjacent SDK still live on some track — checkable via Latest releases and bundles (established in the prior research pass, not re-verified here). No documented evidence of a separate storefront-badge cache distinct from normal change-processing time.
- **"How long the badge takes to disappear"**: no ads-specific number exists in Google's documentation; the only proxy is the general app-changes figure (hours to ~7 days). The one visible signal for "has the declaration itself been accepted" is the item's tab location on App content (Needs attention → Actioned); nothing documents a separate signal for the public storefront badge refreshing.

#### Not established

- No official source confirms or denies a release-track prerequisite for the Ads section to appear (see finding #7).
- Two on-point community threads about this exact symptom could not be read because they render only JS chrome, not substantive content, through direct fetch (see finding #10).

#### Coverage & gaps

- **Searched:** current Play Console Ads-declaration navigation; permission names gating App content/Ads; conditions for the option being absent/greyed (release state, program membership, permissions); other causes of the "Contains ads" badge (scanning, caching, inherited declarations); badge-removal timing after correction; Play Console navigation reorganization/renaming.
- **Read (fetched, raw HTML parsed directly, not just search snippets):** "Prepare your app for review" (answer/9859455) — full body text extracted and quoted above; "Manage target audience and app content settings" (answer/9867159); "Add developer account users and manage permissions" (answer/9844686); "Control when app changes are reviewed and published" (answer/9859654); "Ads" policy page (answer/9857753) — confirmed this page is pure ad-policy content (banned ad behaviors) and contains nothing about declaration navigation or the badge mechanism, and confirmed its "Policy Center > Monetization and Ads" text is the **support-site's own help-article category breadcrumb**, not a Play Console in-product nav path — this matters because a naive read of that breadcrumb could be mistaken for console navigation.
- **Attempted but unreadable (JS-rendered, page chrome only):** two Google Play Developer Community threads specifically about the "Contains ads" mislabeling ("Listing says 'Contains ads' we don't," thread/263523990, and a related thread found in search).
- **NOT found:** (a) an official statement on whether App content/Ads requires at least one released build on any track; (b) an official statement naming which specific Play Console permission gates the Ads Yes/No control; (c) any official documentation of the automated ad-SDK scanner's scope or refresh cadence; (d) any ads-specific propagation-time figure distinct from the general app-changes window; (e) confirmation, from a source I could actually read, of first-hand developer reports resolving "listing says contains ads, we don't" (the two on-point community threads were unreadable).
- **Staleness / bias notes:** none of the official Play Console Help pages fetched expose a static, reliably-parseable "last updated" date (the date field is rendered by client-side JS, `new Date(...)`, not present in the static markup) — so I cannot independently confirm currency beyond these being the live URLs as of 2026-09-03. The discrepancy between "Policy and programs > App content" and "Policy > App content" across two pages that are both currently live suggests at least one of the two has not been updated to match a real navigation rename; I cannot tell which without console access.
