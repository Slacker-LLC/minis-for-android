# 06 — 现状缺口

本文记录 **master 代码尚未对齐合同** 的事实。允许出现遗留包名、旧路径与已废弃实现的名字，供排障与迁移使用。其它现行产品文档不得把下列缺口写成「已经如此」。

## G1 存储死锁（P0）

合同：`03-STORAGE-CONTRACT.md`，真源为 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`。

现状：

- Android `UbuntuPaths.init()` 把 host workspace / memory / skills / shared / sessions 指到 App `Context.filesDir` 下（`minis/workspace`、`minis-global/*`、`minis-sessions`）。
- `UbuntuRuntime.ensureReady()` 把这些路径传给 `ubuntu.start`。
- `minisd` 拒绝非 `/data/adb/minis/*` 的持久化参数；即便 keeper 用合同路径起来，App 的 `runtimeLayoutMatches()` 仍按 filesDir 比对，对不上就停 keeper。

结果：App 与 minisd 对持久化真源不一致。在开启 App Data Isolation 的设备上，root 视角的 filesDir 还可能是 tmpfs_data，数据会丢。IPC 若走 filesDir 上的 socket，App 与 root 也可能不是同一个文件。

**下一轮代码只修这一条。**

## G2 发行不能自举

APK 不能可靠、原子地部署匹配的 minisd + rootfs。`RootfsManager` 修复仍依赖如 `/data/local/tmp/ubuntu-arm64-rootfs.tar.gz` 一类外部归档。无 root 时不得报假 Installed。

## G3 身份未迁

- 现行 `applicationId`：`dev.openminispet.android`
- 现行 `namespace` / Java 包：`com.openminis.app`
- 目标：`llc.slacker.minis`（见 `00-IDENTITY.md`）

本轮文档框架不改 Gradle。

## G4 Root 旁路

`PrivilegedCommandRunner` → `RootCommandRunner.run()` → `su -c`，不经过 `minisd root.exec` 白名单。`RootfsManager` 同样直接 `su -c`。Confirm 在 minisd 侧主要绑方法名，不绑完整 argv。

## G5 恢复语义

Helper 退出码 4/5/6 是 execve 前的基础设施失败。部分路径仍可能被当成普通 shell 退出码。Rust `rootfs_looks_valid()` 仍然偏弱。`health.get` 曾把 SELinux enforcing 写死为 true。

## G6 平台技巧与权限债

- Agent 长任务 FGS 类型曾用 `mediaPlayback` 躲避 `dataSync` 时限，语义不匹配。
- Manifest 仍含宽存储权限与遗留 external-storage 标志，注释仍可能描述已废弃的 bind 模型。
- `usesCleartextTraffic` / NSC 在平台层全开，真实约束必须落在应用策略，不能假装平台已收紧。
- 目标 guest UID 必须是真实 App UID；写死 `10000` 会 chown 到错误用户。

## G7 产品叙事

GitHub About 可能仍展示过时能力（宠物、远程工作台、旧沙箱名）。以 `00-IDENTITY.md` 中的 About 文案为准，需在 GitHub 设置里手工更新。

## 使用规则

修缺口时：改代码与测试，使行为贴近 00–05；然后从本文删除已关闭条目。不要反过来改合同去承认错误实现，除非明确废弃该合同条款。
