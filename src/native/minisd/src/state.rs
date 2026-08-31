use crate::policy::PolicyFile;
use crate::rate::RateLimiter;
use crate::session::SessionTable;
use std::collections::HashMap;
use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
pub struct PendingConfirm {
    pub method: String,
    /// A one-time approval is valid only for the exact structured request.
    /// `serde_json::Value` object equality is independent of key order.
    pub params: serde_json::Value,
    pub expires: Instant,
}

#[derive(Debug, Default)]
pub struct UbuntuState {
    pub running: bool,
    pub pid: Option<i32>,
    pub rootfs: String,
    pub sessions_root: String,
    pub workspace: String,
    pub memory: String,
    pub skills: String,
    pub shared: String,
    pub version: Option<String>,
    pub provisioned: bool,
    pub last_error: Option<String>,
}

impl UbuntuState {
    pub fn rootfs_or_default(&self) -> String {
        if self.rootfs.is_empty() {
            crate::layout::HOST_ROOTFS.to_string()
        } else {
            self.rootfs.clone()
        }
    }

    pub fn workspace_or_default(&self) -> String {
        if self.workspace.is_empty() {
            crate::layout::HOST_WORKSPACE.to_string()
        } else {
            self.workspace.clone()
        }
    }

    pub fn memory_or_default(&self) -> String {
        if self.memory.is_empty() {
            crate::layout::HOST_MEMORY.to_string()
        } else {
            self.memory.clone()
        }
    }

    pub fn skills_or_default(&self) -> String {
        if self.skills.is_empty() {
            crate::layout::HOST_SKILLS.to_string()
        } else {
            self.skills.clone()
        }
    }

    pub fn shared_or_default(&self) -> String {
        if self.shared.is_empty() {
            crate::layout::HOST_SHARED.to_string()
        } else {
            self.shared.clone()
        }
    }
}

pub struct AppState {
    pub mock: bool,
    pub skip_peer: bool,
    pub policy: PolicyFile,
    pub rates: RateLimiter,
    pub sessions: SessionTable,
    pub ubuntu: UbuntuState,
    pub workspace_quota_bytes: u64,
    pub confirms: HashMap<String, PendingConfirm>,
    pub used_confirms: HashMap<String, ()>,
    clock: Instant,
}

impl AppState {
    pub fn new(mock: bool, policy: PolicyFile) -> Self {
        prepare_persistent_layout_on_start(mock, &policy);

        let mut sessions = SessionTable::default();
        sessions.enable_subreaper();
        if should_start_config_proxy(mock, policy.caller.app_uid) {
            // `main` constructs AppState before branching into watchdog mode.
            // The guard above keeps the watchdog parent out: only the actual
            // root socket-server child owns the guest-facing minis-config proxy.
            crate::config_proxy::ensure_started(policy.caller.app_uid);
        }
        Self {
            mock,
            skip_peer: false,
            policy,
            rates: RateLimiter::new(),
            sessions,
            ubuntu: UbuntuState::default(),
            workspace_quota_bytes: 4 * 1024 * 1024 * 1024,
            confirms: HashMap::new(),
            used_confirms: HashMap::new(),
            clock: Instant::now(),
        }
    }

    pub fn now(&self) -> Instant {
        if self.mock {
            self.clock
        } else {
            Instant::now()
        }
    }

    pub fn advance(&mut self, d: Duration) {
        self.clock += d;
    }

    pub fn issue_confirm(&mut self, method: &str, params: &serde_json::Value) -> String {
        let id = format!("c-{}", self.confirms.len() + self.used_confirms.len() + 1);
        self.confirms.insert(
            id.clone(),
            PendingConfirm {
                method: method.to_string(),
                params: params.clone(),
                expires: self.now() + Duration::from_secs(120),
            },
        );
        id
    }

    pub fn consume_confirm(
        &mut self,
        id: &str,
        method: &str,
        params: &serde_json::Value,
    ) -> Result<(), crate::protocol::ErrorCode> {
        if self.used_confirms.contains_key(id) {
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        }
        let Some(p) = self.confirms.remove(id) else {
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        };
        // Consume before validation. A mismatch, expiry, or replay can never
        // leave the ticket usable for a later request.
        self.used_confirms.insert(id.to_string(), ());
        if p.expires <= self.now() {
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        }
        if p.method != method || p.params != *params {
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        }
        Ok(())
    }
}

