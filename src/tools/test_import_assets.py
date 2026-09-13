"""Offline importer contract tests: python -m unittest discover -s src/tools -p test_import_assets.py."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image

import import_assets as importer
import import_environment_materials as environment


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

    def test_offline_mode_prepares_intermediate_without_touching_dds_or_license_index(self):
        resources = self.root / "resources"
        source = self.root / "src/tools/assets/materials/asphalt_02/diff.png"
        source.parent.mkdir(parents=True)
        Image.new("RGB", (2048, 2048), (31, 57, 89)).save(source)
        diffuse = dict(path="textures/materials/asphalt_02/diffuse.dds", sourcePath=source.relative_to(self.root).as_posix(),
            sourceSha256=importer.sha(source.read_bytes()), sha256="old", transformation="old",
            author="preserve author", license="CC0-1.0", acquired="2026-09-09", sourceUrl="preserve URL")
        untouched = dict(path="textures/materials/asphalt_02/normal.png", sha256="unchanged")
        manifest = resources / "licenses/asset-provenance.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps(dict(schemaVersion=1, assets=[diffuse, untouched])), encoding="utf-8")
        before = manifest.read_bytes()
        with patch.object(importer, "ROOT", self.root), patch.object(importer, "RESOURCES", resources), \
                patch.object(importer, "fetch", side_effect=AssertionError("Offline mode fetched data")):
            importer.runtime_diffuse_only()
        self.assertEqual(before, manifest.read_bytes())
        self.assertEqual(source.read_bytes(), source.with_name("runtime-diffuse.png").read_bytes())
        self.assertFalse((resources / "textures/materials/asphalt_02/diffuse.png").exists())
        self.assertFalse((resources / diffuse["path"]).exists())
        before_failure = manifest.read_bytes()
        source.write_bytes(b"changed source")
        with patch.object(importer, "ROOT", self.root), patch.object(importer, "RESOURCES", resources):
            with self.assertRaisesRegex(ValueError, "Original diffuse source hash mismatch"):
                importer.runtime_diffuse_only()
        self.assertEqual(before_failure, manifest.read_bytes())

    def test_environment_diffuse_only_keeps_existing_dds_hash_and_unrelated_entries(self):
        resources = self.root / "src/main/resources"
        sources = self.root / "src/tools/assets/materials"
        source = sources / "dirt/diff.png"
        source.parent.mkdir(parents=True)
        Image.new("RGB", (2048, 2048), (10, 20, 30)).save(source)
        diffuse = dict(path="textures/materials/dirt/diffuse.dds", sourcePath=source.relative_to(self.root).as_posix(),
            sourceSha256=importer.sha(source.read_bytes()), sha256="existing compressed bytes", transformation="existing DDS recipe")
        manifest = resources / "licenses/asset-provenance.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps(dict(schemaVersion=1, assets=[diffuse, {"path": "unrelated", "sha256": "untouched"}])))
        before = manifest.read_bytes()
        with patch.object(environment, "ROOT", self.root), patch.object(environment, "RESOURCES", resources), \
                patch.object(environment, "SOURCES", sources), patch.object(importer, "ROOT", self.root), \
                patch.object(importer, "RESOURCES", resources), patch.object(environment, "fetch", side_effect=AssertionError("Unexpected download")):
            environment.prepare(selected=["dirt"], maps=["diffuse"])
        self.assertEqual(before, manifest.read_bytes())
        self.assertEqual(source.read_bytes(), source.with_name("runtime-diffuse.png").read_bytes())
        self.assertFalse((resources / "textures/materials/dirt/diffuse.png").exists())
        source.write_bytes(b"altered approved original")
        with patch.object(environment, "ROOT", self.root), patch.object(environment, "RESOURCES", resources), \
                patch.object(environment, "SOURCES", sources), patch.object(importer, "ROOT", self.root), \
                patch.object(importer, "RESOURCES", resources):
            with self.assertRaisesRegex(ValueError, "Original diffuse source hash mismatch"):
                environment.prepare(selected=["dirt"], maps=["diffuse"])
        self.assertEqual(before, manifest.read_bytes())

    def test_unprepared_import_has_empty_dds_hash_and_preserves_other_licensed_maps(self):
        resources = self.root / "src/main/resources"
        sources = self.root / "src/tools/assets/materials"
        source = sources / "dirt/diff.png"
        source.parent.mkdir(parents=True)
        Image.new("RGB", (2048, 2048), (10, 20, 30)).save(source)
        untouched = {"path": "textures/materials/dirt/normal.png", "sha256": "unchanged"}
        manifest = resources / "licenses/asset-provenance.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps(dict(schemaVersion=1, assets=[untouched])))
        with patch.object(environment, "ROOT", self.root), patch.object(environment, "RESOURCES", resources), \
                patch.object(environment, "SOURCES", sources), patch.object(importer, "ROOT", self.root), \
                patch.object(importer, "RESOURCES", resources):
            environment.prepare(selected=["dirt"], maps=["diffuse"])
        entries = json.loads(manifest.read_text())["assets"]
        self.assertIn(untouched, entries)
        diffuse = next(item for item in entries if item["path"].endswith("/diffuse.dds"))
        self.assertEqual("", diffuse["sha256"])
        self.assertEqual(importer.sha(source.read_bytes()), diffuse["sourceSha256"])
        self.assertFalse((resources / diffuse["path"]).exists())
        self.assertFalse((resources / "textures/materials/dirt/diffuse.png").exists())

    def test_full_import_vehicle_derivatives_consume_intermediate_and_merge_licensed_maps(self):
        resources = self.root / "src/main/resources"
        sources = self.root / "src/tools/assets"
        folder = sources / "materials/blue_metal_plate"
        folder.mkdir(parents=True)
        for filename, color in [("diff.png", (80, 80, 80)), ("nor_gl.png", (128, 128, 255)), ("rough.png", (100, 100, 100))]:
            Image.new("RGB", (2048, 2048), color).save(folder / filename)
        manifest = resources / "licenses/asset-provenance.json"
        manifest.parent.mkdir(parents=True)
        untouched = {"path": "textures/materials/dirt/diffuse.dds", "sha256": "other importer"}
        manifest.write_text(json.dumps(dict(schemaVersion=1, assets=[untouched])))
        def prepared_diffuse(source, destination, java=None):
            # A visibly distinct decoded intermediate proves derived maps do not
            # read the original or a removed runtime PNG behind the importer's back.
            image = Image.new("RGB", (2048, 2048), (30, 30, 30))
            image.paste((220, 220, 220), (1024, 0, 2048, 2048))
            image.save(destination)
            return "test decoded intermediate"
        with patch.object(importer, "ROOT", self.root), patch.object(importer, "RESOURCES", resources), \
                patch.object(importer, "SOURCES", sources), patch.object(importer, "ENTRIES", []), \
                patch.object(importer, "ORIGINAL_MATERIALS", {"blue_metal_plate": "Rob Tuytel"}), \
                patch.object(importer, "runtime_diffuse", side_effect=prepared_diffuse), patch.object(importer, "fetch", return_value=b""):
            importer.materials()
            importer.merge_material_entries(importer.ENTRIES)
        for filename in ("paint.png", "rubber.png"):
            with Image.open(resources / "textures/vehicle" / filename) as derivative:
                self.assertLess(derivative.getpixel((0, 0))[0], derivative.getpixel((2047, 0))[0])
        self.assertIn(untouched, json.loads(manifest.read_text())["assets"])
        self.assertFalse((resources / "textures/materials/blue_metal_plate/diffuse.png").exists())

    def test_offline_rebuild_restores_leafy_png_without_dds_or_intermediate(self):
        resources = self.root / "src/main/resources"
        source = self.root / "src/tools/assets/materials/leafy_grass/diff.png"
        source.parent.mkdir(parents=True)
        Image.new("RGB", (2048, 2048), (29, 115, 39)).save(source)
        leaf = dict(path="textures/materials/leafy_grass/diffuse.png", sourcePath=source.relative_to(self.root).as_posix(),
            sourceSha256=importer.sha(source.read_bytes()), sha256=importer.sha(source.read_bytes()), transformation="approved PNG")
        manifest = resources / "licenses/asset-provenance.json"
        manifest.parent.mkdir(parents=True)
        manifest.write_text(json.dumps(dict(schemaVersion=1, assets=[leaf])))
        before = manifest.read_bytes()
        with patch.object(importer, "ROOT", self.root), patch.object(importer, "RESOURCES", resources), \
                patch.object(importer, "fetch", side_effect=AssertionError("Unexpected download")):
            importer.runtime_diffuse_only()
        self.assertEqual(source.read_bytes(), (resources / leaf["path"]).read_bytes())
        self.assertFalse((resources / "textures/materials/leafy_grass/diffuse.dds").exists())
        self.assertFalse(source.with_name("runtime-diffuse.png").exists())
        self.assertEqual(before, manifest.read_bytes())

    def test_environment_leafy_import_signs_only_lossless_png_and_preserves_dds_entries(self):
        resources = self.root / "src/main/resources"
        sources = self.root / "src/tools/assets/materials"
        source = sources / "leafy_grass/diff.png"
        source.parent.mkdir(parents=True)
        Image.new("RGB", (2048, 2048), (29, 115, 39)).save(source)
        manifest = resources / "licenses/asset-provenance.json"
        manifest.parent.mkdir(parents=True)
        untouched = {"path": "textures/materials/dirt/diffuse.dds", "sha256": "prepared DDS"}
        manifest.write_text(json.dumps(dict(schemaVersion=1, assets=[untouched])))
        with patch.object(environment, "ROOT", self.root), patch.object(environment, "RESOURCES", resources), \
                patch.object(environment, "SOURCES", sources), patch.object(importer, "ROOT", self.root), \
                patch.object(importer, "RESOURCES", resources):
            environment.prepare(selected=["leafy_grass"], maps=["diffuse"])
        entries = json.loads(manifest.read_text())["assets"]
        self.assertIn(untouched, entries)
        leaf = next(item for item in entries if item["path"] == "textures/materials/leafy_grass/diffuse.png")
        self.assertEqual(importer.sha(source.read_bytes()), leaf["sha256"])
        self.assertEqual(source.read_bytes(), (resources / leaf["path"]).read_bytes())
        self.assertFalse((resources / "textures/materials/leafy_grass/diffuse.dds").exists())
        self.assertFalse(source.with_name("runtime-diffuse.png").exists())


if __name__ == "__main__":
    unittest.main()
