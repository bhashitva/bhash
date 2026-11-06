#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Terminate TLS/HTTP/2 and translate RFC 8441 streams to Bhash HTTP/1.1."""
import argparse
import asyncio
import base64
from contextlib import suppress
from dataclasses import dataclass, field
import hashlib
import re
import secrets
import ssl

from hpack import NeverIndexedHeaderTuple
from h2.config import H2Configuration
from h2.connection import H2Connection
from h2.errors import ErrorCodes
from h2.events import (
    ConnectionTerminated, DataReceived, RemoteSettingsChanged, RequestReceived,
    StreamEnded, StreamReset, WindowUpdated,
)
from h2.exceptions import H2Error, StreamClosedError
from h2.settings import SettingCodes, Settings

CHUNK = 8192
HEAD_LIMIT = 16384
BODY_LIMIT = 1024 * 1024
STREAM_LIMIT = 16
CONNECTION_LIMIT = 16
TIMEOUT = 10
HOP = {"connection", "keep-alive", "proxy-connection", "proxy-authenticate",
       "proxy-authorization", "transfer-encoding", "upgrade", "te", "trailer"}
WS_GENERATED = {"sec-websocket-key", "sec-websocket-accept", "sec-websocket-extensions"}
METHODS = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}
TOKEN = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+")
AUTHORITY = re.compile(r"[A-Za-z0-9.-]+(?::[0-9]{1,5})?")
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class Rejected(Exception):
    def __init__(self, status):
        self.status = status


def values(headers, name):
    return [value for key, value in headers if key == name]


def tokens(headers, name):
    return [part.strip() for value in values(headers, name) for part in value.split(",")]


def safe_field(name, value):
    return TOKEN.fullmatch(name) and all(32 <= ord(c) <= 126 or c == "\t" for c in value)


def content_length(headers):
    lengths = values(headers, "content-length")
    if not lengths:
        return None
    if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
        raise ValueError("invalid content length")
    return int(lengths[0])


@dataclass
class Stream:
    method: str
    path: str
    headers: list
    websocket: bool
    expected: int | None
    buffer: bytearray = field(default_factory=bytearray)
    credit: int = 0
    ended: bool = False
    response: bool = False
    wake: asyncio.Event = field(default_factory=asyncio.Event)
    task: asyncio.Task | None = None


def request(headers, authority):
    if sum(len(name) + len(value) + 4 for name, value in headers) > HEAD_LIMIT:
        raise Rejected(431)
    pseudo = {name: value for name, value in headers if name.startswith(":")}
    method, path = pseudo.get(":method"), pseudo.get(":path", "")
    headers = [(name, value) for name, value in headers if not name.startswith(":")]
    if not all(safe_field(name, value) for name, value in headers):
        raise Rejected(400)
    if values(headers, "expect"):
        raise Rejected(417)
    host = values(headers, "host")
    if (pseudo.get(":scheme") != "https" or pseudo.get(":authority") != authority
            or (host and host != [authority])):
        raise Rejected(421)
    if (not path.startswith("/") or path.startswith("//") or "#" in path
            or not all(33 <= ord(c) <= 126 for c in path) or len(path) > 4096):
        raise Rejected(400)
    websocket = method == "CONNECT" and pseudo.get(":protocol") == "websocket"
    if not websocket and (method not in METHODS or ":protocol" in pseudo):
        raise Rejected(405)
    try:
        expected = content_length(headers)
    except ValueError:
        raise Rejected(400) from None
    if websocket:
        if expected is not None:
            raise Rejected(400)
        version = values(headers, "sec-websocket-version")
        if len(version) != 1:
            raise Rejected(400)
        if version != ["13"]:
            raise Rejected(426)
        if len(values(headers, "origin")) != 1:
            raise Rejected(403)
        method = "GET"
    elif expected is not None and expected > BODY_LIMIT:
        raise Rejected(413)
    cookies = values(headers, "cookie")
    headers = [(name, value) for name, value in headers
               if name not in HOP | {"host", "content-length", "cookie", "expect"}
               and name not in WS_GENERATED
               and (websocket or not name.startswith("sec-websocket-"))]
    if cookies:
        headers.append(("cookie", "; ".join(cookies)))
    return Stream(method, path, headers, websocket, expected)


