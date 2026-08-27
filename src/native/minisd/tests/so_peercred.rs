//! SO_PEERCRED contract tests (no real Unix socket).
//!
//! Real path: `auth::read_peer` runs `getsockopt(fd, SOL_SOCKET, SO_PEERCRED)`
//! on the live socket; `auth::check_peer` then allows only uid 0 or the
//! configured `appUid`, everything else → `ErrorCode::NotAuthorized`. That
//! decision logic is already unit-covered in `src/auth.rs`
//! (`#[cfg(test)] mod tests`: missing peer, unknown uid 99, app uid, root),
//! so these tests do not repeat it. They pin the public seam instead:
//! `dispatch::handle(state, req, peer)` receives the credential the socket
//! layer extracted, and must reject non-authorized uids end-to-end with
//! `NOT_AUTHORIZED` before any method dispatch happens.
//!
//! Not covered here (needs a real socketpair + root): that `read_peer`
//! itself returns the right creds — kernel-verified behavior, exercised in
//! the live Android path.

use minisd::auth::PeerCred;
use minisd::handle;
use minisd::policy::PolicyFile;
use minisd::protocol::{ClientHello, Request};
use minisd::state::AppState;

fn client() -> ClientHello {
    ClientHello {
        id: "contract".into(),
        // B22: capabilities must explicitly include the method being called
        capabilities: vec!["system.ping".into()],
        token: None,
    }
}

fn ping_req() -> Request {
    Request {
        v: 1,
        id: 1,
        method: "system.ping".into(),
        client: Some(client()),
        params: serde_json::json!({}),
        confirm_id: None,
    }
}

/// Default policy with `appUid` set to a non-root app, like the live device.
fn policy_with_app_uid(uid: u32) -> PolicyFile {
    let mut policy = PolicyFile::default_policy();
    policy.caller.app_uid = uid;
    policy
}

#[test]
fn non_authorized_uid_rejected_before_dispatch() {
    let mut state = AppState::new(false, policy_with_app_uid(10123));
    let resp = handle(
        &mut state,
        ping_req(),
        Some(PeerCred {
            uid: 12345,
            gid: 12345,
            pid: 42,
        }),
    );
    assert!(!resp.ok);
    assert_eq!(resp.error.unwrap().code, "NOT_AUTHORIZED");
}

#[test]
fn missing_peer_rejected() {
    // no peer = getsockopt failed / non-socket transport → refuse
    let mut state = AppState::new(false, policy_with_app_uid(10123));
    let resp = handle(&mut state, ping_req(), None);
    assert!(!resp.ok);
    assert_eq!(resp.error.unwrap().code, "NOT_AUTHORIZED");
}

#[test]
fn configured_app_uid_accepted() {
    let mut state = AppState::new(false, policy_with_app_uid(10123));
    let resp = handle(
        &mut state,
        ping_req(),
        Some(PeerCred {
            uid: 10123,
            gid: 10123,
            pid: 7,
        }),
    );
    assert!(resp.ok, "{:?}", resp.error);
    assert_eq!(resp.result.unwrap()["pong"], true);
}

#[test]
fn root_uid_accepted() {
    let mut state = AppState::new(false, policy_with_app_uid(10123));
    let resp = handle(
        &mut state,
        ping_req(),
        Some(PeerCred {
            uid: 0,
            gid: 0,
            pid: 1,
        }),
    );
    assert!(resp.ok, "{:?}", resp.error);
    assert_eq!(resp.result.unwrap()["pong"], true);
}

#[test]
fn mock_mode_bypasses_peer_check() {
    // documented test seam: mock=true short-circuits check_peer; the prod
    // socket path never sets mock. Pinned so nobody "fixes" mock away and
    // breaks the JVM-host contract tests.
    let mut state = AppState::new(true, policy_with_app_uid(10123));
    let resp = handle(
        &mut state,
        ping_req(),
        Some(PeerCred {
            uid: 12345,
            gid: 12345,
            pid: 42,
        }),
    );
    assert!(resp.ok, "{:?}", resp.error);
}
