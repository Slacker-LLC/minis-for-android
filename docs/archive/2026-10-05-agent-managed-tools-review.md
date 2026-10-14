# 工具归属与 Agent 自主管理复核

日期：2026-10-05。源码基线：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本报告独立新建。只进行静态排查和报告／阅读台账记录；没有修改产品代码、测试或合同，没有运行构建、测试、模拟器、设备命令、Root 操作或云任务。此前报告保持原样。

## 用户指出的归属问题成立

按照用户提出的产品目标，开发工具、项目依赖、分析脚本和开发流程应在 Ubuntu guest 中维护，由 Agent 使用并管理。App 承担必要的 Android 原生接线、权限、审批、调用方身份和 session 边界。

当前生产实现仍把一部分开发策略编译进 App。这里复核的是此前 AD01，**不再次计为新增发现**。问题并非文件位于 App 私有目录：guest 的持久用户目录本来就以 App-owned backing 保存。需要核对的是策略在哪里执行、谁能独立更新它。

生产链为：

```text
AgentTools.makeAgentTools(FULL)
  → AndroidAgentTools.definitions()
MinisApp 注册 android.deploy / android.logs / android.diagnose
  → ToolRegistry.AndroidToolHandler
  → AndroidAgentTools.execute
  → App 内的工程扫描、产物选择、部署编排与日志分析
```

入口见 [AgentTools.kt:67](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:67)、[MinisApp.kt:723](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:723)、[ToolRegistry.kt:378](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:378)。给 Kotlin 实现套一层 guest CLI，仍不能让 Agent 在 guest 中维护其策略。

| 当前职责 | 源码位置 | 按用户目标的归属 |
|---|---|---|
| 找 Gradle 输出目录、排列和选择 APK | [AndroidApkInspector.kt:106](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:106)、[resolveSearchRoot:186](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:186) | guest 工具；Agent 明确项目、variant、产物与内容身份 |
| 串起 inspect、install、launch，回用调试目标包 | [AndroidAgentTools.kt:491](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:491) | Agent／技能负责流程；App 接受明确产物并执行有界原生操作 |
| 解析异常栈、判断崩溃和 ANR | [AndroidDiagnoseController.kt:124](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDiagnoseController.kt:124) | guest 分析脚本／Agent；App 提供真实原始证据、权限与来源 |
| 开发任务的目标、产物与复现起点 | [AndroidDebugSessionStore.kt:7](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDebugSessionStore.kt:7) | session 工作区中的任务资料；设备授权、display lease 和 UI generation 仍由 App 校验 |
| SDK、Gradle、项目构建 | [android.debugLoop.md:3](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/android.debugLoop.md:3) | 当前已经要求通过 guest `shell_execute` 执行；本轮没有证据证明构建进程在 App 宿主中运行 |
| PackageManager、安装请求、系统日志获取、Accessibility／VScreen | 原生 controller 与现有权限层 | App 的必要原生能力接口 |

默认开发提示词也随 APK 分发，但 [PromptModuleStore.kt:19](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt:19) 支持设置页及 `minis-config` 覆盖文字。不能说所有流程调整都必须重打 APK；**Kotlin 内的项目选择、日志解析等规则**仍随 App 发布。

Root-owned 基础 rootfs 的受控安装维护与项目工具管理要分别处理。现有 [存储合同](/home/jiale/projects/minis-for-android/docs/contracts/03-STORAGE-CONTRACT.md:9) 已提供 App-owned guest HOME、skills、shared 与 session workspace。不能为下沉开发工具开放任意 Root 安装、恢复旧 minisd／PRoot 或关闭 SELinux。chroot 也不是强安全隔离容器。

## 本轮新增：技能管理尚未支持完整的 Agent 文件工作流

技能文件已在 guest 可见目录中，但 App 内的索引、刷新与安装分支还存在以下断点。它们与“开发策略固化在 App”相关，不能仅靠把脚本复制进沙箱解决。

