# 06 — 现状缺口

本文记录 **master 代码尚未对齐合同** 的事实。允许出现遗留包名、旧路径与已废弃实现的名字，供排障与迁移使用。其它现行产品文档不得把下列缺口写成「已经如此」。

## G1 存储真源 — 代码已对齐合同

App 与 minisd 均以 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}` 为持久化真源。`ubuntu.start` 不再传 filesDir。旧 `Context.filesDir/minis*` 只做一次迁移。

仍待真机验收：SELinux 标签、`df` 非 tmpfs、强停后文件仍在。IPC 仍可能走 filesDir 上的 app-socket（非本条范围）。

## G2 发行自举 — CI APK 已强制携带绑定载荷，运行时升级语义未完成

安装路径：从 `nativeLibraryDir/libminisd.so` 安装 `/data/adb/minis/bin/minisd`；从 APK `assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz` 解到 `/data/adb/minis/runtime/staging/` 再原子替换 rootfs。无 root / 无载荷 fail-closed，不报假 Installed。

CI 使用固定 NDK 构建 arm64 PIE `minisd`，并把它与可复现 rootfs、schema-v2 摘要 manifest 一起打入 Debug/Release APK；缺失、部分载荷或 APK 内摘要不匹配均失败关闭。仍待：App 在安装/启动时消费 manifest 并按版本执行原子升级/回滚；真机验收首装、升级与中断恢复。

## G3 身份未迁

- 现行 `applicationId`：`dev.openminispet.android`
- 现行 `namespace` / Java 包：`com.openminis.app`
- 目标：`llc.slacker.minis`（见 `00-IDENTITY.md`）

本轮文档框架不改 Gradle。

## G4 Root 模式 — Agent 执行路径已对齐合同，剩余 bootstrap/recovery 缺口

Agent 发起的 Root 命令、`root.shell` 与主动 Root 探针已统一通过 `MinisdClient`；标准模式使用 `root.exec` 白名单，越权请求通过完整 `method + params` 绑定的一次性 `root.fullExec` 确认票重放。完全访问由 App 设置持有，Agent 请求不能切换，并在聊天页持续警告。

仍保留的直接 `su -c` 仅位于可信 bootstrap/recovery 路径：安装/启动/探测 minisd、minisd `--call` 传输回退，以及安装或修复 rootfs。这些路径执行 App-owned 静态命令，不承载 Agent 提供的命令；后续 G2/G5 重构应继续缩小并审计该范围。

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
