# Direct Ubuntu 分支与外部上游对账

这是一份针对当前 Direct Ubuntu 分支的对账快照，不是持续同步政策。产品的法律来源与衍生关系仍以 [`../PROVENANCE.md`](../PROVENANCE.md) 为准；当前行为仍以目标分支源码、测试和中文合同为准。

## 快照范围

本次对账使用了以下状态：

| 项目 | 值 |
|---|---|
| 分支 | `refactor/direct-ubuntu-runtime` |
| 分支基线 HEAD | `b89f117989e77188194c332da4c5a539b1a1f519` |
| 同仓库 `origin/main` | `6f9b12149f8af676c5eb576170c0b636d64cc2f` |
| 与 `origin/main` 的合并基点 | `36941d17440267e88358e3102f28cb41bfdab0d4` |
| 外部上游 main 快照 | `4ef29002e88db1e20e462ec2ff46916e8a7dcb45` |
| 外部上游快照日期 | 2026-09-02 |
| 对账日期 | 2026-09-12 |

当前分支与外部上游没有共同的 Git merge-base，因此不能把两棵树当成同一条线直接做语义合并。本次使用 `git archive` 固定上游快照，再对当前工作树的已跟踪和非忽略文件做文件树对比；当前工作树中尚未提交的 Direct Root、文件安全和测试改动也纳入了检查。

## 文件树结果

以下数字是文件数量，不是代码行数。“相同”表示字节相同，“内容不同”表示双方都有但内容不同，“当前分支独有”和“上游独有”表示只存在于一侧。表中数字是本轮共享行为对齐前的初始对账快照，用来固定审查范围；后续新增的对齐测试/实现不改变它们作为历史快照的含义。

| 范围 | 相同 | 内容不同 | 当前分支独有 | 上游独有 |
|---|---:|---:|---:|---:|
| 整个仓库 | 453 | 254 | 463 | 1173 |
| `src/android/app/src` | 442 | 235 | 399 | 92 |

这些数字只能定位审查范围，不能单独决定“应该复制哪一侧”。尤其是资源、第三方代码、历史 runtime 和自定义能力都可能合法地只存在于一侧。

为避免把删除中的旧文件算成当前文件，完成本轮 parity 回移后再次按“工作树中实际存在的文件”计算，得到：

| 范围 | 相同 | 内容不同 | 当前分支独有 | 上游独有 |
|---|---:|---:|---:|---:|
| 整个仓库 | 462 | 259 | 461 | 1157 |
| `src/android/app/src` | 451 | 240 | 395 | 78 |

第二张表是当前可见文件的复核结果；第一张表保留为最初审查快照，便于追溯本轮清理前的范围。

## 必须保留的当前分支差异

### Direct Ubuntu / Root

当前分支的现役执行链是 App-owned session/shell 协调、Direct Ubuntu 24.04 chroot 和受控 Root 基础设施。关键实现集中在：

- `runtime/ubuntu/`：`UbuntuKernel`、`UbuntuProvisioner`、`UbuntuRuntime`、`DirectRootRunner`、`RootPersistentShell`、`RootNetworkProxy`、`UbuntuPaths`、`UbuntuMountPolicy`；
- `runtime/ExecutionCoordinator.kt`、`runtime/RuntimePathRegistry.kt`；
- `runtime/files/`：workspace 文件边界、symlink/path safety 和 Root 写入范围；
- `sandbox/RootfsManager.kt`、`sandbox/TerminalSession.kt`：rootfs 生命周期和 Terminal 会话边界；
- `runtime/guest/`：Guest bridge、native offload、请求并发和取消传播；
- `tools/runtime/`：结构化 Root 工具入口、权限确认和失败关闭逻辑。

这部分不能为了和上游文件名一致而恢复另一套执行栈。上游树中的旧 PRoot/Alpine、`default_mount`、`libproot-loader*` 和旧特权 broker 只作为历史对照，已经不属于当前生产路径，也不能重新进入 production tree。

