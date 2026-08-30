use serde::{Deserialize, Serialize};

pub const PROTOCOL_V: u32 = 1;
pub const SUPPORTED_VERSIONS: &[u32] = &[1];
pub const FRAME_HEADER_BYTES: usize = 4;
pub const MAX_REQUEST_BYTES: usize = 64 * 1024;
pub const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
pub const MAX_ARG_BYTES: usize = 4096;
pub const MAX_ARGS: usize = 32;
pub const REQUEST_TIMEOUT_MS: u64 = 30_000;
pub const CONFIRM_TTL_MS: u64 = 120_000;
pub const PRE_EXEC_TOKEN_ENV: &str = "MINISD_PREEXEC_TOKEN";
const PRE_EXEC_MARKER_PREFIX: &str = "__MINISD_PREEXEC_V1__";

fn valid_pre_exec_token(token: &str) -> bool {
    token.len() == 32 && token.bytes().all(|byte| byte.is_ascii_hexdigit())
}

pub fn format_pre_exec_marker(token: &str, helper_code: u8) -> Option<String> {
    if !valid_pre_exec_token(token) {
        return None;
    }
    Some(format!("{PRE_EXEC_MARKER_PREFIX}:{token}:{helper_code}"))
}

pub fn parse_pre_exec_marker(line: &str, expected_token: &str) -> Option<u8> {
    if !valid_pre_exec_token(expected_token) {
        return None;
    }
    let prefix = format!("{PRE_EXEC_MARKER_PREFIX}:{expected_token}:");
    line.strip_prefix(&prefix)?.parse::<u8>().ok()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    PolicyDenied,
    Timeout,
    ToolTimeout,
    TransportTimeout,
    ProcessKilled,
    UserCancelled,
    CleanupFailed,
    BadParams,
    NotAuthorized,
    RuntimeUnavailable,
    KeeperNamespaceLost,
    ChrootUnavailable,
    GuestPrivilegeSetupFailed,
    GuestExecveFailed,
    RootfsInvalid,
    RuntimeSwitchUnknown,
    RuntimeLayoutMismatch,
    Internal,
    ConfirmRequired,
    RateLimited,
    UnsupportedVersion,
    MountRoUnsupported,
    MountAttestationRequired,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::PolicyDenied => "POLICY_DENIED",
            Self::Timeout => "TIMEOUT",
            Self::ToolTimeout => "TOOL_TIMEOUT",
            Self::TransportTimeout => "TRANSPORT_TIMEOUT",
            Self::ProcessKilled => "PROCESS_KILLED",
            Self::UserCancelled => "USER_CANCELLATION",
            Self::CleanupFailed => "CLEANUP_FAILURE",
            Self::BadParams => "BAD_PARAMS",
            Self::NotAuthorized => "NOT_AUTHORIZED",
            Self::RuntimeUnavailable => "RUNTIME_UNAVAILABLE",
            Self::KeeperNamespaceLost => "KEEPER_NAMESPACE_LOST",
            Self::ChrootUnavailable => "CHROOT_UNAVAILABLE",
            Self::GuestPrivilegeSetupFailed => "PRIVILEGE_SETUP_FAILED",
            Self::GuestExecveFailed => "EXEC_UNAVAILABLE",
            Self::RootfsInvalid => "ROOTFS_INVALID",
            Self::RuntimeSwitchUnknown => "RUNTIME_SWITCH_UNKNOWN",
            Self::RuntimeLayoutMismatch => "RUNTIME_LAYOUT_MISMATCH",
            Self::Internal => "INTERNAL",
            Self::ConfirmRequired => "CONFIRM_REQUIRED",
            Self::RateLimited => "RATE_LIMITED",
            Self::UnsupportedVersion => "UNSUPPORTED_VERSION",
            Self::MountRoUnsupported => "MOUNT_RO_UNSUPPORTED",
            Self::MountAttestationRequired => "MOUNT_ATTESTATION_REQUIRED",
        }
    }

    pub fn all() -> &'static [ErrorCode] {
        &[
            Self::PolicyDenied,
            Self::Timeout,
            Self::ToolTimeout,
            Self::TransportTimeout,
            Self::ProcessKilled,
            Self::UserCancelled,
            Self::CleanupFailed,
            Self::BadParams,
            Self::NotAuthorized,
            Self::RuntimeUnavailable,
            Self::KeeperNamespaceLost,
            Self::ChrootUnavailable,
            Self::GuestPrivilegeSetupFailed,
            Self::GuestExecveFailed,
            Self::RootfsInvalid,
            Self::RuntimeSwitchUnknown,
            Self::RuntimeLayoutMismatch,
            Self::Internal,
            Self::ConfirmRequired,
            Self::RateLimited,
            Self::UnsupportedVersion,
            Self::MountRoUnsupported,
            Self::MountAttestationRequired,
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
        let mut r = Self::err(
            id,
            ErrorCode::UnsupportedVersion,
            "unsupported protocol version",
        );
        if let Some(e) = r.error.as_mut() {
            e.supported = Some(SUPPORTED_VERSIONS.to_vec());
        }
        r
    }

    pub fn confirm(id: u64, confirm_id: String) -> Self {
        let mut r = Self::err(
            id,
            ErrorCode::ConfirmRequired,
            "human confirmation required",
        );
        if let Some(e) = r.error.as_mut() {
            e.confirm_id = Some(confirm_id);
        }
        r
    }
}

