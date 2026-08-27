use crate::protocol::ErrorCode;

pub const HOST_WORKSPACE: &str = "/data/adb/minis/workspace";
pub const GUEST_WORKSPACE: &str = "/workspace";

pub fn normalize_unix(path: &str) -> Result<Vec<String>, ErrorCode> {
    if path.is_empty() || path.contains('\0') {
        return Err(ErrorCode::BadParams);
    }
    if path.contains('\\') {
        return Err(ErrorCode::PolicyDenied);
    }
    let mut out: Vec<String> = Vec::new();
    for part in path.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." {
            return Err(ErrorCode::PolicyDenied);
        }
        out.push(part.to_string());
    }
    Ok(out)
}

pub fn resolve_workspace(user_path: &str) -> Result<String, ErrorCode> {
    if looks_like_symlink_escape(user_path) {
        return Err(ErrorCode::PolicyDenied);
    }
    let parts = normalize_unix(user_path)?;
    let host = if user_path == GUEST_WORKSPACE || user_path.starts_with("/workspace/") {
        join_host(&parts[1..])
    } else if user_path == HOST_WORKSPACE || user_path.starts_with("/data/adb/minis/workspace/") {
        if parts.len() < 4 {
            HOST_WORKSPACE.to_string()
        } else {
            join_host(&parts[4..])
        }
    } else if user_path.starts_with('/') {
        return Err(ErrorCode::PolicyDenied);
    } else {
        join_host(&parts)
    };
    #[cfg(unix)]
    canonical_under(&host, HOST_WORKSPACE)?;
    Ok(host)
}

/// Realpath-based containment check: the resolved absolute path of
/// `host_path` (missing tail components allowed) must stay inside `root`.
/// Prevents symlink escapes created inside the workspace (TOCTOU-safe at
/// open time on unix). Non-unix builds skip this and rely on the lexical
/// check above.
#[cfg(unix)]
pub fn canonical_under(host_path: &str, root: &str) -> Result<(), ErrorCode> {
    let mut cur = std::path::Path::new(host_path);
    let mut missing: Vec<&str> = Vec::new();
    loop {
        match std::fs::canonicalize(cur) {
            Ok(mut resolved) => {
                for seg in missing.iter().rev() {
                    resolved.push(seg);
                }
                let s = resolved.to_string_lossy();
                if s == root || s.starts_with(&format!("{root}/")) {
                    return Ok(());
                }
                return Err(ErrorCode::PolicyDenied);
            }
            Err(_) => match cur.parent() {
                Some(parent) => {
                    // canonicalize failed, so cur is a missing tail segment;
                    // remember it and keep walking up.
                    let name = cur.file_name().and_then(|n| n.to_str());
                    let name = match name {
                        Some(n) => n,
                        None => return Err(ErrorCode::PolicyDenied),
                    };
                    missing.push(name);
                    cur = parent;
                }
                None => return Ok(()), // whole chain nonexistent: lexical fallback
            },
        }
    }
}

fn join_host(rest: &[String]) -> String {
    if rest.is_empty() {
        return HOST_WORKSPACE.to_string();
    }
    format!("{HOST_WORKSPACE}/{}", rest.join("/"))
}

pub fn looks_like_symlink_escape(path: &str) -> bool {
    path.contains("/./") || path.ends_with("/.") || path.contains("//")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t_u7_workspace_ok_and_escape() {
        assert_eq!(
            resolve_workspace("/workspace/x.xlsx").unwrap(),
            "/data/adb/minis/workspace/x.xlsx"
        );
        assert_eq!(
            resolve_workspace("/data/adb/minis/workspace/a/b").unwrap(),
            "/data/adb/minis/workspace/a/b"
        );
        assert_eq!(
            resolve_workspace("sessions/1/out.txt").unwrap(),
            "/data/adb/minis/workspace/sessions/1/out.txt"
        );
        assert_eq!(
            resolve_workspace("/workspace/../policy").unwrap_err(),
            ErrorCode::PolicyDenied
        );
        assert_eq!(
            resolve_workspace("/data/adb/minis/workspace/../policy/policy.json").unwrap_err(),
            ErrorCode::PolicyDenied
        );
        assert_eq!(
            resolve_workspace("/etc/passwd").unwrap_err(),
            ErrorCode::PolicyDenied
        );
        assert_eq!(
            resolve_workspace("/data/adb/minis/policy/x").unwrap_err(),
            ErrorCode::PolicyDenied
        );
        assert!(resolve_workspace("").is_err());
    }

    #[test]
    fn t_u21_workspace_prefix_boundary() {
        assert_eq!(
            resolve_workspace("/workspaceXYZ/x").unwrap_err(),
            ErrorCode::PolicyDenied
        );
        assert_eq!(
            resolve_workspace("/data/adb/minis/workspaceXYZ").unwrap_err(),
            ErrorCode::PolicyDenied
        );
    }

    #[cfg(unix)]
    #[test]
    fn t_u11_symlink_escape_rejected() {
        use std::os::unix::fs::symlink;
        let base = std::env::temp_dir().join(format!("minisd_u11_{}", std::process::id()));
        let root = base.join("m2root/ws");
        let out = base.join("m2root/out");
        std::fs::create_dir_all(out.join("secret")).unwrap();
        std::fs::create_dir_all(&root).unwrap();
        symlink("../out/secret", root.join("evil")).unwrap();
        let root_s = root.to_string_lossy().to_string();
        // symlink escape: resolves outside root
        assert_eq!(
            canonical_under(&root.join("evil/x").to_string_lossy(), &root_s),
            Err(ErrorCode::PolicyDenied)
        );
        // plain path inside root
        std::fs::write(root.join("ok.txt"), "hi").unwrap();
        assert_eq!(
            canonical_under(&root.join("ok.txt").to_string_lossy(), &root_s),
            Ok(())
        );
        // entirely nonexistent tail: lexical fallback
        assert_eq!(
            canonical_under(&root.join("no/such/deep/path").to_string_lossy(), &root_s),
            Ok(())
        );
        let _ = std::fs::remove_dir_all(&base);
    }
}
