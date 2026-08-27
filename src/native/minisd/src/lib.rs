pub mod auth;
pub mod dispatch;
pub mod env;
pub mod exec;
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
pub mod ubuntu;

pub use dispatch::handle;
pub use policy::PolicyFile;
pub use protocol::{encode_response, parse_request, Request, Response};
pub use state::AppState;

/// Test-only convenience entry: always passes peer=None, so outside mock
/// mode every request is rejected by the peer check (see auth::check_peer).
/// Production paths (unix_server / once_stdio) construct their own peer.
pub fn handle_bytes(state: &mut AppState, bytes: &[u8]) -> Response {
    match parse_request(bytes) {
        Ok(req) => handle(state, req, None),
        Err(resp) => resp,
    }
}
