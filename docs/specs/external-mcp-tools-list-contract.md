# External MCP `tools/list` contract for Minis for Android

Status: repository-observed integration contract for Issue #41.
Baseline inspected: `master@ba129724d943317e15c5be05ce77cb52be237db8`.

This guide documents what the current Minis for Android code actually accepts and exposes when it acts as an MCP client. It deliberately does **not** promote repository behavior into an official MCP requirement. Where the repository cannot prove an upstream protocol rule, that item is marked **Unresolved upstream constraint** and paired with a reproducible experiment.

## Short answer

For the current Android client, a `tools/list` page must have a JSON object `result`; `result.tools` may be absent (treated as an empty list). A tool needs a non-blank `name` to survive parsing. `description` and `input_schema` are optional at this parser boundary. The parser does not validate top-level JSON Schema `type`, `properties`, or `required`.

However, only `input_schema.properties` is projected into the model-facing `AgentToolDefinition.parameters`. The top-level `required` array is not copied into `AgentToolDefinition.required`, and top-level `type` is not consulted. Therefore a schema can parse successfully while losing required-argument semantics in Minis' local model/tool preflight.

The `20` constant is **not a tool-count limit**. `MCPRepository.MAX_MCPS_IN_PROMPT = 20` caps the number of enabled MCP **servers** named in the system-prompt fragment, after sorting by `createdAt` descending. `MCPClientSession.listTools()` has no 20-tool cap; it follows `nextCursor` and uses a separate 50-page runaway guard.

## Evidence matrix

| Concern | Repository evidence | Current Minis behavior | Guidance for external servers |
| --- | --- | --- | --- |
| `result` / `tools` | `MCPClientCodec.parseToolsList` | Error frame or missing object `result` => parse failure. Missing `result.tools` => empty list. Non-object array entries are skipped. | Always return a JSON object `result` and an explicit `tools` array for interoperability and diagnostics. |
| tool `name` | `MCPClientCodec.parseToolsList` | `optString("name")`; blank names are skipped. | Supply a stable, non-blank name. Avoid relying on characters that later need provider sanitization. |
| schema wire key | `MCPClientCodec.parseToolsList`; `MCPClientProtocolTest` | Current client reads `input_schema` with `optJSONObject`; missing or non-object => `null`. Existing protocol test also serves `input_schema`. | For this exact baseline, `input_schema` is the field the client consumes. Official MCP spelling is unresolved from this repository alone; test the target upstream-compatible spelling before claiming protocol conformance. |
| top-level `type` | `MCPClientCodec.parseToolsList`; `MCPToolHandler.schemaToParams` | Not validated by parser and not consulted by projection. | Prefer `"type":"object"` because Minis' own outbound tool schemas are object schemas and that shape is the least ambiguous. Do not interpret parser tolerance as an upstream guarantee. |
| `properties` | `MCPToolHandler.schemaToParams` | Missing/non-object `properties` => empty parameter map. Each property must itself be an object to be projected. Property `type` defaults to `string`; property `description` defaults to empty; `enum` is copied as strings. | Provide an object `properties`, even when empty. Give each exposed property an explicit type and useful description. |
| top-level `required` | `MCPClientCodec.RemoteTool`; `MCPToolHandler.definition`; `schemaToParams` | The raw `JSONObject` may contain `required`, but `MCPToolHandler` builds `AgentToolDefinition` without setting `required`; the projection helper does not read it. Current Minis therefore does not carry remote top-level requiredness into local preflight. | Include `required` for external ecosystem correctness, but do not rely on this Minis baseline to enforce it before `tools/call`. The remote server must validate arguments itself. |
| tool `description` | `MCPClientCodec.parseToolsList`; `MCPToolHandler.definition` | Missing/blank => `null`, then fallback text `Remote MCP tool <tool> (server <server>)`. No MCP-client-side length truncation is present in the inspected parse/registration path. | Provide a concise selection-oriented description, including important negative-use conditions when useful. Treat any official length/format limit as unresolved until verified upstream. |
| `20` limit | `MCPRepository.MAX_MCPS_IN_PROMPT`; `mcpPromptFragment` | Top 20 **enabled servers** by `createdAt` are mentioned in the prompt. It does not cap tools returned by one server. | A server exposing ~10 tools is not rejected by this constant. Keep tool sets focused for model selection quality, but there is no repository-proven 20-tool protocol limit. |
| pagination | `MCPClientSession.listTools` | Follows `nextCursor` until absent. More than 50 pages throws `tools/list pagination runaway`. | Terminate pagination and never cycle cursors. This 50-page guard is a Minis implementation safety limit, not an asserted MCP standard. |
| server-name sanitization | `MCPProvider.sanitizeId` | Before registration, every server-id character outside `[a-zA-Z0-9_.-]` is replaced by `_`. | Prefer server IDs already inside that character set. Avoid IDs that collide after replacement (for example two distinct IDs that normalize to the same value). |
| tool-name sanitization | `MCPProvider.connectOne`; `MCPToolHandler.definition`; `AgentToolDefinition.apiName`; `ToolRegistry.register` | The remote tool name is appended raw to canonical `mcp.<sanitizedServer>.<tool>`. `MCPProvider` does **not** apply `sanitizeId` to the tool name. Separately, provider wire names use `AgentToolDefinition.apiName`: non `[a-zA-Z0-9_-]` => `_`, max 64 chars, empty fallback `tool`; `ToolRegistry` aliases that wire name back to the canonical name. | Use simple ASCII tool names (letters, digits, `_`, `-`) to avoid canonical/wire divergence and collision risk. Underscores and digits are supported by Minis' wire sanitizer. |
| `rpc.discover` | `docs/specs/debug-server-api.md`; `DebugMethodRegistry` / `DebugRPCHandler` references there | `rpc.discover` is a developer-only DebugServer JSON-RPC catalogue, available only in debug builds behind loopback/token controls. It is not forwarded to external MCP servers and is not the MCP discovery boundary. | For external MCP integration, Minis discovers tools through the MCP handshake and `tools/list`. Do not implement Minis DebugServer `rpc.discover` just to satisfy the MCP client. |
| transport validation | `MCPHttpTransport`; `MCPClientCodec` | HTTP/SSE transport carries frames; schema interpretation happens in the codec/handler path, not in the HTTP transport. | Debug transport failures separately from schema/projection failures. |

