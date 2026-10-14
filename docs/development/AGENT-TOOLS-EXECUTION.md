# 工具执行整理：M3

> 2026-10-14 当前整体行为见 [M 系列整体接线](M-SERIES-INTEGRATION.md)。本页保留此前按日期记录的切片与证据，不能把旧 APK/重复轮次当作本轮源码已通过。

## 已接入的首个切片

模型的 ToolRound 保留批次内可见性、截断写入拒绝与循环检测；共享 ToolExecutor 负责动作别名身份、JSON、参数 schema、执行权限、确认、注册工具时限和结果预算。CLI 的现有领域 Offload 与 MCP 各自仍负责传输和结果交付，不能因此声称全部 CLI 已迁入共享执行器。

- 注册工具的 required/type/enum/nested 校验复用 ToolCallValidator；只忽略展示字段 tool_title。动作别名不能带相反 action；非法 JSON 在执行前拒绝。FileEdit 的现役 edits[] 和已声明的旧 old_string/new_string 格式均可达，空替换仍可删除内容。
- 注册工具的合作式超时由 ToolExecutor 执行。模型层对注册工具不再套第二个时限；browser/memory 已注册并经过同一管线；浏览器沿用既有 90 秒执行时限。超时返回 timedOut/TOOL_TIMEOUT，外部取消继续抛取消并等待协程清理，不能伪装成超时。
- 首切片保留了 M2 的执行意图位置；后续已将意图写入迁到 ToolExecutor 的权限/确认之后、执行体之前。精确 Room 回执结算仍由会话持久化负责，CLI 传输写出不充当模型持久回执。
- 极简模式的四个编码主干工具保留，并提供 Goal、Todo、用户提问、Job 查询/读取/输入/取消。完整领域工具仍由标准模式提供。预设说明与实际定义一致。
- 子代理只走现役角色/队列运行时；关闭总开关时不发布委派 schema，也拒绝持有旧名称的执行请求。旧同步 SubagentTool 已删除。canonical schema 实时反映 roster，并采用 task/action；现役 sub_agent 和旧 subagent 来源均识别为子会话，不提供再次委派。
- Android 诊断能力按实际 DirectRootRunner/App 回退报告，不把 Shizuku READY 宣称为 Root 诊断已可执行。Shizuku 自身连接能力独立保留。Root/SELinux/虚拟屏设备行为仍需真机验收。

## 验证范围

端到端入口 AgentToolPipelineE2ETest 使用真实 Agent、Room、文件读写、工具注册与权限路径；模型传输及等待超时的工具执行体受控。生成 agent-tool-pipeline-e2e.json 和实际交付文件。额外用真实本机 MCP HTTP 服务验证认证后的目录与 Root 请求；测试凭据只进入该测试且结束时撤销，不写入工件。Debug 与 Release 检查结果以 `.local/test-runs/agent-m3-20261012/` 的冻结清单及报告为准。

## 浏览器、记忆与原生 CLI 的收敛

browser_use、memory_write、memory_get 已接入注册表，AgentLoop 不再保留未注册的领域分发。现有 AgentSession 的浏览器池、记忆开关和 UI 记录继续归该会话持有；直接调用只查已有 owner，不创建页面或第二个执行核心。没有活跃会话时，记忆写入检查数据库中的会话开关。

minis-browser-use 经同一 ToolExecutor 校验参数、权限和时限，保留原生 singleTab 浏览器池及 JSON envelope。绑定既有引擎的 Kotlin 回调仅供本地传输使用，不能由工具参数指定，MCP 调用方不能提供它。CLI 完整 JSON/截图 base64 不进入模型文本预算，文件字节继续由文件路径交付。

截图和下载共用 WorkspaceFileClient 持久化，返回实际保存路径对应的 minis:// URL。只读例程将下载放入已有允许写入的 offloads 目录；文件名保留 Unicode、去掉目录成分。同名选择与写入串行处理；uniqueChildPath 检查真实候选路径，避免只扫描首 500 项后覆盖大目录中的既有文件。