| 编号 | 优先级 | 新发现 |
|---|---|---|
| AT01 | P2 | shell／通用文件工具修改技能后，没有索引刷新通知 |
| AT02 | P2 | 打开设置或重启后的全量扫描仍保留旧的非空 frontmatter 元数据 |
| AT03 | P2 | 发现接受 `skill.md`，安装却固定请求 `SKILL.md` |
| AT04 | P2 | 内置技能首装登记成 1.0.0，下一次启动触发伪升级并覆盖修改 |

### AT01 — Agent 在 guest 安装、删除技能后，App 索引没有同步

[SkillTools.list:66](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/skills/SkillTools.kt:66) 读取 `repository.skills.value`；`read`／`read_resource` 也先从同一内存列表解析 ID。[skillPromptFragment:380](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:380) 仅使用该列表。

[ChatViewModel.kt:10554](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10554) 刻意不在发送路径扫描目录，这个性能选择本身合理。但注释所说的“skill file writes 后刷新”，在当前通用工具链中没有接线：[FileWriteTool.kt:100](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt:100) 和 [FileEditTool.kt:111](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:111) 写完即返回；`WorkspaceFileClient` 与执行协调器也没有技能变更回调。

全生产 Kotlin 搜索 `reloadFromDisk`／`rescanFromDisk` 的调用后，技能的显式扫描调用来自设置页：[SkillsManagementScreen.kt:165](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SkillsManagementScreen.kt:165) 与详情刷新。Repository 初始化也会扫描；`skill.install_github` 则直接通过 Repository 注册，不属于这个缺口。

**触发：** App 已完成初始扫描，Agent 用获准的 shell／文件写入在 `/var/minis/skills/new-helper/SKILL.md` 创建合法技能，随后直接进入下一轮聊天，不打开技能设置。文件可以用通用文件工具读到，但 `skill.list`、`skill.read` 的 ID 查找及自动提示词仍不知道它。删除已登记技能时，旧索引和广告同样会保留；`skill.read` 在磁盘读取失败后还可能回用旧缓存 body，见 [SkillTools.kt:92](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/skills/SkillTools.kt:92)。

**上游对照：** 固定上游 `SkillRepository.kt:1285` 提供后台 `requestReload`；`MinisApp.kt:536` 在 foreground 通知，`ChatViewModel.kt:13110` 在 shell 调用后通知，`:14131` 在技能文件写入后通知。本地没有 `requestReload`／`SkillDiskSignature`，也没有相应替代触发点。这是本地刷新接线缺口，不是必须把昂贵扫描放回每次发送路径。

**方向，未实施：** 复用现有 Repository，在已成功完成的相关文件／shell 操作之后安排有界后台同步，保留禁用和 session override 状态；不要再造第二份技能真源。

### AT02 — 有扫描仍不能同步 Agent 改过的名称、描述和版本

[SkillRepository.kt:1831](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1831) 重新读 body，但名称、描述和版本来自 SQLite。只有旧描述为空或为 `|`／`>` 时，[`:1856`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1856) 才尝试自愈；普通的非空旧描述不会与新 frontmatter 比对。自动发现分支 [`:1903`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1903) 只处理没有 DB 行的目录。

**触发：** 已登记的技能原为“构建辅助”，描述非空。Agent 在 guest 将同目录 `SKILL.md` 改为新的有效名称、用途和版本。即使用户打开技能设置触发全量扫描，或者重启 App，旧 name／description／version 仍被保留，而 body 已更新。`skill.list` 的用途、名称搜索和系统提示词会继续反映旧元数据；直接读文件得到的 frontmatter 又是新值。

现有 `rescanFromDisk(skillId)` [`:1232`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1232) 可以完整更新这些字段，但目前需进入单个技能详情手动刷新。这与 AT01 不同：AT01 缺触发，AT02 是已触发的全量扫描仍不对齐。固定上游也保留普通非空 DB 元数据，属于共有的管理能力缺口；未声称上游已解决。