## Naming stages

A remote tool does not have one single name throughout the stack.

1. Remote name: exactly the non-blank `tool.name` parsed from `tools/list`.
2. Canonical local name: `mcp.<sanitizeId(serverId)>.<remote tool name>`.
3. Provider wire name: `AgentToolDefinition.apiName` sanitizes the entire canonical name to `[a-zA-Z0-9_-]`, replaces other characters with `_`, and truncates to 64 characters.
4. Dispatch: `ToolRegistry.register` keeps the canonical name and adds the wire name as an alias when different.

This means server-ID collisions after `sanitizeId`, and wire-name collisions after `apiName` sanitization/truncation, are integration hazards. The inspected code does not establish a dedicated collision-rejection contract for external MCP names.

## Schema best-practice shape for this client

The following is a **recommended interoperable shape**, not a claim that every field is required by the current parser:

```json
{
  "name": "plan_task",
  "description": "Build a task plan. Do not use for direct execution.",
  "input_schema": {
    "type": "object",
    "properties": {
      "request": {
        "type": "string",
        "description": "The task to plan."
      }
    },
    "required": ["request"]
  }
}
```

On this baseline, `request` becomes a model-visible parameter, but `required: ["request"]` is not transferred into `AgentToolDefinition.required`. The MCP server remains responsible for rejecting invalid arguments at invocation time.

## Discovery boundary

`rpc.discover` is authoritative only for the app's **debug HTTP JSON-RPC server** described in `docs/specs/debug-server-api.md`. It returns the catalogue generated from `DebugMethodRegistry` and is intentionally build-specific.

External MCP discovery follows a different path:

`MCPProvider` -> `MCPClientSession.connect()` -> `initialize` / `notifications/initialized` -> `MCPClientSession.listTools()` -> `tools/list` pages -> `MCPToolHandler` -> `ToolRegistry`.

The user-facing MCP prompt reinforces this boundary by instructing the agent to run `minis-mcp-cli tools <server>` to inspect available tools and `minis-mcp-cli call <server> <tool> [args]` to invoke them. No repository evidence shows an MCP-side `rpc.discover` equivalent that third-party servers must implement.

## Unresolved upstream constraints and reproducible experiments

The repository is sufficient to describe Minis behavior, but not to establish every official MCP rule. The following remain unresolved **as upstream/official constraints** from repository evidence alone:

- whether the official wire field for the tool input schema is `input_schema`, `inputSchema`, or version-dependent;
- whether an official MCP tool schema must have top-level `type: "object"`, `properties`, or `required`;
- any official maximum length or formatting rule for tool descriptions;
- the official tool-name character grammar and maximum length;
- whether additional/nested JSON Schema keywords have mandatory support.

Reproduce these questions without changing production code:

1. Run a local test MCP HTTP server using the protocol version expected by `MCPClientCodec.PROTOCOL_VERSION` (`2025-06-18`).
2. Keep `initialize` constant and vary exactly one `tools/list` field per run: `input_schema` vs `inputSchema`; missing/wrong `type`; missing/empty/malformed `properties`; present/absent `required`; missing/empty/long `description`; unusual server/tool names.
3. Include a 21+ tool response to confirm that tool enumeration is independent of the 20-server prompt cap.
4. Include paginated responses with a terminating cursor, then a deliberately cycling cursor, to observe the 50-page Minis guard.
5. For every case record four boundaries separately: codec parse result, `MCPToolHandler` projected definition, `ToolRegistry` canonical/wire names, and remote `tools/call` argument validation.
6. To determine *official* conformance, run the same fixtures through the authoritative MCP conformance tooling/specification for the exact negotiated protocol version and record the version/tooling commit. Do not infer official requirements from Minis' tolerant parser.

## Regression fixture

`src/android/app/src/test/resources/mcp/tools-list-contract/repo-observed.json` and `MCPToolsListContractFixtureTest` lock down the repository-observed parser/projection and wire-name behavior without changing production behavior. They are intentionally not an MCP conformance suite.
