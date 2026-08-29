# Android `minis://` URL Scheme

**Status:** Current Android implementation

This document describes the `minis://` behavior implemented by Minis for Android. The source of truth is the Android routing and path-resolution code, primarily `ChatLinkResolver`, `DeepLinkHandler`, and `UbuntuPaths`.

The Android scheme has two roles:

1. app-navigation deep links recognized by `DeepLinkHandler`;
2. sandbox/resource links resolved by `ChatLinkResolver` when the URL is not a recognized navigation target.

It is **not** the old iOS/iSH session-scoped resource protocol. In particular, the current Android path resolver does not provide per-chat file isolation for these URLs.

## Routing order

When a chat Markdown link is tapped, `ChatLinkResolver.resolve(...)` applies this order:

1. If the scheme is `minis`, parse it with `DeepLinkHandler`.
   - A recognized action becomes an app `DeepLink`.
   - An unrecognized action falls through to sandbox-file resolution.
2. Resolve supported sandbox/file paths.
   - An existing non-directory file becomes `SandboxFile`.
3. Send supported non-HTTP external schemes such as `intent:`, `mailto:`, `tel:`, `geo:`, and `market:` to Android as `ExternalApp` links.
4. Everything else becomes a normal `Web` link.

Navigation therefore has priority over resource lookup. A URL that `DeepLinkHandler` recognizes is never reinterpreted as a sandbox file.

## Navigation URLs

The following `minis://` routes are currently recognized by `DeepLinkHandler`.

| URL | Android action |
| --- | --- |
| `minis://share` | Open share flow |
| `minis://views/alarm` | Open alarm list |
| `minis://open_terminal?init_command=...` | Open terminal; optional initial command |
| `minis://action/new_chat` | Start a new chat |
| `minis://action/voice_chat` | Start a new chat and trigger voice input |
| `minis://action/camera_chat` | Start a new chat and trigger camera attachment |
| `minis://session/<sessionId>` | Open a chat session |
| `minis://session/<sessionId>/<resource-path>?title=...` | Open that session's HTML preview |
| `minis://settings` | Settings home |
| `minis://settings/providers` | Provider list |
| `minis://settings/providers/<instanceId>` | Provider detail |
| `minis://settings/model-groups` | Model Groups |
| `minis://settings/model-groups/<groupId>` | Model Group detail |
| `minis://settings/usage` | Usage statistics |
| `minis://settings/skills` | Skills |
| `minis://settings/memory` | Memory |
| `minis://settings/storage` | Storage |
| `minis://settings/mount-external` | External mounted folders |
| `minis://settings/shared-folders` | Shared folders |
| `minis://settings/logs` | Logs; optional `?tab=...` is forwarded |
| `minis://settings/appearance` | Appearance |
| `minis://settings/background` | Background |
| `minis://settings/about` | About |
| `minis://settings/permissions` | Permissions |
| `minis://settings/rootfs` | Rootfs management |
| `minis://settings/environments` | Environment variables |

Accepted aliases in the current parser include:

- `model_groups` for `model-groups`;
- `usage-stats` and `usage_stats` for `usage`;
- `mount_external`, `mounts`, `mounted-folders`, and `mounted_folders` for `mount-external`;
- `shared_folders` for `shared-folders`;
- `mirrors`, `rootfs-management`, and `rootfs_management` for `rootfs`.

`minis://settings/environments` also accepts `create_key`, `create_value`, and `create_note` query parameters. A non-empty `create_key` opens the prefilled create flow; missing value/note parameters become empty strings.

An important parser rule is that an unknown path below `minis://settings/...` falls back to Settings home. It does **not** become a resource URL.

## Resource URLs

A `minis://` URL that is not consumed as navigation is eligible for sandbox-file resolution.

For a normal relative resource URL:

```text
minis://<name>/<path>
        ↓
/var/minis/<name>/<path>
```

Examples:

```text
minis://workspace/report.csv
minis://attachments/photo.png
minis://offloads/result.txt
minis://browser/page.html
minis://memory/GLOBAL.md
minis://skills/example/SKILL.md
minis://shared/data.json
```

An absolute form is also accepted:

```text
minis:///var/minis/workspace/report.csv
```

For resource resolution, `ChatLinkResolver`:

- removes the `minis://` prefix;
- removes the query component beginning at `?`;
- URL-decodes the remaining path as UTF-8;
- preserves `#` as a literal filename character;
- prepends `/var/minis/` unless the decoded path is already absolute.

The result becomes a `SandboxFile` only when the resolved host path exists and is not a directory.

## Android host-path mapping

After app initialization, `UbuntuPaths` maps the stable guest paths to app-private storage:

| Guest/Linux path | Android host path |
| --- | --- |
| `/workspace` | `<filesDir>/minis/workspace` |
| `/var/minis/workspace` | `<filesDir>/minis/workspace` |
| `/var/minis/attachments` | `<filesDir>/minis/workspace/attachments` |
| `/var/minis/offloads` | `<filesDir>/minis/workspace/offloads` |
| `/var/minis/browser` | `<filesDir>/minis/workspace/browser` |
| `/memory`, `/var/minis/memory` | `<filesDir>/minis-global/memory` |
| `/skills`, `/var/minis/skills` | `<filesDir>/minis-global/skills` |
| `/shared`, `/var/minis/shared` | `<filesDir>/minis-global/shared` |
| `/home/minis` | `<filesDir>/minis-global/home` |

`/data/adb/minis` is reserved for root-owned Ubuntu runtime state such as the rootfs and `minisd`; it is not the app workspace. The root broker joins the App mount namespace before binding these app-private paths, so its filesystem view matches the Android resolver instead of the global `tmpfs_data` overlay.

Additional paths can be supplied by the runtime bind-mount registry, including user-authorized external folders.

`UbuntuPaths` rejects empty paths, NUL-containing paths, and any path containing a `..` segment. Resolved children are canonicalized and must remain inside the selected host mount.

## Session semantics

`ChatLinkResolver` accepts an optional `sessionId` and prefers `MinisKernel.resolveSessionHostPath(...)` when a session and `Context` are available. Session-scoped workspace, attachment, offload, and browser paths resolve under `<filesDir>/minis-sessions/<sessionId>`. Memory, skills, shared files, the Linux user home, and user-authorized mounts remain App-global.

The `minis://session/<sessionId>/...` navigation form is different: its session id selects the chat/preview destination. It does not change the resource resolver into a per-session filesystem.

## Other chat-link forms

`ChatLinkResolver` also understands these non-`minis://` forms:

- `file:///...` — decoded to a host `File` and opened as a sandbox file when it exists and is not a directory;
- absolute scheme-less Linux paths such as `/var/minis/workspace/file.txt` — resolved through the current Ubuntu path/mount registry;
- supported Android external schemes — dispatched to the appropriate app;
- HTTP(S) and other remaining URLs — handled as web links.

## Implementation references

- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt`
- `src/android/app/src/main/java/com/openminis/app/deeplink/DeepLinkHandler.kt`
- `src/android/app/src/main/java/com/openminis/app/sandbox/MinisKernel.kt`
- `src/android/app/src/main/java/com/openminis/app/sandbox/ubuntu/UbuntuPaths.kt`

When behavior and this document differ, update this document from the Android implementation rather than copying the former iOS/iSH specification.
