use std::io::{BufRead, BufReader, BufWriter, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

const HOST_ROOTFS: &str = "/data/adb/minis/rootfs";
const GUEST_CONFIG_BIN: &str = "/opt/minis/bin/minis-config";
const MAX_ARGC: usize = 128;
const MAX_ARG_BYTES: usize = 64 * 1024;
const MAX_FILE_BYTES: usize = 2 * 1024 * 1024;
const MAX_BRIDGE_BYTES: usize = 2 * 1024 * 1024;
const MAGIC: &str = "MINISCFG1";

static STARTED: OnceLock<()> = OnceLock::new();
static START_LOCK: Mutex<()> = Mutex::new(());

/// Start the guest-facing minis-config proxy once per minisd process.
///
/// The proxy is deliberately separate from minisd's privileged JSON-RPC
/// socket: arbitrary Ubuntu code receives only the minis-config surface and
/// cannot reuse this channel for root.exec / ubuntu.adminExec / other broker
/// methods. A per-process random token prevents unrelated localhost clients
/// from using the proxy; the token is materialized only inside the Ubuntu
/// rootfs alongside the CLI wrapper.
///
/// Failed initialization is never cached as success. A short bounded retry
/// covers transient bind/rootfs filesystem races during broker startup; a later
/// call may retry again if all attempts fail.
pub fn ensure_started(app_uid: u32) {
    if app_uid == 0 || STARTED.get().is_some() {
        return;
    }
    let _guard = match START_LOCK.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    if STARTED.get().is_some() {
        return;
    }

    let mut last_error = None;
    for attempt in 0..3 {
        match start_proxy(app_uid) {
            Ok(()) => {
                let _ = STARTED.set(());
                return;
            }
            Err(error) => {
                last_error = Some(error);
                if attempt < 2 {
                    std::thread::sleep(Duration::from_millis(150));
                }
            }
        }
    }
    if let Some(error) = last_error {
        eprintln!("minis-config proxy init failed: {error}");
    }
}

fn start_proxy(app_uid: u32) -> Result<(), String> {
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .map_err(|e| format!("bind minis-config proxy: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("minis-config proxy local_addr: {e}"))?
        .port();
    let token = random_token()?;
    install_guest_cli(port, &token)?;
    let bridge_name = format!("minis-config-bridge-{app_uid}");
    std::thread::Builder::new()
        .name("minisd-config-proxy".into())
        .spawn(move || accept_loop(listener, token, bridge_name))
        .map_err(|e| format!("spawn minis-config proxy: {e}"))?;
    Ok(())
}

fn accept_loop(listener: TcpListener, token: String, bridge_name: String) {
    for accepted in listener.incoming() {
        let Ok(stream) = accepted else {
            continue;
        };
        let token = token.clone();
        let bridge_name = bridge_name.clone();
        let _ = std::thread::Builder::new()
            .name("minisd-config-request".into())
            .spawn(move || {
                let _ = handle_guest(stream, &token, &bridge_name);
            });
    }
}

fn handle_guest(mut stream: TcpStream, expected_token: &str, bridge_name: &str) -> Result<(), String> {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(15)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(15)));
    let reader_stream = stream
        .try_clone()
        .map_err(|e| format!("clone config stream: {e}"))?;
    let mut reader = BufReader::new(reader_stream);

    let magic = read_line_limited(&mut reader, 32)?;
    if magic != MAGIC {
        return write_cli_response(&mut stream, 1, "minis-config: invalid proxy protocol\n");
    }
    let got_token = read_line_limited(&mut reader, 256)?;
    if !constant_time_eq(expected_token.as_bytes(), got_token.as_bytes()) {
        return write_cli_response(&mut stream, 126, "minis-config: proxy permission denied\n");
    }
    let argc: usize = read_line_limited(&mut reader, 16)?
        .parse()
        .map_err(|_| "invalid argc".to_string())?;
    if argc > MAX_ARGC {
        return write_cli_response(&mut stream, 1, "minis-config: too many arguments\n");
    }

    let mut args = Vec::with_capacity(argc);
    for _ in 0..argc {
        let line = read_line_limited(&mut reader, MAX_ARG_BYTES * 2 + 2)?;
        let bytes = hex_decode(&line, MAX_ARG_BYTES)?;
        let arg = String::from_utf8(bytes).map_err(|_| "argument is not utf-8".to_string())?;
        if arg.contains('\0') {
            return write_cli_response(&mut stream, 1, "minis-config: NUL in argument\n");
        }
        args.push(arg);
    }

    let session = String::from_utf8(hex_decode(
        &read_line_limited(&mut reader, MAX_ARG_BYTES * 2 + 2)?,
        MAX_ARG_BYTES,
    )?)
    .map_err(|_| "session is not utf-8".to_string())?;
    let cwd = String::from_utf8(hex_decode(
        &read_line_limited(&mut reader, MAX_ARG_BYTES * 2 + 2)?,
        MAX_ARG_BYTES,
    )?)
    .map_err(|_| "cwd is not utf-8".to_string())?;

    let has_file = read_line_limited(&mut reader, 4)?;
    let file_payload = match has_file.as_str() {
        "0" => None,
        "1" => {
            let line = read_line_limited(&mut reader, MAX_FILE_BYTES * 2 + 2)?;
            Some(hex_decode(&line, MAX_FILE_BYTES)?)
        }
        _ => return write_cli_response(&mut stream, 1, "minis-config: invalid file marker\n"),
    };

    let args = match rewrite_file_argument(args, file_payload) {
        Ok(v) => v,
        Err(e) => return write_cli_response(&mut stream, 1, &format!("minis-config: {e}\n")),
    };

    let (exit_code, output) = match forward_to_android(bridge_name, &args, &cwd, &session) {
        Ok(v) => v,
        Err(e) => (1, format!("minis-config bridge unavailable: {e}\n")),
    };
    write_cli_response(&mut stream, exit_code, &output)
}

fn rewrite_file_argument(mut args: Vec<String>, payload: Option<Vec<u8>>) -> Result<Vec<String>, String> {
    let Some(payload) = payload else {
        return Ok(args);
    };
    let Some(index) = args.iter().position(|a| a == "--file") else {
        return Err("file payload supplied without --file".into());
    };
    if index + 1 >= args.len() {
        return Err("--file requires a path".into());
    }
    let text = String::from_utf8(payload).map_err(|_| "--file content is not utf-8".to_string())?;
    args.splice(index..=index + 1, [text]);
    Ok(args)
}

fn forward_to_android(
    bridge_name: &str,
    args: &[String],
    cwd: &str,
    session: &str,
) -> Result<(i32, String), String> {
    #[cfg(not(unix))]
    {
        let _ = (bridge_name, args, cwd, session);
        Err("Android config bridge requires unix".into())
    }
    #[cfg(unix)]
    {
        let mut stream = connect_abstract(bridge_name)?;
        let _ = stream.set_read_timeout(Some(Duration::from_secs(130)));
        let _ = stream.set_write_timeout(Some(Duration::from_secs(15)));

        let mut argv = Vec::with_capacity(args.len() + 1);
        argv.push("minis-config".to_string());
        argv.extend(args.iter().cloned());
        let request = serde_json::json!({
            "argv": argv,
            "cwd": if cwd.is_empty() { "/workspace" } else { cwd },
            "session": session,
        });
        let body = serde_json::to_vec(&request).map_err(|e| format!("encode bridge request: {e}"))?;
        if body.is_empty() || body.len() > MAX_BRIDGE_BYTES {
            return Err("bridge request too large".into());
        }
        stream
            .write_all(&(body.len() as u32).to_be_bytes())
            .and_then(|_| stream.write_all(&body))
            .and_then(|_| stream.flush())
            .map_err(|e| format!("write Android bridge: {e}"))?;

        let mut header = [0u8; 4];
        stream
            .read_exact(&mut header)
            .map_err(|e| format!("read Android bridge header: {e}"))?;
        let len = u32::from_be_bytes(header) as usize;
        if len == 0 || len > MAX_BRIDGE_BYTES {
            return Err("invalid Android bridge response length".into());
        }
        let mut response = vec![0u8; len];
        stream
            .read_exact(&mut response)
            .map_err(|e| format!("read Android bridge response: {e}"))?;
        let json: serde_json::Value =
            serde_json::from_slice(&response).map_err(|e| format!("decode Android bridge: {e}"))?;
        let exit_code = json
            .get("exit_code")
            .and_then(|v| v.as_i64())
            .unwrap_or(1)
            .clamp(0, 255) as i32;
        let output = json
            .get("output")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        Ok((exit_code, output))
    }
}

#[cfg(unix)]
fn connect_abstract(name: &str) -> Result<std::os::unix::net::UnixStream, String> {
    use std::os::fd::FromRawFd;

    let name_bytes = name.as_bytes();
    let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    if name_bytes.len() + 1 > addr.sun_path.len() {
        return Err("Android bridge socket name too long".into());
    }
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    addr.sun_path[0] = 0;
    for (dst, src) in addr.sun_path[1..].iter_mut().zip(name_bytes.iter()) {
        *dst = *src as libc::c_char;
    }

    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(format!("socket: {}", std::io::Error::last_os_error()));
    }
    let addr_len = std::mem::size_of::<libc::sa_family_t>() + 1 + name_bytes.len();
    let rc = unsafe {
        libc::connect(
            fd,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            addr_len as libc::socklen_t,
        )
    };
    if rc != 0 {
        let error = std::io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(format!("connect @{name}: {error}"));
    }
    Ok(unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) })
}

