# Pi-style Agent runtime upgrades + Web Remote (Android)

This tree is based on the supplied OpenMinis source and adds a focused set of Pi-inspired coding-agent improvements without replacing OpenMinis' existing persistent PRoot shell architecture.

## Implemented runtime changes

### 1. Atomic multi-edit file tool
- `file_edit` now accepts an `edits[]` array of `{old_text,new_text}` blocks.
- All blocks are matched against the same original snapshot.
- Ambiguous and overlapping matches are rejected before any write occurs.
- Exact matching is tried first; conservative fallback tolerates Unicode smart punctuation, special spaces and trailing whitespace.
- UTF-8 BOM and CRLF/LF line-ending style are preserved.
- Tool output includes a bounded unified-style diff.
- Legacy `old_string/new_string/replace_all` calls remain accepted at runtime.

### 2. Per-file mutation queue
- `file_write` and `file_edit` serialize mutations targeting the same canonical host path with a fair lock.
- Mutations to unrelated files still run concurrently.
- Web editor saves also use an SHA-256 revision guard so a stale browser tab cannot silently overwrite a newer `file_write/file_edit` change.

### 3. Compaction file activity retention
Compaction appends a deterministic machine-readable inventory to its summary:

```text
<file-activity>
read:
- /var/minis/workspace/...
modified:
- /var/minis/workspace/...
</file-activity>
```

The inventory is merged across subsequent compactions, so important read/modified file paths are not dependent solely on the language model's summary quality.

### 4. Token-budget recent-context retention
- Replaces the fixed "keep the last 3 user turns" look-back with a target of roughly 20,000 recent tokens.
- Keeps complete user rounds and still applies the existing tool-pair repair/pruning rules.
- Token estimation avoids double-counting structured text/image parts and includes reasoning, images and a conservative audio estimate.
- Large tool results that will be pruned are excluded from the look-back budget estimate, so a single verbose build log does not consume the entire recent-context allowance.

### 5. Lossless shell-output offload
- `ExecutionCoordinator.CommandResult` retains the complete sanitized command output separately from the legacy display-bounded output.
- Agent shell results are bounded to roughly 2,000 lines / 50 KiB in context.
- When truncated, the full output is written under `/var/minis/offloads/tools/...log`; the tool result tells the agent where to `file_read` it.
- UTF-8 tail truncation walks by Unicode code point, so Chinese text / emoji are not split into invalid byte sequences.

## Web Remote

The Android app now contains an opt-in foreground Web Remote service. It reuses OpenMinis' existing `ChatViewModelStore`, `HeadlessChatRunner`, session database, PRoot filesystem mapping and `ExecutionCoordinator`; it is not a second Agent implementation.

Enable it from:

`Settings -> Agent Runtime -> Web Remote`

The screen shows the LAN URL, listening port and a per-install random access token.

Current Web UI supports:
- session list / new chat;
- sending prompts into the same Agent session used by the native app;
- running-state polling and cancellation;
- message history;
- session filesystem browsing;
- UTF-8 text-file open/edit/save (2 MiB editor limit);
- optimistic SHA-256 stale-write protection;
- direct access to that session's existing Persistent Shell;
- full shell-output offload when the browser result is truncated;
- responsive sidebar and tools drawer for narrower screens.

### Remote API
All `/api/*` endpoints require either:

```http
Authorization: Bearer <token>
```

or `X-Minis-Token`. The token is deliberately not accepted in query strings.

Implemented endpoints:
- `GET /api/status`
- `GET /api/sessions`
- `GET /api/messages?sessionId=...`
- `GET /api/session/status?sessionId=...`
- `POST /api/prompt`
- `POST /api/cancel`
- `GET /api/files?sessionId=...&path=...`
- `GET /api/file?sessionId=...&path=...`
- `PUT|POST /api/file`
- `POST /api/edit`
- `POST /api/shell`

The server is disabled by default, has a 4 MiB request-body limit, a 30-second socket read timeout, a 32-connection concurrency ceiling, same-origin static assets, no permissive CORS policy, CSP/security headers and constant-time token comparison.

## Public-domain deployment boundary

The phone-side server intentionally speaks HTTP. Do **not** forward that raw HTTP port directly to the public Internet because the bearer token would then travel without transport encryption.

For public/domain access, terminate HTTPS at a trusted reverse proxy or tunnel and forward the authenticated connection to the phone's LAN port. The DNS/domain/TLS layer is deliberately outside the Android app so OpenMinis does not need to own certificates or provider-specific tunnel credentials.

A minimal Caddy edge, when the Caddy host can reach the phone over LAN/VPN, is:

```caddyfile
minis.example.com {
    reverse_proxy 192.168.1.50:8765
}
```

For a tunnel, point the tunnel origin at `http://<phone-lan-ip>:8765` and publish only the tunnel's HTTPS hostname. Do not configure both raw router port-forwarding and a tunnel unless you intentionally want two attack surfaces.

## Validation performed in this environment

Passed:
- standalone `kotlinc` executable tests for multi-edit, overlap/ambiguity rejection, Unicode fuzzy matching, BOM/CRLF preservation, per-file serialization, SHA-256 revision calculation and UTF-8-safe shell truncation;
- `node --check` for `assets/remote/app.js`;
- XML parsing for AndroidManifest + default/Chinese string resources;
- Kotlin parser pass (no syntax/illegal-escape diagnostics) on the new Remote server/service/prefs/settings files and modified file tools.

Not completed here:
- full Android Gradle compilation / APK build. The supplied wrapper tries to download Gradle 8.11.1, but this execution container has no outbound access to `services.gradle.org`, no cached Gradle distribution and no Android SDK. The Gradle failure was environmental (`UnknownHostException`), not a compiler result. A real Android development environment should still run at minimum:

```bash
cd src/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Do not treat this package as APK-validated until those two commands pass on a machine with the Android toolchain.

## Deliberately not included
- Pi-style branch/tree session navigation and branch summarization. This is a larger UX/session-model change and was kept out of this patch so the high-value runtime improvements remain isolated.
- In-app TLS certificate management. HTTPS belongs at the public reverse-proxy/tunnel edge.
