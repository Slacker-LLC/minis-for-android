# Changelog

This changelog tracks **Minis for Android** as an independently maintained Android project built on OpenMinis.

Older experimental release notes contained references to removed Web Remote, Cloudflare Tunnel, PRoot, Alpine, and repository-hosted APK releases. Those notes remain available through Git history but are not part of the current product contract.

## Unreleased

### Project identity and documentation

- English is now the primary documentation language.
- The repository is described as an independent Android project built on upstream OpenMinis rather than as an unofficial branch or release mirror.
- Current documentation is aligned to the `minisd` + Ubuntu 24.04 chroot architecture.
- Upstream provenance and selective synchronization policy are documented separately.
- Contribution and issue templates are aligned to the current MCP/root/tool runtime instead of removed Web Remote/PRoot components.

## Current architecture baseline — 2026-08

### Execution runtime

- Replaced the historical Android Alpine + PRoot execution path with a rooted-device architecture based on `minisd`, mount namespaces, chroot, and Ubuntu 24.04 userspace.
- Kept the running Android kernel; the Linux guest does not boot a separate kernel.
- Guest execution uses the app UID instead of granting the guest unrestricted root identity.
- Workspace, memory, skills, shared files, attachments, browser data, and offloads are mounted through explicit host/guest mappings.

### Root broker

- Added the Rust `minisd` root broker with Unix-socket IPC, peer checks, structured methods, compile-time capability ceilings, and runtime policy gates.
- Added framed IPC, concurrent client handling, bounded command output, process-tree termination, and fail-closed rootfs verification.
- Removed legacy privileged supervisor surfaces that could widen the root process-launch boundary.

### Agent and tools

- Expanded the Android agent runtime with goals, todos, jobs, subagents, structured user questions, checkpoints, approval seams, timeout policy, output spill/pruning, and Android-native tools.
- Kept one Android-native source of truth for sessions, providers, tool permissions, and persistence.

### MCP

- Added a local MCP server with bearer authentication and caller-aware tool permissions.
- Added external MCP provider integration through the existing tool registry/runtime.
- Removed the old Web Remote / Cloudflare Tunnel control surface.

### Build and release engineering

- Repository is source-first; APK/AAB build artifacts are not committed to Git.
- Release signing is fail-closed and cannot fall back to the Android debug key.
- CI runs Rust format/Clippy/tests/build, rootfs verification, Android unit tests, Debug/Release lint, Debug/Release packaging, and release APK verification.
- Added a checked-in lint baseline so existing debt can be reduced without allowing new findings to pass silently.

### Provider/runtime hardening

- Aligned public-source provider tests with actual streaming/runtime contracts.
- Kept optional private provider customization fail-closed when a private value is required.

## Upstream

For upstream OpenMinis provenance and synchronization rules, see [UPSTREAM.md](UPSTREAM.md).
