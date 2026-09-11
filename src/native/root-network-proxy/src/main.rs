use std::fs::File;
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
const AUTH_REQUIRED: &str = "proxy authentication required";
const PROXY_USER: &str = "minis";
const FALLBACK_DNS: &[&str] = &[
    "223.5.5.5",
    "114.114.114.114",
    "119.29.29.29",
    "8.8.8.8",
    "1.1.1.1",
];
static DNS_QUERY_COUNTER: AtomicUsize = AtomicUsize::new(1);

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
    let mut auth_stdin = false;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--listen" => {
                listen = args
                    .next()
                    .unwrap_or_else(|| usage("missing --listen value"))
            }
            "--auth-stdin" => auth_stdin = true,
            "--help" | "-h" => usage(""),
            other => usage(&format!("unknown argument: {other}")),
        }
    }
    if listen != DEFAULT_LISTEN {
        usage("listen address is fixed to loopback 127.0.0.1:18787");
    }
    if !auth_stdin {
        usage("--auth-stdin is required");
    }
    let auth_token = read_auth_token().unwrap_or_else(|error| usage(&error));
    if let Err(error) = run_forever(&listen, &auth_token) {
        eprintln!("root-network-proxy: {error}");
        std::process::exit(1);
    }
}

fn usage(error: &str) -> ! {
    if !error.is_empty() {
        eprintln!("root-network-proxy: {error}");
    }
    eprintln!("usage: minis-root-network-proxy [--listen 127.0.0.1:18787] --auth-stdin");
    std::process::exit(if error.is_empty() { 0 } else { 2 });
}

fn read_auth_token() -> Result<String, String> {
    let mut token = String::new();
    std::io::stdin()
        .read_line(&mut token)
        .map_err(|error| format!("cannot read proxy auth token: {error}"))?;
    let token = token.trim_end_matches(['\r', '\n']);
    if token.len() != 64
        || !token
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
    {
        return Err("proxy auth token must be 256-bit lowercase hex".into());
    }
    Ok(token.to_string())
}

fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::with_capacity(input.len().div_ceil(3) * 4);
    let mut index = 0usize;
    while index < input.len() {
        let a = input[index] as u32;
        let b = if index + 1 < input.len() {
            input[index + 1] as u32
        } else {
            0
        };
        let c = if index + 2 < input.len() {
            input[index + 2] as u32
        } else {
            0
        };
        let bits = (a << 16) | (b << 8) | c;
        output.push(TABLE[((bits >> 18) & 0x3f) as usize] as char);
        output.push(TABLE[((bits >> 12) & 0x3f) as usize] as char);
        if index + 1 < input.len() {
            output.push(TABLE[((bits >> 6) & 0x3f) as usize] as char);
        } else {
            output.push('=');
        }
        if index + 2 < input.len() {
            output.push(TABLE[(bits & 0x3f) as usize] as char);
        } else {
            output.push('=');
        }
        index += 3;
    }
    output
}

fn expected_proxy_auth(token: &str) -> String {
    let user_info = format!("{PROXY_USER}:{token}");
    format!("Basic {}", base64_encode(user_info.as_bytes()))
}

