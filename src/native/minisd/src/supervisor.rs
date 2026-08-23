//! cloudflared supervision: spawn the real binary, restart it on crash.

use crate::protocol::ErrorCode;
use crate::state::AppState;
use serde_json::json;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const DEFAULT_CLOUDFLARED_ARGS: &[&str] = &["tunnel", "--no-autoupdate", "run"];
const CHECK_INTERVAL: Duration = Duration::from_secs(2);
const MAX_CONSECUTIVE_RESTARTS: u32 = 5;

/// path must be absolute ('/' prefix), contain no ".." component, and be an existing file.
pub fn validate_cloudflared_path(path: &str) -> Result<(), ErrorCode> {
    if !path.starts_with('/') {
        return Err(ErrorCode::BadParams);
    }
    if path.split('/').any(|c| c == "..") {
        return Err(ErrorCode::BadParams);
    }
    if !Path::new(path).is_file() {
        return Err(ErrorCode::BadParams);
    }
    Ok(())
}

#[derive(Debug)]
struct CloudflaredShared {
    pid: Mutex<Option<i32>>,
}

impl CloudflaredShared {
    fn set_pid(&self, pid: Option<i32>) {
        *self.pid.lock().unwrap() = pid;
    }

    fn take_pid(&self) -> Option<i32> {
        self.pid.lock().unwrap().take()
    }

    fn pid(&self) -> Option<i32> {
        *self.pid.lock().unwrap()
    }
}

#[derive(Debug)]
pub struct CloudflaredSupervisor {
    shared: Arc<CloudflaredShared>,
    stop: Arc<AtomicBool>,
}

impl CloudflaredSupervisor {
    fn spawn_watch(path: String, args: Vec<String>, env: Vec<(String, String)>, pid: i32) -> Self {
        let shared = Arc::new(CloudflaredShared {
            pid: Mutex::new(Some(pid)),
        });
        let stop = Arc::new(AtomicBool::new(false));
        let thread_shared = Arc::clone(&shared);
        let thread_stop = Arc::clone(&stop);
        std::thread::spawn(move || watch(path, args, env, pid, thread_shared, thread_stop));
        Self {
            shared,
            stop,
        }
    }

    /// (running, pid): running is false once the watch loop gives up or stops.
    pub fn status(&self) -> (bool, Option<i32>) {
        let pid = self.shared.pid();
        (pid.is_some(), pid)
    }

    pub fn stop(self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(pid) = self.shared.take_pid() {
            kill_and_reap(pid);
        }
    }
}

pub fn start_cloudflared(
    state: &mut AppState,
    params: &serde_json::Value,
) -> Result<serde_json::Value, (ErrorCode, String)> {
    // parse optional env before the mock early-return so parameter shape is
    // validated in mock mode too (testable without spawning).
    let env: Vec<(String, String)> = match params.get("env") {
        None => Vec::new(),
        Some(v) => {
            let obj = v
                .as_object()
                .ok_or((ErrorCode::BadParams, String::from("env must be an object")))?;
            obj.iter()
                .map(|(k, val)| {
                    let s = val
                        .as_str()
                        .ok_or((ErrorCode::BadParams, String::from("env values must be strings")))?;
                    Ok((k.clone(), s.to_string()))
                })
                .collect::<Result<_, _>>()?
        }
    };
    if state.mock {
        state.supervisor.cloudflared_running = true;
        state.supervisor.cloudflared_pid = None;
        return Ok(json!({"cloudflared": true}));
    }
    let path = params
        .get("path")
        .and_then(|v| v.as_str())
        .ok_or((ErrorCode::BadParams, String::from("path required")))?;
    validate_cloudflared_path(path)
        .map_err(|_| (ErrorCode::BadParams, String::from("path must be absolute, existing file, no '..'")))?;
    let args: Vec<String> = match params.get("args") {
        None => DEFAULT_CLOUDFLARED_ARGS.iter().map(|s| s.to_string()).collect(),
        Some(v) => {
            let arr = v
                .as_array()
                .ok_or((ErrorCode::BadParams, String::from("args must be an array of strings")))?;
            arr.iter()
                .map(|a| {
                    a.as_str()
                        .map(|s| s.to_string())
                        .ok_or((ErrorCode::BadParams, String::from("args must be an array of strings")))
                })
                .collect::<Result<_, _>>()?
        }
    };
    // restart semantics: stop any previous supervised child first
    if let Some(prev) = state.supervisor.cloudflared.take() {
        prev.stop();
    }
    let mut cmd = std::process::Command::new(&path);
    cmd.args(&args);
    for (k, v) in &env {
        cmd.env(k, v);
    }
    let child = cmd
        .spawn()
        .map_err(|e| (ErrorCode::Internal, format!("spawn cloudflared: {e}")))?;
    let pid = child.id() as i32;
    drop(child); // watch() reaps on exit, stop() reaps on kill
    state.supervisor.cloudflared =
        Some(CloudflaredSupervisor::spawn_watch(path.to_string(), args, env, pid));
    state.supervisor.cloudflared_running = true;
    state.supervisor.cloudflared_pid = Some(pid);
    Ok(json!({"cloudflared": true, "pid": pid}))
}

