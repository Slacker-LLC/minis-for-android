use minisd::policy::PolicyFile;
use minisd::protocol::{
    decode_frame_len, encode_response, frame_header, ErrorCode, Request, Response,
    FRAME_HEADER_BYTES, MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES,
};
use minisd::state::AppState;
use minisd::{handle, parse_request};
use std::fs;
use std::io::{Read, Write};
use std::path::PathBuf;
use std::process::ExitCode;

struct Args {
    mock: bool,
    once: bool,
    call: bool,
    watchdog: bool,
    #[cfg_attr(not(unix), allow(dead_code))]
    socket: String,
    #[cfg_attr(not(unix), allow(dead_code))]
    app_socket: Option<String>,
    policy: Option<PathBuf>,
    policy_json: Option<String>,
    lease_pid: Option<i32>,
}

fn parse_args() -> Result<Args, String> {
    let mut mock = false;
    let mut once = false;
    let mut call = false;
    let mut watchdog = false;
    let mut socket = "/data/adb/minis/run/minisd.sock".to_string();
    let mut app_socket = None;
    let mut policy = None;
    let mut policy_json = None;
    let mut lease_pid = None;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--mock" => mock = true,
            "--once" => once = true,
            "--call" => call = true,
            "--watchdog" => watchdog = true,
            "--socket" => {
                socket = it.next().ok_or("--socket needs a path")?;
            }
            "--app-socket" => {
                app_socket = Some(it.next().ok_or("--app-socket needs a path")?);
            }
            "--policy" => {
                policy = Some(PathBuf::from(it.next().ok_or("--policy needs a path")?));
            }
            "--policy-json" => {
                policy_json = Some(it.next().ok_or("--policy-json needs JSON")?);
            }
            "--lease-pid" => {
                lease_pid = Some(
                    it.next()
                        .ok_or("--lease-pid needs a pid")?
                        .parse()
                        .map_err(|_| "--lease-pid must be numeric")?,
                );
            }
            "--help" | "-h" => {
                eprintln!(
                    "minisd [--mock] [--once] [--call] [--watchdog] [--socket NAME] [--app-socket NAME] [--policy PATH] [--policy-json JSON] [--lease-pid PID]"
                );
                eprintln!("minisd --helper keep --rootfs PATH");
                eprintln!(
                    "minisd --helper exec --pid N --rootfs PATH [--session-root PATH] --uid N --gid N --cwd PATH -- ARGV"
                );
                std::process::exit(0);
            }
            other => return Err(format!("unknown arg: {other}")),
        }
    }
    Ok(Args {
        mock,
        once,
        call,
        watchdog,
        socket,
        app_socket,
        policy,
        policy_json,
        lease_pid,
    })
}

fn load_policy(path: &Option<PathBuf>, inline: &Option<String>) -> Result<PolicyFile, String> {
    if let Some(raw) = inline {
        return PolicyFile::parse(raw);
    }
    match path {
        Some(p) => {
            let raw = fs::read_to_string(p).map_err(|e| format!("read policy: {e}"))?;
            PolicyFile::parse(&raw)
        }
        None => Ok(PolicyFile::default_policy()),
    }
}

fn once_stdio(state: &mut AppState) -> ExitCode {
    let mut buf = Vec::new();
    if let Err(e) = std::io::stdin().read_to_end(&mut buf) {
        eprintln!("stdin: {e}");
        return ExitCode::from(1);
    }
    if buf.len() > MAX_REQUEST_BYTES {
        let _ = writeln!(std::io::stderr(), "request too large");
        return ExitCode::from(1);
    }
    let resp = match parse_request(&buf) {
        Ok(req) => handle(state, req, None),
        Err(resp) => resp,
    };
    match encode_response(&resp) {
        Ok(s) => {
            let _ = writeln!(std::io::stdout(), "{s}");
            if resp.ok {
                ExitCode::SUCCESS
            } else {
                ExitCode::from(2)
            }
        }
        Err(_) => ExitCode::from(1),
    }
}

