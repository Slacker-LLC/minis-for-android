use crate::layout::{
    ensure_host_layout_for, ensure_rootfs_layout, is_provisioned, read_os_release,
    rootfs_looks_valid, validate_persistent_backing, DEFAULT_GUEST_CWD, GUEST_UID, HOST_HOME,
    HOST_MEMORY, HOST_ROOTFS, HOST_SESSIONS, HOST_SHARED, HOST_SKILLS, HOST_WORKSPACE,
    PERSISTENT_DATA_MODE, UBUNTU_PID_FILE, UBUNTU_PROXY_PID_FILE, UBUNTU_ROOTFS_FILE,
};
use crate::protocol::{ErrorCode, MAX_ARGS, MAX_ARG_BYTES};
use crate::state::AppState;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::Path;
use std::time::Duration;

const DEFAULT_EXEC_TIMEOUT_MS: u64 = 30_000;
const MAX_EXEC_TIMEOUT_MS: u64 = 600_000;
const MAX_CAPTURE: usize = 256 * 1024;

fn guest_ids(state: &AppState) -> (u32, u32) {
    let uid = if state.policy.caller.app_uid != 0 {
        state.policy.caller.app_uid
    } else {
        GUEST_UID
    };
    (uid, uid)
}

fn validate_fixed_start_param(params: &Value, key: &str, expected: &str) -> Result<(), String> {
    let Some(value) = params.get(key) else {
        return Ok(());
    };
    let value = value
        .as_str()
        .ok_or_else(|| format!("{key} must be a string when supplied"))?;
    if value.is_empty() || value == expected {
        return Ok(());
    }
    Err(format!(
        "{key} is fixed to {expected}; refusing non-persistent source {value}"
    ))
}

fn validate_persistent_start_params(params: &Value) -> Result<(), String> {
    for (key, expected) in [
        ("rootfs", HOST_ROOTFS),
        ("workspace", HOST_WORKSPACE),
        ("sessions_root", HOST_SESSIONS),
        ("memory", HOST_MEMORY),
        ("skills", HOST_SKILLS),
        ("shared", HOST_SHARED),
        ("home", HOST_HOME),
    ] {
        validate_fixed_start_param(params, key, expected)?;
    }
    Ok(())
}

pub const BASE_PACKAGES: &[&str] = &[
    "gawk",
    "python3",
    "python3-pip",
    "python3-venv",
    "git",
    "curl",
    "wget",
    "ca-certificates",
    "zip",
    "unzip",
    "xz-utils",
    "zstd",
];

#[derive(Debug, Clone)]
pub struct UbuntuExec {
    pub argv: Vec<String>,
    pub timeout_ms: u64,
    pub cwd: String,
    pub env: BTreeMap<String, String>,
    pub session_id: Option<String>,
}

pub fn parse_ubuntu_exec(params: &Value) -> Result<UbuntuExec, ErrorCode> {
    if params.get("cmd").is_some() || params.get("command").is_some() {
        return Err(ErrorCode::BadParams);
    }
    let items = params
        .get("argv")
        .and_then(|v| v.as_array())
        .ok_or(ErrorCode::BadParams)?;
    if items.is_empty() || items.len() > MAX_ARGS {
        return Err(ErrorCode::BadParams);
    }
    let mut argv = Vec::with_capacity(items.len());
    for item in items {
        let s = item.as_str().ok_or(ErrorCode::BadParams)?;
        if s.is_empty() || s.len() > MAX_ARG_BYTES || s.contains('\0') {
            return Err(ErrorCode::BadParams);
        }
        argv.push(s.to_string());
    }
    if !argv[0].starts_with('/') || argv[0].contains("..") {
        return Err(ErrorCode::BadParams);
    }
    let timeout_ms = params
        .get("timeout_ms")
        .and_then(|v| v.as_u64())
        .unwrap_or(DEFAULT_EXEC_TIMEOUT_MS)
        .min(MAX_EXEC_TIMEOUT_MS);
    let cwd = params
        .get("cwd")
        .and_then(|v| v.as_str())
        .unwrap_or(DEFAULT_GUEST_CWD)
        .to_string();
    if !cwd.starts_with('/') || cwd.contains('\0') || cwd.split('/').any(|p| p == "..") {
        return Err(ErrorCode::BadParams);
    }
    let env = parse_env_map(params.get("env"))?;
    let session_id = parse_session_id(params.get("session_id"))?;
    Ok(UbuntuExec {
        argv,
        timeout_ms,
        cwd,
        env,
        session_id,
    })
}

fn parse_session_id(value: Option<&Value>) -> Result<Option<String>, ErrorCode> {
    let Some(value) = value else {
        return Ok(None);
    };
    let session_id = value.as_str().ok_or(ErrorCode::BadParams)?;
    if session_id.is_empty()
        || session_id.len() > 128
        || matches!(session_id, "." | "..")
        || !session_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        return Err(ErrorCode::BadParams);
    }
    Ok(Some(session_id.to_string()))
}

