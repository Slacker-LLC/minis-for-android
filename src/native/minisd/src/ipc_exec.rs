use crate::protocol::ErrorCode;
use crate::state::AppState;
use crate::ubuntu::parse_ubuntu_exec;
use serde_json::{json, Value};
use std::io::Read;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const MAX_CAPTURE_BYTES: usize = 256 * 1024;
const CAPTURE_SETTLE_MS: u64 = 100;

#[derive(Debug, Clone)]
pub struct UbuntuExecSnapshot {
    pub pid: i32,
    pub rootfs: String,
    pub guest_uid: u32,
    pub guest_gid: u32,
}

/// Refresh Ubuntu state under the caller's short AppState lock and copy only
/// immutable execution inputs. The actual helper process is launched later,
/// after the global broker state lock has been released.
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
    Ok(UbuntuExecSnapshot {
        pid,
        rootfs,
        guest_uid,
        guest_gid,
    })
}

#[derive(Debug, Clone, Default)]
struct CapturedOutput {
    retained: Vec<u8>,
    total_bytes: u64,
    truncated: bool,
}

fn collect_bounded_shared<R: Read>(
    mut reader: R,
    limit: usize,
    capture: Arc<Mutex<CapturedOutput>>,
) -> std::io::Result<()> {
    let mut buf = [0u8; 8192];
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        let mut out = capture.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        out.total_bytes = out.total_bytes.saturating_add(n as u64);
        if out.retained.len() < limit {
            let keep = (limit - out.retained.len()).min(n);
            out.retained.extend_from_slice(&buf[..keep]);
        }
        out.truncated = out.total_bytes > out.retained.len() as u64;
    }
    Ok(())
}

fn capture_snapshot(capture: &Arc<Mutex<CapturedOutput>>) -> CapturedOutput {
    capture
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}

