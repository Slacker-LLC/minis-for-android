use minisd::policy::PolicyFile;
use minisd::protocol::{encode_response, MAX_REQUEST_BYTES};
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
    socket: PathBuf,
    #[cfg_attr(not(unix), allow(dead_code))]
    app_socket: Option<PathBuf>,
    policy: Option<PathBuf>,
}

fn parse_args() -> Result<Args, String> {
    let mut mock = false;
    let mut once = false;
    let mut call = false;
    let mut watchdog = false;
    let mut socket = PathBuf::from("/data/adb/minis/run/minisd.sock");
    let mut app_socket = None;
    let mut policy = None;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--mock" => mock = true,
            "--once" => once = true,
            "--call" => call = true,
            "--watchdog" => watchdog = true,
            "--socket" => {
                socket = PathBuf::from(it.next().ok_or("--socket needs a path")?);
            }
            "--app-socket" => {
                app_socket = Some(PathBuf::from(it.next().ok_or("--app-socket needs a path")?));
            }
            "--policy" => {
                policy = Some(PathBuf::from(it.next().ok_or("--policy needs a path")?));
            }
            "--help" | "-h" => {
                eprintln!(
                    "minisd [--mock] [--once] [--call] [--watchdog] [--socket PATH] [--app-socket PATH] [--policy PATH]"
                );
                eprintln!("minisd --helper keep --rootfs PATH");
                eprintln!("minisd --helper exec --pid N --rootfs PATH --uid N --gid N --cwd PATH -- ARGV");
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
    })
}

fn load_policy(path: &Option<PathBuf>) -> Result<PolicyFile, String> {
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
    let policy = match load_policy(&args.policy) {
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
    if args.watchdog {
        return watchdog_loop(args.mock, args.socket, args.app_socket, args.policy);
    }
    if args.once {
        return once_stdio(&mut state);
    }
    #[cfg(unix)]
    {
        return unix_server(state, args.socket, args.app_socket);
    }
    #[cfg(not(unix))]
    {
        eprintln!("socket server requires unix; use --once");
        ExitCode::from(2)
    }
}

fn watchdog_loop(mock: bool, socket: PathBuf, app_socket: Option<PathBuf>, policy: Option<PathBuf>) -> ExitCode {
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(e) => {
            eprintln!("current_exe: {e}");
            return ExitCode::from(1);
        }
    };
    loop {
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
        match cmd.status() {
            Ok(st) => eprintln!("minisd child exited {st}; restart in 1s"),
            Err(e) => eprintln!("minisd spawn: {e}"),
        }
        std::thread::sleep(std::time::Duration::from_secs(1));
    }
}

#[cfg(unix)]
fn unix_call(socket: &PathBuf) -> ExitCode {
    use std::os::unix::net::UnixStream;
    let mut buf = Vec::new();
    if let Err(e) = std::io::stdin().read_to_end(&mut buf) {
        eprintln!("stdin: {e}");
        return ExitCode::from(1);
    }
    if buf.len() > MAX_REQUEST_BYTES {
        return ExitCode::from(1);
    }
    let mut stream = match UnixStream::connect(socket) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("connect {}: {e}", socket.display());
            return ExitCode::from(1);
        }
    };
    if let Err(e) = stream.write_all(&buf) {
        eprintln!("write: {e}");
        return ExitCode::from(1);
    }
    let mut out = String::new();
    if let Err(e) = stream.read_to_string(&mut out) {
        eprintln!("read: {e}");
        return ExitCode::from(1);
    }
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
fn acquire_pidfile_lock(socket: &PathBuf) -> Option<std::fs::File> {
    use std::io::Write;
    use std::os::fd::AsRawFd;
    let pid_path = socket.with_extension("pid");
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .read(true)
        .write(true)
        .open(&pid_path)
        .ok()?;
    let fd = f.as_raw_fd();
    if unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        // EWOULDBLOCK/EAGAIN: another minisd holds the lock.
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
fn unix_server(state: AppState, socket: PathBuf, app_socket: Option<PathBuf>) -> ExitCode {
    use minisd::auth::read_peer;
    use std::os::unix::io::AsRawFd;
    use std::sync::Arc;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::sync::Mutex;

    if let Some(parent) = socket.parent() {
        let _ = fs::create_dir_all(parent);
    }
    enable_subreaper();
    // Hold the pidfile flock until process exit (fd closed on termination
    // releases it), so watchdog and a manual start cannot run two brokers
    // on the same socket.
    let lock = match acquire_pidfile_lock(&socket) {
        Some(f) => f,
        None => {
            eprintln!("minisd already running (pidfile locked)");
            return ExitCode::from(4);
        }
    };
    let _ = lock;
    let _ = fs::remove_file(&socket);
    let rt = match tokio::runtime::Builder::new_current_thread()
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
                    eprintln!("minisd app-socket {}", p.display());
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
        eprintln!("minisd listen {}", socket.display());
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
            serve_client(stream, &state).await;
        }
    })
}