MCP 在权限/令牌检查之后、唤醒 Ubuntu 或签发/消费确认票据之前，复用同一参数预检。arguments 必须是 JSON 对象；动作别名还原为实际动作后参与确认绑定。无效重试不消费已批准票据。

设备验收入口 AgentStatefulToolsE2ETest 使用受控模型传输，以及真实 WebView、HTTP 文件服务、记忆仓库、GuestCommandBridge 和经过认证的 MCP 服务。报告仅记录场景与验收文件哈希，不输出用户记忆或测试令牌。证据保存在 `.local/test-runs/agent-m3-stateful-20261012/`；实际轮次、Debug/Release 状态以该目录 RESULTS.md 为准。

## 执行意图与已声明输入兼容

AgentSession 只向 ToolExecutor 传入当前模型调用的不可变 call/run/task 身份，并保留重复动作提醒；旧 dispatchTool 和未注册工具的重复超时分支已删除。共用执行器在参数、权限与确认通过之后检查取消，再同步持久化执行意图；持久化失败则不调用执行体。执行结果保留精确身份，仍由原 Room 保存成功后的路径确认 nonce；已开始且取消的动作保留 UNKNOWN，不能误当成已完成。

MCP 调用方不能提供这份本地模型身份。Guest CLI 的动作由上层 Shell 模型意图覆盖，其独立 stdout 不作为 Room 回执，也不创建另一个检查点系统。模型检查点保留原始 tool call ID。

browser_use 的 cookies/keywords 沿用 BrowserActionInput 已声明的数组和字符串两种格式；数组逐项校验，不通过放宽其它字段来兼容。真实 guest --cookies 和模型数组调用曾被共用 string 校验误拒，已从设备复现并修复；错误数组项拒绝后保留原 Cookie 值。

AgentToolIntentE2ETest 覆盖只读拒绝、实际文件副作用后的取消、检查点文件写入失败、精确 nonce 的 Room 往返与远程身份拒绝；AgentBrowserCompatibilityE2ETest 经实际 WebView CookieManager、模型和 GuestCommandBridge 检验数组/字符串输入。工件在 `.local/test-runs/agent-m3-intent-20261013/`。具体重复轮次及构建范围以该目录 RESULTS.md 为准。

## 按当前调用方发现工具

find_tools（canonical agent.tools）复用 ToolRegistry 与活跃 AgentSession.agentTools，返回工具名称、说明和可选完整参数 schema，支持关键词与分页。极简模式保留该查询；查询不改变本轮工具定义，也不启用被预设、记忆开关或子会话规则隐藏的功能。无活跃 owner 时只返回标明 registered_local_interfaces 的注册目录，不构造第二个执行核心。

可信 caller 由 ToolExecutor 传给现有 handler 接口；JSON 中的 caller 字段不能改变身份。认证 MCP 查询复用 tools/list 的 local-only 与 Token scope 过滤，Root 仍不对远程公开。目录明确 device_availability_checked=false；实际设备条件仍由 android_capabilities 和动作结果核对，目录查询不触发 Root 或 Ubuntu 初始化。大输出沿用共享结果预算与 session 文件引用。

AgentToolCatalogE2ETest 从真实模型入口查询文件参数，并以返回的工具名称完成文件写入和读回；同时验证 CORE/FULL、关闭记忆和委派、旧子会话、无 owner、分页及真实 MCP 全量/窄范围令牌。8 个场景连续 3 轮通过，工件保存于 `.local/test-runs/agent-m3-catalog-20261013/`。查询匹配名称和描述，调用方需要按返回名称选择工具，不能假定第一个搜索结果就是目标动作。

## 下一切片

日历、联系人、位置、剪贴板和闹钟的结构化工具已复用既有 Offload handler；不能为了让 CLI 反向调用模型分发而再建一套实现。四个隐私领域的模型与真实 GuestCommandBridge 撤销权限已通过端到端核对；共享检查后、剪贴板 handler 前撤销权限，也被后续 OffloadGate 拒绝。其他 CLI 继续保持自身传输，完整领域任务仍按其实际入口验收。

