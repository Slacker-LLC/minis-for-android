# 新远端审阅：子代理命名模型选择与覆盖迁移

日期：2026-10-07。固定远端提交 `9e523652b7ea80d7cc5f50e346f7038059896c0f`，提交主题为子代理从用户 Agent Models 列表选择模型（#140）。交付校验时origin/main已更新至`a94f98c97d0c478eff8530e9e7758d3b93216b83`（#141，上下文卸载与循环内策略抽取）。本轮7个全文文件在两提交间未变，ChatViewModel片段仅属于本轮固定快照；新提交5个变更/新增路径下一轮重核。本文和覆盖台账均为新文件，保留旧187份报告及全部旧台账。

本轮全文静态阅读7文件、1230行，另读取4文件片段。没有修改产品代码、编写或运行测试、构建、启动模拟器、调用adb/Root、使用设备或云资源。没有新增已确认缺陷编号；下列两项明确列为待核实候选。

## 已核实的调用链

设置页展示用户直接配置的模型列表，添加、删除和重排沿用ProviderRepository。[ProviderRepository.kt:1482](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt#L1482)删除最终通过setAgentLoopEntryIds持久化；resolvedAgentLoopEntries按用户列表顺序解析并过滤禁用Provider。未根据UI可见就认定该模型一定可调用。

[AppSubAgentPort.kt:230](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt#L230)继续过滤无凭据Provider和非文本输出模型，补充模型能力信息后交给CallableModels。[CallableModels.kt:41](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/CallableModels.kt#L41)按entryId去重、最多提供30项；同名modelId使用provider/model-id区分，再用#n消歧。名称匹配忽略大小写和外围空白，拒绝前缀、模糊名称及任意entryId。

[ChatViewModel.kt:1009](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L1009)工具schema和系统提示词均从subAgentCallableModels生成列表；只有存在Auto子代理时才提供命名模型。子代理自己的会话不再暴露下一层委派工具。[AgentTools.kt:81](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt#L81)将模型名称传入SubAgentToolSchema的enum；没有可用名称时不添加model参数。

[SubAgentRuntime.kt:118](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L118)执行端重新读取允许列表，未知名称返回model_not_allowed，不静默改用另一个模型。Auto子代理显式model优先于model_choice；固定模型子代理忽略model并走固定entry解析。这与提示词和schema描述一致，不能把“忽略非法model”单独报成固定模型绕过。

任务提交保存模型entryId、标签和来源；[SubAgentRuntime.kt:202](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L202)启动子会话时把这些值交给createChild，AppSubAgentPort更新会话来源并应用模型覆盖。已有waiter先登记再启动、完成后异步向父会话递送的逻辑在本轮全文中仍可见；不重复登记以前已经复核的竞态问题。

## 候选一：名称依赖列表顺序，重排可能改变指向

CallableModels的#n编号来自当次列表遍历顺序。同一providerLabel、同一modelId对应多个entry时，列表重排可以把原先的某个handle映射到另一个entry。schema、提示词生成与执行端校验各自取当前列表，并没有在这一层冻结同一个映射快照。因此“各处调用同一个helper”只保证同一输入的规则一致，不能保证用户并发修改后的映射不变。

例如两个配置实例显示相同Provider名称、使用相同modelId，但凭据、计费或上下文设置不同；生成提示词后调换它们的列表顺序，随后重复旧handle可能仍匹配成功而指向另一个entry。需要结合模型请求期间配置更新、已有会话取消/重建策略和真实条目约束，确认是否存在产品要求下的误选；当前没有运行并发场景，也未新增确定问题编号。

## 候选二：排队后保存ID，启动处不重新检查允许列表

delegate入口检查当前允许列表后保存entryId。launch使用保存值，没有在本文件中再次校验用户的Agent Models列表。用户在任务排队期间删除列表条目或禁用Provider，启动时会如何处理，还取决于AgentRunner及模型覆盖解析的后续检查。

需要区分“撤销以后新提交的任务”和“撤销已经接纳但尚未启动的任务”两种产品语义；也要确认后续解析失败是否明确报错、是否回退、是否启动请求。本轮没有将局部缺少二次检查直接写成权限绕过或已发生的错误计费。

## 测试源码与证据限制

CallableModelsTest全文61行，覆盖唯一modelId、跨Provider同名、大小写重名消歧、30项上限及顺序、能力摘要、精确匹配与拒绝模糊/entryId/空字符串。没有覆盖配置在提示词生成与工具执行之间变化，或排队期间撤销条目。本轮只阅读已有测试，没有运行。

SubAgentRuntimeTest和SubAgentTaskTest在新提交中也有变更；它们的旧全文覆盖已经清除，本轮未全文重读，不根据旧测试结论宣布新路径闭环。

## 与旧架构及OpenMinis的关系

本次新增的是模型选择与设置UI，不涉及minisd、PRoot、旧broker或Root执行入口。不能根据这一批未触及这些文件便宣布全库不存在旧路径；那些领域的旧报告仍保留，后续审阅以当前生产链和调用方证据为准。

上游参照仍固定为既有OpenMinis提交`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。本轮未新增上游源码全文读取，也没有fetch或调用外部服务，因此不编造这项功能与上游的全面差异。当前重点是直接核对新远端真实调用链。

## 覆盖迁移与未读范围

新树1782路径，相对旧提交19个已有文件内容变化、3个新增、无删除。仅字节完全相同的文件继承原覆盖。变化的7个原全文文件及ChatViewModel旧片段均重新登记；2个变化文档仍在原排除范围，没有因版本切换误算成代码待读项。

| 本轮全文文件 | 行数 |
|---|---:|
| [AppSubAgentPort.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt#L1) | 247 |
| [CallableModels.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/CallableModels.kt#L1) | 68 |
| [SubAgentRuntime.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L1) | 392 |
| [SubAgentTask.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentTask.kt#L1) | 285 |
| [SubAgentToolSchema.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentToolSchema.kt#L1) | 58 |
| [AgentModelsScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/main/java/com/openminis/app/ui/settings/AgentModelsScreen.kt#L1) | 119 |
| [CallableModelsTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/9e523652b7ea80d7cc5f50e346f7038059896c0f/src/android/app/src/test/java/com/openminis/app/agent/subagents/CallableModelsTest.kt#L1) | 61 |

片段范围：ChatViewModel 980—1060、AgentTools 1—100、AppNavigation 915—950、ProviderRepository 1450—1502与1560—1598。不把搜索结果或diff计为全文；首次截断的全文输出已独立补读。

新台账：[2026-10-07-code-review-coverage-9e523652-subagent-models.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-9e523652-subagent-models.json)。本轮固定快照累计全文 **1353 / 1658文件、226061行**；片段9，尚未登记296，排除124。全库通读仍未完成。全文文件数与旧版本相同、行数降低，是清除变更文件旧覆盖后重新计算的结果，不能用旧版本数字冒充进度。

交付校验旧187份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged代码diff、新快照全部Git blob/SHA-256/字节/行数和引用行号。只新增本报告及新台账。
