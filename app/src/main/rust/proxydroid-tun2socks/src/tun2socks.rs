//! tun2socks using netstack-smoltcp.
//!
//! Reads raw IP packets from the Android TUN fd, runs them through smoltcp's
//! userspace TCP/IP stack, and forwards each accepted TCP connection to the
//! user-configured upstream proxy. UDP DNS is intercepted and forwarded via DoH
//! through the same upstream.
//!
//! Supported upstream proxy types: SOCKS5 (with optional user/password),
//! SOCKS4, HTTP CONNECT (with optional Basic auth), HTTPS (HTTP CONNECT over
//! TLS to the proxy).

use crate::dns_table;
use crate::doh_client::DohClient;
use crate::error::Tun2SocksError;
use crate::logging;
use base64::Engine;
use futures::{SinkExt, StreamExt};
use parking_lot::Mutex as ParkMutex;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::os::raw::c_void;
use std::os::unix::io::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::io::unix::AsyncFd;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot};
use tokio::task::JoinSet;
use tracing::{debug, info};

use netstack_smoltcp::{AnyIpPktFrame, StackBuilder};

// ---------------------------------------------------------------------------
// Upstream proxy configuration
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProxyKind {
    Socks5,
    Socks4,
    Http,
    Https,
}

impl ProxyKind {
    pub fn parse(s: &str) -> ProxyKind {
        match s.to_ascii_lowercase().as_str() {
            "socks5" => ProxyKind::Socks5,
            "socks4" => ProxyKind::Socks4,
            "http" => ProxyKind::Http,
            "https" => ProxyKind::Https,
            _ => ProxyKind::Socks5, // safe default
        }
    }
}

