//! VPN socket protection via JNI callback to Android's VpnService.protect(fd).
//!
//! Before an outbound socket calls connect(), we must call VpnService.protect(fd)
//! to route it through the underlying network instead of the TUN interface.
//! Otherwise: proxy → TUN → proxy → infinite loop.

use jni::objects::GlobalRef;
use jni::JavaVM;
use parking_lot::Mutex;
use std::net::SocketAddr;
use std::os::unix::io::AsRawFd;
use std::sync::OnceLock;
use tokio::net::TcpStream;

static JVM: OnceLock<JavaVM> = OnceLock::new();
static VPN_SERVICE: Mutex<Option<GlobalRef>> = Mutex::new(None);

/// Store the JVM and VpnService references. Called once from JNI when VPN starts.
pub fn set_vpn_service(env: &jni::JNIEnv, service: &jni::objects::JObject) {
    if let Ok(jvm) = env.get_java_vm() {
        JVM.set(jvm).ok();
    }
    if let Ok(global) = env.new_global_ref(service) {
        *VPN_SERVICE.lock() = Some(global);
    }
    crate::logging::bridge_log("protect: VpnService reference stored");
}

/// Clear references when VPN stops.
pub fn clear_vpn_service() {
    *VPN_SERVICE.lock() = None;
    crate::logging::bridge_log("protect: VpnService reference cleared");
}

/// Call VpnService.protect(fd) via JNI. Returns true on success.
pub fn protect_fd(fd: i32) -> bool {
    let jvm = match JVM.get() {
        Some(jvm) => jvm,
        None => return false,
    };
    let service_guard = VPN_SERVICE.lock();
    let service = match service_guard.as_ref() {
        Some(s) => s,
        None => return false,
    };

    // Attach current thread to JVM (safe to call repeatedly — returns existing env if attached)
    let mut env = match jvm.attach_current_thread() {
        Ok(env) => env,
        Err(e) => {
            crate::logging::bridge_log(&format!("protect: JNI attach failed: {}", e));
            return false;
        }
    };

    match env.call_method(service, "protect", "(I)Z", &[jni::objects::JValue::Int(fd)]) {
        Ok(val) => val.z().unwrap_or(false),
        Err(e) => {
            crate::logging::bridge_log(&format!("protect: JNI call failed: {}", e));
            false
        }
    }
}

/// Create a TCP socket, protect it via VpnService.protect(fd), then connect.
/// This replaces the standard `TcpStream::connect()` for proxy outbound connections.
#[allow(dead_code)]
pub async fn protected_connect(addr: &str) -> std::io::Result<TcpStream> {
    // Resolve address
    let sock_addr: SocketAddr = match addr.parse() {
        Ok(a) => a,
        Err(_) => {
            // May be host:port — resolve via tokio
            let addrs: Vec<SocketAddr> = tokio::net::lookup_host(addr).await?.collect();
            *addrs.first().ok_or_else(|| {
                std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, "no addresses found")
            })?
        }
    };

    // Create raw socket via socket2
    let domain = match sock_addr {
        SocketAddr::V4(_) => socket2::Domain::IPV4,
        SocketAddr::V6(_) => socket2::Domain::IPV6,
    };
    let socket = socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))
        .map_err(std::io::Error::other)?;
    socket.set_nonblocking(true)?;

    let raw_fd = socket.as_raw_fd();

    // Protect BEFORE connect — this is the critical step
    if !protect_fd(raw_fd) {
        crate::logging::bridge_log(&format!(
            "protect: WARNING failed to protect fd={} for {}",
            raw_fd, addr
        ));
        // Continue anyway — if addDisallowedApplication is set, it still works
    }

    // Convert to std TcpStream (transfers ownership of fd)
    let std_stream: std::net::TcpStream = socket.into();

    // Convert to tokio TcpStream
    let tokio_stream = TcpStream::from_std(std_stream)?;

    // Connect (non-blocking, via tokio)
    // tokio TcpStream::from_std doesn't connect — we need to use the connect approach
    // Actually, from_std on an unconnected socket won't work directly for async connect.
    // We need to use tokio's connect_std or do it manually.
    drop(tokio_stream);

    // Better approach: use socket2 to start the connect, then wrap
    let socket = socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))
        .map_err(std::io::Error::other)?;
    socket.set_nonblocking(true)?;

    let raw_fd = socket.as_raw_fd();
    protect_fd(raw_fd);

    // Start non-blocking connect
    let sock_addr2 = socket2::SockAddr::from(sock_addr);
    match socket.connect(&sock_addr2) {
        Ok(()) => {}
        Err(e) if e.raw_os_error() == Some(libc::EINPROGRESS) => {}
        Err(e) => return Err(e),
    }

    // Wrap in tokio TcpStream — it will poll for connect completion
    let std_stream: std::net::TcpStream = socket.into();
    let stream = TcpStream::from_std(std_stream)?;

    // Wait for connection to complete
    stream.writable().await?;

    // Check for connect errors
    if let Some(err) = stream.take_error()? {
        return Err(err);
    }

    Ok(stream)
}