fn install_guest_cli(port: u16, token: &str) -> Result<(), String> {
    let root = std::path::Path::new(HOST_ROOTFS);
    if !root.join("etc/os-release").is_file() {
        return Err(format!("Ubuntu rootfs missing at {HOST_ROOTFS}"));
    }
    let bin_dir = root.join("opt/minis/bin");
    let etc_dir = root.join("etc/minis");
    let usr_local_bin = root.join("usr/local/bin");
    std::fs::create_dir_all(&bin_dir).map_err(|e| format!("mkdir {}: {e}", bin_dir.display()))?;
    std::fs::create_dir_all(&etc_dir).map_err(|e| format!("mkdir {}: {e}", etc_dir.display()))?;
    std::fs::create_dir_all(&usr_local_bin)
        .map_err(|e| format!("mkdir {}: {e}", usr_local_bin.display()))?;

    let config = format!("MINIS_CONFIG_PROXY_PORT={port}\nMINIS_CONFIG_PROXY_TOKEN='{token}'\n");
    std::fs::write(etc_dir.join("minis-config-proxy"), config)
        .map_err(|e| format!("write minis-config proxy config: {e}"))?;

    let script = wrapper_script();
    let script_path = bin_dir.join("minis-config");
    std::fs::write(&script_path, script)
        .map_err(|e| format!("write {}: {e}", script_path.display()))?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::{symlink, PermissionsExt};
        std::fs::set_permissions(&script_path, std::fs::Permissions::from_mode(0o755))
            .map_err(|e| format!("chmod {}: {e}", script_path.display()))?;
        let cfg_path = etc_dir.join("minis-config-proxy");
        std::fs::set_permissions(&cfg_path, std::fs::Permissions::from_mode(0o644))
            .map_err(|e| format!("chmod {}: {e}", cfg_path.display()))?;

        let link = usr_local_bin.join("minis-config");
        if link.symlink_metadata().is_ok() {
            std::fs::remove_file(&link)
                .map_err(|e| format!("remove stale {}: {e}", link.display()))?;
        }
        symlink(GUEST_CONFIG_BIN, &link)
            .map_err(|e| format!("symlink {}: {e}", link.display()))?;
    }
    Ok(())
}

