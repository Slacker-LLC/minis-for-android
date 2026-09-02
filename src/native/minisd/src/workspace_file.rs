use crate::protocol::ErrorCode;
use crate::state::AppState;
use serde_json::Value;

#[cfg(unix)]
mod unix_impl {
    use super::*;
    use crate::layout::{
        ensure_host_layout_for, validate_persistent_backing, HOST_HOME, HOST_MEMORY, HOST_SESSIONS,
        HOST_SHARED, HOST_SKILLS, PERSISTENT_DATA_MODE, PERSISTENT_FILE_MODE,
    };
    use std::cmp::min;
    use std::fs::{self, File, OpenOptions};
    use std::io::{Read, Seek, SeekFrom, Write};
    use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
    use std::path::{Path, PathBuf};
    use std::time::UNIX_EPOCH;

    const MAX_READ_BYTES: usize = 512 * 1024;
    const MAX_WRITE_BYTES: usize = 48 * 1024;
    const MAX_LIST_ENTRIES: usize = 500;
    const MAX_PATH_BYTES: usize = 4096;
    const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    #[derive(Debug)]
    struct ResolvedPath {
        path: PathBuf,
        root: PathBuf,
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    enum Scope {
        SessionRoot,
        Session(&'static str),
        Global(&'static str),
    }

    pub fn handle(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
        let operation = params
            .get("operation")
            .and_then(Value::as_str)
            .ok_or((ErrorCode::BadParams, "operation required".into()))?;
        if operation.is_empty() {
            return Err((ErrorCode::BadParams, "operation required".into()));
        }
        if state.mock {
            return Ok(serde_json::json!({
                "mock": true,
                "operation": operation,
            }));
        }

        let (uid, gid) = guest_ids(state);
        ensure_host_layout_for(uid, gid)
            .and_then(|()| validate_persistent_backing())
            .map_err(|detail| {
                (
                    ErrorCode::RuntimeUnavailable,
                    format!("persistent workspace unavailable: {detail}"),
                )
            })?;

        match operation {
            "read" => read(params, uid, gid),
            "write" | "append" => write(params, uid, gid, operation == "append"),
            "mkdir" => mkdir(params, uid, gid),
            "copy" => copy(params, uid, gid),
            "move" => move_path(params, uid, gid),
            "delete" => delete(params, uid, gid),
            "list" => list(params, uid, gid),
            "info" => info(params, uid, gid),
            _ => Err((
                ErrorCode::BadParams,
                format!("unknown workspace file operation: {operation}"),
            )),
        }
    }

    fn guest_ids(state: &AppState) -> (u32, u32) {
        let uid = if state.policy.caller.app_uid != 0 {
            state.policy.caller.app_uid
        } else {
            crate::layout::GUEST_UID
        };
        (uid, uid)
    }

    fn read(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
        let metadata = fs::metadata(&resolved.path).map_err(|e| io_error("stat file", e))?;
        if !metadata.is_file() {
            return Err((
                ErrorCode::BadParams,
                format!("path is not a regular file: {}", resolved.path.display()),
            ));
        }
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

        let total = metadata.len();
        let mut file = File::open(&resolved.path).map_err(|e| io_error("open file", e))?;
        file.seek(SeekFrom::Start(offset))
            .map_err(|e| io_error("seek file", e))?;
        let mut bytes = Vec::with_capacity(min(requested as usize, MAX_READ_BYTES));
        file.take(requested)
            .read_to_end(&mut bytes)
            .map_err(|e| io_error("read file", e))?;
        let read = bytes.len() as u64;
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "data_base64": encode_base64(&bytes),
            "offset": offset,
            "bytes": read,
            "total_bytes": total,
            "eof": offset.saturating_add(read) >= total,
        }))
    }

