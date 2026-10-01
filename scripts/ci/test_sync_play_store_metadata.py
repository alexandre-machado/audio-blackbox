"""Oracles exercise real sync decisions through mocked Google request endpoints."""
import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, call, patch

spec = importlib.util.spec_from_file_location(
    "sync_play_store_metadata", Path(__file__).with_name("sync-play-store-metadata.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class FakeHttpError(Exception):
    def __init__(self, status):
        self.resp = types.SimpleNamespace(status=status)


class SyncMetadataTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.service = Mock()
        self.edits = self.service.edits.return_value
        self.edits.insert.return_value.execute.return_value = {"id": "edit"}
        self.edits.commit.return_value.execute.return_value = {"id": "edit"}
        self.listings = self.edits.listings.return_value
        self.images = self.edits.images.return_value
        self.remote_text = {}
        self.remote_images = {}
        self.paths = {}
        for locale in ("en-US", "pt-BR"):
            root = self.base / locale
            root.mkdir()
            self.remote_text[locale] = {
                "title": "Title", "shortDescription": "Short", "fullDescription": "Full"}
            for filename, value in (("title", "Title"), ("short_description", "Short"),
                                    ("full_description", "Full")):
                (root / (filename + ".txt")).write_text("  " + value + "\n")
            for kind, names in (("icon", ["icon.png"]),
                                ("featureGraphic", ["featureGraphic.png"]),
                                ("phoneScreenshots", ["2.png", "1.png"])):
                directory = root / "images"
                if kind == "phoneScreenshots":
                    directory /= kind
                directory.mkdir(parents=True, exist_ok=True)
                paths = []
                hashes = []
                for name in names:
                    path = directory / name
                    data = (locale + kind + name).encode()
                    path.write_bytes(data)
                    paths.append(str(path))
                    hashes.append({"sha256": hashlib.sha256(data).hexdigest()})
                self.paths[locale, kind] = sorted(paths)
                self.remote_images[locale, kind] = {"images": hashes}
        self.listings.get.side_effect = self.get_listing
        self.images.list.side_effect = lambda **kw: Mock(
            execute=Mock(return_value=self.remote_images[kw["language"], kw["imageType"]]))
        self.media = Mock(side_effect=lambda filename, **kw: filename)

    def get_listing(self, **kwargs):
        value = self.remote_text[kwargs["language"]]
        if isinstance(value, Exception):
            return Mock(execute=Mock(side_effect=value))
        return Mock(execute=Mock(return_value=value))

    def run_sync(self, force=False):
        errors = types.ModuleType("googleapiclient.errors")
        errors.HttpError = FakeHttpError
        with patch.dict(sys.modules, {"googleapiclient.errors": errors}), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            sync.sync_metadata(self.service, "package", self.media, str(self.base), force)
        return output.getvalue()

    def assert_committed(self):
        self.edits.commit.assert_called_once_with(packageName="package", editId="edit")
        self.edits.commit.return_value.execute.assert_called_once_with()
        self.edits.delete.assert_not_called()

    def test_nothing_changed(self):
        """Oracle: equal remote text/hashes must cause zero writes and discard the edit."""
        output = self.run_sync()
        self.assertEqual(self.listings.update.call_count, 0, "unchanged text must not be updated")
        self.assertEqual(self.images.deleteall.call_count, 0, "unchanged images must not be deleted")
        self.images.upload.assert_not_called()
        self.edits.commit.assert_not_called()
        self.edits.delete.assert_called_once_with(packageName="package", editId="edit")
        self.edits.delete.return_value.execute.assert_called_once_with()
        self.assertEqual(self.listings.get.call_count, 2)
        self.assertEqual(self.images.list.call_count, 6)
        self.assertIn("Nothing was committed", output)
        self.assertEqual(output.count(": unchanged"), 8)

    def test_one_locale_text_changed(self):
        """Oracle: a changed remote field updates only its locale and commits once."""
        self.remote_text["pt-BR"]["shortDescription"] = "Old"
        self.run_sync()
        self.listings.update.assert_called_once_with(
            packageName="package", editId="edit", language="pt-BR",
            body={"title": "Title", "shortDescription": "Short", "fullDescription": "Full"})
        self.listings.update.return_value.execute.assert_called_once_with()
        self.images.deleteall.assert_not_called()
        self.images.upload.assert_not_called()
        self.assert_committed()

    def test_one_image_hash_changed(self):
        """Oracle: differing hashes replace only that image type, in filename order."""
        self.remote_images["pt-BR", "phoneScreenshots"]["images"][0]["sha256"] = "old"
        self.run_sync()
        params = dict(packageName="package", editId="edit", language="pt-BR",
                      imageType="phoneScreenshots")
        self.images.deleteall.assert_called_once_with(**params)
        self.images.deleteall.return_value.execute.assert_called_once_with()
        self.assertEqual(self.images.upload.call_args_list,
                         [call(**params, media_body=p)
                          for p in self.paths["pt-BR", "phoneScreenshots"]])
        self.assertEqual(self.images.upload.return_value.execute.call_count, 2)
        self.listings.update.assert_not_called()
        self.assert_committed()

    def test_forced_full_sync(self):
        """Oracle: force rewrites all equal text/image sets and commits."""
        self.run_sync(force=True)
        self.assertEqual(self.listings.update.call_count, 2)
        self.assertEqual(self.images.deleteall.call_count, 6)
        self.assertEqual(self.images.upload.call_count, 8)
        self.assert_committed()

    def test_missing_remote_locale(self):
        """Oracle: only a 404 listing is treated as new locale content."""
        self.remote_text["pt-BR"] = FakeHttpError(404)
        self.run_sync()
        self.assertEqual(self.listings.update.call_count, 1)
        self.assertEqual(self.listings.update.call_args.kwargs["language"], "pt-BR")
        self.assert_committed()

    def test_remote_error_does_not_commit(self):
        """Oracle: authorization/server errors must fail instead of publishing."""
        self.remote_text["en-US"] = FakeHttpError(403)
        with self.assertRaises(FakeHttpError):
            self.run_sync()
        self.edits.commit.assert_not_called()

    def test_remote_whitespace_is_normalized(self):
        """Oracle: surrounding whitespace on either side is not a listing change."""
        self.remote_text["en-US"]["title"] = "  Title\n"
        self.run_sync()
        self.listings.update.assert_not_called()
        self.edits.commit.assert_not_called()

    def test_empty_local_image_set_removes_remote_images(self):
        """Oracle: removing the last local image clears only its remote image type."""
        Path(self.paths["en-US", "icon"][0]).unlink()
        self.run_sync()
        self.images.deleteall.assert_called_once_with(
            packageName="package", editId="edit", language="en-US", imageType="icon")
        self.images.upload.assert_not_called()
        self.assert_committed()


if __name__ == "__main__":
    unittest.main()
