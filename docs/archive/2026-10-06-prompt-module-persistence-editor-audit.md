# 提示词模块、保存一致性与编辑草稿静态审查

日期：2026-10-06。本轮确认 3 项新问题：2 项 P2、1 项 P3。独立保存报告，只更新阅读台账，不修改产品代码和旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读取既有上游 Git 对象。没有构建、执行测试、启动模拟器、使用 adb/Root/设备/云资源、访问账号、读取凭据或启动子 Agent。下列竞态是静态推演，未声称设备复现。

## PM01 · P2 · 并发保存后，自定义提示词缓存可以与磁盘相反

[保存入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt:76)在锁外写文件；直到 [96 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt:96)才进入锁发布本次传入的 normalized。文件提交和缓存发布没有同一临界区。UI 的 [IO 保存](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:70)和配置工具的 [writer](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:999)都调用该入口，UI 保存按钮也没有正在保存的禁用状态；配置确认不等于与 UI 文件写入互斥。

无需任何写盘故障就可成立的交错：A 写完并成功 rename 为文本 A，暂停在发布缓存前；B 写完并成功 rename 为文本 B，发布缓存 B，返回；A 恢复，发布缓存 A，返回。最终磁盘是 B、cache/textFlow 是 A。设置页与后续模型请求使用 A，进程重启或 refresh 后变成 B。[请求入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/AgentSystemPrompt.kt:44)读取缓存，因此差异会影响实际指令，而不仅是页面显示。

此外两次保存复用 [custom.md.tmp](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt:86)，会争用同一临时文件。模块 [saveOverride](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt:158)也在锁外使用固定临时文件；这里作为相同写入边界的核对结果，不另计问题，不把模块的 refresh 行为误说成直接发布 normalized。

修复方向记录：在现有 Store 内将文件变更和缓存发布纳入同一个写入协调边界，并保留失败语义。本项不同于旧 UI05 的“单次写盘失败仍报告成功”；本项两个写入都成功也可触发。

## PM02 · P2 · Agent 更新模块正文会清掉用户尚未保存的草稿

模块页 [订阅 snapshotsFlow](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt:71)，[将最新 snapshot 传入正在编辑的 sheet](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt:117)。编辑文本却用 [remember(module.id, snapshot.text)](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt:187)保存。一旦存储正文改变，remember 的 key 改变，用户当前 text 被重新初始化为新 snapshot.text。

静态触发顺序：用户打开 style.tone，输入未保存草稿 X；同时 Agent 通过已确认的配置写入该模块文本 Y；saveOverride 调用 refresh、发布新快照；sheet 仍打开，但输入框直接换成 Y，X 消失。用户还没有取消、保存或点重置。这里只讨论同一模块正文变化；切换 enabled 而正文不变不会触发这个 key 的重建。

自定义提示词页的 [draft ?: saved](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:59)至少会保留非空 draft，这进一步说明模块 sheet 的丢稿来自其初始化 key。本项不同于旧 UI02 的离开页面丢草稿：用户全程留在编辑器里。

修复方向记录：编辑周期内固定草稿；存储外部更新应保留用户输入并明确呈现冲突，只有用户主动重置或重新开始编辑才替换草稿。

## PM03 · P3 · 配置工具称空文本会移除模块，实际恢复默认正文

[配置字段说明](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:1039)明确写着“An empty value removes this section from the prompt”。实际 writer 调用 saveOverride；[空文本分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt:163)删除覆盖文件，随后 refresh 回到 shipped default，且未改变 enabled。

对默认开启的 style.tone 写入空字符串后，后续 getter 和模型提示词仍有默认 Tone and style 段落；并不会移除该段落。真实移除可通过 enabled=false，所以本项是接口承诺和行为不一致，不是没有关闭能力。

[现有 blank 测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/prompt/SystemPromptComposerTest.kt:104)直接制造 overrideText 为空的内存 snapshot，验证 composer 会跳过空段落；它没有经过 saveOverride 的删文件语义，无法验证配置空写入真的会生成这种 snapshot。本轮仅阅读测试源码，没有执行测试。

修复方向记录：统一空值与 reset/disable 的语义及对外说明；优先复用已有 enabled/reset 行为，不另建提示词配置层。

## 上游及既有问题边界

固定 OpenMinis 树中没有本地 prompt 包、SystemPromptModulesScreen.kt 和 SystemPromptSettingsScreen.kt。这三项按本地新增功能的现役调用链判断；不声称上游已有同名实现或修复。没有 fetch，也没有执行上游代码。

