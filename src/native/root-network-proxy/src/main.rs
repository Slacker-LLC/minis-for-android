use std::io::{BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs, UdpSocket};
use std::process::Command;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

const DEFAULT_LISTEN: &str = "127.0.0.1:18787";
const MAX_HEADER_BYTES: usize = 16 * 1024;
const MAX_CONCURRENT: usize = 64;
const FALLBACK_DNS: &[&str] = &[
    "223.5.5.5",
    "114.114.114.114",
    "119.29.29.29",
    "8.8.8.8",
    "1.1.1.1",
];

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn prctl(option: i32, arg2: usize, arg3: usize, arg4: usize, arg5: usize) -> i32;
}

fn main() {
    #[cfg(target_os = "android")]
    unsafe {
        const PR_SET_PDEATHSIG: i32 = 1;
        const SIGTERM: usize = 15;
        let _ = prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0);
    }

    let mut listen = DEFAULT_LISTEN.to_string();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--listen" => {
                listen = args
                    .next()
                    .unwrap_or_else(|| usage("missing --listen value"))
            }
            "--help" | "-h" => usage(""),
            other => usage(&format!("unknown argument: {other}")),
        }
    }
    if listen != DEFAULT_LISTEN {
        usage("listen address is fixed to loopback 127.0.0.1:18787");
    }
    if let Err(error) = run_forever(&listen) {
        eprintln!("root-network-proxy: {error}");
        std::process::exit(1);
    }
}

fn usage(error: &str) -> ! {
    if !error.is_empty() {
        eprintln!("root-network-proxy: {error}");
    }
    eprintln!("usage: minis-root-network-proxy [--listen 127.0.0.1:18787]");
    std::process::exit(if error.is_empty() { 0 } else { 2 });
}

fn is_fake_ip(ip: Ipv4Addr) -> bool {
    let octets = ip.octets();
    octets[0] == 198 && (octets[1] == 18 || octets[1] == 19)
}

fn is_forbidden_target(ip: Ipv4Addr) -> bool {
    if is_fake_ip(ip) {
        return false;
    }
    ip.is_loopback() || ip.is_private() || ip.is_link_local() || ip.is_broadcast()
}

fn parse_target(first_line: &str) -> Result<(String, u16, bool, String), String> {
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
    Err("only CONNECT or absolute-form HTTP is supported".into())
}

fn split_host_port(spec: &str, default_port: u16) -> Result<(String, u16), String> {
    if let Some(rest) = spec.strip_prefix('[') {
        let (host, tail) = rest.split_once(']').ok_or("bad ipv6")?;
        let port = if let Some(port) = tail.strip_prefix(':') {
            port.parse().map_err(|_| "bad port")?
        } else {
            default_port
        };
        return Ok((host.to_string(), port));
    }
    if let Some((host, port)) = spec.rsplit_once(':') {
        if !port.is_empty() && port.chars().all(|c| c.is_ascii_digit()) {
            return Ok((host.to_string(), port.parse().map_err(|_| "bad port")?));
        }
    }
    Ok((spec.to_string(), default_port))
}

fn run_forever(listen: &str) -> Result<(), String> {
    let server = TcpListener::bind(listen).map_err(|e| format!("bind {listen}: {e}"))?;
    let active = Arc::new(AtomicUsize::new(0));
    println!("READY {listen}");
    let _ = std::io::stdout().flush();
    for incoming in server.incoming() {
        let stream = match incoming {
            Ok(stream) => stream,
            Err(_) => continue,
        };
        if active.load(Ordering::SeqCst) >= MAX_CONCURRENT {
            drop(stream);
            continue;
        }
        active.fetch_add(1, Ordering::SeqCst);
        let active = Arc::clone(&active);
        thread::spawn(move || {
            let _ = handle_client(stream);
            active.fetch_sub(1, Ordering::SeqCst);
        });
    }
    Ok(())
}