当前 Direct Ubuntu 的硬边界没有因对账而放宽：Root 只做 App 构造的 rootfs、namespace、bind、chroot、必要探测/修复和受控迁移；Guest 命令仍使用实际 App UID/GID、清空 supplementary groups 并丢弃 Linux capabilities。网络 proxy 仍是独立的有界 HTTP/CONNECT helper，不是通用 Root RPC。

全仓残留词也已按调用关系复核：生产源码中不再有 `minisd`、PRoot、`default_mount`、旧 distribution 或旧 mount 协调器的实现/调用；仍出现的 `MinisOpenUrlBroker` 只负责 Terminal OSC URL 与 Chat/Web 页面之间的 UI 路由，不是旧特权 broker，也不承载 Root、文件或命令 RPC。

### Guest CLI / native offload 命令面

这次审计确认了一个容易被文件树对比掩盖的差异：上游虽然在 guest rootfs 侧看到一批 `android-*` 和 `minis-*` 命令，但这些文件本身主要是触发点，不是 Android 能力的实现。上游的流程是：

1. `MinisApp` 注册 `NativeOffloadServer` handler；
2. 旧 guest 执行器启动时在 `/usr/local/bin` 写入同名的 `#!/bin/sh` stub；
3. 旧执行器的 `native_offload` 扩展拦截这些 stub 的 `execve`，再回调 Android handler。

Direct Root 禁止把 PRoot/native broker 重新放回生产路径，因此当前分支采用等价但不同的实现：`GuestCommandBridge.ensureGuestCliInstalled()` 根据当前已注册 handler，在 Root-owned Ubuntu rootfs 中生成带随机 token 鉴权的 loopback Bash wrapper。Wrapper 传递 argv、session、cwd、stdin 和受控文件载荷，Android 侧校验协议后调用同一个 `NativeOffloadServer` handler；Guest 进程仍在 chroot 后以真实 App UID/GID、清空 supplementary groups、丢弃 Linux capabilities 的身份运行。

当前 Debug APK 已在小米 `24129PN74C` 真机验证 PATH 命令面，包含：

- `android-alarm`、`android-calendar`、`android-clipboard`、`android-contacts`、`android-device`、`android-location`、`android-notification`、`android-open`、`android-photos`、`android-player`、`android-speak`、`android-speech`、`android-weather`；
- `android-a11y-cli`、`android-shizuku-cli`、`minis-browser-use`、`minis-scheduled`、`minis-sessions-cli`；
- `minis-config`、`minis-model-use`，以及 Debug-only 的 `minis-debug`；
- `minis-open` 和 `xdg-open`、`sensible-browser`、`www-browser`、`x-www-browser`、`gnome-open`、`kde-open` 别名。

真机上 `command -v` 均返回 `/usr/local/bin/<命令>`；`android-device --help`、`android-device info` 和 `minis-config --help` 已获得实际输出。无障碍和 Shizuku 命令本轮只做帮助/版本等无副作用检查，未把未授权状态伪装成通过。这个补齐没有放宽 Root 权限，也不恢复 PRoot、Alpine 或旧 broker。

上游另外存在一个 Python 版 `minis-mcp-cli` 资产，它依赖上游已经退出的脚本和 Python transport；当前分支的 MCP client/server 已由 Android 原生实现承载，本次没有把那套会 `apk add`/自安装依赖的旧资产直接复制进 Direct Ubuntu rootfs。若后续要求在 Guest shell 中提供同名 MCP CLI，需要单独按当前 App-owned MCP 权限和生命周期合同实现，不能直接搬运上游脚本。

### 自定义能力

以下目录和能力只在当前分支出现，属于产品能力而不是可以被上游覆盖的“残留”：

