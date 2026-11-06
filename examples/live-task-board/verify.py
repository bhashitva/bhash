#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the task board behind TLS and exercise separate Chromium sessions on Linux."""
import asyncio
from contextlib import asynccontextmanager, suppress
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import sys
from tempfile import TemporaryDirectory

from playwright.async_api import async_playwright, expect

HERE = Path(__file__).resolve().parent


def certificates(directory):
    def run(*command):
        subprocess.run(command, cwd=directory, check=True, capture_output=True)

    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
        "-subj", "/CN=Bhash board integration CA", "-keyout", "ca.key", "-out", "ca.pem",
        "-addext", "basicConstraints=critical,CA:TRUE",
        "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    run("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
        "-subj", "/CN=127.0.0.1", "-keyout", "server.key", "-out", "server.csr")
    (directory / "server.ext").write_text(
        "subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\nbasicConstraints=CA:FALSE\n"
    )
    run("openssl", "x509", "-req", "-in", "server.csr", "-CA", "ca.pem",
        "-CAkey", "ca.key", "-CAcreateserial", "-out", "server.pem", "-days", "2",
        "-extfile", "server.ext")

    home = directory / "browser-home"
    db = home / ".pki" / "nssdb"
    db.mkdir(parents=True)
    run("certutil", "-N", "-d", f"sql:{db}", "--empty-password")
    run("certutil", "-A", "-d", f"sql:{db}", "-t", "C,,",
        "-n", "Bhash board integration CA", "-i", "ca.pem")
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.set_alpn_protocols(["http/1.1"])
    tls.load_cert_chain(directory / "server.pem", directory / "server.key")
    return tls, home


async def relay(source, destination):
    while data := await source.read(16384):
        destination.write(data)
        await destination.drain()


@asynccontextmanager
async def serve(command, directory, tls, passwords, provider):
    back_port = None
    connections = set()

    async def proxy(reader, writer):
        handler = asyncio.current_task()
        connections.add(handler)
        upstream = None
        try:
            source, upstream = await asyncio.open_connection("127.0.0.1", back_port)
            pipes = [asyncio.create_task(relay(reader, upstream)),
                     asyncio.create_task(relay(source, writer))]
            try:
                await asyncio.wait(pipes, return_when=asyncio.FIRST_COMPLETED)
            finally:
                for pipe in pipes:
                    pipe.cancel()
                await asyncio.gather(*pipes, return_exceptions=True)
        except OSError:
            pass
        finally:
            writer.close()
            if upstream is not None:
                upstream.close()
            connections.discard(handler)

    # The proxy only terminates TLS. HTTP headers and WebSocket bytes pass through intact.
    listener = await asyncio.start_server(proxy, "127.0.0.1", 0, ssl=tls, start_serving=False)
    port = listener.sockets[0].getsockname()[1]
    origin = f"https://127.0.0.1:{port}"
    env = os.environ | {f"BHASH_BOARD_{user.upper()}_PASSWORD": password
                        for user, password in passwords.items()}
    process = None
    output = []
    reader_task = None
    try:
        process = await asyncio.create_subprocess_exec(
            *command, "--port=0", f"--origin={origin}", f"--db={directory / 'board.db'}",
            f"--assets={HERE}", f"--provider={provider}", env=env,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
        )
        ready = asyncio.get_running_loop().create_future()

        async def collect():
            async for line in process.stdout:
                line = line.decode("utf-8", errors="replace").strip()
                output.append(line)
                if line.startswith("READY ") and not ready.done():
                    ready.set_result(int(line.split()[1]))
            if not ready.done():
                ready.set_exception(RuntimeError("The Bhash server exited before startup"))

        reader_task = asyncio.create_task(collect())
        back_port = await asyncio.wait_for(ready, 60)
        await listener.start_serving()
        yield origin
    except BaseException:
        print("\n".join(output), file=sys.stderr)
        raise
    finally:
        listener.close()
        await listener.wait_closed()
        pending = list(connections)
        for connection in pending:
            connection.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
        if process is not None:
            if process.returncode is None:
                with suppress(ProcessLookupError):
                    process.terminate()
                try:
                    await asyncio.wait_for(process.wait(), 10)
                except TimeoutError:
                    process.kill()
                    await process.wait()
            if reader_task is not None:
                await reader_task


