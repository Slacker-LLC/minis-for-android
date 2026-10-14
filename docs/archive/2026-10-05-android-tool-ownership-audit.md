# Minis for Android — Android 开发工具职责与新增缺陷排查

日期：2026-10-05。本轮独立报告，不修改此前五份报告。

## 结论与范围

用户指出的分层问题成立：App 里不仅有 Android 原生能力接口，还固化了 APK 项目扫描、产物选择、日志分析、崩溃归因及调试状态。按照用户明确提出的目标，这些开发策略应由 Ubuntu guest 中的工具、脚本、技能与 Agent 管理，App 提供受控原生能力接口。

但构建本身已经在沙箱执行。本轮没有发现 App 内嵌 Gradle 构建执行器，也不把包管理、Accessibility、VScreen、Root 授权和 session 边界本身算作应当整体移走的业务工具。

- 本地基线：`main` / `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；以下引用针对当前工作树。
- 上游对照：本地只读快照 `/tmp/openminis-review-20261004`，固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。用 `git ls-tree -r --name-only HEAD -- src/android/app/src/main/java/com/openminis/app/tools/android` 确认这个目录在固定上游树中没有文件，不依赖 sparse checkout 的磁盘缺失判断。此结论仅针对本轮文件，不代表上游没有 Android 能力。
- 新增一项架构职责问题、七项功能缺陷；没有重复计入旧报告 F22 的日志任务无法由通用 kill 真正取消。
- 全部是只读源码证据与静态推导，没有修改产品源码、测试、合同或旧报告，没有运行构建、模拟器、设备命令、Root 操作或功能测试。后文验收均为建议，未执行。

| 编号 | 类型／优先级 | 问题 |
|---|---|---|
| AD01 | 架构职责／P2 | Android 开发策略固化在 App，而非沙箱工具与 Agent |
| AD02 | 功能／P1 | APK 暂存缓存缺少 session 身份，可能部署另一会话的产物 |
| AD03 | 功能／P2 | 外挂目录发现 APK 时漏掉正常的 debug／variant 子目录 |
| AD04 | 功能／P2 | 其它包的崩溃可能使目标包被报告为 crashed |
| AD05 | 功能／P2 | 日志游标与任务读取失败仍返回工具成功 |
| AD06 | 功能／P2 | 安装、停止支持 userId，启动却忽略，组合操作跨 profile |
| AD07 | 功能／P2 | 能力探测与应用搜索使用不同 flags，可能误报无可见启动器 |
| AD08 | 功能／P2 | 调试能力宣称支持 Shizuku，但执行器仅走 Direct Root |

<a id="ad01"></a>
## AD01 — 开发工作流在 App 里形成了专用实现

**生产调用链已确认：**

```text
MinisApp 注册 android.deploy / android.logs / android.diagnose 等
  → ToolRegistry.AndroidToolHandler
  → AndroidAgentTools.execute
  → App Kotlin 中的 APK 扫描、调试状态、日志与诊断流程
