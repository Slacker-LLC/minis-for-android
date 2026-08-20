# 解剖 DeepSeek Harness：能给 OpenMinis Pet 的设计清单

> 2026-08-21。解剖对象：DeepSeek Harness 0.1.0-rc.5（@deepseek-ai/dsh-* 70+ 模块）。
> 方法：逐一阅读各模块设计文档（agent-loop、workflow、subagent、compaction、retry、
> checkpoint、jobs、spill、approval、scope、token-meter、output-retention 等），
> 对照 OpenMinis Pet 现有实现，评估「哪些设计值得移植、怎么移植、优先级如何」。

---

## 〇、先说结论

OpenMinis Pet 已经移植了 DSH 的**交互层**（goal/todo/plan/deliverables/feedback/
permission-presets/repeat-reminder/attachment/subagent/提问卡）——这些是「用户看得见
的功能」。本次解剖发现，真正值得再挖的是 DSH 的**运行层**设计——它们解决的是手机
Agent 最痛的问题：**上下文有限、进程随时被杀、单次模型调用失败**。

推荐移植顺序：

1. **Ralph 循环**（长任务上下文管理的终极形态）——P0
2. **工具调用统一超时**（AgentToolDefinition.timeoutMs）——P0，改动最小
3. **LLM 统一重试策略**——P0，改动小、收益直接
4. **工具结果剪枝器**（compaction-tool-result-pruner）——P1
5. **会话检查点**（checkpoint-policy，崩溃恢复语义）——P1，手机场景收益最大
6. **作业系统**（job_output/list/kill）——P1，Web Remote 已有占位
7. **Token Meter**（回放感知计量 + 上下文压力）——P1
8. **Spill 策略**（通用工具结果落盘）——P2，把 shell 专用泛化
9. **审批 seam**（一次性工具审批）——P2
10. **输出保留库**（head 保留 + 精确省略元数据）——P2，工具输出规范化的地基

---

## 一、已经「给」了的部分（对照确认）

| DSH 模块 | OpenMinis Pet 现状 | 评价 |
|---|---|---|
| dsh-goal / dsh-tool-goal | GoalTools（get_goal/create_goal/update_goal） | ✅ 已移植，已补 create/update 语义 |
| dsh-tool-todo | TodoTool（todo_write 整表替换） | ✅ 已移植，已补 status 白名单 |
| dsh-plan-mode | agent.plan.*（软计划模式） | ✅ 已移植（软实现） |
| dsh-client-ui-deliverables | 产出文件条 | ✅ 已移植 |
| dsh-message-feedback | chat.feedback.* | ✅ 已移植 |
| dsh-permission-presets | settings.permissionPreset.* | ✅ 已移植，已让它真实生效 |
| dsh-repeat-tool-reminder | ToolLoopDetector（四策略+熔断） | ✅ 超越原版 |
| dsh-attachment | 附件上传 | ✅ 已移植，已补净化 |
| dsh-subagent / dsh-tool-subagent | SubagentTool（深度/超时/截断） | ✅ 已移植，已补真取消 |
| dsh-tool-ask-user | AskUserQuestionTool + 提问卡 | ✅ 已移植 |
| dsh-agent-presets | 主/子代理设置 | ✅ 已移植 |
| dsh-output-retention（部分） | ShellOutputTruncator / ContextOffload | 🔶 局部等价（见剪枝器） |

---

## 二、推荐移植的设计（按优先级）

### P0-1. Ralph 循环：长任务上下文管理的终极形态
**DSH 设计**（dsh-tool-ralph）：把一个**不可变目标**依次交给多个**全新子 agent**；
每个子 agent 不继承父对话，只有「共享工作区是权威状态」+ 上一轮的结构化交接报告；
报告状态机为 continue | complete | blocked，含非空摘要、证据、后续步骤；
maxHandoffChars 上限 + 状态语义校验（无效/缺失/过大报告使工作流失败，绝不静默截断）；
结果规范包络 { runId, agentsStarted, result }，完成/阻塞标签明确标注「由 worker 报告」。

**为什么给手机版**：手机上的 Agent 上下文窗口比桌面更珍贵（模型 API 便宜、内存有限），
「读 40 个文件找 X」这类长探索用 fresh-agent 轮换 + 工作区记忆，可以把上下文消耗
控制在每轮交接报告的大小，而不是整个会话的累积。Ralph 是「把上下文当作稀缺资源
来管理」的设计，OpenMinis 现有 subagent 只是单次委派，没有「目标驱动的多轮迭代」。