fn main() -> ExitCode {
    let argv: Vec<String> = std::env::args().collect();
    if argv.get(1).map(|s| s.as_str()) == Some("--helper") {
        return helper_main(&argv[2..]);
    }
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::from(2);
        }
    };
    if args.watchdog {
        return watchdog_loop(
            args.mock,
            args.socket,
            args.app_socket,
            args.policy,
            args.policy_json,
            args.lease_pid,
        );
    }
    let policy = match load_policy(&args.policy, &args.policy_json) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::from(2);
        }
    };
    if args.call {
        #[cfg(unix)]
        {
            return unix_call(&args.socket);
        }
        #[cfg(not(unix))]
        {
            eprintln!("--call requires unix");
            return ExitCode::from(2);
        }
    }
    let mut state = AppState::new(args.mock, policy);
    if !args.mock {
        state.ubuntu = minisd::ubuntu::recover_state();
    }
    state.skip_peer = args.once;
    if !args.mock && !running_as_root() {
        eprintln!("root required (KernelSU); or pass --mock");
        return ExitCode::from(3);
    }
    if args.once {
        return once_stdio(&mut state);
    }
    #[cfg(unix)]
    {
        unix_server(state, args.socket, args.app_socket)
    }
    #[cfg(not(unix))]
    {
        eprintln!("socket server requires unix; use --once");
        ExitCode::from(2)
    }
}

fn watchdog_loop(
    mock: bool,
    socket: String,
    app_socket: Option<String>,
    policy: Option<PathBuf>,
    policy_json: Option<String>,
    lease_pid: Option<i32>,
) -> ExitCode {
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("current_exe: {e}");
            return ExitCode::from(1);
        }
    };
    loop {
        if let Some(pid) = lease_pid {
            if !lease_alive(pid) {
                return ExitCode::SUCCESS;
            }
        }
        let mut cmd = std::process::Command::new(&exe);
        if mock {
            cmd.arg("--mock");
        }
        cmd.arg("--socket").arg(&socket);
        if let Some(p) = &app_socket {
            cmd.arg("--app-socket").arg(p);
        }
        if let Some(p) = &policy {
            cmd.arg("--policy").arg(p);
        }
        if let Some(raw) = &policy_json {
            cmd.arg("--policy-json").arg(raw);
        }
        if let Some(pid) = lease_pid {
            cmd.arg("--lease-pid").arg(pid.to_string());
        }
        match cmd.status() {
            Ok(st) => eprintln!("minisd child exited {st}; restart in 1s"),
            Err(e) => eprintln!("minisd spawn: {e}"),
        }
        if let Some(pid) = lease_pid {
            if !lease_alive(pid) {
                return ExitCode::SUCCESS;
            }
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}

#[cfg(unix)]
fn lease_alive(pid: i32) -> bool {
    pid > 0 && unsafe { libc::kill(pid, 0) == 0 }
}

#[cfg(not(unix))]
fn lease_alive(_pid: i32) -> bool {
    false
}

#[cfg(unix)]
fn write_frame_sync<W: Write>(writer: &mut W, payload: &[u8], max: usize) -> Result<(), String> {
    let header = frame_header(payload.len(), max).map_err(|_| "invalid frame size".to_string())?;
    writer
        .write_all(&header)
        .and_then(|_| writer.write_all(payload))
        .and_then(|_| writer.flush())
        .map_err(|e| e.to_string())
}

#[cfg(unix)]
fn read_frame_sync<R: Read>(reader: &mut R, max: usize) -> Result<Vec<u8>, String> {
    let mut header = [0u8; FRAME_HEADER_BYTES];
    reader.read_exact(&mut header).map_err(|e| e.to_string())?;
    let len = decode_frame_len(header, max).map_err(|_| "invalid frame size".to_string())?;
    let mut payload = vec![0u8; len];
    reader.read_exact(&mut payload).map_err(|e| e.to_string())?;
    Ok(payload)
}

#[cfg(unix)]
fn unix_call(socket: &str) -> ExitCode {
    let mut buf = Vec::new();
    if let Err(e) = std::io::stdin().read_to_end(&mut buf) {
        eprintln!("stdin: {e}");
        return ExitCode::from(1);
    }
    if buf.is_empty() || buf.len() > MAX_REQUEST_BYTES {
        return ExitCode::from(1);
    }
    let mut stream = match connect_sock(socket) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("connect {socket}: {e}");
            return ExitCode::from(1);
        }
    };
    if let Err(e) = write_frame_sync(&mut stream, &buf, MAX_REQUEST_BYTES) {
        eprintln!("write frame: {e}");
        return ExitCode::from(1);
    }
    let out = match read_frame_sync(&mut stream, MAX_RESPONSE_BYTES) {
        Ok(bytes) => match String::from_utf8(bytes) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("response utf8: {e}");
                return ExitCode::from(1);
            }
        },
        Err(e) => {
            eprintln!("read frame: {e}");
            return ExitCode::from(1);
        }
    };
    print!("{out}");
    let success = serde_json::from_str::<serde_json::Value>(&out)
        .ok()
        .and_then(|v| v.get("ok").and_then(|o| o.as_bool()))
        == Some(true);
    if success {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(2)
    }
}

