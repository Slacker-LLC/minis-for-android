# Android `minis://` URL Scheme

**Status:** current Android implementation on `refactor/direct-ubuntu-runtime`.

Source of truth: `ChatLinkResolver`, `DeepLinkHandler`, `RuntimePathRegistry`, `runtime.ubuntu.UbuntuPaths`, and `runtime.files.WorkspaceFileClient`.

The scheme has two roles:

1. application navigation deep links recognized by `DeepLinkHandler`;
2. guest/resource links resolved by `ChatLinkResolver` when the URL is not a recognized navigation target.

## Routing order

`ChatLinkResolver.resolveAsync(...)` performs link resolution on `Dispatchers.IO` and applies this order:

1. recognized `minis://` navigation action → `DeepLink`;
2. canonical guest/resource path → stage/read through the runtime file layer and return `SandboxFile`;
3. supported Android external scheme (`intent:`, `mailto:`, `tel:`, `geo:`, `market:` etc.) → `ExternalApp`;
4. remaining URL → `Web`.

A recognized navigation URI is never reinterpreted as a guest file.

## Navigation URLs

Current recognized routes include:

| URL | Android action |
| --- | --- |
| `minis://share` | Open share flow |
| `minis://views/alarm` | Open alarm list |
| `minis://open_terminal?init_command=...` | Open terminal with optional initial command |
| `minis://action/new_chat` | New chat |
| `minis://action/voice_chat` | New chat + voice input |
| `minis://action/camera_chat` | New chat + camera attachment |
| `minis://session/<sessionId>` | Open session |
| `minis://session/<sessionId>/<resource-path>?title=...` | Open session HTML preview |
| `minis://settings` | Settings home |
| `minis://settings/providers[/<instanceId>]` | Provider list/detail |
| `minis://settings/model-groups[/<groupId>]` | Model groups/detail |
| `minis://settings/usage` | Usage statistics |
| `minis://settings/skills` | Skills |
| `minis://settings/memory` | Memory |
| `minis://settings/storage` | Storage |
| `minis://settings/mount-external` | External mounted folders |
| `minis://settings/shared-folders` | Shared folders |
| `minis://settings/logs?tab=...` | Logs |
| `minis://settings/appearance` | Appearance |
| `minis://settings/background` | Background |
| `minis://settings/about` | About |
| `minis://settings/permissions` | Permissions |
| `minis://settings/rootfs` | Rootfs management |
| `minis://settings/environments` | Environment variables |

Aliases accepted by the parser include `model_groups`, `usage-stats`, `usage_stats`, `mount_external`, `mounts`, `mounted-folders`, `mounted_folders`, `shared_folders`, `mirrors`, `rootfs-management`, and `rootfs_management`.

`minis://settings/environments` accepts `create_key`, `create_value`, and `create_note`; non-empty `create_key` opens the prefilled creation flow. Unknown paths below `minis://settings/...` fall back to Settings home.

## Resource URLs

Examples:

```text
minis://workspace/report.csv
minis://attachments/photo.png
minis://offloads/result.txt
minis://browser/page.html
minis://memory/GLOBAL.md
minis://skills/example/SKILL.md
minis://shared/data.json
minis:///var/minis/workspace/report.csv
```

Relative `minis://<name>/<path>` resources map to the guest namespace under `/var/minis/<name>/<path>` unless the decoded path is already absolute.

Canonical guest roots recognized by the resolver include:

```text
/var/minis
/workspace
/memory
/skills
/shared
/home/minis
```

`/var/minis/mounts/...` is intentionally excluded from the canonical staged-resource shortcut because SAF-backed external mounts are a distinct trust/path domain.

## Percent decoding

The current resolver does not use ordinary form-decoding semantics blindly:

- `+` is protected so a literal plus in a filename is not converted to a space;
- the first percent-decoded candidate is attempted first;
- a second percent decode is attempted only if the first candidate cannot be resolved;
- malformed decoding falls back safely rather than crashing.

This preserves literal-percent filenames while supporting double-encoded links.

## Session semantics and staging

Session-aware file resolution is now real, not a global alias masquerading as session isolation.

For canonical guest paths, `ChatLinkResolver` stages the file through `WorkspaceFileClient.readToFile(sessionId, guestPath, cacheFile)` into an App cache file before preview/open. Session-scoped guest paths are not staged without a `sessionId`.

Session-scoped aliases include `/workspace`, `/var/minis/workspace`, `/var/minis/attachments`, `/var/minis/offloads`, `/var/minis/browser`, and their child paths. `UbuntuPaths.resolveSessionHostPath(...)` resolves these into the selected `<filesDir>/minis-sessions/<session_id>/...` backing.

Global aliases such as memory, skills, shared, MCP-server data, and home continue to use global App-owned backing.

## Current host backing

| Guest/Linux path | App-owned backing |
| --- | --- |
| global `/workspace` / `/var/minis/workspace` | `<filesDir>/minis/workspace` |
| session `/workspace` | `<filesDir>/minis-sessions/<session_id>/workspace` |
| session attachments | `<filesDir>/minis-sessions/<session_id>/attachments` |
| session offloads | `<filesDir>/minis-sessions/<session_id>/offloads` |
| session browser | `<filesDir>/minis-sessions/<session_id>/browser` |
| `/memory`, `/var/minis/memory` | `<filesDir>/minis-global/memory` |
| `/skills`, `/var/minis/skills` | `<filesDir>/minis-global/skills` |
| `/shared`, `/var/minis/shared` | `<filesDir>/minis-global/shared` |
| `/var/minis/mcp-servers` | `<filesDir>/minis-global/mcp-servers` |
| `/home/minis` | `<filesDir>/minis/home` |

`/data/adb/minis/rootfs` is Root-owned replaceable Ubuntu runtime state and is not a chat/resource backing. Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` locations are migration sources only.

## SAF external mounts

Paths under `/var/minis/mounts/<name>` are resolved from persisted SAF grants by the Direct Ubuntu/runtime path layer. They are not inserted into ordinary App-owned aliases, and effective read-only/writable state must follow the grant.

## Other link forms

- `file:///...` can open an existing host file when allowed by the existing path/preview flow;
- absolute guest paths can be resolved through the runtime path registry;
- supported non-HTTP Android schemes are dispatched externally;
- HTTP(S) and remaining URLs become web links.

## Implementation references

```text
src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt
src/android/app/src/main/java/com/openminis/app/deeplink/DeepLinkHandler.kt
src/android/app/src/main/java/com/openminis/app/runtime/RuntimePathRegistry.kt
src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt
src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt
```

Do not restore references to the removed `MinisKernel`/old sandbox Ubuntu path model or the former privileged broker as the resource-resolution authority.
