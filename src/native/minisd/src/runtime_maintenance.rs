use crate::protocol::ErrorCode;
use serde_json::{json, Value};

pub const METHOD: &str = "runtime.maintenance";

const OP_PROBE: &str = "probe";
const OP_STAGE: &str = "stage";
const OP_VERIFY: &str = "verify";
const OP_SWITCH: &str = "switch";
const OP_ROLLBACK: &str = "rollback";
const OP_RESET: &str = "reset";
const OP_READ_STATE: &str = "read_state";
const OP_WRITE_STATE: &str = "write_state";
const OP_CLEAR_STATE: &str = "clear_state";

const STATE_PENDING: &str = "pending";
const STATE_DEPLOYED: &str = "deployed";
const TARGET_CANONICAL: &str = "canonical";
const TARGET_PREVIOUS: &str = "previous";

const MAX_STATE_BYTES: usize = 64 * 1024;
const MAX_ARCHIVE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_ARCHIVE_LIST_BYTES: usize = 8 * 1024 * 1024;
const MAX_EXTRACTED_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const TOOL_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(120);
const EXTRACTION_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(600);

const REQUIRED_LAYOUT: &[&str] = &[
    "etc/os-release",
    "etc/passwd",
    "etc/group",
    "etc/minis/rootfs.json",
    "workspace",
    "memory",
    "skills",
    "shared",
    "proc",
    "sys",
    "dev",
    "tmp",
    "run",
    "var/minis",
];

const REQUIRED_REGULAR_FILES: &[&str] = &["etc/passwd", "etc/group", "etc/minis/rootfs.json"];

const REQUIRED_REAL_DIRECTORIES: &[&str] = &[
    "etc",
    "etc/minis",
    "workspace",
    "memory",
    "skills",
    "shared",
    "proc",
    "sys",
    "dev",
    "tmp",
    "run",
    "var",
    "var/minis",
];

const OPTIONAL_REAL_DIRECTORIES: &[&str] =
    &["dev/pts", "dev/shm", "mnt", "home", "home/minis", "root"];

const ALLOWED_STANDARD_ABSOLUTE_LINKS: &[(&str, &str)] = &[
    ("etc/alternatives/awk", "/usr/bin/mawk"),
    ("etc/alternatives/nawk", "/usr/bin/mawk"),
    ("etc/alternatives/pager", "/bin/more"),
    ("etc/alternatives/rmt", "/usr/sbin/rmt-tar"),
    ("etc/alternatives/which", "/usr/bin/which.debianutils"),
    ("etc/rmt", "/usr/sbin/rmt"),
    ("usr/bin/awk", "/etc/alternatives/awk"),
    ("usr/bin/nawk", "/etc/alternatives/nawk"),
    ("usr/bin/pager", "/etc/alternatives/pager"),
    ("usr/bin/which", "/etc/alternatives/which"),
    ("usr/sbin/rmt", "/etc/alternatives/rmt"),
    (
        "etc/systemd/system/multi-user.target.wants/e2scrub_reap.service",
        "/lib/systemd/system/e2scrub_reap.service",
    ),
    (
        "etc/systemd/system/timers.target.wants/apt-daily-upgrade.timer",
        "/lib/systemd/system/apt-daily-upgrade.timer",
    ),
    (
        "etc/systemd/system/timers.target.wants/apt-daily.timer",
        "/lib/systemd/system/apt-daily.timer",
    ),
    (
        "etc/systemd/system/timers.target.wants/dpkg-db-backup.timer",
        "/lib/systemd/system/dpkg-db-backup.timer",
    ),
    (
        "etc/systemd/system/timers.target.wants/e2scrub_all.timer",
        "/lib/systemd/system/e2scrub_all.timer",
    ),
    (
        "etc/systemd/system/timers.target.wants/fstrim.timer",
        "/lib/systemd/system/fstrim.timer",
    ),
    (
        "etc/systemd/system/timers.target.wants/motd-news.timer",
        "/lib/systemd/system/motd-news.timer",
    ),
    ("var/run", "/run"),
    ("var/lock", "/run/lock"),
];

pub fn handle(mock: bool, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let operation = operation(params)?;
    if mock {
        return handle_mock(operation, params);
    }

    #[cfg(unix)]
    {
        handle_unix(operation, params)
    }
    #[cfg(not(unix))]
    {
        let _ = params;
        Err((
            ErrorCode::RuntimeUnavailable,
            "runtime maintenance requires unix".into(),
        ))
    }
}

fn operation(params: &Value) -> Result<&str, (ErrorCode, String)> {
    params
        .get("operation")
        .and_then(Value::as_str)
        .filter(|operation| !operation.is_empty())
        .ok_or((
            ErrorCode::BadParams,
            "runtime maintenance operation required".into(),
        ))
}

fn handle_mock(operation: &str, params: &Value) -> Result<Value, (ErrorCode, String)> {
    match operation {
        OP_PROBE => {
            let target = target(params)?;
            Ok(json!({
                "operation": OP_PROBE,
                "target": target,
                "healthy": true,
                "code": "HEALTHY",
                "detail": "mock runtime rootfs is healthy",
                "metadata": {
                    "distro": "ubuntu",
                    "version": "24.04",
                    "release": "24.04.3",
                    "arch": "arm64",
                    "profile": "base",
                    "upstream_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                    "revision": 1,
                    "archive_sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                },
                "provisioned": true,
                "mock": true
            }))
        }
        OP_READ_STATE => {
            let name = state_name(params)?;
            Ok(json!({"operation": OP_READ_STATE, "name": name, "present": false, "mock": true}))
        }
        OP_WRITE_STATE => {
            let name = state_name(params)?;
            validate_state_content(params)?;
            Ok(json!({"operation": OP_WRITE_STATE, "name": name, "written": true, "mock": true}))
        }
        OP_CLEAR_STATE => {
            let name = state_name(params)?;
            Ok(json!({"operation": OP_CLEAR_STATE, "name": name, "cleared": true, "mock": true}))
        }
        OP_STAGE | OP_VERIFY | OP_SWITCH | OP_ROLLBACK | OP_RESET => {
            Ok(json!({"operation": operation, "ok": true, "mock": true}))
        }
        _ => Err((
            ErrorCode::BadParams,
            format!("unknown runtime maintenance operation: {operation}"),
        )),
    }
}

fn state_name(params: &Value) -> Result<&str, (ErrorCode, String)> {
    let name = params
        .get("name")
        .and_then(Value::as_str)
        .ok_or((ErrorCode::BadParams, "state name required".into()))?;
    match name {
        STATE_PENDING | STATE_DEPLOYED => Ok(name),
        _ => Err((
            ErrorCode::BadParams,
            "state name must be pending or deployed".into(),
        )),
    }
}

fn target(params: &Value) -> Result<&str, (ErrorCode, String)> {
    let target = params
        .get("target")
        .and_then(Value::as_str)
        .ok_or((ErrorCode::BadParams, "rootfs target required".into()))?;
    match target {
        TARGET_CANONICAL | TARGET_PREVIOUS => Ok(target),
        _ => Err((
            ErrorCode::BadParams,
            "rootfs target must be canonical or previous".into(),
        )),
    }
}

fn validate_state_content(params: &Value) -> Result<&str, (ErrorCode, String)> {
    let content = params
        .get("content")
        .and_then(Value::as_str)
        .ok_or((ErrorCode::BadParams, "state content required".into()))?;
    if content.is_empty() || content.len() > MAX_STATE_BYTES {
        return Err((
            ErrorCode::BadParams,
            "state content is empty or too large".into(),
        ));
    }
    if !serde_json::from_str::<Value>(content)
        .ok()
        .is_some_and(|value| value.is_object())
    {
        return Err((
            ErrorCode::BadParams,
            "state content must be a JSON object".into(),
        ));
    }
    Ok(content)
}

#[cfg(unix)]
const STAGING_ARCHIVE: &str = "/data/adb/minis/runtime/staging/ubuntu-arm64-rootfs.tar.gz";

#[cfg(unix)]
const RUNTIME_DIR: &str = "/data/adb/minis/runtime";

#[cfg(unix)]
const STAGING_DIR: &str = "/data/adb/minis/runtime/staging";

#[cfg(unix)]
const PREVIOUS_DIR: &str = "/data/adb/minis/runtime/previous";

#[cfg(unix)]
const PREVIOUS_ROOTFS: &str = "/data/adb/minis/runtime/previous/rootfs";

#[cfg(unix)]
const PENDING_FILE: &str = "/data/adb/minis/runtime/pending.json";

#[cfg(unix)]
const DEPLOYED_FILE: &str = "/data/adb/minis/runtime/deployed.json";

#[cfg(unix)]
const RUNTIME_IDENTITY_FILE: &str = "etc/minis/runtime.sha256";

#[cfg(unix)]
const APK_ASSET: &str = "assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz";

#[cfg(unix)]
static LIFECYCLE_LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();

#[cfg(unix)]
pub fn acquire_lifecycle_lock() -> Result<std::sync::MutexGuard<'static, ()>, String> {
    LIFECYCLE_LOCK
        .get_or_init(|| std::sync::Mutex::new(()))
        .lock()
        .map_err(|_| "runtime lifecycle lock is poisoned".into())
}