fn wrapper_script() -> &'static str {
    r#"#!/bin/bash
set -u
CFG=/etc/minis/minis-config-proxy
if [ ! -r "$CFG" ]; then
  echo "minis-config: minisd proxy is not initialized" >&2
  exit 127
fi
. "$CFG"
if [ -z "${MINIS_CONFIG_PROXY_PORT:-}" ] || [ -z "${MINIS_CONFIG_PROXY_TOKEN:-}" ]; then
  echo "minis-config: invalid minisd proxy configuration" >&2
  exit 127
fi
exec 3<>"/dev/tcp/127.0.0.1/${MINIS_CONFIG_PROXY_PORT}" || {
  echo "minis-config: cannot connect to minisd proxy" >&2
  exit 127
}
printf 'MINISCFG1\n%s\n%s\n' "$MINIS_CONFIG_PROXY_TOKEN" "$#" >&3
for arg in "$@"; do
  printf '%s' "$arg" | od -An -v -tx1 | tr -d ' \n' >&3
  printf '\n' >&3
done
printf '%s' "${MINIS_CHAT_SESSION_ID:-}" | od -An -v -tx1 | tr -d ' \n' >&3
printf '\n' >&3
printf '%s' "$PWD" | od -An -v -tx1 | tr -d ' \n' >&3
printf '\n' >&3
file_path=''
prev=''
for arg in "$@"; do
  if [ "$prev" = '--file' ]; then
    file_path="$arg"
    break
  fi
  prev="$arg"