fn running_as_root() -> bool {
    #[cfg(unix)]
    {
        unsafe { libc::geteuid() == 0 }
    }
    #[cfg(not(unix))]
    {
        false
    }
}

#[cfg(unix)]
/// Try to take the instance lock on `<socket>.pid`.
/// Returns the held File on success; the flock is released automatically when
/// the fd is closed (i.e. when minisd exits). None means another instance is
/// running (EWOULDBLOCK/EAGAIN) or the lock could not be taken at all.
fn acquire_pidfile_lock(socket: &std::path::Path) -> Option<std::fs::File> {
    use std::io::Write;
    use std::os::fd::AsRawFd;
    let pid_path = socket.with_extension("pid");
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&pid_path)
        .ok()?;
    let fd = f.as_raw_fd();
    if unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return None;
    }
    let _ = f.set_len(0);
    if f.write_all(format!("{}\n", std::process::id()).as_bytes())
        .and_then(|()| f.flush())
        .is_err()
    {
        return None;
    }
    Some(f)
}

#[cfg(unix)]
fn unix_server(state: AppState, socket: String, app_socket: Option<String>) -> ExitCode {
    use std::sync::{Arc, Mutex};

    enable_subreaper();
    let _lock = if is_abstract_socket(&socket) {
        None
    } else {
        let path = std::path::Path::new(&socket);
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        match acquire_pidfile_lock(path) {
            Some(f) => Some(f),
            None => {
                eprintln!("minisd already running (pidfile locked)");
                return ExitCode::from(4);
            }
        }
    };
    if !is_abstract_socket(&socket) {
        let _ = fs::remove_file(&socket);
    }
    let rt = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4)
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("runtime: {e}");
            return ExitCode::from(1);
        }
    };
    rt.block_on(async move {
        let listener = match bind_sock(&socket, 0o700) {
            Ok(l) => l,
            Err(e) => {
                eprintln!("{e}");
                return ExitCode::from(1);
            }
        };
        let app_listener = match app_socket.as_ref() {
            Some(p) => match bind_sock(p, 0o666) {
                Ok(l) => {
                    eprintln!("minisd app-socket {p}");
                    Some(l)
                }
                Err(e) => {
                    eprintln!("app-socket {e}");
                    None
                }
            },
            None => None,
        };
        let state = Arc::new(Mutex::new(state));
        eprintln!("minisd listen {socket}");
        loop {
            let accepted = if let Some(app) = app_listener.as_ref() {
                tokio::select! {
                    a = listener.accept() => a,
                    b = app.accept() => b,
                }
            } else {
                listener.accept().await
            };
            let (stream, _) = match accepted {
                Ok(s) => s,
                Err(_) => continue,
            };
            let state = Arc::clone(&state);
            tokio::spawn(async move {
                serve_client(stream, state).await;
            });
        }
    })
}

#[cfg(unix)]
fn bind_sock(path: &str, mode: u32) -> Result<tokio::net::UnixListener, String> {
    if is_abstract_socket(path) {
        return bind_abstract_sock(path);
    }
    let path = std::path::Path::new(path);
    use std::os::unix::fs::PermissionsExt;
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::remove_file(path);
    let listener = tokio::net::UnixListener::bind(path)
        .map_err(|e| format!("bind {}: {e}", path.display()))?;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(mode));
    Ok(listener)
}

#[cfg(unix)]
fn is_abstract_socket(name: &str) -> bool {
    name.starts_with('@')
}

