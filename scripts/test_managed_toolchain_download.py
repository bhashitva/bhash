#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Real HTTPS download/redirect/TLS tests plus installation repair and doctor rollback."""
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT/'target/managed-toolchain'
JAR = ROOT/'target/bhash-compiler-0.1.0-SNAPSHOT.jar'
JAVA = Path(os.environ['JAVA_HOME'])/'bin/java'
KEYTOOL = Path(os.environ['JAVA_HOME'])/'bin/keytool'
NAME = 'bhash-linux-x86_64-llvm18-v1-jammy.zip'
DIGEST = (ARTIFACTS/(NAME+'.sha256')).read_text().split()[0]


def command(args, *, ok=True, env=None):
    result = subprocess.run([str(a) for a in args], env=env, capture_output=True, text=True, timeout=360)
    output = result.stdout+result.stderr
    if (result.returncode == 0) != ok:
        raise AssertionError(f'{args}: {result.returncode}\n{output}')
    return output


def main():
    with tempfile.TemporaryDirectory(prefix='bhash-https-') as temporary:
        work = Path(temporary)
        key, cert, trust = work/'key.pem', work/'cert.pem', work/'trust.p12'
        command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '1', '-nodes', '-keyout', key, '-out', cert, '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1'])
        command([KEYTOOL, '-importcert', '-noprompt', '-alias', 'fixture', '-file', cert, '-keystore', trust, '-storepass', 'changeit'])
        class Handler(SimpleHTTPRequestHandler):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, directory=ARTIFACTS, **kwargs)
            def log_message(self, *_):
                pass
            def do_GET(self):
                if self.path in ('/redirect', '/downgrade'):
                    self.send_response(302)
                    self.send_header('Location', '/'+NAME if self.path == '/redirect' else 'http://localhost:'+str(self.server.server_port)+'/'+NAME)
                    self.end_headers()
                else:
                    super().do_GET()
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert, key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True);thread.start()
        env = {**os.environ, 'BHASH_TOOLCHAIN_HOME': str(work/'cache'), 'BHASH_NATIVE_TOOLCHAIN': 'managed'}
        def cli(*args, trusted=True, ok=True):
            trust_args = ['-Djavax.net.ssl.trustStore='+str(trust), '-Djavax.net.ssl.trustStorePassword=changeit'] if trusted else []
            return command([JAVA, *trust_args, '-jar', JAR, *args], ok=ok, env=env)
        try:
            base = 'https://localhost:'+str(server.server_port)
            cli('native', 'toolchain', 'install', '--url', base+'/redirect', '--sha256', DIGEST)
            active = work/'cache/active.json'; before = active.read_bytes()
            for url in (base+'/missing', base+'/downgrade'):
                cli('native', 'toolchain', 'install', '--url', url, '--sha256', DIGEST, ok=False)
                assert active.read_bytes() == before
            cli('native', 'toolchain', 'install', '--url', base+'/'+NAME, '--sha256', DIGEST, trusted=False, ok=False)
            assert active.read_bytes() == before
            status = json.loads(cli('native', 'toolchain', 'status', '--json'))
            Path(status['path'], 'README.txt').write_text('corrupt')
            cli('native', 'toolchain', 'verify', ok=False)
            cli('native', 'toolchain', 'install', '--archive', ARTIFACTS/NAME, '--sha256', DIGEST)
            cli('native', 'toolchain', 'verify')
            assert active.read_bytes() != before
            # Valid ZIP/inventory, deliberately incompatible executable; doctor must reject it.
            files = {name: b'#!/bin/sh\necho "clang version 19.1.0"\n' for name in ('bin/clang-18','bin/opt-18','bin/ld.lld','bin/llvm-readelf','libexec/clang-18','libexec/opt-18','libexec/ld.lld','libexec/llvm-readelf','sysroot/usr/include/stdio.h','resource/include/stddef.h')}
            inventory = {name: {'sha256': hashlib.sha256(data).hexdigest(), 'executable': name.startswith(('bin/', 'libexec/'))} for name, data in files.items()}
            files['toolchain.json'] = json.dumps({'format': 1, 'id': 'bhash-linux-x86_64-llvm18-v1', 'target': 'x86_64-unknown-linux-gnu', 'llvm_version': '18.1.8', 'minimum_glibc': '2.39', 'files': inventory}).encode()
            bad = work/'incompatible.zip'
            with zipfile.ZipFile(bad, 'w') as archive:
                for name, data in files.items():
                    archive.writestr(name, data)
            before = active.read_bytes()
            error = cli('native', 'toolchain', 'install', '--archive', bad, '--sha256', hashlib.sha256(bad.read_bytes()).hexdigest(), ok=False)
            assert 'BH9103' in error and active.read_bytes() == before
            assert not list((work/'cache').glob('.install-*'))
            cli('native', 'doctor')
            print('HTTPS, trusted TLS, redirect policy, repair and failed-doctor rollback checks passed')
        finally:
            server.shutdown();server.server_close();thread.join(5)


if __name__ == '__main__':
    main()