pub fn frame_header(len: usize, max: usize) -> Result<[u8; FRAME_HEADER_BYTES], ErrorCode> {
    if len == 0 || len > max || len > u32::MAX as usize {
        return Err(ErrorCode::BadParams);
    }
    Ok((len as u32).to_be_bytes())
}

pub fn decode_frame_len(header: [u8; FRAME_HEADER_BYTES], max: usize) -> Result<usize, ErrorCode> {
    let len = u32::from_be_bytes(header) as usize;
    if len == 0 || len > max {
        return Err(ErrorCode::BadParams);
    }
    Ok(len)
}

pub fn encode_frame(payload: &[u8], max: usize) -> Result<Vec<u8>, ErrorCode> {
    let header = frame_header(payload.len(), max)?;
    let mut out = Vec::with_capacity(FRAME_HEADER_BYTES + payload.len());
    out.extend_from_slice(&header);
    out.extend_from_slice(payload);
    Ok(out)
}

#[allow(clippy::result_large_err)]
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
        return Err(Response::err(
            req.id,
            ErrorCode::BadParams,
            "missing method",
        ));
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
    "root.fullExec",
    "root.shellRaw",
    "ubuntu.start",
    "ubuntu.stop",
    "ubuntu.status",
    "ubuntu.exec",
    "ubuntu.adminExec",
    "ubuntu.provision",
    "exec.cancel",
    "proc.killTree",
    "mount.list",
    "mount.reconcile",
    "workspace.info",
    "workspace.file",
    "workspace.setQuota",
    "runtime.maintenance",
    "policy.get",
    "health.get",
];

pub fn is_known_method(method: &str) -> bool {
    KNOWN_METHODS.contains(&method)
}

pub const LEGACY_REMOVED_METHODS: &[&str] = &[
    "mount.prepare",
    "policy.reload",
    "supervisor.status",
    "supervisor.restartCloudflared",
    "supervisor.stopCloudflared",
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn t_u1_roundtrip() {
        let raw = r#"{"v":1,"id":1,"method":"root.exec","params":{"tool":"pm","args":["force-stop","com.example.app"],"timeout_ms":30000}}"#;
        let req = parse_request(raw.as_bytes()).unwrap();
        assert_eq!(req.method, "root.exec");
        assert_eq!(req.params["tool"], "pm");
        let resp = Response::ok(
            1,
            serde_json::json!({"exit_code":0,"stdout":"ok","stderr":""}),
        );
        let encoded = encode_response(&resp).unwrap();
        let back: Response = serde_json::from_str(&encoded).unwrap();
        assert!(back.ok);
        assert_eq!(back.result.unwrap()["exit_code"], 0);
    }

    #[test]
    fn frame_roundtrip_and_bounds() {
        let payload = br#"{"v":1}"#;
        let frame = encode_frame(payload, MAX_REQUEST_BYTES).unwrap();
        let header: [u8; FRAME_HEADER_BYTES] = frame[..FRAME_HEADER_BYTES].try_into().unwrap();
        let len = decode_frame_len(header, MAX_REQUEST_BYTES).unwrap();
        assert_eq!(len, payload.len());
        assert_eq!(&frame[FRAME_HEADER_BYTES..], payload);
        assert_eq!(
            frame_header(0, MAX_REQUEST_BYTES),
            Err(ErrorCode::BadParams)
        );
        assert_eq!(
            frame_header(MAX_REQUEST_BYTES + 1, MAX_REQUEST_BYTES),
            Err(ErrorCode::BadParams)
        );
        assert_eq!(
            decode_frame_len(
                ((MAX_REQUEST_BYTES + 1) as u32).to_be_bytes(),
                MAX_REQUEST_BYTES
            ),
            Err(ErrorCode::BadParams)
        );
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
    fn runtime_pre_exec_error_names_match_android_contract() {
        assert_eq!(
            ErrorCode::KeeperNamespaceLost.as_str(),
            "KEEPER_NAMESPACE_LOST"
        );
        assert_eq!(ErrorCode::ChrootUnavailable.as_str(), "CHROOT_UNAVAILABLE");
        assert_eq!(
            ErrorCode::GuestPrivilegeSetupFailed.as_str(),
            "PRIVILEGE_SETUP_FAILED"
        );
        assert_eq!(ErrorCode::GuestExecveFailed.as_str(), "EXEC_UNAVAILABLE");
        assert_eq!(
            ErrorCode::RuntimeLayoutMismatch.as_str(),
            "RUNTIME_LAYOUT_MISMATCH"
        );
    }

    #[test]
    fn pre_exec_marker_requires_exact_unexposed_token() {
        let token = "0123456789abcdef0123456789abcdef";
        let marker = format_pre_exec_marker(token, 4).unwrap();
        assert_eq!(parse_pre_exec_marker(&marker, token), Some(4));
        assert_eq!(
            parse_pre_exec_marker(&marker, "fedcba9876543210fedcba9876543210"),
            None
        );
        assert!(format_pre_exec_marker("not-a-token", 4).is_none());
        assert_eq!(
            parse_pre_exec_marker("__MINISD_PREEXEC_V1__:bad:4", token),
            None
        );
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
