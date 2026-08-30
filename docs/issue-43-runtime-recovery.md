# Issue #43 runtime recovery contract

Issue #43 is split across three independently owned changes so concurrent runtime work does not overwrite another branch.

## This branch: pre-exec failure semantics

`ubuntu.exec` and `ubuntu.adminExec` must distinguish a helper failure that occurs before guest `execve(2)` from a real guest process that exits with the same numeric status.

The broker generates a fresh 128-bit token for each helper execution and exposes it only in the helper process environment. The helper emits an internal marker containing that token only when it returns before guest `execve`. Guest execution uses an explicit replacement environment, so the internal token is not inherited by the guest process.

The broker promotes an authenticated marker to a structured RPC error:

- helper code 4 + failed keeper `setns` -> `KEEPER_NAMESPACE_LOST`
- helper code 4 + per-session namespace/mount setup -> `RUNTIME_LAYOUT_MISMATCH`
- helper code 5 -> `CHROOT_UNAVAILABLE`
- helper code 6 -> `PRIVILEGE_SETUP_FAILED`
- helper code 7 -> `EXEC_UNAVAILABLE`

A numeric guest exit 4/5/6/7 without the authenticated marker remains an ordinary `exit_code`. This is required to avoid retrying a command that already reached guest `execve` and may have produced side effects.

The Android recovery state machine already retries `KEEPER_NAMESPACE_LOST` at most once by stopping/rebuilding the keeper and replaying only that proven pre-exec attempt. Other structured failures are surfaced without automatic replay.

## Concurrent branch boundaries

PR #63 owns Issue #50 Android persistent-path migration and the canonical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}` contract. This branch does not change those Android path files.

PR #66 owns Issue #51 runtime distribution, versioned/atomic rootfs installation and rollback. It also changes `src/native/minisd/src/main.rs` and `src/native/minisd/src/ubuntu.rs`; this branch touches only the helper pre-exec marker/error-classification hunks in those files and intentionally does not implement or replace #66 rootfs selection/distribution logic.

When #66 is merged, retain both behaviors: its active-rootfs selection plus this branch's authenticated pre-exec marker and structured error promotion.

## Verification

Host CI must run the Rust test suite. The regression tests cover marker authentication, exact error-code mapping, distinction between keeper namespace loss and session-layout failure, and generation of a non-static 128-bit token.

Device-only scenarios from Issue #43 (killed keeper, corrupted/missing rootfs, stale UID/install state) remain device acceptance work; this branch does not run `adb` and does not claim device evidence.