#[cfg(unix)]
fn abstract_sockaddr(name: &str) -> Result<(libc::sockaddr_un, libc::socklen_t), String> {
    use std::mem::{size_of, zeroed};
    let name = name.strip_prefix('@').unwrap_or(name).as_bytes();
    if name.is_empty() {
        return Err("abstract socket name is empty".to_string());
    }
    let capacity = size_of::<libc::sockaddr_un>() - size_of::<libc::sa_family_t>();
    if name.len() >= capacity {
        return Err("abstract socket name is too long".to_string());
    }
    let mut addr: libc::sockaddr_un = unsafe { zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    let path = addr.sun_path.as_mut_ptr() as *mut u8;
    unsafe { std::ptr::copy_nonoverlapping(name.as_ptr(), path.add(1), name.len()) };
    let len = (size_of::<libc::sa_family_t>() + 1 + name.len()) as libc::socklen_t;
    Ok((addr, len))
}

#[cfg(unix)]
fn connect_sock(name: &str) -> Result<std::os::unix::net::UnixStream, String> {
    use std::os::fd::FromRawFd;
    if !is_abstract_socket(name) {
        return std::os::unix::net::UnixStream::connect(name).map_err(|e| e.to_string());
    }
    let (addr, len) = abstract_sockaddr(name)?;
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().to_string());
    }
    let rc = unsafe {
        libc::connect(
            fd,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            len,
        )
    };
    if rc != 0 {
        let error = std::io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(error.to_string());
    }
    Ok(unsafe { std::os::unix::net::UnixStream::from_raw_fd(fd) })
}

#[cfg(unix)]
fn bind_abstract_sock(name: &str) -> Result<tokio::net::UnixListener, String> {
    use std::os::fd::FromRawFd;
    let (addr, len) = abstract_sockaddr(name)?;
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0) };
    if fd < 0 {
        return Err(std::io::Error::last_os_error().to_string());
    }
    let bound = unsafe {
        libc::bind(
            fd,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            len,
        ) == 0
    };
    if !bound {
        let error = std::io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(format!("bind {name}: {error}"));
    }
    if unsafe { libc::listen(fd, 128) } != 0 {
        let error = std::io::Error::last_os_error();
        unsafe { libc::close(fd) };
        return Err(format!("listen {name}: {error}"));
    }
    let listener = unsafe { std::os::unix::net::UnixListener::from_raw_fd(fd) };
    listener
        .set_nonblocking(true)
        .map_err(|e| format!("nonblocking {name}: {e}"))?;
    tokio::net::UnixListener::from_std(listener).map_err(|e| format!("listener {name}: {e}"))
}

#[cfg(unix)]
async fn read_frame_async<R>(reader: &mut R, max: usize) -> Result<Vec<u8>, String>
where
    R: tokio::io::AsyncRead + Unpin,
{
    use tokio::io::AsyncReadExt;
    let mut header = [0u8; FRAME_HEADER_BYTES];
    reader
        .read_exact(&mut header)
        .await
        .map_err(|e| e.to_string())?;
    let len = decode_frame_len(header, max).map_err(|_| "invalid frame size".to_string())?;
    let mut payload = vec![0u8; len];
    reader
        .read_exact(&mut payload)
        .await
        .map_err(|e| e.to_string())?;
    Ok(payload)
}

#[cfg(unix)]
async fn write_frame_async<W>(writer: &mut W, payload: &[u8], max: usize) -> Result<(), String>
where
    W: tokio::io::AsyncWrite + Unpin,
{
    use tokio::io::AsyncWriteExt;
    let header = frame_header(payload.len(), max).map_err(|_| "invalid frame size".to_string())?;
    writer.write_all(&header).await.map_err(|e| e.to_string())?;
    writer.write_all(payload).await.map_err(|e| e.to_string())?;
    writer.flush().await.map_err(|e| e.to_string())
}

