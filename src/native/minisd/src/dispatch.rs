use crate::auth::{check_hello, check_peer, PeerCred};
use crate::exec::{parse_exec, run_exec, validate_exec};
use crate::path_guard::resolve_workspace;
use crate::policy::{decide_method, MethodPolicy, Mode};
use crate::probe::{live_probe, mock_probe};
use crate::protocol::{is_known_method, ErrorCode, Request, Response};
use crate::session::{RecordingKiller, Session};
use crate::state::AppState;
use serde_json::{json, Value};

fn root_access_mode(req: &Request) -> Result<&str, ErrorCode> {
    match req
        .params
        .get("access_mode")
        .and_then(Value::as_str)
        .unwrap_or("standard")
    {
        "standard" => Ok("standard"),
        "full" => Ok("full"),
        _ => Err(ErrorCode::BadParams),
    }
}

/// Confirmation identity deliberately excludes execution_id: it is transport/
/// cancellation metadata, not requested authority. Method + every other param,
/// including the complete argv, timeout and access mode, stays bound.
fn confirmation_key(req: &Request) -> String {
    let mut params = req.params.clone();
    if let Value::Object(map) = &mut params {
        map.remove("execution_id");
    }
    serde_json::to_string(&json!({"method": req.method, "params": params}))
        .expect("confirmation key is JSON")
}

/// Performs all mutable request-gate work (peer auth, hello/capability checks,
/// confirmation consumption and rate limiting) without executing the method.
/// Socket serving can therefore snapshot execution inputs under a short state
/// lock and run long subprocess work after releasing that lock.
// A denied request is already a complete wire-level JSON-RPC response; boxing it
// would add a heap allocation to the normal authorization failure path.
#[allow(clippy::result_large_err)]
pub fn authorize_request(
    state: &mut AppState,
    req: &Request,
    peer: Option<PeerCred>,
) -> Result<(), Response> {
    if let Err(code) = check_peer(&state.policy, peer, state.mock, state.skip_peer) {
        return Err(Response::err(req.id, code, "peer not authorized"));
    }
    if req.method != "system.hello" {
        if let Err(code) = check_hello(&state.policy, req.client.as_ref(), &req.method) {
            return Err(Response::err(req.id, code, "handshake required"));
        }
    } else if req.client.as_ref().map(|c| c.id.is_empty()).unwrap_or(true) {
        return Err(Response::err(
            req.id,
            ErrorCode::NotAuthorized,
            "client.id required",
        ));
    }
    if !is_known_method(&req.method) {
        return Err(Response::err(
            req.id,
            ErrorCode::BadParams,
            "unknown method",
        ));
    }
    let decision = match decide_method(&state.policy, &req.method) {
        Ok(d) => d,
        Err(code) => return Err(Response::err(req.id, code, "policy denied")),
    };

    let mut needs_confirm = decision.mode == Mode::Confirm;
    if req.method == "root.exec" {
        let access_mode = root_access_mode(req)
            .map_err(|code| Response::err(req.id, code, "invalid root access mode"))?;
        if access_mode == "standard" {
            let parsed = parse_exec(&req.params)
                .map_err(|code| Response::err(req.id, code, "bad exec params"))?;
            match validate_exec(state.policy.method("root.exec"), &parsed) {
                Ok(()) => needs_confirm = false,
                Err(ErrorCode::PolicyDenied) => needs_confirm = true,
                Err(code) => return Err(Response::err(req.id, code, "exec policy")),
            }
        } else {
            // Full Access is selected only by the Android user preference layer.
            // minisd remains the sole root executor and retains timeout/output audit.
            needs_confirm = false;
        }
    }

    if needs_confirm {
        let key = confirmation_key(req);
        match req.confirm_id.as_deref() {
            None => {
                let cid = state.issue_confirm(&key);
                return Err(Response::confirm(req.id, cid));
            }
            Some(cid) => {
                if let Err(code) = state.consume_confirm(cid, &key) {
                    return Err(Response::err(
                        req.id,
                        code,
                        "invalid, mismatched or reused confirm_id",
                    ));
                }
            }
        }
    }
    if let Some(limit) = decision.rate_per_min {
        if !state.rates.check(&req.method, limit, state.now()) {
            return Err(Response::err(
                req.id,
                ErrorCode::RateLimited,
                "ratePerMin exceeded",
            ));
        }
    }
    Ok(())
}

