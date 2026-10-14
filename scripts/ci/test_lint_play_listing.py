"""Oracles: lint_root must flag exactly the listings Google Play would reject at edits.commit.

Each test builds a real listing tree on disk and runs the production linter over it. A test
fails if a limit drifts, if length stops being measured as code points of the string the sync
script uploads, or if missing/empty/mismatched files stop being reported.
"""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent

spec = importlib.util.spec_from_file_location("lint_play_listing", HERE / "lint-play-listing.py")
lint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lint)

sync_spec = importlib.util.spec_from_file_location(
    "sync_play_store_metadata_for_lint_test", HERE / "sync-play-store-metadata.py")
sync = importlib.util.module_from_spec(sync_spec)
sync_spec.loader.exec_module(sync)

FIELDS = ("title", "short_description", "full_description")
# Hardcoded on purpose: the oracle is Google Play's published limits, not lint.LIMITS.
PLAY_LIMITS = {"title": 30, "short_description": 80, "full_description": 4000}


class LintPlayListingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "listing"
        self.root.mkdir()

    def write_locale(self, locale, **overrides):
        locale_dir = self.root / locale
        locale_dir.mkdir(exist_ok=True)
        values = {"title": "Title", "short_description": "Short", "full_description": "Full"}
        values.update(overrides)
        for field, text in values.items():
            if text is None:
                continue
            (locale_dir / f"{field}.txt").write_text(text, encoding="utf-8")
        return locale_dir

    def violations(self):
        return lint.lint_root(str(self.root))

    def test_exactly_at_each_limit_passes(self):
        self.write_locale("en-US", **{f: "a" * PLAY_LIMITS[f] for f in FIELDS})
        self.assertEqual(self.violations(), [])

    def test_one_over_each_limit_fails_for_that_field_only(self):
        for field in FIELDS:
            with self.subTest(field=field):
                self.root = Path(self.tmp.name) / f"over-{field}"
                self.root.mkdir()
                limit = PLAY_LIMITS[field]
                locale_dir = self.write_locale("en-US", **{field: "a" * (limit + 1)})
                self.assertEqual(self.violations(), [
                    f"{locale_dir / (field + '.txt')}: {field} has {limit + 1} chars (limit {limit})"])

    def test_multibyte_chars_count_as_code_points(self):
        # "ã" and "é" are 2 UTF-8 bytes each, the emoji is 4 bytes (and 2 UTF-16 units).
        for char in ("ã", "é", "\U0001F600"):
            with self.subTest(char=char):
                self.root = Path(self.tmp.name) / f"mb-{ord(char)}"
                self.root.mkdir()
                self.write_locale("pt-BR", title=char * 30)
                self.assertEqual(self.violations(), [], "30 code points must pass")
                locale_dir = self.write_locale("pt-BR", title=char * 31)
                self.assertEqual(self.violations(), [
                    f"{locale_dir / 'title.txt'}: title has 31 chars (limit 30)"])

    def test_surrounding_whitespace_is_stripped_like_the_sync_script(self):
        raw = "\n  " + "a" * 30 + "\n\n"
        locale_dir = self.write_locale("en-US", title=raw)
        # The string the sync script uploads is exactly 30 chars, so lint must pass it.
        self.assertEqual(len(sync.read_listing_text(str(locale_dir / "title.txt"))), 30)
        self.assertEqual(self.violations(), [])
        # Interior newlines are part of the uploaded text and do count.
        self.write_locale("en-US", title="a" * 15 + "\n" + "a" * 15 + "\n")
        self.assertEqual(self.violations(), [
            f"{locale_dir / 'title.txt'}: title has 31 chars (limit 30)"])

    def test_missing_file_fails(self):
        locale_dir = self.write_locale("en-US", short_description=None)
        self.assertEqual(self.violations(), [
            f"{locale_dir / 'short_description.txt'}: short_description is missing"])

    def test_empty_and_whitespace_only_files_fail(self):
        locale_dir = self.write_locale("en-US", title="", full_description=" \n\n")
        self.assertEqual(self.violations(), [
            f"{locale_dir / 'title.txt'}: title is empty",
            f"{locale_dir / 'full_description.txt'}: full_description is empty"])

    def test_locale_set_mismatch_fails(self):
        self.write_locale("en-US")
        self.write_locale("de-DE", full_description=None)
        violations = self.violations()
        self.assertIn(
            f"{self.root / 'de-DE'}: locale set mismatch, full_description.txt is present in "
            f"en-US but not in de-DE", violations)
        self.assertTrue(all("de-DE" in v for v in violations), violations)

    def test_missing_root_fails(self):
        self.assertEqual(lint.lint_root(str(self.root / "nope")),
                         [f"{self.root / 'nope'}: listing root not found"])

    def test_main_exit_codes(self):
        self.write_locale("en-US")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(lint.main([str(self.root)]), 0)
        self.write_locale("en-US", title="a" * 31)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(lint.main([str(self.root)]), 1)
        self.assertIn("title has 31 chars (limit 30)", out.getvalue())

    def test_real_listing_tree_passes(self):
        real = REPO_ROOT / "distribution" / "metadata" / "android"
        self.assertTrue(real.is_dir(), real)
        self.assertEqual(lint.lint_root(str(real)), [])

    def test_real_custom_listing_trees_pass(self):
        # Each distribution/custom-listings/<slug>/ is its own listing root, same layout as the
        # default listing. Zero slugs is valid (nothing to lint), so this loops instead of
        # asserting a count.
        custom = REPO_ROOT / "distribution" / "custom-listings"
        for slug_root in sorted(p for p in custom.glob("*") if p.is_dir()):
            with self.subTest(slug=slug_root.name):
                self.assertEqual(lint.lint_root(str(slug_root)), [])


if __name__ == "__main__":
    unittest.main()