- Bot/Agent 协调：`agent/`、`data/db/Bot*`、`data/repository/Bot*`、Bot UI 和 session coordination；
- Pi/宠物功能：`pet/` 及其 overlay、控制和模型逻辑；
- Remote/Web/App 同步及远程命令：`remote/`、相关 debug RPC 和同步入口；
- Android 工具：`tools/android/`、`tools/internal/`、系统、媒体、电话、天气、TTS 等工具；
- MCP 客户端/服务端、授权确认和工具列表合同；
- voice/voicecall、Provider transport/retry 扩展、图片历史和自定义模型路由；
- glass/UI 自定义界面、额外主题资源、会话/沙箱扩展和相关测试。

这些能力会与上游共享文件发生交叉，因此“文件存在于双方”不等于“可以整文件覆盖”。涉及共享文件时只采纳已核实的上游行为修复，并保留当前分支的自定义入口、权限合同和测试。

## 双方共有、需要保持上游对账的部分

审查重点放在不属于 Direct Ubuntu 和自定义功能的共享能力：

- Provider 请求/响应、模型目录、thinking、图片和语音；
- auth/OAuth、backup/restore、browser、config、网络状态和通知；
- Room/database、session/message、usage、memory、skill 和文件读写；
- speech/read-aloud、chat、markdown、theme、settings 和 Web/App 同步；
- Manifest、JNI/CMake、资源和多语言文件；
- 单测、instrumentation test、构建脚本和文档合同。

同仓库 `origin/main` 已经包含一轮共享行为对齐，例如命令结果形状、shell 创建锁、shell 执行策略、Terminal 环境/输入、文件读写语义、agent tool 暴露和多项 Root/proxy 失败清理。当前目标分支 HEAD 相对该合并基点新增的已提交提交，均集中在 Direct Runtime、Root、proxy、文件边界、取消传播和遗留路径清理；本轮工作树额外回移的共享修复单独列在下面，不能被误读为 Direct Root 的权限扩张或 UI 重写。

当前工作树还保留了本轮为文件安全、Guest identity、MiMo 语音路由和 runtime lifecycle 增加的未提交测试/实现。它们必须继续经过全量 JVM test、Debug 构建和真实设备证据检查，不能仅凭文件树相同判定完成。

### 本轮工作树差异的逐项归因

为避免把“文件有 diff”误判成“功能被改写”，当前工作树相对该分支基线的共享路径改动按下面四类核对：

| 类别 | 代表文件/范围 | 结论 |
|---|---|---|
| Direct Ubuntu 所需的所有权/路径迁移 | `MinisApp`、`ContextOffload`、`FileMentionIndex`、`MinisDocumentsProvider`、`MinisImageFetcher`、`ChatLinkResolver`、`WebAppPathResolver`、`StorageManagementScreen` | 把旧 broker/PRoot 文件面改为 App-owned guest file API、Direct Root rootfs 和 session workspace；不改变上层文件/分享/网页功能语义 |
| 生命周期、失败关闭和设备兼容 | `NetworkMonitor`、`SystemResourceMonitor`、`ScheduledTaskAlarmReceiver`、`MinisApp.onTerminate`、CMake/native 文件边界 | 只处理 Direct Ubuntu 子进程、网络回调、Native/JNI 和资源回收；不扩展普通 Agent 的 Root 权限 |
| 已核实的共享行为对齐 | `AppDatabase`、`LLMModel`、`ProviderRepository`、`ProviderFactory`、`OpenAIProvider`、`FastModePrefs`、`UsageStatsScreen`、`SessionListScreen` | 对应上游行为已有专门测试，详见下方对齐清单；不覆盖 Bot、Pi、Remote、MCP、voicecall 等自定义入口 |
| 纯文本/合同/产品名更新 | README、contracts、各语言 rootfs 文案和注释 | 只同步 Direct Ubuntu 的事实描述，避免文档继续声称旧执行栈存在 |

除此之外，Chat、Browser、Web/App、Session、Settings 等共享 UI 的本轮变更逐项检查后只有 guest 路径/生命周期语义、主题来源 parity 或上述文字同步；没有借清理 Runtime 顺手替换上游 UI 功能。

## 上游独有内容的处理结论

