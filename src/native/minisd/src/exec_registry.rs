use crate::protocol::ErrorCode;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct ActiveExec {
    pid: i32,
    cancel_requested: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CancelOutcome {
    pub found: bool,
    pub killed: bool,
}

static ACTIVE: OnceLock<Mutex<HashMap<String, ActiveExec>>> = OnceLock::new();

fn table() -> &'static Mutex<HashMap<String, ActiveExec>> {
    ACTIVE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn valid_execution_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b':'))
}

pub struct ExecGuard {
    id: Option<String>,
}

impl ExecGuard {
    pub fn begin(id: Option<&str>) -> Result<Self, ErrorCode> {
        let Some(id) = id else {
            return Ok(Self { id: None });
        };
        if !valid_execution_id(id) {
            return Err(ErrorCode::BadParams);
        }
        let mut active = table().lock().map_err(|_| ErrorCode::Internal)?;
        if active.contains_key(id) {
            return Err(ErrorCode::BadParams);
        }
        active.insert(
            id.to_string(),
            ActiveExec {
                pid: 0,
                cancel_requested: false,
            },
        );
        Ok(Self {
            id: Some(id.to_string()),
        })
    }

    /// Attach the spawned process-group leader. Returns true when cancellation
    /// raced the spawn and the caller must terminate the just-created process.
    pub fn activate(&self, pid: i32) -> Result<bool, ErrorCode> {
        let Some(id) = self.id.as_deref() else {
            return Ok(false);
        };
        let mut active = table().lock().map_err(|_| ErrorCode::Internal)?;
        let entry = active.get_mut(id).ok_or(ErrorCode::Internal)?;
        entry.pid = pid;
        Ok(entry.cancel_requested)
    }

    pub fn was_cancelled(&self) -> bool {
        let Some(id) = self.id.as_deref() else {
            return false;
        };
        table()
            .lock()
            .ok()
            .and_then(|active| active.get(id).copied())
            .map(|entry| entry.cancel_requested)
            .unwrap_or(false)
    }
}

impl Drop for ExecGuard {
    fn drop(&mut self) {
        if let Some(id) = self.id.as_deref() {
            if let Ok(mut active) = table().lock() {
                active.remove(id);
            }
        }
    }
}

#[cfg(unix)]
fn signal_pid_and_group(pid: i32, sig: i32) {
    unsafe {
        // The direct PID signal covers the small race before setpgid(0,0)
        // completes. The negative PID covers the process tree afterwards.
        libc::kill(pid, sig);
        libc::kill(-pid, sig);
    }
}

#[cfg(unix)]
fn group_or_process_alive(pid: i32) -> bool {
    unsafe { libc::kill(pid, 0) == 0 || libc::kill(-pid, 0) == 0 }
}

/// Request cancellation of one execution. Cancellation is deliberately
/// independent from the request socket: callers can close a wedged transport
/// and issue this RPC over a fresh connection.
pub fn cancel(execution_id: &str) -> Result<CancelOutcome, ErrorCode> {
    if !valid_execution_id(execution_id) {
        return Err(ErrorCode::BadParams);
    }
    let pid = {
        let mut active = table().lock().map_err(|_| ErrorCode::Internal)?;
        let Some(entry) = active.get_mut(execution_id) else {
            return Ok(CancelOutcome {
                found: false,
                killed: false,
            });
        };
        entry.cancel_requested = true;
        entry.pid
    };
    if pid <= 0 {
        return Ok(CancelOutcome {
            found: true,
            killed: false,
        });
    }

    #[cfg(unix)]
    {
        signal_pid_and_group(pid, libc::SIGTERM);
        let start = Instant::now();
        while start.elapsed() < Duration::from_millis(250) && group_or_process_alive(pid) {
            std::thread::sleep(Duration::from_millis(10));
        }
        if group_or_process_alive(pid) {
            signal_pid_and_group(pid, libc::SIGKILL);
        }
        Ok(CancelOutcome {
            found: true,
            killed: true,
        })
    }
    #[cfg(not(unix))]
    {
        let _ = pid;
        Ok(CancelOutcome {
            found: true,
            killed: false,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_tracks_and_cleans_execution() {
        let execution_id = "test-registry-cleanup";
        {
            let guard = ExecGuard::begin(Some(execution_id)).unwrap();
            assert!(!guard.was_cancelled());
            assert!(guard.activate(12345).is_ok());
            assert!(ExecGuard::begin(Some(execution_id)).is_err());
        }
        let guard = ExecGuard::begin(Some(execution_id)).unwrap();
        drop(guard);
    }

    #[test]
    fn duplicate_and_invalid_ids_fail_closed() {
        let _guard = ExecGuard::begin(Some("test-duplicate")).unwrap();
        assert!(ExecGuard::begin(Some("test-duplicate")).is_err());
        assert!(ExecGuard::begin(Some("bad/id")).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn cancellation_terminates_hanging_process_group_and_cleans_registry() {
        use std::os::unix::process::CommandExt;
        use std::process::Command;

        let execution_id = format!("test-hanging-process-{}", std::process::id());
        let guard = ExecGuard::begin(Some(&execution_id)).unwrap();
        let mut command = Command::new("/bin/sh");
        command.arg("-c").arg("sleep 30");
        unsafe {
            command.pre_exec(|| {
                if libc::setpgid(0, 0) == 0 {
                    Ok(())
                } else {
                    Err(std::io::Error::last_os_error())
                }
            });
        }
        let mut child = command.spawn().expect("spawn hanging subprocess");
        assert!(!guard.activate(child.id() as i32).unwrap());

        let outcome = cancel(&execution_id).unwrap();
        assert!(outcome.found);
        assert!(outcome.killed);
        let status = child.wait().expect("reap cancelled subprocess");
        assert!(!status.success());

        drop(guard);
        assert!(!cancel(&execution_id).unwrap().found);
    }
}
