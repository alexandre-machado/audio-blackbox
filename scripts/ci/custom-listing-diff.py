#!/usr/bin/env python3
"""
Renders the custom store listing text changed by a pull request as a paste-ready
Markdown report, and optionally upserts it as a single PR comment.

Google Play has no API for custom store listings (edits.listings is keyed only by
package, edit and language, so it can only ever write the DEFAULT listing). The
text under distribution/custom-listings/<slug>/<locale>/<field>.txt is therefore
applied by hand in Play Console; this script tells the owner exactly what to paste.

Report mode (default):
    custom-listing-diff.py --base <sha> --head <sha> [--out FILE] [--github-output FILE]
  Compares merge-base(base, head) to head (the same range as `git diff base...head`).
  Writes the report to stdout or FILE only when something changed, and appends
  `changed=true|false` to --github-output when given. Exit 0 on success.

Post mode:
    custom-listing-diff.py --base <sha> --head <sha> --post --repo OWNER/REPO --pr N
  Finds this script's previous comment on the PR (body starts with MARKER, authored
  by a bot) and PATCHes it, else POSTs a new one. With no change, an existing comment
  is PATCHed to a short "no pending changes" note and nothing is posted otherwise.
  Uses `gh api` (GH_TOKEN from the environment). Stdlib only.

Text is normalized exactly like the uploader/linter (read_listing_text: strip), so a
whitespace-only edit is skipped: it changes nothing that would be pasted.
"""

import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile

MARKER = "<!-- custom-listing-diff -->"
TREE = "distribution/custom-listings/"
# Same limits and field order as lint-play-listing.py.
LIMITS = {"title": 30, "short_description": 80, "full_description": 4000}
PATH_RE = re.compile(r"^distribution/custom-listings/([^/]+)/([^/]+)/([a-z_]+)\.txt$")


def normalize(text):
    """Same normalization as sync-play-store-metadata.read_listing_text."""
    return text.strip()


def git(*args, cwd=None):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True).stdout


def file_at(rev, path, cwd=None):
    return git("show", f"{rev}:{path}", cwd=cwd).decode("utf-8")


def collect_changes(base, head, cwd=None):
    """Return (merge_base, changes). changes: list of dicts sorted by slug, locale, field order."""
    merge_base = git("merge-base", base, head, cwd=cwd).decode().strip()
    raw = git("diff", "--name-status", "--no-renames", "-z", f"{base}...{head}", "--", TREE, cwd=cwd)
    parts = raw.decode("utf-8").split("\0")
    changes = []
    i = 0
    while i + 1 < len(parts):
        status, path = parts[i], parts[i + 1]
        i += 2
        m = PATH_RE.match(path)
        if not m or m.group(3) not in LIMITS:
            continue
        slug, locale, field = m.groups()
        kind = status[0]
        old = file_at(merge_base, path, cwd) if kind != "A" else None
        new = file_at(head, path, cwd) if kind != "D" else None
        if kind not in ("A", "D"):
            kind = "M"
            if normalize(old) == normalize(new):
                continue  # whitespace-only: nothing to paste
        changes.append({"slug": slug, "locale": locale, "field": field, "kind": kind,
                        "old": None if old is None else normalize(old),
                        "new": None if new is None else normalize(new)})
    order = list(LIMITS)
    changes.sort(key=lambda c: (c["slug"], c["locale"], order.index(c["field"])))
    return merge_base, changes


def fence_for(text, char="`"):
    longest = max((len(r) for r in re.findall(re.escape(char) + "+", text)), default=0)
    return char * max(3, longest + 1)


def locale_exists(head, slug, locale, cwd=None):
    out = git("ls-tree", "-r", "--name-only", head, f"{TREE}{slug}/{locale}/", cwd=cwd)
    return bool(out.strip())


def slug_exists(head, slug, cwd=None):
    out = git("ls-tree", "-r", "--name-only", head, f"{TREE}{slug}/", cwd=cwd)
    return bool(out.strip())


