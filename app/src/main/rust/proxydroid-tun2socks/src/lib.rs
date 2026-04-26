//! JNI entry points for ProxyDroid's tun2socks layer.

mod dns_table;
mod doh_client;
mod logging;
mod protect;
mod tun2socks;

use jni::objects::{JClass, JObject, JString};
use jni::sys::jint;
use jni::JNIEnv;
use std::sync::OnceLock;

use crate::tun2socks::{ProxyKind, UpstreamConfig};

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

pub(crate) fn get_runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("Failed to create tokio runtime")
    })
}

fn jstring_to_opt(env: &mut JNIEnv, s: JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(&s).ok().map(|s| s.into())
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_org_proxydroid_utils_Tun2SocksHelper_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    vpn_service: JObject,
    tun_fd: jint,
    _mtu: jint,
    proxy_type: JString,
    socks_host: JString,
    socks_port: jint,
    socks_user: JString,
    socks_password: JString,
) -> jint {
    logging::init_android_logger();

    let kind_str = jstring_to_opt(&mut env, proxy_type).unwrap_or_else(|| "socks5".into());
    let host = match jstring_to_opt(&mut env, socks_host) {
        Some(s) if !s.is_empty() => s,
        _ => {
            logging::bridge_log("nativeStart: socks_host is empty");
            return -1;
        }
    };
    let user = jstring_to_opt(&mut env, socks_user).filter(|s| !s.is_empty());
    let password = jstring_to_opt(&mut env, socks_password).filter(|s| !s.is_empty());

    if !vpn_service.is_null() {
        protect::set_vpn_service(&env, &vpn_service);
    } else {
        logging::bridge_log("nativeStart: WARNING vpn_service is null; protect() unavailable");
    }

    let cfg = UpstreamConfig {
        kind: ProxyKind::parse(&kind_str),
        host,
        port: socks_port as u16,
        user,
        password,
    };

    match tun2socks::start(tun_fd, cfg) {
        Ok(()) => 0,
        Err(e) => {
            logging::bridge_log(&format!("nativeStart: error: {}", e));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_proxydroid_utils_Tun2SocksHelper_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    tun2socks::stop();
    protect::clear_vpn_service();
    logging::bridge_log("nativeStop: requested");
}
