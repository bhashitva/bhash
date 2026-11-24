#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Real HTTPS index authentication, redirect-origin isolation and offline cache checks."""
import argparse
from contextlib import ExitStack
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/bhash-compiler-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--trust-cert', type=Path, action='append', default=[],
                        help='Additional public CA certificate already trusted by the test host (test trust store only)')
    args = parser.parse_args()
    openssl = shutil.which('openssl')
    if not openssl:
        openssl = next((str(p) for p in [Path('C:/Program Files/Git/usr/bin/openssl.exe'), Path('C:/Program Files/Git/mingw64/bin/openssl.exe')] if p.is_file()), None)
    if not openssl:
        raise SystemExit('OpenSSL is required for the local TLS fixture')
    with tempfile.TemporaryDirectory(prefix='package-https-', dir=ROOT / 'target') as temporary, ExitStack() as stack:
        work = Path(temporary).resolve()
        subprocess.run([openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(work/'key.pem'),
                        '-out', str(work/'cert.pem'), '-days', '1', '-subj', '/CN=localhost',
                        '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1'], check=True, capture_output=True, timeout=30)
        subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(work/'cert.pem'),
                        '-keystore', str(work/'trust.p12'), '-storetype', 'PKCS12', '-storepass', 'fixture-only'],
                       check=True, capture_output=True, timeout=30)
        for number, certificate in enumerate(args.trust_cert):
            subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', f'host-ca-{number}', '-file', str(certificate.resolve()),
                            '-keystore', str(work/'trust.p12'), '-storepass', 'fixture-only'],
                           check=True, capture_output=True, timeout=30)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(work/'cert.pem', work/'key.pem')
        index = (ROOT/'packages/index.json').read_bytes()
        assert json.loads(index)['packages']
        requests = []
        ports = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                credential = self.headers.get('Authorization')
                requests.append((self.server.server_port, self.path, credential))
                if self.path == '/redirect':
                    self.send_response(302)
                    self.send_header('Location', f'https://localhost:{ports[1]}/other-origin')
                    self.end_headers()
                    return
                if self.path == '/downgrade':
                    self.send_response(302)
                    self.send_header('Location', 'http://localhost/index')
                    self.end_headers()
                    return
                if self.path == '/other-origin':
                    assert credential is None, 'Credential forwarded to another port/origin'
                elif credential != 'Bearer fixture-token-not-real':
                    self.send_response(401)
                    self.end_headers()
                    return
                if self.path == '/oversized':
                    body = b' ' * (2*1024*1024+1)
                elif self.path == '/local-git':
                    value = json.loads(index)
                    value['packages'][0]['git'] = work.as_uri()
                    body = json.dumps(value).encode()
                elif self.path == '/bad':
                    body = b'{"indexVersion":1,"indexVersion":1,"packages":[]}'
                else:
                    body = index
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                    pass  # Oversized/invalid responses are deliberately abandoned by the client.

        for _ in range(2):
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.socket = context.wrap_socket(server.socket, server_side=True)
            ports.append(server.server_port)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            stack.callback(server.server_close)
            stack.callback(server.shutdown)

        def cli(path, extra=(), success=True, host='localhost'):
            env = os.environ | {'BHASH_PACKAGES_HOST': host, 'BHASH_PACKAGES_TOKEN': 'fixture-token-not-real'}
            result = subprocess.run(['java', f'-Dbhash.packages.cache={work}/cache',
                                     f'-Djavax.net.ssl.trustStore={work}/trust.p12', '-Djavax.net.ssl.trustStorePassword=fixture-only',
                                     '-jar', str(args.jar.resolve()), 'package', 'info', 'bhash-text-utils@1.0.1',
                                     '--index', f'https://localhost:{ports[0]}{path}', *extra],
                                    capture_output=True, text=True, encoding='utf-8', env=env, timeout=45)
            assert (result.returncode == 0) == success, result.stdout + result.stderr
            assert 'fixture-token-not-real' not in result.stdout + result.stderr
            return result

        cli('/index', ['--index-sha256', hashlib.sha256(index).hexdigest()])
        count = len(requests)
        cli('/index', ['--offline'])
        assert len(requests) == count, 'Offline lookup made a request'
        cli('/index', ['--index-sha256', '0'*64], success=False)
        cli('/index', success=False, host='not-this-host.invalid')
        cli('/redirect')
        assert (ports[1], '/other-origin', None) in requests
        for path in ['/downgrade', '/oversized', '/local-git', '/bad']:
            cli(path, success=False)
            cli(path, ['--offline'], success=False)  # Failed downloads must not populate a usable cache.
        print('PASS HTTPS trust/auth, credential redirect isolation, checksum/size/schema/authority checks and offline cache')


if __name__ == '__main__':
    main()
