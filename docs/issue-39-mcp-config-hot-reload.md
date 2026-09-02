# MCP configuration hot-reload contract (Issue #39)

## Scope

This change resolves the concrete runtime correctness problem in Issue #39: changing the global MCP server configuration must update the active MCP client connections and ToolRegistry without requiring an app restart.

It does **not** turn `McpRpcMethods` into a newly promised long-term public API. That interface-policy question remains separate from the hot-reload correctness contract.

## Source of truth and runtime consumer

`MCPRepository` owns the Android view of `servers.json` and the `servers` StateFlow. `MCPProvider` consumes that state to create MCP client sessions and register remote tools as `mcp.<server>.<tool>`.

Before this change, these operations updated Repository state/disk but left MCPProvider untouched:

- add;
- update;
- delete;
- global enable/disable/toggle;
- JSON import;
- re-reading a file modified by another supported writer.

That allowed the persisted config and ToolRegistry/session set to disagree until some unrelated caller explicitly invoked `MCPProvider.reload()`.

## Contract after this change

`MCPProvider.init(repository, context)` binds one module-internal Repository change callback to `MCPProvider.reload()`.

The Repository emits the callback after an effective global server-config mutation. Identical/no-op writes do not reconnect healthy sessions.

`reloadFromDisk()` compares the newly parsed file with the published state and reloads only when the effective config changed. This is the bridge for external writers such as the in-guest `minis-mcp-cli`: writing the file alone cannot synchronously invoke Android code, but the next supported disk refresh both republishes the config and reconnects the provider.

Session-only MCP enable/disable overrides do not reconnect global transports because they are per-session selection state, not server connection configuration.

## Failure semantics

The configuration write happens before the reload callback. A callback exception is logged but does not report the already-persisted configuration as rolled back. `MCPProvider.reload()` itself is asynchronous and already uses generation cancellation, bounded connection concurrency, per-server load timeout, and registry teardown/re-registration.

When MCPProvider is re-initialized with another Repository, the old Repository callback is detached first so stale objects cannot trigger reloads against the new source.

## Interface boundary

Current `McpRpcMethods` remains a debug/remote configuration surface in the repository. This change deliberately fixes behavior below that surface at `MCPRepository`, so UI, debug RPC and import paths cannot diverge in hot-reload semantics.

No statement here guarantees `McpRpcMethods` as a permanent external integration API. A future supported configuration interface should reuse the Repository contract rather than adding another MCP configuration source of truth.

Issue #40's `LOCAL_ONLY`, LAN HTTP/TLS and timeout questions are not changed here.

## Concurrent work

PR #66 and PR #69 currently carry broad runtime-distribution/application-identity changes and may move the same Kotlin classes to the new package identity. This PR is based on current `master` and owns only the MCP config-change/hot-reload behavior. When those migrations integrate, the callback semantics and tests must be preserved through any package move.

PR #75 and Issue #45 do not own this MCP client configuration path.

## Verification

`MCPRepositoryHotReloadTest` uses an isolated temporary `servers.json` and verifies:

- add/update/enable/import trigger exactly one callback for an effective change;
- identical writes and unchanged disk refreshes do not reconnect;
- an externally rewritten `servers.json` triggers a callback on `reloadFromDisk()`;
- preview parsing is read-only;
- a reload callback failure does not make a persisted mutation appear rolled back.

CI remains the authority for Android compilation and JVM test execution. No adb or device operation is required or claimed by this change.