#[cfg(unix)]
const PM_PATH: &str = "/system/bin/pm";

#[cfg(unix)]
const UNZIP_PATH: &str = "/system/bin/unzip";

#[cfg(unix)]
const TAR_PATH: &str = "/system/bin/tar";

#[cfg(unix)]
const SHA256SUM_PATH: &str = "/system/bin/sha256sum";

#[cfg(unix)]
fn handle_unix(operation: &str, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let _guard = acquire_lifecycle_lock().map_err(|detail| (ErrorCode::Internal, detail))?;
    match operation {
        OP_PROBE => probe_rootfs_for_target(target(params)?),
        OP_STAGE => stage_from_apk(
            params
                .get("package_name")
                .and_then(Value::as_str)
                .ok_or((ErrorCode::BadParams, "package_name required".into()))?,
        ),
        OP_VERIFY => verify_staged_archive(
            params
                .get("expected_sha256")
                .and_then(Value::as_str)
                .ok_or((ErrorCode::BadParams, "expected_sha256 required".into()))?,
        ),
        OP_SWITCH => switch_rootfs(
            params
                .get("transaction_id")
                .and_then(Value::as_str)
                .ok_or((ErrorCode::BadParams, "transaction_id required".into()))?,
            params
                .get("expected_sha256")
                .and_then(Value::as_str)
                .ok_or((ErrorCode::BadParams, "expected_sha256 required".into()))?,
        ),
        OP_ROLLBACK => rollback_rootfs(),
        OP_RESET => reset_runtime(),
        OP_READ_STATE => read_state_file(state_name(params)?),
        OP_WRITE_STATE => write_state_file(state_name(params)?, validate_state_content(params)?),
        OP_CLEAR_STATE => clear_state_file(state_name(params)?),
        _ => Err((
            ErrorCode::BadParams,
            format!("unknown runtime maintenance operation: {operation}"),
        )),
    }
}

#[cfg(unix)]
fn state_path(name: &str) -> Result<&'static str, (ErrorCode, String)> {
    match name {
        STATE_PENDING => Ok(PENDING_FILE),
        STATE_DEPLOYED => Ok(DEPLOYED_FILE),
        _ => Err((
            ErrorCode::BadParams,
            "state name must be pending or deployed".into(),
        )),
    }
}

#[cfg(unix)]
fn rootfs_path(target: &str) -> Result<&'static str, (ErrorCode, String)> {
    match target {
        TARGET_CANONICAL => Ok(crate::layout::HOST_ROOTFS),
        TARGET_PREVIOUS => Ok(PREVIOUS_ROOTFS),
        _ => Err((
            ErrorCode::BadParams,
            "rootfs target must be canonical or previous".into(),
        )),
    }
}

#[cfg(unix)]
fn validate_previous_slot(path: &std::path::Path) -> Result<bool, (ErrorCode, String)> {
    match std::fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err((
            ErrorCode::NotAuthorized,
            format!("previous rootfs must not be a symlink: {}", path.display()),
        )),
        Ok(metadata) if !metadata.is_dir() => Err((
            ErrorCode::NotAuthorized,
            format!("previous rootfs must be a directory: {}", path.display()),
        )),
        Ok(_) => Ok(true),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err((
            ErrorCode::Internal,
            format!("stat previous rootfs {}: {error}", path.display()),
        )),
    }
}

#[cfg(unix)]
fn ensure_runtime_dirs() -> Result<(), (ErrorCode, String)> {
    ensure_directory(
        std::path::Path::new(crate::layout::HOST_MINIS),
        crate::layout::HOST_ROOT_MODE,
    )?;
    ensure_directory(
        std::path::Path::new(RUNTIME_DIR),
        crate::layout::HOST_RUNTIME_MODE,
    )?;
    ensure_directory(
        std::path::Path::new(STAGING_DIR),
        crate::layout::HOST_RUNTIME_MODE,
    )?;
    ensure_directory(
        std::path::Path::new(PREVIOUS_DIR),
        crate::layout::HOST_RUNTIME_MODE,
    )?;
    Ok(())
}

#[cfg(unix)]
fn ensure_directory(path: &std::path::Path, mode: u32) -> Result<(), (ErrorCode, String)> {
    use std::os::unix::fs::PermissionsExt;

    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_symlink() => {
            return Err((
                ErrorCode::NotAuthorized,
                format!("runtime path is a symlink: {}", path.display()),
            ))
        }
        Ok(meta) if !meta.is_dir() => {
            return Err((
                ErrorCode::NotAuthorized,
                format!("runtime path is not a directory: {}", path.display()),
            ))
        }
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            std::fs::create_dir(path).map_err(|e| {
                (
                    ErrorCode::Internal,
                    format!("create runtime directory {}: {e}", path.display()),
                )
            })?;
        }
        Err(error) => {
            return Err((
                ErrorCode::Internal,
                format!("stat runtime directory {}: {error}", path.display()),
            ));
        }
    }
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)).map_err(|e| {
        (
            ErrorCode::Internal,
            format!("chmod runtime directory {}: {e}", path.display()),
        )
    })
}

#[cfg(unix)]
fn safe_remove(path: &std::path::Path) -> Result<(), (ErrorCode, String)> {
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_symlink() => Err((
            ErrorCode::NotAuthorized,
            format!("refusing to remove symlink: {}", path.display()),
        )),
        Ok(meta) if meta.is_dir() => std::fs::remove_dir_all(path).map_err(|e| {
            (
                ErrorCode::Internal,
                format!("remove runtime directory {}: {e}", path.display()),
            )
        }),
        Ok(_) => std::fs::remove_file(path).map_err(|e| {
            (
                ErrorCode::Internal,
                format!("remove runtime file {}: {e}", path.display()),
            )
        }),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err((
            ErrorCode::Internal,
            format!("stat runtime path {}: {error}", path.display()),
        )),
    }
}

#[cfg(unix)]
fn read_bounded(path: &std::path::Path, max: usize) -> Result<Vec<u8>, String> {
    use std::io::Read;

    let mut file =
        std::fs::File::open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
    let mut out = Vec::new();
    let mut buffer = [0u8; 8192];
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|e| format!("read {}: {e}", path.display()))?;
        if count == 0 {
            return Ok(out);
        }
        if out.len().saturating_add(count) > max {
            return Err(format!("{} exceeds {max} bytes", path.display()));
        }
        out.extend_from_slice(&buffer[..count]);
    }
}

#[cfg(unix)]
fn read_state_file(name: &str) -> Result<Value, (ErrorCode, String)> {
    let path = std::path::Path::new(state_path(name)?);
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_symlink() => Err((
            ErrorCode::NotAuthorized,
            format!("state file is a symlink: {}", path.display()),
        )),
        Ok(meta) if !meta.is_file() => Err((
            ErrorCode::Internal,
            format!("state path is not a file: {}", path.display()),
        )),
        Ok(_) => {
            let raw = read_bounded(path, MAX_STATE_BYTES).map_err(|detail| {
                (
                    ErrorCode::Internal,
                    format!("read state file failed: {detail}"),
                )
            })?;
            let content = String::from_utf8(raw).map_err(|_| {
                (
                    ErrorCode::BadParams,
                    format!("state file is not utf-8: {}", path.display()),
                )
            })?;
            Ok(json!({"name": name, "present": true, "content": content}))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            Ok(json!({"name": name, "present": false}))
        }
        Err(error) => Err((
            ErrorCode::Internal,
            format!("stat state file {}: {error}", path.display()),
        )),
    }
}

