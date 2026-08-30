# Privileged Android access modes

Issue #45 reduces Agent-triggered Android Root execution to two user-visible modes while keeping `minisd` as the single execution and audit boundary.

## Authority model

```text
Android Agent tool
  -> PrivilegedCommandRunner
  -> user-owned PrivilegedAccessModeStore
  -> MinisdClient
  -> minisd root.exec policy
  -> Android Root executable
```

`PrivilegedCommandRunner` no longer executes Agent argv through `su -c`. The App may still use `su` to bootstrap or reach the fixed `minisd --call` helper when the App socket is unavailable; that transport path does not contain Agent-controlled argv and is not a second Root command executor.

## Standard mode

Standard mode is the default. Missing, invalid, or old preference values resolve to `standard`.

`minisd` first applies the existing `root.exec` built-in tool allowlist and argument policy. Requests that pass the standard policy execute without a confirmation. Requests outside that policy return `CONFIRM_REQUIRED` with a short-lived `confirm_id`.

The Android policy seam shows the complete argv to the user. An approved request is retried once with the issued `confirm_id`.

### Confirmation binding

A confirmation is bound to the canonical JSON value containing:

- the method name;
- every authority-bearing request parameter, including tool, complete argv, timeout and access mode.

`execution_id` is deliberately excluded because it is cancellation/transport metadata and does not change requested Root authority.

A confirmation is consumed before validation. Therefore all of these permanently invalidate the ticket:

- successful use;
- reuse;
- expiry;
- changed argv or any other bound parameter.

A changed request must obtain a new confirmation.

## Full Access mode

Full Access is stored only by the App settings UI. Agent tool schemas do not expose a mode-switch operation.

When the user explicitly enables Full Access, `PrivilegedCommandRunner` marks Root requests as `full`. `minisd` then skips the standard tool allowlist for that request, but still enforces structured argv parsing, executable resolution, command timeout, cancellation, bounded stdout/stderr capture and the normal broker audit boundary. This permits operations such as `sh -c`, `cmd`, `debuggerd`, `chroot`, `unshare` and specialized `mount` invocations when those executables exist on the device.

The settings screen displays a persistent warning while Full Access is selected:

> ⚠ 完全访问模式已开启，Agent 当前拥有设备完整 Root 控制权

Returning to Standard mode is immediate and does not require a confirmation.

## Root probe

The explicit Root capability probe still requires user approval, but the probe itself is now executed by `minisd root.probe`. `RootCommandRunner` is retained only as a passive `su` detector and last-probe cache; it has no command execution method.

## Regression contract

Automated tests cover:

- Standard mode as the fail-closed preference default;
- standard allowlisted execution without confirmation;
- non-allowlisted Standard requests returning a confirmation;
- confirmation binding to the complete request;
- mismatch invalidation and replay rejection;
- Full Access bypassing the standard allowlist while remaining inside `minisd`;
- Android wire requests carrying explicit access mode and confirmation IDs.

## Concurrent PR boundary

This work was branched from `master` at `6cd51ad69c760e929605f6557737b6890898f475`.

At implementation time:

- PR #63 (`fix/issue-50-android-persistent-paths`) was an open Draft based on that same `master`. Its persistent-path work overlaps `MinisdProtocol.kt` but does not define Issue #45 authority semantics.
- PR #66 (`feat/issue-51-runtime-distribution`) was an open Draft stacked on PR #63 and included a broad package/runtime distribution migration that touches the logical Android/minisd files changed here.

This branch does not modify, rebase, force-push, or rewrite either PR branch. If #63 or #66 lands first, Issue #45 behavior must be reconciled against their then-current GitHub heads rather than overwriting their persistent-layout or runtime-distribution changes.
