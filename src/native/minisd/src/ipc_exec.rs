use crate::exec_registry::ExecGuard;
use crate::protocol::ErrorCode;
use crate::state::AppState;
use crate::ubuntu::parse_ubuntu_exec;
use serde_json::{json, Value};
use std::io::Read;
use std::time::{Duration, Instant};

const MAX_CAPTURE_BYTES: usize = 256 * 1024;

#[derive(Debug, Clone)]
pub struct UbuntuExecSnapshot {
    pub pid: i32,
    pub rootfs: String,
    pub sessions_root: String,
    pub guest_uid: u32,
    pub guest_gid: u32,
}

pub fn snapshot_ubuntu_exec(
    state: &mut AppState,
) -> Result<UbuntuExecSnapshot, (ErrorCode, String)> {
    let status = crate::ubuntu::status(state);
    if status.get("mock").and_then(Value::as_bool) == Some(true) {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "mock execution should use in-state dispatcher".into(),
        ));
    }
    if status.get("running").and_then(Value::as_bool) != Some(true) {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu runtime not started".into(),
        ));
    }
    let pid = status
        .get("pid")
        .and_then(Value::as_i64)
        .and_then(|n| i32::try_from(n).ok())
        .ok_or((
            ErrorCode::RuntimeUnavailable,
            "ubuntu keeper pid unavailable".into(),
        ))?;
    let rootfs = status
        .get("rootfs")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or((
            ErrorCode::RuntimeUnavailable,
            "ubuntu rootfs unavailable".into(),
        ))?
        .to_string();
    let guest_uid = status
        .get("uid")
        .and_then(Value::as_u64)
        .and_then(|n| u32::try_from(n).ok())
        .ok_or((ErrorCode::Internal, "ubuntu guest uid unavailable".into()))?;
    let guest_gid = status
        .get("gid")
        .and_then(Value::as_u64)
        .and_then(|n| u32::try_from(n).ok())
        .ok_or((ErrorCode::Internal, "ubuntu guest gid unavailable".into()))?;
    let sessions_root = status
        .get("sessions_root")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    Ok(UbuntuExecSnapshot {
        pid,
        rootfs,
        sessions_root,
        guest_uid,
        guest_gid,
    })
}

#[derive(Debug)]
struct CapturedOutput {
    retained: Vec<u8>,
    total_bytes: u64,
    truncated: bool,
}

fn collect_bounded<R: Read>(mut reader: R, limit: usize) -> std::io::Result<CapturedOutput> {
    let mut retained = Vec::with_capacity(limit.min(8192));
    let mut total_bytes = 0u64;
    let mut buf = [0u8; 8192];
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        total_bytes = total_bytes.saturating_add(n as u64);
        if retained.len() < limit {
            let keep = (limit - retained.len()).min(n);
            retained.extend_from_slice(&buf[..keep]);
        }
    }
    Ok(CapturedOutput {
        truncated: total_bytes > retained.len() as u64,
        retained,
        total_bytes,
    })
}

#[cfg(unix)]
fn kill_process_tree(pid: i32) {
    unsafe {
        // PID handles cancellation before helper setpgid; -PID handles every
        // descendant afterwards (including minis-mcp-cli/network helpers).
        libc::kill(pid, libc::SIGKILL);
        libc::kill(-pid, libc::SIGKILL);
    }
}