**移植方案**：
- SubagentTool 之上加 ralph 工具：参数 {objective, maxRounds}（上限取自设置）
- 交接协议：子代理 prompt = 不可变目标 + 当前轮次/上限 + 工作区权威声明 + 上一轮报告；
  要求子代理以 status: continue|complete|blocked 结构化收尾（JSON 包装）
- 校验：报告状态机语义、maxHandoffChars（如 8k）、round 数上限；不合法即失败
- Web Remote 渲染：generic 卡片显示不可变目标；完成/阻塞标签标注 worker 报告
- 会话列表：每轮子会话以「↳ Ralph N: <目标前 30 字>」命名

**工程量**：中（复用现有 subagent 管线 + 新工具 + 前端卡片）。**收益**：高。

### P0-2. 工具调用统一超时（ToolDefinition.timeoutMs）
**DSH 设计**（dsh-tool-call-timeout-policy）：每个工具在 ToolDefinition 上声明
timeoutMs；一个零配置的 tools/execute 环绕监听器统一强制执行：超时返回结构化
TOOL_TIMEOUT 结果（{isError: true, error: {code: TOOL_TIMEOUT, ...}}）；
协作式信号（工具转发 signal 才真正终止）；未声明预算的工具原样委托。

**OpenMinis 现状**：AgentToolDefinition **没有 timeoutMs 字段**（审查报告也点过：
WEB-REMOTE-DEEPSEEK-BATCH 的 TODO 里有「AgentToolDefinition 无 timeoutMs」）；
只有 shell 执行有自己的超时。模型可以一个 file_read 卡住整个回合。

**移植方案**：
- AgentToolDefinition 加 timeoutMs: Long?
- 工具执行器（AgentTools 分发处）加统一 deadline：withTimeoutOrNull 包工具执行，
  超时返回结构化 ToolExecutionResult(isError=true, code=TOOL_TIMEOUT)
- 给慢工具声明预算：shell 30s~10min（现有）、web fetch 60s、file 读大文件 30s、
  browser 90s、subagent 继承 SubagentLimits

**工程量**：小。**收益**：高——单个坏工具不再卡死整个回合。

### P0-3. LLM 统一重试策略
**DSH 设计**（dsh-llm-retry）：提供方适配器声明嵌套 retryPolicy；normal mode 对
EMPTY_RESPONSE / RATE_LIMIT / SERVER / TIMEOUT / TRANSPORT 重试 2 次，500ms~10s
有界指数退避 + 10% jitter；providerRetryAfterMs 尊重服务端指示；重试期间追加
llm/retry 事件（不进入模型表层）；取消/插件卸载中止退避。

**OpenMinis 现状**：各 provider 自己处理错误，**无统一重试**（审查 TODO 也点了）。
网络抖动/429 直接失败给模型，模型可能误判为配置错误。

**移植方案**：
- LLMError 已有错误分类（InvalidApiKey/RateLimited/Transient/Timeout…）——按类型
  映射到 DSH 的 code 集合
- Provider 调用层加统一重试：normal mode（2 次、指数退避 + jitter）+ 429 尊重 Retry-After
- 移动端特殊：Wi-Fi 切换（CONNECTIVITY_CHANGE）时重置退避、立即重试一次
- 重试事件进 LLMRequestLog（已有基础设施），Web Remote 轨迹可见

**工程量**：小-中。**收益**：高——移动网络下 Agent 成功率显著提升。

### P1-4. 工具结果剪枝器（compaction-tool-result-pruner）
**DSH 设计**：超预算的 tool/result 表层节点改写为「头部 + 固定省略标记 + 尾部」，
原始完整事件保留在仅追加会话日志中（可回放）；thresholdChars=8192、headChars=4096、
tailChars=1024；替换保留 turn/step/callId/error/meta；第二次扫描不重复改写；
文本切片不拆代理对。

**OpenMinis 现状**：ShellOutputTruncator 只做尾部 2000 行/50KiB 截断（执行时）；
ContextOffload 把超限 shell 输出落盘。但**没有**「压缩时对历史工具结果做
head/tail 剪枝」的机制——压缩上下文时要么全留要么全丢。

**移植方案**：在 ContextPolicy/压缩路径上加 ToolResultPruner：
- 触发压缩时，对超阈值（8k 码点）的历史工具结果，替换为 head(4k) + 标记 + tail(1k)
- Room 里保留原始内容列（可回放），只改「模型可见」的投影
- 复用 ShellOutputTruncator 的 Unicode 安全截断逻辑

**工程量**：中。**收益**：中-高——压缩后模型仍能看到关键首尾。

