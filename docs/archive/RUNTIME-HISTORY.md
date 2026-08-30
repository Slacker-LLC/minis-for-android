# Runtime History Archive

> Historical, non-authoritative material. Do not use this file as a current runtime contract.

Earlier stages of the project inherited or evaluated the OpenMinis Android execution model, including Alpine Linux userspace and PRoot-based sandboxing. Some older repository history, issue discussions, dependency notices, and package names may therefore contain OpenMinis, OpenMinisPet, Alpine, or PRoot terminology.

The current project later moved to its own rooted-device runtime based on a Rust `minisd` broker, Linux mount namespaces, explicit bind mounts, `chroot`, and Ubuntu userspace.

This archive exists only to explain old references and preserve technical history. Current behavior is defined by source code, tests, and active documentation such as `docs/EXECUTION-ENVIRONMENT.md`.
