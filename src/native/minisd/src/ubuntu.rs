use crate::layout::{
    ensure_host_layout, ensure_rootfs_layout, is_provisioned, read_os_release, rootfs_looks_valid,
    GUEST_HOME, GUEST_UID, HOST_ROOTFS, UBUNTU_PID_FILE, UBUNTU_PROXY_PID_FILE, UBUNTU_ROOTFS_FILE,
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
        .unwrap_or(GUEST_HOME)
        .to_string();
    if !cwd.starts_with('/') || cwd.contains('\0') || cwd.split('/').any(|p| p == "..") {
        return Err(ErrorCode::BadParams);
    }
    let env = parse_env_map(params.get("env"))?;
    Ok(UbuntuExec {
        argv,
        timeout_ms,
        cwd,
        env,
    })
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
    if state.mock {
        state.ubuntu.running = true;
        state.ubuntu.rootfs = HOST_ROOTFS.to_string();
        state.ubuntu.version = Some("24.04-mock".into());
        state.ubuntu.provisioned = true;
        return Ok(json!({"running": true, "mock": true, "rootfs": HOST_ROOTFS}));
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
fn validate_host_path(p: &str, require_minis: bool) -> Result<(), String> {
    if p.is_empty() {
        return Ok(()); // 空 = 调用方按默认值处理，不校验
    }
    if !p.starts_with('/') {
        return Err(format!("path must be absolute: {p}"));
    }
    if p.split('/').any(|c| c == "..") {
        return Err(format!("path must not contain ..: {p}"));
    }
    if require_minis && p != "/data/adb/minis" && !p.starts_with("/data/adb/minis/") {
        return Err(format!("path must be under /data/adb/minis: {p}"));
    }
    Ok(())
}

#[cfg(unix)]
fn start_live(state: &mut AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    refresh_live(state);
    let rootfs = params
        .get("rootfs")
        .and_then(|v| v.as_str())
        .unwrap_or(HOST_ROOTFS)
        .to_string();
    let workspace = params
        .get("workspace")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let memory = params
        .get("memory")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let skills = params
        .get("skills")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let shared = params
        .get("shared")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    for (p, require_minis) in [
        (&rootfs, true),
        (&workspace, false),
        (&memory, false),
        (&skills, false),
        (&shared, false),
    ] {
        validate_host_path(p, require_minis).map_err(|e| (ErrorCode::BadParams, e))?;
    }
    if state.ubuntu.running {
        let exe = std::env::current_exe().ok();
        let proxy_ok = exe.as_ref().map(|p| spawn_netproxy(p)).unwrap_or(false);
        write_apt_proxy(&state.ubuntu.rootfs_or_default());
        return Ok(json!({
            "running": true,
            "already": true,
            "pid": state.ubuntu.pid,
            "rootfs": state.ubuntu.rootfs,
            "version": state.ubuntu.version,
            "provisioned": state.ubuntu.provisioned,
            "proxy": crate::proxy::PROXY_URI,
            "proxy_ok": proxy_ok
        }));
    }
    ensure_host_layout().map_err(|e| (ErrorCode::Internal, e))?;
    if !rootfs_looks_valid(&rootfs) {
        return Err((
            ErrorCode::RuntimeUnavailable,
            format!("rootfs not installed at {rootfs}"),
        ));
    }
    ensure_rootfs_layout(&rootfs).map_err(|e| (ErrorCode::Internal, e))?;
    let (guid, ggid) = guest_ids(state);
    crate::layout::ensure_guest_user(&rootfs).map_err(|e| (ErrorCode::Internal, e))?;
    crate::layout::ensure_guest_user_ids(&rootfs, guid, ggid)
        .map_err(|e| (ErrorCode::Internal, e))?;
    let dns = crate::env::discover_dns();
    let resolv =
        crate::env::write_resolv_conf(&rootfs, &dns).map_err(|e| (ErrorCode::Internal, e))?;
    chown_tree_best_effort(guid, ggid, &workspace, &memory, &skills, &shared);

    let exe =
        std::env::current_exe().map_err(|e| (ErrorCode::Internal, format!("current_exe: {e}")))?;
    let mut child = std::process::Command::new(&exe)
        .args(["--helper", "keep", "--rootfs", &rootfs])
        .args(["--workspace", &workspace])
        .args(["--memory", &memory])
        .args(["--skills", &skills])
        .args(["--shared", &shared])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| (ErrorCode::Internal, format!("spawn keeper: {e}")))?;
    let pid = child.id() as i32;
    let ready = read_ready_line(&mut child, Duration::from_secs(8));
    match ready {
        Ok(_) => {
            let _ = std::fs::write(UBUNTU_PID_FILE, format!("{pid}\n"));
            let _ = std::fs::write(UBUNTU_ROOTFS_FILE, format!("{rootfs}\n"));
            state.ubuntu.running = true;
            state.ubuntu.pid = Some(pid);
            state.ubuntu.rootfs = rootfs.clone();
            state.ubuntu.version = read_os_release(&rootfs);
            state.ubuntu.provisioned = is_provisioned(&rootfs);
            state.ubuntu.last_error = None;
            std::thread::spawn(move || {
                let _ = child.wait();
            });
            let proxy_ok = spawn_netproxy(&exe);
            write_apt_proxy(&rootfs);
            Ok(json!({
                "running": true,
                "already": false,
                "pid": pid,
                "rootfs": rootfs,
                "version": state.ubuntu.version,
                "provisioned": state.ubuntu.provisioned,
                "resolv": resolv.trim(),
                "proxy": crate::proxy::PROXY_URI,
                "proxy_ok": proxy_ok,
                "via": "unshare+mount2+chroot"
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
        unsafe {
            libc::kill(pid, libc::SIGTERM);
        }
        for _ in 0..20 {
            if !pid_alive(pid) {
                break;
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
    let _ = std::fs::remove_file(UBUNTU_PID_FILE);
    // 身份校验：cmdline 不符（PID 复用）→ 不 kill，防止误杀无关进程
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
fn spawn_netproxy(exe: &std::path::Path) -> bool {
    if let Some(pid) = read_proxy_pid() {
        if pid_alive(pid) {
            return true;
        }
    }
    match std::process::Command::new(exe)
        .args([
            "--helper",
            "netproxy",
            "--listen",
            crate::proxy::PROXY_LISTEN,
        ])
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
    {
        Ok(child) => {
            let pid = child.id() as i32;
            let _ = std::fs::write(UBUNTU_PROXY_PID_FILE, format!("{pid}\n"));
            std::thread::spawn(move || {
                let _ = child.wait_with_output();
            });
            true
        }
        Err(_) => false,
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
    };
    let upd = exec_live(state, &update, true)?;
    if upd.get("exit_code").and_then(|v| v.as_i64()) != Some(0) {
        return Err((ErrorCode::Internal, format!("apt-get update failed: {upd}")));
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
    let pid = state
        .ubuntu
        .pid
        .ok_or((ErrorCode::RuntimeUnavailable, "no keeper pid".into()))?;
    let rootfs = state.ubuntu.rootfs_or_default();
    let (guid, ggid) = guest_ids(state);
    let uid = if admin { 0 } else { guid };
    let gid = if admin { 0 } else { ggid };
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
            // 有界回收：最多等 1s；孙进程逃逸持管道时放弃 join（线程泄漏可接受，进程重启即回收）
            // std::process 的管道在 Unix 上默认 O_CLOEXEC（rust std 保证），此处 join 有界即兜底
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
            if state.ubuntu.rootfs.is_empty() {
                state.ubuntu.rootfs = std::fs::read_to_string(UBUNTU_ROOTFS_FILE)
                    .ok()
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .unwrap_or_else(|| HOST_ROOTFS.to_string());
            }
            if state.ubuntu.version.is_none() {
                state.ubuntu.version = read_os_release(&state.ubuntu.rootfs_or_default());
            }
            state.ubuntu.provisioned = is_provisioned(&state.ubuntu.rootfs_or_default());
            return;
        }
    }
    if state.ubuntu.running {
        state.ubuntu.last_error = Some("keeper process gone".into());
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
fn is_keeper(pid: i32) -> bool {
    let raw = std::fs::read(format!("/proc/{pid}/cmdline")).unwrap_or_default();
    let s = String::from_utf8_lossy(&raw);
    s.contains("--helper") && s.contains("keep")
}

#[cfg(unix)]
fn is_netproxy(pid: i32) -> bool {
    let raw = std::fs::read(format!("/proc/{pid}/cmdline")).unwrap_or_default();
    let s = String::from_utf8_lossy(&raw);
    s.contains("--helper") && s.contains("netproxy")
}

#[cfg(unix)]
fn chown_tree_best_effort(
    uid: u32,
    gid: u32,
    workspace: &str,
    memory: &str,
    skills: &str,
    shared: &str,
) {
    use crate::layout::{HOST_MEMORY, HOST_SHARED, HOST_SKILLS, HOST_WORKSPACE};
    let workspace = if workspace.is_empty() {
        HOST_WORKSPACE
    } else {
        workspace
    };
    let memory = if memory.is_empty() {
        HOST_MEMORY
    } else {
        memory
    };
    let skills = if skills.is_empty() {
        HOST_SKILLS
    } else {
        skills
    };
    let shared = if shared.is_empty() {
        HOST_SHARED
    } else {
        shared
    };
    for path in [workspace, memory, skills, shared] {
        let c = match std::ffi::CString::new(path) {
            Ok(c) => c,
            Err(_) => continue,
        };
        unsafe {
            libc::chown(c.as_ptr(), uid, gid);
        }
    }
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
        ..Default::default()
    };
    #[cfg(unix)]
    {
        if let Some(pid) = read_pidfile() {
            if pid_alive(pid) && is_keeper(pid) {
                u.running = true;
                u.pid = Some(pid);
                u.rootfs = std::fs::read_to_string(UBUNTU_ROOTFS_FILE)
                    .ok()
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .unwrap_or_else(|| HOST_ROOTFS.to_string());
                u.version = read_os_release(&u.rootfs);
                u.provisioned = is_provisioned(&u.rootfs);
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
        let ok = parse_ubuntu_exec(&json!({"argv":["/usr/bin/id"],"cwd":"/workspace"})).unwrap();
        assert_eq!(ok.argv[0], "/usr/bin/id");
        assert_eq!(ok.cwd, "/workspace");
    }

    #[test]
    fn mock_provision() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let out = provision(&mut state).unwrap();
        assert_eq!(out["provisioned"], true);
        assert!(status(&mut state)["provisioned"].as_bool().unwrap());
    }

    #[test]
    fn mock_start_exec_stop() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        assert!(start(&mut state, &json!({})).is_ok());
        let st = status(&mut state);
        assert_eq!(st["running"], true);
        let out = exec(&mut state, &json!({"argv":["/usr/bin/id"]}), false).unwrap();
        assert_eq!(out["exit_code"], 0);
        assert!(out["stdout"].as_str().unwrap().contains("10000"));
        assert!(stop(&mut state).is_ok());
        assert_eq!(status(&mut state)["running"], false);
    }

    #[cfg(unix)]
    #[test]
    fn validate_host_path_guards() {
        assert!(validate_host_path("", true).is_ok());
        assert!(validate_host_path("relative", false).is_err());
        assert!(validate_host_path("/data/adb/minis/../etc", false).is_err());
        assert!(validate_host_path("/data/adb/minis", true).is_ok());
        assert!(validate_host_path("/data/adb/minis/rootfs", true).is_ok());
        assert!(validate_host_path("/data/adb/minis2", true).is_err());
        assert!(validate_host_path("/sdcard/x", false).is_ok());
    }
}
