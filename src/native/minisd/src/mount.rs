use crate::protocol::ErrorCode;
use crate::state::AppState;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[cfg(unix)]
use std::cmp::min;
#[cfg(unix)]
use std::fs::{self, File, OpenOptions};
#[cfg(unix)]
use std::io::{Read, Seek, SeekFrom, Write};
#[cfg(unix)]
use std::os::fd::AsRawFd;
#[cfg(unix)]
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
#[cfg(unix)]
use std::path::Path;
#[cfg(unix)]
use std::time::UNIX_EPOCH;

pub const GUEST_MOUNTS_ROOT: &str = "/var/minis/mounts";
const MAX_MOUNTS: usize = 10;
const MAX_NAME_BYTES: usize = 64;
const MAX_SEGMENT_BYTES: usize = 255;
const MAX_PATH_BYTES: usize = 4096;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct MountSpec {
    #[serde(rename = "id")]
    pub entry_id: String,
    pub name: String,
    pub volume: String,
    #[serde(rename = "path_segments")]
    pub path_segments: Vec<String>,
    pub access: String,
}

#[derive(Debug, Clone)]
pub struct ExternalPath {
    pub path: std::path::PathBuf,
    pub root: std::path::PathBuf,
    pub read_only: bool,
}

pub fn parse_snapshot(
    params: &Value,
) -> Result<(Vec<MountSpec>, String, String), (ErrorCode, String)> {
    let object = params.as_object().ok_or((
        ErrorCode::BadParams,
        "mount.reconcile params must be an object".into(),
    ))?;
    reject_unknown_keys(object.keys().map(String::as_str), &["mounts"])?;
    let mounts = object
        .get("mounts")
        .and_then(Value::as_array)
        .ok_or((ErrorCode::BadParams, "mounts array required".into()))?;
    if mounts.len() > MAX_MOUNTS {
        return Err((
            ErrorCode::BadParams,
            format!("at most {MAX_MOUNTS} mounts allowed"),
        ));
    }

    let mut specs = Vec::with_capacity(mounts.len());
    for value in mounts {
        let object = value
            .as_object()
            .ok_or((ErrorCode::BadParams, "mount entry must be an object".into()))?;
        reject_unknown_keys(
            object.keys().map(String::as_str),
            &["id", "name", "volume", "path_segments", "access"],
        )?;
        let entry_id = object
            .get("id")
            .and_then(Value::as_str)
            .ok_or((ErrorCode::BadParams, "mount id required".into()))?;
        if !is_uuid(entry_id) {
            return Err((ErrorCode::BadParams, "mount id must be a UUID".into()));
        }
        let name = object
            .get("name")
            .and_then(Value::as_str)
            .ok_or((ErrorCode::BadParams, "mount name required".into()))?;
        validate_name(name)?;
        let volume = object
            .get("volume")
            .and_then(Value::as_str)
            .ok_or((ErrorCode::BadParams, "mount volume required".into()))?;
        let volume = validate_volume(volume)?;
        let path_segments = object
            .get("path_segments")
            .and_then(Value::as_array)
            .ok_or((
                ErrorCode::BadParams,
                "mount path_segments array required".into(),
            ))?
            .iter()
            .map(|segment| {
                let segment = segment.as_str().ok_or((
                    ErrorCode::BadParams,
                    "mount path segment must be a string".into(),
                ))?;
                validate_segment(segment)?;
                Ok(segment.to_string())
            })
            .collect::<Result<Vec<_>, (ErrorCode, String)>>()?;
        let access = object
            .get("access")
            .and_then(Value::as_str)
            .ok_or((ErrorCode::BadParams, "mount access required".into()))?;
        if access != "ro" && access != "rw" {
            return Err((ErrorCode::BadParams, "mount access must be ro or rw".into()));
        }
        let spec = MountSpec {
            entry_id: entry_id.to_ascii_lowercase(),
            name: name.to_string(),
            volume,
            path_segments,
            access: access.to_string(),
        };
        if specs.iter().any(|item: &MountSpec| item.name == spec.name) {
            return Err((ErrorCode::BadParams, "mount names must be unique".into()));
        }
        if specs
            .iter()
            .any(|item: &MountSpec| item.entry_id == spec.entry_id)
        {
            return Err((ErrorCode::BadParams, "mount ids must be unique".into()));
        }
        specs.push(spec);
    }

    // Names are the guest namespace identity. Sorting by name makes the
    // digest independent of JSON array order while preserving the full
    // snapshot semantics (one bad entry still rejects the entire snapshot).
    specs.sort_by(|a, b| a.name.cmp(&b.name));
    let canonical = serde_json::to_string(&json!({ "mounts": specs })).map_err(|error| {
        (
            ErrorCode::Internal,
            format!("encode mount snapshot: {error}"),
        )
    })?;
    let digest = snapshot_digest(canonical.as_bytes());
    let parsed = serde_json::from_str::<Value>(&canonical).map_err(|error| {
        (
            ErrorCode::Internal,
            format!("decode canonical mount snapshot: {error}"),
        )
    })?;
    let specs = parsed
        .get("mounts")
        .cloned()
        .and_then(|value| serde_json::from_value(value).ok())
        .ok_or((
            ErrorCode::Internal,
            "canonical mount snapshot lost mounts".into(),
        ))?;
    Ok((specs, canonical, digest))
}

