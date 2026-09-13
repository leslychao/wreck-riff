"""File publication contracts; no Blender launch or asset regeneration is required."""
import errno
import gzip
import importlib.util
from pathlib import Path
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

sys.modules.setdefault('bpy', types.ModuleType('bpy'))
spec = importlib.util.spec_from_file_location('vehicle_author', Path(__file__).with_name('author_vehicle_models.py'))
author = importlib.util.module_from_spec(spec)
spec.loader.exec_module(author)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[2] / 'build/combat-graphics-work/vehicles/publication-test'
        root.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.directory.cleanup)
        self.target = Path(self.directory.name) / 'source.glb'
        self.target.write_bytes(b'old-valid-export')

    def test_replaces_only_after_complete_output_and_retains_the_format_suffix(self):
        with author.atomic_output(self.target) as temporary:
            self.assertEqual('.glb', temporary.suffix)
            self.assertEqual(self.target.parent, temporary.parent)
            temporary.write_bytes(b'new-complete-export')
            self.assertEqual(b'old-valid-export', self.target.read_bytes())
        self.assertEqual(b'new-complete-export', self.target.read_bytes())
        self.assertEqual([self.target], list(self.target.parent.iterdir()))

    def test_transient_windows_sharing_violation_retries_without_losing_old_output(self):
        real_replace = author.os.replace
        calls = []
        def sharing_then_release(source, target):
            calls.append(source)
            self.assertEqual(b'old-valid-export', self.target.read_bytes())
            if len(calls) < 3:
                raise PermissionError(errno.EACCES, 'Windows sharing violation')
            real_replace(source, target)
        with patch.object(author.os, 'replace', side_effect=sharing_then_release), patch.object(author.time, 'sleep') as sleep:
            with author.atomic_output(self.target) as temporary:
                temporary.write_bytes(b'new-complete-export')
        self.assertEqual(3, len(calls))
        self.assertEqual(2, sleep.call_count)
        self.assertEqual(b'new-complete-export', self.target.read_bytes())

    def test_persistent_lock_is_bounded_and_preserves_the_existing_export(self):
        with patch.object(author.os, 'replace', side_effect=PermissionError(errno.EACCES, 'still locked')) as replace, patch.object(author.time, 'sleep') as sleep:
            with self.assertRaises(PermissionError):
                with author.atomic_output(self.target) as temporary:
                    temporary.write_bytes(b'new-complete-export')
        self.assertEqual(6, replace.call_count)
        self.assertLessEqual(sum(call.args[0] for call in sleep.call_args_list), 3)
        self.assertEqual(b'old-valid-export', self.target.read_bytes())
        self.assertEqual([self.target], list(self.target.parent.iterdir()))

    def test_failed_or_empty_export_cannot_replace_a_valid_file(self):
        with self.assertRaisesRegex(RuntimeError, 'writer failed'):
            with author.atomic_output(self.target) as temporary:
                temporary.write_bytes(b'partial')
                raise RuntimeError('writer failed')
        with self.assertRaises(OSError):
            with author.atomic_output(self.target):
                pass
        self.assertEqual(b'old-valid-export', self.target.read_bytes())
        self.assertEqual([self.target], list(self.target.parent.iterdir()))

    def test_temporary_names_do_not_change_the_compressed_mesh_bundle(self):
        bundle={'lods': [[{'positions': [0.0, 1.0, 2.0]}]]}
        author.write_mesh_bundle(self.target.parent, bundle)
        path=self.target.parent/'mesh.json.gz';first=path.read_bytes()
        author.write_mesh_bundle(self.target.parent, bundle)
        self.assertEqual(first, path.read_bytes())
        self.assertIn(b'positions', gzip.decompress(first))

    @unittest.skipUnless(sys.platform=='win32', 'Requires actual Windows sharing rules')
    def test_actual_windows_read_handle_releases_during_bounded_publication(self):
        import ctypes
        from ctypes import wintypes
        kernel=ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.CreateFileW.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD,wintypes.DWORD,wintypes.HANDLE]
        kernel.CreateFileW.restype=wintypes.HANDLE
        kernel.CloseHandle.argtypes=[wintypes.HANDLE]
        # A reader that shares reads but not deletion reproduces the actual exporter failure.
        handle=kernel.CreateFileW(str(self.target),0x80000000,1,None,3,0x80,None)
        if handle==wintypes.HANDLE(-1).value:raise ctypes.WinError(ctypes.get_last_error())
        release=threading.Timer(.15, kernel.CloseHandle, args=[handle]);release.start();began=time.monotonic()
        try:
            with author.atomic_output(self.target) as temporary:
                temporary.write_bytes(b'new-after-reader-closed')
        finally:release.join()
        self.assertGreaterEqual(time.monotonic()-began,.1)
        self.assertLess(time.monotonic()-began,3)
        self.assertEqual(b'new-after-reader-closed',self.target.read_bytes())


if __name__ == '__main__':
    unittest.main()