fn handle_client(mut client: TcpStream) -> Result<(), String> {
    match handle_inner(&mut client) {
        Ok(()) => Ok(()),
        Err(error) => {
            let body = error.as_bytes();
            let _ = write!(
                client,
                "HTTP/1.1 502 Bad Gateway\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            let _ = client.write_all(body);
            Err(error)
        }
    }
}

fn handle_inner(client: &mut TcpStream) -> Result<(), String> {
    handle_inner_with_connector(client, |host, ip, port| {
        TcpStream::connect(SocketAddr::from((ip, port)))
            .map_err(|e| format!("{host}({ip}):{port}: {e}"))
    })
}

fn handle_inner_with_connector<F>(client: &mut TcpStream, connector: F) -> Result<(), String>
where
    F: FnOnce(&str, Ipv4Addr, u16) -> Result<TcpStream, String>,
{
    client.set_read_timeout(Some(Duration::from_secs(30))).ok();
    client
        .set_write_timeout(Some(Duration::from_secs(120)))
        .ok();
    let mut reader = BufReader::new(client.try_clone().map_err(|e| e.to_string())?);
    let mut total = 0usize;
    let mut read_line_capped = |reader: &mut BufReader<TcpStream>| -> Result<String, String> {
        let mut line = String::new();
        let mut byte = [0u8; 1];
        loop {
            match reader.read(&mut byte) {
                Ok(0) => return Err("eof".into()),
                Ok(_) => {
                    line.push(byte[0] as char);
                    if byte[0] == b'\n' {
                        break;
                    }
                    if total + line.len() > MAX_HEADER_BYTES {
                        return Err("header too large".into());
                    }
                }
                Err(error) => return Err(error.to_string()),
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
    if is_forbidden_target(ip) {
        return Err(format!("blocked private/loopback target {host}({ip})"));
    }
    let mut upstream = connector(&host, ip, port)?;
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
    if let Ok(iter) = (host, 0).to_socket_addrs() {
        for addr in iter {
            if let SocketAddr::V4(v4) = addr {
                return Ok(*v4.ip());
            }
        }
    }
    let mut last = String::new();
    for dns in discover_dns()
        .into_iter()
        .chain(FALLBACK_DNS.iter().map(|s| s.to_string()))
    {
        let Ok(server) = dns.parse::<Ipv4Addr>() else {
            continue;
        };
        match dns_query_a(host, &format!("{server}:53")) {
            Ok(ip) => return Ok(ip),
            Err(error) => last = format!("{server}: {error}"),
        }
    }
    Err(format!("dns {host}: {last}"))
}

fn discover_dns() -> Vec<String> {
    let mut found = Vec::new();
    for key in [
        "net.dns1",
        "net.dns2",
        "net.dns3",
        "dhcp.wlan0.dns1",
        "dhcp.wlan0.dns2",
        "dhcp.eth0.dns1",
    ] {
        if let Ok(output) = Command::new("/system/bin/getprop").arg(key).output() {
            let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if usable_dns(&value) && !found.contains(&value) {
                found.push(value);
            }
        }
    }
    if let Ok(output) = Command::new("/system/bin/dumpsys")
        .arg("connectivity")
        .output()
    {
        let text = String::from_utf8_lossy(&output.stdout);
        for line in text.lines() {
            let lower = line.to_ascii_lowercase();
            let Some(start) = lower.find("dnsaddresses") else {
                continue;
            };
            let rest = &line[start..];
            let Some(open) = rest.find('[') else {
                continue;
            };
            let Some(close) = rest[open..].find(']') else {
                continue;
            };
            for token in rest[open + 1..open + close]
                .split(|c: char| !c.is_ascii_hexdigit() && c != '.' && c != ':')
            {
                if usable_dns(token) && !found.iter().any(|value| value == token) {
                    found.push(token.to_string());
                }
            }
        }
    }
    found
}

fn usable_dns(value: &str) -> bool {
    if let Ok(ip) = value.parse::<Ipv4Addr>() {
        return !ip.is_unspecified() && !ip.is_loopback() && !ip.is_broadcast();
    }
    false
}

fn dns_query_a(host: &str, server: &str) -> Result<Ipv4Addr, String> {
    let mut query = Vec::new();
    query.extend_from_slice(&[0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
    for label in host.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err("bad name".into());
        }
        query.push(label.len() as u8);
        query.extend_from_slice(label.as_bytes());
    }
    query.push(0);
    query.extend_from_slice(&[0, 1, 0, 1]);
    let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| e.to_string())?;
    socket
        .set_read_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| e.to_string())?;
    socket.send_to(&query, server).map_err(|e| e.to_string())?;
    let mut buf = [0u8; 512];
    let (size, _) = socket.recv_from(&mut buf).map_err(|e| e.to_string())?;
    parse_dns_a(&buf[..size])
}

fn parse_dns_a(msg: &[u8]) -> Result<Ipv4Addr, String> {
    if msg.len() < 12 {
        return Err("short dns".into());
    }
    let answers = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut index = 12usize;
    while index < msg.len() && msg[index] != 0 {
        index += 1 + msg[index] as usize;
    }
    index += 5;
    for _ in 0..answers {
        if index + 12 > msg.len() {
            break;
        }
        if msg[index] & 0xc0 == 0xc0 {
            index += 2;
        } else {
            while index < msg.len() && msg[index] != 0 {
                index += 1 + msg[index] as usize;
            }
            index += 1;
        }
        if index + 10 > msg.len() {
            break;
        }
        let kind = u16::from_be_bytes([msg[index], msg[index + 1]]);
        let length = u16::from_be_bytes([msg[index + 8], msg[index + 9]]) as usize;
        index += 10;
        if kind == 1 && length == 4 && index + 4 <= msg.len() {
            return Ok(Ipv4Addr::new(
                msg[index],
                msg[index + 1],
                msg[index + 2],
                msg[index + 3],
            ));
        }
        index += length;
    }
    Err("no A".into())
}

fn pump(a: TcpStream, b: TcpStream) -> Result<(), String> {
    let a2 = a.try_clone().map_err(|e| e.to_string())?;
    let b2 = b.try_clone().map_err(|e| e.to_string())?;
    let handle = thread::spawn(move || copy(a2, b2));
    copy(b, a);
    let _ = handle.join();
    Ok(())
}

fn copy(mut reader: TcpStream, mut writer: TcpStream) {
    let mut buf = [0u8; 64 * 1024];
    loop {
        match reader.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(size) => {
                if writer.write_all(&buf[..size]).is_err() {
                    break;
                }
            }
        }
    }
    let _ = writer.shutdown(std::net::Shutdown::Write);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Shutdown;

    fn proxy_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let client = TcpStream::connect(address).unwrap();
        let (server, _) = listener.accept().unwrap();
        (client, server)
    }

    fn local_origin(response: &'static [u8]) -> (SocketAddr, thread::JoinHandle<Vec<u8>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let handle = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let mut request = Vec::new();
            let mut byte = [0u8; 1];
            while request.windows(4).last() != Some(b"\r\n\r\n") {
                if stream.read(&mut byte).unwrap_or(0) == 0 {
                    break;
                }
                request.push(byte[0]);
            }
            stream.write_all(response).unwrap();
            let _ = stream.shutdown(Shutdown::Write);
            request
        });
        (address, handle)
    }

    fn test_connector(origin: SocketAddr) -> impl FnOnce(&str, Ipv4Addr, u16) -> Result<TcpStream, String> {
        move |_host, _ip, _port| TcpStream::connect(origin).map_err(|error| error.to_string())
    }

    #[test]
    fn connect_and_http_targets_parse() {
        let (host, port, connect, _) = parse_target("CONNECT example.com:443 HTTP/1.1").unwrap();
        assert_eq!(
            (host, port, connect),
            ("example.com".to_string(), 443, true)
        );
        let (host, port, connect, line) =
            parse_target("GET http://ports.ubuntu.com/ubuntu-ports/ HTTP/1.1").unwrap();
        assert_eq!(
            (host, port, connect),
            ("ports.ubuntu.com".to_string(), 80, false)
        );
        assert!(line.starts_with("GET /ubuntu-ports/ "));
    }

    #[test]
    fn lan_targets_are_blocked_but_fake_ip_is_allowed() {
        assert!(is_forbidden_target(Ipv4Addr::new(127, 0, 0, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(10, 0, 0, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(192, 168, 1, 1)));
        assert!(!is_forbidden_target(Ipv4Addr::new(198, 18, 0, 1)));
        assert!(!is_forbidden_target(Ipv4Addr::new(8, 8, 8, 8)));
    }

    #[test]
    fn apt_style_absolute_http_is_forwarded_end_to_end() {
        let (origin, origin_thread) = local_origin(
            b"HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\nInRelease",
        );
        let (mut client, mut proxy_side) = proxy_pair();
        let proxy_thread = thread::spawn(move || {
            handle_inner_with_connector(&mut proxy_side, test_connector(origin))
        });

        client
            .write_all(
                b"GET http://8.8.8.8/ubuntu/dists/noble/InRelease HTTP/1.1\r\nHost: archive.ubuntu.com\r\nConnection: close\r\n\r\n",
            )
            .unwrap();
        client.shutdown(Shutdown::Write).unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).unwrap();
        assert!(response.starts_with("HTTP/1.1 200 OK"));
        assert!(response.ends_with("InRelease"));

        let forwarded = String::from_utf8(origin_thread.join().unwrap()).unwrap();
        assert!(forwarded.starts_with("GET /ubuntu/dists/noble/InRelease HTTP/1.1\r\n"));
        assert!(forwarded.contains("Host: archive.ubuntu.com\r\n"));
        proxy_thread.join().unwrap().unwrap();
    }

    #[test]
    fn connect_tunnel_forwards_bidirectional_payload() {
        let (origin, origin_thread) = local_origin(
            b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\ntunnel",
        );
        let (mut client, mut proxy_side) = proxy_pair();
        let proxy_thread = thread::spawn(move || {
            handle_inner_with_connector(&mut proxy_side, test_connector(origin))
        });

        client
            .write_all(b"CONNECT 8.8.8.8:443 HTTP/1.1\r\nHost: 8.8.8.8:443\r\n\r\n")
            .unwrap();
        let mut established = Vec::new();
        let mut byte = [0u8; 1];
        while established.windows(4).last() != Some(b"\r\n\r\n") {
            client.read_exact(&mut byte).unwrap();
            established.push(byte[0]);
        }
        assert_eq!(
            String::from_utf8(established).unwrap(),
            "HTTP/1.1 200 Connection Established\r\n\r\n"
        );

        client
            .write_all(b"GET /through-tunnel HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n")
            .unwrap();
        client.shutdown(Shutdown::Write).unwrap();
        let mut tunneled = String::new();
        client.read_to_string(&mut tunneled).unwrap();
        assert!(tunneled.starts_with("HTTP/1.1 200 OK"));
        assert!(tunneled.ends_with("tunnel"));

        let forwarded = String::from_utf8(origin_thread.join().unwrap()).unwrap();
        assert!(forwarded.starts_with("GET /through-tunnel HTTP/1.1\r\n"));
        proxy_thread.join().unwrap().unwrap();
    }
}