#[derive(Clone)]
pub struct UpstreamConfig {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
    pub user: Option<String>,
    pub password: Option<String>,
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/// Handle to the session that currently owns the tunnel.
struct Session {
    id: u64,
    shutdown: oneshot::Sender<()>,
}

/// The current session, if any. Each `start` installs a new one; state is
/// per-session rather than a process-wide "running" flag, so a session that
/// is still winding down can never clobber the one that replaced it.
static SESSION: ParkMutex<Option<Session>> = ParkMutex::new(None);
static NEXT_SESSION_ID: AtomicU64 = AtomicU64::new(1);

/// Start a session on the TUN `fd` with the given upstream.
///
/// `fd` stays owned by the caller: the session works on its own duplicate, so
/// the caller may close `fd` at any time without the session ever touching a
/// recycled descriptor number. If a session is already running it is shut
/// down and replaced.
pub fn start(fd: i32, cfg: UpstreamConfig) -> Result<(), Tun2SocksError> {
    info!(
        "tun2socks starting: fd={}, kind={:?}, upstream={}:{}",
        fd, cfg.kind, cfg.host, cfg.port
    );

    // Build everything fallible up-front so a bad configuration becomes a
    // synchronous error that nativeStart can surface to Kotlin. The DoH
    // client is per-session: it must follow the proxy of *this* start.
    let doh = Arc::new(DohClient::new(&cfg)?);
    let tun = dup_nonblocking(fd)?;
    let rt = crate::try_get_runtime()?;

    let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();
    let id = NEXT_SESSION_ID.fetch_add(1, Ordering::Relaxed);
    let previous = SESSION.lock().replace(Session {
        id,
        shutdown: shutdown_tx,
    });
    if let Some(previous) = previous {
        logging::bridge_log("tun2socks: replacing the running session");
        // Receiver dropped means that session already exited.
        let _ = previous.shutdown.send(());
    }

    rt.spawn(async move {
        if let Err(e) = run_tun2socks(tun, cfg, doh, shutdown_rx).await {
            logging::bridge_log(&format!("tun2socks error: {}", e));
        }
        {
            // Only clear the slot if it is still ours; a newer session may
            // have taken it over while we were shutting down.
            let mut session = SESSION.lock();
            if session.as_ref().is_some_and(|s| s.id == id) {
                *session = None;
            }
        }
        info!("tun2socks exited");
    });

    Ok(())
}

/// Request a graceful stop. Idempotent and safe to call from any thread.
pub fn stop() {
    if let Some(session) = SESSION.lock().take() {
        // Receiver dropped means the task already exited — nothing to do.
        let _ = session.shutdown.send(());
    }
}

/// Duplicate `fd` and put the duplicate in non-blocking mode.
fn dup_nonblocking(fd: RawFd) -> io::Result<OwnedFd> {
    // SAFETY: fcntl is safe to call on any integer; an invalid fd yields EBADF.
    let dup = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    if dup < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `dup` was just created above and nothing else owns it.
    let owned = unsafe { OwnedFd::from_raw_fd(dup) };

    // SAFETY: `dup` is a valid open descriptor owned by `owned`.
    let flags = unsafe { libc::fcntl(dup, libc::F_GETFL) };
    if flags < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: as above.
    if unsafe { libc::fcntl(dup, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(owned)
}

fn read_fd(fd: RawFd, buf: &mut [u8]) -> io::Result<usize> {
    // SAFETY: `buf` is a valid writable region of `buf.len()` bytes.
    let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut c_void, buf.len()) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

fn write_fd(fd: RawFd, buf: &[u8]) -> io::Result<usize> {
    // SAFETY: `buf` is a valid readable region of `buf.len()` bytes.
    let n = unsafe { libc::write(fd, buf.as_ptr() as *const c_void, buf.len()) };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

// ---------------------------------------------------------------------------
// Main tun2socks loop
// ---------------------------------------------------------------------------

async fn run_tun2socks(
    tun: OwnedFd,
    cfg: UpstreamConfig,
    doh: Arc<DohClient>,
    shutdown_rx: oneshot::Receiver<()>,
) -> io::Result<()> {
    logging::bridge_log("tun2socks: building netstack-smoltcp stack");

    // Registering the TUN fd with the reactor lets the reader and writer
    // sleep until the kernel reports readiness instead of polling.
    let tun = Arc::new(AsyncFd::new(tun)?);

    let (mut stack, tcp_runner, _udp_socket, tcp_listener) = StackBuilder::default()
        .enable_tcp(true)
        .enable_udp(false)
        .stack_buffer_size(1024)
        .tcp_buffer_size(512)
        .build()?;

    // Returned options are `Some` because we enabled TCP above. Treat absence
    // as a configuration bug rather than panicking across the FFI boundary.
    let tcp_runner =
        tcp_runner.ok_or_else(|| io::Error::other("netstack-smoltcp returned no TCP runner"))?;
    let mut tcp_listener = tcp_listener
        .ok_or_else(|| io::Error::other("netstack-smoltcp returned no TCP listener"))?;

    logging::bridge_log("tun2socks: starting tasks");

    let (ingress_tx, mut ingress_rx) = mpsc::channel::<AnyIpPktFrame>(256);
    let (egress_tx, mut egress_rx) = mpsc::unbounded_channel::<Vec<u8>>();

    let runner_handle = tokio::spawn(async move {
        if let Err(e) = tcp_runner.await {
            logging::bridge_log(&format!("tun2socks: TCP runner error: {}", e));
        }
    });

    let egress_tx2 = egress_tx.clone();
    let stack_handle = tokio::spawn(async move {
        loop {
            tokio::select! {
                pkt = ingress_rx.recv() => {
                    match pkt {
                        Some(frame) => {
                            if let Err(e) = stack.send(frame).await {
                                logging::bridge_log(&format!("stack send error: {}", e));
                                break;
                            }
                        }
                        None => break,
                    }
                }
                pkt = stack.next() => {
                    match pkt {
                        Some(Ok(frame)) => { let _ = egress_tx2.send(frame); }
                        Some(Err(e)) => {
                            logging::bridge_log(&format!("stack recv error: {}", e));
                            break;
                        }
                        None => break,
                    }
                }
            }
        }
    });

    let tcp_accept_handle = tokio::spawn(async move {
        // Relays live in a JoinSet owned by this task, so aborting the task on
        // shutdown also aborts every connection of this session.
        let mut relays = JoinSet::new();
        loop {
            tokio::select! {
                accepted = tcp_listener.next() => {
                    let Some((stream, local_addr, remote_addr)) = accepted else {
                        break;
                    };
                    logging::traffic_log(|| {
                        format!("tun2socks: TCP {} -> {}", local_addr, remote_addr)
                    });
                    relays.spawn(handle_tcp_stream(
                        stream,
                        local_addr,
                        remote_addr,
                        cfg.clone(),
                    ));
                }
                // Reap finished relays so the set does not grow without bound.
                Some(_) = relays.join_next(), if !relays.is_empty() => {}
            }
        }
    });

    let writer_tun = tun.clone();
    let tun_writer_handle = tokio::spawn(async move {
        while let Some(pkt) = egress_rx.recv().await {
            loop {
                let mut guard = match writer_tun.writable().await {
                    Ok(guard) => guard,
                    Err(e) => {
                        logging::bridge_log(&format!("tun2socks: TUN poll error: {}", e));
                        return;
                    }
                };
                match guard.try_io(|tun| write_fd(tun.get_ref().as_raw_fd(), &pkt)) {
                    Ok(Ok(_)) => break,
                    Ok(Err(e)) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Ok(Err(e)) => {
                        // The kernel rejected this packet; TCP will
                        // retransmit. A dead interface is noticed by the
                        // reader, which ends the session.
                        debug!("TUN write error: {}", e);
                        break;
                    }
                    // Not writable after all: readiness was cleared, wait again.
                    Err(_would_block) => continue,
                }
            }
        }
    });

    let reader_tun = tun.clone();
    let udp_reply_tx = egress_tx.clone();
    let mut tun_reader_handle = tokio::spawn(async move {
        let mut read_buf = vec![0u8; 65535];
        // DNS lookups live in a JoinSet owned by this task, so they end with
        // the session: an in-flight lookup can neither keep using this
        // session's upstream nor touch the shared DNS table after a stop.
        let mut lookups = JoinSet::new();
        loop {
            let mut guard = match reader_tun.readable().await {
                Ok(guard) => guard,
                Err(e) => {
                    logging::bridge_log(&format!("tun2socks: TUN poll error: {}", e));
                    return;
                }
            };

            // Drain every queued packet before sleeping again.
            loop {
                let n = match guard.try_io(|tun| read_fd(tun.get_ref().as_raw_fd(), &mut read_buf))
                {
                    Ok(Ok(0)) => {
                        guard.clear_ready();
                        break;
                    }
                    Ok(Ok(n)) => n,
                    Ok(Err(e)) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Ok(Err(e)) => {
                        logging::bridge_log(&format!("tun2socks: TUN read error: {}", e));
                        return;
                    }
                    // Queue empty: readiness was cleared, wait for the next packet.
                    Err(_would_block) => break,
                };
                let ip_data = &read_buf[..n];

                if let Some((src_ip, src_port, dst_ip, dst_port, payload)) =
                    parse_udp_packet(ip_data)
                {
                    if dst_port == 53 {
                        // Reap finished lookups so the set stays small.
                        while lookups.try_join_next().is_some() {}
                        lookups.spawn(handle_dns_query(
                            src_ip,
                            src_port,
                            dst_ip,
                            dst_port,
                            payload.to_vec(),
                            doh.clone(),
                            udp_reply_tx.clone(),
                        ));
                        continue;
                    }
                }

                let frame: AnyIpPktFrame = ip_data.to_vec();
                if ingress_tx.send(frame).await.is_err() {
                    return;
                }
            }
        }
    });

    // Wait for either the TUN reader to stop (interface gone) or an explicit
    // shutdown request from `stop()` / a replacing `start()`. Whichever fires
    // first, we then tear down every spawned subtask.
    let reader_finished = tokio::select! {
        _ = &mut tun_reader_handle => {
            logging::bridge_log("tun2socks: TUN reader finished");
            true
        }
        _ = shutdown_rx => {
            logging::bridge_log("tun2socks: shutdown requested");
            false
        }
    };

    runner_handle.abort();
    stack_handle.abort();
    tcp_accept_handle.abort();
    tun_writer_handle.abort();
    if !reader_finished {
        tun_reader_handle.abort();
        let _ = tun_reader_handle.await;
    }
    // Wait for the writer too: once both are gone, `tun` below is the last
    // reference and dropping it closes our duplicate of the TUN fd.
    let _ = tun_writer_handle.await;
    drop(tun);

    logging::bridge_log("tun2socks: exiting");
    Ok(())
}

// ---------------------------------------------------------------------------
// TCP relay
// ---------------------------------------------------------------------------

async fn handle_tcp_stream(
    mut tun_stream: netstack_smoltcp::TcpStream,
    src_addr: SocketAddr,
    dst_addr: SocketAddr,
    cfg: UpstreamConfig,
) {
    let target = match dns_table::dns_table_lookup(dst_addr.ip()) {
        Some(hostname) => Target::Domain(hostname, dst_addr.port()),
        None => Target::Ip(dst_addr),
    };

    logging::traffic_log(|| format!("{} connect: {} -> {:?}", cfg.kind_label(), src_addr, target));

    let upstream = match connect_upstream(&cfg, &target).await {
        Ok(s) => s,
        Err(e) => {
            if logging::traffic_log_enabled() {
                logging::bridge_log(&format!(
                    "UPSTREAM FAIL: {} -> {} err={}",
                    src_addr, dst_addr, e
                ));
            } else {
                // Still worth knowing that the upstream is refusing or
                // unreachable, just not which destination was being reached.
                logging::bridge_log(&format!(
                    "{} upstream connect failed: {}",
                    cfg.kind_label(),
                    e
                ));
            }
            return;
        }
    };

    let mut upstream = upstream;
    match tokio::io::copy_bidirectional(&mut tun_stream, &mut upstream).await {
        Ok((up, down)) => {
            logging::traffic_log(|| {
                format!("TCP relay done: {} up={} down={}", dst_addr, up, down)
            });
        }
        Err(e) => {
            logging::traffic_log(|| format!("TCP relay error: {} err={}", dst_addr, e));
        }
    }
}

impl UpstreamConfig {
    pub fn kind_label(&self) -> &'static str {
        match self.kind {
            ProxyKind::Socks5 => "SOCKS5",
            ProxyKind::Socks4 => "SOCKS4",
            ProxyKind::Http => "HTTP",
            ProxyKind::Https => "HTTPS",
        }
    }
}

#[derive(Debug)]
enum Target {
    Ip(SocketAddr),
    Domain(String, u16),
}

type AsyncIo = Box<dyn AsyncReadWrite + Send + Unpin>;

pub trait AsyncReadWrite: AsyncRead + AsyncWrite {}
impl<T: AsyncRead + AsyncWrite> AsyncReadWrite for T {}

async fn connect_upstream(cfg: &UpstreamConfig, target: &Target) -> io::Result<AsyncIo> {
    let proxy_addr = format!("{}:{}", cfg.host, cfg.port);
    match cfg.kind {
        ProxyKind::Socks5 => {
            let s = TcpStream::connect(&proxy_addr).await?;
            let s =
                socks5_handshake(s, cfg.user.as_deref(), cfg.password.as_deref(), target).await?;
            Ok(Box::new(s))
        }
        ProxyKind::Socks4 => {
            let s = TcpStream::connect(&proxy_addr).await?;
            let s = socks4_handshake(s, cfg.user.as_deref(), target).await?;
            Ok(Box::new(s))
        }
        ProxyKind::Http => {
            let s = TcpStream::connect(&proxy_addr).await?;
            let s = http_connect(s, cfg.user.as_deref(), cfg.password.as_deref(), target).await?;
            Ok(Box::new(s))
        }
        ProxyKind::Https => {
            let s = TcpStream::connect(&proxy_addr).await?;
            let tls = tls_wrap(s, &cfg.host).await?;
            let s = http_connect(tls, cfg.user.as_deref(), cfg.password.as_deref(), target).await?;
            Ok(Box::new(s))
        }
    }
}

// ---------------------------------------------------------------------------
// SOCKS5 with optional user/password auth
// ---------------------------------------------------------------------------

async fn socks5_handshake<S: AsyncRead + AsyncWrite + Unpin>(
    mut s: S,
    user: Option<&str>,
    pass: Option<&str>,
    target: &Target,
) -> io::Result<S> {
    let want_auth = user.is_some() && !user.unwrap().is_empty();
    if want_auth {
        s.write_all(&[0x05, 0x02, 0x00, 0x02]).await?;
    } else {
        s.write_all(&[0x05, 0x01, 0x00]).await?;
    }
    let mut resp = [0u8; 2];
    s.read_exact(&mut resp).await?;
    if resp[0] != 0x05 {
        return Err(io::Error::other("SOCKS5 bad version"));
    }
    match resp[1] {
        0x00 => {} // NO AUTH selected
        0x02 if want_auth => {
            // RFC 1929 user/password sub-negotiation.
            let u = user.unwrap_or("").as_bytes();
            let p = pass.unwrap_or("").as_bytes();
            if u.len() > 255 || p.len() > 255 {
                return Err(io::Error::other("SOCKS5 auth credentials too long"));
            }
            let mut req = vec![0x01, u.len() as u8];
            req.extend_from_slice(u);
            req.push(p.len() as u8);
            req.extend_from_slice(p);
            s.write_all(&req).await?;
            let mut ar = [0u8; 2];
            s.read_exact(&mut ar).await?;
            if ar[1] != 0x00 {
                return Err(io::Error::other(format!("SOCKS5 auth rejected: {}", ar[1])));
            }
        }
        m => {
            return Err(io::Error::other(format!(
                "SOCKS5 method not accepted: {}",
                m
            )))
        }
    }

    match target {
        Target::Ip(SocketAddr::V4(v4)) => {
            let ip = v4.ip().octets();
            let port = v4.port().to_be_bytes();
            s.write_all(&[
                0x05, 0x01, 0x00, 0x01, ip[0], ip[1], ip[2], ip[3], port[0], port[1],
            ])
            .await?;
        }
        Target::Ip(SocketAddr::V6(v6)) => {
            let mut req = vec![0x05, 0x01, 0x00, 0x04];
            req.extend_from_slice(&v6.ip().octets());
            req.extend_from_slice(&v6.port().to_be_bytes());
            s.write_all(&req).await?;
        }
        Target::Domain(domain, port) => {
            let db = domain.as_bytes();
            if db.len() > 255 {
                return Err(io::Error::other("SOCKS5 domain too long"));
            }
            let mut req = Vec::with_capacity(4 + 1 + db.len() + 2);
            req.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, db.len() as u8]);
            req.extend_from_slice(db);
            req.extend_from_slice(&port.to_be_bytes());
            s.write_all(&req).await?;
        }
    }

    let mut rh = [0u8; 4];
    s.read_exact(&mut rh).await?;
    if rh[0] != 0x05 || rh[1] != 0x00 {
        return Err(io::Error::other(format!(
            "SOCKS5 CONNECT failed: rep={}",
            rh[1]
        )));
    }
    match rh[3] {
        0x01 => {
            let mut b = [0u8; 6];
            s.read_exact(&mut b).await?;
        }
        0x03 => {
            let mut l = [0u8; 1];
            s.read_exact(&mut l).await?;
            let mut b = vec![0u8; l[0] as usize + 2];
            s.read_exact(&mut b).await?;
        }
        0x04 => {
            let mut b = [0u8; 18];
            s.read_exact(&mut b).await?;
        }
        a => return Err(io::Error::other(format!("SOCKS5 unknown ATYP: {}", a))),
    }
    Ok(s)
}

// ---------------------------------------------------------------------------
// SOCKS4 (and SOCKS4A for hostnames)
// ---------------------------------------------------------------------------

async fn socks4_handshake<S: AsyncRead + AsyncWrite + Unpin>(
    mut s: S,
    user: Option<&str>,
    target: &Target,
) -> io::Result<S> {
    let userid = user.unwrap_or("").as_bytes();
    match target {
        Target::Ip(SocketAddr::V4(v4)) => {
            let mut req = Vec::with_capacity(8 + userid.len() + 1);
            req.push(0x04); // VN
            req.push(0x01); // CD = CONNECT
            req.extend_from_slice(&v4.port().to_be_bytes());
            req.extend_from_slice(&v4.ip().octets());
            req.extend_from_slice(userid);
            req.push(0x00); // userid NUL terminator
            s.write_all(&req).await?;
        }
        Target::Ip(SocketAddr::V6(_)) => {
            return Err(io::Error::other("SOCKS4 does not support IPv6 targets"));
        }
        Target::Domain(domain, port) => {
            // SOCKS4A: invalid IP 0.0.0.x signals domain follows after userid.
            let mut req = Vec::with_capacity(8 + userid.len() + 1 + domain.len() + 1);
            req.push(0x04);
            req.push(0x01);
            req.extend_from_slice(&port.to_be_bytes());
            req.extend_from_slice(&[0, 0, 0, 1]); // 0.0.0.1 = SOCKS4A marker
            req.extend_from_slice(userid);
            req.push(0x00);
            req.extend_from_slice(domain.as_bytes());
            req.push(0x00);
            s.write_all(&req).await?;
        }
    }

    let mut resp = [0u8; 8];
    s.read_exact(&mut resp).await?;
    if resp[0] != 0x00 {
        return Err(io::Error::other(format!(
            "SOCKS4 bad reply VN: {}",
            resp[0]
        )));
    }
    if resp[1] != 0x5A {
        return Err(io::Error::other(format!(
            "SOCKS4 CONNECT rejected: code={}",
            resp[1]
        )));
    }
    Ok(s)
}

// ---------------------------------------------------------------------------
// HTTP CONNECT (works for both http:// and https:// proxies once the inner
// stream is wrapped with TLS)
// ---------------------------------------------------------------------------

async fn http_connect<S: AsyncRead + AsyncWrite + Unpin>(
    mut s: S,
    user: Option<&str>,
    pass: Option<&str>,
    target: &Target,
) -> io::Result<S> {
    let host_port = match target {
        Target::Ip(addr) => addr.to_string(),
        Target::Domain(d, p) => format!("{}:{}", d, p),
    };

    let mut req = format!(
        "CONNECT {hp} HTTP/1.1\r\nHost: {hp}\r\nProxy-Connection: keep-alive\r\n",
        hp = host_port,
    );
    if let Some(u) = user {
        if !u.is_empty() {
            let p = pass.unwrap_or("");
            let creds = format!("{}:{}", u, p);
            let b64 = base64::engine::general_purpose::STANDARD.encode(creds.as_bytes());
            req.push_str(&format!("Proxy-Authorization: Basic {}\r\n", b64));
        }
    }
    req.push_str("\r\n");
    s.write_all(req.as_bytes()).await?;
    s.flush().await?;

    // Read status line + headers until \r\n\r\n.
    let mut head = Vec::with_capacity(256);
    let mut buf = [0u8; 1];
    let mut consecutive = 0usize;
    while head.len() < 16 * 1024 {
        let n = s.read(&mut buf).await?;
        if n == 0 {
            return Err(io::Error::other("HTTP CONNECT: proxy closed"));
        }
        head.push(buf[0]);
        match (consecutive, buf[0]) {
            (0, b'\r') => consecutive = 1,
            (1, b'\n') => consecutive = 2,
            (2, b'\r') => consecutive = 3,
            (3, b'\n') => break,
            _ => consecutive = 0,
        }
    }

    let head_str = std::str::from_utf8(&head)
        .map_err(|_| io::Error::other("HTTP CONNECT: non-UTF8 response head"))?;
    let status_line = head_str.lines().next().unwrap_or("");
    let mut parts = status_line.split_whitespace();
    let _ver = parts.next();
    let code = parts.next().unwrap_or("");
    if code != "200" {
        return Err(io::Error::other(format!(
            "HTTP CONNECT failed: {}",
            status_line
        )));
    }
    Ok(s)
}

// ---------------------------------------------------------------------------
// TLS wrapper for HTTPS proxies
// ---------------------------------------------------------------------------

async fn tls_wrap(
    s: TcpStream,
    server_name: &str,
) -> io::Result<tokio_rustls::client::TlsStream<TcpStream>> {
    let mut cfg = rustls::ClientConfig::builder()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoVerify))
        .with_no_client_auth();
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    let connector = tokio_rustls::TlsConnector::from(Arc::new(cfg));
    let dns_name = rustls::pki_types::ServerName::try_from(server_name.to_string())
        .map_err(|_| io::Error::other("invalid TLS server name"))?;
    connector.connect(dns_name, s).await
}