#[cfg(unix)]
fn sync_directory(path: &std::path::Path) -> Result<(), (ErrorCode, String)> {
    std::fs::File::open(path)
        .and_then(|directory| directory.sync_all())
        .map_err(|error| {
            (
                ErrorCode::Internal,
                format!("sync runtime directory {}: {error}", path.display()),
            )
        })
}

#[cfg(unix)]
fn write_state_file(name: &str, content: &str) -> Result<Value, (ErrorCode, String)> {
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;

    ensure_runtime_dirs()?;
    let path = std::path::Path::new(state_path(name)?);
    if std::fs::symlink_metadata(path)
        .ok()
        .is_some_and(|meta| meta.file_type().is_symlink())
    {
        return Err((
            ErrorCode::NotAuthorized,
            format!("state file is a symlink: {}", path.display()),
        ));
    }
    let temp = std::path::PathBuf::from(format!("{}.tmp.{}", path.display(), std::process::id()));
    safe_remove(&temp)?;
    let mut file = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&temp)
        .map_err(|e| (ErrorCode::Internal, format!("create state temp file: {e}")))?;
    file.set_permissions(std::fs::Permissions::from_mode(
        crate::layout::PERSISTENT_FILE_MODE,
    ))
    .map_err(|e| (ErrorCode::Internal, format!("chmod state temp file: {e}")))?;
    if let Err(error) = file
        .write_all(content.as_bytes())
        .and_then(|()| file.sync_all())
    {
        let _ = safe_remove(&temp);
        return Err((ErrorCode::Internal, format!("write state file: {error}")));
    }
    drop(file);
    if let Err(error) = std::fs::rename(&temp, path) {
        let _ = safe_remove(&temp);
        return Err((ErrorCode::Internal, format!("commit state file: {error}")));
    }
    std::fs::set_permissions(
        path,
        std::fs::Permissions::from_mode(crate::layout::PERSISTENT_FILE_MODE),
    )
    .map_err(|e| (ErrorCode::Internal, format!("chmod state file: {e}")))?;
    sync_directory(std::path::Path::new(RUNTIME_DIR))?;
    Ok(json!({"name": name, "written": true}))
}

#[cfg(unix)]
fn clear_state_file(name: &str) -> Result<Value, (ErrorCode, String)> {
    let path = std::path::Path::new(state_path(name)?);
    safe_remove(path)?;
    Ok(json!({"name": name, "cleared": true}))
}

#[cfg(unix)]
fn probe_rootfs_for_target(target: &str) -> Result<Value, (ErrorCode, String)> {
    let path = rootfs_path(target)?;
    probe_rootfs(std::path::Path::new(path), target)
}

#[cfg(unix)]
fn probe_rootfs(path: &std::path::Path, target: &str) -> Result<Value, (ErrorCode, String)> {
    use std::os::unix::fs::PermissionsExt;

    let metadata = match std::fs::symlink_metadata(path) {
        Ok(meta) => meta,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(json!({
                "target": target,
                "path": path.display().to_string(),
                "healthy": false,
                "code": "MISSING",
                "detail": "Ubuntu rootfs is missing"
            }));
        }
        Err(error) => {
            return Err((
                ErrorCode::RuntimeUnavailable,
                format!("stat rootfs {}: {error}", path.display()),
            ));
        }
    };
    if metadata.file_type().is_symlink() {
        return Err((
            ErrorCode::NotAuthorized,
            format!("rootfs must not be a symlink: {}", path.display()),
        ));
    }
    if !metadata.is_dir() {
        return Ok(json!({
            "target": target,
            "path": path.display().to_string(),
            "healthy": false,
            "code": "CORRUPT",
            "detail": "rootfs path is not a directory"
        }));
    }
    for relative in REQUIRED_LAYOUT {
        let entry = path.join(relative);
        match std::fs::symlink_metadata(&entry) {
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(json!({
                    "target": target,
                    "path": path.display().to_string(),
                    "healthy": false,
                    "code": "CORRUPT",
                    "detail": format!("rootfs missing required layout entry: {relative}")
                }));
            }
            Err(error) => {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!("stat rootfs layout entry {relative}: {error}"),
                ));
            }
        }
    }
    for relative in REQUIRED_REAL_DIRECTORIES {
        let entry = path.join(relative);
        let metadata = std::fs::symlink_metadata(&entry).map_err(|error| {
            (
                ErrorCode::RootfsInvalid,
                format!("stat rootfs directory {relative}: {error}"),
            )
        })?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Ok(json!({
                "target": target,
                "path": path.display().to_string(),
                "healthy": false,
                "code": "CORRUPT",
                "detail": format!("rootfs layout entry is not a real directory: {relative}")
            }));
        }
    }
    for relative in OPTIONAL_REAL_DIRECTORIES {
        let entry = path.join(relative);
        match std::fs::symlink_metadata(&entry) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
                return Ok(json!({
                    "target": target,
                    "path": path.display().to_string(),
                    "healthy": false,
                    "code": "CORRUPT",
                    "detail": format!("optional rootfs directory is not a real directory: {relative}")
                }));
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!("stat optional rootfs directory {relative}: {error}"),
                ));
            }
        }
    }
    for relative in REQUIRED_REGULAR_FILES {
        let entry = path.join(relative);
        let metadata = std::fs::symlink_metadata(&entry).map_err(|error| {
            (
                ErrorCode::RootfsInvalid,
                format!("stat rootfs file {relative}: {error}"),
            )
        })?;
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            return Ok(json!({
                "target": target,
                "path": path.display().to_string(),
                "healthy": false,
                "code": "CORRUPT",
                "detail": format!("rootfs file is not regular: {relative}")
            }));
        }
    }
    let has_shell = ["bin/bash", "usr/bin/bash", "bin/sh"]
        .iter()
        .any(|relative| {
            std::fs::metadata(path.join(relative))
                .map(|meta| meta.is_file() && meta.permissions().mode() & 0o111 != 0)
                .unwrap_or(false)
        });
    if !has_shell {
        return Ok(json!({
            "target": target,
            "path": path.display().to_string(),
            "healthy": false,
            "code": "CORRUPT",
            "detail": "rootfs has no executable shell"
        }));
    }

    let metadata_path = path.join("etc/minis/rootfs.json");
    let metadata_file = std::fs::symlink_metadata(&metadata_path).map_err(|error| {
        (
            ErrorCode::RootfsInvalid,
            format!("stat rootfs metadata failed: {error}"),
        )
    })?;
    if metadata_file.file_type().is_symlink() || !metadata_file.is_file() {
        return Ok(json!({
            "target": target,
            "path": path.display().to_string(),
            "healthy": false,
            "code": "CORRUPT",
            "detail": "rootfs metadata is not a regular file"
        }));
    }
    let metadata_raw = read_bounded(&metadata_path, MAX_STATE_BYTES).map_err(|detail| {
        (
            ErrorCode::RootfsInvalid,
            format!("read rootfs metadata failed: {detail}"),
        )
    })?;
    let metadata: Value = serde_json::from_slice(&metadata_raw).map_err(|error| {
        (
            ErrorCode::RootfsInvalid,
            format!("rootfs metadata is invalid JSON: {error}"),
        )
    })?;
    let Some(object) = metadata.as_object() else {
        return Ok(json!({
            "target": target,
            "path": path.display().to_string(),
            "healthy": false,
            "code": "CORRUPT",
            "detail": "rootfs metadata is not an object"
        }));
    };
    let distro = object
        .get("distro")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let version = object
        .get("version")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let release = object
        .get("release")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let arch = object
        .get("arch")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let profile = object
        .get("profile")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let revision = object.get("revision").and_then(Value::as_u64).unwrap_or(0);
    let upstream = object
        .get("upstream_sha256")
        .and_then(Value::as_str)
        .unwrap_or("");
    if distro != "ubuntu"
        || !version.starts_with("24.04")
        || !release.starts_with("24.04")
        || arch != "arm64"
        || profile != "base"
        || revision == 0
        || !is_sha256(upstream)
    {
        return Ok(json!({
            "target": target,
            "path": path.display().to_string(),
            "healthy": false,
            "code": "INCOMPATIBLE",
            "detail": format!("incompatible rootfs metadata: distro={distro} version={version} release={release} arch={arch} profile={profile}"),
            "metadata": metadata
        }));
    }
    let runtime_identity = read_runtime_identity(path)?;
    let provisioned = read_provisioned_marker(path)?;
    let mut response_metadata = metadata;
    if let Some(archive_sha256) = runtime_identity.as_deref() {
        response_metadata["archive_sha256"] = Value::String(archive_sha256.to_string());
    }
    Ok(json!({
        "target": target,
        "path": path.display().to_string(),
        "healthy": true,
        "code": "HEALTHY",
        "detail": "Ubuntu rootfs metadata/layout valid",
        "metadata": response_metadata,
        "provisioned": provisioned
    }))
}