#[cfg(unix)]
fn poisoned_lock<T>(state: &std::sync::Arc<std::sync::Mutex<T>>) -> std::sync::MutexGuard<'_, T> {
    state
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(unix)]
async fn dispatch_socket_request(
    state: std::sync::Arc<std::sync::Mutex<AppState>>,
    req: Request,
    peer: Option<minisd::auth::PeerCred>,
) -> Response {
    let id = req.id;
    match req.method.as_str() {
        "root.exec" => {
            let (mock, spec) = {
                let mut st = poisoned_lock(&state);
                if let Err(resp) = minisd::dispatch::authorize_request(&mut st, &req, peer) {
                    return resp;
                }
                (st.mock, st.policy.method("root.exec").cloned())
            };
            match tokio::task::spawn_blocking(move || {
                minisd::dispatch::execute_root_authorized(mock, spec, &req)
            })
            .await
            {
                Ok(resp) => resp,
                Err(_) => Response::err(id, ErrorCode::Internal, "root.exec worker failed"),
            }
        }
        "ubuntu.exec" | "ubuntu.adminExec" => {
            let admin = req.method == "ubuntu.adminExec";
            let snapshot = {
                let mut st = poisoned_lock(&state);
                if let Err(resp) = minisd::dispatch::authorize_request(&mut st, &req, peer) {
                    return resp;
                }
                if st.mock {
                    return minisd::dispatch::dispatch_authorized(&mut st, &req);
                }
                match minisd::ipc_exec::snapshot_ubuntu_exec(&mut st) {
                    Ok(snapshot) => snapshot,
                    Err((code, detail)) => return Response::err(id, code, detail),
                }
            };
            let params = req.params.clone();
            match tokio::task::spawn_blocking(move || {
                minisd::ipc_exec::execute_ubuntu_snapshot(snapshot, params, admin)
            })
            .await
            {
                Ok(Ok(value)) => Response::ok(id, value),
                Ok(Err((code, detail))) => Response::err(id, code, detail),
                Err(_) => Response::err(id, ErrorCode::Internal, "ubuntu.exec worker failed"),
            }
        }
        _ => match tokio::task::spawn_blocking(move || {
            let mut st = poisoned_lock(&state);
            handle(&mut st, req, peer)
        })
        .await
        {
            Ok(resp) => resp,
            Err(_) => Response::err(id, ErrorCode::Internal, "request worker failed"),
        },
    }
}

#[cfg(unix)]
async fn serve_client(
    mut stream: tokio::net::UnixStream,
    state: std::sync::Arc<std::sync::Mutex<AppState>>,
) {
    use minisd::auth::read_peer;
    use std::os::unix::io::AsRawFd;

    let peer = read_peer(stream.as_raw_fd());
    let payload = match tokio::time::timeout(
        std::time::Duration::from_millis(minisd::protocol::REQUEST_TIMEOUT_MS),
        read_frame_async(&mut stream, MAX_REQUEST_BYTES),
    )
    .await
    {
        Ok(Ok(payload)) => payload,
        _ => return,
    };
    let req = match parse_request(&payload) {
        Ok(req) => req,
        Err(resp) => {
            if let Ok(encoded) = encode_response(&resp) {
                let _ =
                    write_frame_async(&mut stream, encoded.as_bytes(), MAX_RESPONSE_BYTES).await;
            }
            return;
        }
    };
    let resp = dispatch_socket_request(state, req, peer).await;
    if let Ok(encoded) = encode_response(&resp) {
        let _ = write_frame_async(&mut stream, encoded.as_bytes(), MAX_RESPONSE_BYTES).await;
    }
}

#[cfg(unix)]
fn enable_subreaper() {
    unsafe {
        libc::prctl(libc::PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);
    }
}

fn helper_main(args: &[String]) -> ExitCode {
    #[cfg(not(unix))]
    {
        let _ = args;
        eprintln!("helpers require unix");
        return ExitCode::from(8);
    }
    #[cfg(unix)]
    {
        match helper_unix(args) {
            Ok(()) => ExitCode::SUCCESS,
            Err((code, msg)) => {
                eprintln!("{msg}");
                ExitCode::from(code)
            }
        }
    }
}

