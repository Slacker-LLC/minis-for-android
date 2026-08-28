use crate::protocol::ErrorCode;
use crate::state::AppState;
use crate::ubuntu::parse_ubuntu_exec;
use serde_json::{json, Value};
use std::io::Read;
use std::time::{Duration, Instant};

#[cfg(unix)]
use std::ffi::CString;
#[cfg(unix)]
use std::os::unix::process::CommandExt;

const MAX_CAPTURE_BYTES: usize = 256 * 1024;

// Reserved errno values used only by our pre_exec closure. Linux execve(2)
// never returns these values, so Command::spawn can tell an infrastructure
// failure before execve apart from failure to execute the guest binary itself.
#[cfg(unix)]
const PREEXEC_ERR_SETNS: i32 = 240;
#[cfg(unix)]
const PREEXEC_ERR_CHROOT: i32 = 241;
#[cfg(unix)]
const PREEXEC_ERR_PRIVS: i32 = 242;
#[cfg(unix)]
const ANDROID_AID_INET: libc::gid_t = 3003;

#[derive(Debug, Clone)]
pub struct UbuntuExecSnapshot {
    pub pid: i32,
    pub rootfs: String,
    pub guest_uid: u32,
    pub guest_gid: u32,
}

/// Refresh Ubuntu state under the caller's short AppState lock and copy only
/// immutable execution inputs. The actual guest process is launched later,
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
fn staged_error(errno: i32) -> std::io::Error {
    std::io::Error::from_raw_os_error(errno)
}

#[cfg(unix)]
fn classify_spawn_error(error: std::io::Error, argv0: &str) -> (ErrorCode, String) {
    match error.raw_os_error() {
        Some(PREEXEC_ERR_SETNS) => (
            ErrorCode::KeeperNamespaceLost,
            "failed to enter keeper mount namespace before guest exec".into(),
        ),
        Some(PREEXEC_ERR_CHROOT) => (
            ErrorCode::ChrootUnavailable,
            "failed to enter Ubuntu rootfs before guest exec".into(),
        ),
        Some(PREEXEC_ERR_PRIVS) => (
            ErrorCode::GuestPrivilegeSetupFailed,
            "failed to establish guest uid/gid/capability boundary before exec".into(),
        ),
        // These are the normal execve/path failures. They happen after the
        // namespace/chroot/privilege boundary succeeded, but before user code
        // can run, so they are still safe pre-exec failures.
        Some(libc::ENOENT | libc::EACCES | libc::ENOEXEC | libc::ENOTDIR | libc::ELOOP | libc::ETXTBSY) => (
            ErrorCode::GuestExecveFailed,
            format!("execve {argv0}: {error}"),
        ),
        _ => (ErrorCode::Internal, format!("spawn guest {argv0}: {error}")),
    }
}

/// Enter the keeper mount namespace and guest root directly in Command's child
/// immediately before execve. Only raw libc operations are used here because
/// pre_exec runs after fork in a multi-threaded process.
#[cfg(unix)]
unsafe fn enter_guest_pre_exec(
    keeper_ns: &CString,
    rootfs: &CString,
    cwd: &CString,
    uid: u32,
    gid: u32,
) -> std::io::Result<()> {
    // Best effort: later timeout cleanup also kills the process group.
    libc::setpgid(0, 0);

    let ns_fd = libc::open(keeper_ns.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC);
    if ns_fd < 0 {
        return Err(staged_error(PREEXEC_ERR_SETNS));
    }
    let setns_rc = libc::setns(ns_fd, libc::CLONE_NEWNS);
    libc::close(ns_fd);
    if setns_rc != 0 {
        return Err(staged_error(PREEXEC_ERR_SETNS));
    }

    if libc::chroot(rootfs.as_ptr()) != 0 || libc::chdir(c"/".as_ptr()) != 0 {
        return Err(staged_error(PREEXEC_ERR_CHROOT));
    }
    // Preserve the previous helper behavior: a vanished/invalid cwd falls back
    // to guest root rather than turning an otherwise healthy runtime into a
    // hard failure.
    if libc::chdir(cwd.as_ptr()) != 0 {
        let _ = libc::chdir(c"/".as_ptr());
    }

    if uid == 0 {
        if libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 {
            return Err(staged_error(PREEXEC_ERR_PRIVS));
        }
        for cap in 0..=40 {
            libc::prctl(libc::PR_CAPBSET_DROP, cap, 0, 0, 0);
        }
        #[repr(C)]
        struct CapHeader {
            version: u32,
            pid: i32,
        }
        #[repr(C)]
        struct CapData {
            effective: u32,
            permitted: u32,
            inheritable: u32,
        }
        let header = CapHeader {
            version: 0x2008_0522,
            pid: 0,
        };
        let data = [
            CapData {
                effective: 0,
                permitted: 0,
                inheritable: 0,
            },
            CapData {
                effective: 0,
                permitted: 0,
                inheritable: 0,
            },
        ];
        if libc::syscall(libc::SYS_capset, &header as *const CapHeader, data.as_ptr()) != 0 {
            return Err(staged_error(PREEXEC_ERR_PRIVS));
        }
    } else {
        let groups = [ANDROID_AID_INET];
        if libc::setgroups(groups.len(), groups.as_ptr()) != 0
            || libc::setgid(gid) != 0
            || libc::setuid(uid) != 0
        {
            return Err(staged_error(PREEXEC_ERR_PRIVS));
        }
        let _ = libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    }
    Ok(())
}