#[cfg(unix)]
fn read_runtime_identity(path: &std::path::Path) -> Result<Option<String>, (ErrorCode, String)> {
    let marker = path.join(RUNTIME_IDENTITY_FILE);
    let metadata = match std::fs::symlink_metadata(&marker) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err((
                ErrorCode::RootfsInvalid,
                format!("stat rootfs runtime identity failed: {error}"),
            ))
        }
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err((
            ErrorCode::RootfsInvalid,
            "rootfs runtime identity must be a regular file".into(),
        ));
    }
    let raw = read_bounded(&marker, 128).map_err(|detail| {
        (
            ErrorCode::RootfsInvalid,
            format!("read rootfs runtime identity failed: {detail}"),
        )
    })?;
    let value = String::from_utf8(raw).map_err(|_| {
        (
            ErrorCode::RootfsInvalid,
            "rootfs runtime identity is not utf-8".into(),
        )
    })?;
    let value = value.trim();
    if !is_sha256(value) {
        return Err((
            ErrorCode::RootfsInvalid,
            "rootfs runtime identity is not a SHA-256 digest".into(),
        ));
    }
    Ok(Some(value.to_ascii_lowercase()))
}

#[cfg(unix)]
fn read_provisioned_marker(path: &std::path::Path) -> Result<bool, (ErrorCode, String)> {
    let marker = path.join(crate::layout::PROVISION_MARKER);
    let metadata = match std::fs::symlink_metadata(&marker) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => {
            return Err((
                ErrorCode::RootfsInvalid,
                format!("stat provision marker failed: {error}"),
            ))
        }
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err((
            ErrorCode::RootfsInvalid,
            "provision marker must be a regular file".into(),
        ));
    }
    Ok(true)
}

#[cfg(unix)]
fn stage_from_apk(package_name: &str) -> Result<Value, (ErrorCode, String)> {
    validate_package_name(package_name)?;
    ensure_runtime_dirs()?;
    cleanup_staged_rootfs()?;
    let apk = resolve_apk(package_name)?;
    let archive = std::path::Path::new(STAGING_ARCHIVE);
    if std::fs::symlink_metadata(archive)
        .ok()
        .is_some_and(|meta| meta.file_type().is_symlink())
    {
        return Err((
            ErrorCode::NotAuthorized,
            format!("staged archive is a symlink: {}", archive.display()),
        ));
    }
    let temp =
        std::path::PathBuf::from(format!("{}.tmp.{}", archive.display(), std::process::id()));
    safe_remove(&temp)?;
    let mut command = fixed_command(UNZIP_PATH, "unzip");
    command.args([
        "-p",
        apk.to_str()
            .ok_or((ErrorCode::Internal, "apk path is not valid utf-8".into()))?,
        APK_ASSET,
    ]);
    let bytes = stream_child_stdout_to_file(command, &temp, MAX_ARCHIVE_BYTES, EXTRACTION_TIMEOUT)?;
    if bytes == 0 {
        let _ = safe_remove(&temp);
        return Err((
            ErrorCode::RootfsInvalid,
            "packaged rootfs archive is empty".into(),
        ));
    }
    std::fs::rename(&temp, archive).map_err(|error| {
        let _ = safe_remove(&temp);
        (
            ErrorCode::Internal,
            format!("commit staged rootfs archive: {error}"),
        )
    })?;
    sync_directory(std::path::Path::new(STAGING_DIR))?;
    Ok(json!({
        "staged": true,
        "archive": STAGING_ARCHIVE,
        "bytes": bytes
    }))
}

#[cfg(unix)]
fn cleanup_staged_rootfs() -> Result<(), (ErrorCode, String)> {
    for entry in std::fs::read_dir(STAGING_DIR).map_err(|error| {
        (
            ErrorCode::Internal,
            format!("read staging directory: {error}"),
        )
    })? {
        let entry =
            entry.map_err(|error| (ErrorCode::Internal, format!("read staging entry: {error}")))?;
        if entry.file_name().to_string_lossy().starts_with("rootfs.") {
            safe_remove(&entry.path())?;
        }
    }
    Ok(())
}

#[cfg(unix)]
fn validate_package_name(package_name: &str) -> Result<(), (ErrorCode, String)> {
    if package_name.is_empty()
        || package_name.len() > 255
        || package_name.split('.').any(|part| {
            part.is_empty()
                || !part
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
        })
    {
        return Err((
            ErrorCode::BadParams,
            "package_name is not a valid Android package name".into(),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn resolve_apk(package_name: &str) -> Result<std::path::PathBuf, (ErrorCode, String)> {
    let mut command = fixed_command(PM_PATH, "pm");
    command.args(["path", package_name]);
    let (status, output) = run_capture(command, 16 * 1024, TOOL_TIMEOUT).map_err(|detail| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("resolve installed APK failed: {detail}"),
        )
    })?;
    if !status.success() {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "package manager could not resolve the installed APK".into(),
        ));
    }
    let text = String::from_utf8(output).map_err(|_| {
        (
            ErrorCode::RuntimeUnavailable,
            "package manager returned non-utf-8 output".into(),
        )
    })?;
    let raw_path = text
        .lines()
        .map(str::trim)
        .find_map(|line| line.strip_prefix("package:"))
        .map(str::trim)
        .filter(|path| !path.is_empty() && path.starts_with('/'))
        .ok_or((
            ErrorCode::RuntimeUnavailable,
            "installed APK path is unavailable".into(),
        ))?;
    let path = std::fs::canonicalize(raw_path).map_err(|error| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("canonicalize installed APK failed: {error}"),
        )
    })?;
    if !std::fs::metadata(&path)
        .map(|metadata| metadata.is_file())
        .unwrap_or(false)
    {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "installed APK is not a regular file".into(),
        ));
    }
    Ok(path)
}

#[cfg(unix)]
fn verify_staged_archive(expected: &str) -> Result<Value, (ErrorCode, String)> {
    validate_sha256(expected)?;
    let actual = staged_archive_sha256()?;
    if actual != expected.to_ascii_lowercase() {
        return Err((
            ErrorCode::RootfsInvalid,
            format!("staged archive digest mismatch: actual={actual} expected={expected}"),
        ));
    }
    Ok(json!({
        "verified": true,
        "archive": STAGING_ARCHIVE,
        "sha256": actual
    }))
}

#[cfg(unix)]
fn staged_archive_sha256() -> Result<String, (ErrorCode, String)> {
    let path = std::path::Path::new(STAGING_ARCHIVE);
    let metadata = std::fs::symlink_metadata(path).map_err(|error| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("staged archive unavailable: {error}"),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err((
            ErrorCode::NotAuthorized,
            "staged archive must be a regular file".into(),
        ));
    }
    let mut command = fixed_command(SHA256SUM_PATH, "sha256sum");
    command.arg(STAGING_ARCHIVE);
    let (status, output) = run_capture(command, 4096, TOOL_TIMEOUT).map_err(|detail| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("hash staged archive failed: {detail}"),
        )
    })?;
    if !status.success() {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "sha256sum could not read staged archive".into(),
        ));
    }
    let actual = String::from_utf8_lossy(&output)
        .split_whitespace()
        .next()
        .unwrap_or("")
        .to_ascii_lowercase();
    if !is_sha256(&actual) {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "sha256sum returned an invalid digest".into(),
        ));
    }
    Ok(actual)
}

