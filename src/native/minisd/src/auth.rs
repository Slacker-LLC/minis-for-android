use crate::policy::PolicyFile;
use crate::protocol::{ClientHello, ErrorCode};

#[derive(Debug, Clone, Copy)]
pub struct PeerCred {
    pub uid: u32,
    pub gid: u32,
    pub pid: i32,
}

pub fn check_peer(
    policy: &PolicyFile,
    peer: Option<PeerCred>,
    mock: bool,
    skip_peer: bool,
) -> Result<(), ErrorCode> {
    if mock || skip_peer {
        return Ok(());
    }
    let Some(peer) = peer else {
        return Err(ErrorCode::NotAuthorized);
    };
    if peer.uid == 0 {
        return Ok(());
    }
    if policy.caller.app_uid != 0 && peer.uid == policy.caller.app_uid {
        return Ok(());
    }
    Err(ErrorCode::NotAuthorized)
}

/// Constant-time string equality for token comparison (timing-safe against length/data leaks).
fn ct_eq(a: &str, b: &str) -> bool {
    let (ab, bb) = (a.as_bytes(), b.as_bytes());
    if ab.len() != bb.len() {
        return false;
    }
    ab.iter().zip(bb).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

pub fn check_hello(
    policy: &PolicyFile,
    hello: Option<&ClientHello>,
    method: &str,
) -> Result<(), ErrorCode> {
    let Some(hello) = hello else {
        return Err(ErrorCode::NotAuthorized);
    };
    if hello.id.is_empty() {
        return Err(ErrorCode::NotAuthorized);
    }
    if policy.caller.require_token {
        match (&policy.caller.token, &hello.token) {
            (Some(expected), Some(got)) if ct_eq(expected, got) && !expected.is_empty() => {}
            _ => return Err(ErrorCode::NotAuthorized),
        }
    }
    // empty capabilities = deny by default: the hello must explicitly declare the method
    if !hello.capabilities.iter().any(|c| c == method) {
        return Err(ErrorCode::NotAuthorized);
    }
    Ok(())
}

#[cfg(unix)]
pub fn read_peer(fd: i32) -> Option<PeerCred> {
    let mut cred = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut _ as *mut libc::c_void,
            &mut len,
        )
    };
    if rc == 0 {
        Some(PeerCred {
            uid: cred.uid,
            gid: cred.gid,
            pid: cred.pid,
        })
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peer_and_token() {
        let mut policy = PolicyFile::default_policy();
        assert!(check_peer(&policy, None, true, false).is_ok());
        assert!(check_peer(&policy, None, false, true).is_ok());
        assert_eq!(
            check_peer(&policy, None, false, false).unwrap_err(),
            ErrorCode::NotAuthorized
        );
        assert!(check_peer(
            &policy,
            Some(PeerCred {
                uid: 0,
                gid: 0,
                pid: 1
            }),
            false,
            false
        )
        .is_ok());
        policy.caller.app_uid = 10123;
        assert!(check_peer(
            &policy,
            Some(PeerCred {
                uid: 10123,
                gid: 10123,
                pid: 9
            }),
            false,
            false
        )
        .is_ok());
        assert_eq!(
            check_peer(
                &policy,
                Some(PeerCred {
                    uid: 99,
                    gid: 99,
                    pid: 9
                }),
                false,
                false
            )
            .unwrap_err(),
            ErrorCode::NotAuthorized
        );

        policy.caller.require_token = true;
        policy.caller.token = Some("secret".into());
        let hello = ClientHello {
            id: "app".into(),
            capabilities: vec!["root.exec".into()],
            token: Some("secret".into()),
        };
        assert!(check_hello(&policy, Some(&hello), "root.exec").is_ok());
        assert_eq!(
            check_hello(&policy, Some(&hello), "root.probe").unwrap_err(),
            ErrorCode::NotAuthorized
        );
        let bad = ClientHello {
            id: "app".into(),
            capabilities: vec![],
            token: Some("nope".into()),
        };
        assert_eq!(
            check_hello(&policy, Some(&bad), "root.exec").unwrap_err(),
            ErrorCode::NotAuthorized
        );
        // B22: empty capabilities are denied even with a correct token
        let empty_caps = ClientHello {
            id: "app".into(),
            capabilities: vec![],
            token: Some("secret".into()),
        };
        assert_eq!(
            check_hello(&policy, Some(&empty_caps), "root.exec").unwrap_err(),
            ErrorCode::NotAuthorized
        );
    }
}
