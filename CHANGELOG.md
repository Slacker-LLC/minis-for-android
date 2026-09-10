# Changelog

This changelog tracks **Minis for Android** as an independently maintained Android project. Legal source lineage is documented separately in [PROVENANCE.md](PROVENANCE.md).

## Unreleased

### Direct Ubuntu runtime finalization — 2026-09-10

- Finalized the production Linux path as Android App-owned orchestration + Ubuntu 24.04 direct chroot.
- Removed the obsolete privileged broker source/build/runtime path, socket protocol, Android client package, payload fields, and packaged broker binary.
- Kept Root execution internal to trusted runtime infrastructure; generic Agent/model/MCP-controlled Root shell/RPC is denied.
- Guest commands run as the real Android App UID/GID after `setpriv` clears supplementary groups and Linux capabilities.
- Moved active guest user data to App-owned backing derived from `Context.filesDir`; `/data/adb/minis/rootfs` remains Root-owned replaceable runtime state.
- Added one-time migration from historical Root-owned user-data trees into App-owned storage.
- Added the standalone loopback HTTP/CONNECT network compatibility helper and deterministic HTTP absolute-form / CONNECT forwarding tests.
- Clarified that the network proxy is independent from the Root/chroot architecture: proxying itself does not require Root; privileged identity is only a deployment choice for Android UID/VPN/BPF egress compatibility.
- Strengthened runtime/package/build cleanup guards so removed broker identities cannot return to production source/build/package paths.
- Kept Release signing fail-closed and verified Debug/Release packaging, native 16 KiB alignment, rootfs payload boundaries, rclone, lint, unit tests, and bundle-generated APKs in canonical CI.
- PR #235 merged the Direct Ubuntu migration into `main` on 2026-09-10. This branch remains useful as the migration/reference branch and may contain documentation follow-up commits after that merge.

### Security and tool boundary

- `root.shell` is denied for both local Agent and MCP callers; Direct Root execution remains internal infrastructure only.
- Android capability/tool descriptions now report Direct Ubuntu rather than the removed runtime architecture.
- Production residue guards distinguish real obsolete runtime identities from unrelated identifiers such as `MinisDocumentsProvider`.

## Historical architecture stages

Earlier project history includes two superseded Linux-runtime stages:

1. Alpine/PRoot userspace inherited or evaluated during early development.
2. A privileged Rust broker + Ubuntu chroot stage used during the first Root-runtime migration.

Both are historical only. Old Issue/PR documents and Git history may retain those names to explain past decisions; they are not current runtime contracts.

## Current architecture summary

```text
Android App
  → ExecutionCoordinator / App-owned shell lifecycle
  → UbuntuKernel / DirectRootRunner
  → su → setsid → unshare -m → bind mounts → chroot
  → setpriv(real App UID/GID, clear groups/caps)
  → Ubuntu 24.04 bash
```

Network compatibility is separate:

```text
Ubuntu guest (App UID)
  → optional/current loopback HTTP/CONNECT compatibility path
  → Android outbound network
```

The current implementation may run that helper with privileged identity where required by Android networking policy; this does not make the proxy protocol itself part of Root/chroot.

## Upstream / source lineage

For legal source lineage, see [PROVENANCE.md](PROVENANCE.md). Historical upstream relationships do not define current product identity or a sync policy.
