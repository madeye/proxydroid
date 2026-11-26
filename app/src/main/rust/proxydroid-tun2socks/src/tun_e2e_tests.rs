//! End-to-end test of a whole tun2socks session against a real TUN device.
//!
//! Unlike the handshake tests this drives the public `start` / `stop` API the
//! JNI layer uses: kernel sockets send packets into a TUN interface, the
//! session relays them to a fake SOCKS5 upstream on loopback, and the replies
//! come back through the TUN.
//!
//! It needs root (CAP_NET_ADMIN), `/dev/net/tun` and the `ip` tool, so it is
//! `#[ignore]`d by default. Run it in a throwaway container:
//!
//! ```text
//! docker run --rm --cap-add NET_ADMIN --device /dev/net/tun -v "$PWD":/src -w /src rust:1 \
//!   sh -c 'apt-get update && apt-get install -y iproute2 && cargo test -- --ignored'
//! ```

use crate::dns_table;
use crate::tun2socks::{start, stop, ProxyKind, UpstreamConfig};
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::os::unix::io::{AsRawFd, FromRawFd, OwnedFd};
use std::process::Command;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const TUN_NAME: &str = "pdtest0";
const TUN_LOCAL: &str = "10.9.0.1";
/// Any address routed into the TUN other than our own.
const TUN_PEER: Ipv4Addr = Ipv4Addr::new(10, 9, 0, 2);
const IO_TIMEOUT: Duration = Duration::from_secs(15);

// ---------------------------------------------------------------------------
// TUN device
// ---------------------------------------------------------------------------

#[repr(C)]
struct IfReq {
    name: [u8; 16],
    flags: libc::c_short,
    _pad: [u8; 22],
}

fn open_tun() -> OwnedFd {
    const TUNSETIFF: u64 = 0x4004_54ca;
    const IFF_TUN: libc::c_short = 0x0001;
    const IFF_NO_PI: libc::c_short = 0x1000;

    // SAFETY: plain open(2) on a NUL-terminated path.
    let fd = unsafe { libc::open(c"/dev/net/tun".as_ptr(), libc::O_RDWR | libc::O_CLOEXEC) };
    assert!(
        fd >= 0,
        "open /dev/net/tun: {}",
        std::io::Error::last_os_error()
    );
    // SAFETY: `fd` was just opened and is owned by nothing else.
    let tun = unsafe { OwnedFd::from_raw_fd(fd) };

    let mut req = IfReq {
        name: [0; 16],
        flags: IFF_TUN | IFF_NO_PI,
        _pad: [0; 22],
    };
    req.name[..TUN_NAME.len()].copy_from_slice(TUN_NAME.as_bytes());
    // SAFETY: `req` has the layout of `struct ifreq` and outlives the call.
    let rc = unsafe { libc::ioctl(fd, TUNSETIFF as _, &mut req) };
    assert!(rc >= 0, "TUNSETIFF: {}", std::io::Error::last_os_error());
    tun
}

fn ip(args: &[&str]) {
    let status = Command::new("ip").args(args).status().expect("run ip");
    assert!(status.success(), "ip {:?} failed", args);
}

/// Number of this process's descriptors that refer to the TUN device.
fn tun_fd_count() -> usize {
    std::fs::read_dir("/proc/self/fd")
        .expect("read /proc/self/fd")
        .filter_map(|entry| std::fs::read_link(entry.ok()?.path()).ok())
        .filter(|target| target.as_os_str() == "/dev/net/tun")
        .count()
}

fn wait_for_tun_fd_count(expected: usize) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while tun_fd_count() != expected {
        assert!(
            Instant::now() < deadline,
            "expected {} TUN descriptors, still have {}",
            expected,
            tun_fd_count()
        );
        thread::sleep(Duration::from_millis(20));
    }
}

// ---------------------------------------------------------------------------
// Fake SOCKS5 upstream: records each CONNECT target, then echoes
// ---------------------------------------------------------------------------

