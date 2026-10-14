#!/usr/bin/env python3
"""
Lints Google Play store listing text before merge.

Each listing root holds <locale>/{title,short_description,full_description}.txt
(the layout sync-play-store-metadata.py uploads). For every root this checks:
  - every locale has all three text files,
  - no file is empty after normalization,
  - title <= 30, short_description <= 80, full_description <= 4000 characters,
  - every locale in a root carries the same set of text files.

Length is measured on the exact string the sync script sends to the API
(read_listing_text, imported from sync-play-store-metadata.py), counted in
Unicode code points. Stdlib only. Exits 1 on any violation, 0 otherwise.
"""

import argparse
import importlib.util
import os
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
DEFAULT_ROOT = os.path.join(REPO_ROOT, "distribution", "metadata", "android")

_spec = importlib.util.spec_from_file_location(
    "sync_play_store_metadata", os.path.join(SCRIPT_DIR, "sync-play-store-metadata.py"))
_sync = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_sync)
read_listing_text = _sync.read_listing_text

# Google Play Console limits, in characters.
LIMITS = {
    "title": 30,
    "short_description": 80,
    "full_description": 4000,
}


def lint_root(root):
    """Return a list of violation strings for one listing root."""
    violations = []
    if not os.path.isdir(root):
        return [f"{root}: listing root not found"]

    locales = sorted(d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d)))
    if not locales:
        return [f"{root}: no locale directories found"]

    present_by_locale = {}
    for locale in locales:
        locale_dir = os.path.join(root, locale)
        present = set()
        for field, limit in LIMITS.items():
            path = os.path.join(locale_dir, f"{field}.txt")
            if not os.path.isfile(path):
                violations.append(f"{path}: {field} is missing")
                continue
            present.add(field)
            text = read_listing_text(path)
            length = len(text)
            if length == 0:
                violations.append(f"{path}: {field} is empty")
            elif length > limit:
                violations.append(f"{path}: {field} has {length} chars (limit {limit})")
        present_by_locale[locale] = present

    union = set().union(*present_by_locale.values())
    for locale in locales:
        absent = union - present_by_locale[locale]
        for field in sorted(absent):
            others = sorted(l for l in locales if field in present_by_locale[l])
            violations.append(
                f"{os.path.join(root, locale)}: locale set mismatch, {field}.txt is present in "
                f"{', '.join(others)} but not in {locale}")
    return violations


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("roots", nargs="*", default=[DEFAULT_ROOT],
                        help="listing roots to check (default: distribution/metadata/android)")
    args = parser.parse_args(argv)

    violations = []
    for root in args.roots:
        violations.extend(lint_root(root))

    for line in violations:
        print(line)
    if violations:
        print(f"Play listing lint FAILED: {len(violations)} violation(s) in {len(args.roots)} root(s).")
        return 1
    print(f"Play listing lint passed: {len(args.roots)} root(s) checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
