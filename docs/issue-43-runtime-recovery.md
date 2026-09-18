# Issue #43 runtime recovery contract — historical implementation record

> **Status (2026-09-10): historical.** This document describes the former privileged-broker runtime stage. It is retained for recovery-semantics history only. The active production runtime is Direct Ubuntu 24.04; current behavior is defined by `docs/EXECUTION-ENVIRONMENT.md` and `docs/contracts/01-ARCHITECTURE.md`.

## Historical scope

Issue #43 split recovery work across several concurrent changes. At that time, the execution path used a privileged broker/helper and needed to distinguish a helper failure that occurred before guest `execve(2)` from a real guest process that exited with the same numeric status.

The historical broker generated a fresh per-execution token and exposed it only to the helper environment. The helper emitted an internal marker containing that token only when it returned before guest `execve`. Guest execution used an explicit replacement environment so the token was not inherited by the guest process.

Historical structured mappings included:

- helper code 4 + failed keeper `setns` → `KEEPER_NAMESPACE_LOST`;
- helper code 4 + per-session namespace/mount setup → `RUNTIME_LAYOUT_MISMATCH`;
- helper code 5 → `CHROOT_UNAVAILABLE`;
- helper code 6 → `PRIVILEGE_SETUP_FAILED`;
- helper code 7 → `EXEC_UNAVAILABLE`.

A numeric guest exit 4/5/6/7 without the authenticated marker remained an ordinary guest `exit_code`, avoiding unsafe replay after a command had already reached `execve`.

## What remains relevant today

The old broker/keeper protocol is gone, but the safety principle remains current:

- distinguish proven pre-execution failure from a command that may already have produced side effects;
- fail closed when Direct Ubuntu readiness cannot be established;
- do not blindly replay operations with unknown outcome;
- report Root/rootfs/mount/chroot/privilege-drop failures distinctly enough for diagnosis.

Current Direct Ubuntu code must implement those principles through its own readiness/execution state rather than reintroducing the old marker/socket/broker protocol.

## Historical concurrent boundaries

Older references to PR #63/#66/#75, Root-owned canonical user data, keeper namespaces, or native broker source files describe the repository state at that time. They are not current implementation instructions.

Current storage is App-owned except for replaceable Root-owned rootfs state; see `docs/contracts/03-STORAGE-CONTRACT.md` and `docs/contracts/07-OWNERSHIP-MIGRATION.md`.

## Verification boundary

Repository CI can cover failure classification, retry policy, unit behavior, payload boundaries, and Direct Ubuntu build contracts. Killed Root processes, corrupted/missing rootfs on a device, SELinux behavior, mount failures, stale UID/install state, VPN/BPF behavior, and OEM lifecycle still require device acceptance tests.
