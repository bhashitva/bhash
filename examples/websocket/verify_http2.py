#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify RFC 8441 through the gateway and the compiled Bhash echo fixture."""
import asyncio
from collections import defaultdict
from contextlib import ExitStack, contextmanager
import os
from pathlib import Path
import runpy
import socket
import ssl
import struct
import subprocess
import sys
from tempfile import TemporaryDirectory
from time import monotonic
from queue import Queue
from threading import Thread
from types import SimpleNamespace

from h2.config import H2Configuration
from h2.connection import H2Connection
from h2.errors import ErrorCodes
from h2.events import (
    ConnectionTerminated, DataReceived, RemoteSettingsChanged, ResponseReceived,
    StreamEnded, StreamReset,
)
from h2.settings import SettingCodes

from verify import frame

AUTHORITY = "bhash.test"
ORIGIN = "https://" + AUTHORITY
GATEWAY = Path(__file__).resolve().parents[2] / "deploy/http2-websocket/gateway.py"


@contextmanager
def process(command, env=None):
    child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             encoding="utf-8", errors="replace", env=env)
    ready = Queue()
    log = []

    def collect():
        for line in child.stdout:
            label, _, value = line.strip().partition(" ")
            if label in ("READY", "PORT", "SESSION"):
                ready.put((label, value))
            if label != "SESSION":
                log.append(line.rstrip())
        ready.put((None, None))

    thread = Thread(target=collect, daemon=True)
    thread.start()

    def expect(label):
        key, value = ready.get(timeout=40)
        assert key == label, (key, label, log)
        return value

    try:
        yield expect
    except BaseException:
        print("\n".join(log), file=sys.stderr)
        raise
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        thread.join(timeout=2)
        child.stdout.close()


class Peer:
    def __init__(self, wire):
        self.wire = wire
        self.h2 = H2Connection(H2Configuration(client_side=True, header_encoding="ascii"))
        self.responses = {}
        self.data = defaultdict(bytearray)
        self.end = set()
        self.reset = {}
        self.stalled = None
        self.connect_enabled = False
        self.h2.initiate_connection()
        # A small receive window makes a stalled WebSocket reproducible.
        self.h2.update_settings({SettingCodes.INITIAL_WINDOW_SIZE: 1024})
        self.flush()
        self.until(lambda: self.connect_enabled)

    def flush(self):
        data = self.h2.data_to_send()
        if data:
            self.wire.sendall(data)

    def receive(self):
        chunk = self.wire.recv(16384)
        assert chunk, "HTTP/2 connection ended early"
        for event in self.h2.receive_data(chunk):
            if isinstance(event, RemoteSettingsChanged):
                setting = event.changed_settings.get(SettingCodes.ENABLE_CONNECT_PROTOCOL)
                if setting is not None:
                    self.connect_enabled = setting.new_value == 1
            elif isinstance(event, ResponseReceived):
                self.responses[event.stream_id] = dict(event.headers)
            elif isinstance(event, DataReceived):
                self.data[event.stream_id].extend(event.data)
                if event.stream_id == self.stalled:
                    if event.flow_controlled_length:
                        self.h2.increment_flow_control_window(event.flow_controlled_length)
                else:
                    self.h2.acknowledge_received_data(event.flow_controlled_length, event.stream_id)
            elif isinstance(event, StreamEnded):
                self.end.add(event.stream_id)
            elif isinstance(event, StreamReset):
                self.reset[event.stream_id] = event.error_code
            elif isinstance(event, ConnectionTerminated):
                raise AssertionError(f"Unexpected GOAWAY: {event.error_code}")
        self.flush()

    def until(self, condition):
        deadline = monotonic() + 20
        while not condition():
            assert monotonic() < deadline, "HTTP/2 acceptance condition timed out"
            self.receive()

    def headers(self, path, method="GET", authority=AUTHORITY, extra=()):
        stream = self.h2.get_next_available_stream_id()
        headers = [(":method", method), (":scheme", "https"),
                   (":authority", authority), (":path", path)]
        if method == "CONNECT":
            headers += [(":protocol", "websocket")]
        self.h2.send_headers(stream, headers + list(extra), end_stream=method != "CONNECT")
        self.flush()
        self.until(lambda: stream in self.responses or stream in self.reset)
        return stream, self.responses.get(stream, {})

    def send(self, stream, frame_bytes):
        while frame_bytes:
            room = min(self.h2.local_flow_control_window(stream), self.h2.max_outbound_frame_size)
            if room <= 0:
                self.until(lambda: self.h2.local_flow_control_window(stream) > 0)
                continue
            self.h2.send_data(stream, frame_bytes[:room])
            frame_bytes = frame_bytes[room:]
            self.flush()

    def frame(self, stream):
        data = self.data[stream]
        self.until(lambda: len(data) >= 2)
        first, second = data[:2]
        assert not (first & 0x70 or second & 0x80)
        count, start = second, 2
        if count in (126, 127):
            start += 2 if count == 126 else 8
            self.until(lambda: len(data) >= start)
            count = int.from_bytes(data[2:start], "big")
        self.until(lambda: len(data) >= start + count)
        payload = bytes(data[start:start + count])
        del data[:start + count]
        return first & 15, bool(first & 128), payload