上游树中只存在而当前树不应恢复的内容，分为三类：

1. **旧执行栈**：`sandbox/PRootKernel`、`PersistentShell`、`MountedFolderCoordinator`、旧 offload runtime、旧 `default_mount`、`libproot-loader.so`/`libproot-loader32.so` 和 `agent/shell/OnDemandBash.kt`。它们与 Direct Ubuntu 合同冲突，明确排除。
2. **旧依赖、资产和脚本**：上游独有的 `deps/` 仍包含 Alpine 准备脚本、talloc、`rclone-mobile` Go 源码和整套旧 LAME 树；当前分支使用已构建的 `src/android/app/libs/rclone.aar`，这些内容不能整批搬回。上游独有的 badge/screenshot、历史设计文档和 Alpine/rootfs 脚本同样不属于当前 Direct Ubuntu 生产输入。
3. **上游快照中的新增共享测试、翻译和资源**：`values-es`、`values-fil`、`values-in`、`values-ms`、`values-pl`、`values-pt-rBR`、`values-ro`、`values-th`、`values-tr` 等 locale 文件当前上游独有；它们属于可选语言资源，不是 Direct Ubuntu 运行时依赖。新增测试和资源不能因为“来自上游”就整批覆盖当前产品；每个项目要么已有当前分支等价测试/实现，要么作为后续明确的 parity gap。

当前实际存在于上游、但不在分支工作树的 `src/android/app/src` 文件共 78 个，已经逐个归类完毕：

| 上游独有类别 | 数量 | 处理结论 |
|---|---:|---|
| 旧 sandbox/PRoot/offload 实现和旧 instrumentation | 40 | 与 Direct Ubuntu 不兼容，不恢复 |
| 旧 sandbox 单测 `OffloadReplySweepTest`、`SeccompFallbackPolicyTest`、`TerminalSanitizerTest` | 3 | 它们测试已退出执行栈，不恢复 |
| `default_mount`、`libproot-loader.so`/`libproot-loader32.so` | 24 | 旧 Alpine/PRoot 资产，不进入生产 APK |
| `agent/shell/OnDemandBash.kt` | 1 | 依赖 Alpine `apk add bash`，由 Ubuntu rootfs 固定提供 bash，不恢复 |
| 上游额外 locale 文件 | 9 | 记录为上游语言资源差异；当前分支未引入带旧 runtime 文案的资源 |
| `ui/chat/MinisPathCandidatesTest.kt` | 1 | 已由当前 `ChatLinkResolverPathTest` 覆盖更宽的路径/文件优先级场景 |

本次已确认当前分支存在等价或更强覆盖的区域包括：

- Responses API 完成状态、顶层图片/工具结果图片和 Provider request body；
- Chat `minis://` 路径解析、文件 provider/path 边界；
- Direct Root 的 start/stop、重复启动、启动中取消、shell/proxy 失败、rootfs repair、path/symlink escape、workspace access 和 cleanup；
- Root tool 权限、危险命令确认、MCP/Agent 不可见 Root 入口和旧 runtime production-tree guard；
- MiMo v2.5 文本与 `mimo-v2.5-tts` 的真实设备请求。

本轮已回移并通过 JVM test 的共享 parity 主题包括：

- restricted settings、聊天配置偏好绑定、compact divider、native vision modality 归一化；
- Room schema 导出及数据库版本 guard；
- preview flicker、skill stale metadata 和 provider empty-key refresh；
- Responses incomplete partial output、tool-result image serialization；
- XAI 动态模型目录、priority/service tier request body；
- usage attribution 分类 helper 和 in-app theme source guard。

这些改动只补足双方共有行为，保留了当前分支的 Provider、UI、权限和 Direct Ubuntu 边界。对应测试文件已放入当前分支，`./gradlew test --no-daemon` 全量通过。上游的 `MinisPathCandidatesTest` 没有原样复制，因为当前已有覆盖更宽的 `ChatLinkResolverPathTest`；上游独有的 `OffloadReplySweepTest`、`SeccompFallbackPolicyTest`、`TerminalSanitizerTest` 仍属于已退出的旧 runtime 约束，不恢复。

