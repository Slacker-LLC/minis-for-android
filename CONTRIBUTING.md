# Contributing

**Chinese contribution rules are authoritative:** [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md)

This repository is independently maintained. The active Linux runtime is App-owned direct Ubuntu 24.04: Root establishes rootfs/mount/chroot infrastructure, then guest shells drop to the real App UID/GID with no Linux capabilities. Active guest user data is App-owned; historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` trees are legacy migration sources only.

Do not restore the former privileged broker, PRoot/Alpine dual runtime, or any model/Agent/MCP-controlled raw `su -c` / generic Root RPC path. The standalone Root network proxy must remain loopback-only and single-purpose. Fail closed. Keep GPL attribution; see [PROVENANCE.md](PROVENANCE.md).

Project constitution: [AGENTS.md](AGENTS.md) and [docs/contracts/](docs/contracts/00-IDENTITY.md).