async def upstream_headers(reader):
    raw = await reader.readuntil(b"\r\n\r\n")
    if len(raw) > HEAD_LIMIT:
        raise ValueError("upstream headers too large")
    status, *lines = raw[:-4].decode("ascii").split("\r\n")
    match = re.fullmatch(r"HTTP/1\.[01] ([1-5][0-9]{2})(?: [ -~]*)?", status)
    if match is None:
        raise ValueError("invalid upstream status")
    headers = []
    for line in lines:
        name, separator, value = line.partition(":")
        if not separator or not safe_field(name, value):
            raise ValueError("invalid upstream header")
        headers.append((name.lower(), value.strip()))
    return int(match[1]), headers


class Connection:
    def __init__(self, reader, writer, authority, port):
        self.reader, self.writer = reader, writer
        self.authority, self.port = authority, port
        self.h2 = H2Connection(config=H2Configuration(client_side=False, header_encoding="ascii"))
        self.h2.local_settings = Settings(client=False, initial_values={
            SettingCodes.ENABLE_CONNECT_PROTOCOL: 1,
            SettingCodes.MAX_CONCURRENT_STREAMS: STREAM_LIMIT,
            SettingCodes.MAX_HEADER_LIST_SIZE: HEAD_LIMIT,
            SettingCodes.HEADER_TABLE_SIZE: 4096,
        })
        self.h2.decoder.max_header_list_size = HEAD_LIMIT
        self.streams = {}
        self.window = asyncio.Event()

    def flush(self):
        self.writer.write(self.h2.data_to_send())

    def reset(self, stream_id, code):
        with suppress(StreamClosedError):
            self.h2.reset_stream(stream_id, error_code=code)
            self.flush()

    def status(self, stream_id, status):
        headers = [(":status", str(status))]
        if status == 426:
            headers.append(("sec-websocket-version", "13"))
        self.h2.send_headers(stream_id, headers, end_stream=True)
        self.flush()

    def start(self, event):
        stream_id = event.stream_id
        try:
            if len(self.streams) >= STREAM_LIMIT:
                raise Rejected(503)
            stream = request(event.headers, self.authority)
        except Rejected as error:
            with suppress(StreamClosedError):
                self.status(stream_id, error.status)
                if not event.stream_ended:
                    self.reset(stream_id, ErrorCodes.NO_ERROR)
            return
        self.streams[stream_id] = stream
        stream.task = asyncio.create_task(self.exchange(stream_id, stream))

    def data(self, event):
        stream_id, credit = event.stream_id, event.flow_controlled_length
        # Restore connection credit immediately so a stalled stream cannot block siblings.
        # Restore stream credit only after its own input has been consumed.
        if credit:
            self.h2.increment_flow_control_window(credit)
        stream = self.streams.get(stream_id)
        if stream is None or not credit:
            return
        if not stream.websocket and len(stream.buffer) + len(event.data) > BODY_LIMIT:
            with suppress(StreamClosedError):
                self.status(stream_id, 413)
                self.reset(stream_id, ErrorCodes.NO_ERROR)
            self.streams.pop(stream_id, None)
            stream.task.cancel()
            return
        stream.buffer.extend(event.data)
        if stream.websocket:
            stream.credit += credit
        elif not event.stream_ended:
            with suppress(StreamClosedError):
                self.h2.increment_flow_control_window(credit, stream_id=stream_id)
        stream.wake.set()

    async def run(self):
        self.h2.initiate_connection()
        self.flush()
        try:
            while True:
                # An idle peer must not hold a connection slot indefinitely.
                async with asyncio.timeout(None if self.streams else 60):
                    data = await self.reader.read(16384)
                if not data:
                    break
                for event in self.h2.receive_data(data):
                    if isinstance(event, RequestReceived):
                        self.start(event)
                    elif isinstance(event, DataReceived):
                        self.data(event)
                    elif isinstance(event, StreamEnded):
                        stream = self.streams.get(event.stream_id)
                        if stream is not None:
                            stream.ended = True
                            stream.wake.set()
                    elif isinstance(event, StreamReset):
                        stream = self.streams.pop(event.stream_id, None)
                        if stream is not None:
                            stream.task.cancel()
                    elif isinstance(event, (WindowUpdated, RemoteSettingsChanged)):
                        self.window.set()
                    elif isinstance(event, ConnectionTerminated):
                        return
                self.flush()
                await asyncio.wait_for(self.writer.drain(), TIMEOUT)
        except (OSError, H2Error, UnicodeError, TimeoutError):
            # hyper-h2 queues GOAWAY when it detects a connection-level protocol error.
            with suppress(OSError):
                self.flush()
        finally:
            tasks = [stream.task for stream in self.streams.values()]
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    async def send(self, stream_id, data):
        while data:
            available = min(self.h2.local_flow_control_window(stream_id),
                            self.h2.max_outbound_frame_size, len(data))
            if available <= 0:
                self.window.clear()
                await asyncio.wait_for(self.window.wait(), TIMEOUT)
                continue
            self.h2.send_data(stream_id, data[:available])
            data = data[available:]
            self.flush()
            await asyncio.wait_for(self.writer.drain(), TIMEOUT)

    async def to_upstream(self, stream_id, stream, upstream):
        while True:
            if stream.credit:
                chunk, credit = stream.buffer, stream.credit
                stream.buffer, stream.credit = bytearray(), 0
                upstream.write(chunk)
                await asyncio.wait_for(upstream.drain(), TIMEOUT)
                if not stream.ended:
                    self.h2.increment_flow_control_window(credit, stream_id=stream_id)
                    self.flush()
            if stream.ended:
                upstream.write_eof()
                return
            stream.wake.clear()
            await stream.wake.wait()

    async def tunnel(self, stream_id, stream, reader, writer):
        # Upstream EOF finishes the H2 stream. Downstream END_STREAM only half-closes
        # the upstream TCP connection. A reset cancels the parent and both pumps.
        async def read():
            while chunk := await reader.read(CHUNK):
                await self.send(stream_id, chunk)
            self.h2.end_stream(stream_id)
            self.flush()

        incoming = asyncio.create_task(self.to_upstream(stream_id, stream, writer))
        outgoing = asyncio.create_task(read())
        try:
            done, _ = await asyncio.wait((incoming, outgoing), return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
            if outgoing not in done:
                await asyncio.wait_for(outgoing, TIMEOUT)
        finally:
            incoming.cancel()
            outgoing.cancel()
            await asyncio.gather(incoming, outgoing, return_exceptions=True)

    async def open_upstream(self, stream):
        key = base64.b64encode(secrets.token_bytes(16)).decode("ascii") if stream.websocket else None
        headers = [("host", self.authority), *stream.headers]
        if key is not None:
            headers += [("connection", "Upgrade"), ("upgrade", "websocket"),
                        ("sec-websocket-key", key)]
        else:
            headers += [("connection", "close"), ("content-length", str(len(stream.buffer)))]
        request_bytes = (f"{stream.method} {stream.path} HTTP/1.1\r\n" +
                         "".join(f"{name}: {value}\r\n" for name, value in headers) + "\r\n")
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port, limit=HEAD_LIMIT)
        try:
            writer.write(request_bytes.encode("ascii"))
            if not stream.websocket:
                writer.write(stream.buffer)
            await writer.drain()
            status, response = await upstream_headers(reader)
            if status == 101:
                accept = (base64.b64encode(hashlib.sha1((key + GUID).encode("ascii")).digest())
                          .decode("ascii")) if key is not None else None
                protocol = values(response, "sec-websocket-protocol")
                if (key is None or values(response, "sec-websocket-accept") != [accept]
                        or [x.lower() for x in tokens(response, "upgrade")] != ["websocket"]
                        or "upgrade" not in [x.lower() for x in tokens(response, "connection")]
                        or len(protocol) > 1
                        or (protocol and protocol[0] not in tokens(headers, "sec-websocket-protocol"))
                        or values(response, "sec-websocket-extensions")):
                    raise ValueError("invalid upstream WebSocket upgrade")
            elif status < 200 or (stream.websocket and 200 <= status < 300):
                raise ValueError("unexpected upstream status")
            return reader, writer, status, response
        except BaseException:
            writer.close()
            raise

    async def exchange(self, stream_id, stream):
        writer = None
        try:
            if not stream.websocket:
                async with asyncio.timeout(TIMEOUT):
                    while not stream.ended:
                        stream.wake.clear()
                        await stream.wake.wait()
                if stream.expected is not None and stream.expected != len(stream.buffer):
                    raise Rejected(400)
            async with asyncio.timeout(TIMEOUT):
                reader, writer, status, headers = await self.open_upstream(stream)
            drop = HOP | WS_GENERATED | set(x.lower() for x in tokens(headers, "connection"))
            if status == 101:
                drop.add("content-length")
            elif values(headers, "transfer-encoding"):
                raise ValueError("unexpected upstream transfer encoding")
            length = 0 if status == 101 else content_length(headers)
            if length is not None and length > BODY_LIMIT:
                raise ValueError("upstream body too large")
            body = status != 101 and stream.method != "HEAD" and status not in (204, 304) and length != 0
            response = [(":status", "200" if status == 101 else str(status))]
            response.extend(NeverIndexedHeaderTuple(name, value) if name == "set-cookie" else (name, value)
                            for name, value in headers if name not in drop)
            self.h2.send_headers(stream_id, response, end_stream=not (status == 101 or body))
            stream.response = True
            self.flush()
            if status == 101:
                await self.tunnel(stream_id, stream, reader, writer)
            elif body:
                async with asyncio.timeout(TIMEOUT):
                    total = 0
                    while chunk := await reader.read(CHUNK):
                        total += len(chunk)
                        if total > BODY_LIMIT or (length is not None and total > length):
                            raise ValueError("upstream body too large")
                        await self.send(stream_id, chunk)
                    if length is not None and total != length:
                        raise ValueError("incomplete upstream response")
                self.h2.end_stream(stream_id)
                self.flush()
            if stream.websocket and status != 101 and not stream.ended:
                self.reset(stream_id, ErrorCodes.NO_ERROR)
        except StreamClosedError:
            pass
        except (Rejected, OSError, TimeoutError, ValueError, H2Error, asyncio.IncompleteReadError,
                asyncio.LimitOverrunError) as error:
            with suppress(H2Error, OSError):
                if stream.response:
                    self.reset(stream_id, ErrorCodes.CANCEL)
                else:
                    self.status(stream_id, error.status if isinstance(error, Rejected) else 502)
                    if not stream.ended:
                        self.reset(stream_id, ErrorCodes.NO_ERROR)
        finally:
            self.streams.pop(stream_id, None)
            if writer is not None:
                writer.close()


async def listen(authority, port, bind, public_port, tls):
    active = set()

    async def handle(reader, writer):
        task = asyncio.current_task()
        if writer.get_extra_info("ssl_object").selected_alpn_protocol() != "h2" or len(active) >= CONNECTION_LIMIT:
            writer.close()
            return
        active.add(task)
        try:
            await Connection(reader, writer, authority, port).run()
        finally:
            active.discard(task)
            writer.close()

    async with await asyncio.start_server(handle, bind, public_port, ssl=tls, ssl_handshake_timeout=TIMEOUT) as server:
        print(f"READY {server.sockets[0].getsockname()[1]}", flush=True)
        await server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--authority", required=True, help="Exact public host[:port], matching Bhash origin")
    parser.add_argument("--backend-port", required=True, type=int, help="Bhash port on 127.0.0.1")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", default=8443, type=int)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    if not AUTHORITY.fullmatch(args.authority) or not 1 <= args.backend_port <= 65535:
        parser.error("invalid authority or backend port")
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.set_alpn_protocols(["h2"])
    tls.load_cert_chain(args.cert, args.key)
    with suppress(KeyboardInterrupt):
        asyncio.run(listen(args.authority, args.backend_port, args.bind, args.port, tls))


if __name__ == "__main__":
    main()