```

注册入口见 [MinisApp.kt:723](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:723)、[ToolRegistry.kt:378](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:378)。这不是未使用的辅助类。

| 现有职责 | 当前证据 | 按用户目标的归属 |
|---|---|---|
| 遍历工程、识别 Gradle 输出目录、按时间选择 APK | [AndroidApkInspector.kt:186](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:186)；guest、外部挂载、宿主发现有各自实现 | 沙箱开发工具，Agent 明确选择项目、variant 与产物 |
| 组合 inspect → install → launch | [AndroidAgentTools.kt:506](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:506) | Agent／技能组织流程；App 只承担受控的单次安装、启动 |
| 记录目标包、APK、日志游标、PID 与 UI generation | [AndroidDebugSessionStore.kt:7](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDebugSessionStore.kt:7) | 开发任务资料可放会话工作区；底层 UI generation、display lease 等真实性与权限状态仍由 App 校验 |
| 日志轮询、严重度摘要、异常候选与崩溃定位 | [AndroidLogManager.kt:104](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:104)、[AndroidDebugParsers.kt:136](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDebugParsers.kt:136)、[AndroidDiagnoseController.kt:124](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDiagnoseController.kt:124) | 沙箱分析工具与 Agent；App 返回有界原始证据及可靠来源 |
| 开发闭环默认步骤 | [android.debugLoop.md:4](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/android.debugLoop.md:4) | 可由技能／沙箱工具说明维护，不应要求改 APK 才能修改底层分析策略 |

上述六个相关 Kotlin 文件：`AndroidAgentTools`、`AndroidApkInspector`、`AndroidLogManager`、`AndroidDiagnoseController`、`AndroidDebugParsers`、`AndroidDebugSessionStore` 合计 **1648 行**（含空行和注释）。这是相关文件规模，不是“1648 行全部可删除”；其中还包含应保留的原生能力接线。

**已在正确层的部分：** [android.debugLoop.md:3](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/android.debugLoop.md:3) 要求用现有 `shell_execute` 构建；`AndroidCapabilityResolver.executionFacts` 将 Java／SDK 等列为需在 guest 探测。本轮没有证据证明 SDK／Gradle 的构建进程已经被放进 App 宿主执行。

**应保留的 App 边界：** PackageManager／安装请求／Activity 启动、Accessibility 与 VScreen、系统权限检查、调用方身份、审批、输出上限和 session 路径校验。这些涉及 Android framework、Binder、原生权限或安全边界；仅仅给 Kotlin 工具加一层 guest CLI wrapper，不会把它的策略实现搬到沙箱。

**与现有合同的关系：** [01-ARCHITECTURE.md:1](/home/jiale/projects/minis-for-android/docs/contracts/01-ARCHITECTURE.md:1) 允许 App 持有 Android 原生工具，并负责 session、权限和 shell 生命周期。因此这里按用户明确要求记录架构调整目标，不能把“任何 Android 工具在 App”一概说成违反现役合同。App-owned backing 也不等于在 App 进程中实现业务策略。

**调整方向（未实施）：** 先复用现有 guest shell、文件与技能体系，将工程发现、产物选择、日志解析及开发流程放在可独立维护的 guest 工具中；App 收缩为必要原生接口。保留结构化、受授权的调用边界，不为迁移新增通用 Root RPC，不恢复旧 minisd／PRoot／broker。当前 chroot 与 App 共享内核和 App UID，也不因代码下沉就自动获得强安全隔离。

<a id="ad02"></a>
## AD02 — APK 缓存忽略 session，而且不验证内容

**证据：** [AndroidApkInspector.kt:220](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:220) 使用 `WorkspaceFileClient.info(sessionId, linuxPath)` 读取对应会话文件，但 [AndroidApkInspector.kt:230](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:230) 的缓存摘要仅含 `linuxPath + size + modified`，不含 sessionId，也不是 APK 内容摘要。目标统一落到 App 的 `cacheDir/android-deploy/guest/`。[AndroidApkInspector.kt:235](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:235) 发现同大小文件就跳过读取；只有未命中才用当前 sessionId 分块读源文件。

**触发序列：**

1. 会话 A 的 `/workspace/app/build/outputs/apk/debug/app-debug.apk` 已暂存。
2. 会话 B 的同虚拟路径 APK 内容不同，但文件大小和修改时间相同（例如保留时间戳的复制）。
3. B 请求 inspect／install，缓存标识与 A 相同，读取被跳过，实际解析和安装 A 的字节。

同一会话中修改内容却保持大小与时间戳，也会复用旧内容。此外 [AndroidApkInspector.kt:146](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:146) 的安装器暂存按 packageName、versionCode、source mtime 命名，也仅按大小判断复用，存在同类内容陈旧风险。

**影响边界：** 可部署错误代码或另一会话的产物，违反 session 文件语义；本轮没有证明跨 Android UID 读取、远程攻击或数据外发。

**调整方向与后续验收：** 来源身份必须包含 session／真实数据域；部署应与选中产物的实际内容绑定，不能仅以元数据推断相同。用两个合法但不同的 APK、同逻辑路径及相同 size／mtime，核对安装前后的内容摘要与版本行为；再验证同会话重建。未执行。

<a id="ad03"></a>
## AD03 — 外挂目录扫描漏掉正常 Gradle 输出层级

[AndroidApkInspector.kt:194](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:194) 对 `/var/minis/mounts/...` 递归扫描，随后 [AndroidApkInspector.kt:201](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:201) 要求 APK 的**直接父目录**以 `/build/outputs/apk` 结束。

因此 `project/app/build/outputs/apk/debug/app-debug.apk` 的父目录以 `/debug` 结束，会被过滤；带 flavor 的 `apk/demo/debug/...` 也一样。没有其它直接放在 apk 根目录的候选时，返回“no APK”。[ExternalMountAccess.kt:83](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ExternalMountAccess.kt:83) 确认 entry.path 是真实递归路径，不会抹掉子目录。

guest 发现与宿主 `discover(File)` 可以继续向输出目录下遍历；现有 [AndroidApkInspectorTest.kt:15](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/android/AndroidApkInspectorTest.kt:15) 只验证宿主分支，不覆盖生产的外挂目录分支。已阅读测试，未运行。

**调整方向与后续验收：** 复用一个工程输出选择规则，覆盖 guest 与 SAF 挂载的 debug／flavor 目录，同时避免把下载目录的任意 APK 当作 Gradle 产物。按 AD01，下沉这类项目发现规则比继续在 App 内维护多份分支更符合用户目标。未执行。

<a id="ad04"></a>
## AD04 — 诊断可能把其它应用的崩溃归给目标应用

- [AndroidLogManager.kt:228](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:228) 将任何 `FATAL EXCEPTION` 当作崩溃块起点，不要求 PID 或 package 匹配。
- [AndroidDiagnoseController.kt:145](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDiagnoseController.kt:145) 的 crash buffer 筛选保留所有含 `AndroidRuntime`、`FATAL EXCEPTION` 的行；它们可能属于另一个包。
- [AndroidDebugParsers.kt:142](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDebugParsers.kt:142) 选择最后一次 fatal 起点，虽提取 `Process:`，却不校验 process 是否是传入 packageName 或其子进程。
- [AndroidDiagnoseController.kt:51](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDiagnoseController.kt:51) 对任何非空解析结果都设置目标诊断 `status=crashed`。

**具体后果：** A 正常运行，设备上 B 是 crash buffer 中最近的崩溃；调用 diagnose(A)，可能同时得到目标 package=A、crash.process=B、status=crashed。Agent 会沿着 B 的异常修 A。cursor 分支同样缺少完整崩溃块归属验证。

现有 [AndroidDebugParsersTest.kt:57](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/android/AndroidDebugParsersTest.kt:57) 只给出单个目标包的崩溃，没有多包混合用例，不能证明归因正确。

**调整方向与后续验收：** 原始日志附可靠 PID、包与时间来源；分析按完整块及目标进程关联，未知归属不应当作目标崩溃。用 A 正常、B 崩溃，以及 A:B 子进程、PID 变化、多个交错异常分别验证归属。未执行。

<a id="ad05"></a>
## AD05 — 日志读取失败被包装成工具成功

[AndroidLogManager.kt:74](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:74) 的 `CURSOR_NOT_FOUND`、`CURSOR_SESSION_MISMATCH`、`CURSOR_BOOT_CHANGED` 返回只有 status 的 JSON；[AndroidLogManager.kt:153](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:153) 的 `JOB_NOT_FOUND` 同样没有 `success=false`。

[AndroidAgentTools.kt:472](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:472) 的成功条件却是：

```kotlin
!result.has("success") || result.optBoolean("success") ||
    action in setOf("mark_cursor", "snapshot", "watch", "read", "stop")
