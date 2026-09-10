# 贡献指南

权威贡献规则是本文件。英文 [CONTRIBUTING.md](CONTRIBUTING.md) 只是摘要。

先读 [AGENTS.md](AGENTS.md) 与 [docs/contracts/](docs/contracts/00-IDENTITY.md)。导入外部代码时读 [PROVENANCE.md](PROVENANCE.md)，保留许可证与著作权声明。

## 报缺陷

尽量包含 Android 版本、`versionName`/`versionCode`、机型/ROM、Root 方案、复现步骤、期望与实际、相关 Provider，以及脱敏日志。Root/runtime 问题再注明 SELinux、VPN/TUN、DNS/Fake-IP 与网络 helper 状态。不要提交 API Key、OAuth/MCP token、签名材料或私人数据。

## Pull Request

默认基于当前 `main`；stacked PR 必须明确 base/head 与依赖。一次 PR 只解决一个明确问题或一条合同边界。

必须遵守：

1. App 是数据库、工具权限、审批、session 和 guest 用户数据 backing 的权威；现役用户数据从 `Context.filesDir` 派生。
2. `/data/adb/minis/rootfs` 是 Root-owned、可替换 Ubuntu rootfs；旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为一次性迁移源。
3. `applicationId = llc.slacker.minis`，namespace = `com.openminis.app`；不要把全库 namespace 重命名混入无关 PR。
4. Guest UID/GID 使用当前安装实际 App identity；进入 chroot 后清空 supplementary groups/capabilities，禁止写死 `10000`。
5. 新工具走现有 Tool Registry 与权限/结果模型。
6. Root 只用于 App-owned Direct Ubuntu 基础设施；禁止把模型、Agent、MCP 或 Provider 输出直接送入 `DirectRootRunner` / `su -c`。
7. 不恢复旧 broker、PRoot/Alpine 或双栈 runtime。
8. Session 执行保持对应 session workspace 语义，不能用全局 workspace 绕过隔离。
9. 网络代理和 Root/chroot 分开设计。代理协议本身不需要 Root；当前 helper 仅可为 Android 出站 UID/VPN/BPF 兼容以特权身份启动，并且只能是 loopback HTTP/CONNECT 单用途服务。
10. 副作用操作保留审批、checkpoint、持久化与恢复语义；能力靠探测，不靠名称，安全路径 fail-closed。
11. 行为变更同步更新相关中文合同/当前缺口与测试；保留 GPL 与第三方义务。

## Runtime

```text
Android App → ExecutionCoordinator / App-owned shell
→ UbuntuKernel / DirectRootRunner
→ su → setsid → unshare -m → bind mounts → chroot
→ setpriv(真实 App UID/GID, clear groups, drop capabilities)
→ Ubuntu 24.04 bash
```

普通 guest/Agent 命令不是 Root 命令。MCP 必须进入同一工具注册与权限边界。普通 Android API、Accessibility、Shizuku 与 Root 是不同能力。

## 文档与历史记录

判断当前实现时，以最终目标分支源码和测试为准。已合并 PR、历史 Issue、旧 runtime 文档与阶段计划只能解释历史。历史文档可以保留旧名称，但必须明确其非现役状态。

## 验证

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

Android / Rust / rootfs / runtime boundary 命令见 [05-ENGINEERING.md](docs/contracts/05-ENGINEERING.md) 与 [BUILDING.zh-CN.md](BUILDING.zh-CN.md)。