pub fn is_external_path(path: &str) -> bool {
    path == GUEST_MOUNTS_ROOT || path.starts_with("/var/minis/mounts/")
}

/// Return true when a workspace.file request targets the broker-owned external
/// mount namespace. The App must not resolve these paths to a host File.
pub fn request_has_external_path(params: &Value) -> bool {
    ["path", "source", "destination"].iter().any(|key| {
        params
            .get(*key)
            .and_then(Value::as_str)
            .is_some_and(is_external_path)
    })
}

pub fn resolve_external_path(
    state: &AppState,
    raw: &str,
) -> Result<Option<ExternalPath>, (ErrorCode, String)> {
    if !is_external_path(raw) {
        return Ok(None);
    }
    if raw == GUEST_MOUNTS_ROOT {
        return Ok(None);
    }
    if !state.ubuntu.external_mount_verified {
        return Err((
            ErrorCode::MountAttestationRequired,
            "external mount snapshot must be re-attested after broker or keeper restart".into(),
        ));
    }
    if raw.len() > MAX_PATH_BYTES || raw.contains('\0') || raw.contains('\\') || raw.contains("//")
    {
        return Err((ErrorCode::BadParams, "invalid external mount path".into()));
    }
    let parts = raw
        .strip_prefix("/var/minis/mounts/")
        .unwrap_or_default()
        .split('/')
        .collect::<Vec<_>>();
    let name = parts.first().copied().unwrap_or_default();
    validate_name(name)?;
    let spec = state
        .external_mounts
        .iter()
        .find(|spec| spec.name == name)
        .ok_or((
            ErrorCode::RuntimeUnavailable,
            "external mount is not active".into(),
        ))?;
    let mut relative = Vec::with_capacity(parts.len().saturating_sub(1));
    for part in parts.iter().skip(1) {
        validate_segment(part)?;
        relative.push(*part);
    }
    let root = source_path(spec, app_uid(state))?;
    reject_symlink_components(&root, &relative)?;
    let path = relative
        .iter()
        .fold(root.clone(), |path, part| path.join(part));
    Ok(Some(ExternalPath {
        path,
        root,
        read_only: spec.access == "ro",
    }))
}

#[cfg(unix)]
const MAX_READ_BYTES: usize = 512 * 1024;
#[cfg(unix)]
const MAX_WRITE_BYTES: usize = 48 * 1024;
#[cfg(unix)]
const MAX_LIST_ENTRIES: usize = 500;
#[cfg(unix)]
const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Execute file operations for `/var/minis/mounts/**` from the broker side.
/// This is deliberately separate from the persistent workspace resolver:
/// external storage keeps its original ownership and mode, and never receives
/// App/session `chown` or `chmod` calls.
#[cfg(unix)]
pub fn handle_file(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let operation = params
        .get("operation")
        .and_then(Value::as_str)
        .ok_or((ErrorCode::BadParams, "operation required".into()))?;
    if state.mock {
        return Ok(json!({"mock": true, "operation": operation}));
    }
    if !state.ubuntu.external_mount_verified {
        return Err((
            ErrorCode::MountAttestationRequired,
            "external mount snapshot must be re-attested after broker or keeper restart".into(),
        ));
    }
    match operation {
        "read" => external_read(state, params),
        "write" | "append" => external_write(state, params, operation == "append"),
        "mkdir" => external_mkdir(state, params),
        "list" => external_list(state, params),
        "info" => external_info(state, params),
        "delete" => external_delete(state, params),
        "copy" => external_copy(state, params),
        "move" => external_move(state, params),
        _ => Err((
            ErrorCode::BadParams,
            format!("unsupported external mount file operation: {operation}"),
        )),
    }
}

#[cfg(not(unix))]
pub fn handle_file(_state: &AppState, _params: &Value) -> Result<Value, (ErrorCode, String)> {
    Err((
        ErrorCode::RuntimeUnavailable,
        "external mount file operations require unix".into(),
    ))
}

#[cfg(unix)]
fn external_read(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let raw = required_path(params, "path")?;
    let resolved = resolve_external_path(state, raw)?.ok_or((
        ErrorCode::BadParams,
        "external read requires a mounted path".into(),
    ))?;
    let metadata = regular_metadata(&resolved.path, "read file")?;
    let offset = params.get("offset").and_then(Value::as_u64).unwrap_or(0);
    let requested = params
        .get("length")
        .and_then(Value::as_u64)
        .unwrap_or(MAX_READ_BYTES as u64);
    if requested == 0 || requested > MAX_READ_BYTES as u64 {
        return Err((
            ErrorCode::BadParams,
            format!("length must be between 1 and {MAX_READ_BYTES}"),
        ));
    }
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&resolved.path)
        .map_err(|error| io_error("open external file", error))?;
    file.seek(SeekFrom::Start(offset))
        .map_err(|error| io_error("seek external file", error))?;
    let mut bytes = Vec::with_capacity(min(requested as usize, MAX_READ_BYTES));
    file.take(requested)
        .read_to_end(&mut bytes)
        .map_err(|error| io_error("read external file", error))?;
    let read = bytes.len() as u64;
    Ok(json!({
        "path": raw,
        "data_base64": encode_base64(&bytes),
        "offset": offset,
        "bytes": read,
        "total_bytes": metadata.len(),
        "eof": offset.saturating_add(read) >= metadata.len(),
    }))
}

