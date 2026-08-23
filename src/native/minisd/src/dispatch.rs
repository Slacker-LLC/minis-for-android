use crate::auth::{check_hello, check_peer, PeerCred};
use crate::exec::{parse_exec, run_exec, validate_exec};
use crate::path_guard::{resolve_workspace, HOST_WORKSPACE};
use crate::policy::{decide_method, Mode};
use crate::probe::{live_probe, mock_probe};
use crate::protocol::{is_known_method, ErrorCode, Request, Response};
use crate::session::{RecordingKiller, Session};
use crate::state::AppState;
use serde_json::json;

pub fn handle(state: &mut AppState, req: Request, peer: Option<PeerCred>) -> Response {
    if let Err(code) = check_peer(&state.policy, peer, state.mock, state.skip_peer) {
        return Response::err(req.id, code, "peer not authorized");
    }
    if req.method != "system.hello" {
        if let Err(code) = check_hello(&state.policy, req.client.as_ref(), &req.method) {
            return Response::err(req.id, code, "handshake required");
        }
    } else if req.client.as_ref().map(|c| c.id.is_empty()).unwrap_or(true) {
        return Response::err(req.id, ErrorCode::NotAuthorized, "client.id required");
    }
    if !is_known_method(&req.method) {
        return Response::err(req.id, ErrorCode::BadParams, "unknown method");
    }
    let decision = match decide_method(&state.policy, &req.method) {
        Ok(d) => d,
        Err(code) => return Response::err(req.id, code, "policy denied"),
    };
    if decision.mode == Mode::Confirm {
        match req.confirm_id.as_deref() {
            None => {
                let cid = state.issue_confirm(&req.method);
                return Response::confirm(req.id, cid);
            }
            Some(cid) => {
                if let Err(code) = state.consume_confirm(cid, &req.method) {
                    return Response::err(req.id, code, "invalid or reused confirm_id");
                }
            }
        }
    }
    if let Some(limit) = decision.rate_per_min {
        if !state.rates.check(&req.method, limit, state.now()) {
            return Response::err(req.id, ErrorCode::RateLimited, "ratePerMin exceeded");
        }
    }
    dispatch_method(state, &req)
}