#[cfg(unix)]
fn helper_unix(args: &[String]) -> Result<(), (u8, String)> {
    let mut kind = "";
    let mut rootfs = minisd::layout::HOST_ROOTFS.to_string();
    let mut workspace = String::new();
    let mut memory = String::new();
    let mut skills = String::new();
    let mut shared = String::new();
    let mut session_root = String::new();
    let mut pid: i32 = 0;
    let mut uid = minisd::layout::GUEST_UID;
    let mut gid = minisd::layout::GUEST_GID;
    let mut cwd = minisd::layout::GUEST_HOME.to_string();
    let mut listen = minisd::proxy::PROXY_LISTEN.to_string();
    let mut tz = "LCL-8".to_string();
    let mut proxy = String::new();
    let mut extra_env: std::collections::BTreeMap<String, String> =
        std::collections::BTreeMap::new();
    let mut guest_argv: Vec<String> = Vec::new();
    let mut i = 0usize;
    while i < args.len() {
        match args[i].as_str() {
            "keep" | "exec" | "netproxy" => {
                kind = args[i].as_str();
                i += 1;
            }
            "--listen" => {
                listen = args
                    .get(i + 1)
                    .ok_or((8u8, "--listen needs value".into()))?
                    .clone();
                i += 2;
            }
            "--workspace" => {
                workspace = args
                    .get(i + 1)
                    .ok_or((8u8, "--workspace needs value".into()))?
                    .clone();
                i += 2;
            }
            "--memory" => {
                memory = args
                    .get(i + 1)
                    .ok_or((8u8, "--memory needs value".into()))?
                    .clone();
                i += 2;
            }
            "--skills" => {
                skills = args
                    .get(i + 1)
                    .ok_or((8u8, "--skills needs value".into()))?
                    .clone();
                i += 2;
            }
            "--shared" => {
                shared = args
                    .get(i + 1)
                    .ok_or((8u8, "--shared needs value".into()))?
                    .clone();
                i += 2;
            }
            "--session-root" => {
                session_root = args
                    .get(i + 1)
                    .ok_or((8u8, "--session-root needs value".into()))?
                    .clone();
                i += 2;
            }
            "--rootfs" => {
                rootfs = args
                    .get(i + 1)
                    .ok_or((8u8, "--rootfs needs value".into()))?
                    .clone();
                i += 2;
            }
            "--pid" => {
                pid = args
                    .get(i + 1)
                    .ok_or((8u8, "--pid needs value".into()))?
                    .parse()
                    .map_err(|_| (8u8, "bad --pid".into()))?;
                i += 2;
            }
            "--uid" => {
                uid = args
                    .get(i + 1)
                    .ok_or((8u8, "--uid needs value".into()))?
                    .parse()
                    .map_err(|_| (8u8, "bad --uid".into()))?;
                i += 2;
            }
            "--gid" => {
                gid = args
                    .get(i + 1)
                    .ok_or((8u8, "--gid needs value".into()))?
                    .parse()
                    .map_err(|_| (8u8, "bad --gid".into()))?;
                i += 2;
            }
            "--cwd" => {
                cwd = args
                    .get(i + 1)
                    .ok_or((8u8, "--cwd needs value".into()))?
                    .clone();
                i += 2;
            }
            "--tz" => {
                tz = args
                    .get(i + 1)
                    .ok_or((8u8, "--tz needs value".into()))?
                    .clone();
                i += 2;
            }
            "--proxy" => {
                proxy = args.get(i + 1).cloned().unwrap_or_default();
                i += 2;
            }
            "--env" => {
                let kv = args.get(i + 1).ok_or((8u8, "--env needs KEY=VAL".into()))?;
                let (k, v) = kv
                    .split_once('=')
                    .ok_or((8u8, "--env needs KEY=VAL".into()))?;
                extra_env.insert(k.to_string(), v.to_string());
                i += 2;
            }
            "--" => {
                guest_argv = args[i + 1..].to_vec();
                break;
            }
            other => return Err((8, format!("unknown helper arg: {other}"))),
        }
    }
    match kind {
        "keep" => helper_keep(&rootfs, &workspace, &memory, &skills, &shared),
        "exec" => helper_exec(
            pid,
            &rootfs,
            &session_root,
            uid,
            gid,
            &cwd,
            &tz,
            &proxy,
            &extra_env,
            &guest_argv,
        ),
        "netproxy" => minisd::proxy::run_forever(&listen).map_err(|e| (1u8, e)),
        _ => Err((8, "helper kind must be keep|exec|netproxy".into())),
    }
}

#[cfg(unix)]
fn helper_keep(
    rootfs: &str,
    workspace: &str,
    memory: &str,
    skills: &str,
    shared: &str,
) -> Result<(), (u8, String)> {
    use minisd::ns;
    unsafe {
        libc::setpgid(0, 0);
    }
    ns::set_process_name("minisd-keep");
    ns::unshare_mount().map_err(|e| (1u8, e))?;
    ns::make_rprivate_root().map_err(|e| (2u8, e))?;
    ns::setup_rootfs_mounts(rootfs, workspace, memory, skills, shared).map_err(|e| (3u8, e))?;
    println!("READY {}", std::process::id());
    let _ = std::io::Write::flush(&mut std::io::stdout());
    unsafe {
        libc::close(1);
    }
    loop {
        unsafe {
            libc::pause();
        }
    }
}

