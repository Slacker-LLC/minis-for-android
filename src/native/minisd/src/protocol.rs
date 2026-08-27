use serde::{Deserialize, Serialize};

pub const PROTOCOL_V: u32 = 1;
pub const SUPPORTED_VERSIONS: &[u32] = &[1];
pub const MAX_REQUEST_BYTES: usize = 64 * 1024;
pub const MAX_ARG_BYTES: usize = 4096;
pub const MAX_ARGS: usize = 32;
pub const REQUEST_TIMEOUT_MS: u64 = 30_000;
pub const CONFIRM_TTL_MS: u64 = 120_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    PolicyDenied,
    Timeout,
    BadParams,
    NotAuthorized,
    RuntimeUnavailable,
    Internal,
    ConfirmRequired,
    RateLimited,
    UnsupportedVersion,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::PolicyDenied => "POLICY_DENIED",
            Self::Timeout => "TIMEOUT",
            Self::BadParams => "BAD_PARAMS",
            Self::NotAuthorized => "NOT_AUTHORIZED",
            Self::RuntimeUnavailable => "RUNTIME_UNAVAILABLE",
            Self::Internal => "INTERNAL",
            Self::ConfirmRequired => "CONFIRM_REQUIRED",
            Self::RateLimited => "RATE_LIMITED",
            Self::UnsupportedVersion => "UNSUPPORTED_VERSION",
        }
    }

    pub fn all() -> &'static [ErrorCode] {
        &[
            Self::PolicyDenied,
            Self::Timeout,
            Self::BadParams,
            Self::NotAuthorized,
            Self::RuntimeUnavailable,
            Self::Internal,
            Self::ConfirmRequired,
            Self::RateLimited,
            Self::UnsupportedVersion,
        ]
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ClientHello {
    pub id: String,
    #[serde(default)]
    pub capabilities: Vec<String>,
    #[serde(default)]
    pub token: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Request {
    pub v: u32,
    pub id: u64,
    pub method: String,
    #[serde(default)]
    pub client: Option<ClientHello>,
    #[serde(default)]
    pub params: serde_json::Value,
    #[serde(default)]
    pub confirm_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ErrorBody {
    pub code: String,
    #[serde(default)]
    pub detail: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub confirm_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub supported: Option<Vec<u32>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Response {
    pub v: u32,
    pub id: u64,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ErrorBody>,
}

impl Response {
    pub fn ok(id: u64, result: serde_json::Value) -> Self {
        Self {
            v: PROTOCOL_V,
            id,
            ok: true,
            result: Some(result),
            error: None,
        }
    }

    pub fn err(id: u64, code: ErrorCode, detail: impl Into<String>) -> Self {
        Self {
            v: PROTOCOL_V,
            id,
            ok: false,
            result: None,
            error: Some(ErrorBody {
                code: code.as_str().to_string(),
                detail: detail.into(),
                confirm_id: None,
                supported: None,
            }),
        }
    }

    pub fn unsupported(id: u64) -> Self {
        let mut r = Self::err(id, ErrorCode::UnsupportedVersion, "unsupported protocol version");
        if let Some(e) = r.error.as_mut() {
            e.supported = Some(SUPPORTED_VERSIONS.to_vec());
        }
        r
    }

    pub fn confirm(id: u64, confirm_id: String) -> Self {
        let mut r = Self::err(id, ErrorCode::ConfirmRequired, "human confirmation required");
        if let Some(e) = r.error.as_mut() {
            e.confirm_id = Some(confirm_id);
        }
        r
    }
}

pub fn parse_request(bytes: &[u8]) -> Result<Request, Response> {
    if bytes.len() > MAX_REQUEST_BYTES {
        return Err(Response::err(0, ErrorCode::BadParams, "request too large"));
    }
    let raw = std::str::from_utf8(bytes)
        .map_err(|_| Response::err(0, ErrorCode::BadParams, "request is not utf-8"))?;
    let req: Request = serde_json::from_str(raw)
        .map_err(|e| Response::err(0, ErrorCode::BadParams, format!("invalid json: {e}")))?;
    if req.v != PROTOCOL_V {
        return Err(Response::unsupported(req.id));
    }
    if req.method.is_empty() {
        return Err(Response::err(req.id, ErrorCode::BadParams, "missing method"));
    }
    Ok(req)
}

pub fn encode_response(resp: &Response) -> Result<String, String> {
    serde_json::to_string(resp).map_err(|e| e.to_string())
}

pub const KNOWN_METHODS: &[&str] = &[
    "system.hello",
    "system.ping",
    "root.probe",
    "root.exec",
    "root.shellRaw",
    "ubuntu.start",
    "ubuntu.stop",
    "ubuntu.status",
    "ubuntu.exec",
    "ubuntu.adminExec",
    "ubuntu.provision",
    "proc.killTree",
    "mount.list",
    "mount.prepare",
    "workspace.info",
    "workspace.setQuota",
    "policy.get",
    "health.get",
];

pub fn is_known_method(method: &str) -> bool {
    KNOWN_METHODS.contains(&method)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t_u1_roundtrip() {
        let raw = r#"{"v":1,"id":1,"method":"root.exec","params":{"tool":"pm","args":["force-stop","com.example.app"],"timeout_ms":30000}}"#;
        let req = parse_request(raw.as_bytes()).unwrap();
        assert_eq!(req.method, "root.exec");
        assert_eq!(req.params["tool"], "pm");
        let resp = Response::ok(1, serde_json::json!({"exit_code":0,"stdout":"ok","stderr":""}));
        let encoded = encode_response(&resp).unwrap();
        let back: Response = serde_json::from_str(&encoded).unwrap();
        assert!(back.ok);
        assert_eq!(back.result.unwrap()["exit_code"], 0);
    }

    #[test]
    fn removed_privileged_control_methods_are_not_known() {
        assert!(!is_known_method("policy.reload"));
        assert!(!is_known_method("supervisor.status"));
        assert!(!is_known_method("supervisor.restartCloudflared"));
        assert!(!is_known_method("supervisor.stopCloudflared"));
    }

    #[test]
    fn t_u1_rejects_bad_json_missing_and_version() {
        assert!(!parse_request(b"{").unwrap_err().ok);
        assert!(!parse_request(br#"{"v":1,"id":2}"#).unwrap_err().ok);
        let err = parse_request(br#"{"v":99,"id":3,"method":"system.ping"}"#).unwrap_err();
        assert_eq!(err.error.unwrap().code, "UNSUPPORTED_VERSION");
    }

    #[test]
    fn t_u2_every_error_code_serializes() {
        for code in ErrorCode::all() {
            let resp = Response::err(7, *code, "detail");
            let s = encode_response(&resp).unwrap();
            let back: Response = serde_json::from_str(&s).unwrap();
            assert!(!back.ok);
            assert_eq!(back.error.as_ref().unwrap().code, code.as_str());
            assert_eq!(back.error.unwrap().detail, "detail");
        }
    }
}