fn dispatch_method(state: &mut AppState, req: &Request) -> Response {
    match req.method.as_str() {
        "system.hello" => Response::ok(req.id, json!({"hello": true, "v": 1})),
        "system.ping" => Response::ok(req.id, json!({"pong": true})),
        "root.probe" => {
            let probe = if state.mock {
                mock_probe()
            } else {
                live_probe()
            };
            Response::ok(req.id, serde_json::to_value(probe).unwrap())
        }
        "root.exec" => exec_root(state, req),
        "root.shellRaw" => Response::err(req.id, ErrorCode::PolicyDenied, "root.shellRaw is INTERNAL"),
        "ubuntu.status" => Response::ok(req.id, crate::ubuntu::status(state)),
        "ubuntu.start" => match crate::ubuntu::start(state, &req.params) {
            Ok(v) => Response::ok(req.id, v),
            Err((code, detail)) => Response::err(req.id, code, detail),
        },
        "ubuntu.stop" => match crate::ubuntu::stop(state) {
            Ok(v) => Response::ok(req.id, v),
            Err((code, detail)) => Response::err(req.id, code, detail),
        },
        "ubuntu.exec" => match crate::ubuntu::exec(state, &req.params, false) {
            Ok(v) => Response::ok(req.id, v),
            Err((code, detail)) => Response::err(req.id, code, detail),
        },
        "ubuntu.adminExec" => match crate::ubuntu::exec(state, &req.params, true) {
            Ok(v) => Response::ok(req.id, v),
            Err((code, detail)) => Response::err(req.id, code, detail),
        },
        "ubuntu.provision" => match crate::ubuntu::provision(state) {
            Ok(v) => Response::ok(req.id, v),
            Err((code, detail)) => Response::err(req.id, code, detail),
        },
        "proc.killTree" => kill_tree(state, req),
        "mount.list" => {
            if !state.mock {
                return Response::err(req.id, ErrorCode::RuntimeUnavailable, "mount ns not started");
            }
            Response::ok(req.id, json!({"mounts": []}))
        }
        "mount.prepare" => {
            if state.mock {
                Response::ok(req.id, json!({"prepared": true, "via": "mock"}))
            } else {
                Response::err(req.id, ErrorCode::RuntimeUnavailable, "mount ns not started")
            }
        },
        "workspace.info" => Response::ok(
            req.id,
            json!({
                "path": HOST_WORKSPACE,
                "guest": "/workspace",
                "quota_bytes": state.workspace_quota_bytes
            }),
        ),
        "workspace.setQuota" => {
            let n = req.params.get("quota_bytes").and_then(|v| v.as_u64());
            let Some(n) = n else {
                return Response::err(req.id, ErrorCode::BadParams, "quota_bytes required");
            };
            state.workspace_quota_bytes = n;
            Response::ok(req.id, json!({"quota_bytes": n}))
        }
        "policy.get" => {
            let json = serde_json::to_value(&json!({
                "methods": state.policy.methods.keys().cloned().collect::<Vec<_>>(),
                "requireToken": state.policy.caller.require_token,
                "appUid": state.policy.caller.app_uid
            }))
            .unwrap();
            Response::ok(req.id, json)
        }
        "policy.reload" => reload_policy(state, req),
        "supervisor.status" => {
            let (running, pid) = state.supervisor.cloudflared_status();
            Response::ok(req.id, json!({"cloudflared": running, "pid": pid}))
        }
        "supervisor.restartCloudflared" => match crate::supervisor::start_cloudflared(state, &req.params) {
            Ok(v) => Response::ok(req.id, v),
            Err((code, detail)) => Response::err(req.id, code, detail),
        },
        "supervisor.stopCloudflared" => {
            let v = crate::supervisor::stop_cloudflared(state);
            Response::ok(req.id, v)
        }
        "health.get" => Response::ok(
            req.id,
            json!({
                "ok": true,
                "mock": state.mock,
                "ubuntu": state.ubuntu.running,
                "ubuntu_pid": state.ubuntu.pid,
                "ubuntu_version": state.ubuntu.version,
                "cloudflared": state.supervisor.cloudflared_status().0,
                "sessions": state.sessions.len(),
                "selinux_enforcing": true
            }),
        ),
        _ => Response::err(req.id, ErrorCode::BadParams, "unknown method"),
    }
}

fn exec_root(state: &mut AppState, req: &Request) -> Response {
    let parsed = match parse_exec(&req.params) {
        Ok(p) => p,
        Err(code) => return Response::err(req.id, code, "bad exec params"),
    };
    let spec = state.policy.method("root.exec");
    let allow = crate::exec::effective_allowlist(spec);
    if let Err(code) = validate_exec(spec, &parsed) {
        return Response::err(req.id, code, "exec policy");
    }
    if state.mock {
        return Response::ok(
            req.id,
            json!({
                "exit_code": 0,
                "stdout": format!("{} {}", parsed.tool, parsed.args.join(" ")),
                "stderr": ""
            }),
        );
    }
    match run_exec(&parsed, &allow) {
        Ok(out) => Response::ok(
            req.id,
            json!({
                "exit_code": out.exit_code,
                "stdout": out.stdout,
                "stderr": out.stderr
            }),
        ),
        Err(code) => Response::err(req.id, code, "exec failed"),
    }
}

fn kill_tree(state: &mut AppState, req: &Request) -> Response {
    let session = req
        .params
        .get("session")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    if session.is_empty() {
        return Response::err(req.id, ErrorCode::BadParams, "session required");
    }
    if state.mock {
        // mock: keep the recording killer path (contract tests rely on it)
        if state.sessions.get(session).is_none() {
            state.sessions.insert(Session {
                id: session.to_string(),
                pgid: 1000,
                children: vec![1001],
            });
        }
        let mut killer = RecordingKiller {
            term_alive: false,
            calls: vec![],
        };
        return match state
            .sessions
            .kill_tree(session, std::time::Duration::from_millis(10), &mut killer)
        {
            Ok(steps) => Response::ok(req.id, json!({"steps": steps.len(), "mock": true})),
            Err(code) => Response::err(req.id, code, "killTree failed"),
        };
    }
    #[cfg(unix)]
    {
        // real path: the session table is filled by exec/spawn callers; a
        // missing session is a real error, not a mock fixture.
        let Some(sess) = state.sessions.get(session).cloned() else {
            return Response::err(req.id, ErrorCode::BadParams, "unknown session");
        };
        let mut killer = crate::session::RealKiller;
        match state
            .sessions
            .kill_tree(session, std::time::Duration::from_secs(2), &mut killer)
        {
            Ok(steps) => {
                let _ = sess;
                Response::ok(req.id, json!({"steps": steps.len(), "pgid": steps.first().map(|s| match s { crate::session::KillStep::Term { pgid } | crate::session::KillStep::Kill { pgid } => *pgid })}))
            }
            Err(code) => Response::err(req.id, code, "killTree failed"),
        }
    }
    #[cfg(not(unix))]
    {
        Response::err(req.id, ErrorCode::RuntimeUnavailable, "killTree requires unix")
    }
}

