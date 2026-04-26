//! IP-to-hostname mapping table populated from DNS responses,
//! plus minimal DNS wire format parsing for queries and A/AAAA answers.

use parking_lot::Mutex as ParkMutex;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::LazyLock;
use std::time::Instant;

// ---------------------------------------------------------------------------
// IP -> hostname table
// ---------------------------------------------------------------------------

struct DnsEntry {
    hostname: String,
    expires_at: Instant,
}

static DNS_TABLE: LazyLock<ParkMutex<HashMap<IpAddr, DnsEntry>>> =
    LazyLock::new(|| ParkMutex::new(HashMap::new()));

const MIN_TTL_SECS: u32 = 60;
const MAX_TTL_SECS: u32 = 3600;
const EVICTION_THRESHOLD: usize = 4096;

/// Insert an IP -> hostname mapping with clamped TTL.
pub fn dns_table_insert(ip: IpAddr, hostname: String, ttl_secs: u32) {
    let ttl = ttl_secs.clamp(MIN_TTL_SECS, MAX_TTL_SECS);
    let expires_at = Instant::now() + std::time::Duration::from_secs(ttl as u64);
    let mut table = DNS_TABLE.lock();
    table.insert(
        ip,
        DnsEntry {
            hostname,
            expires_at,
        },
    );
    if table.len() > EVICTION_THRESHOLD {
        let now = Instant::now();
        table.retain(|_, e| e.expires_at > now);
    }
}

/// Look up hostname for an IP. Returns None if absent or expired.
pub fn dns_table_lookup(ip: IpAddr) -> Option<String> {
    let mut table = DNS_TABLE.lock();
    if let Some(entry) = table.get(&ip) {
        if entry.expires_at > Instant::now() {
            return Some(entry.hostname.clone());
        }
        table.remove(&ip);
    }
    None
}

// ---------------------------------------------------------------------------
// DNS wire format parsing
// ---------------------------------------------------------------------------

pub fn parse_dns_query_name(data: &[u8]) -> Option<String> {
    if data.len() < 12 {
        return None;
    }
    let (name, _) = read_dns_name(data, 12)?;
    Some(name)
}

pub fn parse_dns_response_records(data: &[u8]) -> Vec<(IpAddr, String, u32)> {
    let mut results = Vec::new();
    if data.len() < 12 {
        return results;
    }

    let qdcount = u16::from_be_bytes([data[4], data[5]]) as usize;
    let ancount = u16::from_be_bytes([data[6], data[7]]) as usize;

    let (hostname, mut offset) = match read_dns_name(data, 12) {
        Some(v) => v,
        None => return results,
    };
    offset += 4;
    for _ in 1..qdcount {
        let (_, new_offset) = match read_dns_name(data, offset) {
            Some(v) => v,
            None => return results,
        };
        offset = new_offset + 4;
    }

    for _ in 0..ancount {
        if offset >= data.len() {
            break;
        }
        let (_, new_offset) = match read_dns_name(data, offset) {
            Some(v) => v,
            None => break,
        };
        offset = new_offset;

        if offset + 10 > data.len() {
            break;
        }
        let rtype = u16::from_be_bytes([data[offset], data[offset + 1]]);
        let ttl = u32::from_be_bytes([
            data[offset + 4],
            data[offset + 5],
            data[offset + 6],
            data[offset + 7],
        ]);
        let rdlength = u16::from_be_bytes([data[offset + 8], data[offset + 9]]) as usize;
        offset += 10;

        if offset + rdlength > data.len() {
            break;
        }

        match rtype {
            1 if rdlength == 4 => {
                let ip = Ipv4Addr::new(
                    data[offset],
                    data[offset + 1],
                    data[offset + 2],
                    data[offset + 3],
                );
                results.push((IpAddr::V4(ip), hostname.clone(), ttl));
            }
            28 if rdlength == 16 => {
                let mut octets = [0u8; 16];
                octets.copy_from_slice(&data[offset..offset + 16]);
                let ip = Ipv6Addr::from(octets);
                results.push((IpAddr::V6(ip), hostname.clone(), ttl));
            }
            _ => {}
        }

        offset += rdlength;
    }

    results
}

fn read_dns_name(data: &[u8], offset: usize) -> Option<(String, usize)> {
    let mut labels: Vec<String> = Vec::new();
    let mut pos = offset;
    let mut jumped = false;
    let mut end_pos = 0usize;
    let mut jumps = 0;

    loop {
        if pos >= data.len() {
            return None;
        }
        let len_byte = data[pos];

        if len_byte == 0 {
            if !jumped {
                end_pos = pos + 1;
            }
            break;
        }

        if len_byte & 0xC0 == 0xC0 {
            if pos + 1 >= data.len() {
                return None;
            }
            if !jumped {
                end_pos = pos + 2;
            }
            let ptr = ((len_byte as usize & 0x3F) << 8) | data[pos + 1] as usize;
            pos = ptr;
            jumped = true;
            jumps += 1;
            if jumps > 32 {
                return None;
            }
            continue;
        }

        let label_len = len_byte as usize;
        if pos + 1 + label_len > data.len() {
            return None;
        }
        let label = std::str::from_utf8(&data[pos + 1..pos + 1 + label_len]).ok()?;
        labels.push(label.to_string());
        pos += 1 + label_len;
    }

    let name = labels.join(".");
    Some((name, end_pos))
}