#[cfg(unix)]
fn switch_rootfs(transaction_id: &str, expected: &str) -> Result<Value, (ErrorCode, String)> {
    validate_transaction_id(transaction_id)?;
    validate_sha256(expected)?;
    ensure_runtime_dirs()?;
    let actual = staged_archive_sha256()?;
    if actual != expected.to_ascii_lowercase() {
        return Err((
            ErrorCode::RootfsInvalid,
            format!("staged archive digest mismatch: actual={actual} expected={expected}"),
        ));
    }

    let stage = std::path::PathBuf::from(format!("{STAGING_DIR}/rootfs.{transaction_id}"));
    safe_remove(&stage)?;
    std::fs::create_dir(&stage).map_err(|error| {
        (
            ErrorCode::Internal,
            format!("create rootfs extraction directory: {error}"),
        )
    })?;

    if let Err(error) = validate_archive_entries() {
        let _ = safe_remove(&stage);
        return Err(error);
    }
    let mut command = fixed_command(TAR_PATH, "tar");
    command.args(["-xzf", STAGING_ARCHIVE, "-C"]);
    command.arg(&stage);
    let (status, _) = run_capture(command, 64 * 1024, EXTRACTION_TIMEOUT).map_err(|detail| {
        let _ = safe_remove(&stage);
        (
            ErrorCode::RuntimeUnavailable,
            format!("extract staged rootfs failed: {detail}"),
        )
    })?;
    if !status.success() {
        let _ = safe_remove(&stage);
        return Err((
            ErrorCode::RootfsInvalid,
            "tar failed while extracting staged rootfs".into(),
        ));
    }
    if let Err(error) = write_runtime_identity(&stage, expected) {
        let _ = safe_remove(&stage);
        return Err(error);
    }
    if let Err(error) = check_extracted_size(&stage) {
        let _ = safe_remove(&stage);
        return Err(error);
    }
    let health = match probe_rootfs(&stage, "staged") {
        Ok(value) => value,
        Err(error) => {
            let _ = safe_remove(&stage);
            return Err(error);
        }
    };
    if health.get("code").and_then(Value::as_str) != Some("HEALTHY") {
        let detail = health
            .get("detail")
            .and_then(Value::as_str)
            .unwrap_or("staged rootfs is invalid");
        let _ = safe_remove(&stage);
        return Err((ErrorCode::RootfsInvalid, detail.into()));
    }

    let rootfs = std::path::Path::new(crate::layout::HOST_ROOTFS);
    let previous = std::path::Path::new(PREVIOUS_ROOTFS);
    let previous_exists = validate_previous_slot(previous)?;
    let rootfs_exists = match std::fs::symlink_metadata(rootfs) {
        Ok(metadata) if metadata.file_type().is_symlink() => {
            let _ = safe_remove(&stage);
            return Err((
                ErrorCode::NotAuthorized,
                "canonical rootfs must not be a symlink".into(),
            ));
        }
        Ok(metadata) if !metadata.is_dir() => {
            let _ = safe_remove(&stage);
            return Err((
                ErrorCode::NotAuthorized,
                "canonical rootfs must be a directory".into(),
            ));
        }
        Ok(_) => true,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
        Err(error) => {
            let _ = safe_remove(&stage);
            return Err((
                ErrorCode::Internal,
                format!("stat canonical rootfs: {error}"),
            ));
        }
    };
    if rootfs_exists {
        if !previous_exists {
            ensure_directory(previous, crate::layout::HOST_RUNTIME_MODE)?;
        }
        exchange_paths(&stage, previous).map_err(|error| {
            let _ = safe_remove(&stage);
            (
                ErrorCode::Internal,
                format!("prepare rollback slot for atomic rootfs switch: {error}"),
            )
        })?;
        sync_directory(std::path::Path::new(PREVIOUS_DIR)).map_err(|(_, detail)| {
            (
                ErrorCode::RuntimeSwitchUnknown,
                format!("sync prepared rollback slot after exchange: {detail}"),
            )
        })?;
        exchange_paths(rootfs, previous).map_err(|error| {
            (
                ErrorCode::RuntimeSwitchUnknown,
                format!("atomically switch canonical rootfs: {error}"),
            )
        })?;
        safe_remove(&stage).map_err(|(_, detail)| {
            (
                ErrorCode::RuntimeSwitchUnknown,
                format!("remove superseded rollback slot after switch: {detail}"),
            )
        })?;
    } else if let Err(error) = std::fs::rename(&stage, rootfs) {
        let _ = safe_remove(&stage);
        return Err((
            ErrorCode::Internal,
            format!("install staged rootfs: {error}"),
        ));
    } else {
        safe_remove(previous).map_err(|(_, detail)| {
            (
                ErrorCode::RuntimeSwitchUnknown,
                format!("remove stale rollback slot after initial install: {detail}"),
            )
        })?;
    }
    for path in [
        crate::layout::HOST_MINIS,
        RUNTIME_DIR,
        STAGING_DIR,
        PREVIOUS_DIR,
    ] {
        sync_directory(std::path::Path::new(path)).map_err(|(_, detail)| {
            (
                ErrorCode::RuntimeSwitchUnknown,
                format!("sync runtime layout after rootfs switch: {detail}"),
            )
        })?;
    }
    Ok(json!({
        "switched": true,
        "transaction_id": transaction_id,
        "rootfs": crate::layout::HOST_ROOTFS,
        "previous": PREVIOUS_ROOTFS
    }))
}

#[cfg(unix)]
fn write_runtime_identity(
    rootfs: &std::path::Path,
    expected: &str,
) -> Result<(), (ErrorCode, String)> {
    use std::io::Write;
    use std::os::unix::fs::PermissionsExt;

    let marker = rootfs.join(RUNTIME_IDENTITY_FILE);
    let parent = marker.parent().ok_or((
        ErrorCode::RootfsInvalid,
        "rootfs runtime identity has no parent".into(),
    ))?;
    let parent_metadata = std::fs::symlink_metadata(parent).map_err(|error| {
        (
            ErrorCode::RootfsInvalid,
            format!("stat rootfs runtime identity directory: {error}"),
        )
    })?;
    if parent_metadata.file_type().is_symlink() || !parent_metadata.is_dir() {
        return Err((
            ErrorCode::RootfsInvalid,
            "rootfs runtime identity directory must be real".into(),
        ));
    }
    if std::fs::symlink_metadata(&marker).is_ok() {
        return Err((
            ErrorCode::RootfsInvalid,
            "rootfs archive already contains a runtime identity marker".into(),
        ));
    }
    let mut file = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&marker)
        .map_err(|error| {
            (
                ErrorCode::RootfsInvalid,
                format!("create rootfs runtime identity: {error}"),
            )
        })?;
    file.set_permissions(std::fs::Permissions::from_mode(
        crate::layout::PERSISTENT_FILE_MODE,
    ))
    .map_err(|error| {
        (
            ErrorCode::RootfsInvalid,
            format!("chmod rootfs runtime identity: {error}"),
        )
    })?;
    file.write_all(expected.as_bytes())
        .and_then(|()| file.write_all(b"\n"))
        .and_then(|()| file.sync_all())
        .map_err(|error| {
            (
                ErrorCode::Internal,
                format!("write rootfs runtime identity: {error}"),
            )
        })?;
    Ok(())
}

#[cfg(unix)]
fn check_extracted_size(rootfs: &std::path::Path) -> Result<(), (ErrorCode, String)> {
    fn measure(path: &std::path::Path) -> Result<u64, (ErrorCode, String)> {
        let metadata = std::fs::symlink_metadata(path).map_err(|error| {
            (
                ErrorCode::RootfsInvalid,
                format!("stat extracted rootfs {}: {error}", path.display()),
            )
        })?;
        if metadata.file_type().is_symlink() {
            return Ok(0);
        }
        if metadata.is_file() {
            return Ok(metadata.len());
        }
        if !metadata.is_dir() {
            return Err((
                ErrorCode::RootfsInvalid,
                format!(
                    "extracted rootfs contains unsupported node: {}",
                    path.display()
                ),
            ));
        }
        let mut total = 0u64;
        for entry in std::fs::read_dir(path).map_err(|error| {
            (
                ErrorCode::RootfsInvalid,
                format!("read extracted rootfs {}: {error}", path.display()),
            )
        })? {
            let entry = entry.map_err(|error| {
                (
                    ErrorCode::RootfsInvalid,
                    format!("read extracted rootfs entry: {error}"),
                )
            })?;
            total = total.saturating_add(measure(&entry.path())?);
            if total > MAX_EXTRACTED_BYTES {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!("extracted rootfs exceeds {MAX_EXTRACTED_BYTES} bytes"),
                ));
            }
        }
        Ok(total)
    }

    measure(rootfs).map(|_| ())
}

#[cfg(unix)]
struct ArchiveEntry {
    name: String,
    kind: u8,
    link_target: Option<String>,
    size: u64,
}

