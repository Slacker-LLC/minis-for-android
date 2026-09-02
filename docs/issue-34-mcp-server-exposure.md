# Issue #34: exposing Minis as an MCP server

## Current implementation

Minis already contains an on-device MCP server. This change makes that server configurable from Settings -> MCP Integrations instead of creating a second protocol stack.

The supported exposure contract in this branch is deliberately narrow:

- transport: stateless Streamable HTTP / JSON-RPC 2.0;
- endpoint: `http://127.0.0.1:18789/mcp`;
- interface: loopback only;
- authentication: Bearer token on every request;
- server enablement: explicit opt-in, persisted for the next application start;
- no configured token: start fails closed;
- repeated server crashes: the existing bounded supervisor stops retrying after five consecutive failures.

LAN interfaces, arbitrary bind hosts, TLS termination, and remote-network discovery are outside this slice. They require the separate network security policy tracked by Issue #40.

## Settings-managed credential

The Android settings surface owns one credential id, `android-settings`. Creating or rotating this credential never removes unrelated credentials.

A newly generated credential uses 32 bytes from `SecureRandom`, encoded as 64 lowercase hexadecimal characters. Its initial tool scope is an explicit snapshot of tools that the central `ToolPermissionManager` classifies `MCP_ALLOWED`. An empty scope is never written by the UI because the legacy token format interprets an empty scope as unrestricted over all MCP-visible tools.

The user can edit the credential's explicit tool set. Tools outside the token scope are filtered from `tools/list` and rejected by `tools/call`. Selecting a tool does not override the central permission policy: a centrally denied or local-only tool remains unavailable, and a tool classified `MCP_CONFIRM` still requires its normal one-shot human confirmation.

Rotating the Settings credential invalidates its previous bearer value immediately because authentication reads the current `TokenStore` on every request. Revoking the last token disables the server; if unrelated credentials remain, only the Settings-managed credential is revoked.

## UI behavior

Settings -> MCP Integrations now has a separate **Expose Minis** section before the external-server client list. It shows:

- enabled/running/error state;
- the fixed local endpoint;
- generate/rotate access-token action;
- editable exposed-tool scope;
- copyable client configuration;
- explicit Settings-token revocation.

The copied `mcpServers` JSON is intended for an MCP client running in the same Android network namespace because `127.0.0.1` is intentionally not a LAN address.

## Failure behavior

- Enabling without any credential fails closed and does not persist auto-start.
- A bind/start failure leaves `running=false` and exposes a concise error in Settings.
- Token state is refreshed on every start and status read, so a credential created after application initialization is immediately recognized.
- Disabling stops the listener, crash supervisor, and MCP keep-alive service.

## Verification

JVM coverage checks the fixed loopback endpoint, random token format and rotation, safe initial scope, token-scope list filtering, the legacy-empty-scope guard, and revocation closing the configured gate.

Repository CI is authoritative for Android compilation, lint, unit tests, packaging, and existing server regressions. No phone or adb operation is performed by this change. A live MCP-client connection test remains a device acceptance check and is not claimed by repository CI.

## Concurrent work boundary

- Issue #39 / PR #79 owns MCP **client** configuration hot reload. This branch does not change that Repository/Provider contract.
- Issue #40 owns LAN/TLS and broader network exposure policy.
- PR #69 owns Android application/package identity migration; this branch is based on the current package paths and its behavior must be preserved if those files move.
- PR #66 owns runtime distribution and does not define the MCP server credential/UI contract.
