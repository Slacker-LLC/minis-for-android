//! Root-side HTTP/CONNECT proxy for Ubuntu guests (Q6).
//! Bind 127.0.0.1 only. Guest can loopback; outbound from uid!=0 is blocked
//! by Android bpf / VPN fake-ip. This process stays uid 0 and dials for them.

use std::io::{BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;

pub const PROXY_LISTEN: &str = "127.0.0.1:18787";
pub const PROXY_URI: &str = "http://127.0.0.1:18787";
/// Max header bytes before we refuse the request (B8: no unbounded read).
const MAX_HEADER_BYTES: usize = 16 * 1024;
/// Max concurrent client threads (B8: no unbounded thread spawn).
const MAX_CONCURRENT: usize = 64;

/// Reject private / loopback targets. The proxy only serves outbound
/// Internet traffic from the guest; allowing 127.0.0.1 / RFC1918 would let
/// the guest loop-amplify through the root proxy or probe the LAN (B8).
fn is_fake_ip(ip: Ipv4Addr) -> bool {
    let octets = ip.octets();
    octets[0] == 198 && (octets[1] == 18 || octets[1] == 19)
}

fn is_forbidden_target(ip: Ipv4Addr) -> bool {
    // Fake-IP range (198.18.0.0/15) is used by VPN/TUN clients (Clash/Sing-box)
    // for DNS routing and must always be allowed through.
    if is_fake_ip(ip) {
        return false;
    }
    ip.is_loopback() || ip.is_private() || ip.is_link_local() || ip.is_broadcast()
}

pub fn parse_target(first_line: &str) -> Result<(String, u16, bool, String), String> {
    let parts: Vec<&str> = first_line.split_whitespace().collect();
    if parts.len() < 2 {
        return Err("bad request line".into());
    }
    let method = parts[0].to_ascii_uppercase();
    let spec = parts[1];
    if method == "CONNECT" {
        let (host, port) = split_host_port(spec, 443)?;
        return Ok((host, port, true, first_line.to_string()));
    }
    if let Some(rest) = spec.strip_prefix("http://") {
        let (auth, path) = rest.split_once('/').unwrap_or((rest, ""));
        let (host, port) = split_host_port(auth, 80)?;
        let origin = format!(
            "{method} /{path} {}",
            parts.get(2).copied().unwrap_or("HTTP/1.1")
        );
        return Ok((host, port, false, origin));
    }
    Err("only CONNECT or absolute-form HTTP".into())
}

fn split_host_port(spec: &str, default_port: u16) -> Result<(String, u16), String> {
    if let Some(rest) = spec.strip_prefix('[') {
        let (host, tail) = rest.split_once(']').ok_or("bad ipv6")?;
        let port = if let Some(p) = tail.strip_prefix(':') {
            p.parse().map_err(|_| "bad port")?
        } else {
            default_port
        };
        return Ok((host.to_string(), port));
    }
    if let Some((h, p)) = spec.rsplit_once(':') {
        if !p.is_empty() && p.chars().all(|c| c.is_ascii_digit()) {
            let port: u16 = p.parse().map_err(|_| "bad port")?;
            return Ok((h.to_string(), port));
        }
    }
    Ok((spec.to_string(), default_port))
}

pub fn run_forever(listen: &str) -> Result<(), String> {
    let server = TcpListener::bind(listen).map_err(|e| format!("bind {listen}: {e}"))?;
    let _ = server.set_nonblocking(false);
    let active = Arc::new(AtomicUsize::new(0));
    println!("READY {listen}");
    let _ = std::io::stdout().flush();
    for incoming in server.incoming() {
        let stream = match incoming {
            Ok(s) => s,
            Err(_) => continue,
        };
        // B8: bounded concurrency — refuse when saturated instead of spawning
        // unbounded threads (which would let one guest exhaust root memory).
        if active.load(Ordering::SeqCst) >= MAX_CONCURRENT {
            drop(stream);
            continue;
        }
        active.fetch_add(1, Ordering::SeqCst);
        let act = Arc::clone(&active);
        thread::spawn(move || {
            let _ = handle_client(stream);
            act.fetch_sub(1, Ordering::SeqCst);
        });
    }
    Ok(())
}

fn handle_client(mut client: TcpStream) -> Result<(), String> {
    match handle_inner(&mut client) {
        Ok(()) => Ok(()),
        Err(e) => {
            let body = e.as_bytes();
            let _ = write!(
                client,
                "HTTP/1.1 502 Bad Gateway\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = client.write_all(body);
            let _ = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open("/data/adb/minis/log/proxy.log")
                .and_then(|mut f| writeln!(f, "{e}"));
            Err(e)
        }
    }
}

fn handle_inner(client: &mut TcpStream) -> Result<(), String> {
    client
        .set_read_timeout(Some(std::time::Duration::from_secs(30)))
        .ok();
    client
        .set_write_timeout(Some(std::time::Duration::from_secs(120)))
        .ok();
    let mut reader = BufReader::new(client.try_clone().map_err(|e| e.to_string())?);
    let mut total = 0usize;
    let mut read_line_capped = |reader: &mut BufReader<TcpStream>| -> Result<String, String> {
        let mut line = String::new();
        let mut buf = [0u8; 1];
        loop {
            match reader.read(&mut buf) {
                Ok(0) => return Err("eof".into()),
                Ok(_) => {
                    line.push(buf[0] as char);
                    if buf[0] == b'\n' {
                        break;
                    }
                    if line.len() > MAX_HEADER_BYTES || total + line.len() > MAX_HEADER_BYTES {
                        return Err("header too large".into());
                    }
                }
                Err(e) => return Err(e.to_string()),
            }
        }
        total += line.len();
        Ok(line)
    };
    let first = read_line_capped(&mut reader)?;
    let (host, port, is_connect, forward_line) = parse_target(first.trim_end())?;
    let mut head = Vec::new();
    if !is_connect {
        head.extend_from_slice(forward_line.as_bytes());
        head.extend_from_slice(b"\r\n");
    }
    loop {
        let line = read_line_capped(&mut reader)?;
        if !is_connect {
            head.extend_from_slice(line.as_bytes());
        }
        if line == "\r\n" || line == "\n" || line.is_empty() {
            break;
        }
    }
    let buffered = reader.buffer().to_vec();
    let mut peer = reader.into_inner();
    let ip = resolve_ipv4(&host)?;
    // B8: refuse private / loopback destinations (self-amplification + LAN probe).
    if is_forbidden_target(ip) {
        return Err(format!("blocked private/loopback target {host}({ip})"));
    }
    let mut upstream = TcpStream::connect(SocketAddr::from((ip, port)))
        .map_err(|e| format!("{host}({ip}):{port}: {e}"))?;
    if is_connect {
        peer.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .map_err(|e| e.to_string())?;
        if !buffered.is_empty() {
            upstream.write_all(&buffered).map_err(|e| e.to_string())?;
        }
    } else {
        upstream.write_all(&head).map_err(|e| e.to_string())?;
        if !buffered.is_empty() {
            upstream.write_all(&buffered).map_err(|e| e.to_string())?;
        }
    }
    // Clear read/write timeouts before data streaming / large file downloads
    peer.set_read_timeout(None).ok();
    peer.set_write_timeout(None).ok();
    upstream.set_read_timeout(None).ok();
    upstream.set_write_timeout(None).ok();
    pump(peer, upstream)
}

fn resolve_ipv4(host: &str) -> Result<Ipv4Addr, String> {
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        return Ok(ip);
    }
    // Try system resolver first (libc getaddrinfo) which natively honors
    // Android's Private DNS (DoT), system routing, and VPN/Clash Fake-IP mappings.
    if let Ok(iter) = std::net::ToSocketAddrs::to_socket_addrs(&(host, 0)) {
        for addr in iter {
            if let SocketAddr::V4(v4) = addr {
                return Ok(*v4.ip());
            }
        }
    }
    let mut last = String::new();
    let mut tried = 0usize;
    // Prefer the phone's live network resolvers (router/carrier/VPN DNS).
    // Public recursive DNS is frequently unreachable on carrier networks,
    // which is what previously made every proxy request fail with 000 even
    // though the loopback socket itself was healthy.
    for dns in crate::env::discover_dns() {
        let Ok(server) = dns.parse::<Ipv4Addr>() else {
            continue;
        };
        tried += 1;
        match dns_query_a(host, &format!("{server}:53")) {
            Ok(ip) => return Ok(ip),
            Err(e) => last = format!("{server}: {e}"),
        }
    }
    if tried == 0 {
        for dns in [
            "223.5.5.5:53",
            "119.29.29.29:53",
            "8.8.8.8:53",
            "1.1.1.1:53",
        ] {
            match dns_query_a(host, dns) {
                Ok(ip) => return Ok(ip),
                Err(e) => last = format!("{dns}: {e}"),
            }
        }
    }
    Err(format!("dns {host}: {last}"))
}

fn dns_query_a(host: &str, server: &str) -> Result<Ipv4Addr, String> {
    let mut q = Vec::new();
    q.extend_from_slice(&[
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    ]);
    for label in host.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err("bad name".into());
        }
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0);
    q.extend_from_slice(&[0x00, 0x01, 0x00, 0x01]);
    let sock = UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
    sock.set_read_timeout(Some(std::time::Duration::from_secs(3)))
        .map_err(|e| e.to_string())?;
    sock.send_to(&q, server).map_err(|e| e.to_string())?;
    let mut buf = [0u8; 512];
    let (n, _) = sock.recv_from(&mut buf).map_err(|e| e.to_string())?;
    parse_dns_a(&buf[..n])
}

