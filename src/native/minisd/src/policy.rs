use crate::protocol::{is_known_method, ErrorCode, LEGACY_REMOVED_METHODS};
use serde::Deserialize;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    Allow,
    Deny,
    Confirm,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct ArgRule {
    #[serde(default, rename = "regexDeny")]
    pub regex_deny: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MethodPolicy {
    pub mode: Mode,
    #[serde(default, rename = "toolAllowlist")]
    pub tool_allowlist: Option<Vec<String>>,
    #[serde(default, rename = "argRules")]
    pub arg_rules: BTreeMap<String, ArgRule>,
    #[serde(default, rename = "ratePerMin")]
    pub rate_per_min: Option<u32>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct CallerPolicy {
    #[serde(default, rename = "appUid")]
    pub app_uid: u32,
    #[serde(default, rename = "requireToken")]
    pub require_token: bool,
    #[serde(default)]
    pub token: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PolicyFile {
    #[serde(default)]
    pub methods: BTreeMap<String, MethodPolicy>,
    #[serde(default)]
    pub caller: CallerPolicy,
}

pub const DEFAULT_POLICY_JSON: &str = include_str!("../policy.default.json");

fn is_builtin_root_exec_tool(tool: &str) -> bool {
    matches!(
        tool,
        "pm" | "am" | "settings" | "dumpsys" | "getprop" | "mount"
    )
}

impl PolicyFile {
    pub fn parse(json: &str) -> Result<Self, String> {
        let mut policy: PolicyFile =
            serde_json::from_str(json).map_err(|e| format!("policy json: {e}"))?;
        // In-place upgrades keep the previous installation's policy file until
        // the app materializes a fresh one. Methods that were intentionally
        // removed are dropped instead of fatal: dispatch never consults them,
        // so retaining them could only block startup. Unknown methods remain a
        // hard error so a malformed or future policy still fails closed.
        policy
            .methods
            .retain(|name, _| !LEGACY_REMOVED_METHODS.contains(&name.as_str()));
        policy.validate()?;
        Ok(policy)
    }

    pub fn default_policy() -> Self {
        Self::parse(DEFAULT_POLICY_JSON).expect("embedded default policy")
    }

    pub fn validate(&self) -> Result<(), String> {
        for (name, spec) in &self.methods {
            if !is_known_method(name) {
                return Err(format!("unknown method in policy: {name}"));
            }
            if let Some(n) = spec.rate_per_min {
                if n == 0 {
                    return Err(format!("{name}.ratePerMin must be >= 1"));
                }
            }
            if name == "root.exec" {
                if let Some(tools) = &spec.tool_allowlist {
                    for tool in tools {
                        if !is_builtin_root_exec_tool(tool) {
                            return Err(format!(
                                "root.exec.toolAllowlist may only narrow the built-in capability set: {tool}"
                            ));
                        }
                    }
                }
            }
        }
        Ok(())
    }

    pub fn method(&self, name: &str) -> Option<&MethodPolicy> {
        self.methods.get(name)
    }

    pub fn implicit_mode(&self, name: &str) -> Mode {
        if let Some(m) = self.method(name) {
            return m.mode;
        }
        match name {
            "system.ping" | "system.hello" | "root.probe" | "health.get" => Mode::Allow,
            "root.shellRaw" => Mode::Deny,
            _ => Mode::Deny,
        }
    }
}

pub fn wildcard_match(pat: &str, text: &str) -> bool {
    let mut idx = 0usize;
    for part in pat.split(".*") {
        if part.is_empty() {
            continue;
        }
        match text[idx..].find(part) {
            Some(found) => idx += found + part.len(),
            None => return false,
        }
    }
    true
}

pub fn args_denied(rule: &ArgRule, args: &[String]) -> bool {
    let joined = args.join(" ");
    rule.regex_deny.iter().any(|p| wildcard_match(p, &joined))
}

pub struct Decision {
    pub mode: Mode,
    pub rate_per_min: Option<u32>,
}

pub fn decide_method(policy: &PolicyFile, method: &str) -> Result<Decision, ErrorCode> {
    if !is_known_method(method) {
        return Err(ErrorCode::BadParams);
    }
    let spec = policy.method(method);
    let mode = spec
        .map(|s| s.mode)
        .unwrap_or_else(|| policy.implicit_mode(method));
    if mode == Mode::Deny {
        return Err(ErrorCode::PolicyDenied);
    }
    Ok(Decision {
        mode,
        rate_per_min: spec.and_then(|s| s.rate_per_min),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_policy_loads() {
        let p = PolicyFile::default_policy();
        assert_eq!(p.implicit_mode("root.shellRaw"), Mode::Deny);
        assert_eq!(p.method("root.exec").unwrap().mode, Mode::Allow);
    }

    #[test]
    fn invalid_policy_rejected() {
        assert!(PolicyFile::parse(r#"{"methods":{"nope":{"mode":"allow"}}}"#).is_err());
        assert!(PolicyFile::parse(
            r#"{"methods":{"system.ping":{"mode":"allow","ratePerMin":0}}}"#
        )
        .is_err());
        assert!(PolicyFile::parse("{").is_err());
    }

    #[test]
    fn legacy_removed_methods_are_stripped_but_unknown_methods_still_fail() {
        let legacy = PolicyFile::parse(
            r#"{"methods":{"policy.reload":{"mode":"allow"},"system.ping":{"mode":"allow"}}}"#,
        )
        .unwrap();
        assert!(legacy.method("policy.reload").is_none());
        assert!(legacy.method("system.ping").is_some());
        assert!(PolicyFile::parse(r#"{"methods":{"nope":{"mode":"allow"}}}"#).is_err());
    }

    #[test]
    fn root_exec_policy_cannot_expand_builtin_capabilities() {
        assert!(PolicyFile::parse(
            r#"{"methods":{"root.exec":{"mode":"allow","toolAllowlist":["pm"]}}}"#
        )
        .is_ok());
        assert!(PolicyFile::parse(
            r#"{"methods":{"root.exec":{"mode":"allow","toolAllowlist":["pm","reboot"]}}}"#
        )
        .is_err());
    }

    #[test]
    fn wildcard_shell_su() {
        assert!(wildcard_match("shell.*su.*", "shell su"));
        assert!(wildcard_match("shell.*su.*", "shell foo su bar"));
        assert!(!wildcard_match("shell.*su.*", "force-stop com.x"));
    }

    #[test]
    fn wildcard_conservative_semantics() {
        assert!(wildcard_match("su.*", "pm su"));
        assert!(wildcard_match("shell.*su.*", "shell su -c id"));
        assert!(!wildcard_match("shell.*su.*", "pm su"));
        assert!(wildcard_match("rm.*-rf.*/", "rm -rf /"));
        assert!(wildcard_match("a.*b.*c", "a x b y c"));
        assert!(!wildcard_match("a.*b.*c", "a x b"));
        assert!(wildcard_match(".*", "anything"));
    }
}