#[cfg(unix)]
fn external_write(
    state: &AppState,
    params: &Value,
    append: bool,
) -> Result<Value, (ErrorCode, String)> {
    let raw = required_path(params, "path")?;
    let resolved = resolve_external_path(state, raw)?.ok_or((
        ErrorCode::BadParams,
        "external write requires a mounted file path".into(),
    ))?;
    ensure_writable(&resolved)?;
    if resolved.path == resolved.root {
        return Err((
            ErrorCode::PolicyDenied,
            "cannot write an external mount root".into(),
        ));
    }
    let encoded = params
        .get("data_base64")
        .and_then(Value::as_str)
        .ok_or((ErrorCode::BadParams, "data_base64 required".into()))?;
    let bytes = decode_base64(encoded)?;
    if bytes.len() > MAX_WRITE_BYTES {
        return Err((
            ErrorCode::BadParams,
            format!("write chunk exceeds {MAX_WRITE_BYTES} bytes"),
        ));
    }
    let parent = resolved.path.parent().ok_or((
        ErrorCode::PolicyDenied,
        "external file has no parent".into(),
    ))?;
    if params
        .get("create_dirs")
        .and_then(Value::as_bool)
        .unwrap_or(false)
    {
        ensure_external_parent_dirs(parent, &resolved.root)?;
    } else if !parent.is_dir() {
        return Err((
            ErrorCode::RuntimeUnavailable,
            format!("parent directory is missing: {}", parent.display()),
        ));
    }
    reject_existing_symlink(&resolved.path, "external file")?;
    if fs::symlink_metadata(&resolved.path)
        .map(|metadata| metadata.is_dir())
        .unwrap_or(false)
    {
        return Err((
            ErrorCode::BadParams,
            format!("external path is a directory: {}", resolved.path.display()),
        ));
    }
    let mut options = OpenOptions::new();
    options
        .write(true)
        .create(true)
        .custom_flags(libc::O_NOFOLLOW);
    if append {
        options.append(true);
    } else {
        options.truncate(true);
    }
    let mut file = options
        .open(&resolved.path)
        .map_err(|error| io_error("open external file for write", error))?;
    file.write_all(&bytes)
        .map_err(|error| io_error("write external file", error))?;
    file.sync_data()
        .map_err(|error| io_error("sync external file", error))?;
    sync_directory(parent)?;
    let size = fs::symlink_metadata(&resolved.path)
        .map_err(|error| io_error("stat external file", error))?
        .len();
    Ok(json!({
        "path": raw,
        "bytes": bytes.len(),
        "size": size,
        "append": append,
    }))
}