fn parse_env_map(v: Option<&Value>) -> Result<BTreeMap<String, String>, ErrorCode> {
    let Some(v) = v else {
        return Ok(BTreeMap::new());
    };
    let obj = v.as_object().ok_or(ErrorCode::BadParams)?;
    let mut out = BTreeMap::new();
    for (k, val) in obj {
        if k.is_empty() || !k.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return Err(ErrorCode::BadParams);
        }
        let s = val.as_str().ok_or(ErrorCode::BadParams)?;
        if s.contains('\0') || s.len() > MAX_ARG_BYTES {
            return Err(ErrorCode::BadParams);
        }
        out.insert(k.clone(), s.to_string());
    }
    Ok(out)
}

pub fn start(state: &mut AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    validate_persistent_start_params(params).map_err(|detail| (ErrorCode::BadParams, detail))?;
    if state.mock {
        state.ubuntu.running = true;
        state.ubuntu.rootfs = HOST_ROOTFS.to_string();
        state.ubuntu.sessions_root = HOST_SESSIONS.to_string();
        state.ubuntu.version = Some("24.04-mock".into());
        state.ubuntu.provisioned = true;
        return Ok(json!({
            "running": true,
            "mock": true,
            "rootfs": HOST_ROOTFS,
            "sessions_root": HOST_SESSIONS,
        }));
    }
    #[cfg(not(unix))]
    {
        let _ = params;
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime requires unix".into(),
        ));
    }
    #[cfg(unix)]
    {
        start_live(state, params)
    }
}

pub fn stop(state: &mut AppState) -> Result<Value, (ErrorCode, String)> {
    if state.mock {
        state.ubuntu.running = false;
        return Ok(json!({"running": false, "mock": true}));
    }
    #[cfg(not(unix))]
    {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime requires unix".into(),
        ));
    }
    #[cfg(unix)]
    {
        stop_live(state)
    }
}

pub fn status(state: &mut AppState) -> Value {
    #[cfg(unix)]
    if !state.mock {
        refresh_live(state);
    }
    json!({
        "running": state.ubuntu.running,
        "available": state.mock || state.ubuntu.running || rootfs_looks_valid(&state.ubuntu.rootfs_or_default()),
        "pid": state.ubuntu.pid,
        "rootfs": state.ubuntu.rootfs_or_default(),
        "sessions_root": state.ubuntu.sessions_root,
        "version": state.ubuntu.version,
        "provisioned": state.ubuntu.provisioned,
        "uid": guest_ids(state).0,
        "gid": guest_ids(state).1,
        "last_error": state.ubuntu.last_error,
        "mock": state.mock
    })
}

pub fn exec(
    state: &mut AppState,
    params: &Value,
    admin: bool,
) -> Result<Value, (ErrorCode, String)> {
    let parsed = parse_ubuntu_exec(params).map_err(|c| (c, "bad ubuntu exec params".into()))?;
    if state.mock {
        if !state.ubuntu.running {
            return Err((
                ErrorCode::RuntimeUnavailable,
                "ubuntu runtime unavailable".into(),
            ));
        }
        return Ok(json!({
            "exit_code": 0,
            "stdout": if admin { "mock-admin" } else { "uid=10000(minis) gid=10000(minis)" },
            "stderr": "",
            "argv": parsed.argv
        }));
    }
    #[cfg(not(unix))]
    {
        let _ = admin;
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime requires unix".into(),
        ));
    }
    #[cfg(unix)]
    {
        exec_live(state, &parsed, admin)
    }
}

pub fn provision(state: &mut AppState) -> Result<Value, (ErrorCode, String)> {
    if state.mock {
        if !state.ubuntu.running {
            let _ = start(state, &json!({}));
        }
        state.ubuntu.provisioned = true;
        return Ok(
            json!({"provisioned": true, "already": false, "mock": true, "packages": BASE_PACKAGES}),
        );
    }
    #[cfg(not(unix))]
    {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime requires unix".into(),
        ));
    }
    #[cfg(unix)]
    {
        provision_live(state)
    }
}

#[cfg(unix)]
pub fn prepare_session_root(
    sessions_root: &str,
    session_id: &str,
    uid: u32,
    gid: u32,
) -> Result<String, (ErrorCode, String)> {
    if sessions_root != HOST_SESSIONS {
        return Err((
            ErrorCode::BadParams,
            format!("sessions_root is fixed to {HOST_SESSIONS}; refusing source {sessions_root}"),
        ));
    }
    prepare_session_root_at(Path::new(HOST_SESSIONS), session_id, uid, gid)
}

