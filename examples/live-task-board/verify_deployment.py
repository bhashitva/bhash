#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the same Linux executable behind each of three real HTTPS proxies."""
import argparse
import asyncio
from contextlib import asynccontextmanager
import getpass
import grp
import os
from pathlib import Path
import secrets
import socket
import ssl
from tempfile import TemporaryDirectory
from urllib.error import URLError
from urllib.request import urlopen

from playwright.async_api import async_playwright, expect

from verify import HERE, Session, certificates, exercise


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def proxy_command(name, directory, public, backend):
    upstream = f"127.0.0.1:{backend}"
    config = directory / f"{name}.conf"
    if name == "caddy":
        config.write_text(f"""{{
    admin off
    auto_https disable_redirects
}}
https://127.0.0.1:{public} {{
    bind 127.0.0.1
    tls "{directory}/server.pem" "{directory}/server.key"
    reverse_proxy {upstream}
}}
""")
        return ["caddy", "run", "--adapter", "caddyfile", "--config", str(config)]
    if name == "nginx":
        config.write_text(f"""worker_processes 1;
pid "{directory}/nginx.pid";
error_log stderr;
events {{}}
http {{
    access_log off;
    client_body_temp_path "{directory}/client-body";
    proxy_temp_path "{directory}/proxy-temp";
    fastcgi_temp_path "{directory}/fastcgi-temp";
    uwsgi_temp_path "{directory}/uwsgi-temp";
    scgi_temp_path "{directory}/scgi-temp";
    map $http_upgrade $connection_upgrade {{
        default upgrade;
        '' close;
    }}
    server {{
        listen 127.0.0.1:{public} ssl;
        ssl_certificate "{directory}/server.pem";
        ssl_certificate_key "{directory}/server.key";
        location / {{
            proxy_pass http://{upstream};
            proxy_http_version 1.1;
            proxy_set_header Host $http_host;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection $connection_upgrade;
        }}
    }}
}}
""")
        return ["nginx", "-p", str(directory), "-c", str(config), "-g", "daemon off;"]
    config.write_text(f"""ServerRoot "{directory}"
DefaultRuntimeDir "{directory}"
PidFile "{directory}/apache.pid"
Listen 127.0.0.1:{public}
ServerName 127.0.0.1
User {getpass.getuser()}
Group {grp.getgrgid(os.getgid()).gr_name}
ErrorLog /dev/stderr
LoadModule mpm_event_module /usr/lib/apache2/modules/mod_mpm_event.so
LoadModule authz_core_module /usr/lib/apache2/modules/mod_authz_core.so
LoadModule ssl_module /usr/lib/apache2/modules/mod_ssl.so
LoadModule proxy_module /usr/lib/apache2/modules/mod_proxy.so
LoadModule proxy_http_module /usr/lib/apache2/modules/mod_proxy_http.so
<VirtualHost 127.0.0.1:{public}>
    SSLEngine on
    SSLCertificateFile "{directory}/server.pem"
    SSLCertificateKeyFile "{directory}/server.key"
    ProxyRequests Off
    ProxyPreserveHost On
    ProxyPass / http://{upstream}/ upgrade=websocket retry=0
    ProxyPassReverse / http://{upstream}/
</VirtualHost>
""")
    return ["apache2", "-f", str(config), "-DFOREGROUND"]


@asynccontextmanager
async def running(command, log, env=None):
    with log.open("wb") as output:
        process = await asyncio.create_subprocess_exec(
            *command, env=env, stdout=output, stderr=asyncio.subprocess.STDOUT
        )
        try:
            yield process
        except BaseException:
            print(log.read_text(errors="replace"), flush=True)
            raise
        finally:
            if process.returncode is None:
                process.terminate()
                try:
                    await asyncio.wait_for(process.wait(), 15)
                except TimeoutError:
                    process.kill()
                    await process.wait()


async def ready(origin, tls, *processes):
    def probe():
        with urlopen(origin + "/healthz", context=tls, timeout=2) as response:
            return response.status == 200 and response.read() == b"ok"

    async with asyncio.timeout(60):
        while True:
            for process in processes:
                if process.returncode is not None:
                    raise RuntimeError(f"Process exited during startup: {process.returncode}")
            try:
                if await asyncio.to_thread(probe):
                    return
            except URLError:
                pass
            await asyncio.sleep(.2)


async def verify(name, executable, directory, playwright):
    _, browser_home = certificates(directory)
    tls = ssl.create_default_context(cafile=str(directory / "ca.pem"))
    backend, public = free_port(), free_port()
    while public == backend:
        public = free_port()
    origin = f"https://127.0.0.1:{public}"
    passwords = {user: secrets.token_urlsafe(24) for user in ("alice", "bob", "charlie")}
    env = os.environ | {f"BHASH_BOARD_{user.upper()}_PASSWORD": password
                        for user, password in passwords.items()}
    command = [str(executable), f"--port={backend}", f"--origin={origin}",
               f"--db={directory / 'board.db'}", f"--assets={HERE}"]

    async with running(proxy_command(name, directory, public, backend),
                       directory / "proxy.log") as proxy:
        browser = await playwright.chromium.launch(
            channel="chromium", env=os.environ | {"HOME": str(browser_home)}
        )
        try:
            async with running(command, directory / "board.log", env) as board:
                await ready(origin, tls, proxy, board)
                await exercise(browser, origin, passwords)
                session = Session(await browser.new_page(), origin, "team")
                await session.login("alice", passwords["alice"])
                title = "सर्वर फिर चलने पर भी सुरक्षित"
                await session.add(title)
                await expect(session.task(title)).to_be_visible()
                websocket = session.websockets[-1]
                async with websocket.expect_event("close", timeout=15000):
                    board.terminate()
                    assert await asyncio.wait_for(board.wait(), 15) == 0
                assert proxy.returncode is None

            async with running(command, directory / "board-restart.log", env) as board:
                await ready(origin, tls, proxy, board)
                await session.page.goto(origin)
                assert (await session.request("/session"))["body"]["user"] == ""
                await session.login("alice", passwords["alice"])
                await expect(session.task(title)).to_be_visible()
        finally:
            await browser.close()
    print(f"PASS {name}: trusted HTTPS, secure login, live WebSockets and graceful restart", flush=True)


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    args = parser.parse_args()
    executable = args.executable.resolve(strict=True)
    async with async_playwright() as playwright:
        for name in ("caddy", "nginx", "apache"):
            with TemporaryDirectory(prefix=f"bhash-{name}-") as directory:
                await verify(name, executable, Path(directory), playwright)


if __name__ == "__main__":
    asyncio.run(main())
