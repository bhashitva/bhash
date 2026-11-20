# SPDX-License-Identifier: Apache-2.0
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from merge_ffi_catalogs import merge, bundle, digest


class CatalogTests(unittest.TestCase):
    def fixtures(self, root):
        inputs = []
        for host in ("linux-x64", "windows-x64", "macos-arm64"):
            catalog = root / host
            folder = catalog / "artifacts" / host
            folder.mkdir(parents=True)
            artifacts = []
            for kind in ("static", "shared"):
                file = folder / (kind + ".bin")
                file.write_bytes((host + kind).encode())
                artifacts.append(dict(target=host, kind=kind, path=host + "/" + file.name,
                                      sha256=digest(file), sourceSha256="2" * 64, license=host + "/LICENSE.txt"))
            (folder / "LICENSE.txt").write_text("Fixture only")
            identity = dict(abi=1, contractSha256="0" * 64, coreSha256="1" * 64,
                            sourceSha256="2" * 64, archiveSha256=artifacts[0]["sha256"])
            (folder / "provenance.json").write_text(json.dumps(identity))
            (catalog / "compression-artifacts.json").write_text(json.dumps(dict(schemaVersion=1,
                contractSha256="0" * 64, coreSha256="1" * 64, artifacts=artifacts)))
            inputs.append(catalog)
        return inputs

    def test_complete_merge_and_zip_are_independent_of_input_order(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = self.fixtures(root)
            merge(inputs, root / "one")
            merge(list(reversed(inputs)), root / "two")
            bundle(root / "one", root / "one.zip")
            bundle(root / "two", root / "two.zip")
            self.assertEqual(digest(root / "one.zip"), digest(root / "two.zip"))

    def test_partial_or_changed_provider_never_creates_release_output(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = self.fixtures(root)
            with self.assertRaises(ValueError):
                merge(inputs[:2], root / "output")
            self.assertFalse((root / "output").exists())
            (inputs[0] / "artifacts/linux-x64/static.bin").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                merge(inputs, root / "output")
            self.assertFalse((root / "output").exists())

    def test_runtime_mismatch_is_rejected(self):
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = self.fixtures(root)
            path = inputs[0] / "compression-artifacts.json"
            data = json.loads(path.read_text())
            data["coreSha256"] = "9" * 64
            path.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                merge(inputs, root / "output")


if __name__ == "__main__":
    unittest.main()
