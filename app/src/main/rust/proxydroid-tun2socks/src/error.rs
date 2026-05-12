//! Crate-level error type. Used for the JNI boundary and for top-level start/stop
//! paths. Lower-level I/O paths continue to use [`std::io::Error`].

use thiserror::Error;

#[derive(Debug, Error)]
pub enum Tun2SocksError {
    #[error("tun2socks already running")]
    AlreadyRunning,

    #[error("invalid configuration: {0}")]
    InvalidConfig(String),

    #[error("failed to build tokio runtime: {0}")]
    Runtime(#[source] std::io::Error),

    #[error("failed to build HTTP client: {0}")]
    HttpClient(#[source] reqwest::Error),

    #[error("io: {0}")]
    Io(#[from] std::io::Error),
}

#[allow(dead_code)]
pub type Result<T> = std::result::Result<T, Tun2SocksError>;
