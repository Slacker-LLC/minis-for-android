use crate::exec_registry::ExecGuard;
use crate::policy::{args_denied, MethodPolicy};
use crate::protocol::{ErrorCode, MAX_ARGS, MAX_ARG_BYTES};
use std::io::Read;
use std::time::{Duration, Instant};

/// Compile-time Standard Mode capability set. Runtime policy may only narrow it.
pub const DEFAULT_TOOLS: &[&str] = &[
    "pm", "am", "settings", "dumpsys", "getprop", "mount", "pidof", "ps", "logcat",
];
pub const MAX_CAPTURE_BYTES: usize = 256 * 1024;

#[derive(Debug, Clone)]
pub struct ExecRequest {
    pub tool: String,
    pub args: Vec<String>,
    pub timeout_ms: u64,
    pub execution_id: Option<String>,
}

pub fn parse_exec(params: &serde_json::Value) -> Result<ExecRequest, ErrorCode> {
    let tool = params
        .get("tool")
        .and_then(|v| v.as_str())
        .ok_or(ErrorCode::BadParams)?
        .to_string();
    if tool.is_empty() || tool.contains('/') || tool.contains('\0') {
        return Err(ErrorCode::BadParams);
    }
    let args = match params.get("args") {
        None => Vec::new(),
        Some(serde_json::Value::Array(items)) => {
            if items.len() > MAX_ARGS {
                return Err(ErrorCode::BadParams);
            }
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                let s = item.as_str().ok_or(ErrorCode::BadParams)?;
                if s.len() > MAX_ARG_BYTES || s.contains('\0') {
                    return Err(ErrorCode::BadParams);
                }
                out.push(s.to_string());
            }
            out
        }
        Some(_) => return Err(ErrorCode::BadParams),
    };
    if params.get("command").is_some() {
        return Err(ErrorCode::BadParams);
    }
    let timeout_ms = params
        .get("timeout_ms")
        .and_then(|v| v.as_u64())
        .unwrap_or(30_000)
        .clamp(1_000, 600_000);
    let execution_id = params
        .get("execution_id")
        .and_then(|v| v.as_str())
        .map(str::to_string);
    Ok(ExecRequest {
        tool,
        args,
        timeout_ms,
        execution_id,
    })
}

pub fn effective_allowlist(spec: Option<&MethodPolicy>) -> Vec<&str> {
    match spec.and_then(|s| s.tool_allowlist.as_ref()) {
        Some(policy_tools) => policy_tools
            .iter()
            .map(String::as_str)
            .filter(|tool| DEFAULT_TOOLS.contains(tool))
            .collect(),
        None => DEFAULT_TOOLS.to_vec(),
    }
}

pub fn validate_exec(spec: Option<&MethodPolicy>, req: &ExecRequest) -> Result<(), ErrorCode> {
    let allow = effective_allowlist(spec);
    if !allow.iter().any(|t| *t == req.tool) {
        return Err(ErrorCode::PolicyDenied);
    }
    if let Some(spec) = spec {
        if let Some(rule) = spec.arg_rules.get(&req.tool) {
            if args_denied(rule, &req.args) {
                return Err(ErrorCode::PolicyDenied);
            }
        }
    }
    Ok(())
}

pub fn resolve_tool_path(tool: &str, allow: &[&str]) -> Result<String, ErrorCode> {
    if !allow.contains(&tool) {
        return Err(ErrorCode::PolicyDenied);
    }
    resolve_trusted_tool_path(tool)
}

/// Full Access Mode can select any executable exposed from trusted Android
/// system/vendor binary directories. The request still uses structured argv;
/// shell parsing exists only when the user has explicitly enabled Full Access
/// and the requested tool itself is `sh` with `-c` in argv.
pub fn resolve_unrestricted_tool_path(tool: &str) -> Result<String, ErrorCode> {
    resolve_trusted_tool_path(tool)
}

