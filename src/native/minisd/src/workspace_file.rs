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
    use std::ffi::{CStr, CString};
    use std::fs::{self, File};
    use std::io::{Read, Seek, SeekFrom, Write};
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::MetadataExt;
    use std::os::unix::fs::PermissionsExt;
    use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
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
        relative: PathBuf,
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
        let mut file = open_existing(&resolved, "open file")?;
        let metadata = file.metadata().map_err(|e| io_error("stat file", e))?;
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
        if resolved.relative.as_os_str().is_empty() {
            return Err((
                ErrorCode::PolicyDenied,
                "cannot write a persistent directory".into(),
            ));
        }
        let create_dirs = params
            .get("create_dirs")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        let (parent, name) = open_parent(&resolved, create_dirs, uid, gid)?;
        let mut flags = libc::O_WRONLY | libc::O_CREAT | libc::O_CLOEXEC | libc::O_NOFOLLOW;
        flags |= if append {
            libc::O_APPEND
        } else {
            libc::O_TRUNC
        };
        let mut file = open_at_file(parent.as_raw_fd(), &name, flags, PERSISTENT_FILE_MODE)
            .map_err(|e| io_error("open file for write", e))?;
        let metadata = file
            .metadata()
            .map_err(|e| io_error("stat file for write", e))?;
        if metadata.is_dir() {
            return Err((
                ErrorCode::BadParams,
                format!("path is a directory: {}", resolved.path.display()),
            ));
        }
        file.write_all(&bytes)
            .map_err(|e| io_error("write file", e))?;
        file.sync_data().map_err(|e| io_error("sync file", e))?;
        set_owner_mode_fd(&file, uid, gid, PERSISTENT_FILE_MODE)?;
        let size = file
            .metadata()
            .map_err(|e| io_error("stat written file", e))?
            .len();
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "bytes": bytes.len(),
            "size": size,
            "append": append,
        }))
    }

    fn copy(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let source = resolve_param_path_for(params, "source", "source_session_id", uid, gid)?;
        let destination =
            resolve_param_path_for(params, "destination", "destination_session_id", uid, gid)?;
        if source.relative.as_os_str().is_empty() || destination.relative.as_os_str().is_empty() {
            return Err((
                ErrorCode::PolicyDenied,
                "copying persistent roots is not allowed".into(),
            ));
        }
        if source.path == destination.path {
            return Err((ErrorCode::BadParams, "source == destination".into()));
        }
        let (source_parent, source_name) = open_parent(&source, false, uid, gid)?;
        let source_kind = entry_kind(source_parent.as_raw_fd(), &source_name)
            .map_err(|e| io_error("stat source", e))?;
        if source_kind == EntryKind::Symlink {
            return Err((
                ErrorCode::PolicyDenied,
                format!(
                    "persistent file must not be a symlink: {}",
                    source.path.display()
                ),
            ));
        }
        let source_meta = open_entry(&source_parent, &source_name, source_kind, "open source")?
            .metadata()
            .map_err(|e| io_error("stat source", e))?;
        if source_meta.is_dir() && is_within(&source.path, &destination.path)? {
            return Err((
                ErrorCode::PolicyDenied,
                "destination cannot be inside source".into(),
            ));
        }
        let (destination_parent, destination_name) = open_parent(&destination, true, uid, gid)?;
        copy_entry(
            &source_parent,
            &source_name,
            &destination_parent,
            &destination_name,
            uid,
            gid,
        )?;
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
        if source.relative.as_os_str().is_empty() || destination.relative.as_os_str().is_empty() {
            return Err((
                ErrorCode::PolicyDenied,
                "moving persistent roots is not allowed".into(),
            ));
        }
        if source.path == destination.path {
            return Err((ErrorCode::BadParams, "source == destination".into()));
        }
        let (source_parent, source_name) = open_parent(&source, false, uid, gid)?;
        let source_kind = entry_kind(source_parent.as_raw_fd(), &source_name)
            .map_err(|e| io_error("stat source", e))?;
        if source_kind == EntryKind::Symlink {
            return Err((
                ErrorCode::PolicyDenied,
                format!(
                    "persistent file must not be a symlink: {}",
                    source.path.display()
                ),
            ));
        }
        let source_meta = open_entry(&source_parent, &source_name, source_kind, "open source")?
            .metadata()
            .map_err(|e| io_error("stat source", e))?;
        if source_meta.is_dir() && is_within(&source.path, &destination.path)? {
            return Err((
                ErrorCode::PolicyDenied,
                "destination cannot be inside source".into(),
            ));
        }
        let (destination_parent, destination_name) = open_parent(&destination, true, uid, gid)?;
        if entry_kind(destination_parent.as_raw_fd(), &destination_name)
            .is_ok_and(|kind| kind == EntryKind::Symlink)
        {
            return Err((
                ErrorCode::PolicyDenied,
                format!(
                    "destination must not be a symlink: {}",
                    destination.path.display()
                ),
            ));
        }
        if let Err(error) = rename_at(
            source_parent.as_raw_fd(),
            &source_name,
            destination_parent.as_raw_fd(),
            &destination_name,
        ) {
            if error.raw_os_error() != Some(libc::EXDEV) {
                return Err(io_error("move file", error));
            }
            copy_entry(
                &source_parent,
                &source_name,
                &destination_parent,
                &destination_name,
                uid,
                gid,
            )?;
            remove_entry(&source_parent, &source_name)?;
        }
        Ok(serde_json::json!({
            "source": params.get("source").and_then(Value::as_str).unwrap_or_default(),
            "destination": params.get("destination").and_then(Value::as_str).unwrap_or_default(),
            "type": if source_meta.is_dir() { "dir" } else { "file" },
        }))
    }

    fn delete(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
        if resolved.relative.as_os_str().is_empty() {
            return Err((
                ErrorCode::PolicyDenied,
                "cannot delete a persistent root".into(),
            ));
        }
        let (parent, name) = open_parent(&resolved, false, uid, gid)?;
        remove_entry(&parent, &name)?;
        let _ = (uid, gid);
        Ok(serde_json::json!({
            "path": params.get("path").and_then(Value::as_str).unwrap_or_default(),
            "deleted": true,
        }))
    }

    fn list(params: &Value, uid: u32, gid: u32) -> Result<Value, (ErrorCode, String)> {
        let resolved = resolve_param_path(params, "path", uid, gid)?;
        let directory = open_existing(&resolved, "open directory")?;
        let metadata = directory
            .metadata()
            .map_err(|e| io_error("stat directory", e))?;
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
        for name in
            read_dir_names(directory.as_raw_fd()).map_err(|e| io_error("list directory", e))?
        {
            let entry = entry_kind(directory.as_raw_fd(), &name)
                .map_err(|e| io_error("stat directory entry", e))?;
            let (kind, size, modified) = match entry {
                EntryKind::Symlink => ("link", 0, 0),
                EntryKind::Directory => {
                    let child = open_entry(&directory, &name, entry, "open directory entry")?;
                    let metadata = child
                        .metadata()
                        .map_err(|e| io_error("stat directory entry", e))?;
                    ("dir", 0, modified_millis(&metadata))
                }
                EntryKind::Regular => {
                    let child = open_entry(&directory, &name, entry, "open directory entry")?;
                    let metadata = child
                        .metadata()
                        .map_err(|e| io_error("stat directory entry", e))?;
                    ("file", metadata.len(), modified_millis(&metadata))
                }
                EntryKind::Other => ("other", 0, 0),
            };
            entries.push(serde_json::json!({
                "name": name.to_string_lossy(),
                "type": kind,
                "size": size,
                "modified": modified,
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
        let file = open_existing(&resolved, "open path")?;
        let metadata = file.metadata().map_err(|e| io_error("stat path", e))?;
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
        let relative = if rest.is_empty() {
            PathBuf::new()
        } else {
            PathBuf::from(&rest)
        };
        Ok(ResolvedPath {
            path,
            root,
            relative,
        })
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
        for component in components {
            result.push(component);
        }
        Ok(result)
    }

    fn is_within(root: &Path, candidate: &Path) -> Result<bool, (ErrorCode, String)> {
        Ok(candidate == root || candidate.starts_with(root))
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    enum EntryKind {
        Directory,
        Regular,
        Symlink,
        Other,
    }

    const SYS_OPENAT2: libc::c_long = 437;
    const RESOLVE_NO_MAGICLINKS: u64 = 0x02;
    const RESOLVE_NO_SYMLINKS: u64 = 0x04;
    const RESOLVE_BENEATH: u64 = 0x08;

    #[repr(C)]
    struct OpenHow {
        flags: u64,
        mode: u64,
        resolve: u64,
    }

    fn open_existing(resolved: &ResolvedPath, action: &str) -> Result<File, (ErrorCode, String)> {
        let root = open_root_fd(&resolved.root).map_err(|e| io_error(action, e))?;
        open_relative(
            root.as_raw_fd(),
            &resolved.relative,
            libc::O_RDONLY | libc::O_CLOEXEC,
            0,
        )
        .map_err(|e| io_error(action, e))
    }

    fn open_parent(
        resolved: &ResolvedPath,
        create_dirs: bool,
        uid: u32,
        gid: u32,
    ) -> Result<(File, CString), (ErrorCode, String)> {
        let name = resolved
            .relative
            .file_name()
            .ok_or((ErrorCode::PolicyDenied, "file has no parent".into()))?;
        let name = CString::new(name.as_bytes())
            .map_err(|_| (ErrorCode::BadParams, "path component contains NUL".into()))?;
        let parent_relative = resolved.relative.parent().unwrap_or_else(|| Path::new(""));
        let root = open_root_fd(&resolved.root).map_err(|e| io_error("open persistent root", e))?;
        let parent = open_directory_chain(root.as_raw_fd(), parent_relative, create_dirs, uid, gid)
            .map_err(|e| io_error("open parent directory", e))?;
        Ok((parent, name))
    }

    fn open_root_fd(root: &Path) -> std::io::Result<File> {
        let path = CString::new(root.as_os_str().as_bytes())
            .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
        match try_openat2(
            libc::AT_FDCWD,
            &path,
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            0,
            RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS,
        ) {
            Ok(fd) => unsafe { Ok(File::from_raw_fd(fd)) },
            Err(error)
                if error.raw_os_error() == Some(libc::ENOSYS)
                    || error.raw_os_error() == Some(libc::EINVAL) =>
            {
                open_absolute_without_symlinks(root)
            }
            Err(error) => Err(error),
        }
    }

    fn open_absolute_without_symlinks(path: &Path) -> std::io::Result<File> {
        let root_name = CString::new("/").expect("literal has no NUL");
        let mut current = open_at_file(
            libc::AT_FDCWD,
            &root_name,
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            0,
        )?;
        for component in path.components() {
            let std::path::Component::Normal(component) = component else {
                continue;
            };
            let name = CString::new(component.as_bytes())
                .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
            let next = open_at_file(
                current.as_raw_fd(),
                &name,
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                0,
            )?;
            current = next;
        }
        Ok(current)
    }

    fn open_relative(
        root_fd: RawFd,
        relative: &Path,
        flags: i32,
        mode: u32,
    ) -> std::io::Result<File> {
        if relative.as_os_str().is_empty() {
            return dup_fd(root_fd).map(|fd| unsafe { File::from_raw_fd(fd) });
        }
        let path = CString::new(relative.as_os_str().as_bytes())
            .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
        match try_openat2(
            root_fd,
            &path,
            flags | libc::O_CLOEXEC,
            mode,
            RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS,
        ) {
            Ok(fd) => unsafe { Ok(File::from_raw_fd(fd)) },
            Err(error)
                if error.raw_os_error() == Some(libc::ENOSYS)
                    || error.raw_os_error() == Some(libc::EINVAL) =>
            {
                open_relative_without_symlinks(root_fd, relative, flags, mode)
            }
            Err(error) => Err(error),
        }
    }

    fn open_relative_without_symlinks(
        root_fd: RawFd,
        relative: &Path,
        flags: i32,
        mode: u32,
    ) -> std::io::Result<File> {
        let mut current = dup_fd(root_fd).map(|fd| unsafe { File::from_raw_fd(fd) })?;
        let components = relative.components().collect::<Vec<_>>();
        for (index, component) in components.iter().enumerate() {
            let std::path::Component::Normal(component) = component else {
                return Err(std::io::Error::from_raw_os_error(libc::EINVAL));
            };
            let name = CString::new(component.as_bytes())
                .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
            let component_flags = if index + 1 == components.len() {
                flags
            } else {
                libc::O_RDONLY | libc::O_DIRECTORY
            };
            let next = open_at_file(
                current.as_raw_fd(),
                &name,
                component_flags | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                if index + 1 == components.len() {
                    mode
                } else {
                    0
                },
            )?;
            current = next;
        }
        Ok(current)
    }

    fn open_directory_chain(
        root_fd: RawFd,
        relative: &Path,
        create: bool,
        uid: u32,
        gid: u32,
    ) -> std::io::Result<File> {
        let mut current = dup_fd(root_fd).map(|fd| unsafe { File::from_raw_fd(fd) })?;
        for component in relative.components() {
            let std::path::Component::Normal(component) = component else {
                return Err(std::io::Error::from_raw_os_error(libc::EINVAL));
            };
            let name = CString::new(component.as_bytes())
                .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
            let next = match open_at_file(
                current.as_raw_fd(),
                &name,
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                0,
            ) {
                Ok(file) => file,
                Err(error) if create && error.kind() == std::io::ErrorKind::NotFound => {
                    if unsafe {
                        libc::mkdirat(current.as_raw_fd(), name.as_ptr(), PERSISTENT_DATA_MODE)
                    } != 0
                    {
                        let mkdir_error = std::io::Error::last_os_error();
                        if mkdir_error.raw_os_error() != Some(libc::EEXIST) {
                            return Err(mkdir_error);
                        }
                    }
                    open_at_file(
                        current.as_raw_fd(),
                        &name,
                        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                        0,
                    )?
                }
                Err(error) => return Err(error),
            };
            set_owner_mode_fd(&next, uid, gid, PERSISTENT_DATA_MODE)
                .map_err(|(_, detail)| std::io::Error::other(detail))?;
            current = next;
        }
        Ok(current)
    }

    fn open_entry(
        parent: &File,
        name: &CStr,
        kind: EntryKind,
        action: &str,
    ) -> Result<File, (ErrorCode, String)> {
        let flags = match kind {
            EntryKind::Directory => libc::O_RDONLY | libc::O_DIRECTORY,
            EntryKind::Regular => libc::O_RDONLY,
            EntryKind::Other => libc::O_RDONLY,
            EntryKind::Symlink => {
                return Err((ErrorCode::PolicyDenied, "symlink is not allowed".into()))
            }
        };
        open_at_file(
            parent.as_raw_fd(),
            name,
            flags | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            0,
        )
        .map_err(|e| io_error(action, e))
    }

    fn open_at_file(dirfd: RawFd, name: &CStr, flags: i32, mode: u32) -> std::io::Result<File> {
        open_at(dirfd, name, flags, mode).map(|fd| unsafe { File::from_raw_fd(fd) })
    }

    fn open_at(dirfd: RawFd, name: &CStr, flags: i32, mode: u32) -> std::io::Result<RawFd> {
        let fd = unsafe { libc::openat(dirfd, name.as_ptr(), flags, mode) };
        if fd < 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(fd)
        }
    }

    fn try_openat2(
        dirfd: RawFd,
        path: &CStr,
        flags: i32,
        mode: u32,
        resolve: u64,
    ) -> std::io::Result<RawFd> {
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            let how = OpenHow {
                flags: flags as u64,
                mode: mode as u64,
                resolve,
            };
            let fd = unsafe {
                libc::syscall(
                    SYS_OPENAT2,
                    dirfd,
                    path.as_ptr(),
                    &how as *const OpenHow,
                    std::mem::size_of::<OpenHow>(),
                )
            };
            if fd < 0 {
                Err(std::io::Error::last_os_error())
            } else {
                Ok(fd as RawFd)
            }
        }
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        {
            let _ = (dirfd, path, flags, mode, resolve);
            Err(std::io::Error::from_raw_os_error(libc::ENOSYS))
        }
    }

    fn dup_fd(fd: RawFd) -> std::io::Result<RawFd> {
        let duplicate = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
        if duplicate < 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(duplicate)
        }
    }

    fn entry_kind(parent_fd: RawFd, name: &CStr) -> std::io::Result<EntryKind> {
        let mut stat = unsafe { std::mem::zeroed::<libc::stat>() };
        if unsafe {
            libc::fstatat(
                parent_fd,
                name.as_ptr(),
                &mut stat,
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } != 0
        {
            return Err(std::io::Error::last_os_error());
        }
        let mode = stat.st_mode as libc::mode_t;
        Ok(if mode & libc::S_IFMT == libc::S_IFLNK {
            EntryKind::Symlink
        } else if mode & libc::S_IFMT == libc::S_IFDIR {
            EntryKind::Directory
        } else if mode & libc::S_IFMT == libc::S_IFREG {
            EntryKind::Regular
        } else {
            EntryKind::Other
        })
    }

    fn optional_entry_kind(parent_fd: RawFd, name: &CStr) -> std::io::Result<Option<EntryKind>> {
        match entry_kind(parent_fd, name) {
            Ok(kind) => Ok(Some(kind)),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error),
        }
    }

    fn read_dir_names(dir_fd: RawFd) -> std::io::Result<Vec<CString>> {
        let duplicate = dup_fd(dir_fd)?;
        let directory = unsafe { libc::fdopendir(duplicate) };
        if directory.is_null() {
            unsafe { libc::close(duplicate) };
            return Err(std::io::Error::last_os_error());
        }
        let directory = OwnedDir(directory);
        let mut names = Vec::new();
        loop {
            let entry = unsafe { libc::readdir(directory.0) };
            if entry.is_null() {
                let error = std::io::Error::last_os_error();
                if error.raw_os_error().unwrap_or(0) != 0 {
                    return Err(error);
                }
                break;
            }
            let name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
            if name.to_bytes() == b"." || name.to_bytes() == b".." {
                continue;
            }
            names.push(
                CString::new(name.to_bytes())
                    .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?,
            );
        }
        Ok(names)
    }

    struct OwnedDir(*mut libc::DIR);

    impl Drop for OwnedDir {
        fn drop(&mut self) {
            unsafe { libc::closedir(self.0) };
        }
    }

    fn copy_entry(
        source_parent: &File,
        source_name: &CStr,
        destination_parent: &File,
        destination_name: &CStr,
        uid: u32,
        gid: u32,
    ) -> Result<(), (ErrorCode, String)> {
        let source_kind = entry_kind(source_parent.as_raw_fd(), source_name)
            .map_err(|e| io_error("stat copy source", e))?;
        if source_kind == EntryKind::Symlink {
            return Err((
                ErrorCode::PolicyDenied,
                "copy source must not be a symlink".into(),
            ));
        }
        match source_kind {
            EntryKind::Directory => {
                let source_dir = open_entry(
                    source_parent,
                    source_name,
                    EntryKind::Directory,
                    "open copy source directory",
                )?;
                let destination_dir =
                    match optional_entry_kind(destination_parent.as_raw_fd(), destination_name)
                        .map_err(|e| io_error("stat copy destination", e))?
                    {
                        Some(EntryKind::Symlink) => {
                            return Err((
                                ErrorCode::PolicyDenied,
                                "copy destination must not be a symlink".into(),
                            ))
                        }
                        Some(EntryKind::Directory) => open_entry(
                            destination_parent,
                            destination_name,
                            EntryKind::Directory,
                            "open copy destination directory",
                        )?,
                        Some(_) => {
                            return Err((ErrorCode::BadParams, "destination type conflicts".into()))
                        }
                        None => {
                            if unsafe {
                                libc::mkdirat(
                                    destination_parent.as_raw_fd(),
                                    destination_name.as_ptr(),
                                    PERSISTENT_DATA_MODE,
                                )
                            } != 0
                            {
                                let error = std::io::Error::last_os_error();
                                if error.raw_os_error() != Some(libc::EEXIST) {
                                    return Err(io_error("create copied directory", error));
                                }
                            }
                            open_entry(
                                destination_parent,
                                destination_name,
                                EntryKind::Directory,
                                "open copied directory",
                            )?
                        }
                    };
                set_owner_mode_fd(&destination_dir, uid, gid, PERSISTENT_DATA_MODE)?;
                for child in read_dir_names(source_dir.as_raw_fd())
                    .map_err(|e| io_error("read copy source", e))?
                {
                    copy_entry(&source_dir, &child, &destination_dir, &child, uid, gid)?;
                }
            }
            EntryKind::Regular => {
                match optional_entry_kind(destination_parent.as_raw_fd(), destination_name)
                    .map_err(|e| io_error("stat copy destination", e))?
                {
                    Some(EntryKind::Symlink) => {
                        return Err((
                            ErrorCode::PolicyDenied,
                            "copy destination must not be a symlink".into(),
                        ))
                    }
                    Some(EntryKind::Directory) => {
                        return Err((ErrorCode::BadParams, "destination type conflicts".into()))
                    }
                    _ => {}
                }
                let mut source_file = open_entry(
                    source_parent,
                    source_name,
                    EntryKind::Regular,
                    "open copy source",
                )?;
                let mut destination_file = open_at_file(
                    destination_parent.as_raw_fd(),
                    destination_name,
                    libc::O_WRONLY
                        | libc::O_CREAT
                        | libc::O_TRUNC
                        | libc::O_CLOEXEC
                        | libc::O_NOFOLLOW,
                    PERSISTENT_FILE_MODE,
                )
                .map_err(|e| io_error("open copy destination", e))?;
                std::io::copy(&mut source_file, &mut destination_file)
                    .map_err(|e| io_error("copy file", e))?;
                destination_file
                    .sync_data()
                    .map_err(|e| io_error("sync copied file", e))?;
                set_owner_mode_fd(&destination_file, uid, gid, PERSISTENT_FILE_MODE)?;
            }
            EntryKind::Other => {
                return Err((ErrorCode::PolicyDenied, "unsupported source type".into()))
            }
            EntryKind::Symlink => unreachable!(),
        }
        Ok(())
    }

    fn remove_entry(parent: &File, name: &CStr) -> Result<(), (ErrorCode, String)> {
        let kind =
            entry_kind(parent.as_raw_fd(), name).map_err(|e| io_error("stat delete target", e))?;
        if kind == EntryKind::Symlink {
            return Err((
                ErrorCode::PolicyDenied,
                "persistent file must not be a symlink".into(),
            ));
        }
        if kind == EntryKind::Directory {
            let directory = open_entry(parent, name, kind, "open delete directory")?;
            for child in read_dir_names(directory.as_raw_fd())
                .map_err(|e| io_error("read delete directory", e))?
            {
                remove_entry(&directory, &child)?;
            }
            if unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), libc::AT_REMOVEDIR) } != 0
            {
                return Err(io_error(
                    "remove directory",
                    std::io::Error::last_os_error(),
                ));
            }
        } else if unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), 0) } != 0 {
            return Err(io_error("remove file", std::io::Error::last_os_error()));
        }
        Ok(())
    }

    fn rename_at(
        source_parent: RawFd,
        source_name: &CStr,
        destination_parent: RawFd,
        destination_name: &CStr,
    ) -> std::io::Result<()> {
        if unsafe {
            libc::renameat(
                source_parent,
                source_name.as_ptr(),
                destination_parent,
                destination_name.as_ptr(),
            )
        } != 0
        {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    fn set_owner_mode_fd(
        file: &File,
        uid: u32,
        gid: u32,
        mode: u32,
    ) -> Result<(), (ErrorCode, String)> {
        if unsafe { libc::fchown(file.as_raw_fd(), uid, gid) } != 0 {
            return Err((
                ErrorCode::Internal,
                format!(
                    "chown persistent fd to {uid}:{gid}: {}",
                    std::io::Error::last_os_error()
                ),
            ));
        }
        if unsafe { libc::fchmod(file.as_raw_fd(), mode) } != 0 {
            return Err((
                ErrorCode::Internal,
                format!(
                    "chmod {mode:o} persistent fd: {}",
                    std::io::Error::last_os_error()
                ),
            ));
        }
        Ok(())
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
        } else if error.raw_os_error() == Some(libc::ELOOP) {
            ErrorCode::PolicyDenied
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

            let root_fd = open_root_fd(&root).unwrap();
            let error = open_relative(
                root_fd.as_raw_fd(),
                Path::new("link/file.txt"),
                libc::O_RDONLY,
                0,
            )
            .unwrap_err();
            assert_eq!(error.raw_os_error(), Some(libc::ELOOP));

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

            let root_fd = open_root_fd(&root).unwrap();
            let source = CString::new("source.txt").unwrap();
            let destination = CString::new("link.txt").unwrap();
            let error = copy_entry(&root_fd, &source, &root_fd, &destination, 1, 1).unwrap_err();
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
