# SPDX-License-Identifier: Apache-2.0
"""Ensure batching cannot silently omit, skip, or reuse native test results."""
from pathlib import Path
import tempfile
import unittest

from test_windows_native_batches import discover, verify


class BatchCoverageTest(unittest.TestCase):
    def test_discovery_rejects_unrecognized_annotations_and_duplicate_names(self):
        self.assertEqual(['first', 'second'], discover('@Test void second(){}\n@Test void first(){}'))
        for source in ('', '@Test public void future(){}', '@Test void same(){} @Test void same(){}'):
            with self.assertRaises(ValueError):
                discover(source)

    def test_reports_must_contain_exactly_the_requested_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'report.xml'
            for cases in ('', '<testcase name="one"/><testcase name="one"/>', '<testcase name="other"/>'):
                report.write_text('<testsuite>' + cases + '</testsuite>', encoding='utf-8')
                with self.assertRaises(RuntimeError):
                    verify(report, ['one'])
            report.write_text('<testsuite><testcase name="one"/></testsuite>', encoding='utf-8')
            verify(report, ['one'])

    def test_skips_errors_and_failures_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'report.xml'
            for status in ('skipped', 'error', 'failure'):
                report.write_text(f'<testsuite><testcase name="one"><{status}/></testcase></testsuite>', encoding='utf-8')
                with self.assertRaises(RuntimeError):
                    verify(report, ['one'])


if __name__ == '__main__':
    unittest.main()
