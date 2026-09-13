"""Offline importer contract tests: python -m unittest discover -s src/tools -p test_import_assets.py."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image

import import_assets as importer


class RuntimeDiffuseImportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_rgb8_source_is_copied_byte_for_byte_without_java_or_network(self):
        source, destination = self.root / "source.png", self.root / "runtime.png"
        Image.new("RGB", (2048, 2048), (17, 83, 151)).save(source)
        with patch.object(importer.subprocess, "run") as java, patch.object(importer, "fetch") as fetch:
            self.assertEqual("Unmodified 2K PNG; sRGB color", importer.runtime_diffuse(source, destination))
            self.assertEqual(source.read_bytes(), destination.read_bytes())
            java.assert_not_called()
            fetch.assert_not_called()

    def test_rejects_wrong_dimensions_without_writing_destination(self):
        source, destination = self.root / "source.png", self.root / "runtime.png"
        Image.new("RGB", (32, 32)).save(source)
        with self.assertRaisesRegex(ValueError, "Expected 2K"):
            importer.runtime_diffuse(source, destination)
        self.assertFalse(destination.exists())

    def test_offline_mode_updates_only_diffuse_hash_and_transformation_and_never_fetches(self):
        resources = self.root / "resources"
        source = self.root / "source.png"
        Image.new("RGB", (2048, 2048), (31, 57, 89)).save(source)
        diffuse = dict(path="textures/materials/asphalt_02/diffuse.png", sourcePath="source.png",
            sourceSha256=importer.sha(source.read_bytes()), sha256="old", transformation="old",
            author="preserve author", license="CC0-1.0", acquired="2026-09-09", sourceUrl="preserve URL")
        untouched = dict(path="textures/materials/asphalt_02/normal.png", sha256="unchanged")
        manifest = resources / "licenses/asset-provenance.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps(dict(schemaVersion=1, assets=[diffuse, untouched])), encoding="utf-8")
        with patch.object(importer, "ROOT", self.root), patch.object(importer, "RESOURCES", resources), \
                patch.object(importer, "fetch", side_effect=AssertionError("Offline mode fetched data")):
            importer.runtime_diffuse_only()
        after = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(untouched, after["assets"][1])
        self.assertEqual({**diffuse, "sha256": diffuse["sourceSha256"],
            "transformation": "Unmodified 2K PNG; sRGB color"}, after["assets"][0])
        self.assertEqual(source.read_bytes(), (resources / diffuse["path"]).read_bytes())
        before_failure = manifest.read_bytes()
        source.write_bytes(b"changed source")
        with patch.object(importer, "ROOT", self.root), patch.object(importer, "RESOURCES", resources):
            with self.assertRaisesRegex(ValueError, "Original diffuse source hash mismatch"):
                importer.runtime_diffuse_only()
        self.assertEqual(before_failure, manifest.read_bytes())


if __name__ == "__main__":
    unittest.main()