#[derive(Debug)]
struct NoVerify;

impl rustls::client::danger::ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self,
        _end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }
    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &rustls::pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }
    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

// ---------------------------------------------------------------------------
// UDP / DNS helpers (unchanged from the v1 implementation)
// ---------------------------------------------------------------------------

fn parse_udp_packet(ip_data: &[u8]) -> Option<(u32, u16, u32, u16, &[u8])> {
    if ip_data.len() < 28 {
        return None;
    }
    if (ip_data[0] >> 4) != 4 {
        return None;
    }
    if ip_data[9] != 17 {
        return None;
    }
    let ihl = (ip_data[0] & 0x0F) as usize * 4;
    if ip_data.len() < ihl + 8 {
        return None;
    }
    let src_ip = u32::from_ne_bytes([ip_data[12], ip_data[13], ip_data[14], ip_data[15]]);
    let dst_ip = u32::from_ne_bytes([ip_data[16], ip_data[17], ip_data[18], ip_data[19]]);
    let src_port = u16::from_be_bytes([ip_data[ihl], ip_data[ihl + 1]]);
    let dst_port = u16::from_be_bytes([ip_data[ihl + 2], ip_data[ihl + 3]]);
    let udp_len = u16::from_be_bytes([ip_data[ihl + 4], ip_data[ihl + 5]]) as usize;
    let start = ihl + 8;
    let end = (ihl + udp_len).min(ip_data.len());
    if start > end {
        return None;
    }
    Some((src_ip, src_port, dst_ip, dst_port, &ip_data[start..end]))
}

