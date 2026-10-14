# 提示词存储修复与显式重置交互审计

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文4文件、679行；新增PMR01一项，复核旧PM/UI问题。仅静态阅读源码和现有测试，没有改代码、编写/运行测试、保存实际提示词、访问模型/凭据、构建、模拟器、adb、Root、设备或云任务。此前148份报告及所有旧台账保留。

## PMR01 · P3 · 点击重置模块后编辑框仍保留旧草稿

[SystemPromptModulesScreen.kt:136](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt#L136) onReset启动IO调用PromptModuleStore.resetOverride，没有关闭sheet、重置本地text或发送重置事件。[SystemPromptModulesScreen.kt:188](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt#L188) ModuleEditorSheet.text只remember(snapshot.module.id)，即使snapshot.text从覆盖恢复成默认，同一模块的草稿也不重新初始化。

真实持久化 [PromptModuleStore.kt:188](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt#L188) resetOverride删该模块覆盖文件并refresh，snapshotsFlow传回默认snapshot；这一步已完成，但UI的SettingsTextArea继续绑定旧text。静态顺序：模块覆盖A、默认D → 打开模块sheet显示A → 用户点Reset → 文件已回D、sheet仍显示A → 点击Save第209行传旧A → saveOverride再次生成A覆盖。即使用户没有额外输入未保存内容也触发，重置操作本身不作用于当前编辑框。

与PM02外部Agent更新丢用户草稿不同：保留草稿修复是正确方向，但显式用户Reset应有自己的草稿更新语义。这里是由原remember(id,snapshot.text)改为remember(id)后，Reset没有同步配套的交互遗漏。不是说disk reset没有执行，也不是按Save后恢复A声称没有任何持久化能力；缺口是重置后用户看到和再次提交的仍为旧值。

方向记录：外部变化保留用户草稿，主动Reset成功时用默认正文重置当前草稿，或明确结束编辑周期；只有持久化成功才更新反馈。未实施。缺少UI-reset测试，本轮不写或运行测试。

## 旧修复的范围

原编号来自[提示词模块报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-prompt-module-persistence-editor-audit.md)及[编辑页报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-04-ui-editing-audit.md)。

| 原编号 | 当前静态状态 |
|---|---|
| PM01 两个成功保存磁盘/cache相反 | CustomPromptStore写盘及发布同锁，原交错已修；saveOverride固定tmp也进入锁 |
| PM02 Agent模块更新覆盖未保存输入 | remember只key module.id，原外部更新丢稿已修 |
| PM03 空文本称移除实际恢复默认 | ConfigBuiltins描述已准确说明清覆盖/恢复默认及enabled=false关闭，原承诺差异已修 |
| UI05 自定义提示词保存失败仍发布成功 | catch后仍cache=normalized/textFlow，原失败一致性缺口仍在 |
| UI06 保存期间新输入被draft=null清掉 | Settings页仍在IO返回后无条件清draft，原入口未变 |

[CustomPromptStore.kt:83](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt#L83) CustomPromptStore.save把delete/write/tmp rename与cache发布置于synchronized(lock)。两个成功保存不能再形成先写A、B写并发布、A后发布的旧顺序。tmp争用也由同锁排除。ConcurrentTest调用真实Store，8线程各60次成功save后比较disk/cache/flow，另有blank清理；没有失败磁盘、删除拒绝、模块reset或真实Android UI，所以不能宣称失败路径也已通过。

但同方法catch仅日志，仍发布normalized；clear遇delete失败也仅日志后发布空值。[SystemPromptSettingsScreen.kt:70](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt#L70) UI保存后draft=null，状态按flow得出saved。[AgentSystemPrompt.kt:44](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/prompt/AgentSystemPrompt.kt#L44) 真实模型提示词读cache，因此错误不只影响显示。保持旧UI05编号，不因新锁再次计数；异常/拒绝时不能把“持久化和发布在一把锁”误读成成功事务。

[ConfigBuiltins.kt:1071](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt#L1071) 模块text字段现明确空值清覆盖/恢复默认，要省略section应设enabled=false，与saveOverride/Composer职责一致，PM03原错误说明已修。这个enabled控制的是提示词模块注入，不是工具执行权限边界。

## resetAll与缓存候选

PromptModuleStore.saveOverride/resetOverride在lock内改文件并refresh；load对不可读override返回null/default；setEnabled先apply后refresh，resetAll逐文件删除、prefs清键后refresh，文件删除步骤仍在锁外。并发resetAll与saveOverride是否让重置保留覆盖或刷新不对应调用完成顺序，属于同一存储协调家族候选；没有在本轮另外编号，不能靠saveOverride加锁就宣称所有reset/write已串行。

CustomPromptStore.text首次加载cache并不发布textFlow，但warm/refresh及Settings页LaunchedEffect会发布；不能只凭此断言编辑页必然显示空。read失败降空还会影响草稿来源，但需真实错误处理合同闭环，不重复新建一个“所有提示词会丢失”泛化。

Settings模块开关订阅权威snapshots，保存完成关闭sheet，Reset保持sheet。没有保存中禁用状态、旧IO完成后editingId=null是否误关后来打开的新模块需要可达时序；记录候选，不以任何异步代码都可能出错为理由计Bug。

## 固定上游与全文范围

沿用旧报告已核对的固定OpenMinis HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git树：没有本地这组prompt模块存储/编辑页实现。PMR01依本地现役组件行为，不声称上游有可直接替换的同名修复。本轮没有fetch上游。

| 新增全文文件 | 行数 |
|---|---:|
| [CustomPromptStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt#L1) | 124 |
| [PromptModuleStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt#L1) | 253 |
| [SystemPromptModulesScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt#L1) | 241 |
| [CustomPromptStoreConcurrencyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/prompt/CustomPromptStoreConcurrencyTest.kt#L1) | 61 |

四文件均完整输出，无截断。SystemPromptSettingsScreen已是全文覆盖，本轮再次全文复核不重复统计；ConfigBuiltins、AgentSystemPrompt只看片段。

新累计台账：[2026-10-07-code-review-coverage-37158d18-prompt-store.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-prompt-store.json)。全文 **1196 / 1652文件、192881行**；partial4、not_registered452、excluded124，tracked1776。全库通读尚未完成。

交付核验旧148份报告与所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776远端快照Git blob/hash/bytes/lines及本报告证据行号。仅新建两文件。
