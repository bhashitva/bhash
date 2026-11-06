#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise RFC 6455 over real HTTP/1.1 sockets using an independent wire peer."""
from contextlib import contextmanager
from http.client import HTTPConnection, IncompleteRead, RemoteDisconnected
from queue import Queue
import socket
import struct
import subprocess
import sys
from threading import Thread


def frame(opcode, payload=b"", final=True, mask=True):
    prefix = bytes([opcode | (0x80 if final else 0)])
    flag = 0x80 if mask else 0
    length = len(payload)
    if length < 126:
        header = prefix + bytes([flag | length])
    elif length < 65536:
        header = prefix + bytes([flag | 126]) + struct.pack("!H", length)
    else:
        header = prefix + bytes([flag | 127]) + struct.pack("!Q", length)
    if not mask:
        return header + payload
    key = b"\x89\x02\x77\x04"
    return header + key + bytes(value ^ key[index % 4] for index, value in enumerate(payload))


def receive(wire):
    first, second = wire.read(2)
    assert first & 0x70 == 0 and second & 0x80 == 0
    length = second & 0x7f
    if length in (126, 127):
        length = int.from_bytes(wire.read(2 if length == 126 else 8), "big")
    payload = wire.read(length)
    assert len(payload) == length
    return first & 15, bool(first & 0x80), payload


def message(wire):
    kind, final, payload = receive(wire)
    while not final:
        opcode, final, fragment = receive(wire)
        assert opcode == 0
        payload += fragment
    return kind, payload


def code(wire):
    opcode, final, payload = receive(wire)
    assert opcode == 8 and final
    return struct.unpack("!H", payload[:2])[0]


def verify(command):
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          encoding="utf-8", errors="replace") as process:
        events, transcript = Queue(), []

        def collect():
            for line in process.stdout:
                if not line.startswith("SESSION "):
                    transcript.append(line.rstrip())
                events.put(line.rstrip())
            events.put(None)

        def wait_for(prefix):
            while True:
                line = events.get(timeout=30)
                assert line is not None, transcript
                if line.startswith(prefix):
                    return line.split(maxsplit=1)[1]

        Thread(target=collect, daemon=True).start()
        try:
            port = int(wait_for("PORT "))
            session = wait_for("SESSION ")
            origin = f"http://127.0.0.1:{port}"

            @contextmanager
            def connect(status=101, early=b"", **overrides):
                headers = {
                    "Host": f"127.0.0.1:{port}", "Upgrade": "WebSocket",
                    "Connection": "keep-alive, Upgrade", "Origin": origin,
                    "Cookie": "bhash-local-session=" + session,
                    "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==",
                    "Sec-WebSocket-Version": "13", "Sec-WebSocket-Protocol": "other, bhash.echo",
                } | overrides
                request = "GET /echo/test HTTP/1.1\r\n" + "".join(
                    f"{key}: {value}\r\n" for key, value in headers.items() if value is not None
                ) + "\r\n"
                with socket.create_connection(("127.0.0.1", port), timeout=20) as peer:
                    peer.sendall(request.encode("ascii") + early)
                    with peer.makefile("rb") as wire:
                        assert int(wire.readline().split()[1]) == status
                        response = {}
                        while (line := wire.readline()) != b"\r\n":
                            name, value = line.decode("ascii").split(":", 1)
                            response[name.lower()] = value.strip()
                        if status == 101:
                            assert response["sec-websocket-accept"] == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
                            assert response["sec-websocket-protocol"] == "bhash.echo"
                        if status == 426:
                            assert response["sec-websocket-version"] == "13"
                            assert response["upgrade"] == "websocket"
                        yield peer, wire

            def http(path, method="GET"):
                with_socket = HTTPConnection("127.0.0.1", port, timeout=15)
                try:
                    with_socket.request(method, path)
                    response = with_socket.getresponse()
                    response.read()
                    return response.status
                finally:
                    with_socket.close()

            for status, headers in (
                (401, {"Cookie": None}), (403, {"Origin": None}),
                (403, {"Origin": "https://elsewhere.example"}),
                (426, {"Sec-WebSocket-Version": "12"}),
                (400, {"Sec-WebSocket-Key": "not-a-key"}),
                (400, {"Sec-WebSocket-Protocol": "other"}),
            ):
                with connect(status=status, **headers):
                    pass

            text = "नमस्ते, भारत 😀".encode()
            with connect(early=frame(1, text[:2], final=False)) as (peer, wire):
                assert http("/health") == 200
                peer.sendall(frame(9, b"ping") + frame(0, text[2:]))
                assert receive(wire) == (10, True, b"ping")
                assert message(wire) == (1, text)
                payload = bytes(range(256)) * 257
                peer.sendall(frame(2, payload))
                assert message(wire) == (2, payload)
                peer.sendall(frame(8, struct.pack("!H", 1000) + text))
                assert receive(wire) == (8, True, struct.pack("!H", 1000) + text)
                assert wire.read() == b""

            for packet, expected in (
                (frame(1, b"x", mask=False), 1002),
                (frame(1, b"\xff"), 1007),
                (frame(0, b"x"), 1002),
                (frame(9, b"x", final=False), 1002),
                (frame(8, struct.pack("!H", 1006)), 1002),
                (b"\x82\xff" + struct.pack("!Q", 262145) + b"mask", 1009),
            ):
                with connect() as (peer, wire):
                    peer.sendall(packet)
                    assert code(wire) == expected
            with connect() as (_, wire):
                assert http("/revoke", "POST") == 200
                assert code(wire) == 1008
            with connect(status=401):
                pass
            try:
                http("/stop", "POST")
            # Stopping can close this request after its headers and before its body.
            except (RemoteDisconnected, IncompleteRead):
                pass
            assert process.wait(timeout=20) == 0
        except BaseException:
            print("\n".join(transcript), file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.kill()
    print("WebSocket handshakes, framing, close, cancellation and HTTP coexistence passed")


if __name__ == "__main__":
    verify(sys.argv[1:])
