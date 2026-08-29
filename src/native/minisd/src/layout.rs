pub use crate::path_guard::{GUEST_WORKSPACE, HOST_WORKSPACE};

use std::path::{Path, PathBuf};

pub const HOST_MINIS: &str = "/data/adb/minis";
pub const HOST_ROOTFS: &str = "/data/adb/minis/rootfs";
pub const HOST_SESSIONS: &str = "/data/adb/minis/sessions";
pub const HOST_MEMORY: &str = "/data/adb/minis/memory";
pub const HOST_SKILLS: &str = "/data/adb/minis/skills";
pub const HOST_SHARED: &str = "/data/adb/minis/shared";
pub const HOST_HOME: &str = "/data/adb/minis/home";
pub const HOST_RUN: &str = "/data/adb/minis/run";
pub const HOST_LOG: &str = "/data/adb/minis/log";

pub const GUEST_UID: u32 = 10_000;
pub const GUEST_GID: u32 = 10_000;
pub const GUEST_USER: &str = "minis";
pub const GUEST_HOME: &str = "/home/minis";
pub const GUEST_MEMORY: &str = "/memory";
pub const GUEST_SKILLS: &str = "/skills";
pub const GUEST_SHARED: &str = "/shared";
pub const DEFAULT_GUEST_CWD: &str = GUEST_WORKSPACE;

pub const HOST_ROOT_MODE: u32 = 0o751;
pub const HOST_ROOTFS_MODE: u32 = 0o755;
pub const HOST_RUNTIME_MODE: u32 = 0o750;
pub const PERSISTENT_DATA_MODE: u32 = 0o700;
pub const PERSISTENT_FILE_MODE: u32 = 0o600;

const TMPFS_MAGIC: u64 = 0x0102_1994;