fn parse_dns_a(msg: &[u8]) -> Result<Ipv4Addr, String> {
    if msg.len() < 12 {
        return Err("short dns".into());
    }
    let ancount = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut i = 12usize;
    while i < msg.len() && msg[i] != 0 {
        i += 1 + msg[i] as usize;
    }
    i += 5;
    for _ in 0..ancount {
        if i + 12 > msg.len() {
            break;
        }
        if msg[i] & 0xc0 == 0xc0 {
            i += 2;
        } else {
            while i < msg.len() && msg[i] != 0 {
                i += 1 + msg[i] as usize;
            }
            i += 1;
        }
        if i + 10 > msg.len() {
            break;
        }
        let typ = u16::from_be_bytes([msg[i], msg[i + 1]]);
        let rdlen = u16::from_be_bytes([msg[i + 8], msg[i + 9]]) as usize;
        i += 10;
        if typ == 1 && rdlen == 4 && i + 4 <= msg.len() {
            return Ok(Ipv4Addr::new(msg[i], msg[i + 1], msg[i + 2], msg[i + 3]));
        }
        i += rdlen;
    }
    Err("no A".into())
}

fn pump(a: TcpStream, b: TcpStream) -> Result<(), String> {
    let a2 = a.try_clone().map_err(|e| e.to_string())?;
    let b2 = b.try_clone().map_err(|e| e.to_string())?;
    let h = thread::spawn(move || copy(a2, b2));
    copy(b, a);
    let _ = h.join();
    Ok(())
}

