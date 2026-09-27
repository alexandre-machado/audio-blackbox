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
# carry exactly one GA4 (googletagmanager.com/gtag/js) and Clarity (clarity.ms/tag) loader.
# That catches a missing JEKYLL_ENV=production, which the Liquid scan alone would not.
# It also validates every landing page's language/SEO links and JSON-LD, including verbatim
# FAQ equality (#421/#425). Requires `ruby` on PATH, provided by the Pages build.
#
# Why: pages.yml used to upload raw docs/ with no Jekyll build, and it raced the legacy
# Pages builder. Whenever it won, the live hotsite showed `--- ---` and `{% if ... %}` as
# visible text and GA4/Clarity never loaded. Nothing failed. This guard runs on the built
# artifact before upload, so that shape of bug fails the deploy instead of shipping.
set -euo pipefail

LANDING_PAGES=(index.html pt-br/index.html es/index.html fr/index.html de/index.html it/index.html)
ANALYTICS_PAGES=("${LANDING_PAGES[@]}" design/index.html release/privacy-policy.html)
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
    occurrences="$(grep -oF "$tag" "$f" | wc -l)" || rc=$?
    if [ "$rc" -ge 2 ]; then
      echo "::error file=${f}::grep failed (exit $rc) checking for ${tag}"
      exit 1
    fi
    if [ "$occurrences" -ne 1 ]; then
      echo "::error file=${f}::expected exactly one ${tag} loader, found ${occurrences}; check JEKYLL_ENV=production and layouts"
      leaks=1
    fi
  done
done

# Structured data on the landing page (#425): every application/ld+json block must parse as
# JSON, and the FAQPage Question/Answer pairs must equal the visible #faq text (tags stripped,
# entities decoded), because search engines penalise FAQ markup that does not match the page.
# Ruby, not python3: the Pages build job only guarantees the conda-forge Ruby it installs for
# Jekyll (setup-jekyll-ruby.sh), and json/cgi are in Ruby's stdlib.
if ! command -v ruby >/dev/null 2>&1; then
  echo "::error::ruby not on PATH; cannot validate landing pages in ${site_dir}"
  exit 1
fi
rc=0
ruby - "$site_dir" "${LANDING_PAGES[@]}" <<'RUBY' || rc=$?
require "json"
require "cgi"

site_dir = ARGV.shift
base = "https://alexandre.machado.cc/audio-blackbox/"
locales = {
  "index.html" => ["en", "en_US", ""],
  "pt-br/index.html" => ["pt-BR", "pt_BR", "pt-br/"],
  "es/index.html" => ["es", "es_ES", "es/"],
  "fr/index.html" => ["fr", "fr_FR", "fr/"],
  "de/index.html" => ["de", "de_DE", "de/"],
  "it/index.html" => ["it", "it_IT", "it/"]
}
alternates = locales.values.map { |lang, _, path| [lang, base + path] }.to_h
alternates["x-default"] = base

ARGV.each do |page|
f = File.join(site_dir, page)
fail_with = ->(msg) { puts "::error file=#{f}::#{msg}"; exit 1 }
fail_with.("page missing from built site") unless File.file?(f)
s = File.read(f, encoding: "UTF-8")
lang, og_locale, path = locales.fetch(page)
url = base + path
# Parse attributes independently of their order. These are controlled, generated HTML tags.
attrs = ->(tag) { tag.scan(/([\w:-]+)="([^"]*)"/).to_h.transform_values { |v| CGI.unescapeHTML(v) } }
links = s.scan(/<link\b[^>]*>/).map { |tag| attrs.(tag) }
metas = s.scan(/<meta\b[^>]*>/).map { |tag| attrs.(tag) }
fail_with.("incorrect html lang") unless attrs.(s[/<html\b[^>]*>/].to_s)["lang"] == lang
canonicals = links.select { |l| l["rel"] == "canonical" }.map { |l| l["href"] }
fail_with.("expected one self-canonical #{url}") unless canonicals == [url]
actual_alternates = links.select { |l| l["rel"] == "alternate" && l["hreflang"] }
unless actual_alternates.size == alternates.size && actual_alternates.map { |l| [l["hreflang"], l["href"]] }.to_h == alternates
  fail_with.("hreflang alternates must cover all six languages and x-default exactly once")
