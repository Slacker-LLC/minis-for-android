pub mod auth;
pub mod config_proxy;
pub mod dispatch;
pub mod env;
pub mod exec;
pub mod exec_registry;
pub mod ipc_exec;
pub mod layout;
pub mod ns;
pub mod path_guard;
pub mod policy;
pub mod probe;
pub mod protocol;
pub mod proxy;
pub mod rate;
pub mod session;
pub mod state;
#[path = "ubuntu_persistent.rs"]
pub mod ubuntu;
#[path = "ubuntu.rs"]
mod ubuntu_legacy;
pub mod workspace_file;

pub use policy::PolicyFile;
pub use protocol::{encode_response, parse_request, Request, Response};
pub use state::AppState;

/// Public request entry. `exec.cancel` is deliberately handled outside the
/// normal stateful dispatcher: the execution registry is process-global and
/// cancellation must not wait behind a long AppState operation.
pub fn handle(state: &mut AppState, req: Request, peer: Option<auth::PeerCred>) -> Response {
    if req.method == "exec.cancel" {
        if let Err(resp) = dispatch::authorize_request(state, &req, peer) {
            return resp;
        }
        let Some(execution_id) = req.params.get("execution_id").and_then(|v| v.as_str()) else {
            return Response::err(
                req.id,
                protocol::ErrorCode::BadParams,
                "execution_id required",
            );
        };
        return match exec_registry::cancel(execution_id) {
            Ok(outcome) => Response::ok(
                req.id,
                serde_json::json!({
                    "execution_id": execution_id,
                    "found": outcome.found,
                    "killed": outcome.killed,
                }),
            ),
            Err(code) => Response::err(req.id, code, "exec cancellation failed"),
        };
    }
    dispatch::handle(state, req, peer)
}

/// Test-only convenience entry: always passes peer=None, so outside mock
/// mode every request is rejected by the peer check (see auth::check_peer).
/// Production paths (unix_server / once_stdio) construct their own peer.
pub fn handle_bytes(state: &mut AppState, bytes: &[u8]) -> Response {
    match parse_request(bytes) {
        Ok(req) => handle(state, req, None),
        Err(resp) => resp,
    }
}
