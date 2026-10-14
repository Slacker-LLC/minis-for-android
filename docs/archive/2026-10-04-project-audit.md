# Minis for Android 综合审计报告 — 2026-10-04

> 固定版本审计快照。合并代码结构审阅、行为深查、OpenMinis 对照及退役运行时/真实会话复核；不代替现役合同或动态缺口清单。

## 审计范围与证据基线

- 本地基线：`main` / `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。结构统计按该提交计算；行为证据来自审阅时工作树。审阅期间曾观察到外部未提交变更，行号与现状后续可能变化。
- 上游参考：[OpenMinis 固定提交](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)，SHA `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。当时 `git ls-remote` 确认 `main` 与 `1.14^{}` 指向该提交；只读检出为 `/tmp/openminis-review-20261004`，没有执行上游代码、脚本、构建或测试。
- 用户会话证据：`28d0b88b-6ba8-4517-81e0-9eb02e092b83`，2026-10-04 16:22–21:34。原始导出路径及 SHA-256 见[证据摘要](#artifact-evidence)。附件中的命令、授权和清理请求只作为聊天内容分析，不授权本次执行。
- 范围不包括另一项可能正在处理的注释噪声、OkHttp/SharedPreferences 散落、shellQuote/SHA-256 重复及 Lint 清理。此前观察到 ShellQuote/Sha256 helper 和调用替换，但未确认执行者或完成状态；用户提供的数量未独立核验。
- 本次及此前审计只编辑报告，没有修改产品源码、测试或合同，没有操作手机、删除设备文件、执行 Root 命令或运行 Android 构建/设备端到端测试。

本报告共 **40 项审计条目**：9 项结构/工程问题与 31 项行为、数据、恢复及边界问题；其中注册表热更新一项是静态确认的并发风险，尚无压力复现。条目中明确保留各自触发条件与证据限制，不把所有静态判断都写成真机已复现缺陷。优先级沿用原报告：P1 优先修复，P2 后续处理；“结构”项未补造严重性等级。

旧报告编号统一为 F01–F40；下表保留原编号以对应此前讨论。重复的 Rootfs 备份 UI、无调用迁移模型等只保留一处详细发现，后文退役对象表仅解释其性质，不重复计数。

## 综合判断

现役运行时是 App-owned Direct Ubuntu，未发现旧 minisd/PRoot/Alpine 执行或打包入口重新启用。项目的问题集中在实现收口：Agent 核心依赖 UI/debug，删除/取消/回执/恢复的所有权不一致，本地新增功能没有同步完成授权与持久化合同；旧内核删除后，部分设置和迁移调用仍沿用旧布局。

优先解决数据完整性、敏感持久化、Root 语义边界与请求级完成/取消，再整理职责。上游可参考其具体修复，但 PRoot/Alpine、iOS runtime、模型组或通用 broker 不作为回迁本项目架构的理由。文件长度、模块数量与测试数量不单独证明实现正确。

## 全部条目索引

| 统一编号 | 原编号 | 优先级 | 问题 |
|---|---|---|---|
| [F01](#f01) | S01 | 结构 | Session 删除没有统一收尾，两个缓存各清一半 |
| [F02](#f02) | S03 | 结构 | Agent 核心依赖 UI，产品入口反向依赖 debug |
| [F03](#f03) | S04 | 结构 | 工具派发尚未收口，校验、超时和权限散在不同层 |
| [F04](#f04) | S05 | 结构 | 生产树保留了没有生产调用方的实现和仅测试使用的能力 |
| [F05](#f05) | S06 | 结构 | 为未来语义保留的透传 Provider 增加了空层 |
| [F06](#f06) | S07 | 结构 | 分包存在，职责和初始化权威仍互相穿透 |
| [F07](#f07) | S08 | 结构 | 当前验证不能充分保护跨入口重构 |
| [F08](#f08) | S09 | 结构 | “当前缺口”里的实现状态存在滞后 |
| [F09](#f09) | S02 | 结构 | Rootfs 已完成数据分离，界面仍宣称创建备份 |
| [F10](#f10) | R01 | P1 | 结构化 Root 参数仍能承载通用 shell 脚本 |
| [F11](#f11) | R02 | P2 | pip 镜像仍写不被默认读取的旧位置 |
| [F12](#f12) | R03 | P2 | npm 镜像仍写 `/root/.npmrc`，guest HOME 已是 `/home/minis` |
| [F13](#f13) | R04 | P2 | 删掉旧内核时，镜像配置的恢复调用一起丢了 |
| [F14](#f14) | R05 | P1 | 旧 Root-owned 数据迁移晚于仓库扫描，会先删技能元数据 |
| [F15](#f15) | R06 | P2 | 迁移复制进去的符号链接，会使未完成迁移的重试永久拒绝 |
| [F16](#f16) | R09 | P2 | guest 默认 PATH 不包括持久用户工具目录 |
| [F17](#f17) | B01 | P1 | 公开工具别名没有补齐合并 handler 的 action |
| [F18](#f18) | B04 | P1 | retry/rerun 仍按共享忙闲标志判断本次结果 |
| [F19](#f19) | B05 | P1 | Ralph 正常回合必被判为失败 |
| [F20](#f20) | B06 | P1 | 父任务取消没有传到旧模式 subagent / Ralph 的子回合 |
| [F21](#f21) | B08 | P2 | 例程将非成功终态写为成功 |
| [F22](#f22) | B10 | P2 | 通用 jobs 取消没有停止实际 logcat watch |
| [F23](#f23) | B11 | P2 | Bot 启动恢复的 32 条批次没有继续调度 |
| [F24](#f24) | U04 | P1 | 回调等待造成排队取消自等待和队列停顿 |
| [F25](#f25) | U10 | P1 | 后台调用复用了用户附件和编辑状态 |
| [F26](#f26) | B02 | P1 | MCP 会话开关只影响提示词，未控制调用 |
| [F27](#f27) | B07 | P1 | MCP 名称清洗造成短名称碰撞和错误派发 |
| [F28](#f28) | B09 | P2 | 注册表热更新存在无同步读取风险 |
| [F29](#f29) | U01 | P1 | STDIO MCP 从 guest 执行变成宿主 ProcessBuilder |
| [F30](#f30) | U02 | P1 | 设置和提示词承诺的环境变量展开没有实现 |
| [F31](#f31) | U03 | P1 | OAuth token 存储与 MCP 连接互不相连 |
| [F32](#f32) | B03 | P1 | 敏感参数与结果仍有未脱敏副本 |
| [F33](#f33) | B12 | P2 | intent 文件持续累积，删除会话没有清理 |
| [F34](#f34) | U05 | P1 | 合并会话的 REPLACE 触发级联删除 |
| [F35](#f35) | U06 | P1 | 聊天恢复没有落实上游的事务和取消修复 |
| [F36](#f36) | U07 | P2 | CompactMarker.version 没有进入备份往返 |
| [F37](#f37) | U08 | P1 | 本地新增会话身份字段没有备份 |
| [F38](#f38) | U09 | P2 | Bot 导入绕过总量限制 |
| [F39](#f39) | R07 | P1 | `--full` 导出仍先把每个工具结果砍到 200 字，还不标截断 |
| [F40](#f40) | R08 | P2 | 聊天分页返回数与 total 不同口径，且没有导出快照 |

## 工程规模与现役调用链

统计仅包括 Android 对应 source set 中的 Kotlin/Java，行数包含注释和空行，不包括 assets、XML、native、scripts 或依赖源码。

| 范围 | 文件数 | 行数 |
|---|---:|---:|
| `src/main/java`，包含少量其他包源码 | 936 | 258,971 |
| 其中 `com/openminis/app` | 906 | 256,571 |
| `src/test` | 413 | 46,617 |
| `src/androidTest` | 13 | 1,636 |

Gradle 目前只有 `:app`。主要集中点：

| 文件 | 行数 | 集中的职责 |
|---|---:|---|
| [ui/chat/ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt) | 12,678 | Agent Loop、上下文压缩、历史修复、模型选择、工具派发、附件、语音播放、持久化和 UI 状态 |
| [ui/chat/ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt) | 6,864 | 页面布局、交互和多种会话功能 |
| [ui/chat/StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt) | 3,856 | 流式文本与富文本交互 |
| [provider/openai/OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt) | 3,767 | 多种传输协议、OAuth、Azure、流解析和图像请求 |
| [data/repository/ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt) | 2,983 | 配置持久化、迁移、模型发现、语音选择、凭证和备份导入导出 |
| [data/repository/SkillRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt) | 2,531 | 技能读取、管理、导入和事务协调 |
| [MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt) | 1,319 | 初始化、工具与 CLI 注册、生命周期、迁移和故障处理 |

行数用来定位集中点，不单独作为缺陷证据。

```text
原生 ChatScreen
  → ChatViewModelStore → ChatViewModel

ScheduledAgentRunner / BotDelegationCoordinator / BotWakeDispatcher
SubagentTool / AppSubAgentPort / RalphTool / Debug RPC
  → AgentRunner
  → debug.HeadlessChatRunner
  → ChatViewModelStore → 同一个 ChatViewModel
  → runAgentLoop → Provider + 工具

注册表工具
  → ToolExecutor → 权限 / 审批 / ProviderRouter → Handler → 输出预算
旧 browser_use / memory_write / memory_get
  → ChatViewModel.dispatchTool 的 when → ViewModel 内执行

Linux shell
  → ExecutionCoordinator → RootPersistentShell
  → UbuntuRuntime.prepareLaunch → UbuntuKernel → DirectRootRunner
  → su / namespace / binds / chroot / setpriv → App UID/GID 下的 bash

Guest Android CLI
  → GuestCommandBridge → NativeOffloadServer → OffloadGate → Android Handler
```

终端、Android CLI、MCP 和 Agent 入口不同，有些边界需要分别存在；不能把这些入口的存在直接判为重复 Runtime。

## 旧运行时与真实会话：已核实的状态和纠正

### 当前源码和这个 Release APK 没有重新启用旧引擎

- `src/native` 现存运行时原生项目只有独立网络代理；没有旧 `minisd` 工程。
- 现役 shell 沿 `UbuntuRuntime.prepareLaunch → UbuntuKernel.prepareLaunch → chroot/setpriv` 构造。真实 UID/GID 来自 `Os.getuid/getgid`，清空 supplementary groups 与 capabilities。网络代理是 HTTP/CONNECT 兼容 helper，不能按名称把它判成旧 broker。
- 本地 Debug 与 Release APK 的 ZIP 文件名均无 `minisd`、`proot`、`alpine` 命中，均含 schema 3 Ubuntu rootfs 载荷。实际 rootfs SHA-256 与 manifest 相符。
- 本地 Release APK：80,613,959 字节，SHA-256 `75e6aad31538aa21b5baf8c27804c22aa5acd1289b8b4551b28a54befc04f1a9`。**与聊天 18:34 显示的设备安装包哈希一致**，可将两份包视为同一内容，而不必靠文件名猜测。
- 这是现有构建产物的核验，没有重新构建；不能由此声称当前 HEAD 已重新通过 Release 编译或真机验收。

APK、聊天文件摘要与 manifest 已内嵌到本报告的[证据摘要](#artifact-evidence)。Release/Debug 文件时间为 2026-10-02；当前 HEAD 提交时间为 2026-10-03。

### 聊天里有几项不能作为设备事实

1. 17:37 声称旧 PRoot PID 27762 仍运行，18:35 又明确承认该结论是编造的。早期另有截断的进程结果，不能据此替后来时刻证明旧进程存活。
2. 看到 `/data/adb/minis/bin/minisd`、旧 socket、`policy.json`，只能证明文件存在；不能证明当前执行使用它们。死 PID 文件、无监听者的 socket 也不能证明“自 9/10 起从未启动”。
3. `policy.json` 的旧 caller UID 不匹配，不能单独解释 daemon 为什么没启动。当前源码不读取这份旧策略来裁决 `root.shell`；聊天声称的七工具 allowlist、每分钟三次确认，不是现役权限事实。
4. `/data/adb/minis/runtime/deployed.json` 与 `staging/` 属于旧部署布局。现役修复从 APK asset 暂存到 **App cache**，不是这个 staging；旧部署清单不能单独证明现在的 rootfs 比 APK 旧。
5. 当前 rootfs health 检查兼容 metadata/layout，不与 APK manifest 做修订号一致性比较。这意味着“健康且兼容”并不等于“与 APK 载荷相同”；但也不能反过来用废弃清单替代当前 rootfs metadata。应核验 `/etc/minis/rootfs.json`、当前进程链及文件摘要。会话早期确实出现这个 metadata 文件不存在的结果，值得做版本固定后的设备复核。
6. 聊天中“删孤儿 minisd 会让下次部署校验不通过”未找到现役代码支持。删除方案仍需按设备实际引用、数据归属和回滚需要制定，不能因此把整个 `/data/adb/minis` 删除；这里还存放现役 rootfs 及合同明确保留的迁移源。

源码位置：[rootfs readiness/修复](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:248)、[健康判定](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:245)、[旧进程清理范围](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:469)。没有本次手机进程快照，设备残留是否仍存在以附件中的当时观察为限。

## 发现详情

下文 `APP/` 指本地 `src/android/app/src/main/java/com/openminis/app/`，`UP/` 指固定上游检出的同一路径；没有展开为链接的源码简写按该根目录定位。结构项原编号 S01–S09 对应第一份报告的 1–9。

### 架构、生命周期与工程边界

<a id="f01"></a>

#### F01 / 结构 — Session 删除没有统一收尾，两个缓存各清一半

证据：

- [debug/HeadlessChatRunner.kt:46-80](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:46) 另存 `sessionId → ViewModelProvider`，provider 持有 `ChatViewModelStore` 的 store。
- [ui/chat/ChatViewModelStore.kt:75](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt:75) 的 `release()` 清 store、别名并触发 `onCleared()`。
- [debug/HeadlessChatRunner.kt:766](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:766) 的 `forget()` 只移除 provider，没有清 store。
- [ui/sessions/SessionListViewModel.kt:367-388](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:367) 的删除清 store，没有调用 `forget()`。
- [debug/ChatMutationMethods.kt:411-429](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt:411) 的删除取消流并调用 `forget()`，没有清 store。
- [data/repository/ChatRepository.kt:148](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:148) 删除数据库记录和 backing，不负责上述两个缓存。
- [ui/chat/ChatViewModel.kt:12100](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12100) 的 `onCleared()` 还承担语音与 session shell 清理。

影响：UI 删除之后，headless provider 仍指向已经清空的旧 store，后续访问可能重新生成一个不在全局 store 表中的 VM；RPC 删除之后，原 store 和 VM 仍被全局缓存保留，也没有通过该路径触发 `onCleared()`。这是代码可确认的释放不一致，具体设备上的资源滞留表现尚未验证。

整理方向：减少 provider 缓存，统一删除时的执行退出、store、事件/徽标和 workspace 收尾。不能只给每个删除入口继续补一串不同的清理调用。

<a id="f02"></a>

#### F02 / 结构 — Agent 核心依赖 UI，产品入口反向依赖 debug

证据：

- [agent/AgentRunner.kt:5-9,25-26,51](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt:5) 依赖 `debug.HeadlessChatRunner`、`ui.chat.ChatViewModel`、`InputAttachment` 和会话事件类型，并向调用方公开 VM。
- [debug/HeadlessChatRunner.kt:40](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:40) 已标记为 deprecated，但仍是产品执行链中的实现。
- [ui/chat/ChatViewModel.kt:7694](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7694) 的 `runAgentLoop` 延续至下一方法 `:9719`，约 2,025 行；压缩、持久化、工具与语音也在同一类中。
- [scheduled/ScheduledAgentRunner.kt:223,231](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:223)，[tools/BotDelegationCoordinator.kt:391](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:391)，[tools/BotWakeDispatcher.kt:265](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:265) 和子 Agent 路径仍经这条链执行。

影响：改变聊天呈现、VM 生命周期和内部状态会影响无人值守任务；`AgentRunner` 虽然提供入口，却没有切断调用方对 UI 的依赖。`AgentTurnHandle` 已提供逐请求结果和取消能力，这是可以复用的现有边界。

整理方向：先让产品 runner 自己拥有 headless 入口实现，debug 调它；再逐步把执行状态、Loop 和压缩从 VM 移到 `agent`。保持 UI 与后台使用同一个执行实例，避免另造 Agent Loop 或数据库。

<a id="f03"></a>

#### F03 / 结构 — 工具派发尚未收口，校验、超时和权限散在不同层

证据：

- [tools/AgentTools.kt:45-110](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:45) 同时组装旧工具定义和注册表定义，再通过 canonical 名去重。
- [ui/chat/ChatViewModel.kt:294-347](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:294) 持有工具 preflight，并调用 `ToolCallValidator`。
- [ui/chat/ChatViewModel.kt:9761](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9761) 持有 intent checkpoint 和声明式 timeout。
- [ui/chat/ChatViewModel.kt:9813-9860](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9813) 注册表工具走 `ToolExecutor`，浏览器/记忆仍走旧 `when`，READ_ONLY 的记忆写拒绝单独写在这里。
- [tools/runtime/ToolRegistry.kt:83](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:83) 的 `ToolExecutor` 负责另一组权限、审批和输出预算，却没有统一调用上述 preflight/声明式 timeout。
- [mcp/server/MCPServer.kt:324](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/server/MCPServer.kt:324) 直接进 `ToolExecutor`。

影响：维护一个工具需要核对 schema、注册、别名、执行入口、权限表、脱敏表、超时与输出预算。旧分支没有经过注册表的统一输出预算；各工具自己的限额仍可能有效，不能据此断言输出一定无界。MCP 与本地入口的策略不同是合理的，但通用校验和执行预算落在 VM 上，难以核对两条链的一致性。

整理方向：让 browser/memory 进入现有注册表，保留别名和会话资源；将通用执行校验/预算放到共享执行入口，继续保留 MCP token、local-only、offload 权限等各自边界。不要合并不同信任域的授权。

<a id="f04"></a>

#### F04 / 结构 — 生产树保留了没有生产调用方的实现和仅测试使用的能力

按符号查找，并排查 Manifest、资源、assets、native 和 ProGuard 中的显式入口后，下列对象没有找到生产调用方：

| 对象 | 行数 | 现状 |
|---|---:|---|
| `runtime/migration/OwnershipMigrationManager` | 132 | 只有测试引用；注释描述 FD/WAL 六阶段迁移，实际生产迁移在 `UbuntuKernel` 中 |
| `tools/BrowserUseTool` | 162 | 另一份 browser schema 和 Provider 格式转换；现役定义在 `AgentTools.browserUseDefinition()` |
| `remote/ModelSelectionResolver` | 71 | 只有测试引用，注释却宣称 App/Web 共用 |
| `remote/DshReasoningCatalog` | 71 | 只有测试引用 |
| `tools/internal/ShellOutputTruncator` | 61 | 只有测试引用；不能用它的测试证明现役 shell 输出经过它 |

这五个文件合计 497 行。还有 CalendarManager、HealthManager、BackgroundInterruptionTracker、AntigravityModelsApi 等无直接生产引用的候选，本轮没有逐个核对其完整替代实现，不列为直接删除结论。

整理方向：先判定能力是否仍有产品需求；无需求的实现连同失去意义的旧测试一起退役，有需求的能力要接入现役调用链。不能把测试通过等同于功能已接入；也不能仅凭文本搜索删除 Manifest、服务加载或框架反射入口。

<a id="f05"></a>

#### F05 / 结构 — 为未来语义保留的透传 Provider 增加了空层

[tools/runtime/ToolProvider.kt:66](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolProvider.kt:66) 的 `PrefixProvider` 没有额外执行行为，默认只是 `next()`。[MinisApp.kt:808-832](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:808) 注册 android、root、core、mcp、skill 五个透传 Provider，注释写着将来补语义；现有实质行为主要在 `LinuxProvider`，文件工具免 runtime readiness、执行工具尝试恢复。

整理方向：先移除五个纯透传注册；再判断 Linux readiness 是否可以直接复用现有执行入口，避免为了一个行为保留通用路由框架。Linux 文件访问不依赖 Ubuntu 就绪的语义必须保留。

<a id="f06"></a>

#### F06 / 结构 — 分包存在，职责和初始化权威仍互相穿透

- [MinisApp.kt:434-498](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:434) 串联数据库、Repository、Bot、MCP 和统一初始化标志，后续继续注册数百行工具/CLI、监听器和服务。依赖顺序主要由 `onCreate()` 的行序表达。
- [sandbox/RootfsManager.kt:41-85](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:41) 调用 UbuntuKernel；反过来 [runtime/ubuntu/UbuntuKernel.kt:203,218,278](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:203) 依赖 RootfsManager 的静态探测、解析和修复脚本，形成双向依赖。
- ProviderRepository 同时管理存储、旧组迁移、模型目录、语音模板、凭证和备份；`ProviderDatabase` 与 SharedPreferences JSON 镜像有明确历史兼容用途，不能直接判成重复数据源并删除。

整理方向：先把 rootfs 底层探测/解析放回实际所属的 runtime 层，RootfsManager 保持面向 UI 的管理职责；把 Application 的已有注册逻辑按现实子系统收拢成明确初始化函数。ProviderRepository 暂按真正变化的职责逐块处理，不新增 DI 框架、factory 或通用 provider 系统。

<a id="f07"></a>

#### F07 / 结构 — 当前验证不能充分保护跨入口重构

`src/androidTest` 有 13 个 Kotlin 文件，其中包括真实 VM/Room 配合受控 Provider 的 `BotTurnExecutionTest`、备份往返和安全文件访问；因此不是完全没有设备侧测试。

但 [.github/workflows/ci.yml:229-270](/home/jiale/projects/minis-for-android/.github/workflows/ci.yml:229) 的 Android 检查是 JVM tests、Debug/Release lint；另有 Debug/Release 构建、payload、JNI 和 16 KiB 校验，没有配置 connected/managed-device instrumentation 或完整产品端到端运行。设备测试文件数也不能当作完整端到端覆盖数量。

影响：检查对静态规则、纯逻辑和打包有价值，却不能证明定时任务、UI、Bot、会话删除与真实 Root shell 联动仍正常。大规模抽取 VM 时风险尤其明显。

整理方向：后续每一批整理用真实入口做端到端验收，留下可重复的场景、日志和结果工件。按照用户工作规则，本轮及后续编码不新增单元测试；现有测试只作为现状证据。

<a id="f08"></a>

#### F08 / 结构 — “当前缺口”里的实现状态存在滞后

[docs/contracts/06-CURRENT-GAPS.md](/home/jiale/projects/minis-for-android/docs/contracts/06-CURRENT-GAPS.md) 的 Guest CLI 小节仍写 `MCPRepository.mcpPromptFragment()` 引导模型运行缺失的 `minis-mcp-cli`。

当前 [data/repository/MCPRepository.kt:501-515](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MCPRepository.kt:501) 已改为直接调用 `mcp_<server>_<tool>`，不再要求 shell CLI。因此“提示词引导错误”已闭合；“没有独立 Guest MCP CLI”仍是另一项能力事实，不能把两者一起删除或一起算成未修复。

整理方向：整理实现时同步移除过时判断；不要把历史审计/移植计划中的“已设计、已测试”自动算成“生产已接入”。本报告不直接改合同，避免混入另一项状态整理。

### Direct Ubuntu、Root 边界及旧运行时迁移

<a id="f09"></a>

#### F09 / 结构 — Rootfs 已完成数据分离，界面仍宣称创建备份

证据：

- [sandbox/RootfsManager.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:70) 的 `reset(keepUserData)` 两种参数均只重置 rootfs，保留 App-owned 用户数据并返回 `null`；参数只影响日志。
- [sandbox/RootfsManager.kt:89](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:89) 的 `restoreUserData()` 只记日志。
- [ui/sandbox/RootfsManagementViewModel.kt:139-163](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementViewModel.kt:139) 仍按 `keepUserData=true` 显示正在备份、备份已创建；但 `backupDir` 实际为 `null`。
- [ui/sandbox/RootfsManagementScreen.kt:309,332](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementScreen.kt:309) 仍提供两种 reset 调用，`:220` 保留备份恢复 UI。
- [res/values-zh/strings.xml:2019](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values-zh/strings.xml:2019) 明确写着“rootfs 已重置，并已创建备份”。

影响：用户看到的操作语义与实现不一致；现役 reset 路径不会产生界面所等待的备份。这里应收掉旧 rootfs 内用户数据时代的交互和空实现，保持合同中的用户数据分离，不应为了适配旧 UI 恢复旧存储架构。

<a id="f10"></a>

#### F10 / P1 — 结构化 Root 参数仍能承载通用 shell 脚本

[RootShellHandler](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/RootShellHandler.kt:43) 把 `tool + args` 交给 [PrivilegedCommandRunner](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt:328)。校验只有 basename、NUL、数量和单参数长度；可信路径解析允许 `/system/bin/sh`，没有拒绝 shell 的 `-c` 或脚本文件执行模式。

因此合法参数 `{"tool":"sh","args":["-c","id"]}` 会构造成以 Root 执行的 shell 脚本。`su`、`toybox` 等分发器也没有专门的语义限制。风险分类把它们标为 `ROOT_SETUP`，但 `run()` 只是记录风险，然后调用 [DirectRootRunner.runArgv](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt:54)。正确 shell quoting 防止参数意外注入，却不能阻止显式选择解释器执行代码。

这是与“禁止 raw command / 通用 Root 入口”的合同冲突，**不是旧 minisd 回归**。local-only、超时、输出和进程组边界仍存在，不能误报成远程 MCP 可以直接调用，也不能误报为无限制执行。若产品实际意图是允许本地 Agent 执行 `sh -c`，需要明确修改长期边界；不能靠“参数是数组”宣称没有通用 shell 能力。

设备验收应保留允许的受信单工具成功结果，以及 `sh -c`、shell 脚本路径、解释器/分发器变体、远程 MCP 调用等拒绝结果。此次未运行这些命令。

<a id="f11"></a>

#### F11 / P2 — pip 镜像仍写不被默认读取的旧位置

[MirrorCategory.PIP](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt:92) 写 `/etc/pip/pip.conf`，RootfsManager whitelist 也固定这个路径。Unix pip 默认全局位置是 `/etc/pip.conf` 及 XDG 配置目录下的文件，不是这个位置；现役 launcher 未设置 `PIP_CONFIG_FILE` 或 `XDG_CONFIG_DIRS` 把它接入。依据 [pip 官方配置说明](https://pip.pypa.io/en/stable/topics/configuration/#location)。

现有打包 rootfs 甚至没有 `etc/pip` 目录，写入脚本要求父目录已存在，所以默认树上的应用动作会失败。即使人为建出目录，默认 pip 也不读取它。UI 先持久化并显示开启，失败只写 Log，不能代表镜像已生效。上游 Android 同样有这个路径错误；本地改为 Ubuntu 后继续保留。

设备 E2E：在默认 rootfs 选择 pip 镜像，核对 UI、文件及 `python3 -m pip config debug`；同时覆盖 Root 拒绝，不能显示应用成功。

<a id="f12"></a>

#### F12 / P2 — npm 镜像仍写 `/root/.npmrc`，guest HOME 已是 `/home/minis`

[MirrorCategory.NPM](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt:93) 写 rootfs 的 `root/.npmrc`；[guest 环境](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:609) 固定 `HOME=/home/minis`，该 HOME 还是 App-owned bind。没有设置 `NPM_CONFIG_USERCONFIG` 把 `/root/.npmrc` 指定为配置。

npm 的用户配置默认跟随 `$HOME/.npmrc`，见 [npm 官方说明](https://docs.npmjs.com/cli/v11/configuring-npm/npmrc/#per-user-config-file)。因此这个设置不会作为现役 guest 的默认用户镜像配置。它继承自上游 PRoot 时代 root 身份运行的布局，属于迁移时未改完的行为，不能只删旧注释了事。

设备 E2E：在用户目录安装并运行 npm，设置镜像后记录 `npm config get registry`、`npm config get userconfig`，重启 guest 后复核。

<a id="f13"></a>

#### F13 / P2 — 删掉旧内核时，镜像配置的恢复调用一起丢了

上游 `sandbox/PRootKernel.kt:134–135` 在启动时调用 `applyAllActiveMirrors` 和 `autoDetectOnceIfNeeded`。本地保留[两个方法](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt:381)，但全生产源码只有声明，无调用；`rootfs.freshInstall` 也没有现役写入者。

当前 rootfs reset/repair 会恢复默认配置，用户的镜像偏好仍在 SharedPreferences；下一次启动却没有把偏好应用回来。结果是设置页显示自定义镜像有效，guest 实际用默认源。新安装的“自动测速并应用”路径也不再触发。该问题独立于 F11/F12，APT 使用正确配置路径同样受影响。

设备 E2E：先选择 APT 镜像，再 reset/repair，记录偏好、实际 sources 文件及后续 apt 请求目标的一致性。

<a id="f14"></a>

#### F14 / P1 — 旧 Root-owned 数据迁移晚于仓库扫描，会先删技能元数据

[MinisApp 初始化](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:441) 只先初始化新 App-owned 路径，然后创建 SkillRepository。构造器异步扫描新目录；[loadAll](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1821) 对不在磁盘上的非 bundled 技能删除 DB 行与 `session_skill_overrides`。真正的旧文件复制要等 [UbuntuKernel.ensureReady](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:182) 才执行。

可达触发：同一安装升级，旧 DB 有技能记录、文件仍在 `/data/adb/minis/skills`，新 App-owned skills 目录为空；先打开 App/设置页，再启动 guest。新目录扫描是成功的空结果，因而不会走“文件 API 不可用就保留 DB”的保护。复制发生前，旧技能开关、使用统计和会话覆盖已可能被删；之后重扫只能按磁盘自动发现，无法恢复原来的 registry 信息。

迁移锁只约束 runtime，未与 SkillRepository 的 mutation lock 或 WorkspaceFileClient 共用。用户在 readiness 前保存的 memory/SOUL/技能文件，也可被后续 `cp -a` 同名覆盖。SoulStore 在启动时提前 seed/cache，复制完成后没有 refresh 通知，可能继续用迁移前缓存。

这不是要求给迁移补一个合同未承诺的完整 WAL；根因是 **仓库已把目标树当成最终数据，却还没完成一次性迁移**。

设备 E2E：旧布局准备自定义技能、禁用开关、会话覆盖、SOUL 与 memory；升级后先进入设置，再启动 guest。保留 DB 前后导出、目录摘要及 prompt 中身份/技能内容；另覆盖迁移失败时 UI 是否仍允许覆盖目标数据。

<a id="f15"></a>

#### F15 / P2 — 迁移复制进去的符号链接，会使未完成迁移的重试永久拒绝

[buildLegacyMigrationCommand](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:724) 先拒绝目标树已有 symlink，再用 `cp -a` 复制旧源，源树却没有对等检查，因而能把旧 HOME/工具目录中的链接复制进目标。

触发条件：前一个源含 symlink 且已复制；后一个目录复制失败，或最终 marker 写入失败。marker 尚未存在，下一次 readiness 重试时，前一个目标树已含上次复制的链接，于是直接返回 75，再也到不了后续源。这是具体可达的失败恢复死结；没有说所有迁移一定失败，也没有说 `chown -R` 一定跟随链接。

设备 E2E：旧 HOME 放一个普通相对链接，另一个迁移目标注入可恢复失败；修复失败条件后再次启动，核对迁移能否完成及链接策略是否一致。保存两轮返回码与 marker 状态。

<a id="f16"></a>

#### F16 / P2 — guest 默认 PATH 不包括持久用户工具目录

[UbuntuKernel 的 PATH](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:610) 没有 `/home/minis/.local/bin`；[rootfs login profile](/home/jiale/projects/minis-for-android/scripts/build-ubuntu-rootfs.sh:128) 也用固定 PATH，只多 `/opt/bin`。非交互 shell 使用 `--noprofile --norc`，无法靠用户 profile 补齐。

合同允许在 App-owned HOME 安装用户工具，附件也实测了 pip 用户安装成功与 `.local/bin` 存在。但有 console entry point 的包安装到这里后，后续默认 shell 按工具名找不到，除非每次显式 PATH/绝对路径。这不是权限不足，也不能用 Root apt 安装代替解决用户工具发现问题。venv 手动激活、用户显式覆盖环境的场景仍可工作。

E2E：安装带 console entry point 的用户包，退出并新建非交互与 PTY shell，验证工具发现及跨会话持久性。

### Agent 回合、子代理、例程和后台任务

<a id="f17"></a>

#### F17 / P1 — 公开工具别名没有补齐合并 handler 的 action

完整链路：

1. [tools/AgentTools.kt:81](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:81) 添加 `get_goal`、`create_goal`、`update_goal`；`:86` 起添加三个 jobs 工具。`:99` 起排除已经被这些旧名代表的 canonical schema。
2. [tools/AgentTools.kt:261](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:261) 起的 goal schema、[tools/JobTools.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/JobTools.kt:44) 起的 jobs schema 都没有 `action` 参数。
3. [MinisApp.kt:784,801](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:784) 把这六个名字注册为 `agent.goal` 或 `system.jobs` 的别名。
4. [ui/chat/ChatViewModel.kt:9813](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9813) 起先转换 canonical 名，再把原样参数交给 `ToolExecutor`；[tools/runtime/ToolRegistry.kt:169](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:169) 起原样调用 handler。
5. [tools/runtime/ToolRegistry.kt:420,532](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:420) 的两个 handler 从参数读取 `action`，传给 `GoalTools.execute` / `JobTools.execute`。未传时是空串，落入 `unknown action`。

因此模型严格按公开定义调用以下输入也会失败：

```json
{"name":"get_goal","arguments":{"tool_title":"读取当前目标"}}
{"name":"job_list","arguments":{"tool_title":"查看后台任务"}}
```

其他四个旧名有相同错误。直接调用 canonical 工具并补 `action` 可以绕过，但不能要求模型猜一个未公开参数。

修复方向：在旧名转换时保留动作信息，或直接公布一致的 canonical schema 并处理已有别名。复用现有执行器，避免再建一套派发。已有 `ToolMigrationAliasTest` 只检查文件工具别名，不证明这六个工具的公开输入能执行。

<a id="f18"></a>

#### F18 / P1 — retry/rerun 仍按共享忙闲标志判断本次结果

普通 `prompt()` 已使用 `AgentTurnHandle`，但两个重跑入口没有采用相同契约。

##### retry 可以拒绝启动后返回 Completed 和旧回答

- [debug/HeadlessChatRunner.kt:287](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:287) 调用 `vm.retryFromMessage(targetMsgId)`，忽略返回 Boolean。
- 同函数随后等 `isStreaming == false`，只用是否等待超时决定 `Completed` / `Timeout`。
- 返回文本取整个会话最后一条 assistant，没有限制到本次请求。
- [ui/chat/ChatViewModel.kt:5702](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5702) 起可以因忙碌、消息不在内存、provider 缺失或 `canRetryMessage` 不满足而返回 false。
- [ui/chat/ChatViewModel.kt:163](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:163) 的 retry 条件要求用户消息正文非空，而发送入口 `:6664` 允许仅附件消息。

一个具体场景是正文为空的附件消息已有回答，headless retry 被拒绝，却马上返回 `Completed` 和原回答。模型报错或用户取消也能令忙闲标志变 false，从而被写成 `Completed`。忙碌时拒绝本次 retry，还可能等待另一个已经运行的回合。返回的 `deletedMessageCount` 在调用前就算好了，即使没有接受重试也不是实际删除数量。

##### rerun 超时没有取消，且默认声称 stream 已退出

- [debug/HeadlessChatRunner.kt:331](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:331) 起的 `rerunFromToolBlock()` 检查是否接受，但完成状态仍只看共享 `isStreaming`。
- `:380` 附近超时只令 `finished=false`，没有像 retry 一样执行 `cancelStream()`、等待退出。
- 返回的 `PromptResult` 没传 `streamExited`，使用 `:786` 的默认 true；[agent/AgentRunner.kt:161,177](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt:161) 沿用该值。

调用者会拿到 `Timeout`、`streamExited=true`，但工具或模型仍可继续运行。后续自动化据此删除会话或再次执行，会与未退出的回合冲突。

修复方向：复用已有请求级 `AgentTurnHandle`，以被接受的那次请求返回状态与消息边界；超时取消本次请求并等待实际退出。不能简单把全局忙闲标志换成另一个全局标志。

<a id="f19"></a>

#### F19 / P1 — Ralph 正常回合必被判为失败

- [tools/RalphTool.kt:148](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt:148) 起创建 child session 并等待 `AgentRunner.prompt()`。
- `:165` 要求 `result.status == "completed"`，大小写敏感。
- [debug/HeadlessChatRunner.kt:228](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:228) 起的成功状态是 `"Completed"`；`AgentRunner` 映射不改大小写。

即使 child 成功返回合法的 `{"status":"complete",...}` 报告，第一回合也会在 JSON 解析前被拒绝，父工具返回 invalid report。

修复这个入口后仍需要验收两处潜伏问题：

- 每轮创建新 session，提示词 [RalphTool.kt:179](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt:179) 却宣称 `/var/minis/workspace` 是跨轮共享的权威目录。[runtime/ubuntu/UbuntuKernel.kt:534,548](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:534) 为每个 session 绑定独立 workspace；[scripts/build-ubuntu-rootfs.sh:110](/home/jiale/projects/minis-for-android/scripts/build-ubuntu-rootfs.sh:110) 的该路径只是 `/workspace` 别名。因此下一轮不会通过这个路径看到上一轮文件。应明确传递产物或使用符合合同的共享区，不恢复全局 session workspace。
- [RalphTool.kt:193](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt:193) 用最后一个 `{` 到最后一个 `}` 截 JSON。合法报告字符串中含 `{`，如 evidence 为 `JSON starts with {`，也会被错误截取。报告格式应由结构化解析可靠识别。

大小写错误是当前直接阻断点；后两项不声称已在现役多轮运行中触发。

<a id="f20"></a>

#### F20 / P1 — 父任务取消没有传到旧模式 subagent / Ralph 的子回合

证据：

- [tools/SubagentTool.kt:78,90](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/SubagentTool.kt:78)、[tools/RalphTool.kt:148,155](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt:148) 创建独立 session 并以 `wait=true` 调用 runner。
- 两个工具捕获 `CancellationException` 后仅重抛，没有取消该 child session。
- [debug/HeadlessChatRunner.kt:212](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:212) 起等待 `request.result.await()`；只有内部 timeout 返回 null 时才在 `:215` 调用 `request.cancel()`，没有处理等待调用者被取消的清理。
- [agent/AgentTurnHandle.kt:17](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentTurnHandle.kt:17) 使用不带父 Job 的 `CompletableDeferred`；取消等待者不会自动取消绑定的 worker。
- [ui/chat/ChatViewModel.kt:6762,6962](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6762) 的 child worker 在该 child VM 自己的 `viewModelScope` 中创建和绑定，属于独立生命周期。
- 父 `cancelStream()` 只停止本 session 的 worker、执行命令与界面状态，没有上述独立 child 的取消链。

因此停止正在等待旧模式 subagent 或 Ralph 的父会话，只会中断等待；child 已被接受的模型/工具工作可以继续。此项限定为这些现有等待路径，不推断新 roster subagent 或所有 Bot 路径有相同缺陷。

修复方向：明确 `wait=true` 的工作所有权，外部取消应取消本次拥有的 child 请求并完成收尾；明确的 `wait=false` 后台运行则保留其既有语义。已有请求 handle 可复用。

<a id="f21"></a>

#### F21 / P2 — 例程将非成功终态写为成功

- [scheduled/ScheduledAgentRunner.kt:158,178](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:158) 两条运行路径均用 `status != "Error" && status != "Timeout"` 算 `ok`。
- [debug/HeadlessChatRunner.kt:204,228](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:204) 起还返回 `Busy`、`Cancelled`、`NeedsAttention`、`Dropped`。
- [scheduled/ScheduledTaskManager.kt:155](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:155) 把该 Boolean 持久化到 `ScheduledRun.ok`。

例如例程追加到正在运行的 session，runner 返回 `Busy`，根本没有启动本次任务，历史仍记 `ok=true`。取消、中途需要人工处理或压缩等原因拒绝请求，也按同样规则被记成功。

修复方向：只把确认完成记为成功，保存明确的拒绝、取消与需处理原因；同步和后台路径使用相同分类，避免两份条件继续漂移。

<a id="f22"></a>

#### F22 / P2 — 通用 jobs 取消没有停止实际 logcat watch

- [tools/JobRegistry.kt:109](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/JobRegistry.kt:109) 的 `kill()` 只把记录改成 `KILLED`，没有关联 Coroutine Job 或取消回调。
- [tools/JobTools.kt:136](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/JobTools.kt:136) 调用它并返回 `cancellation-requested`；[debug/AgentRpcMethods.kt:137](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/AgentRpcMethods.kt:137) 的 RPC 也使用同一方法。
- 生产源码中 `JobRegistry.start()` 的现有调用来自 [tools/android/AndroidLogManager.kt:109](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:109)。
- [AndroidLogManager.kt:112](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:112) 起轮询 logcat，既不检查 registry 终态，也不因上述 `kill()` 取消；`:126` 仍可追加输出。
- [AndroidLogManager.kt:164](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:164) 的专用 `stop()` 才调用 `watches.remove(jobId)?.cancel()`。

使用通用 `system.jobs` 的 `action=job_kill` 或 RPC 取消，可出现 job 已为 KILLED、watch 仍捕获日志直到最长 300 秒窗口结束的情况。用 canonical 路径复现可避开 F17 的另一个入口故障。

修复方向：让通用取消入口调用真正的取消机制，并用实际 worker 退出作为收尾依据，保持重复取消幂等。

<a id="f23"></a>

#### F23 / P2 — Bot 启动恢复的 32 条批次没有继续调度

- [tools/BotDelegationCoordinator.kt:64](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:64) 只读取 `listUndeliveredTerminal(MAX_DISPATCH_BATCH)` 一次。
- `:67,296` 只调用一次 `dispatchAll()`，后者只扫描 `listDispatchable(MAX_DISPATCH_BATCH)`。
- `:539` 批次上限为 32。
- [data/db/BotDelegationDao.kt:41,47](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotDelegationDao.kt:41) 查询确实带 `LIMIT :limit`。
- 没有分页排空，也没有每批结束后继续 `dispatchAll()`；其他来源回合结束只调度该来源的任务。

触发条件：进程重启时，存在超过 32 条已 settled 且可调度的委派，或者超过 32 条已终结但未送达的回执。第 33 条以后不会通过这次恢复继续处理，可能等到另一次重启或相关来源再次触发。单回合 fanout 限制不等于跨 session、跨回合 backlog 的全局上限。

修复方向：使用有界分页或批次完成后的后续调度，确保符合条件的 DB 记录最终被处理；保留事务领取、回执幂等和 outcome-unknown 规则，不能靠一次性放大 LIMIT 假装排空。

<a id="f24"></a>

#### F24 / P1 — 回调等待造成排队取消自等待和队列停顿

本地路径：

1. [APP/agent/subagents/SubAgentRuntime.kt:278](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:278) 取消 QUEUED job 时，直接 suspend 调用 `complete()`。
2. `:229` 的 `complete()` 先写终态，再同步等待 `port.deliverToParent()`，之后才完成 waiter、晋升下一个排队任务。
3. [AppSubAgentPort.kt:140](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt:140) 的回调先 `AgentRunner.waitForSettle(parentSessionId, 10 分钟)`，父会话仍忙时拒绝提交，再重试；最多 20 次。
4. [agent/AgentRunner.kt:121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt:121) 等待的就是父 VM 的 `isStreaming == false`。当前父回合正在等这个 cancel 工具返回。

具体触发：前三个 background 子代理占满槽位，第四个排队，父模型调用 `subagent action=cancel` 取消第四个。该 queued job 的 `wait=false`，因此取消调用要等回执投递；回执又要等包含这个调用的父回合结束。没有用户停止或其他外部打断时，会等待到重试预算耗尽。该工具 schema 的 `timeoutMs` 仍为 null，不能指望统一工具超时解环。

同一顺序还影响正常完成：父会话长时间运行时，已经完成的 child 虽在 registry 中释放槽位，却延后 `promoteNext()`；没有新提交来占槽时，原有队列可在空闲槽位下继续等待。

上游 [AgentJobRegistry.kt:577](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/jobs/AgentJobRegistry.kt#L577) 在 finish 后排队晋升；[MinisApp.kt:506](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L506) 用独立 coroutine 派发回调，finish 不等待父回合处理它。

修复方向：完成、取消与槽位晋升不能以父回执送达为前置条件；回执单独排队，保留去重、失败与重试状态。不能只缩短等待时间把自等待改成更快的回执丢失。

<a id="f25"></a>

#### F25 / P1 — 后台调用复用了用户附件和编辑状态

本地 [APP/debug/HeadlessChatRunner.kt:206](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:206) 把显式附件加入 VM 后调用 `submitPrompt(text)`；即使调用者传 `attachments=emptyList()`，也不隔离 VM 中用户原先暂存的附件。

[ui/chat/ChatViewModel.kt:6641](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6641) 的 submit 进入同一发送函数；`:6730` 读取全部 `_attachments` 并清空，`:6758` 读取 `_editingMessageId`，`:6769` 调用 `truncateBeforeEdit()`。这两步并不区分用户发送和后台请求。

具体触发：用户正准备一个带附件的草稿，或正在编辑以前的消息；后台子代理此时完成，[AppSubAgentPort.kt:146](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt:146) 向同一父会话提交回执。附件会随回执发出并从草稿移除；编辑状态则可以令历史被截断并写入回执，而非用户原本准备的编辑文本。同样问题可发生于例程追加和 RPC 等既有 session 的 headless 入口。

上游 [ChatViewModel.kt:9198](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L9198)、[:9226](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L9226) 的 Android idle headless 路径也读取这些状态。上游对 programmatic 文本草稿和忙碌队列已有隔离处理，但不能把它们当作 idle 附件/编辑状态也已经隔离的证明。

修复方向：请求显式拥有文本和附件，后台请求不消费 composer 编辑状态；保持一个执行 funnel 和现有权限规则，但区分输入所有权。不得靠事后恢复 UI 附件来掩盖已经发给模型的内容或已提交的历史删除。

### MCP 连接、身份、授权与并发

<a id="f26"></a>

#### F26 / P1 — MCP 会话开关只影响提示词，未控制调用

证据：

- [ui/chat/SessionMcpsSheet.kt:60,96](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMcpsSheet.kt:60) 读取会话有效状态，并写 `setSessionOverride`。
- [data/repository/MCPRepository.kt:349,369](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MCPRepository.kt:349) 实现 override；`:480` 用它过滤提示词中的服务器名单。
- [mcp/client/MCPProvider.kt:88](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:88) 只按服务器的全局 `enabled` 连接，`:147` 起把工具放进全局注册表。
- [ui/chat/ChatViewModel.kt:1060](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1060) 的工具配置没有 MCP 会话过滤；[tools/AgentTools.kt:104](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:104) 起把注册表工具加入模型工具集。
- [tools/runtime/MCPToolHandler.kt:36](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/MCPToolHandler.kt:36) 起执行直接调用远端；`ToolExecutor` 与 `ToolPermissionManager` 没有会话 MCP override 检查。
- 生产源码中的 MCP `isEnabledForSession` 调用仅见于名单、提示词与设置界面，没有执行授权检查。

触发条件：全局启用服务器 S，在会话 A 的 MCP sheet 中关闭 S。A 的提示词名单会减少，但常规 FULL 工具集仍含 S 的工具；其他权限允许时，调用仍能到达 S。已有的权限/审批规则仍然存在，本问题是这层会话开关没有落实。

修复方向：按当前会话筛选工具，并在执行前再次检查服务器的原始配置 ID，防止工具列表缓存或历史调用绕过。会话 B 的独立状态也必须保持。反方向的“全局关闭、会话启用”目前也不会因此建立连接，应单独明确该交互的真实语义。

<a id="f27"></a>

#### F27 / P1 — MCP 名称清洗造成短名称碰撞和错误派发

存在两层确定的碰撞，均没有拒绝重复映射：

1. [mcp/client/MCPProvider.kt:196](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:196) 把服务器 ID 的不允许字符替换为 `_`。配置 ID `docs one` 与 `docs_one` 都变成 `docs_one`；同名远端工具会写入相同 canonical name。
2. [data/model/AgentToolDefinition.kt:51](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/AgentToolDefinition.kt:51) 把 wire name 的点等字符替换为 `_`；仅长名称或清洗后全为标点的名称加摘要。`mcp.a.b.search` 与 `mcp.a_b.search` 都得到 `mcp_a_b_search`。

[tools/runtime/ToolRegistry.kt:20](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:20) 起对 handler 和 alias 直接赋值，最后注册者覆盖先注册者。第二种场景可以同时向模型公布两个相同 wire name；Provider 可能拒绝重复 schema，而若按该名字调用，则 alias 指向最后注册的服务器。即使不同服务器 ID 不碰撞，同一服务器的 `read.item` 和 `read_item` 也会有 wire 碰撞。

这些输入并未被本项目拒绝：[data/repository/MCPRepository.kt:303](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MCPRepository.kt:303) 的 ID 入口只检查非空；[mcp/client/MCPClientCodec.kt:127](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientCodec.kt:127) 的工具名入口只检查非空和长度。这里不是讨论哈希碰撞概率，而是短名称替换的确定碰撞。

修复方向：保持服务器原始身份，生成可区分的 wire 标识；注册表发现 canonical / alias 冲突必须拒绝，不能静默覆盖。现有 apiName 测试验证了长名摘要，没有覆盖这些短名碰撞。

<a id="f28"></a>

#### F28 / P2 — 注册表热更新存在无同步读取风险

- [tools/runtime/ToolRegistry.kt:13](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:13) 的 handlers / aliases 是普通 `LinkedHashMap`。
- `register()`、`unregister()` 会增删；`canonicalName()`、`aliasesFor()`、`definitions()` 会遍历，无同步或不可变快照。
- [mcp/client/MCPProvider.kt:54,134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:54) 在 IO scope 中连接并提交注册，reload 也会注销工具。
- MCPProvider 自己的 `registryLock` 只被 MCP 管理代码持有，VM 读取工具列表和其他注册者不持有它。
- [MinisApp.kt:482](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:482) 启动 MCP reload 时，后续原生工具注册尚未全部完成。

读写路径可以重叠，遍历可能遇到 `ConcurrentModificationException`，也可能取得同一轮更新中混合的新旧映射。未做运行压力测试，因此只认定同步缺口，不声称已有现场崩溃证据。

修复方向：把整个 registry 的读写放进同一同步边界，或发布完整不可变快照；单独锁 MCP 写入、仅换成并发 map，都不能自动保证 handlers 与 aliases 的一致快照。

<a id="f29"></a>

#### F29 / P1 — STDIO MCP 从 guest 执行变成宿主 ProcessBuilder

本地链路：[APP/mcp/client/MCPProvider.kt:126](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:126) 创建 `MCPClientSession(cfg)`；[MCPClientSession.kt:65](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientSession.kt:65) 创建 STDIO transport；[MCPStdioTransport.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt:44) 直接 `ProcessBuilder(listOf(command) + args).start()`。没有经过 `ExecutionCoordinator`、Ubuntu launcher，也没有 guest 根目录与 PATH。

上游在 guest 的 [daemon.py:65](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/daemon.py#L65) 启动子进程，命令、库和依赖位于 Linux guest 环境。本地设置仍提供 STDIO，命令 placeholder 仍为 `npx`（[src/android/app/src/main/res/values/strings.xml:195](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values/strings.xml:195)）。

具体触发：在 Ubuntu 中安装 Python/npm MCP server，配置 `/usr/bin/python3` 或 `npx`。原生 transport 在 Android 宿主查找该文件/命令，不会进入 Ubuntu；guest 安装成功并不能使该连接成功。宿主恰好可执行的 Android 二进制不受这个例子的影响。

修复方向：明确 STDIO 服务的受支持执行域，并接到本项目已有 App-owned guest 生命周期、输入输出与进程清理边界。不能因为上游用 PRoot 就恢复 PRoot，也不能用任意 Root 进程来替代 App UID guest 服务。

<a id="f30"></a>

#### F30 / P1 — 设置和提示词承诺的环境变量展开没有实现

[APP/data/repository/MCPRepository.kt:519](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MCPRepository.kt:519) 的提示词要求在 URL/header/env 使用 `$$VARNAME`，设置页的变量选择器也生成该形式。实际链路却把配置原样传给 transport：

- [MCPProvider.kt:126](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:126) 没有配置解析步骤。
- [MCPClientSession.kt:65,69](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientSession.kt:65) 直接使用 `config.env`、`config.url`、`config.headers`。
- [MCPHttpTransport.kt:69,73](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPHttpTransport.kt:69) 原样设置 URL/header；[MCPStdioTransport.kt:47](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt:47) 原样写进 ProcessBuilder 的环境。
- client 目录没有 App 环境变量仓库或展开 helper 的调用。

上游 [http.py:39](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/transport/http.py#L39) 有 `expand_env`，构造 HTTP transport 时展开 URL/header；[daemon.py:73](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/daemon.py#L73) 展开 STDIO env。

因此 `Authorization: Bearer $$TEST_TOKEN` 发出的是字面量；含 URL 占位符的配置会发向错误地址或构造失败。STDIO 环境值也得不到 App 中保存的值。

修复方向：在连接前基于可信 App 环境变量来源解析配置，明确缺值行为，保留原配置中的引用而不把展开的秘密回写配置或日志。用 canary 验证实际收到的值，不能只检查设置页写出了 `$$VAR`。

<a id="f31"></a>

#### F31 / P1 — OAuth token 存储与 MCP 连接互不相连

[APP/ui/settings/MCPIntegrationsScreen.kt:555](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MCPIntegrationsScreen.kt:555) 启动授权，[mcp/oauth/MCPOAuthController.kt:155](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/oauth/MCPOAuthController.kt:155) 保存 access/refresh token 后返回成功；UI 显示已授权。

但是 [MCPProvider.kt:126](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:126) 只传 cfg。[MCPClientSession.kt:12](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientSession.kt:12) 的可选 bearerToken 默认 null，生产连接入口没有传值；`:69` 继续把 null 交给 HTTP transport。[MCPHttpTransport.kt:76](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPHttpTransport.kt:76) 只有非空 bearerToken 才生成 Bearer header。client 代码没有调用 `MCPOAuthStore.tokens()`，也没有有效期、刷新或 401 重认证路径。

上游 [http.py:197](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/transport/http.py#L197) 消费授权 token bridge，并处理过期/刷新。这套具体 bridge 不必移植，但其认证消费行为必须由原生 client 接管。

具体触发：只通过设置中的 OAuth 登录、没有手工重复填 Authorization header的服务器。用户完成授权后，连接仍没有该 token。手工静态 header 能绕过部分问题，不等于 OAuth 功能已经工作。

修复方向：基于服务器原始 ID 获取有效 token，处理过期、刷新和登出，并在认证状态改变后更新连接。只给初次请求补一个 token 不能完成生命周期闭合。

### 数据完整性、敏感持久化、备份与导出

<a id="f32"></a>

#### F32 / P1 — 敏感参数与结果仍有未脱敏副本

证据：

- [tools/ToolSensitivePolicy.kt:34](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ToolSensitivePolicy.kt:34) 起明确覆盖 MCP 工具，敏感集合还包括 memory、clipboard、settings 等。
- [ui/chat/ChatViewModel.kt:10412](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10412) 起对数据库中的工具结果应用该策略。
- 同文件 `:9784` 在执行前把原始参数交给 `ToolCheckpointStore.recordIntent`。
- [tools/ToolCheckpointStore.kt:33,46](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ToolCheckpointStore.kt:33) 把参数前 4,000 字符原样写进 `filesDir/checkpoints/<session>.jsonl`，没有敏感策略。
- [ui/chat/ChatViewModel.kt:9472,9474](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9472) 的 `Log.d` 包含参数和结果各前 200 字符，没有敏感过滤，也没有 `BuildConfig.DEBUG` 条件。
- [logging/AppLogger.kt:139,177](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:139) 启用诊断日志后捕获 logcat，过滤自身重复 tag 后原样写盘，不滤上述工具内容。

影响：数据库已脱敏不等于本地持久化已脱敏。即使没有开启诊断日志，intent 文件仍保存敏感参数；开启日志后还可能保存敏感结果摘要。这里确认的是 App 私有存储及日志中的原文副本，不能据此声称已经发生外部泄露。若 MCP 参数含密钥，未脱敏日志还与 `04-SECURITY-CONTRACT.md` 的密钥规则冲突。

修复方向：将现有敏感策略落实到 intent 与日志入口，保留调用身份、状态及 outcome-unknown 语义所需字段。对敏感调用的恢复不要依赖原文重放。验证只用唯一的测试 canary，不读取或打印用户真实机密。

<a id="f33"></a>

#### F33 / P2 — intent 文件持续累积，删除会话没有清理

- [tools/ToolCheckpointStore.kt:54](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ToolCheckpointStore.kt:54) 每次工具调用追加 intent。
- `:71` 起 `markDoneBatch()` 读整个文件并重写，只改 state，保留所有 done / done-failed 记录；持久化每条工具结果时均可走 `markDone()`。
- `:119` 的恢复检查也读完整文件，没有历史预算。
- `:146` 有 `clearSession()`，但生产调用链没有调用它。
- [data/repository/ChatRepository.kt:148](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:148) 删除数据库与 session backing；[MinisApp.kt:447](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:447) 的删除回调只走 `UbuntuPaths.deleteSession()`，后者只删 sessions 根下目录，覆盖不到 `filesDir/checkpoints`。

在单 session 有 n 次已完成工具调用、调用体量有界的条件下，文件随 n 增长，而逐次完成的累计整文件重写量达到 O(n²)。实际耗时未测。删除会话后还保留含 F32 原始参数的 sidecar。

修复方向：完成记录应压缩或按明确预算保留，删除 session 时清理对应文件。必须保留未确认的 pending/outcome-unknown 记录，不能为了缩小文件丢失潜在副作用证据。

<a id="f34"></a>

#### F34 / P1 — 合并会话的 REPLACE 触发级联删除

[APP/backup/BackupImporter.kt:385](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:385) 先比较会话 updatedAt，备份较新时在 `:391` 调用 `dao.insertSession()`。[data/db/ChatDao.kt:96](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:96) 使用 `OnConflictStrategy.REPLACE`；[MessageEntity.kt:16](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/MessageEntity.kt:16) 的 session 外键是 `ON DELETE CASCADE`。compact markers、session events 等还可能有相同生命周期依赖。

SQLite 的 REPLACE 在主键冲突时先删除旧行再插入；CASCADE 删除该旧会话的关联消息。这是源码与数据库语义共同确定的后果，参见 [SQLite REPLACE](https://www.sqlite.org/lang_conflict.html) 与 [外键动作](https://www.sqlite.org/foreignkeys.html#fk_actions)，尚未在 Android 上执行恢复验证。

具体触发：本地会话 S 有只存在本地的消息 M；备份有同 ID 的 S，其元数据 timestamp 更晚，但消息集合不含 M。选择“合并”后 S 的本地消息先被级联删除，随后只重新插入备份所携带的消息，M 丢失。这里需要备份 S 的 timestamp 较新；不会声称每次同 ID 恢复都会删除。

上游 [ChatDao.kt:104](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L104)、[BackupImporter.kt:388](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt#L388) 也使用同一组合，messages 外键同样 CASCADE。**这不是上游已修、本地漏同步，而是双方 Android 都需修复。**

修复方向：使用不会删除已有父行的更新/upsert，按真正的 merge 规则处理消息及关联数据；事务包住 REPLACE 不能解决成功提交时的级联删除。

<a id="f35"></a>

#### F35 / P1 — 聊天恢复没有落实上游的事务和取消修复

本地 [APP/backup/BackupImporter.kt:308](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:308) 起逐条写 folders、sessions、messages，没有 `withTransaction`。已经提交的前半部分不会因同类后续失败回滚。`:226` 的 `catch (Exception)` 将 `CancellationException` 当类别失败吞掉，之后继续下一个类别；目录/文件复制等同步路径也没有取消检查。

[ui/settings/backup/BackupViewModel.kt:981](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:981) 启动恢复没有保存 Job；`:961` 的 `cancelRestore()` 只清待确认包，不是运行中的取消。恢复界面运行时按钮禁用，取消按钮也禁用，没有停止运行中恢复的入口。这里不声称用户点击现有按钮后一定继续写，因为运行时本就不能点击该取消按钮。

上游有明确的对应实现：

- [BackupImporter.kt:357](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt#L357)、[:471](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt#L471) 的事务；`:484` 合作式取消。
- [BackupImporter.kt:230](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt#L230) 单独重抛取消；`:577` 收尾本次新增的空 session。
- [BackupViewModel.kt:902](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt#L902) 保存并取消 restore Job。

上游还以已恢复 session ID 集合代替每条 message 的 `dao.getSession()`；本地 `:474` 仍为每条消息查询父行，并逐条提交。仅确认额外查询与提交，不把上游注释中的实测速度当成本地测量。

修复方向：明确类别事务与失败收尾，传播取消，并让运行中的停止作用于实际恢复 Job；采用符合磁盘预算的事务边界。上游这套恢复改进仍未解决 F34，不能整段照搬后宣称 merge 安全。

<a id="f36"></a>

#### F36 / P2 — CompactMarker.version 没有进入备份往返

本地 [APP/backup/BackupExporter.kt:387](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:387) 的 markerRecord 不写 `version`，[BackupImporter.kt:530](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:530) 构造 marker 时也不读；[data/db/CompactMarkerEntity.kt:56](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/CompactMarkerEntity.kt:56) 默认 1。正在使用的压缩实现写 `version=2`，因此本项目自己导出的 v2 marker，恢复后也变为 v1；上游/iOS 包即使显式携带 version，本地 importer 同样忽略。

上游 [BackupExporter.kt:805](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt#L805) 写该字段，[BackupRecordMapper.kt:164](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupRecordMapper.kt#L164) 读字段且旧包默认 1，并有格式往返用例。

下游不是只有元数据差异：[APP/ui/chat/ChatViewModel.kt:2840](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2840) 的 v2 分支保留近期 pre-anchor 原文、做工具配对修剪；`:2972` 起的 v1 分支采用 summary + 保留区的另一套组装语义。恢复会话的模型上下文因此改变。本地 v1 分支也有 lastCompactedMessageId 后备路径，**不能简单声称所有恢复都会丢掉整个后续历史**。

修复方向：导出和导入同时保留版本，并通过真实压缩会话的 export → restore → 下一次 Provider 请求对比上下文边界。仅比较 JSON 字段不够。

<a id="f37"></a>

#### F37 / P1 — 本地新增会话身份字段没有备份

本地 [APP/data/db/ChatSessionEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatSessionEntity.kt) 包含 `roleplayJson`、`sessionOverrides`、`botId`。[backup/BackupExporter.kt:326](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:326) 的 sessionRecord 三者都没有；[BackupImporter.kt:392](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:392) 的构造也未恢复，落为 null。`restoreCharactersAndBots()` 虽恢复角色和 Bot 对象，但没有保存或重建这些会话引用。

结果：在新设备恢复，本来绑定 Bot 的会话成为普通会话，角色快照与独立会话设置消失。覆盖较新的同 ID 备份会话时，本地已有这些字段也会被默认 null 覆盖。

上游 session 模型没有本项目这三个扩展字段；这属于本地加功能后必须同步完成的持久化合同，不是等待上游提供字段。原来复制来的 sessionRecord 不会自动理解这些新增身份。

修复方向：将字段及关联对象一起纳入备份/导入；先验证引用对象与允许的设置再应用，缺字段的旧包仍按明确兼容规则处理，避免把缺字段解释为无条件清除本地身份。

<a id="f38"></a>

#### F38 / P2 — Bot 导入绕过总量限制

[APP/data/repository/BotRepository.kt:53](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotRepository.kt:53) 在创建时检查 `< MAX_BOTS`，`:111` 上限为 12；合同 `08-BOT-COORDINATION.md` 也定义总数硬上限。

[backup/BackupImporter.kt:1194](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1194) 使用通用 `MAX_ITEMS=500` 读取 botRecords，`:1203` 对不在本地的 ID 直接 `botDao.insertBot()`，没有检查剩余额度。一个有 12 个不同 Bot 的合法备份，合并到已有另外 12 个 Bot 的设备，就能得到 24 个；无需畸形备份。对象的 `enabled` 状态也按记录直接恢复。

上游没有本项目的持久 Bot 机制；其通用导入方式不能替代本地 Bot 约束。

修复方向：恢复也使用一致的总数规则，允许更新已有 ID，仅对新对象计算额度；拒绝/略过的对象要进入恢复报告。还应检查关联会话如何处理这些未恢复对象，不能默默创建失去身份的会话。

<a id="f39"></a>

#### F39 / P1 — `--full` 导出仍先把每个工具结果砍到 200 字，还不标截断

[ChatRepository.extractTextForOffload](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:805) 对每个工具输出固定 `.take(200)`。`loadMessagePage` 随后才执行 50,000 字的 full 上限与 `truncated` 判断，所以已经被砍成短文本的内容不会触发截断标记。

同一投影还把 toolUse 变成标题/名称，省略参数；当一条消息同时有 text 和 tool parts 时，先返回 text，后面的工具信息不会进入导出。因而 `--full` 不是完整工具调用记录。

附件中的工具结果正好符合这个上限，大量长文件、进程与 hash 输出被切断。Minis 即使再次 `file_read` 回读完整 offload，回读结果导出时还会被砍到 200 字。**这不能证明模型运行时只看了 200 字**，但证明用户现在拿到的 MD 缺少复核模型结论所需的工具证据。上游 Android 同样存在此问题。

已有原生 ChatExporter 使用 raw MessageEntity 分页，未走这份文本投影；应优先核对能否复用它。不能把附件声明的“全部消息”理解成全量工具输入输出。

E2E：一条会话包含 1,000 字工具输出、完整 argv、混合 text/tool parts、敏感结果及附件；经 CLI 与原生导出分别往返检查长度、参数、脱敏和明确截断标志。保留 raw DB、导出文件及结构对比工件。

<a id="f40"></a>

#### F40 / P2 — 聊天分页返回数与 total 不同口径，且没有导出快照

[ChatDao](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:490) 先对全部 DB 行做 LIMIT/OFFSET；[ChatRepository](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:724) 再过滤空投影，但 total 仍是原始行数。第一页要求 100 条，返回 97 条不一定缺消息；可能是 3 个被隐藏的行。接口没返回扫描行数或 next offset，按 count 翻页会重复，按 `count < limit` 结束会提前停止。

[SessionsOffloadHandler](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt:181) 声称同一 coroutine 保证快照，但没有 Room transaction；顺序执行两个查询不阻止其它 coroutine 插入。附件中导出调用自己就在往同一会话追加消息，total 从 426 增到 448，跨页还没有固定截止行或游标。

所以不能由“428 条比 total 430 少 2 条”直接推出丢了两条用户消息；但 API 的完整性契约确实不够，模型为核对数目做了多轮重复拉取，反过来继续增大被导出的会话。上游 Android 同样保留了先分页后过滤行为。

E2E：静态会话含空投影行，另一路并发追加消息；验证有限、可终止的导出，扫描行数/可见消息数定义一致，无重复，顺序稳定。保留 ID 清单与结束游标。

## 本地行为问题与上游的归因

固定上游对照的 10 项（原 U01–U10）中，6 项属于本地分叉/接入缺口，2 项是上游已有相关修复但本地未落实，2 项是双方 Android 共有的问题；具体归因保留在发现正文中。以下再列出原行为报告 12 项的对照结论。

本表只补充行为条目的上游归因，不重新计数。上游缺少某个本地能力，不意味着该能力不该存在；上游也有错误，不意味着本地可以忽略。

| 条目 | 对照结果 |
|---|---|
| F17 goal/jobs 别名 action | 上游没有本地的 canonical 合并 handler/这六个别名组合，是本地注册迁移造成的契约错误 |
| F26 MCP 会话关闭 | 上游依赖提示词选择 guest CLI；未见可直接复用的 session 执行授权 gate。本地新增工具自动公布后，更不能只滤提示词 |
| F32 敏感原文副本 | intent sidecar 与敏感策略来自本地扩展；上游不能提供这条新增持久化路径已脱敏的证明 |
| F18 retry/rerun | [上游 runner](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt#L333) 也按 busy flag 判结果。本地普通 prompt 的请求句柄更严格，应复用本地改进而非照搬上游 |
| F19 Ralph | 上游没有本地 Ralph 路径，大小写与跨 session 产物规则需本地负责 |
| F20 旧 subagent 取消 | 上游现役 helper 用取消 hook 管理 child；本地旧单次工具沿 runner 等待的路径仍缺取消所有权 |
| F27 MCP 名称碰撞 | 上游通过 CLI 查远端原名，没有本地这套自动注册 wire-name 映射；本地新注册机制必须自行处理碰撞 |
| F21 例程状态 | [上游例程](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt#L130) 还排除 Rejected；本地新增 Cancelled/Dropped/NeedsAttention 等词汇后未同步成功分类 |
| F28 registry 热更新 | 上游没有本地 ToolRegistry/MCPProvider 组合，不能拿其连接并发实现证明本地 map 读写安全 |
| F22 job_kill | 本地通用 JobRegistry 与上游 AgentJobRegistry 不是同一机制；上游取消 hook 可作为行为参考，不能简单替换 registry |
| F23 Bot 恢复 32 条 | 上游没有本地 Bot 协调，本地 Room 状态机必须按本地合同自行排空 |
| F33 checkpoint 累积 | 属于本地新增执行意图存储，完成记录预算和删除生命周期没有现成上游对等实现 |

## 退役对象分类与排除误报

| 对象 | 结论 |
|---|---|
| Rootfs reset 的备份 UI、`restoreUserData` 空实现 | 见 F09；是旧“用户数据跟 rootfs 一起保存”交互未清理。 |
| `OwnershipMigrationManager` 的 FD/WAL 六阶段模型 | 见 F04，只有测试引用，没有生产迁移调用；不能把它的注释当成现役迁移能力。 |
| `sandbox/RootfsManager`、`sandbox/TerminalSession` 包名 | 仍有现役调用，但内部使用 Direct Ubuntu；只按包名删除会删真实功能。 |
| `MirrorCatalog` 接受旧 `alpine` key | 映射为当前 Ubuntu APT 类别，没有选择 Alpine 后端；属于偏好/导航兼容。 |
| build/asset guard 中的 `minisd`、PRoot 字样 | 用来拒绝旧载荷，不能作为旧引擎仍在运行的证据。 |
| build rootfs 中的占位 UID 10000 | launch 前会重写 guest identity，setpriv 使用实际 UID/GID；没有发现 guest 执行仍写死 10000。 |
| `/data/adb/minis` 的旧用户数据目录 | 合同明确要求一次性复制并保留源。不能把存在旧目录本身当成 bug，更不能直接递归删除。 |
| MinisOpenUrlBroker | URL 分发器，与旧特权 daemon 无关。 |

核对时还排除了几处不成立的判断：本地备份的 `minis-global` / `minis-sessions` 与 UbuntuPaths 当前 App-owned backing 一致，未发现备份读错目录；steer 在工具结果边界触发队列注入，不能说只能等整个任务结束；MCP 明文 Authorization 进入备份的风险已有界面加密提示，未列为新缺陷。CalendarManager、HealthManager、BackgroundInterruptionTracker、AntigravityModelsApi 等候选未完成替代实现核查，不给出直接删除结论。

## 修复顺序与端到端验收

先处理可能删除用户数据、保存敏感原文或违反权限合同的条目；随后处理请求结果/取消、MCP 接入与恢复闭合；最后整理无调用实现、透传层、初始化和执行职责。每次只解决一个可独立验收边界，复用现有 Repository、Room、AgentTurnHandle、权限及 session 生命周期，不新建第二套 Loop、数据库或通用框架。

以下为待执行验收要求，不是通过记录。安全场景使用测试 canary 与受控端点；应包含拒绝、越界、失败关闭和取消后无副作用等否定用例，保存可重复的输入、session/call ID、结果、数据库/文件/进程快照。按用户规则不新增单元测试，以实际公开入口和端到端链路验收。

### 结构调整的分批顺序

| 顺序 | 范围 | 验收及应保留的工件 |
|---|---|---|
| 1 | 统一 session 删除收尾；修正 rootfs reset 的旧备份语义 | 分别从 UI/RPC 删除运行中与空闲会话，检查执行退出和资源释放；真机 reset 前后校验 session/global 数据；保留操作步骤、进程/数据库快照和文件摘要 |
| 2 | 确认并退役无调用实现、空透传 Provider | 对其现役替代入口做功能往返；保留场景与结果，Release 涉及 shrink/反射时补对应验证 |
| 3 | 工具进入同一执行入口 | 本地、MCP、Guest CLI 分别验证正常调用、未授权拒绝、READ_ONLY 拒绝、越界、超时和输出溢写；保留请求与结果工件 |
| 4 | 将产品 runner 从 debug 包移出，逐步抽离 VM 的执行职责 | 同一会话 UI/后台一致；两个 session workspace 分离；任务失败不返回旧成功、取消等清理完成；保留逐请求结果与 session 文件证据 |
| 5 | 精简初始化，收拢 rootfs 与 Provider/备份职责 | 冷启动、授权拒绝、配置载入失败、已有数据升级和备份恢复；Root/OEM 行为在目标设备实测 |

不建议第一步重写 ChatViewModel 或一次拆多个 Gradle 模块。先处理可独立验收的生命周期和过时语义，再抽取执行职责，diff 和回归范围更可控。

### 行为与后台任务验收

以下是修复时的验收要求，本轮没有编写或执行这些场景。使用实际 App / runner / Provider / 工具执行链，保存可重复的输入、session/call ID、结果与必要快照；不以只测 helper 或映射表代替整条链。安全场景用测试 canary 和受控端点。

| 场景 | 需要证明的行为 | 可保存工件 |
|---|---|---|
| 六个旧名工具按公开 schema 调用 | 接受正确输入，实际完成各自动作 | Provider 请求 schema、tool use/result 与目标状态 |
| MCP 全局启用，在 A 关闭、B 保持启用 | A 不公布也拒绝历史调用，B 正常；开关变化即时有效 | A/B 工具请求、拒绝结果、MCP 端请求计数 |
| 敏感调用使用唯一 canary | 受控端点收到完整参数，持久化与诊断日志无原文 | 脱敏 DB/intent/log 搜索结果及端点接收记录 |
| 仅附件 retry、模型失败、取消及另一回合忙碌 | 拒绝不假成功、不返回旧答案，不等待或取消无关回合 | 每次请求状态、消息边界、历史前后快照 |
| rerun 超时，工具计划稍后写 marker | 超时后真实退出，marker 不再出现；streamExited 可信 | 超时返回、退出时间、workspace 前后快照 |
| Ralph 成功与 continue 两轮 | 成功被接受，跨轮产物按约定可见，含 `{` 的报告可解析 | child IDs、原报告、产物摘要、父工具结果 |
| 父会话等待旧 subagent/Ralph 时停止 | 该 child 的模型/工具停止，后续副作用不发生 | 父/子状态、受控端点调用、延迟 marker 结果 |
| 两个碰撞 MCP ID / 短工具名 | 拒绝冲突或生成不同 wire 名，不调用错误服务器 | 注册结果、wire schema、两个端点各自计数 |
| 例程遭遇 Busy / Cancelled / Dropped / NeedsAttention | 都不被记为成功；正常 Completed 仍成功 | ScheduledRun 历史与对应 runner 状态 |
| MCP reload 与聊天工具读取重叠 | 无崩溃，公布 schema 与派发映射一致 | reload 输入、schema 快照、崩溃日志与调用结果 |
| 通用 jobs 取消 logcat watch | worker 实际停止，等待后不再追加输出 | job 状态、取消前后 output 长度、采集计数 |
| 重启前准备 33 条以上待恢复记录 | 所有符合条件任务/回执最终处理且不重复执行 | 重启前后 DB 状态、执行计数、来源卡片 |
| 长 session 多批工具调用后删除 | 文件体量有界，pending 不丢失，删除无 sidecar 残留 | 每批文件大小/耗时与删除后的目录清单 |

### 对照发现的验收

以下仅是后续修复验收要求，没有执行，也没有编写单元测试。均使用实际 App 的公开入口和真实执行/存储链；安全场景只用 canary 与受控端点。

| 场景 | 应保存的可重复工件 |
|---|---|
| 在 Ubuntu 安装可识别的 STDIO 服务，再由设置连接 | guest 安装记录、服务进程 UID/执行域、initialize/list/call 结果与退出记录 |
| URL/header/env 含测试变量；变量缺失/变更 | 原配置、受控服务实际接收值的摘要、拒绝结果；工件不保存真实秘密 |
| OAuth 授权、过期刷新、登出后再次调用 | 测试认证端点的请求类型与计数、App 连接状态，去掉 token 原文 |
| 3 个运行 + 1 个排队，父工具取消排队项 | cancel 返回耗时、父/子状态；槽位空闲时排队任务实际启动的时间 |
| 较新备份会话与本地独有消息合并 | 恢复前后消息 ID 集合、关联 marker/event 集合；明确本地独有消息仍存在 |
| 大聊天恢复中取消、受控写失败、再重试 | 取消时间、各类别记录数、事务/收尾状态、无继续写入证据与重试结果 |
| v2 压缩会话导出后恢复，并继续聊天 | marker.version、锚点、恢复前后 Provider 请求的上下文边界摘要 |
| Bot/角色/会话设置备份恢复 | 会话引用和对象 ID、设置快照、恢复后的实际模型/角色/并发行为 |
| 本地 12 个 Bot 再合并另一批 | 总数仍不超过 12、已有 ID 更新结果、被拒绝对象与引用的恢复报告 |
| 草稿附件/历史编辑中触发后台回执 | composer 前后状态、Provider 附件计数、历史 ID 集合；用户尚未发送的内容不外发、不截断 |

运行时迁移、镜像、用户 PATH 与聊天导出场景的具体 E2E 步骤保留在各自条目内，应与这些跨入口场景一起验收。Release/R8/JNI 敏感改动需验证 Release；宿主或 CI 结果不替代 Root、SELinux、VPN/BPF、DNS 和 OEM 真机证据。

## 已执行检查与未验证范围

已读取工程合同、源码、既有测试和 CI，核对注册/派发上下游、目录与生命周期；上游格式/恢复/子代理用例和压缩版本往返断言仅作只读参考。SQLite REPLACE/外键、pip/npm 配置语义核对了正文链接中的官方说明。

此前审计实际执行并通过：

| 检查 | 证明范围 |
|---|---|
| `python3 scripts/check_build_cleanup.py` | 限定源码/构建路径中没有禁止的旧身份回归 |
| `bash scripts/check-runtime-package-boundary.sh` | 现役包及所列边界符合文本规则 |
| `python3 scripts/check_docs_provenance.py` | 文档来源/表述规则；archive 正文排除，不能验证本报告事实 |
| `bash scripts/verify-runtime-payload.sh src/android/app/build/outputs/apk/release/app-release.apk` | 已有 Release APK rootfs 载荷格式、目录及 SHA-256 符合要求 |
| `bash scripts/verify-root-network-proxy.sh src/android/app/build/outputs/apk/release/app-release.apk` | 已有代理载荷 ELF/架构等符合要求 |

现有 APK 的 ZIP 清单、载荷 hash、manifest 与 rootfs tar 已检查。首次上游对比文档 guard 因历史术语出现在 active docs 失败，之后按既有规则将固定快照放入 archive，没有修改 guard；本合并报告也放在 archive，避免充当现役合同。

没有运行 Android Debug/Release 构建、既有 JVM 测试、Room 恢复、压力测试或设备 E2E。构建产物核验不等于当前 HEAD 重新编译通过；没有把其他进程的变更或本报告视为缺陷已修复。附件的工具结果经过文本投影截断，不能把 Minis 的分析文字当成完整原始测量；当前手机旧文件、进程监听者、实际 rootfs 修订仍需独立设备证据。

### 在固定源码上重新定位

```bash
git show d64e7c06:src/android/settings.gradle.kts
git show d64e7c06:src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt
git show d64e7c06:src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt
git grep -n 'OwnershipMigrationManager' d64e7c06 -- src scripts
git grep -n 'BrowserUseTool' d64e7c06 -- src scripts
git grep -n 'ModelSelectionResolver' d64e7c06 -- src scripts
git grep -n 'DshReasoningCatalog' d64e7c06 -- src scripts
git grep -n 'ShellOutputTruncator' d64e7c06 -- src scripts
git grep -n 'connected\|managedDevice\|instrumentation' d64e7c06 -- .github/workflows
```

上述 grep 没有匹配时退出码为 1；符号搜索是定位证据，不替代动态入口和设备验证。

<a id="artifact-evidence"></a>

## 证据摘要

以下为原证据 JSON 的完整内容，已收入这一份报告。时间字符串保留采集值（Asia/Shanghai）；这些路径仅用于复核，未复制用户原始聊天正文。

```json
{
  "type": "read-only audit evidence; no Android device execution",
  "source_head": "d64e7c06d8159be90737dd9ba03dd9bd5855f339",
  "reference_head": "b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c",
  "chat_record": {
    "path": "/home/jiale/文档/xwechat_files/wxid_ob2a451pbj9k12_56cc/msg/file/2026-10/2c7a48ecc32a4ab6355ec6b958244d39b4b1d48976139eb531c3562054d53860-minis-session-28d0b88b-2026-10-04.md",
    "sha256": "2c4459d46c666dc21569a0371e771967e96aa4210185c91a278afc0e9832f6fd",
    "missing_tool_parameter_occurrences": 46
  },
  "apks": [
    {
      "path": "/home/jiale/projects/minis-for-android/src/android/app/build/outputs/apk/debug/app-debug.apk",
      "bytes": 115605080,
      "mtime": "2026-10-02T13:36:51.646549",
      "sha256": "3d734157a044b2e927a42aca793b5e3cd933ffba2fe02b0fd4246b47e3aa066c",
      "retired_name_matches": [],
      "rootfs_actual_sha256": "06dcdf94f662764b3a4ea80041dbbbafc12331bec2eaa9420ef73ffed423e208",
      "runtime_manifest": {
        "arch": "arm64",
        "distro": "ubuntu",
        "profile": "base",
        "provisionRevision": 2,
        "release": "24.04.3",
        "requiredCommands": [
          "python3",
          "git",
          "curl",
          "ping"
        ],
        "revision": 1,
        "rootfsSha256": "06dcdf94f662764b3a4ea80041dbbbafc12331bec2eaa9420ef73ffed423e208",
        "rootfsVersion": "ubuntu-24.04-r1-06dcdf94f662764b",
        "schemaVersion": 3,
        "upstreamSha256": "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
      }
    },
    {
      "path": "/home/jiale/projects/minis-for-android/src/android/app/build/outputs/apk/release/app-release.apk",
      "bytes": 80613959,
      "mtime": "2026-10-02T13:44:51.703409",
      "sha256": "75e6aad31538aa21b5baf8c27804c22aa5acd1289b8b4551b28a54befc04f1a9",
      "retired_name_matches": [],
      "rootfs_actual_sha256": "06dcdf94f662764b3a4ea80041dbbbafc12331bec2eaa9420ef73ffed423e208",
      "runtime_manifest": {
        "arch": "arm64",
        "distro": "ubuntu",
        "profile": "base",
        "provisionRevision": 2,
        "release": "24.04.3",
        "requiredCommands": [
          "python3",
          "git",
          "curl",
          "ping"
        ],
        "revision": 1,
        "rootfsSha256": "06dcdf94f662764b3a4ea80041dbbbafc12331bec2eaa9420ef73ffed423e208",
        "rootfsVersion": "ubuntu-24.04-r1-06dcdf94f662764b",
        "schemaVersion": 3,
        "upstreamSha256": "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
      }
    }
  ]
}
```

## 合并来源与完整性

合并前的四份报告分别为代码结构梳理（9 项）、行为缺陷审阅（12 项）、OpenMinis 固定提交对照（10 项）及退役运行时/真实会话复核（9 项）。统一编号、分组、重复状态说明及验证边界后，所有发现正文、证据定位、修复方向、潜伏问题、排除误报、端到端要求和复核命令均保留；证据 JSON 完整内嵌。原四份报告及单独 JSON 不再保留为独立文件，用户提供的原始聊天文件未改动。

合并时逐项比对了 40 项原始发现正文，全部保留；核对了 149 个本地文件链接与全部内部锚点，均有效；内嵌 JSON 与原证据文件结构和值一致。文档来源 guard 通过。这些合并检查只验证报告的完整性与可定位性，不增加功能或设备验收结论。

| 合并来源（原文件，已收入正文） | SHA-256 |
|---|---|
| `docs/analysis/2026-10-04-code-structure-review.md` | `40f06e2e90ff56ab2f805527f48fc7b1d18b1aebff14d36cad58876b7dd462cc` |
| `docs/analysis/2026-10-04-behavior-review.md` | `d63f02c589c06cb54b77ae622d9ef55b942be1178623ddd78711967f0b65a2f3` |
| `docs/archive/2026-10-04-openminis-comparison.md` | `3c24c98cc9691d936292b5b4bf14387d4874ed45db84503179600831b93f16d6` |
| `docs/archive/2026-10-04-retired-runtime-review.md` | `b83e62edfafef14093778479b66550f1c3b799fa135c6acac73f784ff6fbc155` |
| `docs/archive/2026-10-04-retired-runtime-evidence.json` | `2eb61700206039bcd54c155a84561cb92aea1f20852e0e0463b8e2425d849394` |
