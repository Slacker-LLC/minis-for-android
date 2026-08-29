use crate::protocol::ErrorCode;
use std::io;
use std::path::Path;

#[cfg(unix)]
use std::ffi::CString;

#[cfg(unix)]
const ANDROID_AID_INET: libc::gid_t = 3003;

#[cfg(unix)]
fn cstr(s: &str) -> Result<CString, String> {
    CString::new(s).map_err(|_| format!("NUL in path: {s}"))
}

#[cfg(unix)]
fn last_err(op: &str) -> String {
    format!("{op}: {}", io::Error::last_os_error())
}

#[cfg(unix)]
pub fn unshare_mount() -> Result<(), String> {
    let rc = unsafe { libc::unshare(libc::CLONE_NEWNS) };
    if rc == 0 {
        Ok(())
    } else {
        Err(last_err("unshare(CLONE_NEWNS)"))
    }
}

#[cfg(unix)]
pub fn make_rprivate_root() -> Result<(), String> {
    let src = cstr("none")?;
    let target = cstr("/")?;
    let rc = unsafe {
        libc::mount(
            src.as_ptr(),
            target.as_ptr(),
            std::ptr::null(),
            libc::MS_REC | libc::MS_PRIVATE,
            std::ptr::null(),
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(last_err("mount MS_REC|MS_PRIVATE /"))
    }
}

#[cfg(unix)]
pub fn mount_fs(
    src: &str,
    target: &str,
    fstype: &str,
    flags: libc::c_ulong,
    data: Option<&str>,
) -> Result<(), String> {
    let c_src = cstr(src)?;
    let c_tgt = cstr(target)?;
    let c_type = cstr(fstype)?;
    let c_data = match data {
        Some(d) => Some(cstr(d)?),
        None => None,
    };
    let data_ptr = c_data
        .as_ref()
        .map(|s| s.as_ptr() as *const libc::c_void)
        .unwrap_or(std::ptr::null());
    let rc = unsafe {
        libc::mount(
            c_src.as_ptr(),
            c_tgt.as_ptr(),
            c_type.as_ptr(),
            flags,
            data_ptr,
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(last_err(&format!("mount {fstype} -> {target}")))
    }
}

#[cfg(unix)]
pub fn bind_mount(src: &str, dst: &str, recursive: bool) -> Result<(), String> {
    let mut flags = libc::MS_BIND;
    if recursive {
        flags |= libc::MS_REC;
    }
    let c_src = cstr(src)?;
    let c_dst = cstr(dst)?;
    let rc = unsafe {
        libc::mount(
            c_src.as_ptr(),
            c_dst.as_ptr(),
            std::ptr::null(),
            flags,
            std::ptr::null(),
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(last_err(&format!("bind {src} -> {dst}")))
    }
}

#[cfg(unix)]
pub fn remount_ro(dst: &str) -> Result<(), String> {
    let c_dst = cstr(dst)?;
    let flags = libc::MS_BIND | libc::MS_REMOUNT | libc::MS_RDONLY | libc::MS_REC;
    let rc = unsafe {
        libc::mount(
            std::ptr::null(),
            c_dst.as_ptr(),
            std::ptr::null(),
            flags,
            std::ptr::null(),
        )
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(last_err(&format!("remount ro {dst}")))
    }
}

#[cfg(unix)]
pub fn mknod_chr(path: &str, mode: u32, major: u32, minor: u32) -> Result<(), String> {
    let c_path = cstr(path)?;
    let dev = libc::makedev(major, minor);
    let rc = unsafe { libc::mknod(c_path.as_ptr(), libc::S_IFCHR | mode, dev) };
    if rc == 0 || io::Error::last_os_error().kind() == io::ErrorKind::AlreadyExists {
        let _ = unsafe { libc::chmod(c_path.as_ptr(), mode) };
        Ok(())
    } else {
        Err(last_err(&format!("mknod {path}")))
    }
}

#[cfg(unix)]
pub fn symlink_force(old: &str, new: &str) -> Result<(), String> {
    let _ = std::fs::remove_file(new);
    let c_old = cstr(old)?;
    let c_new = cstr(new)?;
    let rc = unsafe { libc::symlink(c_old.as_ptr(), c_new.as_ptr()) };
    if rc == 0 || io::Error::last_os_error().kind() == io::ErrorKind::AlreadyExists {
        Ok(())
    } else {
        Err(last_err(&format!("symlink {new} -> {old}")))
    }
}

#[cfg(unix)]
pub fn chroot_to(path: &str) -> Result<(), String> {
    let c = cstr(path)?;
    if unsafe { libc::chroot(c.as_ptr()) } != 0 {
        return Err(last_err(&format!("chroot {path}")));
    }
    if unsafe { libc::chdir(c"/".as_ptr()) } != 0 {
        return Err(last_err("chdir /"));
    }
    Ok(())
}

#[cfg(unix)]
fn guest_supplementary_groups() -> [libc::gid_t; 1] {
    // Normal Android app processes that hold android.permission.INTERNET
    // inherit AID_INET from zygote on releases that still enforce socket
    // access through supplementary groups. minisd starts from root instead,
    // so reproduce only that network group rather than retaining root's groups.
    [ANDROID_AID_INET]
}

#[cfg(unix)]
pub fn drop_privs(uid: u32, gid: u32) -> Result<(), String> {
    let groups = guest_supplementary_groups();
    if unsafe { libc::setgroups(groups.len(), groups.as_ptr()) } != 0 {
        return Err(last_err("setgroups(AID_INET)"));
    }
    if unsafe { libc::setgid(gid) } != 0 {
        return Err(last_err("setgid"));
    }
    if unsafe { libc::setuid(uid) } != 0 {
        return Err(last_err("setuid"));
    }
    let _ = unsafe { libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) };
    Ok(())
}

/// 05 §4: admin (uid==0) entering the keeper mount namespace must not keep
/// any privileges. Sets no_new_privs, drops the capability bounding set and
/// empties the effective/permitted/inheritable capability sets.
#[cfg(unix)]
pub fn lockdown_no_privs() -> Result<(), String> {
    unsafe {
        if libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0 {
            return Err(last_err("prctl(PR_SET_NO_NEW_PRIVS)"));
        }
        // CAP_LAST_CAP conservatively taken as 40; old kernels may report
        // EINVAL for gaps, so per-cap failures are ignored.
        for cap in 0..=40 {
            libc::prctl(libc::PR_CAPBSET_DROP, cap, 0, 0, 0);
        }
    }
    #[repr(C)]
    struct CapHeader {
        version: u32,
        pid: i32,
    }
    #[repr(C)]
    struct CapData {
        effective: u32,
        permitted: u32,
        inheritable: u32,
    }
    let header = CapHeader {
        version: 0x2008_0522, // LINUX_CAPABILITY_VERSION_3
        pid: 0,
    };
    let data = [
        CapData {
            effective: 0,
            permitted: 0,
            inheritable: 0,
        },
        CapData {
            effective: 0,
            permitted: 0,
            inheritable: 0,
        },
    ];
    let rc = unsafe { libc::syscall(libc::SYS_capset, &header as *const CapHeader, data.as_ptr()) };
    if rc != 0 {
        return Err(last_err("capset"));
    }
    Ok(())
}

#[cfg(unix)]
pub fn setns_mnt(pid: i32) -> Result<(), String> {
    let path = format!("/proc/{pid}/ns/mnt");
    let c = cstr(&path)?;
    let fd = unsafe { libc::open(c.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        return Err(last_err(&format!("open {path}")));
    }
    let rc = unsafe { libc::setns(fd, libc::CLONE_NEWNS) };
    unsafe { libc::close(fd) };
    if rc == 0 {
        Ok(())
    } else {
        Err(last_err("setns CLONE_NEWNS"))
    }
}

#[cfg(unix)]
#[allow(dead_code)]
pub fn join_uid_cgroup(uid: u32) -> Result<(), String> {
    if uid == 0 {
        return Ok(());
    }
    let pid = std::process::id().to_string();
    let mut tried = Vec::new();
    let roots = [
        format!("/sys/fs/cgroup/apps/uid_{uid}"),
        format!("/sys/fs/cgroup/uid_{uid}"),
    ];
    for root in &roots {
        if let Ok(rd) = std::fs::read_dir(root) {
            for ent in rd.flatten() {
                let n = ent.file_name().to_string_lossy().into_owned();
                if n.starts_with("pid_") {
                    let leaf = ent.path().join("cgroup.procs");
                    match std::fs::write(&leaf, &pid) {
                        Ok(()) => return Ok(()),
                        Err(e) => tried.push(format!("{}: {e}", leaf.display())),
                    }
                }
            }
        }
        let parent = format!("{root}/cgroup.procs");
        match std::fs::write(&parent, &pid) {
            Ok(()) => return Ok(()),
            Err(e) => tried.push(format!("{parent}: {e}")),
        }
    }
    Err(tried.join("; "))
}

#[cfg(unix)]
pub fn set_process_name(name: &str) {
    if let Ok(c) = cstr(name) {
        unsafe {
            libc::prctl(libc::PR_SET_NAME, c.as_ptr());
        }
    }
}

#[cfg(unix)]
pub fn setup_rootfs_mounts(
    rootfs: &str,
    workspace: &str,
    memory: &str,
    skills: &str,
    shared: &str,
    home: &str,
) -> Result<(), String> {
    use crate::layout::{
        GUEST_HOME, HOST_HOME, HOST_MEMORY, HOST_SHARED, HOST_SKILLS, HOST_WORKSPACE,
    };
    let workspace = if workspace.is_empty() {
        HOST_WORKSPACE
    } else {
        workspace
    };
    let memory = if memory.is_empty() {
        HOST_MEMORY
    } else {
        memory
    };
    let skills = if skills.is_empty() {
        HOST_SKILLS
    } else {
        skills
    };
    let shared = if shared.is_empty() {
        HOST_SHARED
    } else {
        shared
    };
    let home = if home.is_empty() { HOST_HOME } else { home };

    let root = Path::new(rootfs);
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
        "home/minis",
    ] {
        std::fs::create_dir_all(root.join(rel)).map_err(|e| format!("mkdir {rel}: {e}"))?;
    }

    let proc = root.join("proc").to_string_lossy().into_owned();
    match mount_fs(
        "proc",
        &proc,
        "proc",
        libc::MS_NOSUID | libc::MS_NOEXEC | libc::MS_NODEV,
        Some("hidepid=2"),
    ) {
        Ok(()) => {}
        Err(_) => mount_fs(
            "proc",
            &proc,
            "proc",
            libc::MS_NOSUID | libc::MS_NOEXEC | libc::MS_NODEV,
            None,
        )?,
    }

    let sys = root.join("sys").to_string_lossy().into_owned();
    // B14: /sys must be bound AND remounted read-only; a writable /sys inside
    // the guest namespace is host-writable — fail the start instead of
    // continuing silently.
    bind_mount("/sys", &sys, true)?;
    remount_ro(&sys)?;

    let dev = root.join("dev").to_string_lossy().into_owned();
    mount_fs(
        "tmpfs",
        &dev,
        "tmpfs",
        libc::MS_NOSUID,
        Some("mode=0755,size=64m"),
    )?;
    mknod_chr(&format!("{dev}/null"), 0o666, 1, 3)?;
    mknod_chr(&format!("{dev}/zero"), 0o666, 1, 5)?;
    mknod_chr(&format!("{dev}/full"), 0o666, 1, 7)?;
    mknod_chr(&format!("{dev}/random"), 0o666, 1, 8)?;
    mknod_chr(&format!("{dev}/urandom"), 0o666, 1, 9)?;
    mknod_chr(&format!("{dev}/tty"), 0o666, 5, 0)?;
    std::fs::create_dir_all(root.join("dev/pts")).ok();
    std::fs::create_dir_all(root.join("dev/shm")).ok();
    let pts = root.join("dev/pts").to_string_lossy().into_owned();
    if mount_fs(
        "devpts",
        &pts,
        "devpts",
        libc::MS_NOSUID | libc::MS_NOEXEC,
        Some("newinstance,ptmxmode=0666,mode=0620"),
    )
    .is_err()
    {
        let _ = mount_fs(
            "devpts",
            &pts,
            "devpts",
            libc::MS_NOSUID | libc::MS_NOEXEC,
            Some("ptmxmode=0666"),
        );
    }
    let _ = symlink_force("pts/ptmx", &format!("{dev}/ptmx"));
    let _ = symlink_force("/proc/self/fd", &format!("{dev}/fd"));
    let _ = symlink_force("/proc/self/fd/0", &format!("{dev}/stdin"));
    let _ = symlink_force("/proc/self/fd/1", &format!("{dev}/stdout"));
    let _ = symlink_force("/proc/self/fd/2", &format!("{dev}/stderr"));
    let shm = root.join("dev/shm").to_string_lossy().into_owned();
    mount_fs(
        "tmpfs",
        &shm,
        "tmpfs",
        libc::MS_NOSUID | libc::MS_NODEV,
        Some("mode=1777"),
    )?;

    let tmp = root.join("tmp").to_string_lossy().into_owned();
    mount_fs(
        "tmpfs",
        &tmp,
        "tmpfs",
        libc::MS_NOSUID | libc::MS_NODEV,
        Some("mode=1777"),
    )?;
    let run = root.join("run").to_string_lossy().into_owned();
    mount_fs(
        "tmpfs",
        &run,
        "tmpfs",
        libc::MS_NOSUID | libc::MS_NODEV,
        Some("mode=0755"),
    )?;

    let ws = root.join("workspace").to_string_lossy().into_owned();
    bind_mount(workspace, &ws, false)?;
    let _ = bind_mount(memory, &root.join("memory").to_string_lossy(), false);
    let _ = bind_mount(skills, &root.join("skills").to_string_lossy(), false);
    let _ = bind_mount(shared, &root.join("shared").to_string_lossy(), false);
    bind_mount(
        home,
        &root
            .join(GUEST_HOME.trim_start_matches('/'))
            .to_string_lossy(),
        false,
    )?;
    Ok(())
}

/// Overlay the four session-scoped resources in an exec-private mount
/// namespace. The keeper's global workspace remains untouched and shared
/// resources (`/memory`, `/skills`, `/shared`) continue to come from it.
#[cfg(unix)]
pub fn setup_session_mounts(rootfs: &str, session_root: &str) -> Result<(), String> {
    let root = Path::new(rootfs);
    let session = Path::new(session_root);
    let workspace_src = session.join("workspace");
    let workspace_dst = root.join("workspace");
    if !workspace_src.is_dir() {
        return Err(format!(
            "session workspace missing: {}",
            workspace_src.display()
        ));
    }
    bind_mount(
        &workspace_src.to_string_lossy(),
        &workspace_dst.to_string_lossy(),
        false,
    )?;

    for subdir in ["attachments", "offloads", "browser"] {
        let src = session.join(subdir);
        if !src.is_dir() {
            return Err(format!("session mount missing: {}", src.display()));
        }
        // `/var/minis/<subdir>` is a symlink into `/workspace/<subdir>`, so
        // mounting the sibling session directory here keeps shell paths and
        // Android host-side resolvers on the same layout.
        let dst = workspace_dst.join(subdir);
        if dst
            .symlink_metadata()
            .is_ok_and(|meta| meta.file_type().is_symlink())
        {
            return Err(format!(
                "session mountpoint must not be a symlink: {}",
                dst.display()
            ));
        }
        std::fs::create_dir_all(&dst)
            .map_err(|e| format!("create session mountpoint {}: {e}", dst.display()))?;
        bind_mount(&src.to_string_lossy(), &dst.to_string_lossy(), false)?;
    }
    Ok(())
}

/// PoC used by `mount.prepare`: fork + unshare + MS_PRIVATE, then exit.
/// Kept for P1 compatibility; P2 keeper uses the helpers above in a fresh process.
#[cfg(unix)]
pub fn poc_unshare_make_rprivate() -> Result<(), ErrorCode> {
    let pid = unsafe { libc::fork() };
    if pid < 0 {
        return Err(ErrorCode::Internal);
    }
    if pid == 0 {
        unsafe {
            if libc::unshare(libc::CLONE_NEWNS) != 0 {
                libc::_exit(1);
            }
            let src = b"none\0";
            let target = b"/\0";
            let flags = libc::MS_REC | libc::MS_PRIVATE;
            if libc::mount(
                src.as_ptr() as *const libc::c_char,
                target.as_ptr() as *const libc::c_char,
                std::ptr::null(),
                flags,
                std::ptr::null(),
            ) != 0
            {
                libc::_exit(2);
            }
            libc::_exit(0);
        }
    }
    let mut status = 0;
    let w = unsafe { libc::waitpid(pid, &mut status, 0) };
    if w != pid {
        return Err(ErrorCode::Internal);
    }
    if libc::WIFEXITED(status) && libc::WEXITSTATUS(status) == 0 {
        Ok(())
    } else {
        Err(ErrorCode::RuntimeUnavailable)
    }
}

#[cfg(not(unix))]
pub fn poc_unshare_make_rprivate() -> Result<(), ErrorCode> {
    Err(ErrorCode::RuntimeUnavailable)
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn guest_network_group_matches_android_internet_permission() {
        assert_eq!(guest_supplementary_groups(), [ANDROID_AID_INET]);
        assert_eq!(ANDROID_AID_INET, 3003);
    }
}