pub const WORKSPACE_SUBDIRS: &[&str] = &["attachments", "offloads", "browser"];

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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PersistentLayout {
    root: PathBuf,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BindSource {
    pub host: PathBuf,
    pub guest: &'static str,
}

impl PersistentLayout {
    pub fn system() -> Self {
        Self::new(HOST_MINIS)
    }

    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn rootfs(&self) -> PathBuf {
        self.root.join("rootfs")
    }

    pub fn workspace(&self) -> PathBuf {
        self.root.join("workspace")
    }

    pub fn sessions(&self) -> PathBuf {
        self.root.join("sessions")
    }

    pub fn memory(&self) -> PathBuf {
        self.root.join("memory")
    }

    pub fn skills(&self) -> PathBuf {
        self.root.join("skills")
    }

    pub fn shared(&self) -> PathBuf {
        self.root.join("shared")
    }

    pub fn home(&self) -> PathBuf {
        self.root.join("home")
    }

    pub fn run(&self) -> PathBuf {
        self.root.join("run")
    }

    pub fn log(&self) -> PathBuf {
        self.root.join("log")
    }

    pub fn keeper_bind_sources(&self) -> Vec<BindSource> {
        vec![
            BindSource {
                host: self.workspace(),
                guest: GUEST_WORKSPACE,
            },
            BindSource {
                host: self.memory(),
                guest: GUEST_MEMORY,
            },
            BindSource {
                host: self.skills(),
                guest: GUEST_SKILLS,
            },
            BindSource {
                host: self.shared(),
                guest: GUEST_SHARED,
            },
            BindSource {
                host: self.home(),
                guest: GUEST_HOME,
            },
        ]
    }

    pub fn persistent_sources(&self) -> [(&'static str, PathBuf); 6] {
        [
            ("workspace", self.workspace()),
            ("sessions", self.sessions()),
            ("memory", self.memory()),
            ("skills", self.skills()),
            ("shared", self.shared()),
            ("home", self.home()),
        ]
    }

    pub fn initialize(&self, data_uid: u32, data_gid: u32) -> Result<(), String> {
        self.initialize_with_owners(data_uid, data_gid, 0, 0)
    }

    fn initialize_with_owners(
        &self,
        data_uid: u32,
        data_gid: u32,
        runtime_uid: u32,
        runtime_gid: u32,
    ) -> Result<(), String> {
        ensure_directory(&self.root, runtime_uid, runtime_gid, HOST_ROOT_MODE)?;
        ensure_directory(
            &self.rootfs(),
            runtime_uid,
            runtime_gid,
            HOST_ROOTFS_MODE,
        )?;
        ensure_directory(
            &self.run(),
            runtime_uid,
            runtime_gid,
            HOST_RUNTIME_MODE,
        )?;
        ensure_directory(
            &self.log(),
            runtime_uid,
            runtime_gid,
            HOST_RUNTIME_MODE,
        )?;

        for (_, path) in self.persistent_sources() {
            ensure_directory(&path, data_uid, data_gid, PERSISTENT_DATA_MODE)?;
        }
        for sub in WORKSPACE_SUBDIRS {
            ensure_directory(
                &self.workspace().join(sub),
                data_uid,
                data_gid,
                PERSISTENT_DATA_MODE,
            )?;
        }

        let readme = self.workspace().join("README");
        if !readme.exists() {
            std::fs::write(&readme, workspace_readme())
                .map_err(|e| format!("write {}: {e}", readme.display()))?;
        }
        set_owner_mode(&readme, data_uid, data_gid, PERSISTENT_FILE_MODE)?;
        Ok(())
    }

    pub fn validate_persistent_backing(&self) -> Result<(), String> {
        for (label, path) in self.persistent_sources() {
            ensure_non_tmpfs_directory(label, &path)?;
        }
        Ok(())
    }
}

pub fn workspace_readme() -> &'static str {
    "\
Minis persistent workspace
==========================
Guest workspace: /workspace
Guest home: /home/minis

Persistent host sources are rooted at /data/adb/minis and are prepared by
minisd before keeper mount-namespace creation. Session-scoped workspaces live
under /data/adb/minis/sessions; global memory, skills, shared data and HOME are
separate persistent sources.
"
}

pub fn ensure_host_layout(uid: u32, gid: u32) -> Result<(), String> {
    PersistentLayout::system().initialize(uid, gid)
}

pub fn validate_persistent_backing() -> Result<(), String> {
    PersistentLayout::system().validate_persistent_backing()
}

fn ensure_directory(path: &Path, uid: u32, gid: u32, mode: u32) -> Result<(), String> {
    match std::fs::symlink_metadata(path) {
        Ok(meta) => {
            if meta.file_type().is_symlink() {
                return Err(format!("persistent path must not be a symlink: {}", path.display()));
            }
            if !meta.is_dir() {
                return Err(format!("persistent path is not a directory: {}", path.display()));
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            std::fs::create_dir(path).map_err(|e| format!("mkdir {}: {e}", path.display()))?;
        }
        Err(error) => return Err(format!("stat {}: {error}", path.display())),
    }
    set_owner_mode(path, uid, gid, mode)
}

#[cfg(unix)]
fn set_owner_mode(path: &Path, uid: u32, gid: u32, mode: u32) -> Result<(), String> {
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::{MetadataExt, PermissionsExt};

    let c_path = std::ffi::CString::new(path.as_os_str().as_bytes())
        .map_err(|_| format!("NUL in path: {}", path.display()))?;
    if unsafe { libc::chown(c_path.as_ptr(), uid, gid) } != 0 {
        return Err(format!(
            "chown {} to {uid}:{gid}: {}",
            path.display(),
            std::io::Error::last_os_error()
        ));
    }
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
        .map_err(|e| format!("chmod {:o} {}: {e}", mode, path.display()))?;

    let meta = std::fs::metadata(path).map_err(|e| format!("stat {}: {e}", path.display()))?;
    let actual_mode = meta.permissions().mode() & 0o777;
    if meta.uid() != uid || meta.gid() != gid || actual_mode != mode {
        return Err(format!(
            "persistent path ownership/mode mismatch at {}: got {}:{} {:o}, expected {uid}:{gid} {:o}",
            path.display(),
            meta.uid(),
            meta.gid(),
            actual_mode,
            mode
        ));
    }
    Ok(())
}

#[cfg(not(unix))]
fn set_owner_mode(path: &Path, _uid: u32, _gid: u32, _mode: u32) -> Result<(), String> {
    if path.exists() {
        Ok(())
    } else {
        Err(format!("path missing: {}", path.display()))
    }
}

#[cfg(unix)]
fn ensure_non_tmpfs_directory(label: &str, path: &Path) -> Result<(), String> {
    use std::os::unix::ffi::OsStrExt;

    let canonical = std::fs::canonicalize(path)
        .map_err(|e| format!("{label} persistent source unavailable at {}: {e}", path.display()))?;
    if !canonical.is_dir() {
        return Err(format!(
            "{label} persistent source is not a directory: {}",
            canonical.display()
        ));
    }
    let c_path = std::ffi::CString::new(canonical.as_os_str().as_bytes())
        .map_err(|_| format!("{label} persistent source contains NUL"))?;
    let mut stat: libc::statfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statfs(c_path.as_ptr(), &mut stat) } != 0 {
        return Err(format!(
            "statfs {}: {}",
            canonical.display(),
            std::io::Error::last_os_error()
        ));
    }
    reject_tmpfs_type(label, &canonical, stat.f_type as u64)
}

#[cfg(not(unix))]
fn ensure_non_tmpfs_directory(_label: &str, path: &Path) -> Result<(), String> {
    if path.is_dir() {
        Ok(())
    } else {
        Err(format!("persistent source is not a directory: {}", path.display()))
    }
}

fn reject_tmpfs_type(label: &str, path: &Path, fs_type: u64) -> Result<(), String> {
    if fs_type == TMPFS_MAGIC {
        Err(format!(
            "{label} persistent source is backed by tmpfs/tmpfs_data at {}; refusing volatile storage",
            path.display()
        ))
    } else {
        Ok(())
    }
}

pub fn ensure_rootfs_layout(rootfs: &str) -> Result<(), String> {
    let root = Path::new(rootfs);
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
        "home/minis",
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
    let root = Path::new(rootfs);
    root.join("etc/os-release").is_file()
        && (root.join("bin/bash").exists()
            || root.join("usr/bin/bash").exists()
            || root.join("bin/sh").exists())
}

pub fn read_os_release(rootfs: &str) -> Option<String> {
    let text = std::fs::read_to_string(Path::new(rootfs).join("etc/os-release")).ok()?;
    let version = text
        .lines()
        .find_map(|l| l.strip_prefix("VERSION_ID="))
        .map(|s| s.trim_matches('"').to_string())?;
    Some(version)
}

pub fn is_provisioned(rootfs: &str) -> bool {
    Path::new(rootfs).join(PROVISION_MARKER).is_file()
        || Path::new(rootfs).join("usr/bin/python3").exists()
}

pub fn ensure_guest_user(rootfs: &str) -> Result<(), String> {
    ensure_guest_user_ids(rootfs, GUEST_UID, GUEST_GID)
}

pub fn ensure_guest_user_ids(rootfs: &str, uid: u32, gid: u32) -> Result<(), String> {
    let root = Path::new(rootfs);
    let passwd = format!("minis:x:{uid}:{gid}:Minis:{GUEST_HOME}:/bin/bash\n");
    let group = format!("minis:x:{gid}:\n");
    upsert_named_line(root.join("etc/passwd"), "minis:", &passwd)?;
    upsert_named_line(root.join("etc/group"), "minis:", &group)?;
    Ok(())
}

fn upsert_named_line(path: impl AsRef<Path>, prefix: &str, line: &str) -> Result<(), String> {
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
        "sessions": HOST_SESSIONS,
        "memory": HOST_MEMORY,
        "skills": HOST_SKILLS,
        "shared": HOST_SHARED,
        "home": HOST_HOME,
        "guest_workspace": GUEST_WORKSPACE,
        "guest_home": GUEST_HOME,
        "guest_uid": GUEST_UID,
        "guest_gid": GUEST_GID
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_layout(name: &str) -> PersistentLayout {
        let unique = format!(
            "minisd-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        PersistentLayout::new(std::env::temp_dir().join(unique))
    }

    #[test]
    fn persistent_layout_matches_issue_50_contract() {
        let layout = PersistentLayout::system();
        assert_eq!(layout.root(), Path::new("/data/adb/minis"));
        assert_eq!(layout.workspace(), PathBuf::from(HOST_WORKSPACE));
        assert_eq!(layout.sessions(), PathBuf::from(HOST_SESSIONS));
        assert_eq!(layout.memory(), PathBuf::from(HOST_MEMORY));
        assert_eq!(layout.skills(), PathBuf::from(HOST_SKILLS));
        assert_eq!(layout.shared(), PathBuf::from(HOST_SHARED));
        assert_eq!(layout.home(), PathBuf::from(HOST_HOME));
    }

    #[cfg(unix)]
    #[test]
    fn persistent_layout_init_is_idempotent_and_keeps_explicit_modes() {
        use std::os::unix::fs::PermissionsExt;

        let layout = temp_layout("layout-init");
        let uid = unsafe { libc::geteuid() };
        let gid = unsafe { libc::getegid() };
        layout
            .initialize_with_owners(uid, gid, uid, gid)
            .unwrap();
        std::fs::write(layout.workspace().join("keep.txt"), "persist").unwrap();
        layout
            .initialize_with_owners(uid, gid, uid, gid)
            .unwrap();

        assert_eq!(
            std::fs::read_to_string(layout.workspace().join("keep.txt")).unwrap(),
            "persist"
        );
        for (_, path) in layout.persistent_sources() {
            let mode = std::fs::metadata(path).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, PERSISTENT_DATA_MODE);
        }
        let _ = std::fs::remove_dir_all(layout.root());
    }

    #[test]
    fn tmpfs_is_rejected_fail_closed() {
        let path = Path::new("/data/adb/minis/workspace");
        assert!(reject_tmpfs_type("workspace", path, TMPFS_MAGIC).is_err());
        assert!(reject_tmpfs_type("workspace", path, 0xf2f5_2010).is_ok());
    }

    #[test]
    fn keeper_bind_sources_are_fixed_persistent_paths() {
        let sources = PersistentLayout::system().keeper_bind_sources();
        assert_eq!(sources.len(), 5);
        assert_eq!(sources[0].host, PathBuf::from(HOST_WORKSPACE));
        assert_eq!(sources[0].guest, GUEST_WORKSPACE);
        assert_eq!(sources[1].host, PathBuf::from(HOST_MEMORY));
        assert_eq!(sources[2].host, PathBuf::from(HOST_SKILLS));
        assert_eq!(sources[3].host, PathBuf::from(HOST_SHARED));
        assert_eq!(sources[4].host, PathBuf::from(HOST_HOME));
        assert_eq!(sources[4].guest, GUEST_HOME);
    }

    #[test]
    fn home_and_workspace_are_not_conflated() {
        let layout = PersistentLayout::system();
        assert_ne!(layout.home(), layout.workspace());
        assert_ne!(GUEST_HOME, GUEST_WORKSPACE);
        assert_eq!(GUEST_HOME, "/home/minis");
        assert_eq!(DEFAULT_GUEST_CWD, "/workspace");
    }

    #[cfg(unix)]
    #[test]
    fn restart_reuses_the_same_layout_without_deleting_data() {
        let first = temp_layout("restart-layout");
        let second = PersistentLayout::new(first.root().to_path_buf());
        let uid = unsafe { libc::geteuid() };
        let gid = unsafe { libc::getegid() };
        first
            .initialize_with_owners(uid, gid, uid, gid)
            .unwrap();
        std::fs::write(first.home().join(".restart-probe"), "stable").unwrap();
        second
            .initialize_with_owners(uid, gid, uid, gid)
            .unwrap();

        assert_eq!(first, second);
        assert_eq!(
            std::fs::read_to_string(second.home().join(".restart-probe")).unwrap(),
            "stable"
        );
        let _ = std::fs::remove_dir_all(first.root());
    }

    #[test]
    fn invalid_rootfs_rejected() {
        assert!(!rootfs_looks_valid("/no/such/rootfs"));
    }

    #[test]
    fn guest_identity_replaces_stale_minis_entries_and_uses_real_home() {
        let layout = temp_layout("guest-user");
        let root = layout.root().to_path_buf();
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
        assert!(passwd.contains("Minis:/home/minis:/bin/bash"));
        assert!(group.contains("minis:x:12345:"));
        assert!(passwd.contains("root:x:0:0:"));

        let _ = std::fs::remove_dir_all(root);
    }
}