def render_report(base, head, cwd=None):
    """Return the Markdown report, or None when no custom listing text changed."""
    _merge_base, changes = collect_changes(base, head, cwd)
    if not changes:
        return None
    short = lambda rev: git("rev-parse", "--short", rev, cwd=cwd).decode().strip()
    lines = [MARKER, "## Custom store listing changes",
             "",
             "Google Play has no API for custom store listings, so CI cannot apply these. "
             "Paste each block below into Play Console (Grow users > Store presence > "
             "Custom store listings) after this pull request merges.",
             ""]
    current_slug = current_locale = None
    for c in changes:
        slug, locale = c["slug"], c["locale"]
        if slug != current_slug:
            current_slug, current_locale = slug, None
            lines.append(f"### Listing `{slug}`")
            if not slug_exists(head, slug, cwd):
                lines.append("")
                lines.append("**Listing removed from the repo: remove/clear it in Console.**")
            lines.append("")
        if locale != current_locale:
            current_locale = locale
            lines.append(f"#### {locale}")
            if not locale_exists(head, slug, locale, cwd):
                lines.append("")
                lines.append("**Locale removed from the repo: remove/clear this language in Console.**")
            lines.append("")
        field, limit = c["field"], LIMITS[c["field"]]
        if c["kind"] == "D":
            lines.append(f"- `{field}`: deleted. **Remove/clear in Console.**")
            lines.append("")
            continue
        new = c["new"]
        label = "new" if c["kind"] == "A" else "changed"
        lines.append(f"**`{field}`** ({label}), {len(new)}/{limit} chars")
        lines.append("")
        fence = fence_for(new)
        lines += [f"{fence}text", new, fence, ""]
        if c["kind"] == "M":
            diff = "\n".join(difflib.unified_diff(
                c["old"].splitlines(), new.splitlines(),
                fromfile=f"{field} (before)", tofile=f"{field} (after)", lineterm=""))
            dfence = fence_for(diff)
            lines += ["<details><summary>Diff (old to new)</summary>", "",
                      f"{dfence}diff", diff, dfence, "", "</details>", ""]
    lines.append(f"<sub>Base `{short(base)}`, head `{short(head)}`.</sub>")
    return "\n".join(lines) + "\n"


def no_change_note(head_short):
    return f"{MARKER}\nNo pending custom-listing changes at `{head_short}`.\n"


def _gh(args, run):
    return run(["gh", "api", *args])


def find_marker_comment(repo, pr, run):
    jq = (f'.[] | select(.user.type == "Bot" and (.body | startswith("{MARKER}"))) | .id')
    out = _gh(["--paginate", f"repos/{repo}/issues/{pr}/comments", "--jq", jq], run)
    ids = [line.strip() for line in out.splitlines() if line.strip()]
    return ids[0] if ids else None


def upsert(repo, pr, report, head_short, run):
    """Apply the comment policy. Returns the action taken: patch, post, or none."""
    existing = find_marker_comment(repo, pr, run)
    if report is None:
        if existing is None:
            return "none"
        body = no_change_note(head_short)
    else:
        body = report
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump({"body": body}, f)
        payload = f.name
    try:
        if existing is not None:
            _gh(["-X", "PATCH", f"repos/{repo}/issues/comments/{existing}", "--input", payload], run)
            return "patch"
        _gh(["-X", "POST", f"repos/{repo}/issues/{pr}/comments", "--input", payload], run)
        return "post"
    finally:
        os.unlink(payload)


def run_cmd(cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def main(argv=None, run=run_cmd):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--out", help="write the report here instead of stdout")
    parser.add_argument("--github-output", help="append changed=true|false to this file")
    parser.add_argument("--post", action="store_true", help="upsert the PR comment via gh api")
    parser.add_argument("--repo", help="OWNER/REPO (post mode)")
    parser.add_argument("--pr", help="pull request number (post mode)")
    args = parser.parse_args(argv)

    report = render_report(args.base, args.head)
    changed = report is not None
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as f:
            f.write(f"changed={'true' if changed else 'false'}\n")

    if args.post:
        if not args.repo or not args.pr:
            parser.error("--post needs --repo and --pr")
        head_short = git("rev-parse", "--short", args.head).decode().strip()
        action = upsert(args.repo, args.pr, report, head_short, run)
        print(f"custom-listing-diff: changed={changed} comment={action}")
        return 0

    if changed:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8")
        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(report)
        else:
            sys.stdout.write(report)
    else:
        print("custom-listing-diff: no custom listing text changed.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
