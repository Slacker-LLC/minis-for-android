use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct ProbeResult {
    pub uid: u32,
    pub gid: u32,
    pub groups: Vec<u32>,
    #[serde(rename = "capEff")]
    pub cap_eff: String,
    pub selinux: String,
    /// `None` means the SELinux enforcement status could not be read. It must
    /// not be represented as permissive or enforcing by default.
    pub enforcing: Option<bool>,
    pub kernelsu: KernelSuInfo,
    pub mock: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct KernelSuInfo {
    pub present: bool,
    pub version: String,
}

pub fn mock_probe() -> ProbeResult {
    ProbeResult {
        uid: 0,
        gid: 0,
        groups: vec![0],
        cap_eff: "000001ffffffffff".into(),
        selinux: "u:r:su:s0".into(),
        enforcing: Some(true),
        kernelsu: KernelSuInfo {
            present: true,
            version: "mock".into(),
        },
        mock: true,
    }
}

#[cfg(unix)]
pub fn live_probe() -> ProbeResult {
    let uid = unsafe { libc::geteuid() } as u32;
    let gid = unsafe { libc::getegid() } as u32;
    let mut groups = vec![0u32; 64];
    let n =
        unsafe { libc::getgroups(groups.len() as i32, groups.as_mut_ptr() as *mut libc::gid_t) };
    if n > 0 {
        groups.truncate(n as usize);
    } else {
        groups.clear();
    }
    let status = std::fs::read_to_string("/proc/self/status").unwrap_or_default();
    let cap_eff = status
        .lines()
        .find(|l| l.starts_with("CapEff:"))
        .map(|l| l.split_whitespace().nth(1).unwrap_or("0").to_string())
        .unwrap_or_else(|| "0".into());
    let selinux = std::fs::read_to_string("/proc/self/attr/current")
        .unwrap_or_else(|_| "unknown".into())
        .trim()
        .trim_end_matches('\0')
        .to_string();
    let enforcing = std::fs::read_to_string("/sys/fs/selinux/enforce")
        .ok()
        .and_then(|s| parse_enforcing(&s));
    ProbeResult {
        uid,
        gid,
        groups,
        cap_eff,
        selinux,
        enforcing,
        kernelsu: kernelsu_info(uid == 0),
        mock: false,
    }
}

fn parse_enforcing(raw: &str) -> Option<bool> {
    match raw.trim().parse::<u8>().ok()? {
        0 => Some(false),
        1 => Some(true),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::parse_enforcing;

    #[test]
    fn selinux_enforcement_parser_preserves_unknown() {
        assert_eq!(parse_enforcing("0\n"), Some(false));
        assert_eq!(parse_enforcing("1\n"), Some(true));
        assert_eq!(parse_enforcing(""), None);
        assert_eq!(parse_enforcing("not available"), None);
        assert_eq!(parse_enforcing("2"), None);
    }
}

#[cfg(unix)]
fn kernelsu_info(uid0: bool) -> KernelSuInfo {
    let mut version = String::new();
    for bin in ["/data/adb/ksu/bin/ksud", "/data/adb/ksud"] {
        if let Ok(out) = std::process::Command::new(bin).arg("-V").output() {
            let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if !s.is_empty() {
                version = s;
                break;
            }
        }
    }
    let present = uid0
        && (std::path::Path::new("/data/adb/ksu").is_dir()
            || std::path::Path::new("/data/adb/ksud").exists()
            || !version.is_empty());
    KernelSuInfo { present, version }
}

#[cfg(not(unix))]
pub fn live_probe() -> ProbeResult {
    mock_probe()
}
