# 文档索引

本索引对应当前工作线 **`codex/eta-phase6-xposed`**（远端 `Slacker-LLC/minis-eta`，2026-09-20 快照
HEAD `f58adf39`）。`main` 保留移植前的种子线。中文为主要阅读入口；历史审计采样日期保留，不能把旧报告
当作新一轮验收。

当前事实与长期合同分开判断：

```text
当前实现事实：最终目标分支源码与测试
长期行为边界：AGENTS.md + docs/contracts/*
当前缺口/设备验收边界：docs/contracts/06-CURRENT-GAPS.md
产品入口：README.zh-CN.md / CONTRIBUTING.zh-CN.md
本地化规则与覆盖：docs/I18N.md
历史记录：docs/issue-*.md、docs/archive/RUNTIME-HISTORY.md、docs/development/HANDOFF-*.md、Git 历史
法律来源：PROVENANCE.md
```

历史 PR、Issue 实施稿或阶段计划可以解释「当时为什么这样改」，但不能证明当前实现仍然如此。

## 项目文档

| 文件 | 用途 |
|---|---|
| [PROJECT.md](PROJECT.md) | 项目说明：产品定位与身份、能力面、架构边界、构建、本地化、文档地图 |
| [development/PORTING.md](development/PORTING.md) | 开发规范：复用规则、落地 SOP、验证矩阵、归属登记 |
| [development/PROGRESS.md](development/PROGRESS.md) | 移植与收敛进度：逐片记录、已完成、待办阶段、排除项、未验证清单 |
| [development/HANDOFF-2026-09-19.md](development/HANDOFF-2026-09-19.md) | 2026-09-19 的交接快照（过去/现在/将来）；查当前状态请回 PROGRESS.md |
| [I18N.md](I18N.md) | 本地化：语言目录、硬规则、转义与复数坑、审计工具、当前覆盖与剩余 |
| [REFERENCES.md](REFERENCES.md) | 资料索引：Eta 源码速查、上游链接、许可文本、工具链版本 |

## Eta 对照分析

| 文件 | 用途 |
|---|---|
| [analysis/eta-port-program.md](analysis/eta-port-program.md) | 移植阶段计划（阶段划分与顺序） |
| [analysis/eta-integration-plan.md](analysis/eta-integration-plan.md) | 集成方案 |
| [analysis/eta-agent-runtime.md](analysis/eta-agent-runtime.md) | Agent 运行时对照 |
| [analysis/eta-ui-gui-root.md](analysis/eta-ui-gui-root.md) | UI / GUI / Root 能力对照 |
| [analysis/eta-skills-mcp-backup.md](analysis/eta-skills-mcp-backup.md) | Skills / MCP / 备份对照 |

## 当前 runtime 结论

- Linux runtime：Direct Ubuntu 24.04 chroot；
- Android App 持有 session/shell/工具/数据真源；
- `DirectRootRunner` 只用于建立 direct chroot 所需的最小基础设施；本地 Agent 另有上游兼容的结构化 `root.shell`，MCP 不可见且不接受 raw command；
- 普通 guest 命令最终以真实 App UID/GID 且无 Linux capabilities 运行；
- 旧特权 broker 与已淘汰的双栈兼容层不属于生产 runtime；
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
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | 工程状态（结构与验证基线，随 HEAD 更新） |
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

[`archive/RUNTIME-HISTORY.md`](archive/RUNTIME-HISTORY.md) 保留迁移背景；`docs/issue-*.md` 保留有独立解释
价值的历史决策，文件头明确标记历史；`development/HANDOFF-*.md` 是某一天的交接快照。备份 RFC 属于设计
参考，不是当前功能清单。

已删除被合同完全替代的七步计划，以及五份不参与构建的旧 PR patch 副本；它们仍可从 Git 历史恢复。保留
法律文件、现役合同、实测报告和上游对账，不为了减少文件数删除验收缺口。
