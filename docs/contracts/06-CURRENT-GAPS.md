# 06 — 现状缺口

本文记录 **master 代码尚未对齐合同** 的事实。允许出现遗留包名、旧路径与已废弃实现的名字，供排障与迁移使用。其它现行产品文档不得把下列缺口写成「已经如此」。

## G1 存储真源 — 核心路径已迁移，App 侧仍有直接访问

minisd、`ubuntu.start` 和核心 `linux.file.*`/`file_*` 工具均使用
`/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`。新的
`workspace.file` RPC 负责受限 guest 文件读写；旧 `Context.filesDir/minis*`
只做一次迁移，IPC 仍可使用 filesDir 上的 app-socket。

仍未完全对齐的 App 侧直接访问包括 Soul/Memory/Skill repository、文件浏览器
及部分 Markdown/媒体预览、WebApp/APK 检查和调试/存储管理路径。它们仍需改为
RPC 或明确的 App-local cache；`/var/minis/mounts` 的 SAF 映射保持独立，不属于
canonical workspace RPC。

仍待验证：broker 文件 RPC 的真实 App UID/GID 与 SELinux 边界、symlink/TOCTOU
行为、首装/升级/强停后的持久化、`df` 非 tmpfs，以及真机上的完整文件流转。

## G2 发行自举 — App 已消费 manifest 并按版本执行原子 rootfs 事务

CI 使用固定 NDK 构建 arm64 PIE `minisd`，并把它与可复现 rootfs、schema-v2 摘要 manifest 一起打入 Debug/Release APK；缺失、部分载荷或 APK 内摘要不匹配均失败关闭。

App 启动时读取并严格校验 APK 内 `runtime-manifest.json`，验证 `nativeLibraryDir/libminisd.so` 与 rootfs 载荷的 SHA-256 和 rootfs 布局。首次安装、版本变化或 rootfs 损坏时，经 `/data/adb/minis/runtime/`（`staging/`、`previous/`、`pending.json`、`deployed.json`）执行唯一运行时分发事务：停 keeper → 解压到 staging 并校验 → 原子切换 canonical `/data/adb/minis/rootfs` → 启动 keeper 并执行 provision → 失败回滚 previous → 全部成功后写入 deployed identity 并清除 pending。App 被杀可从 pending 恢复或回滚；用户数据目录不参与任何替换。

仍待：真机验收首装、升级、中断恢复、provision（python3/git/curl）、SELinux enforcing 与持久化非 tmpfs。

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
