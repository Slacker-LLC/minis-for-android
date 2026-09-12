# 文档索引

本轮整理基于 2026-09-12 合并后的 `main`（源码基线 `422cc29f`）。中文为主要阅读入口；历史审计采样日期保留，不能把旧报告当作新一轮验收。

当前事实与长期合同分开判断：

```text
当前实现事实：最终目标分支源码与测试
长期行为边界：AGENTS.md + docs/contracts/*
当前缺口/设备验收边界：docs/contracts/06-CURRENT-GAPS.md
产品入口：README.zh-CN.md / CONTRIBUTING.zh-CN.md
历史记录：docs/issue-*.md、docs/archive/RUNTIME-HISTORY.md、Git 历史
法律来源：PROVENANCE.md
```

历史 PR、Issue 实施稿或阶段计划可以解释“当时为什么这样改”，但不能证明当前实现仍然如此。

## 当前 runtime 结论

- Linux runtime：Direct Ubuntu 24.04 chroot；
- Android App 持有 session/shell/工具/数据真源；
- `DirectRootRunner` 只用于建立 direct chroot 所需的最小基础设施；本地 Agent 另有上游兼容的结构化 `root.shell`，MCP 不可见且不接受 raw command；
- 普通 guest 命令最终以真实 App UID/GID 且无 Linux capabilities 运行；
- 旧特权 broker、PRoot、Alpine 不属于生产 runtime；
- `127.0.0.1:18787` HTTP/CONNECT helper 是独立网络兼容组件，代理协议本身不依赖 Root；当前实现仅可为 Android UID/VPN/BPF 出站兼容以特权身份启动。

## 合同（先读这些）

| 文件 | 用途 |
|---|---|
| [`../AGENTS.md`](../AGENTS.md) | Agent 宪法与硬规则 |
| [`contracts/00-IDENTITY.md`](contracts/00-IDENTITY.md) | 产品与 Android/Runtime 身份 |
| [`contracts/01-ARCHITECTURE.md`](contracts/01-ARCHITECTURE.md) | Direct Ubuntu / Root 架构 |
| [`contracts/02-CONSTRAINTS.md`](contracts/02-CONSTRAINTS.md) | Fail-closed 硬限制 |
| [`contracts/03-STORAGE-CONTRACT.md`](contracts/03-STORAGE-CONTRACT.md) | App-owned guest 数据 + Root-owned rootfs |
| [`contracts/04-SECURITY-CONTRACT.md`](contracts/04-SECURITY-CONTRACT.md) | Root/MCP/网络/密钥安全边界 |
| [`contracts/05-ENGINEERING.md`](contracts/05-ENGINEERING.md) | 工程、PR、验证与参考边界 |
| [`contracts/06-CURRENT-GAPS.md`](contracts/06-CURRENT-GAPS.md) | 当前缺口、开放 Issue 与真机验收边界 |
| [`contracts/07-OWNERSHIP-MIGRATION.md`](contracts/07-OWNERSHIP-MIGRATION.md) | 旧 Root-owned 用户数据一次性迁移 |
| [`contracts/08-BOT-COORDINATION.md`](contracts/08-BOT-COORDINATION.md) | Bot 协调边界 |

## 当前说明

| 文件 | 用途 |
|---|---|
| [`../README.zh-CN.md`](../README.zh-CN.md) | 产品入口 |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | Direct Ubuntu 工程状态 |
| [`EXECUTION-ENVIRONMENT.md`](EXECUTION-ENVIRONMENT.md) | 执行、UID/GID、mount 与网络关系 |
| [`SECURITY.md`](SECURITY.md) | 安全模型 |
| [`runtime-package-boundary.md`](runtime-package-boundary.md) | Android runtime 包职责边界 |
| [`UPSTREAM-COMPARISON.md`](UPSTREAM-COMPARISON.md) | Direct Ubuntu 分支与外部上游的对账快照、双方特有内容和复核命令 |
| [`REAL-DEVICE-TEST-REPORT.md`](REAL-DEVICE-TEST-REPORT.md) | 小米真机实测记录、通过项、证据边界与未完成矩阵 |
| [`../BUILDING.zh-CN.md`](../BUILDING.zh-CN.md) | 构建、安装、缓存与内存、验证 |
| [`BUILD-CLEANUP-AUDIT.md`](BUILD-CLEANUP-AUDIT.md) | 构建入口与旧路径回归守卫 |
| [`../CONTRIBUTING.zh-CN.md`](../CONTRIBUTING.zh-CN.md) | 贡献规则 |

## 专题实现说明

- [`AGENT-FOREGROUND-SERVICE.md`](AGENT-FOREGROUND-SERVICE.md)
- [`VOICE.md`](VOICE.md)
- [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md)
- [`specs/debug-server-api.md`](specs/debug-server-api.md)
- [`specs/external-mcp-tools-list-contract.md`](specs/external-mcp-tools-list-contract.md)
- [`specs/backup-rclone-feasibility.md`](specs/backup-rclone-feasibility.md)

这些专题文档只负责各自模块，不能建立第二套 runtime/storage/security 真源。

## 历史文档

[`archive/RUNTIME-HISTORY.md`](archive/RUNTIME-HISTORY.md) 保留迁移背景；`docs/issue-*.md` 保留有独立解释价值的历史决策，文件头明确标记历史。备份 RFC 也属于设计参考，不是当前功能清单。

已删除被合同完全替代的七步计划，以及五份不参与构建的旧 PR patch 副本；它们仍可从 Git 历史恢复。保留法律文件、现役合同、实测报告和上游对账，不为了减少文件数删除验收缺口。