struct FakeProxy {
    port: u16,
    targets: Arc<Mutex<Vec<String>>>,
}

impl FakeProxy {
    fn spawn() -> FakeProxy {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind proxy");
        let port = listener.local_addr().unwrap().port();
        let targets = Arc::new(Mutex::new(Vec::new()));
        let recorded = targets.clone();
        thread::spawn(move || {
            for conn in listener.incoming().flatten() {
                let recorded = recorded.clone();
                thread::spawn(move || {
                    let _ = serve_socks5(conn, &recorded);
                });
            }
        });
        FakeProxy { port, targets }
    }

    fn config(&self) -> UpstreamConfig {
        UpstreamConfig {
            kind: ProxyKind::Socks5,
            host: "127.0.0.1".into(),
            port: self.port,
            user: None,
            password: None,
        }
    }

    fn saw(&self, target: &str) -> bool {
        self.targets.lock().unwrap().iter().any(|t| t == target)
    }

    /// DoH lookups show up as CONNECTs to port 443.
    fn saw_doh_attempt(&self) -> bool {
        self.targets
            .lock()
            .unwrap()
            .iter()
            .any(|t| t.ends_with(":443"))
    }
}

fn serve_socks5(mut conn: TcpStream, targets: &Mutex<Vec<String>>) -> std::io::Result<()> {
    let mut head = [0u8; 2];
    conn.read_exact(&mut head)?;
    let mut methods = vec![0u8; head[1] as usize];
    conn.read_exact(&mut methods)?;
    conn.write_all(&[0x05, 0x00])?; // no auth

    let mut req = [0u8; 4];
    conn.read_exact(&mut req)?;
    let host = match req[3] {
        0x01 => {
            let mut a = [0u8; 4];
            conn.read_exact(&mut a)?;
            Ipv4Addr::from(a).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            conn.read_exact(&mut len)?;
            let mut name = vec![0u8; len[0] as usize];
            conn.read_exact(&mut name)?;
            String::from_utf8_lossy(&name).into_owned()
        }
        other => return Err(std::io::Error::other(format!("unexpected ATYP {other}"))),
    };
    let mut port = [0u8; 2];
    conn.read_exact(&mut port)?;
    targets
        .lock()
        .unwrap()
        .push(format!("{}:{}", host, u16::from_be_bytes(port)));
    conn.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])?;

    // Echo. For a DoH attempt this bounces the TLS ClientHello back, which
    // fails the handshake at once — exactly the "DoH unreachable" case.
    let mut buf = [0u8; 4096];
    loop {
        let n = conn.read(&mut buf)?;
        if n == 0 {
            return Ok(());
        }
        conn.write_all(&buf[..n])?;
    }
}

// ---------------------------------------------------------------------------
// Client side: ordinary kernel sockets routed into the TUN
// ---------------------------------------------------------------------------

/// Open a TCP connection through the tunnel and check a payload round-trips.
fn assert_tcp_echo(dst: SocketAddr, payload: &[u8]) {
    let mut conn = TcpStream::connect_timeout(&dst, IO_TIMEOUT)
        .unwrap_or_else(|e| panic!("connect {dst} through the tunnel: {e}"));
    conn.set_read_timeout(Some(IO_TIMEOUT)).unwrap();

    // Write from a second thread: with a large payload the echo fills every
    // buffer on the way back unless it is being read concurrently.
    let mut writer = conn.try_clone().unwrap();
    let to_send = payload.to_vec();
    let sender = thread::spawn(move || writer.write_all(&to_send));

    let mut echoed = vec![0u8; payload.len()];
    conn.read_exact(&mut echoed)
        .unwrap_or_else(|e| panic!("read echo from {dst}: {e}"));
    sender.join().unwrap().expect("write payload");
    assert_eq!(echoed, payload);
}