#[cfg(unix)]
fn validate_archive_entries() -> Result<(), (ErrorCode, String)> {
    let mut verbose_command = fixed_command(TAR_PATH, "tar");
    verbose_command.args(["-tvzf", STAGING_ARCHIVE]);
    let (verbose_status, verbose_output) =
        run_capture(verbose_command, MAX_ARCHIVE_LIST_BYTES, EXTRACTION_TIMEOUT).map_err(
            |detail| {
                (
                    ErrorCode::RootfsInvalid,
                    format!("inspect staged rootfs archive failed: {detail}"),
                )
            },
        )?;
    if !verbose_status.success() {
        return Err((
            ErrorCode::RootfsInvalid,
            "tar could not inspect staged rootfs archive".into(),
        ));
    }
    let verbose = String::from_utf8(verbose_output).map_err(|_| {
        (
            ErrorCode::RootfsInvalid,
            "staged rootfs archive inspection is not utf-8".into(),
        )
    })?;

    let mut names_command = fixed_command(TAR_PATH, "tar");
    names_command.args(["-tzf", STAGING_ARCHIVE]);
    let (names_status, names_output) =
        run_capture(names_command, MAX_ARCHIVE_LIST_BYTES, EXTRACTION_TIMEOUT).map_err(
            |detail| {
                (
                    ErrorCode::RootfsInvalid,
                    format!("list staged rootfs archive failed: {detail}"),
                )
            },
        )?;
    if !names_status.success() {
        return Err((
            ErrorCode::RootfsInvalid,
            "tar could not list staged rootfs archive".into(),
        ));
    }
    let names = String::from_utf8(names_output).map_err(|_| {
        (
            ErrorCode::RootfsInvalid,
            "staged rootfs archive listing is not utf-8".into(),
        )
    })?;
    let verbose_lines = verbose
        .lines()
        .filter(|line| !line.trim().is_empty())
        .collect::<Vec<_>>();
    let names = names
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(normalize_archive_path)
        .collect::<Result<Vec<_>, _>>()?;
    if verbose_lines.len() != names.len() {
        return Err((
            ErrorCode::RootfsInvalid,
            "tar archive listings disagree on entry count".into(),
        ));
    }

    let mut entries = Vec::with_capacity(names.len());
    let mut seen = std::collections::HashSet::new();
    for (line, name) in verbose_lines.into_iter().zip(names) {
        let Some(name) = name else {
            if line.as_bytes().first() != Some(&b'd') {
                return Err((
                    ErrorCode::RootfsInvalid,
                    "staged rootfs archive has a non-directory root entry".into(),
                ));
            }
            continue;
        };
        if !seen.insert(name.clone()) {
            return Err((
                ErrorCode::RootfsInvalid,
                format!("staged rootfs archive contains duplicate entry: {name}"),
            ));
        }
        let kind = line.as_bytes().first().copied().ok_or((
            ErrorCode::RootfsInvalid,
            format!("staged rootfs archive has an empty verbose entry: {name}"),
        ))?;
        // Toybox prints hardlinks with a regular-file mode and a "link to"
        // suffix (or "->" on older Android), unlike GNU tar's leading `h`.
        let hardlink_separator = match kind {
            b'h' => Some(" link to "),
            b'-' if line.contains(" link to ") => Some(" link to "),
            b'-' if line.contains(" -> ") => Some(" -> "),
            _ => None,
        };
        let hardlink = hardlink_separator.is_some();
        if kind != b'd' && kind != b'-' && kind != b'l' && !hardlink {
            return Err((
                ErrorCode::RootfsInvalid,
                format!("staged rootfs archive contains unsupported node: {name}"),
            ));
        }
        let link_target = if kind == b'l' {
            Some(link_target_from_listing(line, " -> ")?)
        } else if let Some(separator) = hardlink_separator {
            Some(link_target_from_listing(line, separator)?)
        } else {
            None
        };
        let kind = if hardlink { b'h' } else { kind };
        let size = tar_listing_size(line).ok_or((
            ErrorCode::RootfsInvalid,
            format!("could not determine size for archive entry: {name}"),
        ))?;
        entries.push(ArchiveEntry {
            name,
            kind,
            link_target,
            size,
        });
    }

    let names = entries
        .iter()
        .map(|entry| entry.name.as_str())
        .collect::<std::collections::HashSet<_>>();
    let regular_names = entries
        .iter()
        .filter(|entry| entry.kind == b'-')
        .map(|entry| entry.name.as_str())
        .collect::<std::collections::HashSet<_>>();
    let mut symlink_targets = std::collections::HashMap::new();
    for entry in &entries {
        if entry.kind == b'l' {
            let target = entry.link_target.as_deref().ok_or((
                ErrorCode::RootfsInvalid,
                format!("symlink target is missing: {}", entry.name),
            ))?;
            symlink_targets.insert(
                entry.name.clone(),
                normalize_link_target(&entry.name, target)?,
            );
        }
    }
    for entry in &entries {
        if let Some((parent, _)) = entry.name.rsplit_once('/') {
            resolve_archive_path(parent, &symlink_targets)?;
        }
    }
    let mut extracted_bytes = 0u64;
    for entry in &entries {
        if let Some(target) = entry.link_target.as_deref() {
            let normalized = if entry.kind == b'l' {
                symlink_targets.get(&entry.name).cloned().ok_or((
                    ErrorCode::RootfsInvalid,
                    format!("symlink target is missing: {}", entry.name),
                ))?
            } else if entry.kind == b'h' {
                normalize_archive_path(target)?.ok_or((
                    ErrorCode::RootfsInvalid,
                    format!("hardlink target is the archive root: {}", entry.name),
                ))?
            } else {
                target.to_string()
            };
            if entry.kind == b'l' {
                if !normalized.starts_with('/') {
                    resolve_archive_path(&normalized, &symlink_targets)?;
                }
            } else if entry.kind == b'h'
                && (!names.contains(normalized.as_str())
                    || !regular_names.contains(normalized.as_str()))
            {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!(
                        "hardlink target is not a regular archive file: {} -> {target}",
                        entry.name
                    ),
                ));
            }
        }
        if entry.kind == b'-' {
            extracted_bytes = extracted_bytes.saturating_add(entry.size);
            if extracted_bytes > MAX_EXTRACTED_BYTES {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!("rootfs archive expands beyond {MAX_EXTRACTED_BYTES} bytes"),
                ));
            }
        }
    }
    Ok(())
}

#[cfg(unix)]
fn resolve_archive_path(
    path: &str,
    symlink_targets: &std::collections::HashMap<String, String>,
) -> Result<(), (ErrorCode, String)> {
    if path.is_empty() {
        return Ok(());
    }
    let mut components = path.split('/').map(str::to_string).collect::<Vec<_>>();
    let mut visited = std::collections::HashSet::new();
    loop {
        let mut replaced = false;
        for index in 1..=components.len() {
            let prefix = components[..index].join("/");
            let Some(target) = symlink_targets.get(&prefix) else {
                continue;
            };
            if target.starts_with('/') {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!("archive path resolves through an absolute link: {path}"),
                ));
            }
            if !visited.insert(prefix.clone()) {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!("archive path contains a symlink cycle: {path}"),
                ));
            }
            let mut replacement = target.split('/').map(str::to_string).collect::<Vec<_>>();
            replacement.extend_from_slice(&components[index..]);
            components = replacement;
            replaced = true;
            break;
        }
        if !replaced {
            return Ok(());
        }
    }
}

#[cfg(unix)]
fn normalize_archive_path(raw: &str) -> Result<Option<String>, (ErrorCode, String)> {
    let mut value = raw.trim();
    while let Some(stripped) = value.strip_prefix("./") {
        value = stripped;
    }
    value = value.trim_end_matches('/');
    if value.is_empty() || value == "." {
        return Ok(None);
    }
    if value.starts_with('/') || value.contains('\0') {
        return Err((
            ErrorCode::RootfsInvalid,
            format!("staged rootfs archive contains unsafe entry: {raw}"),
        ));
    }
    let mut parts = Vec::new();
    for part in value.split('/') {
        if part.is_empty() || part == ".." || part == "." {
            return Err((
                ErrorCode::RootfsInvalid,
                format!("staged rootfs archive contains unsafe entry: {raw}"),
            ));
        }
        if part.bytes().any(|byte| byte.is_ascii_control()) {
            return Err((
                ErrorCode::RootfsInvalid,
                format!("staged rootfs archive contains unsafe entry: {raw}"),
            ));
        }
        parts.push(part);
    }
    Ok(Some(parts.join("/")))
}

