# SPDX-License-Identifier: Apache-2.0
"""Regression checks for example discovery, output paths and failure classification."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from build_examples import artifact_path, discover, main, unsupported_native


class ExampleBuildTest(unittest.TestCase):
    def test_discovery_groups_projects_and_ignores_dependency_snapshots(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def put(name, text=""):
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text, encoding="utf-8")
            put("hello.bhash")
            put("native/hello.bhash")
            put("app/bhash.project", "sources=src")
            put("app/src/main.bhash")
            put("app/src/helper.bhash")
            put("lib/bhash.project", "kind=library")
            put("lib/src/lib.bhash")
            put("app/.bhash/snapshot/bhash.project", "kind=library")
            put("app/.bhash/snapshot/src/cached.bhash")
            units = discover(root)
            self.assertEqual(["app", "hello", "lib", "native/hello"], [u.name for u in units])
            self.assertEqual(2, len(units[0].files))
            self.assertTrue(units[2].library)
            self.assertEqual(5, sum(len(u.files) for u in units))

    def test_names_cannot_collide_or_overlap_output_directories(self):
        for names in [("same.bhash", "Same/bhash.project"), ("demo.bhash", "demo/other.bhash")]:
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                for name in names:
                    p = root / name
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.touch()
                with self.assertRaises(ValueError):
                    discover(root)

    def test_target_paths_preserve_source_subfolders(self):
        root = Path("target/examples")
        self.assertEqual(root / "jvm/native/hello.jar", artifact_path(root, "jvm", "native/hello"))
        self.assertEqual(root / "dotnet/native/hello", artifact_path(root, "clr", "native/hello"))
        self.assertEqual(root / "native/native/hello", artifact_path(root, "native", "native/hello"))

    def test_only_explicit_native_profile_diagnostics_can_be_skipped(self):
        self.assertTrue(unsupported_native(1, "a:1:1: [BH9001] unsupported"))
        for code, message in [(1, "[BH1001] syntax"), (1, "[BH9001] unsupported\n[BH2001] semantic"),
                              (2, "[BH9001] usage"), (1, "Cannot start clang-18"), (0, "[BH9001]")]:
            self.assertFalse(unsupported_native(code, message))

    def test_native_unsupported_report_keeps_failures_visible(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            examples = root / "examples"
            (examples / "native").mkdir(parents=True)
            for name in ["outside.bhash", "bad.bhash", "native/required.bhash"]:
                (examples / name).touch()
            compiler = root / "compiler.jar"
            compiler.touch()
            def compile_call(command, **kwargs):
                message = "[BH1001] bad syntax" if command[command.index("compile") + 1].endswith("bad.bhash") else "[BH9001] unsupported"
                return subprocess.CompletedProcess(command, 1, "", message)
            with patch("build_examples.shutil.which", return_value="tool"), patch("build_examples.native_unavailable", return_value=None), \
                 patch("build_examples.subprocess.run", side_effect=compile_call), contextlib.redirect_stdout(io.StringIO()):
                status = main(["--compiler", str(compiler), "--examples", str(examples), "--output-root", str(root / "out"), "--target", "native"])
            report = json.loads((root / "out/build-report.json").read_text(encoding="utf-8"))
            self.assertEqual(1, status)
            self.assertEqual(["failed", "failed", "unsupported"], [e["status"] for e in report["results"]])
            self.assertFalse(report["success"])

    def test_unavailable_native_is_optional_unless_required(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            examples = root / "examples"
            examples.mkdir()
            (examples / "hello.bhash").touch()
            compiler = root / "compiler.jar"
            compiler.touch()
            with patch("build_examples.shutil.which", return_value="tool"), patch("build_examples.native_unavailable", return_value="not Linux"), \
                 patch("build_examples.subprocess.run") as run, contextlib.redirect_stdout(io.StringIO()):
                args = ["--compiler", str(compiler), "--examples", str(examples), "--output-root", str(root / "out"), "--target", "native"]
                self.assertEqual(0, main(args))
                self.assertEqual(1, main(args + ["--require-native"]))
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
