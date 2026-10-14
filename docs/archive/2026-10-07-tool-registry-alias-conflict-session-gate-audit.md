# 工具注册表、动作别名、MCP会话执行门与schema全文复核

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。全文静态读取6文件、1155行。旧184份报告及全部旧覆盖台账保留。本轮确认F17/F27/F28/F26等原反例的处理范围，没有新增足够证据支持的独立问题编号。未改代码、编写或运行测试、调用工具/MCP服务、构建或操作模拟器/adb/Root/设备/云资源。

## F27：短canonical/wire冲突不再静默覆盖

[ToolRegistry.kt:42](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt#L42) register先核对definition.name与apiName的占有者，已属于不同工具则返回false，不修改handler或alias。便利alias若被占有，只跳过该alias，仍可注册工具自身；同一canonical重新注册才允许替换。unregister移除其全部alias及action标记。

MCPProvider只把register成功的工具放入registeredTools，拒绝项出现在status skipped数量中，失败回滚只操作本批实际added。服务器ID清洗已追加原ID摘要，改变旧docs one/docs_one确定碰撞。短wire名称仍可能得到相同字符串，但在当前注册入口会拒绝后来者，不能继续声称两个名字必然公布并错误派发。

canonicalName按精确handler、精确alias、大小写、去分隔符先后匹配。注册冲突检测没有覆盖所有宽松normalized等价名；精确公布名称仍优先。未给出实际消费者只能使用歧义宽松拼写的证据，不将理论名称等价另报为当前错误执行。摘要也不等于数学上不可能碰撞，拒绝机制仍有必要。

## F28：注册表读写现在共用monitor

register/unregister/canonicalName/aliasesFor/definition/definitions/handler等均@Synchronized，返回List/Set快照；遍历不再直接与无锁写入并发。旧LinkedHashMap遍历的同步缺口已补。

这不等于“整次工具执行持有注册表锁”：ToolExecutor的canonicalName、handler、argsForCall分别调用，外部定义列表与权限集合也分开取快照。handler一旦取出，可遇到后续reload关闭其session。当前MCP层会返回transport失败；没有完成更细交错证据，不把旧F28无限扩大成所有重载行为已正确或仍必然崩溃。

## F17：动作别名信息已从入口保留到handler

[ToolRegistry.kt:85](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt#L85) argsForCall对actionAliases补action，已有非空action保留。[MinisApp.kt:799](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L799)goal三个旧名及[MinisApp.kt:817](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L817)jobs三个旧名都以aliasesAreActions=true注册。[ChatViewModel.kt:8629](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L8629)调用ToolExecutor时传原始name，不再先把动作名丢为canonical；Executor补参后交给merged handler。因此严格按公开job_list/get_goal schema不传action的旧失败根因已经处理。

显式action覆盖alias是现有刻意行为，既有测试明确断言，不能凭名字不同就报成权限绕过。argsForCall的坏JSON回退JSONObject候选没有补足实际Provider输入证据，不新增立项。

## F26：MCP会话开关有实际执行门

已全文登记的MCPToolHandler本轮完整重读，execute在session.callTool前调用enabledInSession，configId使用原配置ID，与sanitized服务器命名分开。会话关闭服务器后，旧工具列表里的调用不能仅凭handler仍注册就绕过此门。

MCPSessionGateTest47行用注入enabledInSession核对拒绝和允许通过门；允许例只到未连接session的transport失败，并不连接URL。它没有检验Repository override保存、MCPProvider reload或UI切换；本轮不访问该测试中127.0.0.1地址。全局disabled服务器是否可由session override连接仍按既有报告保留边界。

## schema、执行域与权限调用顺序

AgentToolDefinition188行完整阅读：apiName清洗、64字符上限、长名/纯标点digest；Provider JSON都用apiName，MCP序列化使用inputSchema，required与嵌套items/properties由AgentToolParam递归输出。它证明本地定义可表达这些schema，不修复MCPToolHandler从远端schema转param时丢required/嵌套信息的旧MC07。

ToolExecutor先用canonical执行scheduled READ_ONLY、remote virtual-display及caller权限检查，再进入确认/ProviderRouter/handler，最后ToolResultBudget。没有把大小写或分隔符变体能resolve解释成自动绕过Root local-only政策。具体权限策略及审批票据消费仍以之前相应文件和调用报告为准，单读Executor不证明外部票据已经使用一次。

LinuxShellHandler执行域是ExecutionCoordinator；Python代码写session /workspace后交python3，finally删除临时脚本，不是App内嵌Python解释器或旧PRoot执行。超时由现有ToolTimeoutPolicy解析，exit124转TOOL_TIMEOUT。临时文件cleanup与取消失败仍需要实际环境验收，本轮未创建脚本或调用guest。

## 既有测试的具体范围及限制

ToolRegistryConflictTest126行6例：wire冲突拒绝、alias归原主、同名替换、canonical撞alias拒绝、sanitizeId区分、并发重载读取。并发例每线程2000次，join有30秒但没有assert线程结束；不能仅凭源码就宣称全部压力测试通过或所有跨方法交错已覆盖。

ActionAliasJobsTest83行4例调用真实ToolExecutor与SystemJobsHandler，覆盖job_list补action、显式action保留、job_kill调用worker canceller及晚注册canceller立即触发。它还依赖全局注册表/JobRegistry，测试setup只在system.jobs缺失时注册；不是生产启动顺序或真实logcat进程的端到端证据。F22实际watch取消链此前另报告核对，不重复计数。

ProviderRouterTest104行4例覆盖prefix边界、无provider兼容以及FakeHandler+LinuxProvider不可用时拒绝。@Before仅reset ProviderRouter，未清ToolRegistry；注册的Fake linux.shell也无@After恢复。这是此前已登记测试隔离候选，未执行顺序组合，不新增产品缺陷。实际ToolProvider按前缀firstOrNull路由，不能仅凭注释“reset两单例”认为执行过。

四测试全部只读，未运行、新写或改动。LinuxPythonRunHandlerCleanupTest的组合复核输出截断，本轮不计新全文，也不据它声明清理通过。

## 固定OpenMinis对照与覆盖

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1仅读既有Git树/对象。无同名ToolRegistry路径；AgentToolDefinition旧版直接name写Provider schema，param仅type/description/enum。本项目wire名和nested schema是当前新增能力，不能用旧上游结构倒推当前缺失。未fetch或运行上游。

| 本轮新增全文 | 行数 |
|---|---:|
| [AgentToolDefinition.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/model/AgentToolDefinition.kt#L1) | 188 |
| [ToolRegistry.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt#L1) | 607 |
| [ActionAliasJobsTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/runtime/ActionAliasJobsTest.kt#L1) | 83 |
| [MCPSessionGateTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/runtime/MCPSessionGateTest.kt#L1) | 47 |
| [ProviderRouterTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/runtime/ProviderRouterTest.kt#L1) | 104 |
| [ToolRegistryConflictTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/runtime/ToolRegistryConflictTest.kt#L1) | 126 |

ToolRegistry按1—310、311—607完整读，其余文件独立完整输出。AgentToolDefinition首次混合输出截断，后来单独完整重读才登记。MCPToolHandler和ToolProvider已full重读不重复累加；其它相关文件仅核对片段。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-tool-registry.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-tool-registry.json)。累计全文 **1344 / 1655文件、225402行**；partial6、not_registered305、excluded124，tracked1779。ChatViewModel新增8617—8644片段，MinisApp新增788—823片段，均仍partial。

交付校验旧184份报告及全部旧台账hash、本地/远端/上游HEAD、tracked/staged空代码diff、1779快照Git blob/hash/bytes/lines与引用行号。全库通读未完成，仅新增本报告与台账。
