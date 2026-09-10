# 00 — 产品身份

## 正式名称

- 产品：Minis for Android
- 维护者：Slacker-LLC
- 域名：slacker.llc
- 仓库：`Slacker-LLC/minis-for-android`
- 许可证：GPL-3.0

这是独立产品。产品身份、架构、发布和运行时边界只由本仓库当前代码与合同定义。

## 当前 Android 身份

| 项 | 当前值 |
|---|---|
| `applicationId` | `llc.slacker.minis` |
| `namespace` | `com.openminis.app` |
| Kotlin/Java 现有包根 | `com.openminis.app` |

Android 安装身份与源码 namespace 可以不同；当前没有全库搬迁 Java/Kotlin package 的默认要求。FileProvider 等 authority 应优先从当前 `applicationId`/manifest placeholder 派生。

## 当前运行时身份

产品 runtime 是 Root-only 的 direct Ubuntu 24.04 chroot：Android App 自己持有 session shell 生命周期，Root 只执行受控的 namespace/bind/chroot/rootfs 等基础设施动作。Guest shell 最终降到设备实际 App UID/GID，并清空 supplementary groups 与 Linux capabilities。

旧 root broker 不属于当前生产架构；历史文档可以保留其名称用于说明迁移背景，但当前实现、构建、CI 和合同不得把它当现役组件。

## 数据身份

现役 guest 用户数据由 App 私有存储持有：workspace/home、global memory/skills/shared/mcp-servers，以及 per-session backing 都从当前 `Context.filesDir` 派生。`/data/adb/minis/rootfs` 仍是 Root-owned Ubuntu rootfs。

旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为一次性迁移源；迁移完成后不能继续作为运行时真源。具体见 `03-STORAGE-CONTRACT.md` 与 `07-OWNERSHIP-MIGRATION.md`。

## GitHub About 建议

```text
面向 Root 设备的 Android AI Agent Runtime（Direct Ubuntu 24.04 chroot）。独立项目，源码分发。
```

不要再使用旧 broker、旧沙箱、远程工作台、桌面宠物或其它已经不代表当前产品主线的描述作为仓库身份。

## 法律与来源

法律与著作权只看 [PROVENANCE.md](../../PROVENANCE.md)。产品文档不要把来源关系写成当前产品身份或实现依赖。