#[cfg(unix)]
fn prepare_session_root_at(
    root: &Path,
    session_id: &str,
    uid: u32,
    gid: u32,
) -> Result<String, (ErrorCode, String)> {
    parse_session_id(Some(&Value::String(session_id.to_string())))
        .map_err(|code| (code, "invalid session_id".to_string()))?;

    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    let root = std::fs::canonicalize(root).map_err(|e| {
        (
            ErrorCode::RuntimeUnavailable,
            format!(
                "session workspace root unavailable at {}: {e}",
                root.display()
            ),
        )
    })?;
    let root_meta = std::fs::metadata(&root).map_err(|e| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("stat session workspace root {}: {e}", root.display()),
        )
    })?;
    let root_mode = root_meta.permissions().mode() & 0o777;
    if !root_meta.is_dir()
        || root_meta.uid() != uid
        || root_meta.gid() != gid
        || root_mode != PERSISTENT_DATA_MODE
    {
        return Err((
            ErrorCode::NotAuthorized,
            format!(
                "session workspace root must be {uid}:{gid} {:o} (path={}, owner={}:{}, mode={:o})",
                PERSISTENT_DATA_MODE,
                root.display(),
                root_meta.uid(),
                root_meta.gid(),
                root_mode
            ),
        ));
    }

    let session = root.join(session_id);
    if session
        .symlink_metadata()
        .is_ok_and(|meta| meta.file_type().is_symlink())
    {
        return Err((
            ErrorCode::NotAuthorized,
            format!("session root must not be a symlink: {}", session.display()),
        ));
    }
    std::fs::create_dir_all(&session).map_err(|e| {
        (
            ErrorCode::Internal,
            format!("create session workspace {}: {e}", session.display()),
        )
    })?;
    let canonical_session = std::fs::canonicalize(&session).map_err(|e| {
        (
            ErrorCode::Internal,
            format!("canonicalize session workspace {}: {e}", session.display()),
        )
    })?;
    if !canonical_session.starts_with(&root) || canonical_session == root {
        return Err((
            ErrorCode::NotAuthorized,
            "session workspace escapes the configured sessions root".into(),
        ));
    }
    set_session_owner_mode(&canonical_session, uid, gid)?;

    for subdir in ["workspace", "attachments", "offloads", "browser"] {
        let path = canonical_session.join(subdir);
        if path
            .symlink_metadata()
            .is_ok_and(|meta| meta.file_type().is_symlink())
        {
            return Err((
                ErrorCode::NotAuthorized,
                format!("session mount must not be a symlink: {}", path.display()),
            ));
        }
        std::fs::create_dir_all(&path).map_err(|e| {
            (
                ErrorCode::Internal,
                format!("create session mount {}: {e}", path.display()),
            )
        })?;
        let canonical = std::fs::canonicalize(&path).map_err(|e| {
            (
                ErrorCode::Internal,
                format!("canonicalize session mount {}: {e}", path.display()),
            )
        })?;
        if !canonical.starts_with(&canonical_session) {
            return Err((
                ErrorCode::NotAuthorized,
                format!("session mount escapes its session root: {}", path.display()),
            ));
        }
        set_session_owner_mode(&canonical, uid, gid)?;
    }
    Ok(canonical_session.to_string_lossy().into_owned())
}

