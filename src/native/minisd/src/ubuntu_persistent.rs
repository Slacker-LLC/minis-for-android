use crate::layout::{
    ensure_data_directory, ensure_host_layout_for, validate_persistent_backing, DEFAULT_GUEST_CWD,
    GUEST_UID, HOST_HOME, HOST_MEMORY, HOST_SESSIONS, HOST_SHARED, HOST_SKILLS, HOST_WORKSPACE,
};
use crate::protocol::ErrorCode;
use crate::state::{AppState, UbuntuState};
use serde_json::{json, Map, Value};
use std::path::Path;
use std::time::Duration;

#[cfg(unix)]
pub use crate::ubuntu_legacy::prepare_session_root;
pub use crate::ubuntu_legacy::{UbuntuExec, BASE_PACKAGES};

const MAX_CAPTURE: usize = 256 * 1024;

fn guest_ids(state: &AppState) -> (u32, u32) {
    let uid = if state.policy.caller.app_uid != 0 {
        state.policy.caller.app_uid
    } else {
        GUEST_UID
    };
    (uid, uid)
}

fn fixed_runtime_state(state: &AppState) -> bool {
    state.ubuntu.workspace == HOST_WORKSPACE
        && state.ubuntu.memory == HOST_MEMORY
        && state.ubuntu.skills == HOST_SKILLS
        && state.ubuntu.shared == HOST_SHARED
        && state.ubuntu.sessions_root == HOST_SESSIONS
}

fn mark_fixed_runtime_state(state: &mut AppState) {
    state.ubuntu.workspace = HOST_WORKSPACE.to_string();
    state.ubuntu.memory = HOST_MEMORY.to_string();
    state.ubuntu.skills = HOST_SKILLS.to_string();
    state.ubuntu.shared = HOST_SHARED.to_string();
    state.ubuntu.sessions_root = HOST_SESSIONS.to_string();
}

fn clear_runtime_bind_state(state: &mut AppState) {
    state.ubuntu.workspace.clear();
    state.ubuntu.memory.clear();
    state.ubuntu.skills.clear();
    state.ubuntu.shared.clear();
    state.ubuntu.sessions_root = HOST_SESSIONS.to_string();
}

fn expected_persistent_path(key: &str) -> Option<&'static str> {
    match key {
        "workspace" => Some(HOST_WORKSPACE),
        "sessions_root" => Some(HOST_SESSIONS),
        "memory" => Some(HOST_MEMORY),
        "skills" => Some(HOST_SKILLS),
        "shared" => Some(HOST_SHARED),
        "home" => Some(HOST_HOME),
        _ => None,
    }
}

fn validate_requested_persistent_paths(params: &Value) -> Result<(), (ErrorCode, String)> {
    let Some(object) = params.as_object() else {
        return Err((
            ErrorCode::BadParams,
            "ubuntu.start params must be an object".into(),
        ));
    };
    for key in [
        "workspace",
        "sessions_root",
        "memory",
        "skills",
        "shared",
        "home",
    ] {
        let Some(value) = object.get(key) else {
            continue;
        };
        let Some(path) = value.as_str() else {
            return Err((
                ErrorCode::BadParams,
                format!("{key} must be the fixed persistent path"),
            ));
        };
        let expected = expected_persistent_path(key).expect("known persistent key");
        if path != expected {
            return Err((
                ErrorCode::BadParams,
                format!(
                    "{key} must be {expected}; refusing alternate/App-filesDir persistent source {path}"
                ),
            ));
        }
    }
    Ok(())
}

fn normalized_start_params(params: &Value) -> Result<Value, (ErrorCode, String)> {
    validate_requested_persistent_paths(params)?;
    let mut object: Map<String, Value> = params.as_object().cloned().ok_or((
        ErrorCode::BadParams,
        "ubuntu.start params must be an object".into(),
    ))?;
    for (key, value) in [
        ("workspace", HOST_WORKSPACE),
        ("sessions_root", HOST_SESSIONS),
        ("memory", HOST_MEMORY),
        ("skills", HOST_SKILLS),
        ("shared", HOST_SHARED),
        ("home", HOST_HOME),
    ] {
        object.insert(key.to_string(), Value::String(value.to_string()));
    }
    Ok(Value::Object(object))
}

