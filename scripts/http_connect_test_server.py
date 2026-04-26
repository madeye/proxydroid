#!/usr/bin/env python3
"""Minimal HTTP CONNECT proxy for testing ProxyDroid's HTTP / HTTPS upstream
modes. Stdlib only.

Supports:
  - CONNECT host:port HTTP/1.1  (used by HTTPS through HTTP proxy)
  - Optional Proxy-Authorization: Basic <base64> (set via --auth user:pass)

Usage:
    python3 scripts/http_connect_test_server.py --port 8080
    python3 scripts/http_connect_test_server.py --port 8080 --auth alice:s3cret
"""

from __future__ import annotations

import argparse
import base64
import logging
import select
import socket
import threading

log = logging.getLogger("http-connect")


def recv_until_double_crlf(sock: socket.socket, max_bytes: int = 16384) -> bytes:
    buf = bytearray()
    while b"\r\n\r\n" not in buf and len(buf) < max_bytes:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf.extend(chunk)
    return bytes(buf)


def relay(a: socket.socket, b: socket.socket) -> None:
    socks = [a, b]
    try:
        while True:
            ready, _, errored = select.select(socks, [], socks, 60)
            if errored or not ready:
                break
            for s in ready:
                peer = b if s is a else a
                data = s.recv(8192)
                if not data:
                    return
                peer.sendall(data)
    except OSError:
        pass


def handle(client: socket.socket, addr, expect_auth: str | None) -> None:
    log.info("client connected: %s:%s", *addr)
    remote: socket.socket | None = None
    try:
        client.settimeout(15)
        head = recv_until_double_crlf(client)
        if not head:
            return

        head_str = head.decode("iso-8859-1", errors="replace")
        request_line, _, _ = head_str.partition("\r\n")
        parts = request_line.split()
        if len(parts) < 3 or parts[0].upper() != "CONNECT":
            client.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
            log.warning("rejected non-CONNECT: %r", request_line)
            return

        target = parts[1]
        host, _, port_s = target.rpartition(":")
        if not host:
            host = target
            port_s = "443"
        try:
            port = int(port_s)
        except ValueError:
            client.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
            return

        if expect_auth is not None:
            wanted = "Basic " + base64.b64encode(expect_auth.encode()).decode()
            authd = False
            for line in head_str.split("\r\n")[1:]:
                if line.lower().startswith("proxy-authorization:"):
                    if line.split(":", 1)[1].strip() == wanted:
                        authd = True
                    break
            if not authd:
                client.sendall(
                    b"HTTP/1.1 407 Proxy Authentication Required\r\n"
                    b'Proxy-Authenticate: Basic realm="proxy"\r\n\r\n'
                )
                log.warning("rejected unauthenticated CONNECT %s:%d", host, port)
                return

        log.info("CONNECT %s:%d", host, port)
        try:
            remote = socket.create_connection((host, port), timeout=10)
        except OSError as e:
            client.sendall(f"HTTP/1.1 502 Bad Gateway\r\n\r\n{e}\r\n".encode())
            log.warning("upstream connect failed for %s:%d: %s", host, port, e)
            return

        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        client.settimeout(None)
        remote.settimeout(None)
        relay(client, remote)
    except Exception as e:
        log.warning("session error from %s:%s: %s", addr[0], addr[1], e)
    finally:
        try:
            client.close()
        except OSError:
            pass
        if remote is not None:
            try:
                remote.close()
            except OSError:
                pass


def serve(host: str, port: int, expect_auth: str | None) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((host, port))
        srv.listen(64)
        log.info(
            "HTTP CONNECT proxy listening on %s:%d (auth=%s)",
            host,
            port,
            "yes" if expect_auth else "no",
        )
        while True:
            client, addr = srv.accept()
            t = threading.Thread(target=handle, args=(client, addr, expect_auth), daemon=True)
            t.start()


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--auth", help="Require Proxy-Authorization: Basic, value 'user:password'")
    p.add_argument("--quiet", action="store_true")
    args = p.parse_args()

    logging.basicConfig(
        level=logging.WARNING if args.quiet else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        serve(args.host, args.port, args.auth)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