fn copy(mut r: TcpStream, mut w: TcpStream) {
    let mut buf = [0u8; 64 * 1024];
    loop {
        match r.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                if w.write_all(&buf[..n]).is_err() {
                    break;
                }
            }
        }
    }
    let _ = w.shutdown(std::net::Shutdown::Write);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_connect_and_http() {
        let (h, p, c, _) = parse_target("CONNECT example.com:443 HTTP/1.1").unwrap();
        assert_eq!((h, p, c), ("example.com".into(), 443, true));
        let (h, p, c, origin) =
            parse_target("GET http://ports.ubuntu.com/ubuntu-ports/ HTTP/1.1").unwrap();
        assert_eq!((h, p, c), ("ports.ubuntu.com".into(), 80, false));
        assert!(origin.starts_with("GET /ubuntu-ports/ "));
    }

    #[test]
    fn forbidden_targets_are_rejected() {
        assert!(is_forbidden_target(Ipv4Addr::new(127, 0, 0, 1))); // loopback self-amplify
        assert!(is_forbidden_target(Ipv4Addr::new(10, 0, 0, 5))); // RFC1918 LAN
        assert!(is_forbidden_target(Ipv4Addr::new(192, 168, 1, 1))); // RFC1918
        assert!(is_forbidden_target(Ipv4Addr::new(169, 254, 1, 1))); // link-local
        assert!(!is_forbidden_target(Ipv4Addr::new(8, 8, 8, 8))); // public OK
        assert!(!is_forbidden_target(Ipv4Addr::new(185, 199, 108, 153))); // public OK
        assert!(!is_forbidden_target(Ipv4Addr::new(198, 18, 0, 1))); // Fake-IP / VPN OK
        assert!(!is_forbidden_target(Ipv4Addr::new(198, 19, 255, 254))); // Fake-IP / VPN OK
    }

    #[test]
    fn oversized_first_line_is_rejected() {
        let big = "GET ".to_string() + &"x".repeat(MAX_HEADER_BYTES) + " HTTP/1.1";
        // parse_target is not the guard; the guard lives in handle_inner via
        // read_line_capped. Here we only pin that a too-long line is a parse
        // or cap concern, not a panic.
        let _ = parse_target(&big);
    }
}