fn prepare_persistent_layout(state: &AppState) -> Result<(), (ErrorCode, String)> {
    if state.mock {
        return Ok(());
    }
    let (uid, gid) = guest_ids(state);
    ensure_host_layout_for(uid, gid).map_err(|e| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("persistent layout init failed: {e}"),
        )
    })?;
    validate_persistent_backing().map_err(|e| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("persistent layout validation failed: {e}"),
        )
    })
}

pub fn parse_ubuntu_exec(params: &Value) -> Result<UbuntuExec, ErrorCode> {
    let mut parsed = crate::ubuntu_legacy::parse_ubuntu_exec(params)?;
    if params.get("cwd").is_none() {
        parsed.cwd = DEFAULT_GUEST_CWD.to_string();
    }
    Ok(parsed)
}

pub fn start(state: &mut AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let normalized = normalized_start_params(params)?;

    if state.ubuntu.running && !fixed_runtime_state(state) {
        return Err((
            ErrorCode::RuntimeLayoutMismatch,
            "running keeper persistent layout is unknown or non-canonical; stop and restart required"
                .into(),
        ));
    }

    prepare_persistent_layout(state)?;

    #[cfg(unix)]
    let _home_bind = if !state.mock && !state.ubuntu.running {
        Some(stage_home_bind(&normalized)?)
    } else {
        None
    };

    let result = crate::ubuntu_legacy::start(state, &normalized);
    if result.is_ok() && state.ubuntu.running {
        mark_fixed_runtime_state(state);
    }
    result
}

pub fn stop(state: &mut AppState) -> Result<Value, (ErrorCode, String)> {
    let result = crate::ubuntu_legacy::stop(state);
    if result.is_ok() {
        clear_runtime_bind_state(state);
        state.external_mounts.clear();
        state.ubuntu.external_mount_digest = None;
        state.ubuntu.external_mount_verified = false;
    }
    result
}

pub fn status(state: &mut AppState) -> Value {
    let mut value = crate::ubuntu_legacy::status(state);
    if let Some(object) = value.as_object_mut() {
        object.insert(
            "persistent_layout_known".into(),
            Value::Bool(!state.ubuntu.running || fixed_runtime_state(state)),
        );
        object.insert("sessions_root".into(), Value::String(HOST_SESSIONS.into()));
        object.insert("home_source".into(), Value::String(HOST_HOME.into()));
    }
    value
}

pub fn exec(
    state: &mut AppState,
    params: &Value,
    admin: bool,
) -> Result<Value, (ErrorCode, String)> {
    let parsed = parse_ubuntu_exec(params).map_err(|c| (c, "bad ubuntu exec params".into()))?;
    if !state.mock && state.ubuntu.running && !state.ubuntu.external_mount_verified {
        return Err((
            ErrorCode::MountAttestationRequired,
            "external mount snapshot must be re-attested before guest exec".into(),
        ));
    }
    if state.mock {
        return crate::ubuntu_legacy::exec(state, params, admin);
    }

    #[cfg(not(unix))]
    {
        let _ = (state, parsed, admin);
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime requires unix".into(),
        ));
    }

    #[cfg(unix)]
    {
        exec_live_persistent(state, &parsed, admin)
    }
}

pub fn provision(state: &mut AppState) -> Result<Value, (ErrorCode, String)> {
    if state.mock {
        return crate::ubuntu_legacy::provision(state);
    }

    let _ = crate::ubuntu_legacy::status(state);
    if state.ubuntu.running && !fixed_runtime_state(state) {
        return Err((
            ErrorCode::RuntimeLayoutMismatch,
            "running keeper persistent layout is unknown or non-canonical; stop and restart required"
                .into(),
        ));
    }
    if !state.ubuntu.running {
        start(state, &json!({}))?;
    }
    crate::ubuntu_legacy::provision(state)
}

