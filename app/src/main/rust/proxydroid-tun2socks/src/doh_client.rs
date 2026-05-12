//! DNS-over-HTTPS client. The DoH HTTP requests are tunnelled through the same
//! upstream proxy as user traffic, so DNS works on networks where direct UDP/53
//! is blocked.

use crate::error::Tun2SocksError;
use crate::logging;
use crate::tun2socks::{ProxyKind, UpstreamConfig};
use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
use std::sync::OnceLock;
use tracing::{info, warn};

const DOH_TIMEOUT_SECS: u64 = 5;

const IP_BASED_DOH_URLS: &[&str] = &["https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"];

struct DohClient {
    http_client: reqwest::Client,
    doh_urls: Vec<String>,
}

static DOH_CLIENT: OnceLock<DohClient> = OnceLock::new();

/// Initialise the process-wide DoH client. Subsequent calls are no-ops — the
/// first proxy configuration wins, matching the lifetime of the tokio runtime.
pub fn init_doh_client(cfg: &UpstreamConfig) -> Result<(), Tun2SocksError> {
    if DOH_CLIENT.get().is_some() {
        return Ok(());
    }
    let proxy_url = build_proxy_url(cfg);
    info!("DoH client: proxy={}", proxy_url);

    let proxy = reqwest::Proxy::all(&proxy_url).map_err(Tun2SocksError::HttpClient)?;

    let http_client = reqwest::Client::builder()
        .proxy(proxy)
        .timeout(std::time::Duration::from_secs(DOH_TIMEOUT_SECS))
        .danger_accept_invalid_certs(true)
        .build()
        .map_err(Tun2SocksError::HttpClient)?;

    // Ignore the Err result: a race where another thread initialised first is
    // fine because the configuration is identical for the runtime's lifetime.
    let _ = DOH_CLIENT.set(DohClient {
        http_client,
        doh_urls: IP_BASED_DOH_URLS.iter().map(|s| s.to_string()).collect(),
    });
    Ok(())
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

pub async fn resolve_via_doh(query: &[u8]) -> Option<Vec<u8>> {
    let client = DOH_CLIENT.get()?;

    for url in &client.doh_urls {
        match client
            .http_client
            .post(url)
            .header("Content-Type", "application/dns-message")
            .header("Accept", "application/dns-message")
            .body(query.to_vec())
            .send()
            .await
        {
            Ok(resp) => {
                if resp.status().is_success() {
                    match resp.bytes().await {
                        Ok(bytes) => return Some(bytes.to_vec()),
                        Err(e) => {
                            warn!("DoH response body error from {}: {}", url, e);
                            continue;
                        }
                    }
                } else {
                    warn!("DoH HTTP {} from {}", resp.status(), url);
                    continue;
                }
            }
            Err(e) => {
                warn!("DoH request failed to {}: {}", url, e);
                continue;
            }
        }
    }

    logging::bridge_log("DoH: all servers failed");
    None
}
