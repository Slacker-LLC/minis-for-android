# 00 — 产品身份

## 正式名称

- 产品：Minis for Android
- 维护者：Slacker-LLC
- 域名：slacker.llc
- 仓库：`Slacker-LLC/minis-for-android`
- 许可证：GPL-3.0

这是独立产品。产品身份、架构、发布和运行时边界只由本仓库当前代码与合同定义。

## 当前 Android 身份

`master` 当前 Gradle 身份：

| 项 | 当前值 |
|---|---|
| `applicationId` | `llc.slacker.minis` |
| `namespace` | `com.openminis.app` |
| Kotlin/Java 现有包根 | `com.openminis.app` |

`applicationId` 已经完成迁移，不再是未来目标。Android 安装身份与源码 namespace 可以不同；当前没有全库搬迁 Java/Kotlin package 的默认要求。

FileProvider 等 authority 应优先从当前 `applicationId`/manifest placeholder 派生，不在文档里写死旧应用身份。

## Linux guest identity

Guest 文件与进程需要使用设备实际分配给当前 App 的 UID/GID。禁止将 `10000` 或任何示例 UID/GID 当成固定合同。

`/data/adb/minis` 的用户数据若存在属主不匹配，应按 `07-OWNERSHIP-MIGRATION.md` 做受限、可重复的前向校正；当前合同不要求逐文件 WAL、逆向回滚或六阶段事务迁移。

## GitHub About 建议

```text
面向 Root 设备的 Android AI Agent Runtime（minisd + Ubuntu 24.04）。独立项目，源码分发。
```

不要再使用旧沙箱、远程工作台、桌面宠物或其它已经不代表当前产品主线的描述作为仓库身份。

## 法律与来源

法律与著作权只看 [PROVENANCE.md](../../PROVENANCE.md)。产品文档不要把来源关系写成当前产品身份或实现依赖。

## 语言

- 行为与工程规范：中文（`AGENTS.md`、`docs/contracts/`、`README.zh-CN.md`、`CONTRIBUTING.zh-CN.md`）
- 英文 README / 部分 `docs/*.md`：摘要或实现说明，不得另立冲突行为
