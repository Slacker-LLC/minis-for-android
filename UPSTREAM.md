# Upstream Relationship

Minis for Android is an independent Android-focused project built on the open-source [OpenMinis](https://github.com/OpenMinis/OpenMinis) codebase.

## Upstream

- Repository: `OpenMinis/OpenMinis`
- License: GPL-3.0
- Project website: https://openminis.app

OpenMinis remains the upstream source for a substantial part of the native Android application, provider/model behavior, shared agent concepts, browser tooling, voice features, and cross-platform product work.

## This project

This repository is maintained separately and is not the official OpenMinis Android distribution. It intentionally diverges in areas where the project targets a rooted Android execution model.

Major local directions include:

- Ubuntu 24.04 chroot backed by the running Android kernel;
- Rust `minisd` root broker with structured RPC and policy gates;
- local MCP server and external MCP provider integration;
- expanded Android-native tool runtime;
- jobs, subagents, goals, todos, approvals, checkpoints, and recovery seams;
- independent CI, release-signing, lint, and rootfs-verification policy.

## Synchronization policy

Upstream changes are evaluated selectively rather than merged blindly.

When porting an upstream change:

1. preserve upstream copyright and license notices;
2. identify whether the change assumes the upstream PRoot/Alpine execution model;
3. adapt execution/runtime code to the local `minisd` + Ubuntu architecture instead of reintroducing a parallel PRoot backend;
4. preserve the Android app as the single source of truth for sessions, providers, tools, and persistence;
5. route new tools through the existing permission/runtime layer;
6. add or update tests for the local architecture;
7. update English primary documentation when behavior changes.

## Naming and attribution

Use **Minis for Android** when referring to this repository and its independently maintained Android project.

Use **OpenMinis** when referring to the upstream project, upstream code, upstream releases, or upstream design decisions.

Do not describe this repository as an upstream release mirror. It is a derivative GPL project with its own architecture and development history.

## License

Because this repository contains code derived from OpenMinis, the project continues to be distributed under GPL-3.0. See [LICENSE](LICENSE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
