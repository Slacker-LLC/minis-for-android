use minisd::policy::PolicyFile;
use minisd::protocol::{parse_request, ClientHello, KNOWN_METHODS, Request};
use minisd::state::AppState;
use minisd::{handle, handle_bytes};
use serde_json::json;

fn client(method: &str) -> ClientHello {
    ClientHello {
        id: "contract".into(),
        capabilities: vec![method.into()],
        token: None,
    }
}

fn call(state: &mut AppState, method: &str, params: serde_json::Value) -> minisd::Response {
    handle(
        state,
        Request {
            v: 1,
            id: 1,
            method: method.into(),
            client: Some(client(method)),
            params,
            confirm_id: None,
        },
        None,
    )
}

#[test]
fn t_i1_every_known_method_has_shaped_response() {
    let mut state = AppState::new(true, PolicyFile::default_policy());
    assert!(call(&mut state, "ubuntu.start", json!({})).ok);
    for method in KNOWN_METHODS {
        let params = match *method {
            "root.exec" => json!({"tool":"getprop","args":["ro.build.version.release"]}),
            "root.shellRaw" => json!({"cmd":"id"}),
            "ubuntu.exec" => json!({"argv":["/usr/bin/id"]}),
            "ubuntu.adminExec" => json!({"argv":["/usr/bin/apt-get","update"]}),
            "ubuntu.provision" => json!({}),
            "proc.killTree" => json!({"session":"s-contract"}),
            "workspace.setQuota" => json!({"quota_bytes": 1024}),
            "policy.reload" => json!({"json": minisd::policy::DEFAULT_POLICY_JSON}),
            _ => json!({}),
        };
        let mut req = Request {
            v: 1,
            id: 1,
            method: (*method).into(),
            client: Some(client(method)),
            params,
            confirm_id: None,
        };
        if *method == "ubuntu.exec" || *method == "ubuntu.adminExec" {
            let _ = call(&mut state, "ubuntu.start", json!({}));
        }
        if *method == "ubuntu.adminExec" {
            let pending = handle(&mut state, req.clone(), None);
            req.confirm_id = pending.error.and_then(|e| e.confirm_id);
        }
        let resp = handle(&mut state, req, None);
        if *method == "root.shellRaw" {
            assert!(!resp.ok, "shellRaw must deny");
            assert_eq!(resp.error.unwrap().code, "POLICY_DENIED");
        } else {
            assert!(resp.ok, "{method} should succeed in mock: {:?}", resp.error);
        }
    }
}

#[test]
fn t_i1_stdio_bytes_contract() {
    let mut state = AppState::new(true, PolicyFile::default_policy());
    let resp = handle_bytes(
        &mut state,
        br#"{"v":1,"id":2,"method":"system.ping","client":{"id":"c","capabilities":["system.ping"]}}"#,
    );
    assert!(resp.ok);
    assert_eq!(resp.result.unwrap()["pong"], true);
}

#[test]
fn unknown_method_and_unauth() {
    let mut state = AppState::new(true, PolicyFile::default_policy());
    let resp = handle_bytes(
        &mut state,
        br#"{"v":1,"id":1,"method":"root.drop","client":{"id":"c","capabilities":["root.drop"]}}"#,
    );
    assert_eq!(resp.error.unwrap().code, "BAD_PARAMS");
    let parsed = parse_request(br#"{"v":1,"id":1,"method":"system.ping"}"#).unwrap();
    let denied = handle(&mut state, parsed, None);
    assert_eq!(denied.error.unwrap().code, "NOT_AUTHORIZED");
}


