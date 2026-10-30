# SPDX-License-Identifier: Apache-2.0
"""Ensure batching cannot silently omit, skip, or reuse native test results."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_windows_native_batches import container_command, discover, verify


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

    def test_container_runs_offline_with_explicit_dependencies_and_literal_selector(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            maven = root / 'maven/bin/mvn.cmd'
            maven.parent.mkdir(parents=True)
            maven.write_text('', encoding='utf-8')
            (root / '.m2/repository').mkdir(parents=True)
            environment = {key: str(root / key) for key in
                           ('JAVA_HOME', 'DOTNET_ROOT', 'BHASH_TOOLCHAIN_HOME')}
            for value in environment.values():
                Path(value).mkdir()
            selector = '-Dtest=NativeBackendTest#first+second'
            with patch('test_windows_native_batches.ROOT', root), patch.object(Path, 'home', return_value=root):
                command = container_command([str(maven), selector, 'test'], environment)
            self.assertEqual(['docker', 'run', '--rm', '--network', 'none'], command[:5])
            self.assertIn('BHASH_NATIVE_TOOLCHAIN=managed', command)
            self.assertIn('BHASH_REQUIRE_NATIVE=true', command)
            self.assertIn('BHASH_REQUIRE_CLR=true', command)
            self.assertEqual([r'C:\maven\bin\mvn.cmd', '-o', r'-Dmaven.repo.local=C:\m2', selector, 'test'], command[-5:])
            self.assertNotIn('shell=True', command)


if __name__ == '__main__':
    unittest.main()
