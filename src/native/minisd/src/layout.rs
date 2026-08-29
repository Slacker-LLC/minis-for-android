pub use crate::path_guard::{GUEST_WORKSPACE, HOST_WORKSPACE};

pub const HOST_MINIS: &str = "/data/adb/minis";
pub const HOST_ROOTFS: &str = "/data/adb/minis/rootfs";
pub const HOST_MEMORY: &str = "/data/adb/minis/memory";
pub const HOST_SKILLS: &str = "/data/adb/minis/skills";
pub const HOST_SHARED: &str = "/data/adb/minis/shared";
pub const HOST_RUN: &str = "/data/adb/minis/run";
pub const HOST_LOG: &str = "/data/adb/minis/log";

pub const GUEST_UID: u32 = 10_000;
pub const GUEST_GID: u32 = 10_000;
pub const GUEST_USER: &str = "minis";
pub const GUEST_HOME: &str = "/workspace";
pub const GUEST_MEMORY: &str = "/memory";
pub const GUEST_SKILLS: &str = "/skills";
pub const GUEST_SHARED: &str = "/shared";

pub const WORKSPACE_SUBDIRS: &[&str] =
    &["attachments", "offloads", "browser", "sessions", "shared"];

/// P2 migration-window guest symlinks. Deleted at P2 exit.
pub const VAR_MINIS_LINKS: &[(&str, &str)] = &[
    ("var/minis/workspace", "/workspace"),
    ("var/minis/attachments", "/workspace/attachments"),
    ("var/minis/offloads", "/workspace/offloads"),
    ("var/minis/browser", "/workspace/browser"),
    ("var/minis/memory", "/memory"),
    ("var/minis/skills", "/skills"),
    ("var/minis/shared", "/shared"),
];

pub const ROOTFS_MARKER: &str = "etc/minis/rootfs.json";
pub const PROVISION_MARKER: &str = "etc/minis/provisioned";
pub const UBUNTU_PID_FILE: &str = "/data/adb/minis/run/ubuntu.pid";
pub const UBUNTU_ROOTFS_FILE: &str = "/data/adb/minis/run/ubuntu.rootfs";
pub const UBUNTU_PROXY_PID_FILE: &str = "/data/adb/minis/run/ubuntu-proxy.pid";

pub fn workspace_readme() -> &'static str {
    "\
Minis legacy workspace
======================
Guest: /workspace

Normal chat commands receive a private per-session workspace mount.
Session-scoped resources: workspace, attachments, offloads, browser.
App-global resources: /memory, /skills, /shared.

This host directory is retained only for sessionless compatibility and
privileged maintenance; it is not shared into normal chat executions.
"
}

pub fn ensure_host_layout() -> Result<(), String> {
    for dir in [
        HOST_MINIS,
        HOST_ROOTFS,
        HOST_WORKSPACE,
        HOST_MEMORY,
        HOST_SKILLS,
        HOST_SHARED,
        HOST_RUN,
        HOST_LOG,
    ] {
        std::fs::create_dir_all(dir).map_err(|e| format!("mkdir {dir}: {e}"))?;
    }
    for sub in WORKSPACE_SUBDIRS {
        let p = format!("{HOST_WORKSPACE}/{sub}");
        std::fs::create_dir_all(&p).map_err(|e| format!("mkdir {p}: {e}"))?;
    }
    let readme = format!("{HOST_WORKSPACE}/README");
    if !std::path::Path::new(&readme).exists() {
        std::fs::write(&readme, workspace_readme()).map_err(|e| format!("write README: {e}"))?;
    }
    Ok(())
}

pub fn ensure_rootfs_layout(rootfs: &str) -> Result<(), String> {
    let root = std::path::Path::new(rootfs);
    if !root.is_dir() {
        return Err(format!("rootfs missing: {rootfs}"));
    }
    for rel in [
        "proc",
        "sys",
        "dev",
        "dev/pts",
        "dev/shm",
        "tmp",
        "run",
        "workspace",
        "memory",
        "skills",
        "shared",
        "mnt",
        "var/minis",
        "etc/minis",
        "home",
        "root",
    ] {
        std::fs::create_dir_all(root.join(rel)).map_err(|e| format!("mkdir {rel}: {e}"))?;
    }
    for (rel, target) in VAR_MINIS_LINKS {
        let link = root.join(rel);
        if link.exists() || link.symlink_metadata().is_ok() {
            continue;
        }
        if let Some(parent) = link.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::symlink;
            symlink(target, &link).map_err(|e| format!("symlink {rel}: {e}"))?;
        }
        #[cfg(not(unix))]
        {
            let _ = (rel, target);
        }
    }
    Ok(())
}

