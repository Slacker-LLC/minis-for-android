# Runtime History Archive

> Historical, non-authoritative material. Do not use this file as a current runtime contract.

The Android Linux runtime evolved through several stages:

1. Early development inherited or evaluated OpenMinis-era Alpine/PRoot userspace execution.
2. The first rooted-device migration introduced Ubuntu chroot plus a privileged Rust broker, mount namespaces, bind mounts, and a Root-owned user-data layout.
3. The Direct Ubuntu refactor removed that privileged broker and moved active guest user data to App-owned storage. The Android App now owns shell/session lifecycle and uses internal Root infrastructure only to establish the chroot/mount environment before dropping the guest to the real App UID/GID with no Linux capabilities.

The current implementation also contains a standalone loopback HTTP/CONNECT network compatibility helper. That helper is separate from the Root/chroot architecture: proxying itself does not require Root; the current Android deployment may use privileged identity only to work around UID/VPN/BPF networking restrictions.

Older repository history, issue discussions, patch snapshots, dependency notices, and package names may therefore contain OpenMinis, Alpine, PRoot, privileged-broker, old Root-owned storage, or other superseded terminology.

Current behavior is defined by active source/tests and documents such as `docs/contracts/01-ARCHITECTURE.md`, `docs/contracts/03-STORAGE-CONTRACT.md`, `docs/contracts/04-SECURITY-CONTRACT.md`, and `docs/EXECUTION-ENVIRONMENT.md`.
