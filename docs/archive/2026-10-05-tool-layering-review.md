# Android 开发工具分层复核

日期：2026-10-05。源码基线：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本报告单独新建，只排查和记录。没有修改产品源码、测试、合同或此前报告，没有启动构建、测试、模拟器、设备操作或云任务。

## 用户指出的分层问题成立

当前 App 承担了工程发现、APK 选择、日志分析、崩溃归因和开发任务状态。这些策略按用户要求应放在 Ubuntu guest 中，由 Agent 使用工具、脚本和技能维护。App 提供必要的 Android 原生接口以及权限、session 和调用边界。

这是对此前 [Android 工具报告 AD01](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-android-tool-ownership-audit.md:28) 的进一步复核，不重复统计为新增问题。当前合同允许 Android 原生工具存在于 App；不能因此认定全部 Android 接口都违反合同。

生产调用链是：`MinisApp → ToolRegistry.AndroidToolHandler → AndroidAgentTools → 各 Kotlin controller/parser`。入口见 [MinisApp.kt:723](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:723)、[ToolRegistry.kt:378](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:378)。这些实现正在生产注册，不是单纯的历史文档。

| 工作 | 当前实际实现 | 按用户目标的归属 |
|---|---|---|
| 搜索 Gradle 工程输出、按修改时间排列 APK、默认选择候选 | [AndroidApkInspector.kt:106](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:106)、[AndroidApkInspector.kt:186](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:186) | guest 开发工具；由 Agent 明确项目、variant 和产物 |
| 编排 inspect、install、launch 三步 | [AndroidAgentTools.kt:491](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:491) | Agent／技能组织；App 接受明确产物并完成受控安装、启动 |
| 解析日志、提取严重度摘要和异常候选 | [AndroidLogManager.kt:236](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:236) | guest 分析工具；App 获取有界原始日志并报告真实权限和来源 |
| 从日志推断目标是否 crashed、解析 meminfo、判断 ANR | [AndroidDiagnoseController.kt:14](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDiagnoseController.kt:14) | guest 分析工具和 Agent；原生证据获取保留在 App |
| 保存开发目标包、产物路径、复现起点等任务资料 | [AndroidDebugSessionStore.kt:7](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDebugSessionStore.kt:7) | session 工作区中的可持久任务资料；权限、UI generation、display lease 等仍由 App 校验 |

APK 的 PackageManager 元信息读取、SAF 授权文件暂存、安装器调用、Accessibility、VScreen、审批和真实设备能力检查有 Android framework／Binder／权限依赖，需要保留原生接线。不能把整个 controller 原样搬入 guest，也不能让 Agent 自行认定权限或恢复过期的 UI 引用。

**区分执行位置与入口名称：** 从 guest CLI 调到 App，再由 Kotlin 完成项目扫描和日志分析，策略仍在 App。只有把这些策略的实现和任务资料实际迁到 guest，才符合用户提出的管理方式。

**构建已经在 guest：** [AndroidAgentTools.kt:178](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:178) 明确使用 `shell_execute` 构建；[android.debugLoop.md:3](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/android.debugLoop.md:3) 同样要求用该入口。没有证据表明 Gradle／SDK 构建进程在 App 宿主执行。

**提示词可以覆盖：** [PromptModuleStore.kt:19](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt:19) 支持用户覆盖默认模块，设置编辑器及 `minis-config` 可修改。因此不能说“调整任何开发流程都必须重新打包 APK”。但覆盖文字不会改变 Kotlin 内的 APK 选择和崩溃解析规则；这些规则目前仍随 App 代码发布。

**基础系统维护与开发工具分开：** [UbuntuProvisioner.kt:14](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuProvisioner.kt:14) 安装固定的基础包，是受控 rootfs 维护。项目 SDK、构建依赖、分析脚本和技能应在可写的 guest 用户数据中管理。不能为了让 Agent 管工具就开放任意 Root 安装入口或把 rootfs 改成全局可写；当前 [存储合同](/home/jiale/projects/minis-for-android/docs/contracts/03-STORAGE-CONTRACT.md:9) 已区分 Root-owned rootfs 与 App-owned guest 用户目录。

## 新定位的后果：日志复现起点随 App 进程消失（P2）

[AndroidLogManager.kt:36](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:36) 把游标存入进程内 `ConcurrentHashMap`；[markCursor:40](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:40) 生成 UUID 后只写入该 map，没有写入会话文件或持久仓库。

[cursorJson:268](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:268) 虽返回时间、包、PID、bootId，但 [readSince:73](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt:73) 只接受 map 中存在的 ID，没有从先前返回的资料重建起点的路径。

触发条件：Agent 在复现前获取 cursor；App 被系统回收或用户关闭，设备未重启；重新打开原聊天后，用之前工具输出中的 ID 读取日志。map 已为空，结果为 `CURSOR_NOT_FOUND`，即使该时间段的 logcat 仍在，也不能继续沿原起点读取。`AndroidDebugSessionStore` 的目标包和产物路径同样仅在内存，见 [AndroidDebugSessionStore.kt:19](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDebugSessionStore.kt:19)。

这是开发任务恢复资料与 App 生命周期耦合的具体后果。此前 AD05 记录的是错误被包装为成功；本条记录的是游标本身不能跨 App 进程恢复，二者触发原因不同。没有声称 Android 日志必然保存，也没有声称后台 watch 应在进程死亡后继续执行。

建议方向，未实施：复现目标、起点和产物身份作为 session 任务资料保存；每次取证重新确认 bootId、目标 PID、权限和日志是否覆盖该区间。过期的屏幕引用及授权状态必须重新校验。不能靠重新 mark_cursor 恢复之前的起点，因为新游标会改变复现区间。

## 上游对照与证据限制

只读上游快照 `/tmp/openminis-review-20261004` 的提交为 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。本轮用 `git ls-tree -r --name-only HEAD --` 检查上述 `tools/android` 目录和 `android.debugLoop.md`，该固定提交没有这些路径。此结论仅说明这套具体实现不在该上游树中，不代表上游没有设备控制，也不能据此判定某个人是谁或何时引入了代码。

全部结论基于当前源码及调用链。没有执行进程回收／重启复现场景。后续验收应保留“同一会话、同一设备 bootId、重启 App 前后的游标读取结果”作为可复核工件；本轮未运行。

本轮围绕工具分层复核相关片段，没有新增全库全文阅读覆盖计数，也没有声称已读完全部源码。