fn resolve_trusted_tool_path(tool: &str) -> Result<String, ErrorCode> {
    if tool.is_empty() || tool.contains('/') || tool.contains('\0') {
        return Err(ErrorCode::BadParams);
    }
    for prefix in ["/system/bin/", "/system/xbin/", "/vendor/bin/"] {
        let p = format!("{prefix}{tool}");
        if std::path::Path::new(&p).exists() {
            return Ok(p);
        }
    }
    Err(ErrorCode::BadParams)
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

pub struct ExecOutput {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub stdout_bytes: u64,
    pub stderr_bytes: u64,
    pub stdout_truncated: bool,
    pub stderr_truncated: bool,
}

#[cfg(unix)]
fn kill_process_tree(pid: i32) {
    unsafe {
        libc::kill(pid, libc::SIGKILL);
        libc::kill(-pid, libc::SIGKILL);
    }
}

pub fn run_exec(req: &ExecRequest, allow: &[&str]) -> Result<ExecOutput, ErrorCode> {
    let path = resolve_tool_path(&req.tool, allow)?;
    run_exec_path(req, &path)
}

pub fn run_exec_unrestricted(req: &ExecRequest) -> Result<ExecOutput, ErrorCode> {
    let path = resolve_unrestricted_tool_path(&req.tool)?;
    run_exec_path(req, &path)
}

fn run_exec_path(req: &ExecRequest, path: &str) -> Result<ExecOutput, ErrorCode> {
    let guard = ExecGuard::begin(req.execution_id.as_deref())?;
    let mut cmd = std::process::Command::new(path);
    cmd.args(&req.args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        unsafe {
            cmd.pre_exec(|| {
                if libc::setpgid(0, 0) != 0 {
                    return Err(std::io::Error::last_os_error());
                }
                Ok(())
            });
        }
    }
    let mut child = cmd.spawn().map_err(|_| ErrorCode::Internal)?;
    let pid = child.id() as i32;
    if guard.activate(pid)? {
        #[cfg(unix)]
        kill_process_tree(pid);
    }
    let stdout = child.stdout.take().ok_or(ErrorCode::Internal)?;
    let stderr = child.stderr.take().ok_or(ErrorCode::Internal)?;
    let stdout_handle = std::thread::spawn(move || collect_bounded(stdout, MAX_CAPTURE_BYTES));
    let stderr_handle = std::thread::spawn(move || collect_bounded(stderr, MAX_CAPTURE_BYTES));
    let wait_handle = std::thread::spawn(move || child.wait());
    let start = Instant::now();

    loop {
        if wait_handle.is_finished() {
            let status = wait_handle
                .join()
                .map_err(|_| ErrorCode::Internal)?
                .map_err(|_| ErrorCode::Internal)?;
            let stdout = stdout_handle
                .join()
                .map_err(|_| ErrorCode::Internal)?
                .map_err(|_| ErrorCode::Internal)?;
            let stderr = stderr_handle
                .join()
                .map_err(|_| ErrorCode::Internal)?
                .map_err(|_| ErrorCode::Internal)?;
            if guard.was_cancelled() {
                return Err(ErrorCode::UserCancelled);
            }
            let Some(exit_code) = status.code() else {
                return Err(ErrorCode::ProcessKilled);
            };
            return Ok(ExecOutput {
                exit_code,
                stdout: String::from_utf8_lossy(&stdout.retained).into_owned(),
                stderr: String::from_utf8_lossy(&stderr.retained).into_owned(),
                stdout_bytes: stdout.total_bytes,
                stderr_bytes: stderr.total_bytes,
                stdout_truncated: stdout.truncated,
                stderr_truncated: stderr.truncated,
            });
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
            return Err(ErrorCode::ToolTimeout);
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{MethodPolicy, Mode, PolicyFile};
    use std::io::Cursor;

    #[test]
    fn t_u4_allowlist_and_deny() {
        let policy = PolicyFile::default_policy();
        let spec = policy.method("root.exec");
        let ok = parse_exec(&serde_json::json!({"tool":"pm","args":["list","packages"]})).unwrap();
        assert!(validate_exec(spec, &ok).is_ok());
        let logcat = parse_exec(&serde_json::json!({"tool":"logcat","args":["-d"]})).unwrap();
        assert!(validate_exec(spec, &logcat).is_ok());
        let bad_tool = parse_exec(&serde_json::json!({"tool":"reboot","args":[]})).unwrap();
        assert_eq!(
            validate_exec(spec, &bad_tool).unwrap_err(),
            ErrorCode::PolicyDenied
        );
        let denied = parse_exec(&serde_json::json!({"tool":"pm","args":["shell","su"]})).unwrap();
        assert_eq!(
            validate_exec(spec, &denied).unwrap_err(),
            ErrorCode::PolicyDenied
        );
        let destructive =
            parse_exec(&serde_json::json!({"tool":"pm","args":["uninstall","com.example.test"]}))
                .unwrap();
        assert_eq!(
            validate_exec(spec, &destructive).unwrap_err(),
            ErrorCode::PolicyDenied
        );
        let long = "x".repeat(crate::protocol::MAX_ARG_BYTES + 1);
        assert!(parse_exec(&serde_json::json!({"tool":"pm","args":[long]})).is_err());
        assert!(parse_exec(&serde_json::json!({"tool":"pm","command":"pm shell"})).is_err());
    }

    #[test]
    fn full_access_request_is_still_structured_argv() {
        let parsed = parse_exec(&serde_json::json!({
            "tool":"sh",
            "args":["-c","id; getenforce"],
            "execution_id":"root:s1:42"
        }))
        .unwrap();
        assert_eq!(parsed.tool, "sh");
        assert_eq!(parsed.args, vec!["-c", "id; getenforce"]);
        assert_eq!(parsed.execution_id.as_deref(), Some("root:s1:42"));
    }

    #[test]
    fn execution_id_is_carried_for_targeted_cancel() {
        let parsed =
            parse_exec(&serde_json::json!({"tool":"pm","args":[],"execution_id":"root:s1:42"}))
                .unwrap();
        assert_eq!(parsed.execution_id.as_deref(), Some("root:s1:42"));
    }

    #[test]
    fn runtime_allowlist_can_only_narrow_builtin_tools() {
        let spec = MethodPolicy {
            mode: Mode::Allow,
            tool_allowlist: Some(vec!["pm".into(), "reboot".into()]),
            arg_rules: Default::default(),
            rate_per_min: None,
        };
        let allow = effective_allowlist(Some(&spec));
        assert_eq!(allow, vec!["pm"]);
        let pm = parse_exec(&serde_json::json!({"tool":"pm","args":[]})).unwrap();
        assert!(validate_exec(Some(&spec), &pm).is_ok());
        let reboot = parse_exec(&serde_json::json!({"tool":"reboot","args":[]})).unwrap();
        assert_eq!(
            validate_exec(Some(&spec), &reboot).unwrap_err(),
            ErrorCode::PolicyDenied
        );
    }

    #[test]
    fn bounded_collector_truncates_large_stdout() {
        let data = vec![b'o'; MAX_CAPTURE_BYTES + 4096];
        let out = collect_bounded(Cursor::new(data), MAX_CAPTURE_BYTES).unwrap();
        assert_eq!(out.retained.len(), MAX_CAPTURE_BYTES);
        assert_eq!(out.total_bytes, (MAX_CAPTURE_BYTES + 4096) as u64);
        assert!(out.truncated);
    }

    #[test]
    fn bounded_collector_truncates_large_stderr() {
        let data = vec![b'e'; MAX_CAPTURE_BYTES + 1];
        let out = collect_bounded(Cursor::new(data), MAX_CAPTURE_BYTES).unwrap();
        assert_eq!(out.retained.len(), MAX_CAPTURE_BYTES);
        assert!(out.truncated);
    }

    #[test]
    fn bounded_collectors_handle_combined_large_output_independently() {
        let stdout = collect_bounded(
            Cursor::new(vec![b'o'; MAX_CAPTURE_BYTES + 10]),
            MAX_CAPTURE_BYTES,
        )
        .unwrap();
        let stderr = collect_bounded(
            Cursor::new(vec![b'e'; MAX_CAPTURE_BYTES + 20]),
            MAX_CAPTURE_BYTES,
        )
        .unwrap();
        assert!(stdout.truncated && stderr.truncated);
        assert_eq!(
            stdout.retained.len() + stderr.retained.len(),
            MAX_CAPTURE_BYTES * 2
        );
    }
}
