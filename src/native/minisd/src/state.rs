use crate::policy::PolicyFile;
use crate::rate::RateLimiter;
use crate::session::SessionTable;
use std::collections::HashMap;
use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
pub struct PendingConfirm {
    pub method: String,
    pub expires: Instant,
}

#[derive(Debug, Default)]
pub struct UbuntuState {
    pub running: bool,
    pub pid: Option<i32>,
    pub rootfs: String,
    pub sessions_root: String,
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
        let mut sessions = SessionTable::default();
        sessions.enable_subreaper();
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

    pub fn issue_confirm(&mut self, method: &str) -> String {
        let id = format!("c-{}", self.confirms.len() + self.used_confirms.len() + 1);
        self.confirms.insert(
            id.clone(),
            PendingConfirm {
                method: method.to_string(),
                expires: self.now() + Duration::from_secs(120),
            },
        );
        id
    }

    pub fn consume_confirm(
        &mut self,
        id: &str,
        method: &str,
    ) -> Result<(), crate::protocol::ErrorCode> {
        if self.used_confirms.contains_key(id) {
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        }
        let Some(p) = self.confirms.get(id) else {
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        };
        if p.method != method {
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        }
        if p.expires <= self.now() {
            self.confirms.remove(id);
            return Err(crate::protocol::ErrorCode::PolicyDenied);
        }
        self.confirms.remove(id);
        self.used_confirms.insert(id.to_string(), ());
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::ErrorCode;

    #[test]
    fn t_u16_wrong_method_keeps_entry() {
        let mut state = AppState::new(true, crate::policy::PolicyFile::default_policy());
        let id = state.issue_confirm("root.exec");
        assert_eq!(
            state.consume_confirm(&id, "other.method"),
            Err(ErrorCode::PolicyDenied)
        );
        assert!(state.confirms.contains_key(&id));
        assert_eq!(state.consume_confirm(&id, "root.exec"), Ok(()));
        assert!(!state.confirms.contains_key(&id));
        assert!(state.used_confirms.contains_key(&id));
    }

    #[test]
    fn t_u16_expired_removed() {
        let mut state = AppState::new(true, crate::policy::PolicyFile::default_policy());
        let id = state.issue_confirm("root.exec");
        state.advance(Duration::from_secs(121));
        assert_eq!(
            state.consume_confirm(&id, "root.exec"),
            Err(ErrorCode::PolicyDenied)
        );
        assert!(!state.confirms.contains_key(&id));
        assert!(!state.used_confirms.contains_key(&id));
    }
}