def verify(wire, token):
    peer = Peer(wire)
    auth = [("origin", ORIGIN), ("cookie", "other=1"),
            ("cookie", "__Host-bhash-session=" + token),
            ("sec-websocket-version", "13"), ("sec-websocket-protocol", "bhash.echo")]

    _, result = peer.headers("/echo/test", "CONNECT", "other.test", auth)
    assert result[":status"] == "421", result
    _, result = peer.headers("/echo/test", "CONNECT", extra=auth[1:])
    assert result[":status"] == "403", result
    _, result = peer.headers("/echo/test", "CONNECT", extra=[h for h in auth if h[0] != "cookie"])
    assert result[":status"] == "401", result
    _, result = peer.headers("/echo/test", "CONNECT",
                             extra=[(k, "12" if k == "sec-websocket-version" else v) for k, v in auth])
    assert result[":status"] == "426" and result["sec-websocket-version"] == "13", result

    stalled, result = peer.headers("/echo/stalled", "CONNECT", extra=auth)
    assert result == {":status": "200", "sec-websocket-protocol": "bhash.echo"}, result
    active, result = peer.headers("/echo/active", "CONNECT", extra=auth)
    assert result == {":status": "200", "sec-websocket-protocol": "bhash.echo"}, result
    peer.stalled = stalled
    peer.send(stalled, frame(2, bytes(range(256)) * 32))
    peer.until(lambda: len(peer.data[stalled]) == 1024)
    assert stalled not in peer.end

    health, result = peer.headers("/health")
    assert result[":status"] == "200"
    peer.until(lambda: health in peer.end)
    assert bytes(peer.data[health]).decode() == "तैयार"

    greeting = "नमस्ते, HTTP/2 😀".encode()
    peer.send(active, frame(1, greeting[:2], final=False))
    peer.send(active, frame(9, b"ping") + frame(0, greeting[2:]))
    pong = peer.frame(active)
    assert pong == (10, True, b"ping"), pong
    assert peer.frame(active) == (1, True, greeting)

    peer.h2.reset_stream(stalled, error_code=ErrorCodes.CANCEL)
    peer.flush()
    peer.send(active, frame(1, b"still alive"))
    assert peer.frame(active) == (1, True, b"still alive")
    close = struct.pack("!H", 1000)
    peer.send(active, frame(8, close))
    assert peer.frame(active) == (8, True, close)
    peer.until(lambda: active in peer.end)
    health, result = peer.headers("/health")
    assert result[":status"] == "200"
    peer.until(lambda: health in peer.end)


async def verify_gateway_write_race():
    """DATA arriving during drain must be forwarded without a third wakeup."""
    gateway = runpy.run_path(str(GATEWAY))
    stream = gateway["Stream"]("GET", "/echo/test", [], True, None)
    stream.buffer.extend(b"first")
    stream.credit = 5
    credit = []

    class Upstream:
        def __init__(self):
            self.writes = []
            self.eof = False

        def write(self, data):
            self.writes.append(bytes(data))

        async def drain(self):
            await asyncio.sleep(0)
            if len(self.writes) == 1:
                stream.buffer.extend(b"second")
                stream.credit += 6
            else:
                stream.ended = True
            stream.wake.set()

        def write_eof(self):
            self.eof = True

    upstream = Upstream()
    connection = SimpleNamespace(
        h2=SimpleNamespace(increment_flow_control_window=lambda count, stream_id: credit.append(count)),
        flush=lambda: None,
    )
    await asyncio.wait_for(gateway["Connection"].to_upstream(connection, 1, stream, upstream), 2)
    assert upstream.writes == [b"first", b"second"] and upstream.eof
    assert credit == [5]


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python verify_http2.py SERVER [SERVER ARGUMENTS ...]")
    asyncio.run(verify_gateway_write_race())
    with TemporaryDirectory(prefix="bhash-h2-") as tmp, ExitStack() as stack:
        cert, key = Path(tmp) / "cert.pem", Path(tmp) / "key.pem"
        # A config file works with both OpenSSL and the macOS system LibreSSL.
        config = Path(tmp) / "openssl.cnf"
        config.write_text(
            "[req]\ndistinguished_name=dn\nprompt=no\nx509_extensions=extensions\n"
            f"[dn]\nCN={AUTHORITY}\n[extensions]\nsubjectAltName=DNS:{AUTHORITY}\n",
            encoding="ascii",
        )
        result = subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
             "-config", str(config), "-keyout", str(key), "-out", str(cert)],
            capture_output=True,
        )
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors="replace"))
        bhash = stack.enter_context(process(sys.argv[1:], os.environ | {"BHASH_WS_TEST_ORIGIN": ORIGIN}))
        backend, token = int(bhash("PORT")), bhash("SESSION")
        gateway = stack.enter_context(process([sys.executable, "-u", str(GATEWAY), "--authority",
                                               AUTHORITY, "--backend-port", str(backend), "--port", "0",
                                               "--cert", str(cert), "--key", str(key)]))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.load_verify_locations(cafile=str(cert))
        context.set_alpn_protocols(["h2"])
        with socket.create_connection(("127.0.0.1", int(gateway("READY"))), timeout=20) as tcp:
            with context.wrap_socket(tcp, server_hostname=AUTHORITY) as wire:
                assert wire.selected_alpn_protocol() == "h2"
                verify(wire, token)
    print("RFC 8441 TLS, WebSocket admission, multiplexing, flow control and reset passed")


if __name__ == "__main__":
    main()
