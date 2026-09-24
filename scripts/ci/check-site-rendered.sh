#!/usr/bin/env bash
# Liquid-leak guard for the GitHub Pages artifact (issue #414).
#
# Usage: scripts/ci/check-site-rendered.sh <site-dir>
#
# Fails if any .html file under <site-dir> still carries unrendered Jekyll source:
#   - a Liquid tag opener `{%`
#   - a Liquid output of a site variable `{{ site.`
#   - YAML front matter (a first line that is `---`)
#
# Why: pages.yml used to upload raw docs/ with no Jekyll build, and it raced the legacy
# Pages builder. Whenever it won, the live hotsite showed `--- ---` and `{% if ... %}` as
# visible text and GA4/Clarity never loaded. Nothing failed. This guard runs on the built
# artifact right before upload, so that shape of bug fails the deploy instead of shipping.
set -euo pipefail

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

liquid_hits="$(grep -rlE --include='*.html' '\{%|\{\{ *site\.' "$site_dir" || true)"
if [ -n "$liquid_hits" ]; then
  while IFS= read -r f; do
    echo "::error file=${f}::unrendered Liquid in built site"
    grep -nE '\{%|\{\{ *site\.' "$f" | head -3
  done <<< "$liquid_hits"
  leaks=1
fi

while IFS= read -r -d '' f; do
  if head -n 1 "$f" | grep -qE '^---[[:space:]]*$'; then
    echo "::error file=${f}::front matter left in built site (first line is ---)"
    leaks=1
  fi
done < <(find "$site_dir" -type f -name '*.html' -print0)

if [ "$leaks" -ne 0 ]; then
  echo "Liquid-leak guard FAILED: '$site_dir' contains unrendered Jekyll source."
  exit 1
fi

echo "Liquid-leak guard passed: ${html_count} .html files under '$site_dir' are fully rendered."
