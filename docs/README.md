# 文档索引

当前事实与长期合同分开判断：

```text
当前实现事实：最终 master 源码与测试
长期行为边界：AGENTS.md + docs/contracts/*
已确认差异：docs/contracts/06-CURRENT-GAPS.md
产品入口：README.zh-CN.md / CONTRIBUTING.zh-CN.md
历史记录：docs/issue-*.md、旧计划、docs/archive/*
法律来源：PROVENANCE.md
```

历史 PR、Issue 实施稿或阶段计划可以解释“当时为什么这样改”，但不能证明今天 `master` 仍然如此。

## 合同（先读这些）

| 文件 | 用途 |
|---|---|
| [`../AGENTS.md`](../AGENTS.md) | Agent 宪法与当前身份/运行时硬规则 |
| [`contracts/00-IDENTITY.md`](contracts/00-IDENTITY.md) | 当前产品与 Android 身份 |
| [`contracts/01-ARCHITECTURE.md`](contracts/01-ARCHITECTURE.md) | Root/minisd/Ubuntu 架构 |
| [`contracts/02-CONSTRAINTS.md`](contracts/02-CONSTRAINTS.md) | Fail-closed 硬限制 |
| [`contracts/03-STORAGE-CONTRACT.md`](contracts/03-STORAGE-CONTRACT.md) | `/data/adb/minis` 持久化真源 |
| [`contracts/04-SECURITY-CONTRACT.md`](contracts/04-SECURITY-CONTRACT.md) | 安全边界 |
| [`contracts/05-ENGINEERING.md`](contracts/05-ENGINEERING.md) | 工程、PR、验证与参考边界 |
| [`contracts/06-CURRENT-GAPS.md`](contracts/06-CURRENT-GAPS.md) | 当前 `master` 已确认缺口 |
| [`contracts/07-OWNERSHIP-MIGRATION.md`](contracts/07-OWNERSHIP-MIGRATION.md) | 属主校正/恢复规则 |

## 入口与当前状态

| 文件 | 用途 |
|---|---|
| [`../README.zh-CN.md`](../README.zh-CN.md) | 产品入口 |
| [`../README.md`](../README.md) | 英文摘要 |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | 当前工程能力与已确认风险 |
| [`EXECUTION-ENVIRONMENT.md`](EXECUTION-ENVIRONMENT.md) | 当前 Root/minisd/Ubuntu 执行模型 |
| [`SECURITY.md`](SECURITY.md) | 安全模型实现说明 |
| [`../BUILDING.md`](../BUILDING.md) | 构建步骤 |
| [`../CONTRIBUTING.zh-CN.md`](../CONTRIBUTING.zh-CN.md) | 贡献规则 |

## 专题实现说明

- [`AGENT-FOREGROUND-SERVICE.md`](AGENT-FOREGROUND-SERVICE.md)
- [`VOICE.md`](VOICE.md)
- [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md)
- [`specs/debug-server-api.md`](specs/debug-server-api.md)

这些文件描述具体模块；与当前源码或合同冲突时必须更新，不能另立行为。

## 历史实施记录

`docs/issue-*.md`、`BUILD-CLEANUP-AUDIT.md` 等按其标题记录对应 Issue/PR 当时的范围与证据，其中出现的 branch、PR、SHA、未完成项属于历史上下文，不是当前状态列表。

`minis-seven-step-execution-plan.md` 已标记为被当前 contracts / GitHub Issues 取代。

[`archive/`](archive/) 只保留历史引用与快照，非当前权威。