#[cfg(unix)]
fn set_session_owner_mode(path: &Path, uid: u32, gid: u32) -> Result<(), (ErrorCode, String)> {
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::{MetadataExt, PermissionsExt};

    let c_path = std::ffi::CString::new(path.as_os_str().as_bytes()).map_err(|_| {
        (
            ErrorCode::Internal,
            format!("NUL in session path: {}", path.display()),
        )
    })?;
    if unsafe { libc::chown(c_path.as_ptr(), uid, gid) } != 0 {
        return Err((
            ErrorCode::Internal,
            format!(
                "chown session path {} to {uid}:{gid}: {}",
                path.display(),
                std::io::Error::last_os_error()
            ),
        ));
    }
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(PERSISTENT_DATA_MODE)).map_err(
        |e| {
            (
                ErrorCode::Internal,
                format!(
                    "chmod {:o} session path {}: {e}",
                    PERSISTENT_DATA_MODE,
                    path.display()
                ),
            )
        },
    )?;
    let meta = std::fs::metadata(path).map_err(|e| {
        (
            ErrorCode::Internal,
            format!("stat session path {}: {e}", path.display()),
        )
    })?;
    let mode = meta.permissions().mode() & 0o777;
    if meta.uid() != uid || meta.gid() != gid || mode != PERSISTENT_DATA_MODE {
        return Err((
            ErrorCode::Internal,
            format!(
                "session path ownership/mode mismatch at {}: got {}:{} {:o}, expected {uid}:{gid} {:o}",
                path.display(),
                meta.uid(),
                meta.gid(),
                mode,
                PERSISTENT_DATA_MODE
            ),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn start_live(state: &mut AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    validate_persistent_start_params(params).map_err(|detail| (ErrorCode::BadParams, detail))?;
    refresh_live(state);
    if state.ubuntu.running {
        validate_persistent_backing().map_err(|e| (ErrorCode::RuntimeUnavailable, e))?;
        state.ubuntu.sessions_root = HOST_SESSIONS.to_string();
        let exe = std::env::current_exe().ok();
        let proxy_ok = exe.as_ref().map(|p| spawn_netproxy(p)).unwrap_or(false);
        write_apt_proxy(HOST_ROOTFS);
        return Ok(json!({
            "running": true,
            "already": true,
            "pid": state.ubuntu.pid,
            "rootfs": HOST_ROOTFS,
            "sessions_root": HOST_SESSIONS,
            "version": state.ubuntu.version,
            "provisioned": state.ubuntu.provisioned,
            "proxy": crate::proxy::PROXY_URI,
            "proxy_ok": proxy_ok
        }));
    }

    retire_stale_keeper();
    let (guid, ggid) = guest_ids(state);
    ensure_host_layout_for(guid, ggid).map_err(|e| (ErrorCode::Internal, e))?;
    validate_persistent_backing().map_err(|e| (ErrorCode::RuntimeUnavailable, e))?;
    if !rootfs_looks_valid(HOST_ROOTFS) {
        return Err((
            ErrorCode::RuntimeUnavailable,
            format!("rootfs not installed at {HOST_ROOTFS}"),
        ));
    }
    ensure_rootfs_layout(HOST_ROOTFS).map_err(|e| (ErrorCode::Internal, e))?;
    crate::layout::ensure_guest_user_ids(HOST_ROOTFS, guid, ggid)
        .map_err(|e| (ErrorCode::Internal, e))?;
    let dns = crate::env::discover_dns();
    let resolv =
        crate::env::write_resolv_conf(HOST_ROOTFS, &dns).map_err(|e| (ErrorCode::Internal, e))?;

    let exe =
        std::env::current_exe().map_err(|e| (ErrorCode::Internal, format!("current_exe: {e}")))?;
    let mut child = std::process::Command::new(&exe)
        .args(["--helper", "keep", "--rootfs", HOST_ROOTFS])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| (ErrorCode::Internal, format!("spawn keeper: {e}")))?;
    let pid = child.id() as i32;
    let ready = read_ready_line(&mut child, Duration::from_secs(8));
    match ready {
        Ok(_) => {
            std::fs::write(UBUNTU_PID_FILE, format!("{pid}\n"))
                .map_err(|e| (ErrorCode::Internal, format!("write ubuntu pid: {e}")))?;
            std::fs::write(UBUNTU_ROOTFS_FILE, format!("{HOST_ROOTFS}\n"))
                .map_err(|e| (ErrorCode::Internal, format!("write ubuntu rootfs: {e}")))?;
            state.ubuntu.running = true;
            state.ubuntu.pid = Some(pid);
            state.ubuntu.rootfs = HOST_ROOTFS.to_string();
            state.ubuntu.sessions_root = HOST_SESSIONS.to_string();
            state.ubuntu.version = read_os_release(HOST_ROOTFS);
            state.ubuntu.provisioned = is_provisioned(HOST_ROOTFS);
            state.ubuntu.last_error = None;
            std::thread::spawn(move || {
                let _ = child.wait();
            });
            let proxy_ok = spawn_netproxy(&exe);
            write_apt_proxy(HOST_ROOTFS);
            Ok(json!({
                "running": true,
                "already": false,
                "pid": pid,
                "rootfs": HOST_ROOTFS,
                "sessions_root": HOST_SESSIONS,
                "version": state.ubuntu.version,
                "provisioned": state.ubuntu.provisioned,
                "resolv": resolv.trim(),
                "proxy": crate::proxy::PROXY_URI,
                "proxy_ok": proxy_ok,
                "via": "unshare+mount2+chroot",
                "persistent_root": crate::layout::HOST_MINIS
            }))
        }
        Err(e) => {
            let _ = child.kill();
            let err_out = child
                .wait_with_output()
                .ok()
                .map(|o| String::from_utf8_lossy(&o.stderr).into_owned())
                .unwrap_or_default();
            state.ubuntu.running = false;
            state.ubuntu.pid = None;
            state.ubuntu.last_error = Some(format!("{e} {err_out}"));
            Err((
                ErrorCode::RuntimeUnavailable,
                format!("ubuntu keep failed: {e} {err_out}"),
            ))
        }
    }
}

#[cfg(unix)]
fn read_ready_line(child: &mut std::process::Child, timeout: Duration) -> Result<String, String> {
    use std::io::{BufRead, BufReader};
    let stdout = child.stdout.take().ok_or("no stdout")?;
    let handle = std::thread::spawn(move || {
        let mut line = String::new();
        BufReader::new(stdout)
            .read_line(&mut line)
            .map(|_| line)
            .map_err(|e| e.to_string())
    });
    let start = std::time::Instant::now();
    while start.elapsed() < timeout {
        if handle.is_finished() {
            let line = handle.join().map_err(|_| "join ready".to_string())??;
            let line = line.trim().to_string();
            if line.starts_with("READY") {
                return Ok(line);
            }
            return Err(if line.is_empty() {
                "keeper exited before READY".into()
            } else {
                line
            });
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    Err("timeout waiting for READY".into())
}

#[cfg(unix)]
fn stop_live(state: &mut AppState) -> Result<Value, (ErrorCode, String)> {
    refresh_live(state);
    if let Some(pid) = state.ubuntu.pid {
        terminate_keeper(pid);
    }
    let _ = std::fs::remove_file(UBUNTU_PID_FILE);
    if let Some(ppid) = read_proxy_pid().filter(|p| is_netproxy(*p)) {
        unsafe {
            libc::kill(ppid, libc::SIGTERM);
            libc::kill(ppid, libc::SIGKILL);
        }
    }
    let _ = std::fs::remove_file(UBUNTU_PROXY_PID_FILE);
    state.ubuntu.running = false;
    state.ubuntu.pid = None;
    Ok(json!({"running": false}))
}

#[cfg(unix)]
fn terminate_keeper(pid: i32) {
    unsafe {
        libc::kill(pid, libc::SIGTERM);
    }
    for _ in 0..20 {
        if !pid_alive(pid) {
            return;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    if pid_alive(pid) {
        unsafe {
            libc::kill(pid, libc::SIGKILL);
            libc::kill(-pid, libc::SIGKILL);
        }
    }
}

#[cfg(unix)]
fn retire_stale_keeper() {
    let Some(pid) = read_pidfile() else {
        return;
    };
    if pid_alive(pid) && is_any_keeper(pid) && !is_keeper(pid) {
        terminate_keeper(pid);
    }
    if !pid_alive(pid) || !is_keeper(pid) {
        let _ = std::fs::remove_file(UBUNTU_PID_FILE);
    }
}

#[cfg(unix)]
fn spawn_netproxy(exe: &std::path::Path) -> bool {
    if let Some(pid) = read_proxy_pid() {
        if pid_alive(pid) && is_netproxy(pid) && proxy_listener_ready() {
            return true;
        }
        if pid_alive(pid) && is_netproxy(pid) {
            unsafe {
                libc::kill(pid, libc::SIGTERM);
            }
        }
        let _ = std::fs::remove_file(UBUNTU_PROXY_PID_FILE);
    }

    let log = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open("/data/adb/minis/log/ubuntu-proxy.log")
        .ok();
    let stdout = log
        .as_ref()
        .and_then(|file| file.try_clone().ok())
        .map(std::process::Stdio::from)
        .unwrap_or_else(std::process::Stdio::null);
    let stderr = log
        .and_then(|file| file.try_clone().ok())
        .map(std::process::Stdio::from)
        .unwrap_or_else(std::process::Stdio::null);

    match std::process::Command::new(exe)
        .args([
            "--helper",
            "netproxy",
            "--listen",
            crate::proxy::PROXY_LISTEN,
        ])
        .stdin(std::process::Stdio::null())
        .stdout(stdout)
        .stderr(stderr)
        .spawn()
    {
        Ok(mut child) => {
            let pid = child.id() as i32;
            let deadline = std::time::Instant::now() + Duration::from_secs(2);
            while std::time::Instant::now() < deadline {
                match child.try_wait() {
                    Ok(Some(_)) | Err(_) => {
                        let _ = std::fs::remove_file(UBUNTU_PROXY_PID_FILE);
                        return false;
                    }
                    Ok(None) if proxy_listener_ready() => {
                        if write_proxy_pid(pid).is_err() {
                            let _ = child.kill();
                            let _ = child.wait();
                            return false;
                        }
                        std::thread::spawn(move || {
                            let _ = child.wait();
                            if read_proxy_pid() == Some(pid) {
                                let _ = std::fs::remove_file(UBUNTU_PROXY_PID_FILE);
                            }
                        });
                        return true;
                    }
                    Ok(None) => std::thread::sleep(Duration::from_millis(25)),
                }
            }
            let _ = child.kill();
            let _ = child.wait();
            let _ = std::fs::remove_file(UBUNTU_PROXY_PID_FILE);
            false
        }
        Err(_) => false,
    }
}

#[cfg(unix)]
fn proxy_listener_ready() -> bool {
    let Ok(addr) = crate::proxy::PROXY_LISTEN.parse() else {
        return false;
    };
    std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(150)).is_ok()
}

#[cfg(unix)]
fn write_proxy_pid(pid: i32) -> std::io::Result<()> {
    let tmp = format!("{UBUNTU_PROXY_PID_FILE}.tmp.{}", std::process::id());
    std::fs::write(&tmp, format!("{pid}\n"))?;
    match std::fs::rename(&tmp, UBUNTU_PROXY_PID_FILE) {
        Ok(()) => Ok(()),
        Err(error) => {
            let _ = std::fs::remove_file(&tmp);
            Err(error)
        }
    }
}

#[cfg(unix)]
fn read_proxy_pid() -> Option<i32> {
    std::fs::read_to_string(UBUNTU_PROXY_PID_FILE)
        .ok()?
        .trim()
        .parse()
        .ok()
}

fn write_apt_proxy(rootfs: &str) {
    let dir = Path::new(rootfs).join("etc/apt/apt.conf.d");
    let _ = std::fs::create_dir_all(&dir);
    let body = format!(
        "Acquire::http::Proxy \"{}\";\nAcquire::https::Proxy \"{}\";\n",
        crate::proxy::PROXY_URI,
        crate::proxy::PROXY_URI
    );
    let _ = std::fs::write(dir.join("99minis-proxy"), body);
}

#[cfg(unix)]
fn provision_live(state: &mut AppState) -> Result<Value, (ErrorCode, String)> {
    if !state.ubuntu.running {
        start_live(state, &json!({}))?;
    }
    let rootfs = state.ubuntu.rootfs_or_default();
    if is_provisioned(&rootfs) {
        state.ubuntu.provisioned = true;
        return Ok(json!({"provisioned": true, "already": true, "packages": BASE_PACKAGES}));
    }
    let update = UbuntuExec {
        argv: vec!["/usr/bin/apt-get".into(), "update".into()],
        timeout_ms: 180_000,
        cwd: "/".into(),
        env: apt_env(),
        session_id: None,
    };
    let upd = exec_live(state, &update, true)?;
    if upd.get("exit_code").and_then(|v| v.as_i64()) != Some(0) {
        return Err((
            ErrorCode::Internal,
            format!("apt-get update failed: {upd}"),
        ));
    }
    let mut argv = vec![
        "/usr/bin/apt-get".into(),
        "install".into(),
        "-y".into(),
        "--no-install-recommends".into(),
    ];
    argv.extend(BASE_PACKAGES.iter().map(|s| (*s).to_string()));
    let install = UbuntuExec {
        argv,
        timeout_ms: 600_000,
        cwd: "/".into(),
        env: apt_env(),
        session_id: None,
    };
    let ins = exec_live(state, &install, true)?;
    if ins.get("exit_code").and_then(|v| v.as_i64()) != Some(0) {
        return Err((
            ErrorCode::Internal,
            format!("apt-get install failed: {ins}"),
        ));
    }
    let marker = Path::new(&rootfs).join(crate::layout::PROVISION_MARKER);
    if let Some(parent) = marker.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    std::fs::write(&marker, "ok\n")
        .map_err(|e| (ErrorCode::Internal, format!("write provisioned: {e}")))?;
    state.ubuntu.provisioned = true;
    Ok(json!({
        "provisioned": true,
        "already": false,
        "packages": BASE_PACKAGES,
        "update": upd.get("exit_code"),
        "install": ins.get("exit_code")
    }))
}

#[cfg(unix)]
fn apt_env() -> BTreeMap<String, String> {
    let mut env = BTreeMap::new();
    env.insert("DEBIAN_FRONTEND".into(), "noninteractive".into());
    env
}

#[cfg(unix)]
fn exec_live(
    state: &mut AppState,
    req: &UbuntuExec,
    admin: bool,
) -> Result<Value, (ErrorCode, String)> {
    refresh_live(state);
    if !state.ubuntu.running {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime not started".into(),
        ));
    }
    validate_persistent_backing().map_err(|e| (ErrorCode::RuntimeUnavailable, e))?;
    let pid = state
        .ubuntu
        .pid
        .ok_or((ErrorCode::RuntimeUnavailable, "no keeper pid".into()))?;
    let rootfs = state.ubuntu.rootfs_or_default();
    let (guid, ggid) = guest_ids(state);
    let uid = if admin { 0 } else { guid };
    let gid = if admin { 0 } else { ggid };
    let session_root = match req.session_id.as_deref() {
        Some(session_id) => prepare_session_root(HOST_SESSIONS, session_id, guid, ggid)?,
        None => String::new(),
    };
    let exe =
        std::env::current_exe().map_err(|e| (ErrorCode::Internal, format!("current_exe: {e}")))?;
    let tz = crate::env::discover_tz();
    let proxy = if admin {
        crate::env::discover_proxy()
    } else {
        crate::proxy::PROXY_URI.to_string()
    };
    let mut cmd = std::process::Command::new(&exe);
    cmd.args([
        "--helper",
        "exec",
        "--pid",
        &pid.to_string(),
        "--rootfs",
        &rootfs,
        "--uid",
        &uid.to_string(),
        "--gid",
        &gid.to_string(),
        "--cwd",
        &req.cwd,
        "--tz",
        &tz,
        "--proxy",
        &proxy,
    ]);
    if !session_root.is_empty() {
        cmd.arg("--session-root").arg(&session_root);
    }
    for (k, v) in &req.env {
        cmd.arg("--env").arg(format!("{k}={v}"));
    }
    cmd.arg("--");
    cmd.args(&req.argv);
    cmd.stdin(std::process::Stdio::null());
    let output = wait_output_timeout(cmd, Duration::from_millis(req.timeout_ms))?;
    Ok(json!({
        "exit_code": output.0,
        "stdout": truncate(&output.1),
        "stderr": truncate(&output.2),
        "uid": uid,
        "admin": admin
    }))
}

#[cfg(unix)]
fn wait_output_timeout(
    mut cmd: std::process::Command,
    timeout: Duration,
) -> Result<(i32, String, String), (ErrorCode, String)> {
    let child = cmd
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| (ErrorCode::Internal, format!("spawn exec helper: {e}")))?;
    let pid = child.id() as i32;
    let handle = std::thread::spawn(move || child.wait_with_output());
    let start = std::time::Instant::now();
    loop {
        if handle.is_finished() {
            let out = handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join exec helper".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("wait exec helper: {e}")))?;
            let code = out.status.code().unwrap_or(255);
            return Ok((
                code,
                String::from_utf8_lossy(&out.stdout).into_owned(),
                String::from_utf8_lossy(&out.stderr).into_owned(),
            ));
        }
        if start.elapsed() >= timeout {
            unsafe {
                libc::kill(pid, libc::SIGKILL);
                libc::kill(-pid, libc::SIGKILL);
            }
            for _ in 0..50 {
                if handle.is_finished() {
                    let _ = handle.join();
                    break;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            return Err((
                ErrorCode::Timeout,
                format!("ubuntu exec exceeded {}ms", timeout.as_millis()),
            ));
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(unix)]
fn refresh_live(state: &mut AppState) {
    if let Some(pid) = state.ubuntu.pid.or_else(read_pidfile) {
        if pid_alive(pid) && is_keeper(pid) {
            state.ubuntu.running = true;
            state.ubuntu.pid = Some(pid);
            state.ubuntu.rootfs = HOST_ROOTFS.to_string();
            state.ubuntu.sessions_root = HOST_SESSIONS.to_string();
            if state.ubuntu.version.is_none() {
                state.ubuntu.version = read_os_release(HOST_ROOTFS);
            }
            state.ubuntu.provisioned = is_provisioned(HOST_ROOTFS);
            return;
        }
    }
    if state.ubuntu.running {
        state.ubuntu.last_error =
            Some("keeper process gone or persistent layout identity mismatch".into());
    }
    state.ubuntu.running = false;
    state.ubuntu.pid = None;
}

#[cfg(unix)]
fn read_pidfile() -> Option<i32> {
    std::fs::read_to_string(UBUNTU_PID_FILE)
        .ok()?
        .trim()
        .parse()
        .ok()
}

#[cfg(unix)]
fn pid_alive(pid: i32) -> bool {
    Path::new(&format!("/proc/{pid}")).exists()
}

#[cfg(unix)]
fn read_cmdline(pid: i32) -> Vec<u8> {
    std::fs::read(format!("/proc/{pid}/cmdline")).unwrap_or_default()
}

fn command_args(raw: &[u8]) -> Vec<&[u8]> {
    raw.split(|byte| *byte == 0)
        .filter(|arg| !arg.is_empty())
        .collect()
}

fn minisd_basename(args: &[&[u8]]) -> bool {
    args.first()
        .is_some_and(|arg| arg.rsplit(|byte| *byte == b'/').next() == Some(b"minisd".as_slice()))
}

fn keeper_cmdline_matches(raw: &[u8]) -> bool {
    let args = command_args(raw);
    if !minisd_basename(&args) {
        return false;
    }
    let helper = args
        .windows(2)
        .any(|pair| pair[0] == b"--helper" && pair[1] == b"keep");
    let rootfs = args
        .windows(2)
        .any(|pair| pair[0] == b"--rootfs" && pair[1] == HOST_ROOTFS.as_bytes());
    let has_legacy_source_override = args.iter().any(|arg| {
        *arg == b"--workspace"
            || *arg == b"--memory"
            || *arg == b"--skills"
            || *arg == b"--shared"
            || *arg == b"--home"
    });
    helper && rootfs && !has_legacy_source_override
}

fn any_keeper_cmdline_matches(raw: &[u8]) -> bool {
    let args = command_args(raw);
    minisd_basename(&args)
        && args
            .windows(2)
            .any(|pair| pair[0] == b"--helper" && pair[1] == b"keep")
}

#[cfg(unix)]
fn is_keeper(pid: i32) -> bool {
    keeper_cmdline_matches(&read_cmdline(pid))
}

#[cfg(unix)]
fn is_any_keeper(pid: i32) -> bool {
    any_keeper_cmdline_matches(&read_cmdline(pid))
}

#[cfg(unix)]
fn is_netproxy(pid: i32) -> bool {
    let raw = read_cmdline(pid);
    netproxy_cmdline_matches(&raw)
}

fn netproxy_cmdline_matches(raw: &[u8]) -> bool {
    let args = command_args(raw);
    let is_minisd = minisd_basename(&args);
    let helper = args
        .windows(2)
        .any(|pair| pair[0] == b"--helper" && pair[1] == b"netproxy");
    let listener = args
        .windows(2)
        .any(|pair| pair[0] == b"--listen" && pair[1] == crate::proxy::PROXY_LISTEN.as_bytes());
    is_minisd && helper && listener
}

fn truncate(s: &str) -> String {
    if s.len() <= MAX_CAPTURE {
        s.to_string()
    } else {
        format!(
            "{}\n… truncated {} bytes",
            &s[..MAX_CAPTURE],
            s.len() - MAX_CAPTURE
        )
    }
}

pub fn recover_state() -> crate::state::UbuntuState {
    let mut u = crate::state::UbuntuState {
        rootfs: HOST_ROOTFS.to_string(),
        sessions_root: HOST_SESSIONS.to_string(),
        ..Default::default()
    };
    #[cfg(unix)]
    {
        if let Some(pid) = read_pidfile() {
            if pid_alive(pid) && is_keeper(pid) {
                u.running = true;
                u.pid = Some(pid);
                u.version = read_os_release(HOST_ROOTFS);
                u.provisioned = is_provisioned(HOST_ROOTFS);
            }
        } else if rootfs_looks_valid(HOST_ROOTFS) {
            u.version = read_os_release(HOST_ROOTFS);
            u.provisioned = is_provisioned(HOST_ROOTFS);
        }
    }
    u
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::PolicyFile;

    #[test]
    fn rejects_raw_cmd_and_relative_argv0() {
        assert!(parse_ubuntu_exec(&json!({"cmd":"id"})).is_err());
        assert!(parse_ubuntu_exec(&json!({"argv":["id"]})).is_err());
        assert!(parse_ubuntu_exec(&json!({"argv":["/usr/bin/../etc/shadow"]})).is_err());
        assert!(parse_ubuntu_exec(&json!({"argv":[]})).is_err());
        let ok = parse_ubuntu_exec(&json!({"argv":["/usr/bin/id"]})).unwrap();
        assert_eq!(ok.argv[0], "/usr/bin/id");
        assert_eq!(ok.cwd, "/workspace");
        let scoped =
            parse_ubuntu_exec(&json!({"argv":["/usr/bin/id"],"session_id":"__new__abc-123"}))
                .unwrap();
        assert_eq!(scoped.session_id.as_deref(), Some("__new__abc-123"));
        for invalid in ["", ".", "..", "../other", "a/b", "会话"] {
            assert!(
                parse_ubuntu_exec(&json!({"argv":["/usr/bin/id"],"session_id":invalid}),).is_err()
            );
        }
    }

    #[test]
    fn persistent_start_params_reject_app_files_and_allow_fixed_sources() {
        assert!(validate_persistent_start_params(&json!({})).is_ok());
        assert!(validate_persistent_start_params(&json!({
            "workspace": HOST_WORKSPACE,
            "sessions_root": HOST_SESSIONS,
            "memory": HOST_MEMORY,
            "skills": HOST_SKILLS,
            "shared": HOST_SHARED,
            "home": HOST_HOME,
            "rootfs": HOST_ROOTFS
        }))
        .is_ok());
        assert!(validate_persistent_start_params(&json!({
            "workspace": "/data/user/0/dev.openminispet.android/files/minis/workspace"
        }))
        .is_err());
        assert!(validate_persistent_start_params(&json!({
            "sessions_root": "/data/user/0/dev.openminispet.android/files/minis-sessions"
        }))
        .is_err());
        assert!(validate_persistent_start_params(&json!({"workspace": "/dev/shm/ws"})).is_err());
    }

    #[test]
    fn mock_provision() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let out = provision(&mut state).unwrap();
        assert_eq!(out["provisioned"], true);
        assert!(status(&mut state)["provisioned"].as_bool().unwrap());
    }

    #[test]
    fn mock_start_exec_stop_uses_fixed_sessions_root() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        assert!(start(&mut state, &json!({})).is_ok());
        let st = status(&mut state);
        assert_eq!(st["running"], true);
        assert_eq!(st["sessions_root"], HOST_SESSIONS);
        assert!(start(
            &mut state,
            &json!({"sessions_root":"/data/user/0/app/files/minis-sessions"}),
        )
        .is_err());
        let out = exec(&mut state, &json!({"argv":["/usr/bin/id"]}), false).unwrap();
        assert_eq!(out["exit_code"], 0);
        assert!(out["stdout"].as_str().unwrap().contains("10000"));
        assert!(stop(&mut state).is_ok());
        assert_eq!(status(&mut state)["running"], false);
    }

    #[test]
    fn keeper_identity_rejects_legacy_source_overrides() {
        assert!(keeper_cmdline_matches(
            b"/data/adb/minis/bin/minisd\0--helper\0keep\0--rootfs\0/data/adb/minis/rootfs\0"
        ));
        assert!(!keeper_cmdline_matches(
            b"/data/adb/minis/bin/minisd\0--helper\0keep\0--rootfs\0/data/adb/minis/rootfs\0--workspace\0/data/user/0/app/files/minis\0"
        ));
        assert!(!keeper_cmdline_matches(
            b"/data/adb/minis/bin/minisd\0--helper\0keep\0--rootfs\0/data/adb/minis/other-rootfs\0"
        ));
    }

    #[test]
    fn netproxy_pid_identity_requires_exact_helper_and_listener() {
        assert!(netproxy_cmdline_matches(
            b"/data/adb/minis/bin/minisd\0--helper\0netproxy\0--listen\x00127.0.0.1:18787\0"
        ));
        assert!(!netproxy_cmdline_matches(b"com.tencent.mobileqq\0"));
        assert!(!netproxy_cmdline_matches(
            b"/data/adb/minis/bin/minisd\0--helper\0keep\0--listen\x00127.0.0.1:18787\0"
        ));
        assert!(!netproxy_cmdline_matches(
            b"/data/adb/minis/bin/minisd\0--helper\0netproxy\0--listen\x00127.0.0.1:9999\0"
        ));
    }

    #[cfg(unix)]
    #[test]
    fn session_root_is_isolated_and_rejects_symlink_mounts() {
        use std::os::unix::fs::symlink;
        use std::os::unix::fs::PermissionsExt;

        let unique = format!(
            "minisd-sessions-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        let root = std::env::temp_dir().join(unique);
        std::fs::create_dir_all(&root).unwrap();
        std::fs::set_permissions(&root, std::fs::Permissions::from_mode(PERSISTENT_DATA_MODE))
            .unwrap();
        let uid = unsafe { libc::geteuid() };
        let gid = unsafe { libc::getegid() };

        let session = prepare_session_root_at(&root, "session-a", uid, gid).unwrap();
        for subdir in ["workspace", "attachments", "offloads", "browser"] {
            let path = Path::new(&session).join(subdir);
            assert!(path.is_dir());
            assert_eq!(
                std::fs::metadata(path).unwrap().permissions().mode() & 0o777,
                PERSISTENT_DATA_MODE
            );
        }
        let other = prepare_session_root_at(&root, "session-b", uid, gid).unwrap();
        assert_ne!(session, other);

        let escaped = root.join("escaped");
        std::fs::create_dir_all(&escaped).unwrap();
        set_session_owner_mode(&escaped, uid, gid).unwrap();
        let outside = std::env::temp_dir().join(format!("{session}-outside"));
        std::fs::create_dir_all(&outside).unwrap();
        symlink(&outside, escaped.join("workspace")).unwrap();
        assert!(prepare_session_root_at(&root, "escaped", uid, gid).is_err());

        let _ = std::fs::remove_dir_all(&root);
        let _ = std::fs::remove_dir_all(&outside);
    }

    #[test]
    fn fixed_session_api_rejects_noncanonical_root() {
        #[cfg(unix)]
        assert!(prepare_session_root("/tmp/minis-sessions", "a", 1, 1).is_err());
    }
}
