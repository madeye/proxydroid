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
    if let IpAddr::V4(v4) = ip {
        if is_fake_ip(v4) {
            return FAKE_POOL.lock().by_ip.get(&v4).cloned();
        }
    }
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
// Fake-IP fallback
// ---------------------------------------------------------------------------
//
// When DoH cannot be reached through the upstream proxy (typically a proxy
// that only allows selected destinations), DNS would otherwise fail outright
// and nothing would work. Instead each queried hostname is answered with a
// placeholder address from 198.18.0.0/16 (reserved for benchmarking by
// RFC 2544, never routed on the Internet). When the app then connects to that
// address, `dns_table_lookup` maps it back to the hostname and the upstream
// proxy resolves the name itself. No query leaves the device unproxied.

const FAKE_NET: u32 = 0xC612_0000; // 198.18.0.0
const FAKE_MASK: u32 = 0xFFFF_0000;
/// Usable host addresses: skips the network and broadcast addresses.
const FAKE_POOL_SIZE: u32 = (1 << 16) - 2;
/// TTL of synthesized answers. Short, so apps return to real answers soon
/// after DoH recovers; the mapping itself outlives it.
const FAKE_TTL_SECS: u32 = 60;

const DNS_TYPE_A: u16 = 1;
const DNS_CLASS_IN: u16 = 1;

#[derive(Default)]
struct FakePool {
    by_host: HashMap<String, Ipv4Addr>,
    by_ip: HashMap<Ipv4Addr, String>,
    next: u32,
}

static FAKE_POOL: LazyLock<ParkMutex<FakePool>> =
    LazyLock::new(|| ParkMutex::new(FakePool::default()));

fn is_fake_ip(ip: Ipv4Addr) -> bool {
    u32::from(ip) & FAKE_MASK == FAKE_NET
}

/// Return the placeholder address for `hostname`, allocating one on first use.
/// Addresses are handed out round-robin; once the pool wraps, the oldest
/// mapping is replaced.
fn fake_ip_for(hostname: &str) -> Ipv4Addr {
    let hostname = hostname.to_ascii_lowercase();
    let mut pool = FAKE_POOL.lock();
    if let Some(ip) = pool.by_host.get(&hostname) {
        return *ip;
    }
    let ip = Ipv4Addr::from(FAKE_NET + 1 + pool.next % FAKE_POOL_SIZE);
    pool.next = pool.next.wrapping_add(1);
    if let Some(evicted) = pool.by_ip.insert(ip, hostname.clone()) {
        pool.by_host.remove(&evicted);
    }
    pool.by_host.insert(hostname, ip);
    ip
}

