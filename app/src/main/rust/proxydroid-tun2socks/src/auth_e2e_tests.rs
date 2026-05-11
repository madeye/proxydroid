//! End-to-end tests for upstream-proxy basic authentication.
//!
//! These tests spin up real tokio TCP listeners on 127.0.0.1 that act as
//! auth-requiring upstream proxies, then drive the crate's actual handshake
//! functions (`socks5_handshake`, `http_connect`) against them. They cover
//! both protocols across three cases each: correct credentials,
//! wrong credentials, and a credential-requiring upstream with no creds.

use super::{http_connect, socks5_handshake, Target};
use std::net::SocketAddr;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Bind a loopback listener on an ephemeral port and return (addr, listener).
async fn bind_loopback() -> (SocketAddr, TcpListener) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    (addr, listener)
}

/// Read exactly `n` bytes or panic.
async fn read_exact_n(s: &mut TcpStream, n: usize) -> Vec<u8> {
    let mut buf = vec![0u8; n];
    s.read_exact(&mut buf).await.unwrap();
    buf
}

// ---------------------------------------------------------------------------
// Fake SOCKS5 upstream
// ---------------------------------------------------------------------------

/// Behaviour of the fake SOCKS5 upstream.
#[derive(Clone, Copy)]
enum Socks5Mode {
    /// Server advertises only user/password auth; accepts iff creds match.
    RequireAuth {
        user: &'static str,
        pass: &'static str,
    },
    /// Server requires auth and rejects everything (advertises 0xFF).
    RejectNoMatchingMethod,
}

/// Run a one-shot fake SOCKS5 server and return what the handshake wrote.
/// `ready_tx` fires once the listener is bound so the test can connect.
async fn run_fake_socks5(listener: TcpListener, mode: Socks5Mode) {
    let (mut sock, _) = listener.accept().await.unwrap();

    // Greeting: VER NMETHODS METHODS...
    let header = read_exact_n(&mut sock, 2).await;
    assert_eq!(header[0], 0x05, "bad SOCKS version");
    let nmethods = header[1] as usize;
    let methods = read_exact_n(&mut sock, nmethods).await;

    match mode {
        Socks5Mode::RejectNoMatchingMethod => {
            // No acceptable methods.
            sock.write_all(&[0x05, 0xFF]).await.unwrap();
            return;
        }
        Socks5Mode::RequireAuth { user, pass } => {
            if !methods.contains(&0x02) {
                sock.write_all(&[0x05, 0xFF]).await.unwrap();
                return;
            }
            sock.write_all(&[0x05, 0x02]).await.unwrap();

            // RFC 1929 sub-negotiation.
            let ver_ulen = read_exact_n(&mut sock, 2).await;
            assert_eq!(ver_ulen[0], 0x01);
            let ulen = ver_ulen[1] as usize;
            let uname = read_exact_n(&mut sock, ulen).await;
            let plen_buf = read_exact_n(&mut sock, 1).await;
            let plen = plen_buf[0] as usize;
            let pword = read_exact_n(&mut sock, plen).await;

            let creds_ok = uname == user.as_bytes() && pword == pass.as_bytes();
            if !creds_ok {
                // RFC 1929: any non-zero status indicates failure.
                sock.write_all(&[0x01, 0x01]).await.unwrap();
                return;
            }
            sock.write_all(&[0x01, 0x00]).await.unwrap();
        }
    }

    // CONNECT request: VER CMD RSV ATYP ...
    let head = read_exact_n(&mut sock, 4).await;
    assert_eq!(head[0], 0x05);
    assert_eq!(head[1], 0x01); // CONNECT
    match head[3] {
        0x01 => {
            let _ = read_exact_n(&mut sock, 6).await; // IPv4 + port
        }
        0x03 => {
            let l = read_exact_n(&mut sock, 1).await[0] as usize;
            let _ = read_exact_n(&mut sock, l + 2).await;
        }
        0x04 => {
            let _ = read_exact_n(&mut sock, 18).await;
        }
        _ => panic!("bad ATYP"),
    }

    // Success reply with a bogus BND.ADDR/BND.PORT.
    sock.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
        .await
        .unwrap();
}

// ---------------------------------------------------------------------------
// Fake HTTP CONNECT upstream
// ---------------------------------------------------------------------------

#[derive(Clone, Copy)]
struct HttpAuth {
    user: &'static str,
    pass: &'static str,
}

