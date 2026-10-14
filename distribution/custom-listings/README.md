# Custom store listings

This tree holds the text of the Google Play **custom store listings** for Audio Blackbox: extra
store pages, each with its own title and descriptions, that Play shows to people arriving through a
specific link (the `&listing=` URL parameter) instead of the default listing.

Layout, same per-locale shape as `distribution/metadata/android/`:

```
distribution/custom-listings/<slug>/<locale>/title.txt
distribution/custom-listings/<slug>/<locale>/short_description.txt
distribution/custom-listings/<slug>/<locale>/full_description.txt
```

The repository is the source of truth for this text. Play Console is where it gets applied, by hand.

## Why this is not automated

The Google Play Developer API has no endpoint for custom store listings.
[`edits.listings`](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.listings)
is keyed only by package name, edit id and language, so there is no way to address a custom
listing through it. Sending this text through that endpoint would overwrite the **default**
listing for that language.

For that reason:

- `scripts/ci/sync-play-store-metadata.py` only reads `distribution/metadata/android/` and must
  never be pointed at this tree.
- The `deploy-playstore` job in `.github/workflows/ci.yml` never reads this directory. Its listing
  sync is gated on changes under `distribution/metadata/` only.

## The process

1. Edit the `.txt` files here and open a pull request.
2. CI lints the text against Play's limits (title 30, short description 80, full description 4000
   characters, counted after trimming surrounding whitespace) in the `build` job.
3. The `custom-listing-diff` job posts one comment on the pull request with the paste-ready text for
   every field that changed, grouped by listing and locale, with a collapsed diff of the old text.
   It updates that same comment on every push instead of adding new ones.
4. After merge, the owner pastes each changed field into Play Console:
   **Grow users > Store presence > Custom store listings**, open the listing, pick the language,
   paste, and save. Deleted files or locales are flagged in the comment as "remove/clear in
   Console".

Nothing in this tree reaches Play on its own. If the comment says something changed, it has not
been applied until someone pastes it in.

## Listings

| Slug | Console listing name | `&listing=` URL parameter |
| :--- | :--- | :--- |
| `creators` | Creators (musicians, songwriters, ideas) | TBD (owner creates in Console) |
| `safety` | Safety (pocket audio dashcam) | TBD (owner creates in Console) |

Copy rule: a custom listing may reframe what the default listing says for a narrower audience, but
it must not claim anything the default listing does not already claim (no new features, numbers or
certifications).