```

缺 success 字段就视为成功，而且 read 等动作会覆盖显式 false。因此不存在、跨会话或重启失效的游标读取，也会返回 `ToolExecutionResult.success=true`。错误 status 文本仍在输出中，但机器可读的成功标识与结果冲突。

**调整方向与后续验收：** 成功标识应表达实际完成情况；权限不足／能力受限与失败应明确区分。分别读不存在游标、另一会话游标、不存在 job，并核对顶层失败状态。这里不重复统计旧 F22 的任务取消缺陷。未执行。

<a id="ad06"></a>
## AD06 — install_and_launch／restart 没有保持目标 Android 用户

[AndroidAgentTools.kt:508](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:508) 将 userId 传入安装，但 [AndroidAgentTools.kt:511](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:511) 启动时不传。[AndroidPackageController.kt:207](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidPackageController.kt:207) 的 restart 先按 userId stop，再调用不接受 userId 的 launch。

[AndroidPackageController.kt:134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidPackageController.kt:134) 优先用当前 App Context 启动；特权后备 [AndroidPackageController.kt:308](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidPackageController.kt:308) 的 `am start` 和 `monkey` 也没有指定用户。

**触发条件：** 设备存在工作资料／第二用户，目标 userId 与当前 App 用户或系统命令默认用户不同。安装到指定 profile 后，启动可能失败或打开另一个 profile 的旧版本；restart 可能停止一处却启动另一处。具体默认用户行为需真机确认，不能在静态报告中一概写死为 user 0。

**调整方向与后续验收：** 受控原生接口应显式保持同一 userId；无法在该用户启动时应明确不支持，不能静默用默认用户。两个 profile 安装同包不同版本，分别核对安装、PID 所属用户、停止与启动结果。未执行。

<a id="ad07"></a>
## AD07 — 能力页误用启动器查询 flags

[AndroidCapabilityResolver.kt:248](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt:248) 对 MAIN + LAUNCHER 使用 `MATCH_DEFAULT_ONLY`，以数量 0 判定 UNAVAILABLE。[AndroidPackageController.kt:36](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidPackageController.kt:36) 的搜索路径却已明确改为 flags=0，源码注释说明启动器通常缺 CATEGORY_DEFAULT，旧 flags 会漏掉。

两条路径处理同一种可见启动器，却使用不一致的查询条件。因此 capabilities 可以报告没有可见启动器，而紧接着 android_app search 能查到应用。这是可见性探测不一致，不是所有包都应对 App 可见。

**调整方向与后续验收：** 共用必要的原生可见性查询规则；在只有 LAUNCHER、没有 DEFAULT 的应用上核对能力数量和实际搜索一致。仍保持 Android package visibility 边界。未执行。

<a id="ad08"></a>
## AD08 — 调试能力宣称 Shizuku 可用，实际执行器没有这个后端

[AndroidCapabilityResolver.kt:57](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt:57) 将 Root 已授权或 Shizuku READY 都视为 privilegedAvailable；[AndroidCapabilityResolver.kt:133](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidCapabilityResolver.kt:133) 据此把 full-device logcat、dumpsys、meminfo 宣称为 AVAILABLE，并可标记 backend=shizuku。默认开发提示词也说这些操作可以使用 Shizuku。

但 [PrivilegedCommandRunner.kt:355](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt:355) 的 run 全路径只调用 [PrivilegedCommandRunner.kt:391](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt:391) 的 `DirectRootRunner.runArgv`；rootOnly 只影响错误说明，没有选择 Shizuku 的分支。Root 失败时日志／部分诊断回退到 App 自身权限，并不会因此通过已授权 Shizuku 获得目标包完整证据。

**触发条件：** Shizuku 已就绪，但 Direct Root 不可用／未获授权。能力声明与实际执行不符；在支持 Root 的设备上，单独的 Shizuku 授权也不证明这条调试执行链已获得权限。

**边界：** 仅指本轮包／日志／诊断执行器，不是“项目所有 Shizuku 功能失效”。VScreen 有独立 UserService，guest 也有单独的 Shizuku handler；不能混为一个后端。

**调整方向与后续验收：** 能力状态按实际接入的路径报告；是否接入已有受控 Shizuku 能力需按产品边界决定，不能仅以管理器 READY 宣称所有操作可用。分别在仅 Root、仅 Shizuku、都不可用三种条件核对声明、实际 backend 与可读证据。未执行。

## 全文阅读与审计进度

本轮登记了 18 个 Kotlin 文件及 2 个默认提示词的全文阅读，共 3306 行（含空行、注释）。除了上述九个主体 Kotlin 文件，还完整阅读了两份既有测试、AndroidOperationRiskPolicy、AppSearchPolicy、PackageWaitPolicy、AppStatePolicy、ExternalMountAccess、ToolExecutionResult、PromptModuleRegistry。

另对注册入口、guest 文件 API 和相关合同核对调用边界；片段核对不冒充全文阅读。此前报告属于专题／调用链审计，不能据此断言整个代码库已完整看过。

全库 tracked 文件清单及本轮可核验全文记录见 [代码覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。台账按内容 SHA-256 固定阅读版本；后续文件发生变化，需要重新核对，旧的阅读标记不能自动代表新内容。

本次清单有 1670 个 tracked 文件，其中代码、测试、配置、源资源与 fixture 合计 1546 个；正文档、许可证、二进制及批量字典数据 124 个另列。当前登记全文 20 个、片段 4 个，其余 1522 个尚未登记全文。这里透明记录可核验进度，不把此前专题审计的命中数量折算成全库覆盖率；全库完整阅读任务尚未完成。

## 本轮验证的限制

本报告只确认源码职责、分支条件与数据流，并读了现有测试的覆盖范围。没有任何设备运行或修复通过声明。后续修复验收应保存实际 APK 摘要、工具 JSON、目标用户／PID、原始日志及同一操作序列，使结果可重复复核；本轮仅提供建议，不启动这些流程。