pub fn stop_cloudflared(state: &mut AppState) -> serde_json::Value {
    state.supervisor.cloudflared_running = false;
    state.supervisor.cloudflared_pid = None;
    if let Some(prev) = state.supervisor.cloudflared.take() {
        prev.stop();
    }
    json!({"cloudflared": false})
}

fn watch(
    path: String,
    args: Vec<String>,
    env: Vec<(String, String)>,
    mut pid: i32,
    shared: Arc<CloudflaredShared>,
    stop: Arc<AtomicBool>,
) {
    let mut consecutive: u32 = 0;
    loop {
        std::thread::sleep(CHECK_INTERVAL);
        if stop.load(Ordering::SeqCst) {
            shared.set_pid(None);
            return;
        }
        if process_alive(pid) {
            continue;
        }
        reap(pid);
        if stop.load(Ordering::SeqCst) {
            shared.set_pid(None);
            return;
        }
        // check before counting: at most MAX restarts, give up on death #MAX+1
        if consecutive >= MAX_CONSECUTIVE_RESTARTS {
            eprintln!(
                "cloudflared: died again, {MAX_CONSECUTIVE_RESTARTS} restarts exhausted, giving up"
            );
            shared.set_pid(None);
            return;
        }
        consecutive += 1;
        eprintln!(
            "cloudflared: pid {pid} exited, restart {consecutive}/{MAX_CONSECUTIVE_RESTARTS}"
        );
        match std::process::Command::new(&path).args(&args).envs(env.iter().map(|(k, v)| (k.as_str(), v.as_str()))).spawn() {
            Ok(child) => {
                pid = child.id() as i32;
                drop(child);
                shared.set_pid(Some(pid));
                if stop.load(Ordering::SeqCst) {
                    // lost the race with stop(): kill the fresh child
                    kill_and_reap(pid);
                    shared.set_pid(None);
                    return;
                }
            }
            Err(e) => {
                // spawn failure adds no extra count; the next tick's death check
                // retries the respawn within the same consecutive budget
                eprintln!("cloudflared: respawn failed: {e}");
                shared.set_pid(None);
            }
        }
    }
}

#[cfg(unix)]
fn process_alive(pid: i32) -> bool {
    // /proc/<pid>/stat is "pid (comm) state ppid ..."; state 'Z' means zombie (dead but unreaped)
    let stat = match std::fs::read_to_string(format!("/proc/{pid}/stat")) {
        Ok(s) => s,
        Err(_) => return false,
    };
    match stat.rsplit_once(')') {
        Some((_, rest)) => !rest.trim_start().starts_with('Z'),
        None => false,
    }
}

#[cfg(not(unix))]
fn process_alive(_pid: i32) -> bool {
    // ponytail: non-unix builds are dev-only; supervision is a no-op there
    true
}

#[cfg(unix)]
fn reap(pid: i32) {
    unsafe {
        let mut status: libc::c_int = 0;
        libc::waitpid(pid, &mut status, libc::WNOHANG);
    }
}

#[cfg(not(unix))]
fn reap(_pid: i32) {}

#[cfg(unix)]
fn kill_and_reap(pid: i32) {
    unsafe {
        libc::kill(pid, libc::SIGTERM);
        let mut status: libc::c_int = 0;
        // 1s grace period for a clean shutdown, then SIGKILL
        for _ in 0..5 {
            if libc::waitpid(pid, &mut status, libc::WNOHANG) != 0 {
                return; // reaped, or already gone (ECHILD)
            }
            std::thread::sleep(Duration::from_millis(200));
        }
        libc::kill(pid, libc::SIGKILL);
        libc::waitpid(pid, &mut status, 0);
    }
}

#[cfg(not(unix))]
fn kill_and_reap(_pid: i32) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cloudflared_path_rules() {
        assert!(validate_cloudflared_path("").is_err());
        assert!(validate_cloudflared_path("relative/path").is_err());
        assert!(validate_cloudflared_path("/etc/../shadow").is_err());
        assert!(validate_cloudflared_path("/..").is_err());
        #[cfg(unix)]
        {
            assert!(validate_cloudflared_path("/bin/sh").is_ok());
            assert!(validate_cloudflared_path("/definitely/not/here").is_err());
        }
    }

    #[test]
    fn mock_start_parses_env_and_rejects_bad_env() {
        let mut state = AppState::new(true, crate::policy::PolicyFile::default_policy());
        let ok = start_cloudflared(
            &mut state,
            &serde_json::json!({"path": "/bin/sh", "env": {"TUNNEL_TOKEN": "t", "HTTP_PROXY": ""}}),
        )
        .unwrap();
        assert_eq!(ok["cloudflared"], true);
        // env must be an object of strings
        assert!(start_cloudflared(
            &mut state,
            &serde_json::json!({"path": "/bin/sh", "env": {"A": 1}}),
        )
        .is_err());
    }
}
