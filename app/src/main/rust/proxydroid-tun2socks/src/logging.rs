use log::info;
use std::sync::OnceLock;

static INIT: std::sync::Once = std::sync::Once::new();
static TRAFFIC_LOG: OnceLock<bool> = OnceLock::new();

/// Initialize android_logger. Safe to call multiple times.
pub fn init_android_logger() {
    INIT.call_once(|| {
        // Info by default: Debug also lets dependencies (rustls, hyper) log
        // the addresses they talk to. Raised only with traffic logging on.
        let level = if traffic_log_enabled() {
            log::LevelFilter::Debug
        } else {
            log::LevelFilter::Info
        };
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(level)
                .with_tag("proxydroid-tun2socks"),
        );
    });
}

/// Log a line about the service itself: lifecycle, configuration, errors.
/// Must not name what the user's apps connect to; use [`traffic_log`] for that.
pub fn bridge_log(msg: &str) {
    info!("{}", msg);
}

/// Whether per-connection and per-lookup lines are logged. Off by default.
///
/// Those lines name every hostname the device resolves and every destination
/// it connects to. logcat is readable by anything with log access (adb, bug
/// reports), so writing them unconditionally would leave a browsing record
/// behind. To debug routing, turn them on explicitly and restart the app:
///
/// ```text
/// adb shell setprop debug.proxydroid.traffic_log 1
/// adb shell am force-stop org.proxydroid
/// ```
///
/// The switch is read once per process.
pub fn traffic_log_enabled() -> bool {
    *TRAFFIC_LOG.get_or_init(read_traffic_log_switch)
}

/// Log a line that names a destination or a queried hostname. The message is
/// only built when traffic logging is enabled.
pub fn traffic_log(msg: impl FnOnce() -> String) {
    if traffic_log_enabled() {
        info!("{}", msg());
    }
}

#[cfg(target_os = "android")]
fn read_traffic_log_switch() -> bool {
    // PROP_VALUE_MAX, including the terminating NUL.
    let mut value = [0u8; 92];
    // SAFETY: the name is NUL-terminated and `value` is PROP_VALUE_MAX bytes,
    // the size __system_property_get requires of its output buffer.
    let len = unsafe {
        libc::__system_property_get(
            c"debug.proxydroid.traffic_log".as_ptr(),
            value.as_mut_ptr().cast(),
        )
    };
    len > 0 && matches!(value[0], b'1' | b't' | b'y')
}

#[cfg(not(target_os = "android"))]
fn read_traffic_log_switch() -> bool {
    std::env::var_os("PROXYDROID_TRAFFIC_LOG").is_some()
}
