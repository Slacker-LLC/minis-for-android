use crate::policy::{args_denied, MethodPolicy};
use crate::protocol::{ErrorCode, MAX_ARGS, MAX_ARG_BYTES};
use std::time::{Duration, Instant};

pub const DEFAULT_TOOLS: &[&str] = &["pm", "am", "settings", "dumpsys", "getprop", "mount"];

#[derive(Debug, Clone)]
pub struct ExecRequest {
    pub tool: String,
    pub args: Vec<String>,
    pub timeout_ms: u64,
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
    Ok(ExecRequest {
        tool,
        args,
        timeout_ms,
    })
}

/// Single source of truth for the root.exec tool allowlist:
/// policy `toolAllowlist` when present, DEFAULT_TOOLS as fallback.
pub fn effective_allowlist(spec: Option<&MethodPolicy>) -> Vec<&str> {
    spec.and_then(|s| s.tool_allowlist.as_ref())
        .map(|v| v.iter().map(String::as_str).collect())
        .unwrap_or_else(|| DEFAULT_TOOLS.to_vec())
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
    for prefix in ["/system/bin/", "/system/xbin/", "/vendor/bin/"] {
        let p = format!("{prefix}{tool}");
        if std::path::Path::new(&p).exists() {
            return Ok(p);
        }
    }
    Err(ErrorCode::BadParams)
}

pub struct ExecOutput {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
}

pub fn run_exec(req: &ExecRequest, allow: &[&str]) -> Result<ExecOutput, ErrorCode> {
    let path = resolve_tool_path(&req.tool, allow)?;
    let mut cmd = std::process::Command::new(&path);
    cmd.args(&req.args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    let child = cmd.spawn().map_err(|_| ErrorCode::Internal)?;
    let pid = child.id() as i32;
    let handle = std::thread::spawn(move || child.wait_with_output());
    let start = Instant::now();
    loop {
        if handle.is_finished() {
            let out = handle
                .join()
                .map_err(|_| ErrorCode::Internal)?
                .map_err(|_| ErrorCode::Internal)?;
            return Ok(ExecOutput {
                exit_code: out.status.code().unwrap_or(255),
                stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
                stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
            });
        }
        if start.elapsed() >= Duration::from_millis(req.timeout_ms) {
            #[cfg(unix)]
            unsafe {
                libc::kill(pid, libc::SIGKILL);
            }
            #[cfg(unix)]
            {
                // bounded join: at most ~1s (50 x 20ms) for the killed child to be reaped
                for _ in 0..50 {
                    if handle.is_finished() {
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(20));
                }
            }
            return Err(ErrorCode::Timeout);
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{MethodPolicy, Mode, PolicyFile};

    #[test]
    fn t_u4_allowlist_and_deny() {
        let policy = PolicyFile::default_policy();
        let spec = policy.method("root.exec");
        let ok = parse_exec(&serde_json::json!({"tool":"pm","args":["force-stop","a.b"]})).unwrap();
        assert!(validate_exec(spec, &ok).is_ok());
        let bad_tool = parse_exec(&serde_json::json!({"tool":"reboot","args":[]})).unwrap();
        assert_eq!(validate_exec(spec, &bad_tool).unwrap_err(), ErrorCode::PolicyDenied);
        let denied = parse_exec(&serde_json::json!({"tool":"pm","args":["shell","su"]})).unwrap();
        assert_eq!(validate_exec(spec, &denied).unwrap_err(), ErrorCode::PolicyDenied);
        let long = "x".repeat(crate::protocol::MAX_ARG_BYTES + 1);
        assert!(parse_exec(&serde_json::json!({"tool":"pm","args":[long]})).is_err());
        assert!(parse_exec(&serde_json::json!({"tool":"pm","command":"pm shell"})).is_err());
    }

    #[test]
    fn t_u4_effective_allowlist() {
        // None → DEFAULT_TOOLS fallback
        assert_eq!(effective_allowlist(None), DEFAULT_TOOLS);
        // policy toolAllowlist overrides the default
        let spec = MethodPolicy {
            mode: Mode::Allow,
            tool_allowlist: Some(vec!["pm".into(), "reboot".into()]),
            arg_rules: Default::default(),
            rate_per_min: None,
        };
        let allow = effective_allowlist(Some(&spec));
        assert!(allow.contains(&"reboot"));
        assert!(!allow.contains(&"getprop"));
        // validate_exec uses the same source: reboot now allowed, getprop not
        let reboot = parse_exec(&serde_json::json!({"tool":"reboot","args":[]})).unwrap();
        assert!(validate_exec(Some(&spec), &reboot).is_ok());
        let getprop = parse_exec(&serde_json::json!({"tool":"getprop","args":[]})).unwrap();
        assert_eq!(
            validate_exec(Some(&spec), &getprop).unwrap_err(),
            ErrorCode::PolicyDenied
        );
        // resolve_tool_path honors the passed allowlist param (deny path needs no fs)
        assert_eq!(
            resolve_tool_path("getprop", &allow).unwrap_err(),
            ErrorCode::PolicyDenied
        );
    }
}
