# Contributing

**Chinese contribution rules are authoritative:** [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md).

This repository is independently maintained. The active Linux runtime is Direct Ubuntu 24.04 chroot: Root establishes only the required rootfs/mount/chroot infrastructure, then guest shells drop to the real App UID/GID with no Linux capabilities. Active guest user data is App-owned; historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` trees are migration sources only.

Do not restore the former privileged broker, PRoot/Alpine dual runtime, or any Agent/model/MCP-controlled raw `su -c` / generic Root RPC path.

The loopback HTTP/CONNECT proxy is a separate network-compatibility component. Proxy functionality is not inherently Root-dependent; the current deployment may use privileged identity only to preserve outbound connectivity on Android/VPN/BPF configurations that restrict App-UID guest sockets. Do not expand it into shell/file/configuration/generic RPC service.

Fail closed. Keep GPL attribution; see [PROVENANCE.md](PROVENANCE.md). Project constitution: [AGENTS.md](AGENTS.md) and [docs/contracts/](docs/contracts/00-IDENTITY.md).