#[cfg(unix)]
fn normalize_link_target(entry: &str, target: &str) -> Result<String, (ErrorCode, String)> {
    if target.is_empty() || target.contains('\0') {
        return Err((
            ErrorCode::RootfsInvalid,
            format!("staged rootfs archive contains unsafe link: {entry} -> {target}"),
        ));
    }
    if target.starts_with('/') {
        if crate::layout::VAR_MINIS_LINKS
            .iter()
            .any(|(path, allowed)| *path == entry && *allowed == target)
            || ALLOWED_STANDARD_ABSOLUTE_LINKS
                .iter()
                .any(|(path, allowed)| *path == entry && *allowed == target)
        {
            return Ok(target.to_string());
        }
        return Err((
            ErrorCode::RootfsInvalid,
            format!("staged rootfs archive contains unsafe link: {entry} -> {target}"),
        ));
    }
    let mut parts = entry.split('/').collect::<Vec<_>>();
    parts.pop();
    for component in target.split('/') {
        if component.is_empty() || component == "." {
            continue;
        }
        if component.bytes().any(|byte| byte.is_ascii_control()) {
            return Err((
                ErrorCode::RootfsInvalid,
                format!("staged rootfs archive contains unsafe link: {entry} -> {target}"),
            ));
        }
        if component == ".." {
            if parts.pop().is_none() {
                return Err((
                    ErrorCode::RootfsInvalid,
                    format!("staged rootfs archive contains unsafe link: {entry} -> {target}"),
                ));
            }
        } else {
            parts.push(component);
        }
    }
    if parts.is_empty() {
        return Err((
            ErrorCode::RootfsInvalid,
            format!("staged rootfs archive contains unsafe link: {entry} -> {target}"),
        ));
    }
    Ok(parts.join("/"))
}

#[cfg(unix)]
fn link_target_from_listing(line: &str, separator: &str) -> Result<String, (ErrorCode, String)> {
    line.rsplit_once(separator)
        .map(|(_, target)| target.trim().to_string())
        .filter(|target| !target.is_empty())
        .ok_or((
            ErrorCode::RootfsInvalid,
            "staged rootfs archive link target is missing".into(),
        ))
}

#[cfg(unix)]
fn tar_listing_size(line: &str) -> Option<u64> {
    let fields = line.split_whitespace().collect::<Vec<_>>();
    fields.windows(2).find_map(|window| {
        if is_tar_date(window[1]) {
            window[0].parse::<u64>().ok()
        } else {
            None
        }
    })
}

#[cfg(unix)]
fn is_tar_date(value: &str) -> bool {
    value.len() == 10
        && value.as_bytes()[4] == b'-'
        && value.as_bytes()[7] == b'-'
        && value
            .bytes()
            .enumerate()
            .all(|(index, byte)| matches!(index, 4 | 7) || byte.is_ascii_digit())
}

#[cfg(unix)]
fn exchange_paths(first: &std::path::Path, second: &std::path::Path) -> std::io::Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;

    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        let first = CString::new(first.as_os_str().as_bytes())
            .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
        let second = CString::new(second.as_os_str().as_bytes())
            .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
        let result = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                libc::AT_FDCWD,
                first.as_ptr(),
                libc::AT_FDCWD,
                second.as_ptr(),
                2u32,
            )
        };
        if result == 0 {
            Ok(())
        } else {
            Err(std::io::Error::last_os_error())
        }
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        let _ = (first, second);
        Err(std::io::Error::from_raw_os_error(libc::ENOSYS))
    }
}

#[cfg(unix)]
fn rollback_rootfs() -> Result<Value, (ErrorCode, String)> {
    ensure_runtime_dirs()?;
    let rootfs = std::path::Path::new(crate::layout::HOST_ROOTFS);
    let previous = std::path::Path::new(PREVIOUS_ROOTFS);
    let previous_meta = std::fs::symlink_metadata(previous).map_err(|error| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("previous rootfs unavailable: {error}"),
        )
    })?;
    if previous_meta.file_type().is_symlink() || !previous_meta.is_dir() {
        return Err((
            ErrorCode::NotAuthorized,
            "previous rootfs must be a real directory".into(),
        ));
    }
    let rootfs_exists = match std::fs::symlink_metadata(rootfs) {
        Ok(metadata) if metadata.file_type().is_symlink() => {
            return Err((
                ErrorCode::NotAuthorized,
                "canonical rootfs must not be a symlink".into(),
            ));
        }
        Ok(metadata) if !metadata.is_dir() => {
            return Err((
                ErrorCode::NotAuthorized,
                "canonical rootfs must be a real directory".into(),
            ));
        }
        Ok(_) => true,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
        Err(error) => {
            return Err((
                ErrorCode::Internal,
                format!("stat canonical rootfs: {error}"),
            ));
        }
    };
    if rootfs_exists {
        exchange_paths(rootfs, previous).map_err(|error| {
            (
                ErrorCode::Internal,
                format!("atomically rollback rootfs: {error}"),
            )
        })?;
    } else {
        std::fs::rename(previous, rootfs).map_err(|error| {
            (
                ErrorCode::Internal,
                format!("restore previous rootfs failed: {error}"),
            )
        })?;
    }
    sync_directory(std::path::Path::new(crate::layout::HOST_MINIS))?;
    sync_directory(std::path::Path::new(RUNTIME_DIR))?;
    sync_directory(std::path::Path::new(PREVIOUS_DIR))?;
    Ok(json!({
        "rolled_back": true,
        "rootfs": crate::layout::HOST_ROOTFS,
        "previous": PREVIOUS_ROOTFS
    }))
}

#[cfg(unix)]
fn reset_runtime() -> Result<Value, (ErrorCode, String)> {
    ensure_runtime_dirs()?;
    for path in [
        crate::layout::HOST_ROOTFS,
        STAGING_DIR,
        PREVIOUS_DIR,
        PENDING_FILE,
        DEPLOYED_FILE,
    ] {
        safe_remove(std::path::Path::new(path))?;
    }
    ensure_runtime_dirs()?;
    sync_directory(std::path::Path::new(crate::layout::HOST_MINIS))?;
    sync_directory(std::path::Path::new(RUNTIME_DIR))?;
    Ok(json!({
        "reset": true,
        "rootfs": crate::layout::HOST_ROOTFS,
        "runtime": RUNTIME_DIR
    }))
}

