"""Pure file-publication checks; no Blender window, rendering or runtime asset writes."""
import importlib.util
import ctypes
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SOURCE = Path(__file__).resolve().parents[1] / "author_combat_vfx.py"
with patch.dict("sys.modules", {name: MagicMock() for name in ("bpy", "numpy", "mathutils")}):
    spec = importlib.util.spec_from_file_location("author_combat_vfx_atomic_test", SOURCE)
    author = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(author)


class AtomicPublicationTest(unittest.TestCase):
    def test_json_publication_uses_checkout_stable_lf_bytes_on_windows(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "recipe.json"
            author.write_json(destination, {"source": "original"})
            self.assertNotIn(b"\r", destination.read_bytes())
            self.assertTrue(destination.read_bytes().endswith(b"\n"))

    def test_empty_export_cannot_replace_a_complete_resource(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "flame.png"
            destination.write_bytes(b"old")
            with self.assertRaisesRegex(OSError, "empty"):
                with author.atomic_output(destination) as temporary:
                    temporary.write_bytes(b"")
            self.assertEqual(b"old", destination.read_bytes())

    @unittest.skipUnless(os.name == "nt", "Real Windows sharing contract")
    def test_real_windows_reader_lock_preserves_resource_until_reader_releases_it(self):
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
        kernel.CreateFileW.restype = ctypes.c_void_p
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "smoke.png"
            destination.write_bytes(b"old")
            handle = kernel.CreateFileW(str(destination), 0x80000000, 3, None, 3, 0x80, None)
            self.assertNotEqual(ctypes.c_void_p(-1).value, handle)
            try:
                with patch.object(author.time, "sleep") as sleep:
                    with self.assertRaises(OSError):
                        with author.atomic_output(destination) as temporary:
                            temporary.write_bytes(b"new")
                self.assertEqual(5, sleep.call_count)
                self.assertEqual(b"old", destination.read_bytes())
                self.assertEqual([destination], list(Path(directory).iterdir()))
            finally:
                kernel.CloseHandle(handle)
            with author.atomic_output(destination) as temporary:
                temporary.write_bytes(b"new")
            self.assertEqual(b"new", destination.read_bytes())

    def test_failed_blender_png_save_cannot_truncate_the_existing_atlas(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "smoke.png"
            destination.write_bytes(b"previous-complete")
            image = MagicMock()

            def failed_save():
                Path(image.filepath_raw).write_bytes(b"partial-render")
                raise RuntimeError("image save failed")

            image.save.side_effect = failed_save
            author.bpy.data.images.new.return_value = image
            pixels = MagicMock()
            pixels.shape = (4, 4, 4)
            with self.assertRaisesRegex(RuntimeError, "image save failed"):
                author.save_pixels(destination, pixels)
            self.assertEqual(b"previous-complete", destination.read_bytes())
            self.assertEqual([destination], list(Path(directory).iterdir()))

    def test_partial_writer_failure_keeps_previous_resource_and_removes_temporary(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "smoke.png"
            destination.write_bytes(b"previous-complete")
            with self.assertRaisesRegex(RuntimeError, "writer failed"):
                with author.atomic_output(destination) as temporary:
                    self.assertEqual(destination.parent, temporary.parent)
                    self.assertEqual(".png", temporary.suffix)
                    temporary.write_bytes(b"partial")
                    self.assertEqual(b"previous-complete", destination.read_bytes())
                    raise RuntimeError("writer failed")
            self.assertEqual(b"previous-complete", destination.read_bytes())
            self.assertEqual([destination], list(Path(directory).iterdir()))

    def test_busy_destination_retries_complete_atomic_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "recipes.json"
            destination.write_bytes(b"old")
            replace = author.os.replace
            attempts = []

            def busy_twice(source, target):
                attempts.append((source, target))
                self.assertEqual(b"complete", Path(source).read_bytes())
                self.assertEqual(b"old", destination.read_bytes())
                if len(attempts) < 3:
                    raise PermissionError(13, "resource mapped")
                replace(source, target)

            with patch.object(author.os, "replace", side_effect=busy_twice), patch.object(author.time, "sleep") as sleep:
                with author.atomic_output(destination) as temporary:
                    temporary.write_bytes(b"complete")
            self.assertEqual(3, len(attempts))
            self.assertEqual(2, sleep.call_count)
            self.assertEqual(b"complete", destination.read_bytes())
            self.assertEqual([destination], list(Path(directory).iterdir()))

    def test_persistent_sharing_failure_is_bounded_and_preserves_previous_file(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "source.blend"
            destination.write_bytes(b"old")
            with patch.object(author.os, "replace", side_effect=PermissionError(13, "busy")) as replace, patch.object(author.time, "sleep") as sleep:
                with self.assertRaises(PermissionError):
                    with author.atomic_output(destination) as temporary:
                        temporary.write_bytes(b"new")
            self.assertEqual(6, replace.call_count)
            self.assertLessEqual(sum(call.args[0] for call in sleep.call_args_list), 2.5)
            self.assertEqual(b"old", destination.read_bytes())
            self.assertEqual([destination], list(Path(directory).iterdir()))

    def test_unrelated_failure_is_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "provenance.json"
            with patch.object(author.os, "replace", side_effect=FileNotFoundError(2, "missing")) as replace, patch.object(author.time, "sleep") as sleep:
                with self.assertRaises(FileNotFoundError):
                    with author.atomic_output(destination) as temporary:
                        temporary.write_bytes(b"manifest")
            self.assertEqual(1, replace.call_count)
            sleep.assert_not_called()
            self.assertEqual([], list(Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