#[cfg(unix)]
// This is the direct CLI-to-syscall helper boundary; keeping the security-
// relevant uid/gid/rootfs/cwd/env inputs explicit is clearer than hiding them
// in a loosely reusable options bag.
#[allow(clippy::too_many_arguments)]
fn helper_exec(
    pid: i32,
    rootfs: &str,
    session_root: &str,
    uid: u32,
    gid: u32,
    cwd: &str,
    tz: &str,
    proxy: &str,
    extra_env: &std::collections::BTreeMap<String, String>,
    argv: &[String],
) -> Result<(), (u8, String)> {
    use minisd::env::guest_env;
    use minisd::ns;
    use std::ffi::CString;
    if argv.is_empty() {
        return Err((8, "missing guest argv".into()));
    }
    unsafe {
        libc::setpgid(0, 0);
    }
    ns::set_process_name("minisd-exec");
    ns::setns_mnt(pid).map_err(|e| (4u8, e))?;
    if !session_root.is_empty() {
        // Each command receives a private copy of the keeper mount namespace.
        // Session bind mounts therefore cannot race with another chat or
        // mutate the long-lived keeper namespace.
        ns::unshare_mount().map_err(|e| (4u8, e))?;
        ns::make_rprivate_root().map_err(|e| (4u8, e))?;
        ns::setup_session_mounts(rootfs, session_root).map_err(|e| (4u8, e))?;
    }
    ns::chroot_to(rootfs).map_err(|e| (5u8, e))?;
    if std::env::set_current_dir(cwd).is_err() {
        let _ = std::env::set_current_dir("/");
    }
    if uid == 0 {
        ns::lockdown_no_privs().map_err(|e| (6u8, e))?;
    } else {
        ns::drop_privs(uid, gid).map_err(|e| (6u8, e))?;
    }
    let home = if uid == 0 {
        "/root"
    } else {
        minisd::layout::GUEST_HOME
    };
    let env = guest_env(tz, proxy, home, extra_env);
    let c_argv: Vec<CString> = argv
        .iter()
        .map(|s| CString::new(s.as_str()).map_err(|_| (8u8, "NUL in argv".into())))
        .collect::<Result<_, _>>()?;
    let mut argv_ptrs: Vec<*const libc::c_char> = c_argv.iter().map(|s| s.as_ptr()).collect();
    argv_ptrs.push(std::ptr::null());
    let c_env: Vec<CString> = env
        .iter()
        .map(|(k, v)| CString::new(format!("{k}={v}")).map_err(|_| (8u8, "NUL in env".into())))
        .collect::<Result<_, _>>()?;
    let mut env_ptrs: Vec<*const libc::c_char> = c_env.iter().map(|s| s.as_ptr()).collect();
    env_ptrs.push(std::ptr::null());
    unsafe {
        libc::execve(c_argv[0].as_ptr(), argv_ptrs.as_ptr(), env_ptrs.as_ptr());
    }
    Err((
        7,
        format!("execve {}: {}", argv[0], std::io::Error::last_os_error()),
    ))
}

#[cfg(all(test, unix))]
mod ipc_tests {
    use super::*;
    use tokio::io::AsyncWriteExt;

    #[test]
    fn framed_reader_accepts_fragmented_stream_writes() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let (mut client, mut server) = tokio::io::duplex(256);
            let payload = br#"{"v":1,"id":9,"method":"system.ping"}"#.to_vec();
            let header = frame_header(payload.len(), MAX_REQUEST_BYTES).unwrap();
            let expected = payload.clone();
            let writer = tokio::spawn(async move {
                client.write_all(&header[..2]).await.unwrap();
                tokio::task::yield_now().await;
                client.write_all(&header[2..]).await.unwrap();
                client.write_all(&payload[..7]).await.unwrap();
                tokio::task::yield_now().await;
                client.write_all(&payload[7..]).await.unwrap();
            });
            let got = read_frame_async(&mut server, MAX_REQUEST_BYTES)
                .await
                .unwrap();
            writer.await.unwrap();
            assert_eq!(got, expected);
        });
    }

    #[test]
    fn framed_reader_rejects_oversize_before_payload_allocation() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let (mut client, mut server) = tokio::io::duplex(16);
            let header = ((MAX_REQUEST_BYTES + 1) as u32).to_be_bytes();
            client.write_all(&header).await.unwrap();
            assert!(read_frame_async(&mut server, MAX_REQUEST_BYTES)
                .await
                .is_err());
        });
    }
}