pub fn rootfs_looks_valid(rootfs: &str) -> bool {
    let root = std::path::Path::new(rootfs);
    root.join("etc/os-release").is_file()
        && (root.join("bin/bash").exists()
            || root.join("usr/bin/bash").exists()
            || root.join("bin/sh").exists())
}

pub fn read_os_release(rootfs: &str) -> Option<String> {
    let text = std::fs::read_to_string(std::path::Path::new(rootfs).join("etc/os-release")).ok()?;
    let version = text
        .lines()
        .find_map(|l| l.strip_prefix("VERSION_ID="))
        .map(|s| s.trim_matches('"').to_string())?;
    Some(version)
}

pub fn is_provisioned(rootfs: &str) -> bool {
    std::path::Path::new(rootfs)
        .join(PROVISION_MARKER)
        .is_file()
        || std::path::Path::new(rootfs)
            .join("usr/bin/python3")
            .exists()
}

pub fn ensure_guest_user(rootfs: &str) -> Result<(), String> {
    ensure_guest_user_ids(rootfs, GUEST_UID, GUEST_GID)
}

pub fn ensure_guest_user_ids(rootfs: &str, uid: u32, gid: u32) -> Result<(), String> {
    let root = std::path::Path::new(rootfs);
    let passwd = format!("minis:x:{uid}:{gid}:Minis:/workspace:/bin/bash\n");
    let group = format!("minis:x:{gid}:\n");
    upsert_named_line(root.join("etc/passwd"), "minis:", &passwd)?;
    upsert_named_line(root.join("etc/group"), "minis:", &group)?;
    Ok(())
}

fn upsert_named_line(
    path: impl AsRef<std::path::Path>,
    prefix: &str,
    line: &str,
) -> Result<(), String> {
    let path = path.as_ref();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("mkdir {}: {e}", parent.display()))?;
    }
    let existing = std::fs::read_to_string(path).unwrap_or_default();
    let mut body = existing
        .lines()
        .filter(|existing_line| !existing_line.starts_with(prefix))
        .collect::<Vec<_>>()
        .join("\n");
    if !body.is_empty() {
        body.push('\n');
    }
    body.push_str(line.trim_end_matches('\n'));
    body.push('\n');
    std::fs::write(path, body).map_err(|e| format!("write {}: {e}", path.display()))
}

pub fn host_paths() -> serde_json::Value {
    serde_json::json!({
        "host_minis": HOST_MINIS,
        "rootfs": HOST_ROOTFS,
        "workspace": HOST_WORKSPACE,
        "memory": HOST_MEMORY,
        "skills": HOST_SKILLS,
        "shared": HOST_SHARED,
        "guest_workspace": GUEST_WORKSPACE,
        "guest_uid": GUEST_UID,
        "guest_gid": GUEST_GID
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn readme_mentions_q16() {
        assert!(workspace_readme().contains("/workspace"));
        assert!(workspace_readme().contains("per-session"));
    }

    #[test]
    fn invalid_rootfs_rejected() {
        assert!(!rootfs_looks_valid("/no/such/rootfs"));
    }

    #[test]
    fn guest_identity_replaces_stale_minis_entries() {
        let unique = format!(
            "minisd-layout-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        let root = std::env::temp_dir().join(unique);
        let etc = root.join("etc");
        std::fs::create_dir_all(&etc).unwrap();
        std::fs::write(
            etc.join("passwd"),
            "root:x:0:0:root:/root:/bin/sh\nminis:x:10000:10000:Minis:/workspace:/bin/bash\n",
        )
        .unwrap();
        std::fs::write(etc.join("group"), "root:x:0:\nminis:x:10000:\n").unwrap();

        ensure_guest_user_ids(root.to_str().unwrap(), 12345, 12345).unwrap();

        let passwd = std::fs::read_to_string(etc.join("passwd")).unwrap();
        let group = std::fs::read_to_string(etc.join("group")).unwrap();
        assert_eq!(
            passwd.lines().filter(|l| l.starts_with("minis:")).count(),
            1
        );
        assert_eq!(group.lines().filter(|l| l.starts_with("minis:")).count(), 1);
        assert!(passwd.contains("minis:x:12345:12345:"));
        assert!(group.contains("minis:x:12345:"));
        assert!(passwd.contains("root:x:0:0:"));

        ensure_guest_user_ids(root.to_str().unwrap(), 23456, 23456).unwrap();
        let passwd = std::fs::read_to_string(etc.join("passwd")).unwrap();
        assert!(!passwd.contains("minis:x:12345:12345:"));
        assert!(passwd.contains("minis:x:23456:23456:"));

        let _ = std::fs::remove_dir_all(root);
    }
}