### P1-5. 会话检查点（checkpoint-policy）：崩溃恢复语义
**DSH 设计**：模型请求前、顶层工具正文执行前、每个 pre-step 边界创建持久化检查点；
工具检查点后、结果前崩溃 → 会话恢复时注入模型可见的 TOOL_OUTCOME_UNKNOWN
结果——允许重试只读/幂等工作，要求对有副作用调用验证状态或请求用户确认；
拒绝检查点 = 失败即阻止（工具正文不运行）。

**OpenMinis 现状**：Room 持久化消息，但**没有「执行意图」检查点**——进程被杀后
恢复的会话不知道某个工具是否已执行过，模型可能重复执行有副作用的操作（重复写文件、
重复扣费）。

**移植方案**：
- 工具分派前把 {sessionId, toolName, args, callId, seq} 写入 Room（执行意图表）
- 工具结果到达后更新状态；恢复会话时，发现「有意图无结果」的条目 → 注入
  TOOL_OUTCOME_UNKNOWN 工具结果消息
- 移动端进程被杀是常态（后台回收），这个设计对 OpenMinis 的价值可能比桌面还大

**工程量**：中。**收益**：高——手机场景崩溃恢复的正确性。

### P1-6. 作业系统（job_output / job_list / job_kill）
**DSH 设计**（dsh-tool-jobs）：kind 无关的作业控制器：job_output(job_id, wait?,
timeout_ms?) 非阻塞读增量、job_list()、job_kill(job_id, reason?)；完成通知
「background job <id> (<kind>: <label>) finished [status: ...]. Read its output with
job_output.」；outputLimitBytes 有界输出；公共快照省略 ownerSession。

**OpenMinis 现状**：审查时发现 Web Remote 白名单里有 agent.jobs.list/cancel 的
占位但「尚无 handler/registry 条目」；有 ScheduledAgentRunner（定时任务）但无通用
作业概念。子代理/压缩/迁移都是「fire and forget」或「同步等待」。

**移植方案**：
- JobRegistry（ConcurrentHashMap + 状态机）+ 三个模型工具 + Web Remote RPC
- 把子代理执行、定时任务运行、长 shell 统一注册为 jobs
- 完成通知写入会话（模型可读），Web 端 job 面板

**工程量**：中。**收益**：中——把「等一个可能很久的东西」变成「注册 + 查询 + 取消」。

### P1-7. Token Meter（回放感知计量 + 上下文压力）
**DSH 设计**（dsh-token-meter）：4 字符/token 固定启发式 + 结构开销；从持久日志推进
fold（回放感知）；提供方用量锚点复用（envelope 匹配才复用）；contextPressure =
uncached + cacheRead + cacheWrite；会话投影 tokenUsage / contextPressure。

**OpenMinis 现状**：TokenUsageSheet 显示 provider 返回的 usage；**没有**上下文压力
估算——无法在请求前预判「这轮会不会超上下文」。

**移植方案**：
- TokenMeter：估算器（4 字符/token + JSON 结构开销）+ 每会话 fold（Room 消息 → tokens 累计）
- 请求前 measure(session) → 压力百分比；>80% 提示压缩（现有 AutoCompact 可接入）
- Web Remote / 手机 UI 显示「上下文压力」条

**工程量**：中。**收益**：中——自动化的压缩决策基础。

### P2-8. Spill 策略：通用工具结果落盘
**DSH 设计**（dsh-spill-policy）：tools/post-execute 转换器：纯文本工具结果超
maxInlineBytes → 完整文本存入 spill store，模型可见替换为「head/tail 预览 +
定位信息 + 取回指引」；整个替换内容不超过预算；跳过嵌套执行/已接受替换/read 循环。

**OpenMinis 现状**：只有 shell 输出走 ContextOffload（落盘 + 截断返回）；
file_read 80k 硬截断（只留头，无尾部无取回指引）。工具结果层没有通用 spill。

**移植方案**：把 ContextOffload 泛化成 SpillStore + post-execute 钩子：
- 纯文本工具结果 > 上限 → 落盘到 /var/minis/offloads/tools/（现有目录）
- 模型可见替换为 head + (Omitted N bytes. Full result at: ...) + tail
- 通知里带取回命令（复用现有 file_read）

**工程量**：中。**收益**：中——所有工具共享一致的「大输出不占上下文」行为。

### P2-9. 用户审批 seam（一次性工具审批）
**DSH 设计**（dsh-user-approval）：通道无关的一次性审批：request(req) 返回
allowed-once | rejected | cancelled | unavailable；应答者缺失 = 拒绝关闭；
approval/asked + approval/decided 审计记录；策略 ask | never；
沙箱 bash 升权重试也走此 seam。

