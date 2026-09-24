#!/usr/bin/env bash
# Liquid-leak guard for the GitHub Pages artifact (issue #414).
#
# Usage: scripts/ci/check-site-rendered.sh <site-dir>
#
# Fails if any .html file under <site-dir> still carries unrendered Jekyll source:
#   - a Liquid tag opener `{%`
#   - a Liquid-shaped output `{{ name }}` / `{{ 'str' | filter }}` (any variable, not just
#     site.*). Deliberately narrower than a bare `{{`: the brace must be followed by a
#     variable path or quoted string and then `|` or `}}`, so inline JS such as `{{b:1}}` in
#     an object literal does not trip it. The rendered site has zero `{{`/`{%` today, so any
#     hit is worth a look.
#   - YAML front matter: a first line of `---`, including after a UTF-8 BOM (Jekyll 3 treats a
#     BOM-prefixed page as a static file and copies it raw, so this can leak even when the
#     build itself works).
# and if the production analytics blocks did not render: every page in ANALYTICS_PAGES must
# carry both the GA4 (googletagmanager.com/gtag/js) and Clarity (clarity.ms/tag) script tags.
# That catches a missing JEKYLL_ENV=production, which the Liquid scan alone would not.
#
# Why: pages.yml used to upload raw docs/ with no Jekyll build, and it raced the legacy
# Pages builder. Whenever it won, the live hotsite showed `--- ---` and `{% if ... %}` as
# visible text and GA4/Clarity never loaded. Nothing failed. This guard runs on the built
# artifact before upload, so that shape of bug fails the deploy instead of shipping.
set -euo pipefail

ANALYTICS_PAGES=(index.html design/index.html release/privacy-policy.html)
# `{{`, then a variable path (page.title, site.x[0]) or a quoted string, then a filter pipe or
# the closing `}}`. JS like `{{b:1}}` does not match: Liquid never has `:` right after the
# leading expression.
LIQUID_RE='\{%|\{\{-?[[:space:]]*([A-Za-z_][][A-Za-z0-9_.'"'"'"]*|'"'"'[^'"'"']*'"'"'|"[^"]*")[[:space:]]*(\||-?\}\})'
FRONT_MATTER_RE=$'^(\xef\xbb\xbf)?---[[:space:]]*$'

site_dir="${1:?usage: $0 <site-dir>}"
if [ ! -d "$site_dir" ]; then
  echo "::error::site dir '$site_dir' does not exist"
  exit 1
fi

html_count="$(find "$site_dir" -type f -name '*.html' | wc -l)"
if [ "$html_count" -eq 0 ]; then
  echo "::error::no .html files under '$site_dir'; refusing to pass an empty artifact"
  exit 1
fi

leaks=0

# grep exits 0 = matches, 1 = none, >=2 = error. An error must not read as "clean".
rc=0
liquid_hits="$(grep -rlE --include='*.html' "$LIQUID_RE" "$site_dir")" || rc=$?
if [ "$rc" -ge 2 ]; then
  echo "::error::grep failed (exit $rc) scanning '$site_dir' for Liquid"
  exit 1
fi
if [ "$rc" -eq 0 ]; then
  while IFS= read -r f; do
    echo "::error file=${f}::unrendered Liquid in built site"
    grep -nE -m3 "$LIQUID_RE" "$f" || true
  done <<< "$liquid_hits"
  leaks=1
fi

while IFS= read -r -d '' f; do
  rc=0
  first=""
  IFS= read -r first < "$f" || true
  # A here-string, not `head | grep -q`: no pipe, so no SIGPIPE/141 under pipefail.
  LC_ALL=C grep -qE "$FRONT_MATTER_RE" <<< "$first" || rc=$?
  if [ "$rc" -ge 2 ]; then
    echo "::error file=${f}::grep failed (exit $rc) checking front matter"
    exit 1
  fi
  if [ "$rc" -eq 0 ]; then
    echo "::error file=${f}::front matter left in built site (first line is ---)"
    leaks=1
  fi
done < <(find "$site_dir" -type f -name '*.html' -print0)

for page in "${ANALYTICS_PAGES[@]}"; do
  f="${site_dir}/${page}"
  if [ ! -f "$f" ]; then
    echo "::error file=${f}::analytics page missing from built site"
    leaks=1
    continue
  fi
  for tag in 'googletagmanager.com/gtag/js' 'clarity.ms/tag'; do
    rc=0
    grep -qF "$tag" "$f" || rc=$?
    if [ "$rc" -ge 2 ]; then
      echo "::error file=${f}::grep failed (exit $rc) checking for ${tag}"
      exit 1
    fi
    if [ "$rc" -ne 0 ]; then
      echo "::error file=${f}::${tag} missing; was JEKYLL_ENV=production set?"
      leaks=1
    fi
  done
done

if [ "$leaks" -ne 0 ]; then
  echo "Liquid-leak guard FAILED: '$site_dir' contains unrendered Jekyll source or is missing analytics."
  exit 1
fi

echo "Liquid-leak guard passed: ${html_count} .html files under '$site_dir' are fully rendered; analytics present on ${#ANALYTICS_PAGES[@]} pages."