Ralph 已收敛到共用 AgentRunner 的精确请求句柄：单轮时限和父调用取消会停止所属请求，并等待该请求的清理完成，不取消后来进入同一会话的用户请求。共用 runner 原本已发出取消，本次补齐的是 Ralph 所需的清理完成等待和准确结果分类。Ralph 子会话来源拒绝直接嵌套，不再用全局计数阻挡独立父会话；未知子工具意图不能被 complete 报告结算。交接报告要求 continue 的下一步和 blocked 的原因，超大 evidence 不再先截断再验收。无效深度设置已删除，现役 RalphLimits 复用原偏好文件/timeout_minutes；原生设置和 agent.settings.get/set、rpc.discover 都明确只设置 Ralph 单轮时限。该时限不冒充队列子代理的预算。端到端和工件见 [.local/test-runs/agent-m3-ralph-20261013/RESULTS.md](../../.local/test-runs/agent-m3-ralph-20261013/RESULTS.md)。跨领域核验、SAF 证据、屏幕租约和产物归属随后续阶段推进。本切片不代表 M3–M5 全部完成。

## 结构化结果与未知副作用

共用执行器按发生位置标记参数、权限、确认、检查点拒绝及执行开始；领域 handler 可保留明确的拒绝状态。普通返回失败、执行超时、进程/传输失败、清理失败、观察失败与异常造成的结果未知使用既有 ToolFailureKind。分类不依靠正文里是否出现 timeout 等词；普通失败也不能证明没有副作用。取消继续抛取消并等待清理，Android/browser catch 不再吞协程取消；执行体抛出普通 Exception 时返回精确身份的未知结果，让当前 Agent 能核实现场。

ToolRound 将 failureKind/executionStarted 带入实际模型结果与 Room；旧消息缺字段保留空值。超时、异常和观察失败的回执即使已保存，也不确认 pending nonce；会话保存和加载后的补 ACK 两处使用相同条件。恢复提醒说明“中断或缺少确定结果”，不再把同进程内超时错误写成进程死亡。ACTIVE 普通目标遇到未结算动作会转 WAITING，自动接续不会重放。

Android UI 的 TIMED_OUT 和 ACCEPTED_NO_EVIDENCE 来自既有 evidence 字段，分别投影为超时与观察失败；保留旧动作接受布尔值，并通过未知结果阻止据此结算执行意图。真机 Root/Shizuku/OEM 观察仍未因此获得证明。

MCP 保留原 content/isError，在 structuredContent 增加 output 和执行事实；复用当前 2025-06-18 协议的可选结构化结果字段（[官方说明](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-06-18/server/tools.mdx)）。MCP 与浏览器 CLI 共用 executionFacts()，不发布本地模型的 nonce/run/task 身份。浏览器 CLI 在 data/error 的 execution 字段保留事实，quiet 模式仍可读取；原 base64/文件字节和 JSON envelope 沿用。动作成功但结果交付出错会标明观察失败，而非伪装成动作未执行。

AgentToolOutcomeE2ETest 从受控模型和故障执行体、真实文件/Room/nonce、GuestCommandBridge 与认证 MCP 验证上述管线；原生 UI evidence 的硬件采集不由受控执行体替代。结果与完整回归/Release 状态见 `.local/test-runs/agent-m3-outcome-20261013/RESULTS.md`。

## 日历与联系人实际入口一致性

日历模型适配现在转发已有 CLI 的 calendar_name/calendar_id 和创建 reminder_minutes；ID 与名称同传时沿用现役 CLI 的 ID 优先行为。更新保留未传字段，明确空字符串会送到原 ContentProvider 实现，用于清空日历备注/地点和联系人字段；明确无效日期不会再被静默当作未传。没有新增领域后端。

AgentAndroidDomainE2ETest 经受控模型传输、真实 Agent/Room、GuestCommandBridge 和 Android Calendar/Contacts Provider 验证目标日历、提醒、清空、交叉读写/删除、无效输入及权限拒绝。所有记录使用测试独有的本机账户和 ID，结束后删除；系统权限由宿主恢复。敏感正文仍按既有策略脱敏保存，执行身份和分类保留。工件及构建范围见 [.local/test-runs/agent-m3-domain-20261013/RESULTS.md](../../.local/test-runs/agent-m3-domain-20261013/RESULTS.md)。模拟器不证明真实设备 Root、定位信号或 OEM 行为。