/// Wait for the foreground helper, while continuously draining stdout/stderr.
///
/// A shell may intentionally leave a background service running (for example
/// `python3 -m http.server 1455 &`). That service inherits the shell's output
/// descriptors. Waiting for collector EOF after the foreground shell exits
/// therefore blocks the RPC forever even though the command itself completed.
///
/// Collectors are allowed a short bounded settle window after foreground exit.
/// If a descendant still owns either pipe, the collectors remain detached and
/// keep draining it so the service neither blocks on a full pipe nor gets a
/// broken pipe. The RPC can then return and the host can open localhost while
/// the service is still alive.
fn wait_child_with_capture(
    mut child: std::process::Child,
    timeout: Duration,
) -> Result<(i32, CapturedOutput, CapturedOutput), (ErrorCode, String)> {
    let pid = child.id() as i32;
    let stdout = child
        .stdout
        .take()
        .ok_or((ErrorCode::Internal, "missing exec stdout".into()))?;
    let stderr = child
        .stderr
        .take()
        .ok_or((ErrorCode::Internal, "missing exec stderr".into()))?;

    let stdout_capture = Arc::new(Mutex::new(CapturedOutput::default()));
    let stderr_capture = Arc::new(Mutex::new(CapturedOutput::default()));
    let stdout_state = Arc::clone(&stdout_capture);
    let stderr_state = Arc::clone(&stderr_capture);
    let stdout_handle =
        std::thread::spawn(move || collect_bounded_shared(stdout, MAX_CAPTURE_BYTES, stdout_state));
    let stderr_handle =
        std::thread::spawn(move || collect_bounded_shared(stderr, MAX_CAPTURE_BYTES, stderr_state));

    let start = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let settle_deadline = Instant::now() + Duration::from_millis(CAPTURE_SETTLE_MS);
                let mut last_totals = (u64::MAX, u64::MAX);
                let mut stable_samples = 0u8;
                while Instant::now() < settle_deadline {
                    if stdout_handle.is_finished() && stderr_handle.is_finished() {
                        break;
                    }
                    let totals = (
                        capture_snapshot(&stdout_capture).total_bytes,
                        capture_snapshot(&stderr_capture).total_bytes,
                    );
                    if totals == last_totals {
                        stable_samples = stable_samples.saturating_add(1);
                        if stable_samples >= 2 {
                            break;
                        }
                    } else {
                        last_totals = totals;
                        stable_samples = 0;
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }

                // Joining is safe only when the reader has actually reached
                // EOF. Otherwise dropping the JoinHandle intentionally detaches
                // the collector so a surviving background service stays live.
                if stdout_handle.is_finished() {
                    let _ = stdout_handle.join();
                }
                if stderr_handle.is_finished() {
                    let _ = stderr_handle.join();
                }

                return Ok((
                    status.code().unwrap_or(255),
                    capture_snapshot(&stdout_capture),
                    capture_snapshot(&stderr_capture),
                ));
            }
            Ok(None) => {}
            Err(e) => {
                return Err((
                    ErrorCode::Internal,
                    format!("wait exec helper: {e}"),
                ));
            }
        }

        if start.elapsed() >= timeout {
            #[cfg(unix)]
            unsafe {
                libc::kill(pid, libc::SIGKILL);
                libc::kill(-pid, libc::SIGKILL);
            }
            let _ = child.wait();
            return Err((
                ErrorCode::Timeout,
                format!("ubuntu exec exceeded {}ms", timeout.as_millis()),
            ));
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// Run ubuntu.exec / ubuntu.adminExec using a previously authorized and
/// snapshotted keeper context. This function does not touch AppState.
pub fn execute_ubuntu_snapshot(
    snapshot: UbuntuExecSnapshot,
    params: Value,
    admin: bool,
) -> Result<Value, (ErrorCode, String)> {
    let req =
        parse_ubuntu_exec(&params).map_err(|code| (code, "bad ubuntu exec params".to_string()))?;
    let uid = if admin { 0 } else { snapshot.guest_uid };
    let gid = if admin { 0 } else { snapshot.guest_gid };
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
    for (k, v) in &req.env {
        cmd.arg("--env").arg(format!("{k}={v}"));
    }
    cmd.arg("--");
    cmd.args(&req.argv);
    cmd.stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());

    let child = cmd
        .spawn()
        .map_err(|e| (ErrorCode::Internal, format!("spawn exec helper: {e}")))?;
    let (exit_code, stdout, stderr) =
        wait_child_with_capture(child, Duration::from_millis(req.timeout_ms))?;

    Ok(json!({
        "exit_code": exit_code,
        "stdout": String::from_utf8_lossy(&stdout.retained).into_owned(),
        "stderr": String::from_utf8_lossy(&stderr.retained).into_owned(),
        "stdout_bytes": stdout.total_bytes,
        "stderr_bytes": stderr.total_bytes,
        "stdout_truncated": stdout.truncated,
        "stderr_truncated": stderr.truncated,
        "uid": uid,
        "admin": admin
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn bounded_collector_drains_but_retains_prefix_only() {
        let input = vec![b'x'; MAX_CAPTURE_BYTES + 8192];
        let capture = Arc::new(Mutex::new(CapturedOutput::default()));
        collect_bounded_shared(Cursor::new(input), MAX_CAPTURE_BYTES, Arc::clone(&capture)).unwrap();
        let out = capture_snapshot(&capture);
        assert_eq!(out.retained.len(), MAX_CAPTURE_BYTES);
        assert_eq!(out.total_bytes, (MAX_CAPTURE_BYTES + 8192) as u64);
        assert!(out.truncated);
    }

    #[cfg(unix)]
    #[test]
    fn background_descendant_does_not_hold_exec_response_open() {
        let mut cmd = std::process::Command::new("/bin/sh");
        cmd.args(["-c", "sleep 1 & printf ready"])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        let child = cmd.spawn().unwrap();
        let start = Instant::now();
        let (exit, stdout, _) =
            wait_child_with_capture(child, Duration::from_millis(500)).unwrap();
        assert_eq!(exit, 0);
        assert_eq!(String::from_utf8_lossy(&stdout.retained), "ready");
        assert!(start.elapsed() < Duration::from_millis(500));
    }
}