end
{"og:locale" => og_locale, "og:url" => url}.each do |key, expected|
  fail_with.("incorrect #{key}") unless metas.select { |m| m["property"] == key }.map { |m| m["content"] } == [expected]
end
%w[og:title og:description].each do |key|
  values = metas.select { |m| m["property"] == key }.map { |m| m["content"] }
  fail_with.("missing or duplicate #{key}") unless values.size == 1 && !values[0].to_s.strip.empty?
end
anchors = s.scan(/<a\b[^>]*>/).map { |tag| attrs.(tag) }
switcher = anchors.select { |a| a["hreflang"] }
expected_switcher = alternates.reject { |key, _| key == "x-default" }.transform_values { |v| v.delete_prefix("https://alexandre.machado.cc") }
unless switcher.size == 6 && switcher.map { |a| [a["hreflang"], a["href"]] }.to_h == expected_switcher
  fail_with.("language switcher missing or incorrect")
end
fail_with.("privacy policy link missing") unless anchors.any? { |a| a["href"] == base + "release/privacy-policy" }
s.scan(/<img\b[^>]*>/).each do |tag|
  src = attrs.(tag)["src"].to_s
  fail_with.("unresolved image asset #{src}") unless src.start_with?("/audio-blackbox/") && File.file?(File.join(site_dir, src.delete_prefix("/audio-blackbox/")))
end

blocks = s.scan(%r{<script type="application/ld\+json">(.*?)</script>}m).map(&:first)
fail_with.("no application/ld+json blocks found") if blocks.empty?
parsed = blocks.each_with_index.map do |b, i|
  JSON.parse(b)
rescue JSON::ParserError => e
  fail_with.("JSON-LD block #{i + 1} of #{blocks.size} does not parse: #{e.message.lines.first.to_s.strip[0, 200]}")
end

faqs = parsed.select { |b| b.is_a?(Hash) && b["@type"] == "FAQPage" }
apps = parsed.select { |b| b.is_a?(Hash) && b["@type"] == "MobileApplication" }
fail_with.("expected one localized MobileApplication") unless apps.size == 1 && apps[0]["url"] == url && !apps[0]["description"].to_s.empty?
fail_with.("expected exactly one FAQPage block, found #{faqs.size}") unless faqs.size == 1
ld = Array(faqs[0]["mainEntity"]).map { |e| [e["name"], e.dig("acceptedAnswer", "text")] }

start = s.index('<section id="faq">') or fail_with.("no <section id=\"faq\"> in page")
sec = s[start...(s.index("</section>", start) || s.size)]
strip = ->(t) { CGI.unescapeHTML(t.gsub(/<[^>]+>/, "")).strip }
visible = sec.scan(%r{<h3[^>]*>(.*?)</h3>\s*<p[^>]*>(.*?)</p>}m).map { |q, a| [strip.(q), strip.(a)] }
fail_with.("no visible FAQ entries found in #faq") if visible.empty?

if visible != ld
  fail_with.("FAQPage JSON-LD has #{ld.size} entries, visible FAQ has #{visible.size}") if visible.size != ld.size
  i = visible.each_index.find { |k| visible[k] != ld[k] }
  fail_with.("FAQ entry #{i + 1} differs. visible: #{visible[i].inspect} JSON-LD: #{ld[i].inspect}")
end
puts "Landing page OK: #{page}; SEO, language links, assets and #{ld.size} verbatim FAQ entries."
end
RUBY
if [ "$rc" -ne 0 ]; then
  leaks=1
fi

if [ "$leaks" -ne 0 ]; then
  echo "Liquid-leak guard FAILED: '$site_dir' contains unrendered Jekyll source, is missing analytics, or has invalid/mismatched JSON-LD."
  exit 1
fi

echo "Liquid-leak guard passed: ${html_count} .html files under '$site_dir' are fully rendered; analytics present on ${#ANALYTICS_PAGES[@]} pages."