pub fn recover_state() -> UbuntuState {
    let mut state = crate::ubuntu_legacy::recover_state();
    // A recovered keeper predates this process, so HOME bind provenance cannot
    // be proven. Keep the bind-state fields unknown and force an explicit
    // stop/start before any Agent exec. The on-disk layout itself is unchanged.
    state.sessions_root = HOST_SESSIONS.to_string();
    state.workspace.clear();
    state.memory.clear();
    state.skills.clear();
    state.shared.clear();
    state
}

#[cfg(unix)]
pub fn replace_keeper_with_external_mounts(
    state: &mut AppState,
    specs_json: &str,
    app_uid: u32,
) -> Result<(), (ErrorCode, String)> {
    crate::ubuntu_legacy::replace_keeper_with_external_mounts(state, specs_json, app_uid)
}

#[cfg(not(unix))]
pub fn replace_keeper_with_external_mounts(
    _state: &mut AppState,
    _specs_json: &str,
    _app_uid: u32,
) -> Result<(), (ErrorCode, String)> {
    Err((
        ErrorCode::RuntimeUnavailable,
        "external mounts require unix".into(),
    ))
}

#[cfg(unix)]
struct StagedHomeBind {
    target: String,
}

#[cfg(unix)]
impl Drop for StagedHomeBind {
    fn drop(&mut self) {
        if let Ok(target) = std::ffi::CString::new(self.target.as_str()) {
            unsafe {
                libc::umount2(target.as_ptr(), libc::MNT_DETACH);
            }
        }
    }
}

#[cfg(unix)]
fn stage_home_bind(params: &Value) -> Result<StagedHomeBind, (ErrorCode, String)> {
    let rootfs = params
        .get("rootfs")
        .and_then(Value::as_str)
        .unwrap_or(crate::layout::HOST_ROOTFS);
    let target = Path::new(rootfs).join("home/minis");
    std::fs::create_dir_all(&target).map_err(|e| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("prepare guest HOME mountpoint {}: {e}", target.display()),
        )
    })?;
    let target = target.to_string_lossy().into_owned();

    // Clear only a stale staging mount in minisd's host namespace. A live
    // keeper has its own private mount namespace and is unaffected.
    if let Ok(c_target) = std::ffi::CString::new(target.as_str()) {
        let rc = unsafe { libc::umount2(c_target.as_ptr(), libc::MNT_DETACH) };
        if rc != 0 {
            let error = std::io::Error::last_os_error();
            match error.raw_os_error() {
                Some(libc::EINVAL) | Some(libc::ENOENT) => {}
                _ => {
                    return Err((
                        ErrorCode::RuntimeUnavailable,
                        format!("clear stale HOME staging mount {target}: {error}"),
                    ));
                }
            }
        }
    }

    crate::ns::bind_mount(HOST_HOME, &target, true).map_err(|e| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("bind persistent HOME {HOST_HOME} -> {target}: {e}"),
        )
    })?;
    Ok(StagedHomeBind { target })
}