fn constant_time_eq(left: &str, right: &str) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.as_bytes()
        .iter()
        .zip(right.as_bytes())
        .fold(0u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

fn is_fake_ip(ip: Ipv4Addr) -> bool {
    let octets = ip.octets();
    octets[0] == 198 && (octets[1] == 18 || octets[1] == 19)
}

fn is_shared_address(ip: Ipv4Addr) -> bool {
    let octets = ip.octets();
    octets[0] == 100 && (64..=127).contains(&octets[1])
}

fn is_forbidden_target(ip: Ipv4Addr) -> bool {
    if is_fake_ip(ip) {
        return false;
    }
    let octets = ip.octets();
    octets[0] == 0
        || ip.is_unspecified()
        || ip.is_loopback()
        || ip.is_private()
        || ip.is_link_local()
        || is_shared_address(ip)
        || ip.is_multicast()
        || octets[0] >= 240
        || ip.is_broadcast()
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

fn run_forever(listen: &str, auth_token: &str) -> Result<(), String> {
    let server = TcpListener::bind(listen).map_err(|e| format!("bind {listen}: {e}"))?;
    let active = Arc::new(AtomicUsize::new(0));
    let expected_auth = Arc::new(expected_proxy_auth(auth_token));
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
        let expected_auth = Arc::clone(&expected_auth);
        thread::spawn(move || {
            let _ = handle_client(stream, expected_auth.as_str());
            active.fetch_sub(1, Ordering::SeqCst);
        });
    }
    Ok(())
}

fn handle_client(mut client: TcpStream, expected_auth: &str) -> Result<(), String> {
    match handle_inner(&mut client, expected_auth) {
        Ok(()) => Ok(()),
        Err(error) => {
            let body = error.as_bytes();
            if error == AUTH_REQUIRED {
                let _ = write!(
                    client,
                    "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"minis\"\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
            } else {
                let _ = write!(
                    client,
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
            }
            let _ = client.write_all(body);
            Err(error)
        }
    }
}

fn handle_inner(client: &mut TcpStream, expected_auth: &str) -> Result<(), String> {
    handle_inner_with_connector(client, expected_auth, |host, ip, port| {
        TcpStream::connect(SocketAddr::from((ip, port)))
            .map_err(|e| format!("{host}({ip}):{port}: {e}"))
    })
}

fn handle_inner_with_connector<F>(
    client: &mut TcpStream,
    expected_auth: &str,
    connector: F,
) -> Result<(), String>
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
    let mut authenticated = false;
    let mut auth_header_seen = false;
    if !is_connect {
        head.extend_from_slice(forward_line.as_bytes());
        head.extend_from_slice(b"\r\n");
    }
    loop {
        let line = read_line_capped(&mut reader)?;
        if line == "\r\n" || line == "\n" || line.is_empty() {
            if !is_connect {
                head.extend_from_slice(line.as_bytes());
            }
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("Proxy-Authorization") {
                if auth_header_seen {
                    return Err(AUTH_REQUIRED.into());
                }
                auth_header_seen = true;
                if !constant_time_eq(value.trim(), expected_auth) {
                    return Err(AUTH_REQUIRED.into());
                }
                authenticated = true;
                continue;
            }
        }
        if !is_connect {
            head.extend_from_slice(line.as_bytes());
        }
    }
    if !authenticated {
        return Err(AUTH_REQUIRED.into());
    }

    let buffered = reader.buffer().to_vec();
    let mut peer = reader.into_inner();
    let ip = resolve_ipv4(&host)?;
    if is_forbidden_target(ip) {
        return Err(format!("blocked non-public target {host}({ip})"));
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

fn next_dns_query_id() -> u16 {
    let mut bytes = [0u8; 2];
    if File::open("/dev/urandom")
        .and_then(|mut file| file.read_exact(&mut bytes))
        .is_ok()
    {
        return u16::from_be_bytes(bytes);
    }
    let counter = DNS_QUERY_COUNTER.fetch_add(1, Ordering::Relaxed) as u16;
    counter.wrapping_add(std::process::id() as u16)
}

fn dns_query_a(host: &str, server: &str) -> Result<Ipv4Addr, String> {
    let transaction_id = next_dns_query_id();
    let mut query = Vec::new();
    query.extend_from_slice(&transaction_id.to_be_bytes());
    query.extend_from_slice(&[0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
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
    socket.connect(server).map_err(|e| e.to_string())?;
    socket.send(&query).map_err(|e| e.to_string())?;
    let mut buf = [0u8; 512];
    let size = socket.recv(&mut buf).map_err(|e| e.to_string())?;
    parse_dns_a(&buf[..size], transaction_id)
}

fn skip_dns_name(msg: &[u8], index: &mut usize) -> Result<(), String> {
    let mut labels = 0usize;
    loop {
        if *index >= msg.len() {
            return Err("truncated dns name".into());
        }
        let length = msg[*index];
        if length & 0xc0 == 0xc0 {
            if *index + 1 >= msg.len() {
                return Err("truncated dns pointer".into());
            }
            *index += 2;
            return Ok(());
        }
        if length & 0xc0 != 0 {
            return Err("invalid dns label".into());
        }
        *index += 1;
        if length == 0 {
            return Ok(());
        }
        let length = length as usize;
        if length > 63 || *index + length > msg.len() {
            return Err("truncated dns label".into());
        }
        *index += length;
        labels += 1;
        if labels > 127 {
            return Err("dns name too deep".into());
        }
    }
}

fn parse_dns_a(msg: &[u8], expected_transaction_id: u16) -> Result<Ipv4Addr, String> {
    if msg.len() < 12 {
        return Err("short dns".into());
    }
    let transaction_id = u16::from_be_bytes([msg[0], msg[1]]);
    if transaction_id != expected_transaction_id {
        return Err("dns transaction id mismatch".into());
    }
    let flags = u16::from_be_bytes([msg[2], msg[3]]);
    if flags & 0x8000 == 0 {
        return Err("dns packet is not a response".into());
    }
    if flags & 0x7800 != 0 {
        return Err("unsupported dns opcode".into());
    }
    if flags & 0x0200 != 0 {
        return Err("truncated dns response".into());
    }
    let rcode = flags & 0x000f;
    if rcode != 0 {
        return Err(format!("dns rcode {rcode}"));
    }
    let questions = u16::from_be_bytes([msg[4], msg[5]]) as usize;
    if questions != 1 {
        return Err("unexpected dns question count".into());
    }
    let answers = u16::from_be_bytes([msg[6], msg[7]]) as usize;
    let mut index = 12usize;
    skip_dns_name(msg, &mut index)?;
    if index + 4 > msg.len() {
        return Err("truncated dns question".into());
    }
    let question_type = u16::from_be_bytes([msg[index], msg[index + 1]]);
    let question_class = u16::from_be_bytes([msg[index + 2], msg[index + 3]]);
    if question_type != 1 || question_class != 1 {
        return Err("unexpected dns question".into());
    }
    index += 4;

    for _ in 0..answers {
        skip_dns_name(msg, &mut index)?;
        if index + 10 > msg.len() {
            return Err("truncated dns answer".into());
        }
        let kind = u16::from_be_bytes([msg[index], msg[index + 1]]);
        let class = u16::from_be_bytes([msg[index + 2], msg[index + 3]]);
        let length = u16::from_be_bytes([msg[index + 8], msg[index + 9]]) as usize;
        index += 10;
        if index + length > msg.len() {
            return Err("truncated dns rdata".into());
        }
        if kind == 1 && class == 1 && length == 4 {
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

    const TEST_TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn test_auth() -> String {
        expected_proxy_auth(TEST_TOKEN)
    }

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
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
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

    fn test_connector(
        origin: SocketAddr,
    ) -> impl FnOnce(&str, Ipv4Addr, u16) -> Result<TcpStream, String> {
        move |_host, _ip, _port| TcpStream::connect(origin).map_err(|error| error.to_string())
    }

    fn dns_response(transaction_id: u16, flags: u16, answer_class: u16) -> Vec<u8> {
        let mut msg = Vec::new();
        msg.extend_from_slice(&transaction_id.to_be_bytes());
        msg.extend_from_slice(&flags.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg.extend_from_slice(&1u16.to_be_bytes());
        msg.extend_from_slice(&0u16.to_be_bytes());
        msg.extend_from_slice(&0u16.to_be_bytes());
        for label in ["example", "com"] {
            msg.push(label.len() as u8);
            msg.extend_from_slice(label.as_bytes());
        }
        msg.push(0);
        msg.extend_from_slice(&[0, 1, 0, 1]);
        msg.extend_from_slice(&[0xc0, 0x0c]);
        msg.extend_from_slice(&[0, 1]);
        msg.extend_from_slice(&answer_class.to_be_bytes());
        msg.extend_from_slice(&[0, 0, 0, 60]);
        msg.extend_from_slice(&[0, 4, 8, 8, 8, 8]);
        msg
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
    fn proxy_auth_encoding_is_stable() {
        assert_eq!(base64_encode(b"minis:abc"), "bWluaXM6YWJj");
        assert!(test_auth().starts_with("Basic bWluaXM6"));
    }

    #[test]
    fn non_public_targets_are_blocked_but_fake_ip_is_allowed() {
        assert!(is_forbidden_target(Ipv4Addr::new(0, 0, 0, 0)));
        assert!(is_forbidden_target(Ipv4Addr::new(0, 1, 2, 3)));
        assert!(is_forbidden_target(Ipv4Addr::new(127, 0, 0, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(10, 0, 0, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(100, 64, 0, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(100, 127, 255, 254)));
        assert!(is_forbidden_target(Ipv4Addr::new(169, 254, 1, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(192, 168, 1, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(224, 0, 0, 1)));
        assert!(is_forbidden_target(Ipv4Addr::new(240, 0, 0, 1)));
        assert!(!is_forbidden_target(Ipv4Addr::new(100, 128, 0, 1)));
        assert!(!is_forbidden_target(Ipv4Addr::new(198, 18, 0, 1)));
        assert!(!is_forbidden_target(Ipv4Addr::new(8, 8, 8, 8)));
    }

    #[test]
    fn dns_parser_requires_matching_successful_in_a_response() {
        let response = dns_response(0x1234, 0x8180, 1);
        assert_eq!(
            parse_dns_a(&response, 0x1234).unwrap(),
            Ipv4Addr::new(8, 8, 8, 8)
        );
        assert!(parse_dns_a(&response, 0x5678).is_err());

        let not_response = dns_response(0x1234, 0x0100, 1);
        assert!(parse_dns_a(&not_response, 0x1234).is_err());

        let nxdomain = dns_response(0x1234, 0x8183, 1);
        assert!(parse_dns_a(&nxdomain, 0x1234).is_err());

        let wrong_class = dns_response(0x1234, 0x8180, 3);
        assert!(parse_dns_a(&wrong_class, 0x1234).is_err());
    }

    #[test]
    fn proxy_auth_is_required() {
        for auth_line in [None, Some("Proxy-Authorization: Basic wrong\r\n")] {
            let (mut client, proxy_side) = proxy_pair();
            let expected = test_auth();
            let proxy_thread = thread::spawn(move || handle_client(proxy_side, &expected));
            let request = format!(
                "GET http://8.8.8.8/ HTTP/1.1\r\nHost: 8.8.8.8\r\n{}Connection: close\r\n\r\n",
                auth_line.unwrap_or("")
            );
            client.write_all(request.as_bytes()).unwrap();
            client.shutdown(Shutdown::Write).unwrap();
            let mut response = String::new();
            client.read_to_string(&mut response).unwrap();
            assert!(response.starts_with("HTTP/1.1 407 Proxy Authentication Required"));
            assert!(response.contains("Proxy-Authenticate: Basic realm=\"minis\""));
            assert_eq!(proxy_thread.join().unwrap().unwrap_err(), AUTH_REQUIRED);
        }
    }

    #[test]
    fn duplicate_proxy_auth_is_rejected() {
        let (mut client, proxy_side) = proxy_pair();
        let expected = test_auth();
        let proxy_thread = thread::spawn(move || handle_client(proxy_side, &expected));
        let auth = test_auth();
        let request = format!(
            "GET http://8.8.8.8/ HTTP/1.1\r\nHost: 8.8.8.8\r\nProxy-Authorization: {auth}\r\nProxy-Authorization: {auth}\r\nConnection: close\r\n\r\n"
        );
        client.write_all(request.as_bytes()).unwrap();
        client.shutdown(Shutdown::Write).unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).unwrap();
        assert!(response.starts_with("HTTP/1.1 407 Proxy Authentication Required"));
        assert_eq!(proxy_thread.join().unwrap().unwrap_err(), AUTH_REQUIRED);
    }

    #[test]
    fn apt_style_absolute_http_is_forwarded_end_to_end() {
        let (origin, origin_thread) = local_origin(
            b"HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\nInRelease",
        );
        let (mut client, mut proxy_side) = proxy_pair();
        let expected = test_auth();
        let proxy_thread = thread::spawn(move || {
            handle_inner_with_connector(&mut proxy_side, &expected, test_connector(origin))
        });

        let request = format!(
            "GET http://8.8.8.8/ubuntu/dists/noble/InRelease HTTP/1.1\r\nHost: archive.ubuntu.com\r\nProxy-Authorization: {}\r\nConnection: close\r\n\r\n",
            test_auth()
        );
        client.write_all(request.as_bytes()).unwrap();
        client.shutdown(Shutdown::Write).unwrap();
        let mut response = String::new();
        client.read_to_string(&mut response).unwrap();
        assert!(response.starts_with("HTTP/1.1 200 OK"));
        assert!(response.ends_with("InRelease"));

        let forwarded = String::from_utf8(origin_thread.join().unwrap()).unwrap();
        assert!(forwarded.starts_with("GET /ubuntu/dists/noble/InRelease HTTP/1.1\r\n"));
        assert!(forwarded.contains("Host: archive.ubuntu.com\r\n"));
        assert!(!forwarded
            .to_ascii_lowercase()
            .contains("proxy-authorization:"));
        proxy_thread.join().unwrap().unwrap();
    }

    #[test]
    fn connect_tunnel_forwards_bidirectional_payload() {
        let (origin, origin_thread) = local_origin(
            b"HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\ntunnel",
        );
        let (mut client, mut proxy_side) = proxy_pair();
        let expected = test_auth();
        let proxy_thread = thread::spawn(move || {
            handle_inner_with_connector(&mut proxy_side, &expected, test_connector(origin))
        });

        let request = format!(
            "CONNECT 8.8.8.8:443 HTTP/1.1\r\nHost: 8.8.8.8:443\r\nProxy-Authorization: {}\r\n\r\n",
            test_auth()
        );
        client.write_all(request.as_bytes()).unwrap();
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
            .write_all(
                b"GET /through-tunnel HTTP/1.1\r\nHost: example.test\r\nConnection: close\r\n\r\n",
            )
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
