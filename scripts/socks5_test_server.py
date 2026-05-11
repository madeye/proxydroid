#!/usr/bin/env python3
"""
Minimal SOCKS5 proxy server used by the Android instrumentation test
HostSocks5ProxyIntegrationTest. Stdlib only.

Defaults to listening on 0.0.0.0:1080. From inside the Android emulator the
host loopback is reachable as 10.0.2.2, so the device sees this as
10.0.2.2:1080.

Supports:
  - SOCKS5 with NO_AUTH (method 0x00) by default
  - Optional RFC 1929 user/password auth (method 0x02) via --auth user:pass
  - CMD = CONNECT (0x01) only
  - ATYP = IPv4 (0x01), DOMAINNAME (0x03), IPv6 (0x04)

Usage:
    python3 scripts/socks5_test_server.py
    python3 scripts/socks5_test_server.py --host 0.0.0.0 --port 1080
    python3 scripts/socks5_test_server.py --port 1081 --auth alice:s3cret
"""

from __future__ import annotations

import argparse
import logging
import select
import socket
import struct
import threading

VER = 0x05
NO_AUTH = 0x00
USER_PASS_AUTH = 0x02
NO_ACCEPTABLE = 0xFF
CMD_CONNECT = 0x01
ATYP_IPV4 = 0x01
ATYP_DOMAIN = 0x03
ATYP_IPV6 = 0x04

REP_OK = 0x00
REP_GENERAL_FAILURE = 0x01
REP_NETWORK_UNREACHABLE = 0x03
REP_HOST_UNREACHABLE = 0x04
REP_CONN_REFUSED = 0x05
REP_CMD_NOT_SUPPORTED = 0x07
REP_ATYP_NOT_SUPPORTED = 0x08

log = logging.getLogger("socks5")


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"peer closed after {len(buf)}/{n} bytes")
        buf.extend(chunk)
    return bytes(buf)


def send_reply(sock: socket.socket, rep: int) -> None:
    # VER, REP, RSV, ATYP=IPv4, BND.ADDR=0.0.0.0, BND.PORT=0
    sock.sendall(struct.pack("!BBBB4sH", VER, rep, 0x00, ATYP_IPV4, b"\x00\x00\x00\x00", 0))


def negotiate_auth(client: socket.socket, expect_auth: tuple[str, str] | None) -> None:
    ver, nmethods = struct.unpack("!BB", recv_exact(client, 2))
    if ver != VER:
        raise ConnectionError(f"bad SOCKS version: {ver}")
    methods = recv_exact(client, nmethods)

    if expect_auth is None:
        if NO_AUTH not in methods:
            client.sendall(struct.pack("!BB", VER, NO_ACCEPTABLE))
            raise ConnectionError("client did not offer NO_AUTH")
        client.sendall(struct.pack("!BB", VER, NO_AUTH))
        return

    if USER_PASS_AUTH not in methods:
        client.sendall(struct.pack("!BB", VER, NO_ACCEPTABLE))
        raise ConnectionError("client did not offer USER/PASS auth")
    client.sendall(struct.pack("!BB", VER, USER_PASS_AUTH))

    # RFC 1929: VER=1, ULEN, UNAME, PLEN, PASSWD
    (sub_ver, ulen) = struct.unpack("!BB", recv_exact(client, 2))
    if sub_ver != 0x01:
        raise ConnectionError(f"bad sub-negotiation version: {sub_ver}")
    uname = recv_exact(client, ulen).decode("utf-8", errors="replace")
    (plen,) = struct.unpack("!B", recv_exact(client, 1))
    passwd = recv_exact(client, plen).decode("utf-8", errors="replace")

    expected_user, expected_pass = expect_auth
    if uname == expected_user and passwd == expected_pass:
        client.sendall(struct.pack("!BB", 0x01, 0x00))
    else:
        # Any non-zero status indicates failure.
        client.sendall(struct.pack("!BB", 0x01, 0x01))
        raise ConnectionError(f"auth rejected for user={uname!r}")


def read_request(client: socket.socket) -> tuple[str, int]:
    ver, cmd, _rsv, atyp = struct.unpack("!BBBB", recv_exact(client, 4))
    if ver != VER:
        raise ConnectionError(f"bad SOCKS version in request: {ver}")
    if cmd != CMD_CONNECT:
        send_reply(client, REP_CMD_NOT_SUPPORTED)
        raise ConnectionError(f"unsupported CMD: {cmd}")

    if atyp == ATYP_IPV4:
        addr = socket.inet_ntop(socket.AF_INET, recv_exact(client, 4))
    elif atyp == ATYP_IPV6:
        addr = socket.inet_ntop(socket.AF_INET6, recv_exact(client, 16))
    elif atyp == ATYP_DOMAIN:
        (length,) = struct.unpack("!B", recv_exact(client, 1))
        addr = recv_exact(client, length).decode("ascii")
    else:
        send_reply(client, REP_ATYP_NOT_SUPPORTED)
        raise ConnectionError(f"unsupported ATYP: {atyp}")

    (port,) = struct.unpack("!H", recv_exact(client, 2))
    return addr, port


def open_remote(host: str, port: int) -> socket.socket:
    last_err: Exception | None = None
    for family, socktype, proto, _canon, sa in socket.getaddrinfo(
        host, port, type=socket.SOCK_STREAM
    ):
        s = socket.socket(family, socktype, proto)
        try:
            s.settimeout(10)
            s.connect(sa)
            s.settimeout(None)
            return s
        except OSError as e:
            last_err = e
            s.close()
    raise last_err or ConnectionError(f"cannot connect to {host}:{port}")


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


def handle(
    client: socket.socket,
    addr: tuple[str, int],
    expect_auth: tuple[str, str] | None,
) -> None:
    log.info("client connected: %s:%s", *addr)
    remote: socket.socket | None = None
    try:
        client.settimeout(15)
        negotiate_auth(client, expect_auth)
        host, port = read_request(client)
        log.info("CONNECT %s:%s", host, port)
        try:
            remote = open_remote(host, port)
        except OSError as e:
            log.warning("upstream connect failed for %s:%s: %s", host, port, e)
            send_reply(client, REP_HOST_UNREACHABLE)
            return
        send_reply(client, REP_OK)
        client.settimeout(None)
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


def serve(host: str, port: int, expect_auth: tuple[str, str] | None) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((host, port))
        srv.listen(64)
        log.info(
            "SOCKS5 proxy listening on %s:%d (auth=%s, CONNECT only)",
            host,
            port,
            "user/pass" if expect_auth else "none",
        )
        while True:
            client, addr = srv.accept()
            t = threading.Thread(
                target=handle, args=(client, addr, expect_auth), daemon=True
            )
            t.start()


def main() -> None:
    p = argparse.ArgumentParser(description="Minimal SOCKS5 proxy for emulator integration tests")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=1080)
    p.add_argument(
        "--auth",
        help="Require RFC 1929 user/password auth, value 'user:password'",
    )
    p.add_argument("--quiet", action="store_true")
    args = p.parse_args()

    expect_auth: tuple[str, str] | None = None
    if args.auth is not None:
        if ":" not in args.auth:
            p.error("--auth must be in the form user:password")
        u, _, pw = args.auth.partition(":")
        expect_auth = (u, pw)

    logging.basicConfig(
        level=logging.WARNING if args.quiet else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    try:
        serve(args.host, args.port, expect_auth)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