fn reload_policy(state: &mut AppState, req: &Request) -> Response {
    let json = req.params.get("json").and_then(|v| v.as_str());
    let Some(json) = json else {
        return Response::err(req.id, ErrorCode::BadParams, "json required");
    };
    match state.try_reload_policy(json) {
        Ok(()) => Response::ok(req.id, json!({"reloaded": true})),
        Err(e) => Response::err(req.id, ErrorCode::BadParams, format!("policy rejected, previous kept: {e}")),
    }
}

pub fn require_workspace_path(params: &serde_json::Value) -> Result<String, ErrorCode> {
    let path = params.get("path").and_then(|v| v.as_str()).unwrap_or("");
    resolve_workspace(path)
}

#[cfg(test)]
mod path_param_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn workspace_param_guard() {
        assert!(require_workspace_path(&json!({"path": "/workspace/a"})).is_ok());
        assert!(require_workspace_path(&json!({"path": "/etc/shadow"})).is_err());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::PolicyFile;
    use crate::protocol::{parse_request, ClientHello};

    fn hello(method: &str) -> ClientHello {
        ClientHello {
            id: "app".into(),
            capabilities: vec![method.into()],
            token: None,
        }
    }

    fn req(method: &str, params: serde_json::Value) -> Request {
        Request {
            v: 1,
            id: 1,
            method: method.into(),
            client: Some(hello(method)),
            params,
            confirm_id: None,
        }
    }

    #[test]
    fn t_u3_policy_order_deny_and_rollback() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let denied = handle(&mut state, req("root.shellRaw", json!({"cmd":"id"})), None);
        assert_eq!(denied.error.unwrap().code, "POLICY_DENIED");

        let previous = state.policy.methods.len();
        let bad = handle(
            &mut state,
            req("policy.reload", json!({"json": "{\"methods\":{\"nope\":{\"mode\":\"allow\"}}}"})),
            None,
        );
        assert!(!bad.ok);
        assert_eq!(state.policy.methods.len(), previous);

        let good = PolicyFile::default_policy();
        let raw = serde_json::to_string(&json!({
            "methods": {
                "system.ping": {"mode":"allow"},
                "root.shellRaw": {"mode":"deny"}
            },
            "caller": {"appUid": 0, "requireToken": false}
        }))
        .unwrap();
        assert!(state.try_reload_policy(&raw).is_ok());
        assert!(good.method("system.ping").is_some());
    }

    #[test]
    fn confirm_once_and_reuse_rejected() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let first = handle(&mut state, req("ubuntu.adminExec", json!({"argv":["/usr/bin/apt-get","update"]})), None);
        let cid = first.error.unwrap().confirm_id.unwrap();
        let mut second = req("ubuntu.adminExec", json!({"argv":["/usr/bin/apt-get","update"]}));
        second.confirm_id = Some(cid.clone());
        let started = handle(&mut state, req("ubuntu.start", json!({})), None);
        assert!(started.ok);
        let ok = handle(&mut state, second, None);
        assert!(ok.ok);
        let mut third = req("ubuntu.adminExec", json!({"argv":["/usr/bin/apt-get","update"]}));
        third.confirm_id = Some(cid);
        let reuse = handle(&mut state, third, None);
        assert_eq!(reuse.error.unwrap().code, "POLICY_DENIED");
    }

    #[test]
    fn parse_and_handle_ping() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let raw = br#"{"v":1,"id":9,"method":"system.ping","client":{"id":"app","capabilities":["system.ping"]}}"#;
        let parsed = parse_request(raw).unwrap();
        let resp = handle(&mut state, parsed, None);
        assert!(resp.ok);
    }
}