#[cfg(unix)]
fn validate_transaction_id(transaction_id: &str) -> Result<(), (ErrorCode, String)> {
    if transaction_id.is_empty()
        || transaction_id.len() > 128
        || !transaction_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        return Err((
            ErrorCode::BadParams,
            "transaction_id contains invalid characters".into(),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn validate_sha256(value: &str) -> Result<(), (ErrorCode, String)> {
    if !is_sha256(value) {
        return Err((
            ErrorCode::BadParams,
            "expected_sha256 must be a 64-character hexadecimal digest".into(),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn is_sha256(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

#[cfg(unix)]
fn fixed_command(path: &str, toybox_name: &str) -> std::process::Command {
    let mut command = if std::path::Path::new(path).is_file() {
        std::process::Command::new(path)
    } else {
        let mut command = std::process::Command::new("/system/bin/toybox");
        command.arg(toybox_name);
        command
    };
    command.env_clear();
    command.env("PATH", "/system/bin:/system/xbin");
    command
}

#[cfg(unix)]
fn put_in_process_group(command: &mut std::process::Command) {
    use std::os::unix::process::CommandExt;

    unsafe {
        command.pre_exec(|| {
            if libc::setpgid(0, 0) != 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
}

#[cfg(unix)]
fn kill_process_group(pid: u32) {
    let pid = pid as libc::pid_t;
    unsafe {
        libc::kill(-pid, libc::SIGKILL);
        libc::kill(pid, libc::SIGKILL);
    }
}

#[cfg(unix)]
fn run_capture(
    mut command: std::process::Command,
    max_output: usize,
    timeout: std::time::Duration,
) -> Result<(std::process::ExitStatus, Vec<u8>), String> {
    use std::io::Read;
    use std::process::Stdio;

    command.stdout(Stdio::piped()).stderr(Stdio::null());
    put_in_process_group(&mut command);
    let mut child = command
        .spawn()
        .map_err(|e| format!("spawn fixed tool: {e}"))?;
    let stdout = child.stdout.take().ok_or_else(|| {
        kill_process_group(child.id());
        let _ = child.wait();
        "fixed tool stdout unavailable".to_string()
    })?;
    let reader = std::thread::spawn(move || {
        let mut stdout = stdout;
        let mut output = Vec::new();
        let mut buffer = [0u8; 8192];
        loop {
            let count = stdout
                .read(&mut buffer)
                .map_err(|e| format!("read fixed tool output: {e}"))?;
            if count == 0 {
                return Ok(output);
            }
            if output.len().saturating_add(count) > max_output {
                return Err(format!("fixed tool output exceeds {max_output} bytes"));
            }
            output.extend_from_slice(&buffer[..count]);
        }
    });
    let started = std::time::Instant::now();
    let mut reader = Some(reader);
    let mut output = None;
    loop {
        if output.is_none() && reader.as_ref().is_some_and(|handle| handle.is_finished()) {
            let handle = reader.take().expect("reader handle exists");
            let result = handle
                .join()
                .map_err(|_| "fixed tool output reader failed".to_string())?;
            match result {
                Ok(bytes) => output = Some(bytes),
                Err(error) => {
                    kill_process_group(child.id());
                    let _ = child.wait();
                    return Err(error);
                }
            }
        }
        let status = match child.try_wait() {
            Ok(status) => status,
            Err(error) => {
                kill_process_group(child.id());
                let _ = child.wait();
                return Err(format!("wait fixed tool: {error}"));
            }
        };
        if let Some(status) = status {
            let captured = match output {
                Some(bytes) => bytes,
                None => reader
                    .take()
                    .expect("reader handle exists")
                    .join()
                    .map_err(|_| "fixed tool output reader failed".to_string())?
                    .map_err(|error| error.to_string())?,
            };
            return Ok((status, captured));
        }
        if started.elapsed() >= timeout {
            kill_process_group(child.id());
            let _ = child.wait();
            if let Some(handle) = reader.take() {
                let _ = handle.join();
            }
            return Err(format!("fixed tool timed out after {}s", timeout.as_secs()));
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
}

#[cfg(unix)]
fn stream_child_stdout_to_file(
    mut command: std::process::Command,
    destination: &std::path::Path,
    max_bytes: u64,
    timeout: std::time::Duration,
) -> Result<u64, (ErrorCode, String)> {
    use std::io::{Read, Write};
    use std::os::unix::fs::PermissionsExt;
    use std::process::Stdio;

    let file = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(destination)
        .map_err(|e| {
            (
                ErrorCode::Internal,
                format!("create archive temp file: {e}"),
            )
        })?;
    file.set_permissions(std::fs::Permissions::from_mode(
        crate::layout::PERSISTENT_FILE_MODE,
    ))
    .map_err(|e| (ErrorCode::Internal, format!("chmod archive temp file: {e}")))?;
    command.stdout(Stdio::piped()).stderr(Stdio::null());
    put_in_process_group(&mut command);
    let mut child = command.spawn().map_err(|e| {
        let _ = safe_remove(destination);
        (
            ErrorCode::RuntimeUnavailable,
            format!("spawn archive tool: {e}"),
        )
    })?;
    let stdout = child.stdout.take().ok_or_else(|| {
        kill_process_group(child.id());
        let _ = child.wait();
        let _ = safe_remove(destination);
        (
            ErrorCode::RuntimeUnavailable,
            "archive tool stdout unavailable".into(),
        )
    })?;
    let reader = std::thread::spawn(move || {
        let mut stdout = stdout;
        let mut file = file;
        let mut buffer = [0u8; 32 * 1024];
        let mut total = 0u64;
        loop {
            let count = stdout
                .read(&mut buffer)
                .map_err(|e| format!("read archive output: {e}"))?;
            if count == 0 {
                file.sync_all().map_err(|e| format!("sync archive: {e}"))?;
                return Ok(total);
            }
            total = total.saturating_add(count as u64);
            if total > max_bytes {
                return Err(format!("archive exceeds {max_bytes} bytes"));
            }
            file.write_all(&buffer[..count])
                .map_err(|e| format!("write archive temp file: {e}"))?;
        }
    });
    let started = std::time::Instant::now();
    let mut reader = Some(reader);
    let mut bytes = None;
    loop {
        if bytes.is_none() && reader.as_ref().is_some_and(|handle| handle.is_finished()) {
            let handle = reader.take().expect("reader handle exists");
            match handle
                .join()
                .map_err(|_| (ErrorCode::Internal, "archive output reader failed".into()))?
            {
                Ok(count) => bytes = Some(count),
                Err(error) => {
                    kill_process_group(child.id());
                    let _ = child.wait();
                    let _ = safe_remove(destination);
                    return Err((ErrorCode::RootfsInvalid, error));
                }
            }
        }
        let status = match child.try_wait() {
            Ok(status) => status,
            Err(error) => {
                kill_process_group(child.id());
                let _ = child.wait();
                let _ = safe_remove(destination);
                return Err((
                    ErrorCode::RuntimeUnavailable,
                    format!("wait archive tool: {error}"),
                ));
            }
        };
        if let Some(status) = status {
            let bytes = match bytes {
                Some(count) => count,
                None => reader
                    .take()
                    .expect("reader handle exists")
                    .join()
                    .map_err(|_| (ErrorCode::Internal, "archive output reader failed".into()))?
                    .map_err(|error| (ErrorCode::RootfsInvalid, error))?,
            };
            if !status.success() {
                let _ = safe_remove(destination);
                return Err((
                    ErrorCode::RuntimeUnavailable,
                    "archive tool could not read the packaged asset".into(),
                ));
            }
            return Ok(bytes);
        }
        if started.elapsed() >= timeout {
            kill_process_group(child.id());
            let _ = child.wait();
            if let Some(handle) = reader.take() {
                let _ = handle.join();
            }
            let _ = safe_remove(destination);
            return Err((
                ErrorCode::RuntimeUnavailable,
                format!("archive tool timed out after {}s", timeout.as_secs()),
            ));
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn operation_requires_known_state_names() {
        assert!(state_name(&json!({"name": "pending"})).is_ok());
        assert!(state_name(&json!({"name": "rootfs"})).is_err());
        assert!(operation(&json!({})).is_err());
    }

    #[test]
    fn state_content_must_be_a_small_json_object() {
        assert!(validate_state_content(&json!({"content": "{\"ok\":true}"})).is_ok());
        assert!(validate_state_content(&json!({"content": "[]"})).is_err());
        assert!(validate_state_content(&json!({"content": "not-json"})).is_err());
    }

    #[test]
    fn mock_maintenance_is_structured() {
        let value = handle(true, &json!({"operation": "switch"})).unwrap();
        assert_eq!(value["operation"], "switch");
        assert_eq!(value["mock"], true);
    }

    #[cfg(unix)]
    #[test]
    fn package_and_transaction_inputs_reject_shell_syntax() {
        assert!(validate_package_name("com.example.app").is_ok());
        assert!(validate_package_name("com.example;su").is_err());
        assert!(validate_transaction_id("tx-1_2.3").is_ok());
        assert!(validate_transaction_id("../root").is_err());
        assert!(validate_sha256(&"ab".repeat(32)).is_ok());
        assert!(validate_sha256("short").is_err());
    }

    #[cfg(unix)]
    #[test]
    fn archive_paths_and_links_are_bounded_to_root() {
        assert_eq!(
            normalize_archive_path("./etc/minis/").unwrap(),
            Some("etc/minis".into())
        );
        assert!(normalize_archive_path("/data/adb/minis").is_err());
        assert!(normalize_archive_path("etc/../outside").is_err());
        assert_eq!(
            normalize_link_target("bin/sh", "../usr/bin/sh").unwrap(),
            "usr/bin/sh"
        );
        assert!(normalize_link_target("bin", "../../outside").is_err());
        assert_eq!(
            normalize_link_target("var/minis/workspace", "/workspace").unwrap(),
            "/workspace"
        );
        assert_eq!(normalize_link_target("var/run", "/run").unwrap(), "/run");
        assert!(normalize_link_target("etc/minis/escape", "/data/adb/minis/workspace").is_err());
        let mut links = std::collections::HashMap::new();
        links.insert("var/run".to_string(), "/run".to_string());
        assert!(resolve_archive_path("var/run/lock", &links).is_err());
        assert_eq!(
            tar_listing_size("-rw-r--r-- root/root 1234 2026-09-01 00:00 file"),
            Some(1234)
        );
        assert_eq!(
            link_target_from_listing(
                "-rw-r--r-- root/root 0 2026-09-01 00:00 usr/bin/copy -> usr/bin/source",
                " -> "
            )
            .unwrap(),
            "usr/bin/source"
        );
    }
}