    fn write(
        params: &Value,
        uid: u32,
        gid: u32,
        append: bool,
    ) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
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
        if resolved.path == resolved.root {
            return Err((
                ErrorCode::PolicyDenied,
                "cannot write a persistent directory".into(),
            ));
        }
        let parent = resolved
            .path
            .parent()
            .ok_or((ErrorCode::PolicyDenied, "file has no parent".into()))?;
        if params
            .get("create_dirs")
            .and_then(Value::as_bool)
            .unwrap_or(false)
        {
            ensure_parent_dirs(parent, &resolved.root, uid, gid)?;
        } else if !parent.is_dir() {
            return Err((
                ErrorCode::RuntimeUnavailable,
                format!("parent directory is missing: {}", parent.display()),
            ));
        }
        if fs::symlink_metadata(&resolved.path).is_ok_and(|meta| meta.file_type().is_symlink()) {
            return Err((
                ErrorCode::PolicyDenied,
                format!("file must not be a symlink: {}", resolved.path.display()),
            ));
        }
        if fs::metadata(&resolved.path)
            .map(|meta| meta.is_dir())
            .unwrap_or(false)
        {
            return Err((
                ErrorCode::BadParams,
                format!("path is a directory: {}", resolved.path.display()),
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
            .map_err(|e| io_error("open file for write", e))?;
        file.write_all(&bytes)
            .map_err(|e| io_error("write file", e))?;
        file.sync_data().map_err(|e| io_error("sync file", e))?;
        set_owner_mode(&resolved.path, uid, gid, PERSISTENT_FILE_MODE)?;
        let size = fs::metadata(&resolved.path)
            .map_err(|e| io_error("stat written file", e))?
            .len();
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "bytes": bytes.len(),
            "size": size,
            "append": append,
        }))
    }

    fn mkdir(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
        if resolved.path == resolved.root {
            return Err((
                ErrorCode::PolicyDenied,
                "cannot create a persistent root".into(),
            ));
        }
        match fs::symlink_metadata(&resolved.path) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                return Err((
                    ErrorCode::PolicyDenied,
                    format!(
                        "directory must not be a symlink: {}",
                        resolved.path.display()
                    ),
                ));
            }
            Ok(metadata) if metadata.is_dir() => {
                return Ok(serde_json::json!({
                    "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
                    "created": false,
                }));
            }
            Ok(_) => {
                return Err((
                    ErrorCode::BadParams,
                    format!("path is not a directory: {}", resolved.path.display()),
                ));
            }
            Err(error) if error.kind() != std::io::ErrorKind::NotFound => {
                return Err(io_error("stat directory", error));
            }
            Err(_) => {}
        }
        let parent = resolved
            .path
            .parent()
            .ok_or((ErrorCode::PolicyDenied, "directory has no parent".into()))?;
        if !parent.is_dir() {
            return Err((
                ErrorCode::RuntimeUnavailable,
                format!("parent directory is missing: {}", parent.display()),
            ));
        }
        fs::create_dir(&resolved.path).map_err(|e| io_error("create directory", e))?;
        set_owner_mode(&resolved.path, uid, gid, PERSISTENT_DATA_MODE)?;
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "created": true,
        }))
    }

    fn copy(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let source = resolve_param_path_for(params, "source", "source_session_id", uid, gid)?;
        let destination =
            resolve_param_path_for(params, "destination", "destination_session_id", uid, gid)?;
        let source_meta =
            fs::symlink_metadata(&source.path).map_err(|e| io_error("stat source", e))?;
        reject_symlink(&source.path, &source_meta)?;
        if source.path == source.root || destination.path == destination.root {
            return Err((
                ErrorCode::PolicyDenied,
                "copying persistent roots is not allowed".into(),
            ));
        }
        if source.path == destination.path {
            return Err((ErrorCode::BadParams, "source == destination".into()));
        }
        if source_meta.is_dir() && is_within(&source.path, &destination.path)? {
            return Err((
                ErrorCode::PolicyDenied,
                "destination cannot be inside source".into(),
            ));
        }
        let parent = destination
            .path
            .parent()
            .ok_or((ErrorCode::PolicyDenied, "destination has no parent".into()))?;
        ensure_parent_dirs(parent, &destination.root, uid, gid)?;
        copy_tree(&source.path, &destination.path, uid, gid)?;
        Ok(serde_json::json!({
            "source": params.get("source").and_then(Value::as_str).unwrap_or_default(),
            "destination": params.get("destination").and_then(Value::as_str).unwrap_or_default(),
            "type": if source_meta.is_dir() { "dir" } else { "file" },
        }))
    }

    fn move_path(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let source = resolve_param_path_for(params, "source", "source_session_id", uid, gid)?;
        let destination =
            resolve_param_path_for(params, "destination", "destination_session_id", uid, gid)?;
        let source_meta =
            fs::symlink_metadata(&source.path).map_err(|e| io_error("stat source", e))?;
        reject_symlink(&source.path, &source_meta)?;
        if source.path == source.root || destination.path == destination.root {
            return Err((
                ErrorCode::PolicyDenied,
                "moving persistent roots is not allowed".into(),
            ));
        }
        if source.path == destination.path {
            return Err((ErrorCode::BadParams, "source == destination".into()));
        }
        if source_meta.is_dir() && is_within(&source.path, &destination.path)? {
            return Err((
                ErrorCode::PolicyDenied,
                "destination cannot be inside source".into(),
            ));
        }
        let parent = destination
            .path
            .parent()
            .ok_or((ErrorCode::PolicyDenied, "destination has no parent".into()))?;
        ensure_parent_dirs(parent, &destination.root, uid, gid)?;
        if fs::symlink_metadata(&destination.path).is_ok_and(|meta| meta.file_type().is_symlink()) {
            return Err((
                ErrorCode::PolicyDenied,
                format!(
                    "destination must not be a symlink: {}",
                    destination.path.display()
                ),
            ));
        }
        if fs::rename(&source.path, &destination.path).is_err() {
            copy_tree(&source.path, &destination.path, uid, gid)?;
            remove_tree(&source.path)?;
        }
        Ok(serde_json::json!({
            "source": params.get("source").and_then(Value::as_str).unwrap_or_default(),
            "destination": params.get("destination").and_then(Value::as_str).unwrap_or_default(),
            "type": if source_meta.is_dir() { "dir" } else { "file" },
        }))
    }

    fn delete(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
        if resolved.path == resolved.root {
            return Err((
                ErrorCode::PolicyDenied,
                "cannot delete a persistent root".into(),
            ));
        }
        let metadata =
            fs::symlink_metadata(&resolved.path).map_err(|e| io_error("stat delete target", e))?;
        reject_symlink(&resolved.path, &metadata)?;
        remove_tree(&resolved.path)?;
        let _ = (uid, gid);
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "deleted": true,
        }))
    }

    fn list(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
        let metadata = fs::metadata(&resolved.path).map_err(|e| io_error("stat directory", e))?;
        if !metadata.is_dir() {
            return Err((
                ErrorCode::BadParams,
                format!("path is not a directory: {}", resolved.path.display()),
            ));
        }
        let offset = params.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
        let limit = params
            .get("limit")
            .and_then(Value::as_u64)
            .unwrap_or(100)
            .clamp(1, MAX_LIST_ENTRIES as u64) as usize;
        let mut entries = Vec::new();
        for item in fs::read_dir(&resolved.path).map_err(|e| io_error("list directory", e))? {
            let item = item.map_err(|e| io_error("read directory entry", e))?;
            let path = item.path();
            let metadata =
                fs::symlink_metadata(&path).map_err(|e| io_error("stat directory entry", e))?;
            let kind = if metadata.file_type().is_symlink() {
                "link"
            } else if metadata.is_dir() {
                "dir"
            } else {
                "file"
            };
            entries.push(serde_json::json!({
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
        let total = entries.len();
        let page = entries
            .into_iter()
            .skip(offset)
            .take(limit)
            .collect::<Vec<_>>();
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "entries": page,
            "total": total,
            "offset": offset,
            "limit": limit,
            "next_offset": if offset + page.len() < total {
                Some(offset + page.len())
            } else {
                None::<usize>
            },
        }))
    }

    fn info(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
        let metadata = fs::metadata(&resolved.path).map_err(|e| io_error("stat path", e))?;
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "type": if metadata.is_dir() { "dir" } else if metadata.is_file() { "file" } else { "other" },
            "size": if metadata.is_file() { metadata.len() } else { 0 },
            "modified": modified_millis(&metadata),
            "readable": true,
            "writable": true,
            "uid": metadata.uid(),
            "gid": metadata.gid(),
            "mode": metadata.permissions().mode() & 0o777,
        }))
    }

    fn resolve_param_path(
        params: &Value,
        key: &str,
        uid: u32,
        gid: u32,
    ) -> Result<ResolvedPath, (ErrorCode, String)> {
        resolve_param_path_for(params, key, "session_id", uid, gid)
    }

    fn resolve_param_path_for(
        params: &Value,
        key: &str,
        session_key: &str,
        uid: u32,
        gid: u32,
    ) -> Result<ResolvedPath, (ErrorCode, String)> {
        let path = params
            .get(key)
            .and_then(Value::as_str)
            .ok_or((ErrorCode::BadParams, format!("{key} required")))?;
        resolve_guest_path(
            params
                .get(session_key)
                .and_then(Value::as_str)
                .or_else(|| params.get("session_id").and_then(Value::as_str)),
            path,
            uid,
            gid,
        )
    }

    fn resolve_guest_path(
        session_id: Option<&str>,
        raw: &str,
        uid: u32,
        gid: u32,
    ) -> Result<ResolvedPath, (ErrorCode, String)> {
        if raw.len() > MAX_PATH_BYTES {
            return Err((ErrorCode::BadParams, "path is too long".into()));
        }
        let normalized = normalize_guest_path(raw)?;
        let (scope, prefix) = match_scope(&normalized).ok_or((
            ErrorCode::PolicyDenied,
            format!("path is outside the guest persistent layout: {raw}"),
        ))?;
        let (root, rest) = match scope {
            Scope::SessionRoot => {
                let session_id = session_id.ok_or((
                    ErrorCode::BadParams,
                    "session_id required for session workspace path".into(),
                ))?;
                let session_root =
                    crate::ubuntu::prepare_session_root(HOST_SESSIONS, session_id, uid, gid)
                        .map_err(|(code, detail)| {
                            (code, format!("prepare session workspace: {detail}"))
                        })?;
                (PathBuf::from(session_root), suffix(&normalized, prefix))
            }
            Scope::Global(root) => (PathBuf::from(root), suffix(&normalized, prefix)),
            Scope::Session(subdir) => {
                let session_id = session_id.ok_or((
                    ErrorCode::BadParams,
                    "session_id required for session workspace path".into(),
                ))?;
                let session_root =
                    crate::ubuntu::prepare_session_root(HOST_SESSIONS, session_id, uid, gid)
                        .map_err(|(code, detail)| {
                            (code, format!("prepare session workspace: {detail}"))
                        })?;
                (
                    PathBuf::from(session_root).join(subdir),
                    suffix(&normalized, prefix),
                )
            }
        };
        let root = fixed_root(&root)?;
        let path = resolve_under(&root, &rest)?;
        Ok(ResolvedPath { path, root })
    }

    fn normalize_guest_path(raw: &str) -> Result<String, (ErrorCode, String)> {
        if raw.is_empty() || raw.contains('\0') || raw.contains('\\') {
            return Err((ErrorCode::BadParams, "invalid guest path".into()));
        }
        if raw == "." {
            return Ok("/workspace".into());
        }
        let absolute = if raw.starts_with('/') {
            raw.to_string()
        } else {
            format!("/workspace/{raw}")
        };
        if absolute.contains("//") || absolute.ends_with("/.") || absolute.contains("/./") {
            return Err((ErrorCode::PolicyDenied, "non-canonical guest path".into()));
        }
        if absolute.split('/').any(|part| part == ".." || part == ".") {
            return Err((
                ErrorCode::PolicyDenied,
                "guest path traversal denied".into(),
            ));
        }
        Ok(absolute.trim_end_matches('/').to_string())
    }

    fn match_scope(path: &str) -> Option<(Scope, &'static str)> {
        // The exact session root is a synthetic view over the four fixed
        // per-session directories. Descendants continue through the more
        // specific aliases below, so global paths such as /var/minis/memory
        // are never captured by this session scope.
        if path == "/var/minis" {
            return Some((Scope::SessionRoot, "/var/minis"));
        }
        const SESSION_ALIASES: &[(&str, &str)] = &[
            ("/var/minis/workspace/attachments", "attachments"),
            ("/workspace/attachments", "attachments"),
            ("/var/minis/workspace/offloads", "offloads"),
            ("/workspace/offloads", "offloads"),
            ("/var/minis/workspace/browser", "browser"),
            ("/workspace/browser", "browser"),
            ("/var/minis/attachments", "attachments"),
            ("/var/minis/offloads", "offloads"),
            ("/var/minis/browser", "browser"),
            ("/var/minis/workspace", "workspace"),
            ("/workspace", "workspace"),
        ];
        const GLOBAL_ALIASES: &[(&str, &str)] = &[
            ("/var/minis/memory", HOST_MEMORY),
            ("/memory", HOST_MEMORY),
            ("/var/minis/skills", HOST_SKILLS),
            ("/skills", HOST_SKILLS),
            ("/var/minis/shared", HOST_SHARED),
            ("/shared", HOST_SHARED),
            ("/home/minis", HOST_HOME),
        ];
        for (prefix, subdir) in SESSION_ALIASES {
            if matches_prefix(path, prefix) {
                return Some((Scope::Session(subdir), prefix));
            }
        }
        for (prefix, root) in GLOBAL_ALIASES {
            if matches_prefix(path, prefix) {
                return Some((Scope::Global(root), prefix));
            }
        }
        None
    }

    fn matches_prefix(path: &str, prefix: &str) -> bool {
        path == prefix || path.starts_with(&format!("{prefix}/"))
    }

    fn suffix(path: &str, prefix: &str) -> String {
        path.strip_prefix(prefix)
            .unwrap_or_default()
            .trim_start_matches('/')
            .to_string()
    }

    fn fixed_root(root: &Path) -> Result<PathBuf, (ErrorCode, String)> {
        let metadata =
            fs::symlink_metadata(root).map_err(|e| io_error("stat persistent root", e))?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err((
                ErrorCode::NotAuthorized,
                format!(
                    "persistent source must be a real directory: {}",
                    root.display()
                ),
            ));
        }
        fs::canonicalize(root).map_err(|e| io_error("canonicalize persistent root", e))
    }

    fn resolve_under(root: &Path, rest: &str) -> Result<PathBuf, (ErrorCode, String)> {
        if rest.is_empty() {
            return Ok(root.to_path_buf());
        }
        let components = rest.split('/').collect::<Vec<_>>();
        let mut result = root.to_path_buf();
        let mut missing = false;
        for (index, component) in components.iter().enumerate() {
            result.push(component);
            if missing {
                continue;
            }
            match fs::symlink_metadata(&result) {
                Ok(metadata) => {
                    if metadata.file_type().is_symlink() {
                        return Err((
                            ErrorCode::PolicyDenied,
                            format!("path component must not be a symlink: {}", result.display()),
                        ));
                    }
                    if index + 1 < components.len() && !metadata.is_dir() {
                        return Err((
                            ErrorCode::BadParams,
                            format!("path component is not a directory: {}", result.display()),
                        ));
                    }
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    missing = true;
                }
                Err(error) => return Err(io_error("stat path component", error)),
            }
        }
        Ok(result)
    }

    fn ensure_parent_dirs(
        parent: &Path,
        root: &Path,
        uid: u32,
        gid: u32,
    ) -> Result<(), (ErrorCode, String)> {
        let relative = parent.strip_prefix(root).map_err(|_| {
            (
                ErrorCode::PolicyDenied,
                "parent escapes persistent root".into(),
            )
        })?;
        let mut current = root.to_path_buf();
        for component in relative.components() {
            let name = match component {
                std::path::Component::Normal(name) => name,
                _ => {
                    return Err((
                        ErrorCode::PolicyDenied,
                        "parent contains a non-normal component".into(),
                    ));
                }
            };
            current.push(name);
            match fs::symlink_metadata(&current) {
                Ok(metadata) if metadata.file_type().is_symlink() => {
                    return Err((
                        ErrorCode::PolicyDenied,
                        format!("parent must not be a symlink: {}", current.display()),
                    ));
                }
                Ok(metadata) if !metadata.is_dir() => {
                    return Err((
                        ErrorCode::BadParams,
                        format!("parent is not a directory: {}", current.display()),
                    ));
                }
                Ok(_) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                    fs::create_dir(&current).map_err(|e| io_error("create parent directory", e))?;
                }
                Err(error) => return Err(io_error("stat parent directory", error)),
            }
            set_owner_mode(&current, uid, gid, PERSISTENT_DATA_MODE)?;
        }
        if !is_within(root, parent)? {
            return Err((
                ErrorCode::PolicyDenied,
                "parent escapes persistent root".into(),
            ));
        }
        Ok(())
    }

    fn copy_tree(
        source: &Path,
        destination: &Path,
        uid: u32,
        gid: u32,
    ) -> Result<(), (ErrorCode, String)> {
        let metadata = fs::symlink_metadata(source).map_err(|e| io_error("stat copy source", e))?;
        reject_symlink(source, &metadata)?;
        if metadata.is_dir() {
            if let Ok(existing) = fs::symlink_metadata(destination) {
                if existing.file_type().is_symlink() || !existing.is_dir() {
                    return Err((
                        ErrorCode::BadParams,
                        format!("destination type conflicts: {}", destination.display()),
                    ));
                }
            } else {
                fs::create_dir(destination).map_err(|e| io_error("create copied directory", e))?;
            }
            set_owner_mode(destination, uid, gid, PERSISTENT_DATA_MODE)?;
            for entry in fs::read_dir(source).map_err(|e| io_error("read copy source", e))? {
                let entry = entry.map_err(|e| io_error("read copy entry", e))?;
                copy_tree(
                    &entry.path(),
                    &destination.join(entry.file_name()),
                    uid,
                    gid,
                )?;
            }
        } else if metadata.is_file() {
            if fs::symlink_metadata(destination).is_ok_and(|meta| meta.file_type().is_symlink()) {
                return Err((
                    ErrorCode::PolicyDenied,
                    format!(
                        "copy destination must not be a symlink: {}",
                        destination.display()
                    ),
                ));
            }
            if let Some(parent) = destination.parent() {
                fs::create_dir_all(parent).map_err(|e| io_error("create copy parent", e))?;
            }
            fs::copy(source, destination).map_err(|e| io_error("copy file", e))?;
            set_owner_mode(destination, uid, gid, PERSISTENT_FILE_MODE)?;
        } else {
            return Err((
                ErrorCode::PolicyDenied,
                format!("unsupported source type: {}", source.display()),
            ));
        }
        Ok(())
    }

    fn remove_tree(path: &Path) -> Result<(), (ErrorCode, String)> {
        let metadata = fs::symlink_metadata(path).map_err(|e| io_error("stat delete target", e))?;
        if metadata.is_dir() {
            fs::remove_dir_all(path).map_err(|e| io_error("remove directory", e))?;
        } else {
            fs::remove_file(path).map_err(|e| io_error("remove file", e))?;
        }
        Ok(())
    }

    fn reject_symlink(path: &Path, metadata: &fs::Metadata) -> Result<(), (ErrorCode, String)> {
        if metadata.file_type().is_symlink() {
            return Err((
                ErrorCode::PolicyDenied,
                format!("persistent file must not be a symlink: {}", path.display()),
            ));
        }
        Ok(())
    }

    fn is_within(root: &Path, candidate: &Path) -> Result<bool, (ErrorCode, String)> {
        Ok(candidate == root || candidate.starts_with(root))
    }

    fn set_owner_mode(
        path: &Path,
        uid: u32,
        gid: u32,
        mode: u32,
    ) -> Result<(), (ErrorCode, String)> {
        use std::os::unix::ffi::OsStrExt;
        let c_path = std::ffi::CString::new(path.as_os_str().as_bytes()).map_err(|_| {
            (
                ErrorCode::Internal,
                format!("NUL in persistent path: {}", path.display()),
            )
        })?;
        if unsafe { libc::chown(c_path.as_ptr(), uid, gid) } != 0 {
            return Err((
                ErrorCode::Internal,
                format!(
                    "chown persistent path {} to {uid}:{gid}: {}",
                    path.display(),
                    std::io::Error::last_os_error()
                ),
            ));
        }
        fs::set_permissions(path, fs::Permissions::from_mode(mode)).map_err(|e| {
            (
                ErrorCode::Internal,
                format!("chmod {mode:o} persistent path {}: {e}", path.display()),
            )
        })
    }

    fn modified_millis(metadata: &fs::Metadata) -> u128 {
        metadata
            .modified()
            .ok()
            .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
            .map(|duration| duration.as_millis())
            .unwrap_or(0)
    }

    fn io_error(action: &str, error: std::io::Error) -> (ErrorCode, String) {
        let code = if error.kind() == std::io::ErrorKind::NotFound {
            ErrorCode::RuntimeUnavailable
        } else {
            ErrorCode::Internal
        };
        (code, format!("{action}: {error}"))
    }

    fn encode_base64(bytes: &[u8]) -> String {
        let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
        let mut index = 0;
        while index < bytes.len() {
            let a = bytes[index];
            let b = bytes.get(index + 1).copied();
            let c = bytes.get(index + 2).copied();
            out.push(B64[(a >> 2) as usize] as char);
            out.push(B64[(((a & 0x03) << 4) | b.map_or(0, |v| v >> 4)) as usize] as char);
            out.push(b.map_or('=', |v| {
                B64[(((v & 0x0f) << 2) | c.map_or(0, |w| w >> 6)) as usize] as char
            }));
            out.push(c.map_or('=', |v| B64[(v & 0x3f) as usize] as char));
            index += 3;
        }
        out
    }

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

    fn decode_base64_byte(byte: u8) -> Result<u8, (ErrorCode, String)> {
        B64.iter()
            .position(|candidate| *candidate == byte)
            .map(|value| value as u8)
            .ok_or((ErrorCode::BadParams, "invalid base64 data".into()))
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn base64_round_trip() {
            for input in [b"".as_slice(), b"a", b"ab", b"abc", b"hello\0world"] {
                assert_eq!(decode_base64(&encode_base64(input)).unwrap(), input);
            }
        }

        #[test]
        fn guest_paths_reject_traversal_and_unknown_roots() {
            assert!(normalize_guest_path("/workspace/../etc").is_err());
            assert!(normalize_guest_path("/workspace/a//b").is_err());
            assert!(normalize_guest_path("/etc/passwd").is_ok());
            assert!(match_scope("/etc/passwd").is_none());
            assert!(match_scope("/workspace-good/file").is_none());
        }

        #[test]
        fn guest_paths_use_longest_session_alias() {
            let path = normalize_guest_path("/workspace/attachments/a.png").unwrap();
            assert_eq!(
                match_scope(&path),
                Some((Scope::Session("attachments"), "/workspace/attachments"))
            );
        }

        #[test]
        fn session_root_does_not_capture_global_paths() {
            assert_eq!(
                match_scope("/var/minis"),
                Some((Scope::SessionRoot, "/var/minis"))
            );
            assert_eq!(
                match_scope("/var/minis/memory"),
                Some((Scope::Global(HOST_MEMORY), "/var/minis/memory"))
            );
        }

        #[test]
        fn invalid_base64_is_rejected() {
            assert!(decode_base64("not-base64!").is_err());
            assert!(decode_base64("a===").is_err());
            assert!(decode_base64("AA=A").is_err());
            assert!(decode_base64("AB==").is_err());
            assert!(decode_base64("AAB=").is_err());
        }

        #[test]
        fn path_resolution_rejects_symlink_components() {
            let stamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let root = std::env::temp_dir().join(format!(
                "minisd-workspace-file-{}-{stamp}",
                std::process::id(),
            ));
            let outside = root.with_extension("outside");
            let _ = fs::remove_dir_all(&root);
            let _ = fs::remove_dir_all(&outside);
            fs::create_dir_all(&root).unwrap();
            fs::create_dir_all(&outside).unwrap();
            std::os::unix::fs::symlink(&outside, root.join("link")).unwrap();

            assert!(resolve_under(&root, "link/file.txt").is_err());

            fs::remove_dir_all(&root).unwrap();
            fs::remove_dir_all(&outside).unwrap();
        }

        #[test]
        fn copy_rejects_symlink_destination() {
            let stamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let root = std::env::temp_dir().join(format!(
                "minisd-workspace-copy-{}-{stamp}",
                std::process::id(),
            ));
            let outside = root.with_extension("outside");
            let _ = fs::remove_dir_all(&root);
            let _ = fs::remove_dir_all(&outside);
            fs::create_dir_all(&root).unwrap();
            fs::create_dir_all(&outside).unwrap();
            fs::write(root.join("source.txt"), b"source").unwrap();
            fs::write(outside.join("target.txt"), b"outside").unwrap();
            std::os::unix::fs::symlink(outside.join("target.txt"), root.join("link.txt")).unwrap();

            let error =
                copy_tree(&root.join("source.txt"), &root.join("link.txt"), 1, 1).unwrap_err();
            assert_eq!(error.0, ErrorCode::PolicyDenied);
            assert_eq!(fs::read(outside.join("target.txt")).unwrap(), b"outside");

            fs::remove_dir_all(&root).unwrap();
            fs::remove_dir_all(&outside).unwrap();
        }
    }
}

#[cfg(unix)]
pub fn handle(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    unix_impl::handle(state, params)
}

#[cfg(not(unix))]
pub fn handle(state: &AppState, params: &Value) -> Result<Value, (ErrorCode, String)> {
    if state.mock {
        return Ok(serde_json::json!({
            "mock": true,
            "operation": params
                .get("operation")
                .and_then(Value::as_str)
                .unwrap_or_default(),
        }));
    }
    Err((
        ErrorCode::RuntimeUnavailable,
        "workspace file RPC requires unix".into(),
    ))
}