#[cfg(unix)]
fn external_mkdir(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let raw = required_path(params, "path")?;
    let resolved = resolve_external_path(state, raw)?.ok_or((
        ErrorCode::BadParams,
        "external mkdir requires a mounted directory path".into(),
    ))?;
    ensure_writable(&resolved)?;
    if resolved.path == resolved.root {
        return Err((
            ErrorCode::PolicyDenied,
            "cannot create an external mount root".into(),
        ));
    }
    match fs::symlink_metadata(&resolved.path) {
        Ok(metadata) if metadata.file_type().is_symlink() => Err((
            ErrorCode::PolicyDenied,
            "external directory is a symlink".into(),
        )),
        Ok(metadata) if metadata.is_dir() => Ok(json!({"path": raw, "created": false})),
        Ok(_) => Err((
            ErrorCode::BadParams,
            "external path is not a directory".into(),
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let parent = resolved.path.parent().ok_or((
                ErrorCode::PolicyDenied,
                "external directory has no parent".into(),
            ))?;
            ensure_external_parent_dirs(parent, &resolved.root)?;
            fs::create_dir(&resolved.path)
                .map_err(|error| io_error("create external directory", error))?;
            sync_directory(parent)?;
            Ok(json!({"path": raw, "created": true}))
        }
        Err(error) => Err(io_error("stat external directory", error)),
    }
}

#[cfg(unix)]
fn external_list(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let raw = required_path(params, "path")?;
    if raw == GUEST_MOUNTS_ROOT {
        let mut entries = state
            .external_mounts
            .iter()
            .map(|spec| {
                json!({
                    "name": spec.name,
                    "type": "dir",
                    "size": 0,
                    "modified": 0,
                    "writable": spec.access == "rw",
                })
            })
            .collect::<Vec<_>>();
        return paginate_entries(raw, params, &mut entries);
    }
    let resolved = resolve_external_path(state, raw)?.ok_or((
        ErrorCode::BadParams,
        "external list requires a mounted directory path".into(),
    ))?;
    let metadata = fs::symlink_metadata(&resolved.path)
        .map_err(|error| io_error("stat external directory", error))?;
    if !metadata.is_dir() {
        return Err((
            ErrorCode::BadParams,
            "external path is not a directory".into(),
        ));
    }
    let mut entries = Vec::new();
    for item in
        fs::read_dir(&resolved.path).map_err(|error| io_error("list external directory", error))?
    {
        let item = item.map_err(|error| io_error("read external directory entry", error))?;
        let path = item.path();
        let metadata =
            fs::symlink_metadata(&path).map_err(|error| io_error("stat external entry", error))?;
        let kind = if metadata.file_type().is_symlink() {
            "link"
        } else if metadata.is_dir() {
            "dir"
        } else if metadata.is_file() {
            "file"
        } else {
            "other"
        };
        entries.push(json!({
            "name": item.file_name().to_string_lossy(),
            "type": kind,
            "size": if metadata.is_file() { metadata.len() } else { 0 },
            "modified": modified_millis(&metadata),
        }));
    }
    entries.sort_by(|a, b| {
        a.get("name")
            .and_then(Value::as_str)
            .cmp(&b.get("name").and_then(Value::as_str))
    });
    paginate_entries(raw, params, &mut entries)
}

#[cfg(unix)]
fn external_info(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let raw = required_path(params, "path")?;
    if raw == GUEST_MOUNTS_ROOT {
        return Ok(json!({
            "path": raw,
            "type": "dir",
            "size": 0,
            "modified": 0,
            "readable": true,
            "writable": true,
        }));
    }
    let resolved = resolve_external_path(state, raw)?.ok_or((
        ErrorCode::BadParams,
        "external info requires a mounted path".into(),
    ))?;
    let metadata = fs::symlink_metadata(&resolved.path)
        .map_err(|error| io_error("stat external path", error))?;
    Ok(json!({
        "path": raw,
        "type": if metadata.is_dir() { "dir" } else if metadata.is_file() { "file" } else { "other" },
        "size": if metadata.is_file() { metadata.len() } else { 0 },
        "modified": modified_millis(&metadata),
        "readable": true,
        "writable": !resolved.read_only,
        "uid": metadata.uid(),
        "gid": metadata.gid(),
        "mode": metadata.permissions().mode() & 0o777,
    }))
}

#[cfg(unix)]
fn external_delete(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let raw = required_path(params, "path")?;
    let resolved = resolve_external_path(state, raw)?.ok_or((
        ErrorCode::BadParams,
        "external delete requires a mounted path".into(),
    ))?;
    ensure_writable(&resolved)?;
    if resolved.path == resolved.root {
        return Err((
            ErrorCode::PolicyDenied,
            "cannot delete an external mount root".into(),
        ));
    }
    let metadata = fs::symlink_metadata(&resolved.path)
        .map_err(|error| io_error("stat external delete target", error))?;
    reject_existing_symlink(&resolved.path, "external delete target")?;
    if metadata.is_dir() {
        fs::remove_dir_all(&resolved.path)
            .map_err(|error| io_error("remove external directory", error))?;
    } else {
        fs::remove_file(&resolved.path).map_err(|error| io_error("remove external file", error))?;
    }
    if let Some(parent) = resolved.path.parent() {
        sync_directory(parent)?;
    }
    Ok(json!({"path": raw, "deleted": true}))
}

#[cfg(unix)]
fn external_copy(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let (source, destination) = resolve_external_pair(state, params)?;
    ensure_writable(&destination)?;
    reject_external_roots(&source, &destination)?;
    let source_meta = fs::symlink_metadata(&source.path)
        .map_err(|error| io_error("stat external copy source", error))?;
    reject_existing_symlink(&source.path, "external copy source")?;
    if source_meta.is_dir() && destination.path.starts_with(&source.path) {
        return Err((
            ErrorCode::PolicyDenied,
            "destination cannot be inside source".into(),
        ));
    }
    let parent = destination.path.parent().ok_or((
        ErrorCode::PolicyDenied,
        "external destination has no parent".into(),
    ))?;
    ensure_external_parent_dirs(parent, &destination.root)?;
    copy_external_tree(&source.path, &destination.path)?;
    Ok(json!({
        "source": params.get("source").and_then(Value::as_str).unwrap_or_default(),
        "destination": params.get("destination").and_then(Value::as_str).unwrap_or_default(),
        "type": if source_meta.is_dir() { "dir" } else { "file" },
    }))
}

#[cfg(unix)]
fn external_move(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let (source, destination) = resolve_external_pair(state, params)?;
    ensure_writable(&source)?;
    ensure_writable(&destination)?;
    reject_external_roots(&source, &destination)?;
    let source_meta = fs::symlink_metadata(&source.path)
        .map_err(|error| io_error("stat external move source", error))?;
    reject_existing_symlink(&source.path, "external move source")?;
    if source_meta.is_dir() && destination.path.starts_with(&source.path) {
        return Err((
            ErrorCode::PolicyDenied,
            "destination cannot be inside source".into(),
        ));
    }
    let parent = destination.path.parent().ok_or((
        ErrorCode::PolicyDenied,
        "external destination has no parent".into(),
    ))?;
    ensure_external_parent_dirs(parent, &destination.root)?;
    if fs::rename(&source.path, &destination.path).is_err() {
        copy_external_tree(&source.path, &destination.path)?;
        remove_external_tree(&source.path)?;
    }
    sync_directory(parent)?;
    Ok(json!({
        "source": params.get("source").and_then(Value::as_str).unwrap_or_default(),
        "destination": params.get("destination").and_then(Value::as_str).unwrap_or_default(),
        "type": if source_meta.is_dir() { "dir" } else { "file" },
    }))
}

#[cfg(unix)]
fn resolve_external_pair(
    state: &AppState,
    params: &Value,
) -> Result<(ExternalPath, ExternalPath), (ErrorCode, String)> {
    let source_raw = required_path(params, "source")?;
    let destination_raw = required_path(params, "destination")?;
    if !is_external_path(source_raw) || !is_external_path(destination_raw) {
        return Err((
            ErrorCode::PolicyDenied,
            "cross-boundary external mount copy/move is unsupported".into(),
        ));
    }
    let source = resolve_external_path(state, source_raw)?
        .ok_or((ErrorCode::BadParams, "invalid external source".into()))?;
    let destination = resolve_external_path(state, destination_raw)?
        .ok_or((ErrorCode::BadParams, "invalid external destination".into()))?;
    if source.path == destination.path {
        return Err((ErrorCode::BadParams, "source == destination".into()));
    }
    Ok((source, destination))
}

#[cfg(unix)]
fn reject_external_roots(
    source: &ExternalPath,
    destination: &ExternalPath,
) -> Result<(), (ErrorCode, String)> {
    if source.path == source.root || destination.path == destination.root {
        return Err((
            ErrorCode::PolicyDenied,
            "external mount roots cannot be copied or moved".into(),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn ensure_writable(path: &ExternalPath) -> Result<(), (ErrorCode, String)> {
    if path.read_only {
        Err((
            ErrorCode::PolicyDenied,
            "external mount is read-only".into(),
        ))
    } else {
        Ok(())
    }
}

#[cfg(unix)]
fn required_path<'a>(params: &'a Value, key: &str) -> Result<&'a str, (ErrorCode, String)> {
    params
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or((ErrorCode::BadParams, format!("{key} required")))
}

#[cfg(unix)]
fn regular_metadata(path: &Path, action: &str) -> Result<fs::Metadata, (ErrorCode, String)> {
    let metadata = fs::symlink_metadata(path).map_err(|error| io_error(action, error))?;
    if metadata.file_type().is_symlink() {
        return Err((
            ErrorCode::PolicyDenied,
            format!("{action}: symlink is not allowed"),
        ));
    }
    if !metadata.is_file() {
        return Err((
            ErrorCode::BadParams,
            format!("{action}: path is not a regular file"),
        ));
    }
    Ok(metadata)
}

#[cfg(unix)]
fn reject_existing_symlink(path: &Path, label: &str) -> Result<(), (ErrorCode, String)> {
    if fs::symlink_metadata(path)
        .map(|metadata| metadata.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err((
            ErrorCode::PolicyDenied,
            format!("{label} must not be a symlink"),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn ensure_external_parent_dirs(parent: &Path, root: &Path) -> Result<(), (ErrorCode, String)> {
    if !parent.starts_with(root) {
        return Err((
            ErrorCode::PolicyDenied,
            "external parent escapes mount root".into(),
        ));
    }
    let relative = parent
        .strip_prefix(root)
        .map_err(|_| (ErrorCode::PolicyDenied, "invalid external parent".into()))?;
    let mut current = root.to_path_buf();
    for component in relative.components() {
        let std::path::Component::Normal(name) = component else {
            return Err((
                ErrorCode::BadParams,
                "invalid external parent component".into(),
            ));
        };
        current.push(name);
        match fs::symlink_metadata(&current) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                return Err((
                    ErrorCode::PolicyDenied,
                    "external parent contains a symlink".into(),
                ));
            }
            Ok(metadata) if !metadata.is_dir() => {
                return Err((
                    ErrorCode::BadParams,
                    "external parent is not a directory".into(),
                ));
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(&current)
                    .map_err(|error| io_error("create external parent", error))?;
            }
            Err(error) => return Err(io_error("stat external parent", error)),
        }
    }
    Ok(())
}

#[cfg(unix)]
fn copy_external_tree(source: &Path, destination: &Path) -> Result<(), (ErrorCode, String)> {
    let metadata = fs::symlink_metadata(source)
        .map_err(|error| io_error("stat external copy source", error))?;
    reject_existing_symlink(source, "external copy source")?;
    if metadata.is_dir() {
        match fs::symlink_metadata(destination) {
            Ok(existing) if existing.file_type().is_symlink() || !existing.is_dir() => {
                return Err((
                    ErrorCode::BadParams,
                    "external destination type conflicts".into(),
                ));
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(destination)
                    .map_err(|error| io_error("create external copy directory", error))?;
            }
            Err(error) => return Err(io_error("stat external copy destination", error)),
        }
        for entry in
            fs::read_dir(source).map_err(|error| io_error("read external copy source", error))?
        {
            let entry = entry.map_err(|error| io_error("read external copy entry", error))?;
            copy_external_tree(&entry.path(), &destination.join(entry.file_name()))?;
        }
    } else if metadata.is_file() {
        reject_existing_symlink(destination, "external copy destination")?;
        if fs::symlink_metadata(destination)
            .map(|existing| existing.is_dir())
            .unwrap_or(false)
        {
            return Err((
                ErrorCode::BadParams,
                "external file destination is a directory".into(),
            ));
        }
        fs::copy(source, destination).map_err(|error| io_error("copy external file", error))?;
    } else {
        return Err((
            ErrorCode::PolicyDenied,
            "unsupported external source type".into(),
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn remove_external_tree(path: &Path) -> Result<(), (ErrorCode, String)> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|error| io_error("stat external remove target", error))?;
    reject_existing_symlink(path, "external remove target")?;
    if metadata.is_dir() {
        fs::remove_dir_all(path).map_err(|error| io_error("remove external directory", error))?;
    } else {
        fs::remove_file(path).map_err(|error| io_error("remove external file", error))?;
    }
    Ok(())
}

#[cfg(unix)]
fn paginate_entries(
    raw: &str,
    params: &Value,
    entries: &mut Vec<Value>,
) -> Result<Value, (ErrorCode, String)> {
    let offset = params.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
    let limit = params
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(100)
        .clamp(1, MAX_LIST_ENTRIES as u64) as usize;
    let total = entries.len();
    let page = entries
        .drain(..)
        .skip(offset)
        .take(limit)
        .collect::<Vec<_>>();
    Ok(json!({
        "path": raw,
        "entries": page,
        "total": total,
        "offset": offset,
        "limit": limit,
        "next_offset": if offset + page.len() < total { Some(offset + page.len()) } else { None::<usize> },
    }))
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), (ErrorCode, String)> {
    File::open(path)
        .and_then(|directory| directory.sync_all())
        .map_err(|error| io_error("sync external directory", error))
}

#[cfg(unix)]
fn io_error(action: &str, error: std::io::Error) -> (ErrorCode, String) {
    let code = if error.kind() == std::io::ErrorKind::NotFound {
        ErrorCode::RuntimeUnavailable
    } else {
        ErrorCode::Internal
    };
    (code, format!("{action}: {error}"))
}

#[cfg(unix)]
fn modified_millis(metadata: &fs::Metadata) -> u128 {
    metadata
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

#[cfg(unix)]
fn encode_base64(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    let mut index = 0;
    while index < bytes.len() {
        let a = bytes[index];
        let b = bytes.get(index + 1).copied();
        let c = bytes.get(index + 2).copied();
        out.push(B64[(a >> 2) as usize] as char);
        out.push(B64[(((a & 0x03) << 4) | b.map_or(0, |value| value >> 4)) as usize] as char);
        out.push(b.map_or('=', |value| {
            B64[(((value & 0x0f) << 2) | c.map_or(0, |item| item >> 6)) as usize] as char
        }));
        out.push(c.map_or('=', |value| B64[(value & 0x3f) as usize] as char));
        index += 3;
    }
    out
}

#[cfg(unix)]
fn decode_base64(raw: &str) -> Result<Vec<u8>, (ErrorCode, String)> {
    if !raw.len().is_multiple_of(4) {
        return Err((ErrorCode::BadParams, "invalid base64 length".into()));
    }
    let mut out = Vec::with_capacity(raw.len() / 4 * 3);
    let bytes = raw.as_bytes();
    for (index, chunk) in bytes.chunks(4).enumerate() {
        if chunk[0] == b'=' || chunk[1] == b'=' {
            return Err((ErrorCode::BadParams, "invalid base64 padding".into()));
        }
        if chunk[2] == b'=' && chunk[3] != b'=' {
            return Err((ErrorCode::BadParams, "invalid base64 padding".into()));
        }
        if (chunk[2] == b'=' || chunk[3] == b'=') && index + 1 != bytes.len() / 4 {
            return Err((ErrorCode::BadParams, "base64 padding must be final".into()));
        }
        let a = decode_base64_byte(chunk[0])?;
        let b = decode_base64_byte(chunk[1])?;
        let c = if chunk[2] == b'=' {
            0
        } else {
            decode_base64_byte(chunk[2])?
        };
        let d = if chunk[3] == b'=' {
            0
        } else {
            decode_base64_byte(chunk[3])?
        };
        if chunk[2] == b'=' && (b & 0x0f) != 0 {
            return Err((ErrorCode::BadParams, "non-zero base64 padding bits".into()));
        }
        if chunk[3] == b'=' && chunk[2] != b'=' && (c & 0x03) != 0 {
            return Err((ErrorCode::BadParams, "non-zero base64 padding bits".into()));
        }
        out.push((a << 2) | (b >> 4));
        if chunk[2] != b'=' {
            out.push((b << 4) | (c >> 2));
        }
        if chunk[3] != b'=' {
            out.push((c << 6) | d);
        }
    }
    Ok(out)
}

#[cfg(unix)]
fn decode_base64_byte(byte: u8) -> Result<u8, (ErrorCode, String)> {
    B64.iter()
        .position(|candidate| *candidate == byte)
        .map(|value| value as u8)
        .ok_or((ErrorCode::BadParams, "invalid base64 data".into()))
}

pub fn mount_reconcile(state: &mut AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    let (specs, canonical, digest) = parse_snapshot(params)?;
    if !state.mock && !state.ubuntu.running {
        return Err((
            ErrorCode::RuntimeUnavailable,
            "ubuntu keeper must be running before mount.reconcile".into(),
        ));
    }
    let app_uid = state.policy.caller.app_uid;
    if app_uid == 0 && !state.mock {
        return Err((
            ErrorCode::NotAuthorized,
            "mount.reconcile requires configured App UID".into(),
        ));
    }
    if state.mock {
        state.external_mounts = specs.clone();
        state.ubuntu.external_mount_digest = Some(digest.clone());
        state.ubuntu.external_mount_verified = true;
        return Ok(json!({
            "snapshot_digest": digest,
            "mounts": specs.len(),
            "keeper_pid": state.ubuntu.pid,
            "keeper_verified": true,
            "mock": true
        }));
    }

    if state.ubuntu.external_mount_verified
        && state.ubuntu.external_mount_digest.as_deref() == Some(digest.as_str())
    {
        state.external_mounts = specs.clone();
        return Ok(json!({
            "snapshot_digest": digest,
            "mounts": specs.len(),
            "keeper_pid": state.ubuntu.pid,
            "keeper_verified": true,
            "idempotent": true
        }));
    }

    crate::ubuntu::replace_keeper_with_external_mounts(state, &canonical, app_uid)?;
    state.external_mounts = specs.clone();
    state.ubuntu.external_mount_digest = Some(digest.clone());
    state.ubuntu.external_mount_verified = true;
    Ok(json!({
        "snapshot_digest": digest,
        "mounts": specs.len(),
        "keeper_pid": state.ubuntu.pid,
        "keeper_verified": true
    }))
}

fn app_uid(state: &AppState) -> u32 {
    if state.policy.caller.app_uid != 0 {
        state.policy.caller.app_uid
    } else {
        crate::layout::GUEST_UID
    }
}

fn source_path(spec: &MountSpec, uid: u32) -> Result<std::path::PathBuf, (ErrorCode, String)> {
    let root = if spec.volume == "primary" {
        std::path::PathBuf::from(format!("/storage/emulated/{}", uid / 100_000))
    } else {
        std::path::PathBuf::from("/storage").join(&spec.volume)
    };
    let mut path = root.clone();
    for segment in &spec.path_segments {
        validate_segment(segment)?;
        path.push(segment);
    }
    reject_symlink_components(
        &root,
        &spec
            .path_segments
            .iter()
            .map(String::as_str)
            .collect::<Vec<_>>(),
    )?;
    let metadata = std::fs::symlink_metadata(&path).map_err(|error| {
        (
            ErrorCode::RuntimeUnavailable,
            format!("external mount source unavailable: {path:?}: {error}"),
        )
    })?;
    if !metadata.is_dir() {
        return Err((
            ErrorCode::BadParams,
            "external mount source is not a directory".into(),
        ));
    }
    Ok(path)
}

#[cfg(unix)]
pub fn apply_to_namespace(rootfs: &str, specs_json: &str, app_uid: u32) -> Result<(), String> {
    let specs: Vec<MountSpec> = serde_json::from_value(
        serde_json::from_str::<Value>(specs_json)
            .map_err(|error| format!("parse canonical mounts: {error}"))?
            .get("mounts")
            .cloned()
            .ok_or("canonical mounts missing mounts")?,
    )
    .map_err(|error| format!("decode canonical mounts: {error}"))?;
    let root = std::path::Path::new(rootfs);
    if rootfs != crate::layout::HOST_ROOTFS
        || root
            .symlink_metadata()
            .map_err(|e| e.to_string())?
            .file_type()
            .is_symlink()
    {
        return Err("external mount rootfs must be the fixed canonical rootfs".into());
    }
    let mount_root = root.join("var/minis/mounts");
    ensure_directory_no_symlink(&mount_root)?;
    for spec in &specs {
        let source = open_source_dir(app_uid, spec)?;
        let destination = mount_root.join(&spec.name);
        ensure_directory_no_symlink(&destination)?;
        crate::ns::bind_mount_fd(source.as_raw_fd(), &destination.to_string_lossy())?;
        crate::ns::remount_external(&destination.to_string_lossy(), spec.access == "ro").map_err(
            |error| {
                if spec.access == "ro" {
                    format!("{}: {error}", ErrorCode::MountRoUnsupported.as_str())
                } else {
                    error
                }
            },
        )?;
        // Keep the source FD alive through both mount syscalls. The mount is
        // otherwise allowed to resolve a changed path after validation.
    }
    Ok(())
}

#[cfg(not(unix))]
pub fn apply_to_namespace(_rootfs: &str, _specs_json: &str, _app_uid: u32) -> Result<(), String> {
    Err("external mounts require unix".into())
}

#[cfg(unix)]
fn open_source_dir(uid: u32, spec: &MountSpec) -> Result<std::os::fd::OwnedFd, String> {
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    let root_fd = unsafe {
        libc::open(
            c"/".as_ptr(),
            libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC,
        )
    };
    if root_fd < 0 {
        return Err(format!("open /: {}", std::io::Error::last_os_error()));
    }
    let root = unsafe { OwnedFd::from_raw_fd(root_fd) };
    let storage = openat_dir(root.as_raw_fd(), "storage")?;
    let volume = if spec.volume == "primary" {
        let emulated = openat_dir(storage.as_raw_fd(), "emulated")?;
        openat_dir(emulated.as_raw_fd(), &(uid / 100_000).to_string())?
    } else {
        openat_dir(storage.as_raw_fd(), &spec.volume)?
    };
    let mut current = volume;
    for segment in &spec.path_segments {
        current = openat_dir(current.as_raw_fd(), segment)?;
    }
    Ok(current)
}

#[cfg(unix)]
fn openat_dir(parent: std::os::fd::RawFd, name: &str) -> Result<std::os::fd::OwnedFd, String> {
    use std::os::fd::{FromRawFd, OwnedFd};
    let c_name = std::ffi::CString::new(name).map_err(|_| "NUL in path segment".to_string())?;
    let fd = unsafe {
        libc::openat(
            parent,
            c_name.as_ptr(),
            libc::O_PATH | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err(format!(
            "openat {name}: {}",
            std::io::Error::last_os_error()
        ));
    }
    Ok(unsafe { OwnedFd::from_raw_fd(fd) })
}

#[cfg(unix)]
fn ensure_directory_no_symlink(path: &std::path::Path) -> Result<(), String> {
    match path.symlink_metadata() {
        Ok(metadata) if metadata.file_type().is_symlink() => Err(format!(
            "mount destination must not be a symlink: {}",
            path.display()
        )),
        Ok(metadata) if metadata.is_dir() => Ok(()),
        Ok(_) => Err(format!(
            "mount destination is not a directory: {}",
            path.display()
        )),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => std::fs::create_dir(path)
            .map_err(|e| format!("create mount destination {}: {e}", path.display())),
        Err(error) => Err(format!(
            "stat mount destination {}: {error}",
            path.display()
        )),
    }
}

fn reject_symlink_components(
    root: &std::path::Path,
    relative: &[&str],
) -> Result<(), (ErrorCode, String)> {
    let mut current = root.to_path_buf();
    if std::fs::symlink_metadata(&current)
        .map(|metadata| metadata.file_type().is_symlink())
        .unwrap_or(true)
    {
        return Err((
            ErrorCode::PolicyDenied,
            "external mount source root is invalid".into(),
        ));
    }
    for (index, segment) in relative.iter().enumerate() {
        current.push(segment);
        match std::fs::symlink_metadata(&current) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                return Err((
                    ErrorCode::PolicyDenied,
                    "external mount source contains a symlink".into(),
                ))
            }
            Ok(metadata) if index + 1 == relative.len() && !metadata.is_dir() => {
                return Err((
                    ErrorCode::BadParams,
                    "external mount source is not a directory".into(),
                ))
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                // Missing components are valid for broker-mediated
                // create_dirs/write/mkdir targets. Existing mount sources are
                // checked by source_path before the mount is created.
                return Ok(());
            }
            Err(error) => {
                return Err((
                    ErrorCode::RuntimeUnavailable,
                    format!("stat external mount source: {error}"),
                ))
            }
            _ => {}
        }
    }
    Ok(())
}

fn reject_unknown_keys<'a>(
    keys: impl Iterator<Item = &'a str>,
    allowed: &[&str],
) -> Result<(), (ErrorCode, String)> {
    for key in keys {
        if !allowed.contains(&key) {
            return Err((
                ErrorCode::BadParams,
                format!("unsupported mount parameter: {key}"),
            ));
        }
    }
    Ok(())
}

fn validate_name(name: &str) -> Result<(), (ErrorCode, String)> {
    if name.is_empty() || name.len() > MAX_NAME_BYTES || matches!(name, "." | "..") {
        return Err((ErrorCode::BadParams, "invalid mount name".into()));
    }
    if !name
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    {
        return Err((
            ErrorCode::BadParams,
            "mount name must use ASCII letters, digits, _ or -".into(),
        ));
    }
    Ok(())
}

fn validate_segment(segment: &str) -> Result<(), (ErrorCode, String)> {
    if segment.is_empty() || segment.len() > MAX_SEGMENT_BYTES || matches!(segment, "." | "..") {
        return Err((
            ErrorCode::BadParams,
            "invalid external mount path segment".into(),
        ));
    }
    if segment.contains('/')
        || segment.contains('\\')
        || segment.contains('\0')
        || segment.chars().any(char::is_control)
    {
        return Err((
            ErrorCode::BadParams,
            "invalid external mount path segment".into(),
        ));
    }
    Ok(())
}

fn validate_volume(volume: &str) -> Result<String, (ErrorCode, String)> {
    if volume == "primary" {
        return Ok(volume.to_string());
    }
    if !is_uuid(volume) {
        return Err((
            ErrorCode::BadParams,
            "volume must be primary or a storage UUID".into(),
        ));
    }
    Ok(volume.to_ascii_lowercase())
}

fn is_uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
}

fn snapshot_digest(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(bytes);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::PolicyFile;

    fn state() -> AppState {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        state.ubuntu.running = true;
        state
    }

    #[test]
    fn snapshot_rejects_host_and_guest_path_inputs() {
        let params = json!({
            "mounts": [{
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "name": "docs",
                "volume": "primary",
                "path_segments": ["Documents"],
                "access": "ro",
                "host_path": "/data/local/tmp/escape"
            }]
        });
        assert_eq!(parse_snapshot(&params).unwrap_err().0, ErrorCode::BadParams);
    }

    #[test]
    fn snapshot_digest_is_order_independent_and_empty_is_valid() {
        let a = json!({"mounts": [
            {"id":"550e8400-e29b-41d4-a716-446655440000","name":"b","volume":"primary","path_segments":[],"access":"ro"},
            {"id":"550e8400-e29b-41d4-a716-446655440001","name":"a","volume":"primary","path_segments":[],"access":"rw"}
        ]});
        let b = json!({"mounts": [
            {"id":"550e8400-e29b-41d4-a716-446655440001","name":"a","volume":"primary","path_segments":[],"access":"rw"},
            {"id":"550e8400-e29b-41d4-a716-446655440000","name":"b","volume":"primary","path_segments":[],"access":"ro"}
        ]});
        let (_, ca, da) = parse_snapshot(&a).unwrap();
        let (_, cb, db) = parse_snapshot(&b).unwrap();
        assert_eq!(ca, cb);
        assert_eq!(da, db);
        let (empty, _, _) = parse_snapshot(&json!({"mounts": []})).unwrap();
        assert!(empty.is_empty());
        let _ = state();
    }

    #[test]
    fn path_rules_reject_alias_components() {
        for bad in [".", "..", "a/b", "a\\b", "a\0b"] {
            assert!(validate_segment(bad).is_err(), "accepted {bad:?}");
        }
        assert!(validate_name("docs-1").is_ok());
        assert!(validate_name("docs/name").is_err());
        assert!(validate_volume("primary").is_ok());
        assert!(validate_volume("not-a-volume").is_err());
    }
}
