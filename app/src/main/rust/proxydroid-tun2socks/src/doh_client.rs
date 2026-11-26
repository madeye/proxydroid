//! DNS-over-HTTPS client. The DoH HTTP requests are tunnelled through the same
//! upstream proxy as user traffic, so DNS works on networks where direct UDP/53
//! is blocked.
//!
//! One [`DohClient`] is built per tun2socks session, so a session always
//! resolves through the proxy it was started with.

use crate::error::Tun2SocksError;
use crate::logging;
use crate::tun2socks::{ProxyKind, UpstreamConfig};
use parking_lot::Mutex as ParkMutex;
use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, Instant};
use tracing::{info, warn};

const DOH_TIMEOUT_SECS: u64 = 5;

const IP_BASED_DOH_URLS: &[&str] = &["https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"];

/// Consecutive all-servers-failed lookups before DoH is considered unreachable.
const FAILURES_BEFORE_BACKOFF: u32 = 2;

/// How long DoH is skipped once it is considered unreachable. After that a
/// single lookup probes it again.
const BACKOFF: Duration = Duration::from_secs(60);

pub struct DohClient {
    http_client: reqwest::Client,
    consecutive_failures: AtomicU32,
    /// `Some(t)`: DoH is considered unreachable and is skipped until `t`.
    retry_at: ParkMutex<Option<Instant>>,
}

impl DohClient {
    /// Build a DoH client that tunnels through `cfg`. Fails synchronously on
    /// an invalid proxy URL so `nativeStart` can surface it to Kotlin.
    pub fn new(cfg: &UpstreamConfig) -> Result<Self, Tun2SocksError> {
        let proxy_url = build_proxy_url(cfg);
        info!(
            "DoH client: {} proxy {}:{}",
            cfg.kind_label(),
            cfg.host,
            cfg.port
        );

        let proxy = reqwest::Proxy::all(&proxy_url).map_err(Tun2SocksError::HttpClient)?;

        let http_client = reqwest::Client::builder()
            .proxy(proxy)
            .timeout(Duration::from_secs(DOH_TIMEOUT_SECS))
            .danger_accept_invalid_certs(true)
            .build()
            .map_err(Tun2SocksError::HttpClient)?;

        Ok(DohClient {
            http_client,
            consecutive_failures: AtomicU32::new(0),
            retry_at: ParkMutex::new(None),
        })
    }

    /// Resolve a raw DNS query. Returns `None` when no DoH server could be
    /// reached through the proxy; the caller is expected to fall back.
    ///
    /// All servers are queried concurrently and the first success wins, so a
    /// lookup takes at most one timeout. Once DoH keeps failing it is skipped
    /// entirely for [`BACKOFF`], which keeps the fallback path fast instead of
    /// making every lookup wait for a timeout first.
    pub async fn resolve(&self, query: &[u8]) -> Option<Vec<u8>> {
        {
            let mut retry_at = self.retry_at.lock();
            if let Some(t) = *retry_at {
                let now = Instant::now();
                if now < t {
                    return None;
                }
                // Backoff elapsed: this lookup probes DoH again. Push the
                // deadline out so concurrent lookups keep using the fallback
                // until the probe has an answer.
                *retry_at = Some(now + BACKOFF);
            }
        }

        let attempts = IP_BASED_DOH_URLS
            .iter()
            .map(|url| Box::pin(self.query_one(url, query)));

        match futures::future::select_ok(attempts).await {
            Ok((response, _still_running)) => {
                self.consecutive_failures.store(0, Ordering::Relaxed);
                *self.retry_at.lock() = None;
                Some(response)
            }
            Err(()) => {
                let failures = self.consecutive_failures.fetch_add(1, Ordering::Relaxed) + 1;
                if failures >= FAILURES_BEFORE_BACKOFF {
                    let mut retry_at = self.retry_at.lock();
                    if retry_at.is_none() {
                        logging::bridge_log(
                            "DoH: unreachable through the upstream proxy; \
                             letting the proxy resolve hostnames instead",
                        );
                    }
                    *retry_at = Some(Instant::now() + BACKOFF);
                }
                None
            }
        }
    }

    async fn query_one(&self, url: &str, query: &[u8]) -> Result<Vec<u8>, ()> {
        let resp = self
            .http_client
            .post(url)
            .header("Content-Type", "application/dns-message")
            .header("Accept", "application/dns-message")
            .body(query.to_vec())
            .send()
            .await
            .map_err(|e| warn!("DoH request failed to {}: {}", url, e))?;

        if !resp.status().is_success() {
            warn!("DoH HTTP {} from {}", resp.status(), url);
            return Err(());
        }

        resp.bytes()
            .await
            .map(|bytes| bytes.to_vec())
            .map_err(|e| warn!("DoH response body error from {}: {}", url, e))
    }
}

fn build_proxy_url(cfg: &UpstreamConfig) -> String {
    let scheme = match cfg.kind {
        ProxyKind::Socks5 => "socks5h", // resolve hostnames at the proxy
        ProxyKind::Socks4 => "socks4a",
        ProxyKind::Http => "http",
        ProxyKind::Https => "https",
    };
    let auth = match (cfg.user.as_deref(), cfg.password.as_deref()) {
        (Some(u), Some(p)) if !u.is_empty() => format!(
            "{}:{}@",
            utf8_percent_encode(u, NON_ALPHANUMERIC),
            utf8_percent_encode(p, NON_ALPHANUMERIC),
        ),
        (Some(u), None) if !u.is_empty() => {
            format!("{}@", utf8_percent_encode(u, NON_ALPHANUMERIC))
        }
        _ => String::new(),
    };
    format!("{}://{}{}:{}", scheme, auth, cfg.host, cfg.port)
}