/// Build a locally synthesized response to `query`.
///
/// `A` queries get a placeholder address (see above). Every other type gets
/// an empty NOERROR answer, which makes resolvers fall back to `A`. Returns
/// `None` if `query` is not a single-question standard query.
pub fn build_fake_dns_response(query: &[u8]) -> Option<Vec<u8>> {
    if query.len() < 12 {
        return None;
    }
    let is_response = query[2] & 0x80 != 0;
    let opcode = (query[2] >> 3) & 0x0F;
    let qdcount = u16::from_be_bytes([query[4], query[5]]);
    if is_response || opcode != 0 || qdcount != 1 {
        return None;
    }

    let (name, name_end) = read_dns_name(query, 12)?;
    let question_end = name_end + 4;
    if name.is_empty() || question_end > query.len() {
        return None;
    }
    let qtype = u16::from_be_bytes([query[name_end], query[name_end + 1]]);
    let qclass = u16::from_be_bytes([query[name_end + 2], query[name_end + 3]]);
    let answer = (qtype == DNS_TYPE_A && qclass == DNS_CLASS_IN).then(|| fake_ip_for(&name));

    let mut resp = Vec::with_capacity(question_end + 16);
    resp.extend_from_slice(&query[0..2]); // ID
    resp.push(0x80 | (query[2] & 0x01)); // QR=1, echo RD
    resp.push(0x80); // RA=1, RCODE=NOERROR
    resp.extend_from_slice(&1u16.to_be_bytes()); // QDCOUNT
    resp.extend_from_slice(&(answer.is_some() as u16).to_be_bytes()); // ANCOUNT
    resp.extend_from_slice(&[0, 0, 0, 0]); // NSCOUNT, ARCOUNT
    resp.extend_from_slice(&query[12..question_end]);
    if let Some(ip) = answer {
        resp.extend_from_slice(&[0xC0, 0x0C]); // name: pointer to the question
        resp.extend_from_slice(&DNS_TYPE_A.to_be_bytes());
        resp.extend_from_slice(&DNS_CLASS_IN.to_be_bytes());
        resp.extend_from_slice(&FAKE_TTL_SECS.to_be_bytes());
        resp.extend_from_slice(&4u16.to_be_bytes()); // RDLENGTH
        resp.extend_from_slice(&ip.octets());
    }
    Some(resp)
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Standard query for `name` with the given type, ID 0x1234, RD set.
    fn query(name: &str, qtype: u16) -> Vec<u8> {
        let mut q = vec![0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0];
        for label in name.split('.') {
            q.push(label.len() as u8);
            q.extend_from_slice(label.as_bytes());
        }
        q.push(0);
        q.extend_from_slice(&qtype.to_be_bytes());
        q.extend_from_slice(&DNS_CLASS_IN.to_be_bytes());
        q
    }

    #[test]
    fn fake_a_answer_maps_back_to_hostname() {
        let q = query("Fake-A.example.test", DNS_TYPE_A);
        let resp = build_fake_dns_response(&q).expect("response");

        assert_eq!(&resp[0..2], &[0x12, 0x34], "ID is echoed");
        assert_eq!(resp[2], 0x81, "QR and RD set");
        assert_eq!(resp[3] & 0x0F, 0, "NOERROR");

        let records = parse_dns_response_records(&resp);
        assert_eq!(records.len(), 1);
        let (ip, _, ttl) = &records[0];
        assert_eq!(*ttl, FAKE_TTL_SECS);
        let IpAddr::V4(v4) = *ip else {
            panic!("expected an IPv4 answer")
        };
        assert!(is_fake_ip(v4));
        assert_eq!(
            dns_table_lookup(*ip).as_deref(),
            Some("fake-a.example.test")
        );
    }

    #[test]
    fn same_hostname_keeps_its_address() {
        let a = fake_ip_for("stable.example.test");
        let b = fake_ip_for("STABLE.example.test");
        let other = fake_ip_for("other.example.test");
        assert_eq!(a, b);
        assert_ne!(a, other);
    }

    #[test]
    fn non_a_query_gets_empty_noerror() {
        let q = query("fake-aaaa.example.test", 28);
        let resp = build_fake_dns_response(&q).expect("response");
        assert_eq!(u16::from_be_bytes([resp[6], resp[7]]), 0, "ANCOUNT");
        assert_eq!(resp[3] & 0x0F, 0, "NOERROR");
        assert_eq!(resp.len(), q.len(), "header + question only");
    }

    #[test]
    fn malformed_or_non_query_is_ignored() {
        assert!(build_fake_dns_response(&[0u8; 5]).is_none());

        let mut response = query("resp.example.test", DNS_TYPE_A);
        response[2] |= 0x80;
        assert!(build_fake_dns_response(&response).is_none());

        let mut truncated = query("cut.example.test", DNS_TYPE_A);
        truncated.truncate(truncated.len() - 3);
        assert!(build_fake_dns_response(&truncated).is_none());
    }

    #[test]
    fn unknown_fake_address_has_no_hostname() {
        // Top of the pool: only reachable after ~65k allocations.
        let unused = IpAddr::V4(Ipv4Addr::new(198, 18, 255, 254));
        assert_eq!(dns_table_lookup(unused), None);
    }
}