**方向，未实施：** 明确技能 frontmatter 与用户开关的权威：有效文件中的名称、描述、版本与 body 一起更新；用户的 enabled／session override 独立保留。不能靠换目录名逃避同步。

### AT03 — 发现与安装的文件名规则不一致

[SkillSourcePolicy.kt:130](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/skills/SkillSourcePolicy.kt:130) 用不区分大小写的方式发现 `SKILL.md`，但只返回目录，丢失原始文件名。[`:144`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/skills/SkillSourcePolicy.kt:144) 构造下载 URL 时固定拼写 `SKILL.md`；[SkillRepository.kt:892](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:892) 正式安装使用该 URL。

**触发：** 同一固定提交里只有 `skills/other/skill.md`。inspect 将 `skills/other` 返回为可安装候选，Agent 按该目录和 commitSha 安装时，请求的是不存在的 `skills/other/SKILL.md`，报未找到。现有 [SkillSourcePolicyTest.kt:92](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/skills/SkillSourcePolicyTest.kt:92) 明确要求小写文件成为候选，但没有串起候选与安装下载；本轮只阅读，没有执行测试。

**方向，未实施：** 发现与安装统一规则：若只支持规范的大写文件名，发现时明确拒绝；若支持其它大小写，保留并请求真实文件名。无需为此新增开发工具抽象层。

### AT04 — 内置技能首装版本错误导致下一次启动覆盖用户修改

[SkillRepository.kt:1662](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1662) 声明内置 `skill-creator` 为 2.0.0，但 fresh install [`:1700`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1700) 调 `add` 没传 version。`add` [`:213`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:213) 默认 1.0.0，并将它写入注册行和 `SKILL.md`；首装日志却宣称 2.0.0。

**触发：** 全新技能库第一次成功安装该内置技能后，用户通过设置页或 Agent 改写正文。下次启动先加载 1.0.0 记录，然后 [`:1666`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1666) 判定需要升级，使用 APK 内置正文替换已有 `SKILL.md`。即使没有真正升级 App，第一次启动后做的修改也会被伪升级覆盖。设置页 `update` [`:247`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:247) 保留已有版本，所以通过设置页修改也满足这个触发条件，不依赖 AT01。

固定上游 `SkillRepository.kt:1234–1266` 存在相同的 fresh install 参数遗漏，属于共有问题。这里只确认 `SKILL.md` 修改被替换；没有声称其它 sibling 文件都被删除，也没有声称每次启动都会发生。

**方向，未实施：** 首装登记真实内置版本，区分 seed、真正升级和用户修改；不能把普通冷启动当成恢复内置默认内容的时机。

## 上游和验证边界

上游仅使用已有只读快照 `/tmp/openminis-review-20261004`，提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。通过 Git tree 确认这个固定提交没有当前 `tools/android`、`tools/skills` 和 `android.debugLoop.md` 路径；没有以 sparse checkout 的磁盘缺失作为证据，也不推断某位开发者或代码引入时间。

本轮新增四项功能／管理缺口；Android 开发分层是旧 AD01 的复核，不重复计数。没有把注释噪音、HTTP client 重复、Preferences、shellQuote、sha256 或 Lint 基线作为本轮发现。

后续端到端验收建议，未执行：在不打开设置的情况下由 Agent 新建、修改、删除技能，比较 guest 文件、`skill.list`、自动提示词及搜索结果；再核对全量扫描、重启、首次内置技能初始化和同提交的大小写候选安装。保留各阶段文件摘要、技能元数据与工具返回，验证 enabled／session override 不丢失。

本轮完整阅读九个文件，其中两个提示词文件此前已登记；新增全文覆盖七个文件、1802 行。`SkillRepository`、`ChatViewModel` 等只阅读相关片段，未登记为全文。全库台账累计 **146／1546 个范围内文件**完成全文阅读，**29323 行**；全库阅读仍未完成。