/// Run ubuntu.exec / ubuntu.adminExec using a previously authorized and
/// snapshotted keeper context. Infrastructure failures before execve are
/// returned as structured RPC errors and can never collide with a user program's
/// numeric exit status.
pub fn execute_ubuntu_snapshot(
    snapshot: UbuntuExecSnapshot,
    params: Value,
    admin: bool,
) -> Result<Value, (ErrorCode, String)> {
    let req =
        parse_ubuntu_exec(&params).map_err(|code| (code, "bad ubuntu exec params".to_string()))?;
    let uid = if admin { 0 } else { snapshot.guest_uid };
    let gid = if admin { 0 } else { snapshot.guest_gid };
    let tz = crate::env::discover_tz();
    // The guest shares the phone network namespace and mirrors the Android
    // system proxy only when one is configured.
    let proxy = crate::env::discover_proxy();
    let home = if uid == 0 {
        "/root"
    } else {
        crate::layout::GUEST_HOME
    };
    let env = crate::env::guest_env(&tz, &proxy, home, &req.env);

    #[cfg(not(unix))]
    {
        let _ = (snapshot, req, uid, gid, env);
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu execution requires unix".into(),
        ));
    }

    #[cfg(unix)]
    let mut cmd = {
        let keeper_ns = CString::new(format!("/proc/{}/ns/mnt", snapshot.pid))
            .map_err(|_| (ErrorCode::BadParams, "invalid keeper namespace path".into()))?;
        let rootfs = CString::new(snapshot.rootfs.clone())
            .map_err(|_| (ErrorCode::BadParams, "NUL in rootfs path".into()))?;
        let cwd = CString::new(req.cwd.clone())
            .map_err(|_| (ErrorCode::BadParams, "NUL in cwd".into()))?;

        let mut command = std::process::Command::new(&req.argv[0]);
        command.args(&req.argv[1..]);
        command.env_clear();
        command.envs(&env);
        command.stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        unsafe {
            command.pre_exec(move || enter_guest_pre_exec(&keeper_ns, &rootfs, &cwd, uid, gid));
        }
        command
    };

    #[cfg(unix)]
    let mut child = cmd
        .spawn()
        .map_err(|e| classify_spawn_error(e, &req.argv[0]))?;
    #[cfg(unix)]
    let pid = child.id() as i32;
    #[cfg(unix)]
    let stdout = child
        .stdout
        .take()
        .ok_or((ErrorCode::Internal, "missing exec stdout".into()))?;
    #[cfg(unix)]
    let stderr = child
        .stderr
        .take()
        .ok_or((ErrorCode::Internal, "missing exec stderr".into()))?;
    #[cfg(unix)]
    let stdout_handle = std::thread::spawn(move || collect_bounded(stdout, MAX_CAPTURE_BYTES));
    #[cfg(unix)]
    let stderr_handle = std::thread::spawn(move || collect_bounded(stderr, MAX_CAPTURE_BYTES));
    #[cfg(unix)]
    let wait_handle = std::thread::spawn(move || child.wait());
    #[cfg(unix)]
    let start = Instant::now();

    #[cfg(unix)]
    loop {
        if wait_handle.is_finished() {
            let status = wait_handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join guest process".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("wait guest process: {e}")))?;
            let stdout = stdout_handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join stdout collector".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("read stdout: {e}")))?;
            let stderr = stderr_handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "join stderr collector".into()))?
                .map_err(|e| (ErrorCode::Internal, format!("read stderr: {e}")))?;
            return Ok(json!({
                "exit_code": status.code().unwrap_or(255),
                "stdout": String::from_utf8_lossy(&stdout.retained).into_owned(),
                "stderr": String::from_utf8_lossy(&stderr.retained).into_owned(),
                "stdout_bytes": stdout.total_bytes,
                "stderr_bytes": stderr.total_bytes,
                "stdout_truncated": stdout.truncated,
                "stderr_truncated": stderr.truncated,
                "uid": uid,
                "admin": admin
            }));
        }
        if start.elapsed() >= Duration::from_millis(req.timeout_ms) {
            unsafe {
                libc::kill(pid, libc::SIGKILL);
                libc::kill(-pid, libc::SIGKILL);
            }
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
                ErrorCode::Timeout,
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

    #[cfg(unix)]
    #[test]
    fn reserved_preexec_errnos_map_to_structured_runtime_errors() {
        assert_eq!(
            classify_spawn_error(staged_error(PREEXEC_ERR_SETNS), "/bin/true").0,
            ErrorCode::KeeperNamespaceLost
        );
        assert_eq!(
            classify_spawn_error(staged_error(PREEXEC_ERR_CHROOT), "/bin/true").0,
            ErrorCode::ChrootUnavailable
        );
        assert_eq!(
            classify_spawn_error(staged_error(PREEXEC_ERR_PRIVS), "/bin/true").0,
            ErrorCode::GuestPrivilegeSetupFailed
        );
        assert_eq!(
            classify_spawn_error(std::io::Error::from_raw_os_error(libc::ENOENT), "/missing").0,
            ErrorCode::GuestExecveFailed
        );
    }
}