#[cfg(unix)]
fn exec_live_persistent(
    state: &mut AppState,
    req: &UbuntuExec,
    admin: bool,
) -> Result<Value, (ErrorCode, String)> {
    let _ = crate::ubuntu_legacy::status(state);
    if !state.ubuntu.running {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime not started".into(),
        ));
    }
    if !fixed_runtime_state(state) {
        return Err((
            ErrorCode::RuntimeLayoutMismatch,
            "keeper persistent layout is unknown or non-canonical; stop and restart required"
                .into(),
        ));
    }

    prepare_persistent_layout(state)?;

    let pid = state
        .ubuntu
        .pid
        .ok_or((ErrorCode::RuntimeUnavailable, "no keeper pid".into()))?;
    let rootfs = state.ubuntu.rootfs_or_default();
    let (guest_uid, guest_gid) = guest_ids(state);
    let uid = if admin { 0 } else { guest_uid };
    let gid = if admin { 0 } else { guest_gid };

    let session_root = if let Some(session_id) = req.session_id.as_deref() {
        let root = crate::ubuntu_legacy::prepare_session_root(
            HOST_SESSIONS,
            session_id,
            guest_uid,
            guest_gid,
        )?;
        enforce_session_modes(Path::new(&root), guest_uid, guest_gid)?;
        Some(root)
    } else {
        None
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
    ]);
    if let Some(session_root) = session_root.as_deref() {
        cmd.args(["--session-root", session_root]);
    }
    cmd.args([
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
    for (key, value) in &req.env {
        cmd.arg("--env").arg(format!("{key}={value}"));
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
        "admin": admin,
        "session_root": session_root,
    }))
}

#[cfg(unix)]
fn enforce_session_modes(
    session_root: &Path,
    uid: u32,
    gid: u32,
) -> Result<(), (ErrorCode, String)> {
    ensure_data_directory(session_root, uid, gid).map_err(|e| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("session layout: {e}"),
        )
    })?;
    for rel in ["workspace", "attachments", "offloads", "browser"] {
        ensure_data_directory(&session_root.join(rel), uid, gid).map_err(|e| {
            (
                ErrorCode::RuntimeUnavailable,
                format!("session layout: {e}"),
            )
        })?;
    }
    Ok(())
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
            let output = handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join exec helper".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("wait exec helper: {e}")))?;
            return Ok((
                output.status.code().unwrap_or(255),
                String::from_utf8_lossy(&output.stdout).into_owned(),
                String::from_utf8_lossy(&output.stderr).into_owned(),
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

fn truncate(value: &str) -> String {
    if value.len() <= MAX_CAPTURE {
        value.to_string()
    } else {
        format!(
            "{}\n… truncated {} bytes",
            &value[..MAX_CAPTURE],
            value.len() - MAX_CAPTURE
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::PolicyFile;

    #[test]
    fn alternate_app_filesdir_paths_are_rejected() {
        let error = normalized_start_params(&json!({
            "workspace": "/data/user/0/com.openminis.app/files/workspace",
            "sessions_root": "/data/user/0/com.openminis.app/files/sessions"
        }))
        .unwrap_err();
        assert_eq!(error.0, ErrorCode::BadParams);
        assert!(error.1.contains("App-filesDir"));
    }

    #[test]
    fn fixed_start_params_are_injected_when_omitted() {
        let value = normalized_start_params(&json!({})).unwrap();
        assert_eq!(value["workspace"], HOST_WORKSPACE);
        assert_eq!(value["sessions_root"], HOST_SESSIONS);
        assert_eq!(value["memory"], HOST_MEMORY);
        assert_eq!(value["skills"], HOST_SKILLS);
        assert_eq!(value["shared"], HOST_SHARED);
        assert_eq!(value["home"], HOST_HOME);
    }

    #[test]
    fn home_and_workspace_remain_distinct_in_exec_defaults() {
        let parsed = parse_ubuntu_exec(&json!({"argv":["/usr/bin/id"]})).unwrap();
        assert_eq!(parsed.cwd, "/workspace");
        assert_ne!(crate::layout::GUEST_HOME, parsed.cwd);
    }

    #[test]
    fn mock_start_uses_only_fixed_persistent_layout() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        start(&mut state, &json!({})).unwrap();
        assert!(fixed_runtime_state(&state));
        assert_eq!(state.ubuntu.sessions_root, HOST_SESSIONS);
        assert!(start(
            &mut state,
            &json!({"sessions_root":"/data/user/0/app/files/minis-sessions"}),
        )
        .is_err());
    }
}
