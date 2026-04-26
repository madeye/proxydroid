use log::info;

static INIT: std::sync::Once = std::sync::Once::new();

/// Initialize android_logger. Safe to call multiple times.
pub fn init_android_logger() {
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("proxydroid-tun2socks"),
        );
    });
}

pub fn bridge_log(msg: &str) {
    info!("{}", msg);
}