## 精确动作核验与必填结构参数

verify_outcome 已通过现有 agent.goal handler、别名及极简/完整工具目录接通。读取任务时返回原未知 execution/Run/Task 身份，并排除当前 get_goal 自身意图；核验完整非 append 写入后先保存精确 Room 回执再 ACK，不隐式结算任务或恢复 WAITING/NEEDS_USER。错误哈希、原 Run/Task/版本不符、暂停及未持有真实模型执行身份的调用拒绝。任务完成改为单独提交 evidence，旧 resolved_call_ids 批量确认入口不再披露。

真实模型回合复现了 ToolJsonRepair 将所有 required 字段转为字符串，破坏必填对象参数的问题；现仅对 schema 声明为 string 的字段进行文本修复，对象/数组/数值保持类型。核验与跨入口回归见 [.local/test-runs/agent-m2-verification-20261013/RESULTS.md](../../.local/test-runs/agent-m2-verification-20261013/RESULTS.md)。后续检查点切片已使坏行/编码、重复身份、权限拒绝和非普通文件失败关闭，读取、追加、精确 ACK 和 legacy settle 不丢弃损坏现场；解析错误不附带记录原文，本地存储错误不触发 Provider 回退。get_goal 非模型入口保留无 execution ID 的旧未知记录，reported 不代表完成。最终 APK 的 45 次 instrumentation、499 次场景执行及 Release/R8/Lint 证据见 [.local/test-runs/agent-m2-checkpoint-integrity-20261013/RESULTS.md](../../.local/test-runs/agent-m2-checkpoint-integrity-20261013/RESULTS.md)。非文件未知副作用仍待领域核验，不能据切片声称 M2–M5 整体完成。

## Android 领域执行事实与持久回执（2026-10-14）

NativeOffloadResult 沿既有四领域 handler 传递 failureKind/executionStarted，AndroidSystemOps 直接复用其结果转换。模型适配和领域日期/ID 校验、App 工具权限、系统运行时权限及服务不可用保留执行前拒绝事实；进入修改接口后异常保持 UNKNOWN，不能凭 SecurityException 或退出码断言没有副作用。日历写入需同时具备读写权限，以免选择目标日历时绕过 READ_CALENDAR。联系人已写入但聚合 ID 无法核实，保留原实际结果与观察失败。

日历事件和提醒是多步动作。事件已写入后，提醒插入/替换返回 null 或抛异常不能证明提醒没有生效；创建和更新均保留 event ID/rows/reminder_error，并标记 OUTCOME_UNKNOWN。Room 保存失败回执不 ACK 精确意图，普通目标保持 WAITING、不消耗自动接续预算。

四领域 Guest CLI 支持位于参数首位的 --execution-json，返回 {exit_code, output, execution}；默认原文、JSON 形状、换行和退出码继续沿用。中间出现的同名字面量不触发 framing，compact/quiet 不删除执行事实。本地模型的 execution/run/task 身份不进入 CLI/MCP 事实字段。MCP 仍需其原有确认票据；测试在真实认证 HTTP 流程中批准绑定的测试请求后才验收领域结果。

AgentAndroidOutcomeFactsE2ETest 使用受控模型、真实 Room/权限/认证桥接/MCP，以及真实 Events/Reminders/Contacts Provider 写入；仅写入后的异常/null 回执受控。四种提醒故障与两种联系人观察故障核对实际记录、精确未知意图和预算；测试记录与系统权限在结束时清理/恢复。最终重复运行、共享入口、强制停止恢复与 Debug/Release 检查以 [.local/test-runs/agent-m3-domain-outcomes-20261014/RESULTS.md](../../.local/test-runs/agent-m3-domain-outcomes-20261014/RESULTS.md) 为准。未新增或运行 JVM 单元测试。其它领域的跨入口结果、非文件未知动作独立核验、SAF 证据和完整交付仍待后续切片，M2–M5 整体未完成。