仍不能仅凭“已有相邻测试”宣称完全等价的内容，主要是完整真机功能矩阵和 HyperOS instrumentation 执行证据；它们应在设备回归中逐项补齐或明确记录 gap，而不是通过恢复旧执行栈解决。当前 UI 的“朗读”动作在小米机上观察到绑定 Xiaomi 系统 TTS 引擎；这证明系统朗读链路可用，但不把它冒充成 `mimo-v2.5-tts` provider 选择证据。后者已有独立的 MiMo TTS 请求证据。

## 可复核的对账命令

```bash
AUDIT_TREE="$(mktemp -d /tmp/minis-upstream-audit.XXXXXX)"
git archive refs/remotes/upstream/main | tar -x -C "$AUDIT_TREE"

# 当前工作树：已跟踪 + 非忽略且实际存在的文件；不把删除中的路径或 build/ 等输出算进来
git ls-files -co --exclude-standard -z | while IFS= read -r -d '' p; do
  test -f "$p" && printf '%s\n' "$p"
done | sort

# 上游对应文件树
find "$AUDIT_TREE/src/android/app/src" -type f -print | sort

# 共享文件的逐文件差异
diff -rq "$AUDIT_TREE/src/android/app/src" src/android/app/src
```

对账时不要使用工作树外的设备数据替代源码证据。`adb install -r` 会保留 App 私有数据，`/data/adb/minis/rootfs` 是 Root-owned runtime state；当前小米机还并存旧包 `com.openminis.app` 和新包 `llc.slacker.minis`，两者的数据目录与进程不能混为一谈。设备上残留的旧进程或旧数据应单独按设备清理范围处理，不能据此把旧 runtime 重新写回生产代码。

## 当前验收证据

- `./gradlew test --no-daemon`：通过；
- `./gradlew assembleDebug --no-daemon`：通过；
- `./gradlew :app:assembleDebugAndroidTest --no-daemon`：通过；
- `./gradlew :app:minifyReleaseWithR8 -x requireReleaseSigning --no-daemon`：通过；完整 `assembleRelease` 在生产签名门禁处停止，因为当前环境没有 release keystore，不使用 Debug 签名替代；
- 小米真实设备（Android 17/HyperOS）：已安装当前 Debug APK，Terminal 启动提示为干净的 `minis@localhost:/$`；其中 `minis@localhost` 是标准 `user@host` 前缀，不是旧数据输出。Guest `id` 与 rootfs passwd/group 的动态 App UID/GID 一致；
- 小米真实设备：Terminal 关闭后对应 `bash -l` 进程已回收；强停 App 后重新打开、再次进入 Terminal 的冷启动也已通过；连续关闭/重开后仍为干净提示符；
- 小米真实设备：Guest 执行自杀场景 `kill -9 0` 后 Terminal 返回主界面，未留下 `bash -l`，随后重新进入 Terminal 成功；这只证明单个 Guest shell 的退出回收，不等于并发多 session 或 Root helper 崩溃矩阵已完成；
- 小米真实设备 Provider：MiMo v2.5 文本请求成功，MiMo v2.5 TTS 请求成功；
- 测试 APK 在该 HyperOS 设备上被系统的 test APK 安装限制拒绝，不能把 instrumentation 已编译等同于已在该设备执行；
- 仍需按设备验收矩阵留证：Root 授权拒绝/允许分支、重启手机后重开、升级 APK 保留数据、多 Terminal session，以及 HyperOS 上受系统限制尚未执行的 instrumentation；本轮不把这些未跑项写成已完成。

因此，这份对账已经把“哪些必须和上游保持、哪些是当前产品特有、哪些是 Direct Ubuntu 必须偏离”固定下来，但不把尚未完成的共享 parity 测试或完整真机矩阵伪装成已完成。
