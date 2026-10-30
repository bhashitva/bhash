#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run every native backend case in bounded, fresh Maven/JVM invocations.

Windows CI can exhaust helper-process resources during this large cross-target
suite, after which even a new diagnostic PowerShell cannot initialize. Releasing
the JVM between groups bounds retained process resources without retrying tests.
Surefire XML is checked so a selector or discovery change cannot lose coverage.
"""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def discover(source):
    methods = re.findall(r'@Test\s+void\s+(\w+)\s*\(', source)
    if not methods or len(methods) != len(re.findall(r'@Test\b', source)):
        raise ValueError('Unrecognized @Test declaration; update batch discovery before running')
    if len(set(methods)) != len(methods):
        raise ValueError('Duplicate test names are not supported by method selectors')
    return sorted(methods)


def verify(report, expected):
    suite = ET.parse(report).getroot()
    cases = suite.findall('testcase')
    actual = [case.attrib['name'] for case in cases]
    if sorted(actual) != sorted(expected):
        raise RuntimeError(f'Incomplete batch coverage in {report}: expected {expected}, got {actual}')
    if any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')):
        raise RuntimeError(f'Failed or skipped test in {report}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', default=shutil.which('mvn') or 'mvn')
    parser.add_argument('--maven-arg', action='append', default=[])
    parser.add_argument('--batch-size', type=int, default=12)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 20:
        parser.error('--batch-size must be between 1 and 20')
    source = ROOT / 'src/test/java/dev/bhash/NativeBackendTest.java'
    methods = discover(source.read_text(encoding='utf-8'))
    batches = [methods[i:i + args.batch_size] for i in range(0, len(methods), args.batch_size)]
    print(f'{len(methods)} native tests in {len(batches)} fresh JVM batches; no retries', flush=True)
    for number, batch in enumerate(batches, 1):
        reports = ROOT / f'target/surefire-reports/windows-native-batch-{number:02}'
        report = reports / 'TEST-dev.bhash.NativeBackendTest.xml'
        if not args.dry_run:
            reports.mkdir(parents=True, exist_ok=True)
            report.unlink(missing_ok=True)  # Never accept a previous run's report.
        selector = 'NativeBackendTest#' + '+'.join(batch)
        command = [args.maven, '--batch-mode', '--no-transfer-progress', *args.maven_arg,
                   f'-Dtest={selector}', '-DargLine=-Xmx768m',
                   f'-Dsurefire.reportsDirectory={reports}', 'test']
        print(f'Batch {number}/{len(batches)}: ' + ', '.join(batch), flush=True)
        if not args.dry_run:
            subprocess.run(command, cwd=ROOT, check=True)
            verify(report, batch)
    print(f'All {len(methods)} native tests ' + ('selected' if args.dry_run else 'passed without skips'), flush=True)


if __name__ == '__main__':
    main()
