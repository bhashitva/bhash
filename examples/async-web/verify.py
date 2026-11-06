#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise an already compiled async HTTP example through real sockets."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from http.client import HTTPConnection, RemoteDisconnected
import os
from pathlib import Path
import queue
import socket
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread


def verify_server(command, database, path):
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          encoding="utf-8", errors="replace",
                          env=os.environ | {"BHASH_ASYNC_WEB_TEST_DB": str(path)}) as server:
        events = queue.Queue()
        transcript = []

        def collect():
            for line in server.stdout:
                transcript.append(line.rstrip())
                events.put(line.rstrip())
            events.put(None)

        def wait_for(marker):
            while True:
                line = events.get(timeout=20)
                if line is None:
                    raise AssertionError(f"Server stopped before {marker}")
                if line.startswith(marker):
                    return line

        Thread(target=collect, daemon=True).start()
        try:
            port = int(wait_for("PORT ").split()[-1])

            def request(path, method="GET", body=None):
                connection = HTTPConnection("127.0.0.1", port, timeout=15)
                try:
                    connection.request(method, path, body=body)
                    response = connection.getresponse()
                    return response.status, response.read()
                finally:
                    connection.close()

            with ThreadPoolExecutor() as executor:
                slow = executor.submit(request, "/slow")
                wait_for("WAITING")
                assert request("/fast") == (200, "तुरंत".encode())
                assert not slow.done()
                assert request("/release", "POST")[0] == 200
                assert slow.result(timeout=15) == (200, "धीमा".encode())

                database.execute("BEGIN EXCLUSIVE")
                waiting = executor.submit(request, "/database")
                try:
                    wait_for("DB_WAITING")
                    assert request("/fast") == (200, "तुरंत".encode())
                    assert not waiting.done()
                finally:
                    database.commit()
                assert waiting.result(timeout=15) == (200, "डेटा".encode())

            # The incomplete headers and body must not monopolize the dispatcher.
            with socket.create_connection(("127.0.0.1", port), timeout=15) as connection:
                body = "परीक्षण".encode()
                connection.sendall(b"POST /echo HTTP/1.1\r\nHost: localhost\r\n")
                assert request("/fast")[0] == 200
                connection.sendall(f"Content-Length: {len(body)}\r\n\r\n".encode() + body[:2])
                assert request("/fast")[0] == 200
                connection.sendall(body[2:])
                assert connection.makefile("rb").read().endswith(body)

            assert request("/fast", "HEAD") == (200, b"")
            assert request("/echo")[0] == 405
            assert request("/missing")[0] == 404
            assert request("/timeout")[0] == 504
            with socket.create_connection(("127.0.0.1", port), timeout=15) as connection:
                connection.sendall(b"GET /cancel HTTP/1.1\r\nHost: localhost\r\n\r\n")
                wait_for("CANCELLABLE")
            wait_for("CLEANED")
            assert request("/fast")[0] == 200

            with socket.create_connection(("127.0.0.1", port), timeout=15) as connection:
                connection.sendall(b"GET /cancel HTTP/1.1\r\nHost: localhost\r\n\r\n")
                wait_for("CANCELLABLE")
                try:
                    request("/stop", "POST")
                except RemoteDisconnected:
                    pass
                wait_for("CLEANED")
            assert server.wait(timeout=15) == 0
        except BaseException:
            print("\n".join(transcript), file=sys.stderr)
            raise
        finally:
            if server.poll() is None:
                server.kill()
    print("Async HTTP concurrency, framing, timeout, disconnect and shutdown passed")


def verify(command):
    target = Path("target").resolve()
    target.mkdir(exist_ok=True)
    with TemporaryDirectory(prefix="async-web-", dir=target) as directory:
        path = Path(directory) / "test.sqlite"
        with closing(sqlite3.connect(path)) as database:
            database.execute("CREATE TABLE notes(title TEXT NOT NULL)")
            database.execute("INSERT INTO notes VALUES (?)", ("डेटा",))
            database.commit()
            verify_server(command, database, path)


if __name__ == "__main__":
    verify(sys.argv[1:])