class Session:
    def __init__(self, page, origin, board):
        self.page = page
        self.origin = origin
        self.board = board
        self.events = []
        self.websockets = []
        page.on("websocket", self.observe)

    def observe(self, socket):
        if socket.url == f"wss://{self.origin.removeprefix('https://')}/live/{self.board}":
            self.websockets.append(socket)
            socket.on("framereceived", lambda payload: self.events.append(json.loads(payload)))

    async def login(self, username, password):
        response = await self.page.goto(self.origin)
        assert response.ok
        await self.page.locator("[name=username]").fill(username)
        await self.page.locator("[name=password]").fill(password)
        await self.page.locator("#login button").click()
        await self.live()

    async def live(self):
        await expect(self.page.locator("#connection")).to_have_attribute("data-state", "live")
        assert self.websockets
        assert any(event.get("type") == "ready" for event in self.events)
        cookie, = [cookie for cookie in await self.page.context.cookies()
                   if cookie["name"] == "__Host-bhash-session"]
        assert cookie["secure"] and cookie["httpOnly"] and cookie["sameSite"] == "Lax"
        assert await self.page.evaluate("window.isSecureContext")
        return cookie["value"]

    async def add(self, title):
        await self.page.locator("[name=title]").fill(title)
        await self.page.locator("#add-task button").click()

    async def request(self, path, method="GET", body=None, csrf=None):
        # Use fetch inside Chromium so this request uses the same TLS trust and cookies as WSS.
        return await self.page.evaluate("""async ({path, method, body, csrf}) => {
            const headers = {};
            if (body !== null) headers['Content-Type'] = 'application/json';
            if (csrf !== null) headers['X-CSRF-Token'] = csrf;
            const response = await fetch(path, {method, headers,
                body: body === null ? undefined : JSON.stringify(body)});
            return {status: response.status, body: response.ok && response.status !== 204
                ? await response.json() : null};
        }""", {"path": path, "method": method, "body": body, "csrf": csrf})

    async def snapshot(self):
        response = await self.request(f"/api/boards/{self.board}")
        assert response["status"] == 200
        return response["body"]

    def task(self, title):
        return self.page.get_by_role("checkbox", name=title, exact=True)

    def revisions(self):
        return [event["revision"] for event in self.events if event.get("type") == "changed"]


async def exercise(browser, origin, passwords):
    expect.set_options(timeout=15000)
    contexts = [await browser.new_context() for _ in range(3)]
    alice, bob, charlie = [
        Session(await context.new_page(), origin, board)
        for context, board in zip(contexts, ["team", "team", "studio"])
    ]
    await alice.login("alice", passwords["alice"])
    await bob.login("bob", passwords["bob"])
    await charlie.login("charlie", passwords["charlie"])
    assert len({await session.live() for session in (alice, bob, charlie)}) == 3

    baseline = (await alice.snapshot())["revision"]
    other_baseline = (await charlie.snapshot())["revision"]
    denied = await charlie.request("/api/boards/team")
    assert denied["status"] == 403
    async with asyncio.timeout(10):
        code = await charlie.page.evaluate("""() => new Promise(resolve => {
            const url = new URL('/live/team', location.href);
            url.protocol = 'wss:';
            const socket = new WebSocket(url, 'bhash.board.v1');
            socket.onclose = event => resolve(event.code);
        })""")
    assert code == 1008

    title = "पुस्तक <script> पढ़ें — " + secrets.token_hex(4)
    await alice.add(title)
    await expect(alice.task(title)).to_be_visible()
    await expect(bob.task(title)).to_be_visible()
    await expect(charlie.task(title)).to_have_count(0)
    # Both sockets, including the writer's, must actually receive a revision event.
    async with asyncio.timeout(10):
        while not all(baseline + 1 in client.revisions() for client in (alice, bob)):
            await asyncio.sleep(.02)
    assert not charlie.revisions()
    assert (await charlie.snapshot())["revision"] == other_baseline

    csrf = (await alice.request("/session"))["body"]["csrf"]
    rejected = await alice.request("/api/boards/team/tasks", "POST", {"title": "CSRF"})
    assert rejected["status"] == 403
    rollback = await alice.request("/api/boards/team/tasks/2147483647", "PATCH", {"done": True}, csrf)
    assert rollback["status"] == 404
    assert (await alice.snapshot())["revision"] == baseline + 1

    # An edit in the other direction proves that Alice's live view also updates.
    await bob.task(title).check()
    await expect(alice.task(title)).to_be_checked()
    async with asyncio.timeout(10):
        while not all(baseline + 2 in client.revisions() for client in (alice, bob)):
            await asyncio.sleep(.02)
    assert alice.revisions() == [baseline + 1, baseline + 2], alice.events
    assert bob.revisions() == [baseline + 1, baseline + 2], bob.events
    assert not charlie.revisions()

    await bob.page.close()
    missed = "फिर जुड़ने पर दिखेगा — " + secrets.token_hex(4)
    await alice.add(missed)
    await expect(alice.task(missed)).to_be_visible()
    bob = Session(await contexts[1].new_page(), origin, "team")
    await bob.page.goto(origin)
    await bob.live()
    await expect(bob.task(missed)).to_be_visible()
    await expect(bob.task(title)).to_be_checked()

    # Logout on a separate page must revoke the old session's still-open socket.
    socket = bob.websockets[-1]
    csrf = (await bob.request("/session"))["body"]["csrf"]
    async with socket.expect_event("close", timeout=10000):
        response = await bob.request("/logout", "POST", csrf=csrf)
        assert response["status"] == 204
    await expect(bob.page.locator("#login-panel")).to_be_visible()
    for context in contexts:
        await context.close()


async def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: python verify.py SERVER [SERVER ARGUMENTS ...]")
    provider = os.getenv("BHASH_BOARD_TEST_PROVIDER", "sqlite")
    passwords = {name: secrets.token_urlsafe(24) for name in ("alice", "bob", "charlie")}
    with TemporaryDirectory(prefix="bhash-board-") as temp:
        directory = Path(temp)
        tls, home = certificates(directory)
        async with serve(sys.argv[1:], directory, tls, passwords, provider) as origin:
            async with async_playwright() as playwright:
                browser = await playwright.chromium.launch(
                    channel="chromium", env=os.environ | {"HOME": str(home)}
                )
                try:
                    await exercise(browser, origin, passwords)
                finally:
                    await browser.close()
    print(f"TLS task board verified with independent Chromium sessions ({provider})")


if __name__ == "__main__":
    asyncio.run(main())
