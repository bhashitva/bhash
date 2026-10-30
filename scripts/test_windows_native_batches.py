#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run every native backend case in bounded, fresh Maven/JVM invocations.

Windows CI can exhaust helper-process resources during this large cross-target
suite, after which even a new diagnostic PowerShell cannot initialize. CI uses
a fresh Windows container for each group to isolate OS resources as well as JVM
state, without retrying tests or changing the compiler/runtime under test.
Surefire XML is checked so a selector or discovery change cannot lose coverage.
"""
import argparse
import os
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


def container_command(command, environment):
    """Use the host's already-built sources, pinned SDK and offline Maven cache."""
    maven = Path(environment.get('BHASH_TEST_MAVEN_HOME',
                                 str(Path(command[0]).resolve().parent.parent)))
    java = Path(environment['JAVA_HOME']).resolve()
    dotnet = Path(environment['DOTNET_ROOT']).resolve()
    sdk = Path(environment['BHASH_TOOLCHAIN_HOME']).resolve()
    repository = Path.home() / '.m2/repository'
    for directory in (maven, java, dotnet, sdk, repository):
        if not directory.is_dir():
            raise RuntimeError(f'Missing Windows test dependency: {directory}')
    if not (maven / 'bin/mvn.cmd').is_file():
        raise RuntimeError(f'Missing Windows Maven launcher in {maven}')
    result = ['docker', 'run', '--rm', '--network', 'none', '--workdir', r'C:\repo']
    for source, destination in ((ROOT, r'C:\repo'), (maven, r'C:\maven'),
                                (java, r'C:\java'), (dotnet, r'C:\dotnet'),
                                (sdk, r'C:\toolchains'), (repository, r'C:\m2')):
        result.extend(['-v', f'{source}:{destination}'])
    settings = {
        'JAVA_HOME': r'C:\java', 'DOTNET_ROOT': r'C:\dotnet',
        'PATH': r'C:\java\bin;C:\dotnet;C:\Windows\system32;C:\Windows',
        'BHASH_TOOLCHAIN_HOME': r'C:\toolchains', 'BHASH_NATIVE_TOOLCHAIN': 'managed',
        'BHASH_REQUIRE_NATIVE': 'true', 'BHASH_REQUIRE_CLR': 'true',
        'DOTNET_CLI_TELEMETRY_OPTOUT': '1',
    }
    for key, value in settings.items():
        result.extend(['-e', f'{key}={value}'])
    result.extend(['mcr.microsoft.com/windows/servercore:ltsc2022',
                   'cmd.exe', '/d', '/c', r'C:\maven\bin\mvn.cmd',
                   '-o', r'-Dmaven.repo.local=C:\m2', *command[1:]])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven', default=shutil.which('mvn') or 'mvn')
    parser.add_argument('--maven-arg', action='append', default=[])
    parser.add_argument('--batch-size', type=int, default=12)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--windows-container', action='store_true',
                        help='Run each batch in a fresh, offline Windows Server Core container')
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 20:
        parser.error('--batch-size must be between 1 and 20')
    if args.windows_container:
        # PATH may contain a Chocolatey shim instead of Maven's actual bin.
        version = subprocess.run([args.maven, '--version'], capture_output=True,
                                 text=True, check=True).stdout
        version = re.sub(r'\x1b\[[0-9;]*m', '', version)
        home = re.search(r'^Maven home:\s*(.+)$', version, re.MULTILINE)
        if not home:
            raise RuntimeError('Cannot identify installed Maven home for the Windows container')
        os.environ['BHASH_TEST_MAVEN_HOME'] = home.group(1).strip()
    source = ROOT / 'src/test/java/dev/bhash/NativeBackendTest.java'
    methods = discover(source.read_text(encoding='utf-8'))
    batches = [methods[i:i + args.batch_size] for i in range(0, len(methods), args.batch_size)]
    print(f'{len(methods)} native tests in {len(batches)} fresh JVM batches; no retries', flush=True)
    for number, batch in enumerate(batches, 1):
        reports = ROOT / 'target/surefire-reports'
        suffix = f'windows-native-batch-{number:02}'
        report = reports / f'TEST-dev.bhash.NativeBackendTest-{suffix}.xml'
        if not args.dry_run:
            reports.mkdir(parents=True, exist_ok=True)
            report.unlink(missing_ok=True)  # Never accept a previous run's report.
        selector = 'NativeBackendTest#' + '+'.join(batch)
        command = [args.maven, '--batch-mode', '--no-transfer-progress', *args.maven_arg,
                   f'-Dtest={selector}', '-DargLine=-Xmx768m',
                   f'-Dsurefire.reportNameSuffix={suffix}', 'test']
        print(f'Batch {number}/{len(batches)}: ' + ', '.join(batch), flush=True)
        if args.windows_container:
            command = container_command(command, os.environ)
        if not args.dry_run:
            subprocess.run(command, cwd=ROOT, check=True)
            verify(report, batch)
    print(f'All {len(methods)} native tests ' + ('selected' if args.dry_run else 'passed without skips'), flush=True)


if __name__ == '__main__':
    main()
