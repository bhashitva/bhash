# SPDX-License-Identifier: Apache-2.0
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile
from build_native_toolchain import package_owner, write_zip


class NativeToolchainBuilderTest(unittest.TestCase):
    def test_diversion_diagnostics_are_not_package_names(self):
        text = "diversion by libc6 from: /lib64/ld-linux-x86-64.so.2\ndiversion by libc6 to: /lib64/ld-linux-x86-64.so.2.usr-is-merged\nlibc6:amd64: /lib64/ld-linux-x86-64.so.2\n"
        self.assertEqual(package_owner(text), 'libc6:amd64')

    def test_ordinary_ownership_and_no_match(self):
        self.assertEqual(package_owner('clang-18: /usr/lib/llvm-18/bin/clang'), 'clang-18')
        self.assertIsNone(package_owner('local diversion from: /lib64/loader'))

    def test_zip_has_stable_order_timestamps_and_contents(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)/'payload';root.mkdir()
            (root/'z.txt').write_bytes(b'z')
            (root/'a.txt').write_bytes(b'a')
            first, second = Path(temporary)/'first.zip', Path(temporary)/'second.zip'
            write_zip(root, first);write_zip(root, second)
            self.assertEqual(hashlib.sha256(first.read_bytes()).digest(), hashlib.sha256(second.read_bytes()).digest())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(archive.namelist(), ['a.txt', 'z.txt'])
                self.assertEqual(archive.read('a.txt'), b'a')
                self.assertTrue(all(item.date_time == (2026, 1, 1, 0, 0, 0) for item in archive.infolist()))
                self.assertIsNone(archive.testzip())


if __name__ == '__main__':
    unittest.main()