pub fn handle(state: &mut AppState, req: Request, peer: Option<PeerCred>) -> Response {
    if let Err(resp) = authorize_request(state, &req, peer) {
        return resp;
    }
    dispatch_authorized(state, &req)
}

fn requested_runtime_layout(params: &Value) -> (String, String, String, String) {
    fn path_or(params: &Value, key: &str, fallback: &str) -> String {
        params
            .get(key)
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .unwrap_or(fallback)
            .to_string()
    }
    (
        path_or(params, "workspace", crate::layout::HOST_WORKSPACE),
        path_or(params, "memory", crate::layout::HOST_MEMORY),
        path_or(params, "skills", crate::layout::HOST_SKILLS),
        path_or(params, "shared", crate::layout::HOST_SHARED),
    )
}

fn runtime_layout_known(state: &AppState) -> bool {
    !state.ubuntu.workspace.is_empty()
        && !state.ubuntu.memory.is_empty()
        && !state.ubuntu.skills.is_empty()
        && !state.ubuntu.shared.is_empty()
}

/// `ubuntu.start` is also the single source of truth for host bind paths. A
/// recovered keeper whose original bind layout is unknown must be restarted;
/// silently accepting new paths while reusing the old keeper would recreate the
/// split-brain workspace bug.
fn start_ubuntu_with_layout(state: &mut AppState, req: &Request) -> Response {
    let (workspace, memory, skills, shared) = requested_runtime_layout(&req.params);

    if state.ubuntu.running {
        if !runtime_layout_known(state) {
            return Response::err(
                req.id,
                ErrorCode::RuntimeLayoutMismatch,
                "running keeper bind layout is unknown; stop and restart required",
            );
        }
        if state.ubuntu.workspace != workspace
            || state.ubuntu.memory != memory
            || state.ubuntu.skills != skills
            || state.ubuntu.shared != shared
        {
            return Response::err(
                req.id,
                ErrorCode::RuntimeLayoutMismatch,
                "requested host bind paths differ from the running keeper",
            );
        }
    }

    match crate::ubuntu::start(state, &req.params) {
        Ok(v) => {
            if v.get("running").and_then(Value::as_bool) == Some(true) {
                state.ubuntu.workspace = workspace;
                state.ubuntu.memory = memory;
                state.ubuntu.skills = skills;
                state.ubuntu.shared = shared;
            }
            Response::ok(req.id, v)
        }
        Err((code, detail)) => Response::err(req.id, code, detail),
    }
}

fn ubuntu_status_with_layout(state: &mut AppState) -> Value {
    let mut value = crate::ubuntu::status(state);
    if let Some(obj) = value.as_object_mut() {
        let known = runtime_layout_known(state);
        obj.insert("layout_known".into(), Value::Bool(known));
        obj.insert(
            "workspace".into(),
            if known {
                Value::String(state.ubuntu.workspace.clone())
            } else {
                Value::Null
            },
        );
        obj.insert(
            "memory".into(),
            if known {
                Value::String(state.ubuntu.memory.clone())
            } else {
                Value::Null
            },
        );
        obj.insert(
            "skills".into(),
            if known {
                Value::String(state.ubuntu.skills.clone())
            } else {
                Value::Null
            },
        );
        obj.insert(
            "shared".into(),
            if known {
                Value::String(state.ubuntu.shared.clone())
            } else {
                Value::Null
            },
        );
    }
    value
}

