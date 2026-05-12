//! VPN socket protection via JNI callback to Android's VpnService.protect(fd).
//!
//! Before an outbound socket calls connect(), we must call VpnService.protect(fd)
//! to route it through the underlying network instead of the TUN interface.
//! Otherwise: proxy -> TUN -> proxy -> infinite loop.

use jni::objects::GlobalRef;
use jni::JavaVM;
use parking_lot::Mutex;
use std::sync::OnceLock;

static JVM: OnceLock<JavaVM> = OnceLock::new();
static VPN_SERVICE: Mutex<Option<GlobalRef>> = Mutex::new(None);

/// Store the JVM and VpnService references. Called once from JNI when VPN starts.
pub fn set_vpn_service(env: &jni::JNIEnv, service: &jni::objects::JObject) {
    if let Ok(jvm) = env.get_java_vm() {
        // First-write wins; subsequent attempts are no-ops because a process
        // only ever has one JVM.
        let _ = JVM.set(jvm);
    }
    match env.new_global_ref(service) {
        Ok(global) => *VPN_SERVICE.lock() = Some(global),
        Err(e) => {
            crate::logging::bridge_log(&format!(
                "protect: failed to create global VpnService ref: {}",
                e
            ));
        }
    }
    crate::logging::bridge_log("protect: VpnService reference stored");
}

/// Clear references when VPN stops.
pub fn clear_vpn_service() {
    *VPN_SERVICE.lock() = None;
    crate::logging::bridge_log("protect: VpnService reference cleared");
}

/// Call `VpnService.protect(fd)` via JNI. Returns true on success.
///
/// Currently unused by the SOCKS/HTTP outbound path because the configured
/// upstream proxy is reached through Android's normal network selection — the
/// VPN service excludes our package's traffic via `addDisallowedApplication`.
/// Kept here so the relay can opt in per-socket if that policy changes.
#[allow(dead_code)]
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

    // attach_current_thread is safe to call repeatedly; it returns an env for
    // the already-attached thread when present.
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