fn prepare_persistent_layout_on_start(mock: bool, policy: &PolicyFile) {
    if mock {
        return;
    }
    #[cfg(all(unix, not(test)))]
    {
        if unsafe { libc::geteuid() } != 0 {
            return;
        }
        let uid = if policy.caller.app_uid != 0 {
            policy.caller.app_uid
        } else {
            crate::layout::GUEST_UID
        };
        if let Err(error) = crate::layout::ensure_host_layout_for(uid, uid)
            .and_then(|()| crate::layout::validate_persistent_backing())
        {
            panic!("persistent storage initialization failed closed: {error}");
        }
    }
    #[cfg(any(not(unix), test))]
    {
        let _ = policy;
    }
}

fn should_start_config_proxy(mock: bool, app_uid: u32) -> bool {
    if mock || app_uid == 0 {
        return false;
    }
    let non_serving_mode = std::env::args_os().any(|arg| {
        matches!(
            arg.to_str(),
            Some("--watchdog" | "--once" | "--call" | "--mock")
        )
    });
    if non_serving_mode {
        return false;
    }
    #[cfg(unix)]
    {
        // The production socket server is root-owned; refusing to initialize
        // the proxy before that privilege check avoids writing runtime state
        // from accidental non-root direct invocations.
        unsafe { libc::geteuid() == 0 }
    }
    #[cfg(not(unix))]
    {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::ErrorCode;

    #[test]
    fn wrong_method_consumes_confirmation() {
        let mut state = AppState::new(true, crate::policy::PolicyFile::default_policy());
        let params = serde_json::json!({"argv":["/system/bin/id"]});
        let id = state.issue_confirm("root.exec", &params);
        assert_eq!(
            state.consume_confirm(&id, "other.method", &params),
            Err(ErrorCode::PolicyDenied)
        );
        assert!(!state.confirms.contains_key(&id));
        assert!(state.used_confirms.contains_key(&id));
        assert_eq!(
            state.consume_confirm(&id, "root.exec", &params),
            Err(ErrorCode::PolicyDenied)
        );
    }

    #[test]
    fn confirm_is_bound_to_exact_argv() {
        let mut state = AppState::new(true, crate::policy::PolicyFile::default_policy());
        let params = serde_json::json!({"argv":["/system/bin/id"]});
        let id = state.issue_confirm("root.fullExec", &params);
        // Same confirm id, different argv -> denied.
        assert_eq!(
            state.consume_confirm(
                &id,
                "root.fullExec",
                &serde_json::json!({"argv":["/system/bin/reboot"]}),
            ),
            Err(ErrorCode::PolicyDenied)
        );
        assert!(!state.confirms.contains_key(&id));
        // The mismatched attempt consumed the ticket, including for the
        // originally approved request.
        assert_eq!(
            state.consume_confirm(&id, "root.fullExec", &params),
            Err(ErrorCode::PolicyDenied)
        );
    }

    #[test]
    fn confirm_params_are_order_independent() {
        let a = serde_json::json!({"argv":["x"],"timeout_ms":1000});
        let b = serde_json::json!({"timeout_ms":1000,"argv":["x"]});
        let mut state = AppState::new(true, crate::policy::PolicyFile::default_policy());
        let id = state.issue_confirm("root.fullExec", &a);
        assert_eq!(state.consume_confirm(&id, "root.fullExec", &b), Ok(()));
    }

    #[test]
    fn t_u16_expired_removed() {
        let mut state = AppState::new(true, crate::policy::PolicyFile::default_policy());
        let params = serde_json::json!({});
        let id = state.issue_confirm("root.exec", &params);
        state.advance(Duration::from_secs(121));
        assert_eq!(
            state.consume_confirm(&id, "root.exec", &params),
            Err(ErrorCode::PolicyDenied)
        );
        assert!(!state.confirms.contains_key(&id));
        assert!(state.used_confirms.contains_key(&id));
    }

    #[test]
    fn mock_never_starts_config_proxy() {
        assert!(!should_start_config_proxy(true, 12345));
    }

    #[test]
    fn zero_uid_never_starts_config_proxy() {
        assert!(!should_start_config_proxy(false, 0));
    }

    #[test]
    fn mock_startup_does_not_touch_persistent_host_layout() {
        prepare_persistent_layout_on_start(true, &crate::policy::PolicyFile::default_policy());
    }
}
