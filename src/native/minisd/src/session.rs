use crate::protocol::ErrorCode;
use std::collections::HashMap;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KillStep {
    Term { pgid: i32 },
    Kill { pgid: i32 },
}

pub trait Killer {
    fn signal_group(&mut self, pgid: i32, term: bool) -> Result<bool, ErrorCode>;
    fn group_alive(&self, pgid: i32) -> bool;
}

#[derive(Debug, Clone)]
pub struct Session {
    pub id: String,
    pub pgid: i32,
    pub children: Vec<i32>,
}

#[derive(Debug, Default)]
pub struct SessionTable {
    sessions: HashMap<String, Session>,
    pub subreaper: bool,
}

impl SessionTable {
    pub fn insert(&mut self, session: Session) {
        self.sessions.insert(session.id.clone(), session);
    }

    pub fn get(&self, id: &str) -> Option<&Session> {
        self.sessions.get(id)
    }

    pub fn remove(&mut self, id: &str) -> Option<Session> {
        self.sessions.remove(id)
    }

    pub fn len(&self) -> usize {
        self.sessions.len()
    }

    pub fn enable_subreaper(&mut self) {
        self.subreaper = true;
    }

    pub fn kill_tree(
        &mut self,
        id: &str,
        timeout: Duration,
        killer: &mut impl Killer,
    ) -> Result<Vec<KillStep>, ErrorCode> {
        let Some(sess) = self.sessions.get(id).cloned() else {
            return Err(ErrorCode::BadParams);
        };
        let mut steps = Vec::new();
        let dead = killer.signal_group(sess.pgid, true)?;
        steps.push(KillStep::Term { pgid: sess.pgid });
        if !dead {
            let start = std::time::Instant::now();
            while start.elapsed() < timeout && killer.group_alive(sess.pgid) {
                std::thread::sleep(std::time::Duration::from_millis(20));
            }
            if killer.group_alive(sess.pgid) {
                let _ = killer.signal_group(sess.pgid, false)?;
                steps.push(KillStep::Kill { pgid: sess.pgid });
            }
        }
        self.sessions.remove(id);
        Ok(steps)
    }

    pub fn reap_orphans(&mut self, live: &[i32]) -> Vec<i32> {
        if !self.subreaper {
            return Vec::new();
        }
        let mut reaped = Vec::new();
        for sess in self.sessions.values_mut() {
            sess.children.retain(|pid| {
                if live.contains(pid) {
                    true
                } else {
                    reaped.push(*pid);
                    false
                }
            });
        }
        reaped
    }
}

#[derive(Default)]
pub struct RecordingKiller {
    pub term_alive: bool,
    pub calls: Vec<KillStep>,
}

impl Killer for RecordingKiller {
    fn signal_group(&mut self, pgid: i32, term: bool) -> Result<bool, ErrorCode> {
        if term {
            self.calls.push(KillStep::Term { pgid });
            Ok(!self.term_alive)
        } else {
            self.calls.push(KillStep::Kill { pgid });
            Ok(true)
        }
    }

    fn group_alive(&self, _pgid: i32) -> bool {
        self.term_alive
    }
}

/// Real process-group killer: kill(-pgid, SIGTERM), then SIGKILL. unix only.
#[cfg(unix)]
pub struct RealKiller;

#[cfg(unix)]
impl Killer for RealKiller {
    fn signal_group(&mut self, pgid: i32, term: bool) -> Result<bool, ErrorCode> {
        let sig = if term { libc::SIGTERM } else { libc::SIGKILL };
        if unsafe { libc::kill(-pgid, sig) } == 0 {
            Ok(false)
        } else {
            let e = std::io::Error::last_os_error();
            if e.kind() == std::io::ErrorKind::NotFound {
                Ok(true) // group already gone
            } else {
                Err(ErrorCode::Internal)
            }
        }
    }

    fn group_alive(&self, pgid: i32) -> bool {
        // signal 0 probes without killing; ESRCH means the group is gone
        unsafe { libc::kill(-pgid, 0) == 0 }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t_u5_kill_order_and_orphans() {
        let mut table = SessionTable::default();
        table.enable_subreaper();
        table.insert(Session {
            id: "s1".into(),
            pgid: 42,
            children: vec![43, 44],
        });
        let mut killer = RecordingKiller {
            term_alive: true,
            calls: vec![],
        };
        let steps = table
            .kill_tree("s1", Duration::from_millis(1), &mut killer)
            .unwrap();
        assert_eq!(
            steps,
            vec![KillStep::Term { pgid: 42 }, KillStep::Kill { pgid: 42 }]
        );
        assert_eq!(table.len(), 0);

        table.insert(Session {
            id: "s2".into(),
            pgid: 50,
            children: vec![51, 52],
        });
        let reaped = table.reap_orphans(&[51]);
        assert_eq!(reaped, vec![52]);
        assert_eq!(table.get("s2").unwrap().children, vec![51]);
    }

    #[test]
    fn t_u9_term_then_group_gone_no_kill() {
        let mut table = SessionTable::default();
        table.insert(Session {
            id: "s1".into(),
            pgid: 42,
            children: vec![],
        });
        let mut killer = RecordingKiller {
            term_alive: false,
            calls: vec![],
        };
        let steps = table
            .kill_tree("s1", Duration::from_millis(100), &mut killer)
            .unwrap();
        assert_eq!(steps, vec![KillStep::Term { pgid: 42 }]);
        assert_eq!(killer.calls, vec![KillStep::Term { pgid: 42 }]);
    }
}