#[cfg(unix)]
fn bind_sock(path: &PathBuf, mode: u32) -> Result<tokio::net::UnixListener, String> {
    use std::os::unix::fs::PermissionsExt;
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::remove_file(path);
    let listener = tokio::net::UnixListener::bind(path).map_err(|e| format!("bind {}: {e}", path.display()))?;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(mode));
    Ok(listener)
}

#[cfg(unix)]
async fn serve_client(mut stream: tokio::net::UnixStream, state: &std::sync::Arc<tokio::sync::Mutex<AppState>>) {
    use minisd::auth::read_peer;
    use std::os::unix::io::AsRawFd;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let fd = stream.as_raw_fd();
    let peer = read_peer(fd);
    let mut buf = vec![0u8; MAX_REQUEST_BYTES + 1];
    let read = tokio::time::timeout(
        std::time::Duration::from_millis(minisd::protocol::REQUEST_TIMEOUT_MS),
        stream.read(&mut buf),
    )
    .await;
    let n = match read {
        Ok(Ok(n)) => n,
        _ => return,
    };
    if n == 0 || n > MAX_REQUEST_BYTES {
        return;
    }
    let resp = {
        let mut st = state.lock().await;
        match parse_request(&buf[..n]) {
            Ok(req) => handle(&mut st, req, peer),
            Err(resp) => resp,
        }
    };
    if let Ok(s) = encode_response(&resp) {
        let _ = stream.write_all(s.as_bytes()).await;
        let _ = stream.write_all(b"\n").await;
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
    let mut pid: i32 = 0;
    let mut uid = minisd::layout::GUEST_UID;
    let mut gid = minisd::layout::GUEST_GID;
    let mut cwd = minisd::layout::GUEST_HOME.to_string();
    let mut listen = minisd::proxy::PROXY_LISTEN.to_string();
    let mut tz = "LCL-8".to_string();
    let mut proxy = String::new();
    let mut extra_env: std::collections::BTreeMap<String, String> = std::collections::BTreeMap::new();
    let mut guest_argv: Vec<String> = Vec::new();
    let mut i = 0usize;
    while i < args.len() {
        match args[i].as_str() {
            "keep" | "exec" | "netproxy" => {
                kind = args[i].as_str();
                i += 1;
            }
            "--listen" => {
                listen = args.get(i + 1).ok_or((8u8, "--listen needs value".into()))?.clone();
                i += 2;
            }
            "--workspace" => {
                workspace = args.get(i + 1).ok_or((8u8, "--workspace needs value".into()))?.clone();
                i += 2;
            }
            "--memory" => {
                memory = args.get(i + 1).ok_or((8u8, "--memory needs value".into()))?.clone();
                i += 2;
            }
            "--skills" => {
                skills = args.get(i + 1).ok_or((8u8, "--skills needs value".into()))?.clone();
                i += 2;
            }
            "--shared" => {
                shared = args.get(i + 1).ok_or((8u8, "--shared needs value".into()))?.clone();
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
                let (k, v) = kv.split_once('=').ok_or((8u8, "--env needs KEY=VAL".into()))?;
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
        "exec" => helper_exec(pid, &rootfs, uid, gid, &cwd, &tz, &proxy, &extra_env, &guest_argv),
        "netproxy" => minisd::proxy::run_forever(&listen).map_err(|e| (1u8, e)),
        _ => Err((8, "helper kind must be keep|exec|netproxy".into())),
    }
}

#[cfg(unix)]
fn helper_keep(rootfs: &str, workspace: &str, memory: &str, skills: &str, shared: &str) -> Result<(), (u8, String)> {
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
fn helper_exec(
    pid: i32,
    rootfs: &str,
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
    ns::chroot_to(rootfs).map_err(|e| (5u8, e))?;
    if std::env::set_current_dir(cwd).is_err() {
        let _ = std::env::set_current_dir("/");
    }
    if uid == 0 {
        // admin path: entering keeper ns must not keep privileges (05 §4)
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