/// Send an `A` query into the tunnel and return the first answer address.
fn resolve_a(name: &str) -> Ipv4Addr {
    let mut query = vec![0xBE, 0xEF, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
    for label in name.split('.') {
        query.push(label.len() as u8);
        query.extend_from_slice(label.as_bytes());
    }
    query.extend_from_slice(&[0, 0, 1, 0, 1]); // root, type A, class IN

    let socket = UdpSocket::bind((TUN_LOCAL, 0)).expect("bind udp");
    socket.set_read_timeout(Some(IO_TIMEOUT)).unwrap();
    socket.send_to(&query, (TUN_PEER, 53)).expect("send query");

    let mut buf = [0u8; 1500];
    let (n, from) = socket
        .recv_from(&mut buf)
        .unwrap_or_else(|e| panic!("no DNS answer for {name}: {e}"));
    assert_eq!(from, SocketAddr::from((TUN_PEER, 53)));
    assert_eq!(&buf[..2], &[0xBE, 0xEF], "answer carries the query ID");

    match dns_table::parse_dns_response_records(&buf[..n]).first() {
        Some((IpAddr::V4(ip), _, _)) => *ip,
        other => panic!("expected an A record for {name}, got {other:?}"),
    }
}

// ---------------------------------------------------------------------------
// The test
// ---------------------------------------------------------------------------

#[test]
#[ignore = "needs root, /dev/net/tun and iproute2; see the module docs"]
fn session_relays_resolves_restarts_and_stops() {
    let tun = open_tun();
    ip(&["addr", "add", &format!("{TUN_LOCAL}/24"), "dev", TUN_NAME]);
    ip(&["link", "set", "dev", TUN_NAME, "up"]);
    ip(&["route", "add", "198.18.0.0/16", "dev", TUN_NAME]);
    assert_eq!(tun_fd_count(), 1);

    // --- First session -----------------------------------------------------
    let first = FakeProxy::spawn();
    start(tun.as_raw_fd(), first.config()).expect("start first session");
    assert_eq!(tun_fd_count(), 2, "the session works on its own duplicate");

    // TCP by address: TUN -> stack -> SOCKS5 -> echo -> stack -> TUN.
    assert_tcp_echo((TUN_PEER, 8080).into(), b"hello through the tunnel");
    assert!(first.saw("10.9.0.2:8080"));

    // A payload well beyond one MTU exercises the write path under load.
    let big: Vec<u8> = (0..200_000u32).map(|i| i as u8).collect();
    assert_tcp_echo((TUN_PEER, 8082).into(), &big);

    // DNS: DoH cannot work through this upstream, so the lookup must fall
    // back to a placeholder address instead of failing.
    let placeholder = resolve_a("e2e.example.test");
    assert_eq!(placeholder.octets()[..2], [198, 18]);
    assert!(first.saw_doh_attempt(), "DoH was tried through the proxy");

    // Connecting to the placeholder reaches the proxy *by hostname*.
    assert_tcp_echo((placeholder, 80).into(), b"by name");
    assert!(first.saw("e2e.example.test:80"));

    // --- Restart on the same TUN with a different upstream -----------------
    let second = FakeProxy::spawn();
    start(tun.as_raw_fd(), second.config()).expect("start replaces the session");
    wait_for_tun_fd_count(2); // the first session released its duplicate

    assert_tcp_echo((TUN_PEER, 8081).into(), b"second session");
    assert!(second.saw("10.9.0.2:8081"));
    assert!(!first.saw("10.9.0.2:8081"), "traffic left the old upstream");

    // DNS must follow the new upstream as well.
    assert!(!second.saw_doh_attempt());
    let again = resolve_a("second.example.test");
    assert_eq!(again.octets()[..2], [198, 18]);
    assert!(second.saw_doh_attempt(), "DoH moved to the new upstream");

    // --- Stop --------------------------------------------------------------
    stop();
    wait_for_tun_fd_count(1);
    stop(); // idempotent
}