/// Dispatch a request that has already passed [authorize_request].
pub fn dispatch_authorized(state: &mut AppState, req: &Request) -> Response {
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
        "root.exec" => {
            execute_root_authorized(state.mock, state.policy.method("root.exec").cloned(), req)
        }
        "root.shellRaw" => {
            Response::err(req.id, ErrorCode::PolicyDenied, "root.shellRaw is INTERNAL")
        }
        "ubuntu.status" => Response::ok(req.id, ubuntu_status_with_layout(state)),
        "ubuntu.start" => start_ubuntu_with_layout(state, req),
        "ubuntu.stop" => match crate::ubuntu::stop(state) {
            Ok(v) => {
                state.ubuntu.workspace.clear();
                state.ubuntu.memory.clear();
                state.ubuntu.skills.clear();
                state.ubuntu.shared.clear();
                Response::ok(req.id, v)
            }
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
                return Response::err(
                    req.id,
                    ErrorCode::RuntimeUnavailable,
                    "mount ns not started",
                );
            }
            Response::ok(req.id, json!({"mounts": []}))
        }
        "mount.prepare" => {
            if state.mock {
                Response::ok(req.id, json!({"prepared": true, "via": "mock"}))
            } else {
                Response::err(
                    req.id,
                    ErrorCode::RuntimeUnavailable,
                    "mount ns not started",
                )
            }
        }
        "workspace.info" => Response::ok(
            req.id,
            json!({
                "path": if runtime_layout_known(state) {
                    Some(state.ubuntu.workspace.clone())
                } else {
                    None::<String>
                },
                "guest": "/workspace",
                "layout_known": runtime_layout_known(state),
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
        "policy.get" => Response::ok(
            req.id,
            json!({
                "methods": state.policy.methods.keys().cloned().collect::<Vec<_>>(),
                "requireToken": state.policy.caller.require_token,
                "appUid": state.policy.caller.app_uid
            }),
        ),
        "health.get" => {
            let rootfs_ok = crate::layout::rootfs_looks_valid(&state.ubuntu.rootfs_or_default());
            let layout_known = runtime_layout_known(state);
            Response::ok(
                req.id,
                json!({
                    "ok": rootfs_ok && (!state.ubuntu.running || layout_known),
                    "mock": state.mock,
                    "ubuntu": state.ubuntu.running,
                    "ubuntu_pid": state.ubuntu.pid,
                    "ubuntu_version": state.ubuntu.version,
                    "rootfs_ok": rootfs_ok,
                    "layout_known": layout_known,
                    "sessions": state.sessions.len(),
                    "selinux_enforcing": true
                }),
            )
        }
        _ => Response::err(req.id, ErrorCode::BadParams, "unknown method"),
    }
}

/// Execute an already-authorized root.exec using a policy snapshot. This helper
/// intentionally owns the policy snapshot so callers can release AppState
/// before entering a potentially long privileged subprocess.
pub fn execute_root_authorized(mock: bool, spec: Option<MethodPolicy>, req: &Request) -> Response {
    let parsed = match parse_exec(&req.params) {
        Ok(p) => p,
        Err(code) => return Response::err(req.id, code, "bad exec params"),
    };
    let access_mode = match root_access_mode(req) {
        Ok(mode) => mode,
        Err(code) => return Response::err(req.id, code, "invalid root access mode"),
    };
    let spec_ref = spec.as_ref();
    let restricted = validate_exec(spec_ref, &parsed).is_ok();
    let one_time_override = access_mode == "standard" && !restricted && req.confirm_id.is_some();
    let unrestricted = access_mode == "full" || one_time_override;
    if !restricted && !unrestricted {
        return Response::err(req.id, ErrorCode::PolicyDenied, "exec policy");
    }
    if mock {
        let stdout = format!("{} {}", parsed.tool, parsed.args.join(" "));
        return Response::ok(
            req.id,
            json!({
                "exit_code": 0,
                "stdout_bytes": stdout.len(),
                "stderr_bytes": 0,
                "stdout_truncated": false,
                "stderr_truncated": false,
                "stdout": stdout,
                "stderr": "",
                "access_mode": access_mode
            }),
        );
    }
    let dynamic_allow;
    let allow: Vec<&str> = if unrestricted {
        dynamic_allow = vec![parsed.tool.as_str()];
        dynamic_allow
    } else {
        crate::exec::effective_allowlist(spec_ref)
    };
    match run_exec(&parsed, &allow) {
        Ok(out) => Response::ok(
            req.id,
            json!({
                "exit_code": out.exit_code,
                "stdout": out.stdout,
                "stderr": out.stderr,
                "stdout_bytes": out.stdout_bytes,
                "stderr_bytes": out.stderr_bytes,
                "stdout_truncated": out.stdout_truncated,
                "stderr_truncated": out.stderr_truncated,
                "access_mode": access_mode
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
        return match state.sessions.kill_tree(
            session,
            std::time::Duration::from_millis(10),
            &mut killer,
        ) {
            Ok(steps) => Response::ok(req.id, json!({"steps": steps.len(), "mock": true})),
            Err(code) => Response::err(req.id, code, "killTree failed"),
        };
    }
    #[cfg(unix)]
    {
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
                Response::ok(
                    req.id,
                    json!({
                        "steps": steps.len(),
                        "pgid": steps.first().map(|s| match s {
                            crate::session::KillStep::Term { pgid }
                            | crate::session::KillStep::Kill { pgid } => *pgid,
                        })
                    }),
                )
            }
            Err(code) => Response::err(req.id, code, "killTree failed"),
        }
    }
    #[cfg(not(unix))]
    {
        Response::err(
            req.id,
            ErrorCode::RuntimeUnavailable,
            "killTree requires unix",
        )
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
    fn authorization_can_be_separated_from_dispatch() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let request = req("system.ping", json!({}));
        assert!(authorize_request(&mut state, &request, None).is_ok());
        let resp = dispatch_authorized(&mut state, &request);
        assert!(resp.ok);
    }

    #[test]
    fn removed_policy_reload_is_rejected() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let resp = handle(
            &mut state,
            req("policy.reload", json!({"json": "{}"})),
            None,
        );
        assert!(!resp.ok);
        assert_eq!(resp.error.unwrap().code, "BAD_PARAMS");
    }

    #[test]
    fn standard_allowlisted_root_exec_runs_without_confirmation() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let request = req(
            "root.exec",
            json!({"tool":"getprop","args":[],"access_mode":"standard"}),
        );
        assert!(authorize_request(&mut state, &request, None).is_ok());
        assert!(dispatch_authorized(&mut state, &request).ok);
    }

    #[test]
    fn standard_outside_allowlist_requires_full_request_bound_confirmation() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let first = req(
            "root.exec",
            json!({"tool":"debuggerd","args":["123"],"access_mode":"standard"}),
        );
        let cid = authorize_request(&mut state, &first, None)
            .unwrap_err()
            .error
            .unwrap()
            .confirm_id
            .unwrap();

        let mut changed = req(
            "root.exec",
            json!({"tool":"debuggerd","args":["456"],"access_mode":"standard"}),
        );
        changed.confirm_id = Some(cid.clone());
        let mismatch = authorize_request(&mut state, &changed, None).unwrap_err();
        assert_eq!(mismatch.error.unwrap().code, "POLICY_DENIED");

        let mut original = first;
        original.confirm_id = Some(cid);
        let replay = authorize_request(&mut state, &original, None).unwrap_err();
        assert_eq!(replay.error.unwrap().code, "POLICY_DENIED");
    }

    #[test]
    fn standard_confirmation_is_one_shot_and_executes_exact_request() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let mut request = req(
            "root.exec",
            json!({"tool":"cmd","args":["package","list","packages"],"access_mode":"standard"}),
        );
        let cid = authorize_request(&mut state, &request, None)
            .unwrap_err()
            .error
            .unwrap()
            .confirm_id
            .unwrap();
        request.confirm_id = Some(cid.clone());
        assert!(authorize_request(&mut state, &request, None).is_ok());
        assert!(
            execute_root_authorized(true, state.policy.method("root.exec").cloned(), &request).ok
        );
        let mut replay = request.clone();
        replay.confirm_id = Some(cid);
        assert_eq!(
            authorize_request(&mut state, &replay, None)
                .unwrap_err()
                .error
                .unwrap()
                .code,
            "POLICY_DENIED"
        );
    }

    #[test]
    fn full_access_bypasses_standard_allowlist_without_confirmation() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let request = req(
            "root.exec",
            json!({"tool":"sh","args":["-c","id"],"access_mode":"full"}),
        );
        assert!(authorize_request(&mut state, &request, None).is_ok());
        assert!(
            execute_root_authorized(true, state.policy.method("root.exec").cloned(), &request).ok
        );
    }

    #[test]
    fn confirm_once_and_reuse_rejected() {
        let mut state = AppState::new(true, PolicyFile::default_policy());
        let first = handle(
            &mut state,
            req(
                "ubuntu.adminExec",
                json!({"argv":["/usr/bin/apt-get","update"]}),
            ),
            None,
        );
        let cid = first.error.unwrap().confirm_id.unwrap();
        let mut second = req(
            "ubuntu.adminExec",
            json!({"argv":["/usr/bin/apt-get","update"]}),
        );
        second.confirm_id = Some(cid.clone());
        let started = handle(&mut state, req("ubuntu.start", json!({})), None);
        assert!(started.ok);
        let ok = handle(&mut state, second, None);
        assert!(ok.ok);
        let mut third = req(
            "ubuntu.adminExec",
            json!({"argv":["/usr/bin/apt-get","update"]}),
        );
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
