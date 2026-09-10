# 文档索引

当前事实与长期合同分开判断：

```text
当前实现事实：最终目标分支源码与测试
长期行为边界：AGENTS.md + docs/contracts/*
当前缺口/设备验收边界：docs/contracts/06-CURRENT-GAPS.md
产品入口：README.zh-CN.md / CONTRIBUTING.zh-CN.md
历史记录：docs/issue-*.md、旧计划、docs/archive/*
法律来源：PROVENANCE.md
```

历史 PR、Issue 实施稿或阶段计划可以解释“当时为什么这样改”，但不能证明当前实现仍然如此。

## 当前 runtime 结论

- Linux runtime：Direct Ubuntu 24.04 chroot；
- Android App 持有 session/shell/工具/数据真源；
- Root 只用于建立 direct chroot 所需的最小基础设施；
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
| [`../BUILDING.md`](../BUILDING.md) | 构建与验证 |
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

`docs/issue-*.md`、`minis-seven-step-execution-plan.md`、`BUILD-CLEANUP-AUDIT.md` 中保留的旧 branch、PR、SHA、broker/runtime 名称属于历史证据。`archive/` 仅用于历史追溯。判断当前实现必须回到现役 contracts、目标分支源码与测试。