fn build_udp_packet(
    src_ip: u32,
    src_port: u16,
    dst_ip: u32,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let total_len = 20 + udp_len;
    let mut p = vec![0u8; total_len];
    p[0] = 0x45;
    p[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    p[6] = 0x40;
    p[8] = 64;
    p[9] = 17;
    p[12..16].copy_from_slice(&src_ip.to_ne_bytes());
    p[16..20].copy_from_slice(&dst_ip.to_ne_bytes());
    let ck = ip_checksum(&p[..20]);
    p[10..12].copy_from_slice(&ck.to_be_bytes());
    p[20..22].copy_from_slice(&src_port.to_be_bytes());
    p[22..24].copy_from_slice(&dst_port.to_be_bytes());
    p[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
    p[28..].copy_from_slice(payload);
    p
}

fn ip_checksum(h: &[u8]) -> u16 {
    let mut s: u32 = 0;
    for i in (0..h.len()).step_by(2) {
        s += if i + 1 < h.len() {
            (h[i] as u32) << 8 | h[i + 1] as u32
        } else {
            (h[i] as u32) << 8
        };
    }
    while s >> 16 != 0 {
        s = (s & 0xFFFF) + (s >> 16);
    }
    !s as u16
}

async fn handle_dns_query(
    src_ip: u32,
    src_port: u16,
    dst_ip: u32,
    dst_port: u16,
    query: Vec<u8>,
    doh: Arc<DohClient>,
    reply_tx: mpsc::UnboundedSender<Vec<u8>>,
) {
    logging::traffic_log(|| {
        format!(
            "DoH: {} from {:?}:{}",
            dns_table::parse_dns_query_name(&query).unwrap_or_default(),
            Ipv4Addr::from(src_ip.to_ne_bytes()),
            src_port
        )
    });

    let response = match doh.resolve(&query).await {
        Some(response) => {
            for (ip, hostname, ttl) in dns_table::parse_dns_response_records(&response) {
                dns_table::dns_table_insert(ip, hostname, ttl);
            }
            response
        }
        // DoH is unreachable through this proxy. Answer with a placeholder
        // address instead of failing the lookup; the connection that follows
        // is sent to the proxy by hostname, so the proxy does the resolving.
        None => match dns_table::build_fake_dns_response(&query) {
            Some(response) => {
                logging::traffic_log(|| {
                    format!(
                        "DNS: {} answered locally (DoH unavailable)",
                        dns_table::parse_dns_query_name(&query).unwrap_or_default()
                    )
                });
                response
            }
            None => return,
        },
    };

    let _ = reply_tx.send(build_udp_packet(
        dst_ip, dst_port, src_ip, src_port, &response,
    ));
}

#[cfg(test)]
#[path = "auth_e2e_tests.rs"]
mod auth_e2e_tests;
