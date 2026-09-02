# 00 — 产品身份

## 正式名称

- 产品：Minis for Android
- 维护者：Slacker-LLC
- 域名：slacker.llc
- 仓库：https://github.com/Slacker-LLC/minis-for-android
- 许可证：GPL-3.0

这是独立产品。架构、发布、包名与开发决策只由本仓库定义。不与任何外部仓库持续同步。

## 目标 Android 身份（已落地为 `llc.slacker.minis`）

| 项 | 目标值 |
|---|---|
| `applicationId` | `llc.slacker.minis` |
| 未来 Java/Kotlin 包根 | `llc.slacker.minis` |
| FileProvider / 其它 authority | 随 `applicationId` 派生为 `llc.slacker.minis.documents` 等 |

已落地 `applicationId` 迁移与六阶段事务级属主迁移规范（详见 `07-OWNERSHIP-MIGRATION.md`）。

遗留安装将被视为另一款应用；升级与数据迁移策略在身份迁移时单独规定。

## GitHub About（需在仓库设置里手工粘贴）

```text
面向 Root 设备的 Android AI Agent Runtime（minisd + Ubuntu 24.04）。独立项目，源码分发。
```

不要再使用桌面宠物、远程工作台、已废弃沙箱后端等过时描述。

## 与外部项目的关系

法律与著作权：只看 [PROVENANCE.md](../../PROVENANCE.md)。

产品文档、README、架构合同、Issue/PR 模板 **不要** 把本项目定义成外部项目的分支、对比表或同步下游。读者不需要了解任何上游产品也能理解 Minis for Android。

## 语言

- 行为与工程规范：中文（`AGENTS.md`、`docs/contracts/`、`README.zh-CN.md`、`CONTRIBUTING.zh-CN.md`）
- 英文 README / 部分 `docs/*.md`：摘要或实现备忘，不得另立行为
