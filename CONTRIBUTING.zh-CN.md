# 贡献指南

权威贡献规则是本文件。英文 [CONTRIBUTING.md](CONTRIBUTING.md) 只是摘要指针。

先读 [AGENTS.md](AGENTS.md) 与 [docs/contracts/](docs/contracts/00-IDENTITY.md)。导入外部代码时读 [PROVENANCE.md](PROVENANCE.md)，保留许可证与著作权声明。

## 报缺陷

尽量包含：Android 版本、`versionName`/`versionCode`、机型与 ROM、Root 方案、复现步骤、期望与实际、相关 Provider、脱敏日志。不要贴 API Key、OAuth、MCP token、签名材料或无关私人数据。

## Pull Request

默认基于当前 `main`；stacked PR 必须明确 base/head 与依赖。一次 PR 只解决一个明确问题或一条合同边界。

必须遵守：

1. App 是应用状态、数据库、工具权限、审批、session 和 guest 用户数据 backing 的权威；现役用户数据从 `Context.filesDir` 派生。
2. `/data/adb/minis/rootfs` 是 Root-owned、可替换 Ubuntu rootfs；旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为一次性迁移源，禁止重新作为现役真源。
3. `applicationId = llc.slacker.minis`，namespace = `com.openminis.app`；不要把全库 namespace 重命名混入无关 PR。
4. Guest UID/GID 动态使用设备实际 App identity，进入 chroot 后清空 supplementary groups/capabilities；禁止写死 `10000`。
5. 新工具走现有 Tool Registry 与权限/结果模型。
6. Root 只用于 App-owned runtime 基础设施。禁止新增把模型、Agent、MCP 或 Provider 输出直接送进 `DirectRootRunner` / `su -c` 的任意 Root command/RPC 通道。
7. Runtime 是 Root + App-owned direct Ubuntu 24.04 chroot；不要恢复旧 broker、PRoot/Alpine 或双栈。
8. Session 执行保持对应 session workspace 语义，不能用全局 workspace 绕过隔离。
9. Root 网络兼容只允许单用途 loopback proxy；不得扩展成 shell、文件或通用 RPC 服务。
10. 副作用操作保留审批、checkpoint、持久化与恢复语义；能力靠探测，不靠名称，安全路径 fail-closed。
11. 行为变更同步更新相关中文合同/当前缺口与测试；保留 GPL 与第三方义务。

## Runtime

执行链：

```text
Android App → ExecutionCoordinator / App-owned shell
→ UbuntuKernel / DirectRootRunner
→ su → setsid → unshare -m → bind mounts → chroot
→ setpriv(真实 App UID/GID, clear groups, drop capabilities)
→ Ubuntu 24.04 bash
```

Root 负责 rootfs、namespace、bind/chroot、受控 legacy migration 和固定 loopback egress helper；普通 guest/Agent 命令不是 Root 命令。

MCP 必须进入同一套工具注册与权限。普通 Android API、无障碍、Shizuku 和 Root 是不同能力。

## 当前事实与历史记录

判断当前实现时，以最终目标分支源码和测试为准；已合并 PR、历史 Issue、旧 broker 文档和阶段计划只能解释历史。带 SHA/日期的旧 gap 基线不能自动覆盖新架构。

## 验证

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

Android / Rust / rootfs / runtime boundary 命令见 [05-ENGINEERING.md](docs/contracts/05-ENGINEERING.md) 与 [BUILDING.zh-CN.md](BUILDING.zh-CN.md)。
