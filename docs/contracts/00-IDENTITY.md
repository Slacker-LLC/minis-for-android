# 00 — 产品身份

## 正式名称

- 产品：Minis for Android
- 维护者：Slacker-LLC
- 域名：slacker.llc
- 仓库：`Slacker-LLC/minis-for-android`
- 许可证：GPL-3.0

这是独立产品。产品身份、架构、发布和运行时边界由本仓库当前代码、测试与现役合同定义。

## 当前 Android 身份

| 项 | 当前值 |
|---|---|
| `applicationId` | `llc.slacker.minis` |
| `namespace` | `com.openminis.app` |
| Kotlin/Java 包根 | `com.openminis.app` |

Android 安装身份与源码 namespace 可以不同；当前没有全库迁移 Java/Kotlin package 的默认要求。FileProvider 等 authority 应从当前 `applicationId`/manifest placeholder 派生。

## 当前运行时身份

合同中的 **direct Ubuntu** 指当前生产 Linux runtime：Direct Ubuntu 24.04 chroot。Android App 自己持有 session/shell 生命周期；需要特权的 rootfs、mount namespace、bind mount、chroot、受控迁移等基础设施由内部 Root 路径完成。Guest shell 最终降到设备实际 App UID/GID，并清空 supplementary groups 与 Linux capabilities。

旧特权 broker、PRoot 和 Alpine 不属于当前生产架构；历史文档可以保留这些名称用于解释迁移背景，但现役实现/构建/合同不得把它们当 active component。

## 网络组件身份

`minis-root-network-proxy` 是当前代码中的网络兼容 helper 名称。这里的 `root` 表示当前 Android 部署可以使用特权身份建立出站 socket，**不表示 HTTP/CONNECT 代理协议天然需要 Root，也不表示网络代理属于 chroot 权限模型本身**。

当前 helper 固定 loopback、单用途、无命令/文件/通用 RPC。若某设备可让 App-UID guest 直接正确联网，Direct Ubuntu runtime 的身份仍然不变。

## 数据身份

现役 guest 用户数据由 App 私有存储持有：workspace/home、global memory/skills/shared/mcp-servers，以及 per-session backing 都从当前 `Context.filesDir` 派生。`/data/adb/minis/rootfs` 是 Root-owned Ubuntu rootfs。

旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为一次性迁移源。具体见 `03-STORAGE-CONTRACT.md` 与 `07-OWNERSHIP-MIGRATION.md`。

## GitHub About 建议

```text
面向 Root 设备的 Android AI Agent Runtime（Direct Ubuntu 24.04 chroot）。独立项目，源码分发。
```

不要再使用旧 broker、旧沙箱、远程工作台、桌面宠物或其它已经不代表当前产品主线的描述作为仓库身份。

## 法律与来源

法律与著作权只看 [PROVENANCE.md](../../PROVENANCE.md)。产品文档不要把来源关系写成当前产品身份或实现依赖。