#[cfg(unix)]
pub fn execute_ubuntu_snapshot(
    snapshot: UbuntuExecSnapshot,
    params: Value,
    admin: bool,
) -> Result<Value, (ErrorCode, String)> {
    let req =
        parse_ubuntu_exec(&params).map_err(|code| (code, "bad ubuntu exec params".to_string()))?;
    let execution_id = params.get("execution_id").and_then(Value::as_str);
    let guard = ExecGuard::begin(execution_id)
        .map_err(|code| (code, "invalid or duplicate execution_id".into()))?;
    let uid = if admin { 0 } else { snapshot.guest_uid };
    let gid = if admin { 0 } else { snapshot.guest_gid };
    let exe =
        std::env::current_exe().map_err(|e| (ErrorCode::Internal, format!("current_exe: {e}")))?;
    let tz = crate::env::discover_tz();
    let proxy = crate::env::discover_proxy();
    let session_root = if admin {
        None
    } else {
        req.session_id
            .as_deref()
            .map(|session_id| {
                crate::ubuntu::prepare_session_root(&snapshot.sessions_root, session_id, uid, gid)
            })
            .transpose()?
    };

    let mut cmd = std::process::Command::new(&exe);
    cmd.args([
        "--helper",
        "exec",
        "--pid",
        &snapshot.pid.to_string(),
        "--rootfs",
        &snapshot.rootfs,
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
    if let Some(session_root) = &session_root {
        cmd.arg("--session-root").arg(session_root);
    }
    for (k, v) in &req.env {
        cmd.arg("--env").arg(format!("{k}={v}"));
    }
    cmd.arg("--");
    cmd.args(&req.argv);
    cmd.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());

    let mut child = cmd
        .spawn()
        .map_err(|e| (ErrorCode::Internal, format!("spawn exec helper: {e}")))?;
    let pid = child.id() as i32;
    if guard
        .activate(pid)
        .map_err(|code| (code, "execution registry activation failed".into()))?
    {
        #[cfg(unix)]
        kill_process_tree(pid);
    }
    let stdout = child
        .stdout
        .take()
        .ok_or((ErrorCode::Internal, "missing exec stdout".into()))?;
    let stderr = child
        .stderr
        .take()
        .ok_or((ErrorCode::Internal, "missing exec stderr".into()))?;
    let stdout_handle = std::thread::spawn(move || collect_bounded(stdout, MAX_CAPTURE_BYTES));
    let stderr_handle = std::thread::spawn(move || collect_bounded(stderr, MAX_CAPTURE_BYTES));
    let wait_handle = std::thread::spawn(move || child.wait());
    let start = Instant::now();

    loop {
        if wait_handle.is_finished() {
            let status = wait_handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join exec helper".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("wait exec helper: {e}")))?;
            let stdout = stdout_handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join stdout collector".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("read stdout: {e}")))?;
            let stderr = stderr_handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join stderr collector".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("read stderr: {e}")))?;
            if guard.was_cancelled() {
                return Err((
                    ErrorCode::UserCancelled,
                    "execution cancelled by caller".into(),
                ));
            }
            let Some(exit_code) = status.code() else {
                return Err((
                    ErrorCode::ProcessKilled,
                    "execution process was killed by a signal".into(),
                ));
            };
            return Ok(json!({
                "exit_code": exit_code,
                "stdout": String::from_utf8_lossy(&stdout.retained).into_owned(),
                "stderr": String::from_utf8_lossy(&stderr.retained).into_owned(),
                "stdout_bytes": stdout.total_bytes,
                "stderr_bytes": stderr.total_bytes,
                "stdout_truncated": stdout.truncated,
                "stderr_truncated": stderr.truncated,
                "uid": uid,
                "admin": admin,
                "session_id": req.session_id,
                "execution_id": execution_id
            }));
        }
        if start.elapsed() >= Duration::from_millis(req.timeout_ms) {
            #[cfg(unix)]
            kill_process_tree(pid);
            for _ in 0..50 {
                if wait_handle.is_finished() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            if wait_handle.is_finished() {
                let _ = wait_handle.join();
            }
            return Err((
                ErrorCode::ToolTimeout,
                format!("ubuntu exec exceeded {}ms", req.timeout_ms),
            ));
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn bounded_collector_drains_but_retains_prefix_only() {
        let input = vec![b'x'; MAX_CAPTURE_BYTES + 8192];
        let out = collect_bounded(Cursor::new(input), MAX_CAPTURE_BYTES).unwrap();
        assert_eq!(out.retained.len(), MAX_CAPTURE_BYTES);
        assert_eq!(out.total_bytes, (MAX_CAPTURE_BYTES + 8192) as u64);
        assert!(out.truncated);
    }
}