**OpenMinis 现状**：有 ConfigConfirmationGate（配置变更审批）+ 计划模式软引导；
**没有**通用工具审批。手机 Agent 执行危险操作（删文件、装包）时只能靠权限预设
（一次性授权），不能「每次询问」。

**移植方案**：
- ApprovalSeam：request/decide + 审计表（Room）
- 工具侧：危险工具（file delete、shell 特定命令、browser 提交表单）声明
  requiresApproval，执行前走 seam；无应答者 = 拒绝
- 手机 UI：审批卡片（与提问卡同款交互）；Web Remote 同步
- 策略设置：ask / never（合并进现有权限预设页）

**工程量**：中-大。**收益**：中-高——手机 Agent 安全性的关键拼图。

### P2-10. 输出保留库（head 保留 + 精确省略元数据）
**DSH 设计**（dsh-output-retention）：ItemRetainer（有序逻辑单元，head 保留 +
**精确**省略计数）+ TextRetainer（head/tail/headTail，UTF-8 边界安全）；
describeOmitted（exact/unknown）+ formatRetentionNotice（省略子句 + 恢复指引）。

**OpenMinis 现状**：各工具的截断各写各的（file_read 80k 硬截、ls 1000 项、grep 未截），
省略信息不精确（模型不知道到底省略了多少）。

**移植方案**：Kotlin 实现 ItemRetainer / TextRetainer，统一所有列表/文本类工具
的输出保留：head 保留 + 「… omitted N items (exact) …」 + 恢复指引。这是「工具输出
规范化」的地基，为剪枝器/Spill 提供统一格式。

**工程量**：小-中。**收益**：中——模型对「世界被省略了多少」有精确感知。

---

## 三、观察但不建议移植（附理由）

| DSH 设计 | 为什么不建议 |
|---|---|
| dsh-scope（带作用域注册/事件路由） | 架构级抽象，服务于多 agent 同进程隔离；OpenMinis 会话级服务已够用 |
| dsh-session-projection / query-sqlite | Room 已承担查询职责；等 TokenMeter 需要投影时再引入 |
| dsh-agent-tool-presentation（native/code 模式） | code 模式省 schema token 有意义，但 OpenMinis 工具面固定，列为远期 |
| dsh-typert / cordis 插件体系 | 整个运行时架构，不可移植；只借鉴「插件=职责边界」思想 |
| dsh-session-telemetry-otel | 遥测栈过重；LLMRequestLog + Logcat 已覆盖 |
| dsh-session-persistence-jsonl | Room 更合适 Android；借鉴「仅追加日志+可回放」思想即可 |
| dsh-time-context / tmux-context | 简单功能，系统提示里已有时间上下文 |
| dsh-skill / skill-filesystem | OpenMinis 已有 SkillRepository + SKILL.md 体系 |
| dsh-tool-fs-search | OpenMinis 有 shell + file 工具族；如需结构化 grep 工具可单独评估 |
| dsh-llm-deepseek / pi-ai 适配器 | 模型供应商接入是 OpenMinis 已有能力 |

---

## 四、建议路线图

Phase 1（下一版，小改动高收益）
  - AgentToolDefinition.timeoutMs + 统一 TOOL_TIMEOUT（P0-2）
  - LLM 统一重试策略（normal mode + jitter）（P0-3）
  - Output Retainer 库落地到 file_read/ls/grep（P2-10，地基）

Phase 2（再一版，中改动）
  - Ralph 循环工具（复用 subagent 管线）（P0-1）
  - 工具结果剪枝器（压缩时 head/tail 改写）（P1-4）
  - Token Meter + 上下文压力条（P1-7）

Phase 3（后续，需要设计评审）
  - 会话检查点 + TOOL_OUTCOME_UNKNOWN 恢复语义（P1-5）
  - 作业系统（job_output/list/kill + 通知）（P1-6）
  - 通用 Spill 策略（P2-8）
  - 工具审批 seam（ask/never + 审计）（P2-9）

---

## 五、方法论备注（为什么这样解剖）

1. **读设计文档优先于读代码**：DSH 每个包都有 README.zh.md，先读「契约」再读实现——
   移植的是契约（状态机、错误语义、边界条件），不是代码。
2. **按「手机场景稀缺资源」排序**：上下文（Ralph/剪枝/Spill/Meter）、进程生命周期
   （检查点/作业）、网络（重试/超时）——这三类是移动 Agent 与桌面 Agent 的本质差异。
3. **能复用就复用**：OpenMinis 已有 offloads 目录、LLMRequestLog、SubagentTool 管线、
   提问卡交互——新设计都挂在现有基础设施上，不另起炉灶。