done
if [ -n "$file_path" ]; then
  if [ ! -r "$file_path" ] || [ ! -f "$file_path" ]; then
    echo "minis-config: --file cannot read '$file_path'" >&2
    exit 1
  fi
  printf '1\n' >&3
  od -An -v -tx1 -- "$file_path" | tr -d ' \n' >&3
  printf '\n' >&3
else
  printf '0\n' >&3
fi
if ! IFS= read -r exit_code <&3; then
  echo "minis-config: minisd proxy closed without a response" >&2
  exit 1
fi
cat <&3
case "$exit_code" in
  ''|*[!0-9]*) exit 1 ;;
  *) exit "$exit_code" ;;
esac
"#
}

fn random_token() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut bytes))
        .map_err(|e| format!("read /dev/urandom: {e}"))?;
    Ok(hex_encode(&bytes))
}

fn read_line_limited<R: BufRead>(reader: &mut R, max: usize) -> Result<String, String> {
    let mut out = Vec::new();
    loop {
        let buf = reader.fill_buf().map_err(|e| e.to_string())?;
        if buf.is_empty() {
            return Err("unexpected EOF".into());
        }
        let take = buf
            .iter()
            .position(|b| *b == b'\n')
            .map(|i| i + 1)
            .unwrap_or(buf.len());
        let remaining = max.saturating_add(1).saturating_sub(out.len());
        if take > remaining {
            return Err("proxy line too large".into());
        }
        out.extend_from_slice(&buf[..take]);
        reader.consume(take);
        if out.last() == Some(&b'\n') {
            out.pop();
            if out.last() == Some(&b'\r') {
                out.pop();
            }
            return String::from_utf8(out).map_err(|_| "proxy line is not utf-8".into());
        }
    }
}

fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

fn hex_decode(text: &str, max_bytes: usize) -> Result<Vec<u8>, String> {
    if text.len() % 2 != 0 || text.len() / 2 > max_bytes {
        return Err("invalid hex payload length".into());
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() / 2);
    let mut i = 0;
    while i < bytes.len() {
        let hi = hex_nibble(bytes[i]).ok_or_else(|| "invalid hex payload".to_string())?;
        let lo = hex_nibble(bytes[i + 1]).ok_or_else(|| "invalid hex payload".to_string())?;
        out.push((hi << 4) | lo);
        i += 2;
    }
    Ok(out)
}

fn hex_nibble(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn write_cli_response(stream: &mut TcpStream, exit_code: i32, output: &str) -> Result<(), String> {
    let mut writer = BufWriter::new(stream);
    writeln!(writer, "{}", exit_code.clamp(0, 255)).map_err(|e| e.to_string())?;
    writer.write_all(output.as_bytes()).map_err(|e| e.to_string())?;
    writer.flush().map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_roundtrip_preserves_shell_sensitive_text() {
        let raw = b"quote=\"x\" backslash=\\ newline=\n dollar=$ backtick=`";
        let encoded = hex_encode(raw);
        assert_eq!(hex_decode(&encoded, 1024).unwrap(), raw);
    }

    #[test]
    fn file_payload_replaces_file_flag_without_shell_roundtrip() {
        let args = vec![
            "set".to_string(),
            "soul.body".to_string(),
            "--file".to_string(),
            "/tmp/soul.json".to_string(),
        ];
        let payload = b"\"line 1\\nline 2 with $ and ` and \\\\ and \\\"quote\\\"\"".to_vec();
        let out = rewrite_file_argument(args, Some(payload.clone())).unwrap();
        assert_eq!(out.len(), 3);
        assert_eq!(out[0], "set");
        assert_eq!(out[1], "soul.body");
        assert_eq!(out[2].as_bytes(), payload.as_slice());
    }

    #[test]
    fn wrapper_installs_canonical_cli_path_and_minisd_transport() {
        let script = wrapper_script();
        assert!(script.contains("/etc/minis/minis-config-proxy"));
        assert!(script.contains("/dev/tcp/127.0.0.1/"));
        assert!(script.contains("--file"));
        assert!(!script.contains("root.exec"));
        assert!(!script.contains("ubuntu.adminExec"));
        assert_eq!(GUEST_CONFIG_BIN, "/opt/minis/bin/minis-config");
    }
}
