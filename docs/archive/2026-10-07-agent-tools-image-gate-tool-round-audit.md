# 工具暴露门控、ToolRound抽取与子代理测试范围审查

日期：2026-10-07。远端主线已读取到`ea620e9ec51a246ce76cdec45a8d43109462f472`（#142）。独立新报告与累计台账；旧192份报告和所有旧台账不覆盖。本轮全文5文件、1776行，另重读已全文的SubAgentToolExposureTest47行，不重复计数。仅静态阅读和Git获取：没有改产品代码、编写/运行测试、构建、模拟器、adb、Root、设备、云资源、凭据或模型/工具接口调用。

## ATG01 / P2：注册表合并把无视觉能力时应隐藏的读图工具重新暴露

当前真实生产条件：FULL工具预设、主模型inputModalities没有image、未配置Vision Group。[ChatViewModel.kt:990](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L990)把两项能力事实传给makeAgentTools；[AgentTools.kt:70](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt#L70)因此不添加read_image。可是函数后半段又合并注册表所有未被已有工具代表的定义。

[AgentTools.kt:103](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt#L103)的legacyCanonicals只从已添加工具计算。read_image因为被能力门控排除，其canonical也不在集合；[ToolRegistry.kt:136](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt#L136)definitions返回所有handler定义，并不按视觉能力过滤。合并的另两类条件只保护Bot身份和子代理嵌套委派，没有视觉条件。

[MinisApp.kt:794](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L794)生产启动无条件注册LinuxReadImageHandler及read_image别名；[ToolRegistry.kt:467](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt#L467)canonical是linux.file.image.read。所以两个视觉布尔值都为false时，最终工具列表仍包含linux.file.image.read；这不是只看注释推断，而是注册、条件列表、去重及合并链路共同决定。

[ChatViewModel.kt:6668](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L6668)普通工具轮次直接使用agentTools，并为每项加入canonical、wire名和别名；[ToolRound.kt:65](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ToolRound.kt#L65)的实际执行门允许本轮工具和其canonical映射。因此后置“只执行本轮已提供工具”拒绝逻辑无法弥补此错误：图片工具本来就已经被错误放进本轮列表。

影响：文本模型没有视觉组也看到并可尝试调用读图能力，违背现有能力门控；可能产生无助于该模型的图片/元数据结果和额外工具轮次。这里确认的是工具选择与能力不一致，不声称所有Provider必然报错，不把它描述成文件权限逃逸或Root越权；执行仍受既有文件、权限和预算边界控制。

CORE在合并前提前返回，不受此合并问题影响；chat-only列表为空也仍拒绝执行。至少一项视觉能力成立时，read_image已有定义，其canonical进入去重集合，行为不同。不要把这些正常路径一起报成失效。

与旧RI01区别：RI01是已有Vision Group时实际描述路由断开；ATG01是根本没有视觉能力或Vision Group却仍暴露工具。修复描述执行不能自动修复列表门控。已检索旧报告，没有把RI01重新编号。

记录方向：注册表保留系统执行能力，但轮次列表合并必须保留前面做出的能力筛选；优先在现有合并处复用同一门控，不新建平行注册表。未来授权验收用真实App注册配置和公开Agent请求，覆盖视觉能力两布尔组合、FULL/CORE/chat-only及canonical/alias，保留Provider请求工具列表和执行记录；当前只记录，不实现或运行。

固定OpenMinis `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的[AgentTools:52](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt#L52)同样先做视觉能力条件，但该makeAgentTools函数没有末尾注册表全量合并，因而这个分支不会在本函数被加回。仅读既有上游对象/片段，未fetch、未运行，不据此建议恢复其旧PRoot实现。

## #142：ToolRound职责抽取真实发生，不能据此宣布整个Agent engine已脱离ViewModel

远端本次仅变化ChatViewModel、新增ToolRound及ToolRoundTest：把每个调用的轮次门、参数修复、截断写拒绝、loop detector、preflight、执行、UI/模型结果折叠移出ViewModel。[ChatViewModel.kt:1060](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L1060)仍由VM提供preflight、executeTool和UI更新宿主；[ChatViewModel.kt:7785](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L7785)实际创建并运行ToolRound。onExecuting在工具体之前设置副作用标志，是保持取消/失败边界的实际接线。

工具列表生成未变化，所以ATG01在a94f98c9和最新ea620e9e都成立。该提交改善代码职责范围，不是本轮要求修改代码，也没有把抽取本身计成缺陷。

ToolRoundTest全文146行，8例调用真实ToolRound搭配FakeHost：未提供拒绝、chat-only拒绝、preflight拒绝、成功/失败/超时、混合调用顺序及参数解析。它没有启动App注册流程、没有真实Provider、没有测试ATG01、截断写canonical/alias矩阵、实际取消退出或完整VM回合。chat-only用空offered和other工具；不能把这个例子扩大为所有宿主状态组合已验证。未运行测试，未宣称通过。

新ToolRound仍按原始name只对file_write/file_edit执行截断写拒绝，并保留修复/拒绝日志片段；canonical别名覆盖和敏感原文日志需要与参数修复、Provider名字归一化及既有脱敏问题对照，不仅凭此两个字符串就宣布可达数据损坏或新增泄露。本轮不重复计旧问题，也不把候选写成已复现事实。

## 子代理模型选择测试：已有覆盖及真实限制

SubAgentTaskTest171行直接核对参数解析、默认/限幅、结果与回执、brief指令、roster及schema、进度、旧prompt兼容、model空白处理和模型枚举。它能证明纯解析/schema预期，不能证明实际模型凭据或Provider请求选择。

SubAgentRuntimeTest707行使用真实Runtime/Registry和FakePort，覆盖后台/阻塞完成、排队晋升、取消/steer、父会话隔离、超时/创建失败、无模型、非法输入、named model优先及未知名称拒绝、空列表拒绝、固定模型忽略选择与默认model_choice。后续恢复例使用MemStore并重建Runtime/Registry，仍复用同一测试进程和scope，不能当Room落盘、进程死亡、设备重启的恢复证据。

FakePort的模型解析只制造entry字符串及origin，固定模型相关断言不等于真实Repository/凭据选择已验证。配置在排队期间被删除/改名、同名#序号重新排序、旧任务与恢复执行交错仍需既有生产链和未来授权验收闭环；不因缺少某个测试就另计产品Bug。

SubAgentToolExposureTest47行仅核对roster、旧单次schema兼容及child没有委派工具；无真实完整注册表初始化，也无视觉门控组合。开关关闭回到旧schema是该测试明确保护的兼容行为，本轮不自行解释为“所有委派必须禁用”。

## 覆盖和交付

| 本轮新增全文 | 行数 |
|---|---:|
| [AgentTools.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt#L1) | 330 |
| [ToolRound.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ToolRound.kt#L1) | 422 |
| [SubAgentRuntimeTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt#L1) | 707 |
| [SubAgentTaskTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentTaskTest.kt#L1) | 171 |
| [ToolRoundTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/ui/chat/ToolRoundTest.kt#L1) | 146 |

AgentTools1—129、130—210、211—330；SubAgentRuntimeTest1—240、241—480、481—707；SubAgentTaskTest1—110及111—171；ToolRound1—140、141—280、281—422；ToolRoundTest1—146完整输出。初次组合输出截断处已补读，不把定位检索或VM diff算全文。

新版本只继承内容SHA-256一致的文件覆盖；ChatViewModel内容变化，旧片段范围失效，现版仅登记975—1005、1050—1091、6665—6689、7782—7808。MinisApp此前partial范围已有本轮注册片段，仍不算全文。新增2路径，总tracked1788、included1664、excluded124。

累计全文 **1378 / 1664文件、232260行**；partial10、尚未登记276。全库通读仍未完成。新台账：[2026-10-07-code-review-coverage-ea620e9e-agent-tools.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-ea620e9e-agent-tools.json)。

交付校验：旧192份报告和所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged代码diff、1788快照Git blob/SHA-256/字节/行数、台账与本报告引用行号。只新增本文及新版台账。