旧 UI02（离开编辑页丢草稿）、UI05（保存失败仍显示成功）、SS 系列会话设置问题仅用于排除重复。本轮没有重复计算注释噪音、OkHttp/Preferences/helper 复制、Lint 基线或 Android 工具归属问题。内置提示词确实通过 ChatViewModel 10549 行的 AgentSystemPrompt.base 进入运行链；没有把模块开关误当作执行权限边界。

## 阅读范围与核验

本轮新增全文记录 25 个文件、1459 行，其中 PromptModuleStore 从 partial 补为 full；已读 PromptModuleRegistry 只复核，不重复计数。较长 fixture 输出曾被截断，已分段重读 memory_off 的 1—55/56—114 行、memory_on 的 1—57/58—121 行；默认资产也拆分重读后才登记全文。

累计全文 919 / 1546 个文件、187220 行；partial 12，not_registered 615，排除非源文本等 124。全库通读尚未完成。

完整新增文件清单：

- [src/android/app/src/main/assets/prompts/core.intro.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/core.intro.md)：1 行。
- [src/android/app/src/main/assets/prompts/env.variables.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/env.variables.md)：5 行。
- [src/android/app/src/main/assets/prompts/files.creation.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/files.creation.md)：10 行。
- [src/android/app/src/main/assets/prompts/memory.notice.disabled.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/memory.notice.disabled.md)：4 行。
- [src/android/app/src/main/assets/prompts/memory.notice.enabled.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/memory.notice.enabled.md)：9 行。
- [src/android/app/src/main/assets/prompts/paths.inlineMedia.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/paths.inlineMedia.md)：8 行。
- [src/android/app/src/main/assets/prompts/paths.minisUrl.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/paths.minisUrl.md)：9 行。
- [src/android/app/src/main/assets/prompts/paths.sharedDirs.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/paths.sharedDirs.md)：9 行。
- [src/android/app/src/main/assets/prompts/scheduled.tasks.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/scheduled.tasks.md)：1 行。
- [src/android/app/src/main/assets/prompts/style.tone.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/style.tone.md)：3 行。
- [src/android/app/src/main/assets/prompts/style.toolCall.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/style.toolCall.md)：6 行。
- [src/android/app/src/main/assets/prompts/terminal.interactive.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/terminal.interactive.md)：1 行。
- [src/android/app/src/main/assets/prompts/tools.available.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/tools.available.md)：7 行。
- [src/android/app/src/main/assets/prompts/tools.memory.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/prompts/tools.memory.md)：2 行。
- [src/android/app/src/main/java/com/openminis/app/prompt/AgentSystemPrompt.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/AgentSystemPrompt.kt)：76 行。
- [src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt)：121 行。
- [src/android/app/src/main/java/com/openminis/app/prompt/PromptModule.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/PromptModule.kt)：67 行。
- [src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/PromptModuleStore.kt)：247 行。
- [src/android/app/src/main/java/com/openminis/app/prompt/SystemPromptComposer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/SystemPromptComposer.kt)：45 行。
- [src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptModulesScreen.kt)：239 行。
- [src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt)：144 行。
- [src/android/app/src/test/java/com/openminis/app/prompt/PromptModuleRegistryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/prompt/PromptModuleRegistryTest.kt)：59 行。
- [src/android/app/src/test/java/com/openminis/app/prompt/SystemPromptComposerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/prompt/SystemPromptComposerTest.kt)：151 行。
- [src/android/app/src/test/resources/prompts/legacy_modules_memory_off.txt](/home/jiale/projects/minis-for-android/src/android/app/src/test/resources/prompts/legacy_modules_memory_off.txt)：114 行。
- [src/android/app/src/test/resources/prompts/legacy_modules_memory_on.txt](/home/jiale/projects/minis-for-android/src/android/app/src/test/resources/prompts/legacy_modules_memory_on.txt)：121 行。

交叉核对 ConfigBuiltins 975—1082、ChatViewModel 10530—10585、ConfigBridge 644—665 行。所有 1670 个跟踪路径及其 SHA256/字节数、正整数文本行数均重新核对；58 份旧报告摘要未变。本地 HEAD 固定，产品 tracked/staged diff 为空。新报告本地引用与行号另行核验；上游缺失路径已用固定 Git 树核对。阅读台账只代表已完整看到的源码内容，不代表测试通过或整个系统已无问题。