/// Reads the request head until \r\n\r\n and returns it as a String.
async fn read_http_head(sock: &mut TcpStream) -> String {
    let mut buf = Vec::with_capacity(512);
    let mut byte = [0u8; 1];
    while buf.len() < 16 * 1024 {
        sock.read_exact(&mut byte).await.unwrap();
        buf.push(byte[0]);
        if buf.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    String::from_utf8(buf).unwrap()
}

/// Run a one-shot fake HTTP CONNECT server requiring the given Basic creds.
async fn run_fake_http_connect(listener: TcpListener, required: HttpAuth) {
    use base64::Engine;
    let (mut sock, _) = listener.accept().await.unwrap();
    let head = read_http_head(&mut sock).await;

    assert!(
        head.starts_with("CONNECT "),
        "expected CONNECT, got: {head:?}"
    );

    let expected = format!(
        "Basic {}",
        base64::engine::general_purpose::STANDARD
            .encode(format!("{}:{}", required.user, required.pass).as_bytes())
    );
    let presented = head
        .lines()
        .find_map(|l| l.strip_prefix("Proxy-Authorization: "))
        .map(|s| s.trim().to_string());

    let response = match presented {
        Some(v) if v == expected => {
            "HTTP/1.1 200 Connection Established\r\nProxy-Agent: test\r\n\r\n"
        }
        _ => "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\n\r\n",
    };
    sock.write_all(response.as_bytes()).await.unwrap();
}

// ---------------------------------------------------------------------------
// Test target
// ---------------------------------------------------------------------------

fn target_v4() -> Target {
    Target::Ip(SocketAddr::from(([93, 184, 216, 34], 443)))
}

// ---------------------------------------------------------------------------
// SOCKS5 tests
// ---------------------------------------------------------------------------

#[tokio::test]
async fn socks5_basic_auth_succeeds_with_correct_credentials() {
    let (addr, listener) = bind_loopback().await;
    let server = tokio::spawn(run_fake_socks5(
        listener,
        Socks5Mode::RequireAuth {
            user: "alice",
            pass: "s3cret",
        },
    ));

    let client = TcpStream::connect(addr).await.unwrap();
    let target = target_v4();
    socks5_handshake(client, Some("alice"), Some("s3cret"), &target)
        .await
        .expect("handshake should succeed");

    server.await.unwrap();
}

#[tokio::test]
async fn socks5_basic_auth_fails_with_wrong_password() {
    let (addr, listener) = bind_loopback().await;
    let server = tokio::spawn(run_fake_socks5(
        listener,
        Socks5Mode::RequireAuth {
            user: "alice",
            pass: "s3cret",
        },
    ));

    let client = TcpStream::connect(addr).await.unwrap();
    let target = target_v4();
    let err = socks5_handshake(client, Some("alice"), Some("wrong"), &target)
        .await
        .expect_err("handshake should fail");
    assert!(
        err.to_string().contains("SOCKS5 auth rejected"),
        "unexpected error: {err}"
    );

    server.await.unwrap();
}

#[tokio::test]
async fn socks5_no_credentials_against_auth_required_upstream_fails() {
    let (addr, listener) = bind_loopback().await;
    let server = tokio::spawn(run_fake_socks5(
        listener,
        Socks5Mode::RejectNoMatchingMethod,
    ));

    let client = TcpStream::connect(addr).await.unwrap();
    let target = target_v4();
    let err = socks5_handshake(client, None, None, &target)
        .await
        .expect_err("handshake should fail without creds");
    assert!(
        err.to_string().contains("method not accepted"),
        "unexpected error: {err}"
    );

    server.await.unwrap();
}

// ---------------------------------------------------------------------------
// HTTP CONNECT tests
// ---------------------------------------------------------------------------

#[tokio::test]
async fn http_connect_basic_auth_succeeds_with_correct_credentials() {
    let (addr, listener) = bind_loopback().await;
    let server = tokio::spawn(run_fake_http_connect(
        listener,
        HttpAuth {
            user: "alice",
            pass: "s3cret",
        },
    ));

    let client = TcpStream::connect(addr).await.unwrap();
    let target = target_v4();
    http_connect(client, Some("alice"), Some("s3cret"), &target)
        .await
        .expect("CONNECT should return 200");

    server.await.unwrap();
}

#[tokio::test]
async fn http_connect_basic_auth_fails_with_wrong_credentials() {
    let (addr, listener) = bind_loopback().await;
    let server = tokio::spawn(run_fake_http_connect(
        listener,
        HttpAuth {
            user: "alice",
            pass: "s3cret",
        },
    ));

    let client = TcpStream::connect(addr).await.unwrap();
    let target = target_v4();
    let err = http_connect(client, Some("alice"), Some("wrong"), &target)
        .await
        .expect_err("CONNECT should fail");
    assert!(err.to_string().contains("407"), "unexpected error: {err}");

    server.await.unwrap();
}

#[tokio::test]
async fn http_connect_no_credentials_against_auth_required_upstream_fails() {
    let (addr, listener) = bind_loopback().await;
    let server = tokio::spawn(run_fake_http_connect(
        listener,
        HttpAuth {
            user: "alice",
            pass: "s3cret",
        },
    ));

    let client = TcpStream::connect(addr).await.unwrap();
    let target = target_v4();
    let err = http_connect(client, None, None, &target)
        .await
        .expect_err("CONNECT should fail without creds");
    assert!(err.to_string().contains("407"), "unexpected error: {err}");

    server.await.unwrap();
}
