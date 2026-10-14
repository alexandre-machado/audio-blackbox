"""Oracles: render_report must show the owner exactly the custom-listing text to paste, and
upsert must keep at most one marker comment per PR.

Each test builds a real git repository with real commits and runs the production diff over it,
so a test fails if changed files stop being detected, if the pasted text drifts from the
committed text (normalization, encoding, fences), or if the comment policy changes.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent

spec = importlib.util.spec_from_file_location("custom_listing_diff", HERE / "custom-listing-diff.py")
cld = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cld)

# Hardcoded on purpose: the oracle is the string the CI job searches for, not cld.MARKER.
MARKER = "<!-- custom-listing-diff -->"
ROOT = "distribution/custom-listings"


class GitFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.git("init", "-q", "-b", "main")
        (self.repo / "README.md").write_text("fixture\n", encoding="utf-8")
        self.base = self.commit("base")

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid",
             "-c", "commit.gpgsign=false", *args],
            cwd=self.repo, check=True, capture_output=True, text=True).stdout.strip()

    def write(self, slug, locale, field, text):
        path = self.repo / ROOT / slug / locale / f"{field}.txt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def write_locale(self, slug, locale, title="Title", short="Short", full="Full"):
        self.write(slug, locale, "title", title + "\n")
        self.write(slug, locale, "short_description", short + "\n")
        self.write(slug, locale, "full_description", full + "\n")

    def commit(self, msg):
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", msg)
        return self.git("rev-parse", "HEAD")

    def report(self, base, head):
        return cld.render_report(base, head, cwd=str(self.repo))


class RenderReportTest(GitFixture):
    def test_no_listing_change_gives_no_report(self):
        (self.repo / "other.txt").write_text("x\n", encoding="utf-8")
        # README under the tree is not listing text either.
        (self.repo / ROOT).mkdir(parents=True)
        (self.repo / ROOT / "README.md").write_text("docs\n", encoding="utf-8")
        head = self.commit("unrelated")
        self.assertIsNone(self.report(self.base, head))

    def test_modified_field_shows_full_new_text_count_and_diff(self):
        self.write_locale("creators", "en-US", title="Old title")
        base = self.commit("seed")
        self.write("creators", "en-US", "title", "New title\n")
        head = self.commit("edit")
        report = self.report(base, head)
        self.assertIsNotNone(report)
        self.assertEqual(report.splitlines()[0], MARKER)
        self.assertIn("### Listing `creators`", report)
        self.assertIn("#### en-US", report)
        self.assertIn("**`title`** (changed), 9/30 chars", report)
        self.assertIn("```text\nNew title\n```", report)
        self.assertIn("<details>", report)
        self.assertIn("-Old title\n+New title", report)
        # Only the changed field is reported.
        self.assertNotIn("short_description", report)
        self.assertIn(f"head `{self.git('rev-parse', '--short', head)}`", report)

    def test_added_locale_shows_every_field_as_new(self):
        self.write_locale("safety", "en-US")
        base = self.commit("seed")
        self.write_locale("safety", "pt-BR", title="Título", short="Curta", full="Completa")
        head = self.commit("add pt-BR")
        report = self.report(base, head)
        self.assertIn("#### pt-BR", report)
        self.assertNotIn("#### en-US", report)
        for field, text in (("title", "Título"), ("short_description", "Curta"),
                            ("full_description", "Completa")):
            self.assertIn(f"**`{field}`** (new)", report)
            self.assertIn(f"```text\n{text}\n```", report)
        self.assertNotIn("<details>", report)
        # Fields render in Console order.
        self.assertLess(report.index("`title`"), report.index("`short_description`"))
        self.assertLess(report.index("`short_description`"), report.index("`full_description`"))

    def test_deleted_file_and_locale_say_remove_in_console_without_copy_block(self):
        self.write_locale("safety", "en-US")
        self.write_locale("safety", "pt-BR")
        base = self.commit("seed")
        self.git("rm", "-q", "-r", f"{ROOT}/safety/pt-BR")
        head = self.commit("drop pt-BR")
        report = self.report(base, head)
        self.assertIn("remove/clear this language in Console", report)
        self.assertEqual(report.count("deleted. **Remove/clear in Console.**"), 3)
        self.assertNotIn("```text", report)

    def test_deleted_slug_is_flagged(self):
        self.write_locale("safety", "en-US")
        base = self.commit("seed")
        self.git("rm", "-q", "-r", f"{ROOT}/safety")
        head = self.commit("drop listing")
        self.assertIn("Listing removed from the repo: remove/clear it in Console",
                      self.report(base, head))

    def test_backticks_in_text_do_not_break_the_fence(self):
        self.write_locale("creators", "en-US")
        base = self.commit("seed")
        text = "Use ``` and ```` inside\nand `code`"
        self.write("creators", "en-US", "full_description", text)
        head = self.commit("backticks")
        report = self.report(base, head)
        # Longest run is 4, so the fence must be 5 and the text must sit intact between fences.
        self.assertIn(f"`````text\n{text}\n`````", report)
        self.assertIn("`````diff\n", report)

    def test_non_ascii_text_is_preserved_and_counted_in_code_points(self):
        self.write_locale("creators", "pt-BR")
        base = self.commit("seed")
        title = "Gravação à mão 🎸"
        self.write("creators", "pt-BR", "title", title)
        head = self.commit("acentos")
        report = self.report(base, head)
        self.assertIn(f"```text\n{title}\n```", report)
        self.assertIn(f"{len(title)}/30 chars", report)
        self.assertEqual(len(title), 16)

    def test_whitespace_only_change_is_skipped(self):
        self.write_locale("creators", "en-US", title="Same")
        base = self.commit("seed")
        self.write("creators", "en-US", "title", "\n  Same  \n\n")
        head = self.commit("whitespace")
        self.assertIsNone(self.report(base, head))
        # ...but a real change next to it is still reported, alone.
        self.write("creators", "en-US", "short_description", "Different\n")
        head2 = self.commit("real")
        report = self.report(base, head2)
        self.assertIn("`short_description`", report)
        self.assertNotIn("**`title`**", report)

    def test_range_is_three_dot_from_merge_base(self):
        # A change that landed on base after the branch point must not show up as the PR's.
        self.write_locale("creators", "en-US", title="Branch point")
        fork = self.commit("seed")
        self.git("checkout", "-q", "-b", "feature")
        self.write("creators", "en-US", "short_description", "PR change")
        head = self.commit("pr")
        self.git("checkout", "-q", "main")
        self.write("creators", "en-US", "title", "Main moved on")
        base = self.commit("main moves")
        report = self.report(base, head)
        self.assertIn("PR change", report)
        self.assertNotIn("Main moved on", report)
        self.assertNotEqual(fork, base)


class FakeGh:
    def __init__(self, existing_ids=()):
        self.existing_ids = list(existing_ids)
        self.calls = []

    def __call__(self, cmd):
        if "--paginate" in cmd:
            self.calls.append(("list", cmd))
            return "".join(f"{i}\n" for i in self.existing_ids)
        method = cmd[cmd.index("-X") + 1]
        payload = Path(cmd[cmd.index("--input") + 1]).read_text(encoding="utf-8")
        endpoint = next(a for a in cmd if a.startswith("repos/"))
        self.calls.append((method, endpoint, json.loads(payload)["body"]))
        return "{}"


class UpsertTest(unittest.TestCase):
    REPORT = MARKER + "\n## Custom store listing changes\nbody\n"

    def test_list_query_filters_on_marker(self):
        gh = FakeGh()
        cld.upsert("o/r", "7", self.REPORT, "abc1234", gh)
        _, cmd = gh.calls[0]
        self.assertIn("repos/o/r/issues/7/comments", cmd)
        jq = cmd[cmd.index("--jq") + 1]
        self.assertIn(f'startswith("{MARKER}")', jq)

    def test_change_without_existing_comment_posts(self):
        gh = FakeGh()
        self.assertEqual(cld.upsert("o/r", "7", self.REPORT, "abc1234", gh), "post")
        self.assertEqual(gh.calls[1], ("POST", "repos/o/r/issues/7/comments", self.REPORT))

    def test_change_with_existing_comment_patches_it(self):
        gh = FakeGh(existing_ids=[42, 99])
        self.assertEqual(cld.upsert("o/r", "7", self.REPORT, "abc1234", gh), "patch")
        self.assertEqual(gh.calls[1], ("PATCH", "repos/o/r/issues/comments/42", self.REPORT))
        self.assertEqual(len(gh.calls), 2)

    def test_no_change_without_existing_comment_posts_nothing(self):
        gh = FakeGh()
        self.assertEqual(cld.upsert("o/r", "7", None, "abc1234", gh), "none")
        self.assertEqual(len(gh.calls), 1)

    def test_no_change_with_existing_comment_patches_note(self):
        gh = FakeGh(existing_ids=[42])
        self.assertEqual(cld.upsert("o/r", "7", None, "abc1234", gh), "patch")
        method, endpoint, body = gh.calls[1]
        self.assertEqual((method, endpoint), ("PATCH", "repos/o/r/issues/comments/42"))
        self.assertTrue(body.startswith(MARKER + "\n"), body)
        self.assertIn("No pending custom-listing changes at `abc1234`", body)


class MainTest(GitFixture):
    def test_github_output_flag_and_out_file(self):
        out = self.repo / "gh_output"
        report_file = self.repo / "report.md"
        cwd = os.getcwd()
        os.chdir(self.repo)
        try:
            cld.main(["--base", self.base, "--head", self.base, "--github-output", str(out)])
            self.write_locale("creators", "en-US")
            head = self.commit("seed")
            cld.main(["--base", self.base, "--head", head, "--github-output", str(out),
                      "--out", str(report_file)])
        finally:
            os.chdir(cwd)
        self.assertEqual(out.read_text(), "changed=false\nchanged=true\n")
        self.assertTrue(report_file.read_text(encoding="utf-8").startswith(MARKER + "\n"))


if __name__ == "__main__":
    unittest.main()
