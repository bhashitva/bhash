# SPDX-License-Identifier: Apache-2.0
import json
from pathlib import Path
import sys
import tempfile
import unittest

from run import clean_env, measure, summary, validate
from workloads import checksum, expected, generate


class BenchmarksTest(unittest.TestCase):
    def test_reference_and_protocol(self):
        case = {"name": "integer", "n": 1000}
        def line(s, t): return {"text": s, "at_s": t, "process": {"cpu_s": t / 2}}
        lines = [line("BHASH_READY", .1), line(f"BHASH_RESULT २ {checksum('integer', 1000, 2)}", .2),
                 line("BHASH_BEGIN ०", .3), line(f"BHASH_RESULT ४ {checksum('integer', 1000, 4)}", .7), line("BHASH_END ०", .8)]
        record = {"stdout": lines, "samples": [{"at_s": .5, "rss_bytes": 1024}]}
        result = validate(case, record, 1)
        self.assertEqual(result["calls_per_s"], 8)
        self.assertEqual(result["measured_rss_median_bytes"], 1024)
        lines[3]["text"] = "BHASH_RESULT ४ ०"
        with self.assertRaisesRegex(ValueError, "Checksum"): validate(case, record, 1)
        self.assertEqual(expected("array-sort", 3), 6)
        self.assertEqual(expected("list-map", 3), 12)

    def test_generated_inputs_are_identical_and_scalable(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a", Path(tmp) / "b"
            self.assertEqual(generate(a, ["small"], 10, 10, 1), generate(b, ["small"], 10, 10, 1))
            self.assertEqual({str(p.relative_to(a)): p.read_bytes() for p in a.rglob('*') if p.is_file()},
                             {str(p.relative_to(b)): p.read_bytes() for p in b.rglob('*') if p.is_file()})
            self.assertEqual(len(list((a / 'project-small/src').glob('*.bhash'))), 11)

    def test_statistics_and_sanitized_environment(self):
        self.assertEqual(summary([1, 3, 2])["median"], 2)
        self.assertIsNone(summary([None]))
        self.assertNotIn("BHASH_GC_STRESS", clean_env())

    @unittest.skipUnless(sys.platform == "linux", "Linux measurement API")
    def test_measure_actual_child_cpu_rss_and_timeout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result = measure([sys.executable, "-c", "x=bytearray(8*1024*1024); print('ok')"], root, clean_env(), 10, root / "ok.json")
            self.assertGreater(result["peak_rss_bytes"], 8 * 1024 * 1024)
            self.assertGreater(result["cpu_s"], 0)
            self.assertEqual(result["stdout"][0]["text"], "ok")
            # A large observer must not become the native application's RSS floor.
            observer_memory = bytearray(32 * 1024 * 1024)
            tiny = measure(["/bin/true"], root, clean_env(), 10, root / "tiny.json")
            if tiny["peak_rss_bytes"] is not None:
                self.assertLess(tiny["peak_rss_bytes"], len(observer_memory) // 2)
            with self.assertRaises(RuntimeError):
                measure([sys.executable, "-c", "import time; time.sleep(20)"], root, clean_env(), .05, root / "timeout.json")
            self.assertTrue(json.loads((root / "timeout.json").read_text())["timed_out"])


if __name__ == "__main__": unittest.main()
