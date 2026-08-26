# Play Console — Data safety form answers (draft)

**Draft for the owner to paste into the Play Console. Not legal advice, not
counsel-reviewed.** Answers are phrased to match the Console's actual
question wording as closely as this document's author could reconstruct
without live access to the Console UI; the owner should sanity-check each
answer against the live form at submission time, since Google periodically
changes the form's wording and structure.

*Last drafted: 2026-08-25, against commit `24cc125`.*

## Does your app collect or share any of the required user data types?

**Yes.** (Audio, and app activity/diagnostics via Firebase Analytics — see
below. If the owner removes Firebase Analytics before submission per the
open decision noted in the privacy policy, this answer and every row below
that references it should be revisited.)

## Data collection

| Data type | Collected? | Shared with third parties? | Why | Optional or required | Notes |
|---|---|---|---|---|---|
| **Audio (voice/sound recordings)** | Yes, but only ever stored **on-device** (see Notes) | No | App functionality (the core recording feature) | Required — this is the app's core function | *Verified.* Audio is captured to an in-memory ring buffer and only written to on-device storage when the user explicitly saves. It is never transmitted off the device: the app requests no network permission and the audio pipeline contains no networking code (see the PR appendix for citations). Google's Data safety form distinguishes "collected" (leaves the device) from "processed on-device"; if the Console's current wording separates these, answer "processed, not collected" for audio and adjust this row accordingly at submission time. |
| **App activity — App interactions / In-app search history (analogous "usage data")** | Yes | Yes, with Google (as the Firebase Analytics processor) | Analytics | Optional (does not gate core functionality) | *Verified.* `FirebaseAnalyticsTracker` (`app/src/main/java/cc/machado/audioblackbox/analytics/AnalyticsTracker.kt`) sends: engine state changes, save-triggered/save-completed events (duration in minutes, format, file size in bytes), retention-window-changed events, and sanitized error categories. No audio content, file names, paths, or file contents are included. |
| **Diagnostics — Crash logs / App performance** | Only if the app additionally uses Firebase Crashlytics or similar | Depends | — | — | *Assumed absent — not present in code as of this commit.* Only `firebase-analytics` was found in `app/build.gradle.kts:192-193`; no Crashlytics or Performance Monitoring dependency was found. If the owner adds one before submission, add a row here. |
| **Personal identifiers (name, email, user ID, address, phone)** | No | No | — | — | *Verified — not collected.* No forms, accounts, sign-in, or contact-capture UI exist in `app/src/main`. |
| **Location** | No | No | — | — | *Verified — not collected.* No location permission is requested in `app/src/main/AndroidManifest.xml`, and no location API calls were found. |
| **Files and docs / Photos and videos** | No | No | — | — | *Verified — not requested/collected.* The app writes recordings it creates itself via `MediaStore`; it does not request `READ_MEDIA_*`/`READ_EXTERNAL_STORAGE` and cannot browse the user's other files or media. |
| **Contacts** | No | No | — | — | *Verified — not requested.* No `READ_CONTACTS` permission, no contacts API usage. |
| **Device or other identifiers** | Possibly, indirectly, only via Firebase Analytics' own default collection | Yes, with Google | Analytics | Optional | *Assumed.* Firebase Analytics collects some baseline signals (e.g., an analytics instance ID) as part of its own SDK behaviour, independent of this app's explicit `logEvent` calls. This app's own code does not read or transmit a device identifier directly; this row exists to disclose the SDK's own baseline collection, which the owner should confirm against Google's current Firebase Analytics data-collection documentation before submission, since that SDK's default behaviour is Google's to define, not this app's. |

## Data sharing

| Recipient | Data shared | Purpose | Notes |
|---|---|---|---|
| Google (Firebase Analytics) | App activity / operational events, possibly a baseline device/analytics identifier per Firebase's own SDK behaviour | Analytics, provided by a service provider | *Verified* the app sends events to Firebase Analytics (`AnalyticsTracker.kt`); the *characterization* as "third-party processor acting as a service provider under Google's terms" rather than "third party for its own purposes" is the standard framing for Firebase Analytics but should be confirmed by the owner against Google's current Play Console guidance, since that determines which Console radio button applies. |
| No other recipient | — | — | *Verified.* No other network client, SDK, ad network, or third-party service call was found in `app/src/main`. |

## Security practices

| Question | Answer | Notes |
|---|---|---|
| Is data encrypted in transit? | N/A for audio (never transmitted). For Firebase Analytics events: yes, standard Firebase SDK behaviour (HTTPS), which is Google's default and not something this app's code configures or could turn off. | *Verified* for audio (no transmission path exists). *Assumed* for the Firebase transport encryption claim — inherited from the SDK, not verified by reading Firebase's internals in this pass. |
| Can users request data deletion? | Users can delete their own saved recordings directly through the device's Files or Music app, or (subject to the ownership-attribution caveat below) from the app's own gallery. There is no server-side user data to delete because nothing personal is collected off-device other than the Firebase Analytics events described above, which the owner can address via Firebase's own data-deletion tooling if requested. | *Verified* for on-device deletion capability (`RecordingsRepository.delete`, `app/src/main/java/cc/machado/audioblackbox/export/MediaStoreSink.kt:197-202`, catches `SecurityException`/`RecoverableSecurityException` rather than silently failing). *Assumed/deferred* for the Firebase-side deletion tooling — not exercised in this pass. |
| Does the app follow the Play Families Policy? | N/A — app is not directed at children. | Not independently re-verified in this pass; carried from the privacy policy's "Children" section. |

## Data deletion — uninstall behaviour (the #59 question)

This is worth calling out separately since it's easy to get backwards in the
Console form: **uninstalling Audio Blackbox does not delete previously saved
recordings.** Android's shared-storage model keeps media files an app wrote
even after that app is uninstalled — this is documented Android platform
behaviour, not something the app opts into or out of. See the privacy
policy's retention section for the full wording, including the
not-yet-hardware-confirmed caveat about the app's own gallery losing the
ability to *list* those files after a reinstall (tracked in #59). Answer the
Console's "can users delete data via account deletion" and similar prompts
consistently with this: there is no account, and no server copy to delete;
the only artifacts are the local files the user already controls directly.

## Cross-consistency check (policy vs. this form)

Pairs checked between `docs/legal/privacy-policy.md` and this document:

- **Audio never leaves the device / stored on-device only.** Consistent
  (privacy policy "What we collect → Audio"; this form's Audio row).
- **Firebase Analytics is the one thing that does leave the device.**
  Consistent (privacy policy calls this out explicitly as an exception to
  full offline operation; this form's App activity and Device identifiers
  rows disclose the same thing with the same "Google, as processor"
  framing).
- **No personal identifiers, location, contacts, files/photos access.**
  Consistent (privacy policy "Nothing else" / permissions list; this form's
  corresponding rows all say No, tied to the same manifest/code evidence).
- **Uninstall does not delete saved recordings; reinstall listing caveat is
  pending #59 hardware confirmation.** Consistent (privacy policy retention
  section and this form's Data deletion section use the same wording and the
  same open-issue caveat, neither overstating deletion nor confirming the
  gallery-listing gap as settled).
- **Open decision on whether Firebase Analytics stays, is replaced, or is
  removed.** Consistent — both documents flag this as unresolved and both
  say they'll need re-checking once decided, rather than one document
  assuming an outcome the other doesn't.

No contradiction was found between the two documents as drafted. If the
owner changes the Firebase Analytics decision, the jurisdictional
consent-recording note, or the #59 hardware confirmation before submission,
re-run this check.
