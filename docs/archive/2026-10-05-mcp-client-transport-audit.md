# MCP 客户端：握手、传输、工具合同与配置持久化审计

日期：2026-10-05。当前源码 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本报告单独保存本轮发现，不改写此前报告。只读源码、测试源码和协议文档，未修改产品代码，未构建、运行测试、启动模拟器或执行设备操作。结论是静态可推导的行为；未宣称真实设备或第三方服务已复现。

沿用论文复核中的审阅方向：Harness 的质量取决于具体调用、取消、恢复与数据流是否正确。工具已接入 Registry、存在测试或已有大小限制，都不能替代这些行为证据。工具归属与按需暴露的讨论见[此前论文报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-harness-paper-review.md)，本轮继续核对实现。

## 结论与范围

新增 10 项。优先级 P1 表示主要接入路径被阻断；P2 表示条件触发的功能、数据或资源生命周期问题，不代表已经发生安全事件。

| 编号 | 优先级 | 确认的问题 |
|---|---|---|
| MC01 | P1 | STDIO 握手等待没有回复的 initialized 通知 |
| MC02 | P2 | STDIO 共享读取缺少串行边界与请求 ID 关联 |
| MC03 | P2 | 一条超长 stderr 日志就结束整个诊断读取循环 |
| MC04 | P2 | SSE 按单行 JSON 解析，合法多行事件失败 |
| MC05 | P2 | HTTP 收到响应头后即移出活动集合，关闭无法取消正在读的 body |
| MC06 | P2 | HTTP 会话过期 404 被误判为协议不支持，不能恢复 |
| MC07 | P2 | 模型收到的 MCP 参数合同丢失 required、items 和嵌套结构 |
| MC08 | P2 | content 数组存在时，structuredContent 被丢弃 |
| MC09 | P2 | servers.json 保存失败仍报成功，写入也不具备原子替换 |
| MC10 | P2 | 设置页的 STDIO 启动超时没有进入原生连接预算 |

此前 F26 会话开关、F27 短名称碰撞、F28 Registry 并发读写、F29 宿主 ProcessBuilder、F30 环境占位符、F31 OAuth 接线问题仍见[原报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-04-project-audit.md:511)。本轮不把它们重复计算为新问题。MC02 是 transport 自身的帧读取与关联问题，与 F28 注册表并发问题分属不同边界。

## MC01 / P1 — STDIO 握手等待 initialized 通知的回复

**证据：** [MCPClientSession.kt:87](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientSession.kt:87) 把 initialized 通知交给与请求相同的 `send()`。该通知由 [MCPClientCodec.kt:45](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientCodec.kt:45) 构造，没有 `id`。但 [MCPStdioTransport.kt:95](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt:95) 每次写入后都启动线程读取下一行，没有通知的只写分支。JSON-RPC 明确规定通知没有回复。[JSON-RPC 2.0 §4.1](https://www.jsonrpc.org/specification#notification)

**触发与影响：** 服务正常回答 initialize，随后遵守协议、不回答 initialized，且没有主动发送其他消息。`connect()` 就停在通知发送处，不能进入 tools/list；Provider 的 45 秒整体加载预算最终取消并关闭进程。收到无关主动消息时可能绕过这次等待，但它不是通知的确认，也不能证明链路正确。此问题独立于命令究竟在 Android 宿主还是 Ubuntu 执行。

**上游对照：** 固定 OpenMinis 版本 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的 [daemon.py:167](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/daemon.py#L167) 使用 `notify=True` 的只写 `_send`，不调用 `_read_reply`；独立 stdio.py 也这样区分。

**修复方向与验收：** 分开请求和通知的传输语义。以后用实际 STDIO 服务完成 initialize → 不回复 initialized → list → call，保存全部收发帧与连接结果。本轮现有完整握手测试使用 HTTP 202，STDIO 测试只验证取消挂起进程；没有覆盖该握手。

## MC02 / P2 — STDIO 共享读取没有请求关联边界

**证据：** [MCPProvider.kt:147](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:147) 为同一服务的 handler 共享一个 session；[SessionConcurrencyManager.kt:16](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/SessionConcurrencyManager.kt:16) 允许五个 Agent session 并发。每个 [MCPStdioTransport.send():79](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt:79) 都写同一 writer，再创建线程读同一 `MCPBoundedLineReader`。没有请求级锁、单一读取者或 pending-ID 映射。该 reader 的 [逐字符读取:25](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPBoundedLineReader.kt:25) 也没有整行互斥。

[MCPClientSession.kt:140](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientSession.kt:140) 的 `nextId++` 没有并发保护，收到回复后直接解析 result，不核对回复 ID。JSON-RPC 使用 ID 关联请求和响应。[JSON-RPC 2.0 §4–5](https://www.jsonrpc.org/specification#request_object)

**触发与影响：** 两个会话同时调用同一 STDIO 服务，可能交叉消费帧字符而得到坏 JSON；即使恰好逐帧读取，B 的结果先到时，A 的等待者也可能拿走 B 的结果。单次调用前收到服务通知，也会把通知当回复并失败，让真正的结果残留到下一次调用。这里不声称每次并发都会错，也不声称正常 STDIO 当前一定能先越过 MC01；两项必须分别收口。

**上游对照：** [daemon.py:173](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/daemon.py#L173) 在 list/call 上使用同一服务的 `_lock`；`:120` 等待匹配 ID 的帧，而不是接受下一条消息。这能参考其边界，不意味着上游完整支持所有服务主动请求。

**修复方向与验收：** 优先采用符合当前需求的最小串行请求边界，同时保证通知处理和回复 ID 校验；若确需并发，再实现单读取者按 ID 分发。用两个会话调用同一服务，并插入通知，保存各自请求、回复和归属记录；不同服务仍应可独立工作。

## MC03 / P2 — 超长 stderr 日志结束整个读取循环

**证据：** [MCPStdioTransport.kt:65](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPStdioTransport.kt:65) 捕获超过 8 Ki 字符的 stderr 行，跳过其尾部后返回 null；随后的 `?: break` 退出循环，外层 `use` 关闭 stderr reader。上限用于诊断日志，不能等同于 MCP stdout 帧的损坏。[MCP STDIO 规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#stdio) 允许 stderr 日志。

**触发与影响：** 服务输出一行较长的错误详情或序列化对象，后续正常诊断再也不被读取。服务继续写已关闭的 stderr 管道时还可能遇到写失败，具体后果取决于服务实现；本轮没有把关闭管道误写成“必然堵满”。

**修复方向与验收：** 限制单条诊断输出后继续读取，区分真正 EOF 和主动省略；跳过也须有界。输入超长日志、普通日志、正常工具回复，保存后两者仍可取得的记录。`MCPBoundedLineReaderTest` 覆盖了 skip 后继续读的 helper，但 transport 本身没有使用这种继续语义。

## MC04 / P2 — SSE 多行事件被当成多个独立 JSON

**证据：** [MCPHttpTransport.kt:135](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPHttpTransport.kt:135) 收集各条 `data:` 行，倒序找以 `{` 开头的行并立刻 `JSONObject(t)`。它不按空行组装事件，也不合并同一事件的多个 data 字段。SSE 的 data 字段需要累积为一个事件。[WHATWG SSE 解析规则](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)

以下是静态推导所用输入示意，未运行：

```text
event: message
data: {"jsonrpc":"2.0",
data: "id":100,"result":{"content":[{"type":"text","text":"ok"}]}}

```

它组装后是完整 JSON；当前代码选择第一行的不完整 `{...` 并抛解析异常。单行事件测试正常不能排除这个问题。

**相关流式限制：** `readBoundedBody()` 要到 body EOF 才开始解析。回复已经在 SSE 事件中到达、但 HTTP body 尚未结束时，调用仍等待；有持续心跳时还会累计整个流的 4 MiB 预算。规范建议服务在回复后关闭 POST SSE 流，但不能据此把当前实现描述成已具备事件级读取。[MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#streamable-http)

**上游对照：** 固定上游 [http.py:60](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/transport/http.py#L60) 也按单行 data 解析并先读取完整正文。这是参考实现自身的局限，不能照搬解决。

**修复方向与验收：** 采用真正的有界事件解析，完成事件后按请求 ID 选择回复；边界应覆盖单事件和等待时间。用单行、多行、心跳、通知和完整回复后的延迟关闭做端到端验收，保存原始 SSE 与得到的结果。

## MC05 / P2 — 关闭 HTTP transport 漏掉正在读取正文的调用

**证据：** [MCPHttpTransport.kt:109](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPHttpTransport.kt:109) 在 `onResponse` 入口就执行 `activeCalls -= call`，之后才读取 body。`:188` 的 `close()` 只取消这个集合里的调用。Provider reload/unregister 通过 session.close 关闭旧 transport，不能找回已移出集合的请求。

**触发与影响：** 响应头已经收到，body 延迟或持续发送；这时重载、删除或关闭 MCP 服务。旧 socket/body 读取仍可继续占用资源，旧执行者也可能后来收到结果。这里区别于协程取消：`:95` 的 cancellation handler 仍能直接 `call.cancel()`，不能把本项说成所有超时取消都无效。服务端已经产生的操作也不能通过取消网络倒带。

**修复方向与验收：** 将调用跟踪保持到正文处理和 response 释放完成，所有结束路径都移除。响应头先到、正文延迟时主动关闭，再核对旧请求终止和资源释放；保存连接时序。现有 `MCPProviderConcurrencyTest` 的 bodyDelay 用例验证协程超时，不验证 transport.close。

## MC06 / P2 — 过期 HTTP 会话无法恢复

**证据：** [MCPHttpTransport.kt:118](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPHttpTransport.kt:118) 将全部 404 与 405 报成不支持 Streamable HTTP。初始化收到的 session ID 则在后续请求中持续附带。MCP 对已终止会话的 404 要求重新进行不带旧 session ID 的初始化。[MCP 会话管理](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#session-management)

**触发与影响：** 服务重启或回收会话后返回 404；工具调用报错，但 session ID 没有清除，后续仍发旧 ID。Provider 的 connected 状态只在连接/重载时更新，调用失败不会重连，因此可以显示已连接却持续失败，直到外部触发重载。

**上游对照：** 上游 [http.py 的 `_call_with_reconnect`](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/transport/http.py) 有 reset + initialize + 一次重试；识别依赖错误文本含 session 等词，不是完整的 HTTP 状态分类，空正文 404 也不能保证恢复。只能参考状态恢复思路。

**修复方向与验收：** 区分 endpoint 不存在与已建立会话被拒绝，清除旧状态、重新握手和发现工具，更新连接状态。对结果未知的写入工具不能一概自动重放。端到端保留会话回收后的 404、新初始化无旧 ID、后续正常调用及重复操作检查记录。

## MC07 / P2 — 模型侧 schema 丢失远端调用合同

**证据：** [MCPToolHandler.kt:28](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/MCPToolHandler.kt:28) 构造 definition 时没有传入 required；`:95` 的 `schemaToParams` 只转换属性的 type、description 和字符串化 enum，丢弃数组 items、对象 properties、嵌套 required。它发生在 Codec 已经保留了合法、小尺寸 inputSchema 之后，不能用 schema 大小预算解释。

项目现有 [AgentToolParam:154](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/AgentToolDefinition.kt:154) 已支持 items、properties、requiredProperties；definition 本身支持顶层 required。丢失来自 MCP 转换入口，并非基础类型完全没有这些能力。MCP inputSchema 描述工具参数合同。[MCP 工具定义](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#tool)

**触发与影响：** 远端工具要求 `files: array<object{path:string}>` 且 files/path 必填，模型收到的却只是一个没有元素结构的 array，也看不到必填项。生成错误参数的概率增加，调用交给服务才发现缺失；某些 Provider 的 schema 校验也可能拒绝不完整数组定义。本轮没有断言所有 Provider 必然拒绝。数值 enum 转成字符串同样改变了原约束。

**测试核对：** `MCPToolsListContractFixtureTest` 确认 fixture 的原始 schema 有 required，但随后只检查 properties 投影，没有检查最终 handler definition 是否保留 required 或数组结构。

**修复方向与验收：** 复用现有参数结构保留所支持的嵌套和必填信息；对无法表达的合法 schema 明确处理，避免静默改写。通过实际 Provider → 工具服务调用验证必填、嵌套数组和 typed enum，保存远端 schema、模型收到的 schema 与请求结果。

## MC08 / P2 — content 数组遮蔽 structuredContent

**证据：** [MCPClientCodec.kt:209](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPClientCodec.kt:209) 只要 content 是数组就遍历它，只有完全没有数组时才读 structuredContent。结果 `{"content":[],"structuredContent":{"value":42},"isError":false}` 会变成空字符串；有文字摘要时，也会丢掉额外结构数据。MCP 允许结构化结果；建议额外返回文本以便兼容，并不意味着客户端可以假定它总是重复全部结构数据。[MCP 结构化内容](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#structured-content)

**影响：** handler 仍以 `!isError` 返回成功，模型或聊天记录却拿不到真正结果。既有 output pruning/offload 只处理已经生成的字符串，不能恢复之前被丢弃的字段。

**修复方向与验收：** 合并或显式保留两种结果，控制重复内容和大小预算。用空 content + structuredContent、摘要 + 额外结构字段两种服务回复，保存原始回复和 Agent 最终可见结果。现有协议测试只检查 text content 与 isError。

## MC09 / P2 — 配置落盘失败仍发布成功状态

**证据：** [MCPRepository.kt:193](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MCPRepository.kt:193) 直接 `writeText` 写 servers.json，捕获写异常后只记日志，无成功/失败返回。`:302` 的 add 和 update、setEnabled、import 都先改内存，再 save，再通知重连；add/update 仍返回 true。

**触发与影响：** 文件不可写或写中途失败，界面及 live Provider 按新内存配置运行，但重启读回旧内容或不完整 JSON。同一配置再次保存还会因内存相等直接作为 no-op 返回成功，不尝试补写。直接覆盖也允许中途退出/并发读看见半个文件；readServersFile 将读/解析失败当空列表，刷新会发布空配置，后续保存可能覆盖此前列表。这里区分失败语义和实际磁盘数据，未声称本轮发生盘满。

**上游对照：** 上游 [utils/config.py:76](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/utils/config.py#L76) 使用临时文件 + `os.replace`；这提供原子可见性，并不自动解决所有多写者冲突。本地需使用 Android 当前可用的持久化方式，而不是增加另一套配置框架。

**修复方向与验收：** 原子保存成功后再发布状态和重载；错误保留旧配置，明确告知调用方。读坏文件须区分与真正空配置。用可重复的写入失败和中断场景验收，保存前后文件、失败结果、内存/运行配置和重启结果。现有 hot-reload 测试仅覆盖持久化成功后回调失败，没有覆盖落盘失败。

## MC10 / P2 — 启动超时设置未被执行链消费

**证据：** [MCPIntegrationsScreen.kt:647](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MCPIntegrationsScreen.kt:647) 展示启动超时，`:706` 将值保存为 startupTimeoutSeconds。Repository 能导入、读写和导出该字段，但其注释仍说由 guest daemon 执行。当前 [MCPProvider.kt:40](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:40) 对所有服务固定使用 45,000 ms，且预算涵盖 connect + 整个分页发现；MCPClientSession 不读取每服务字段。

**触发与影响：** 用户将启动超时设为 120 秒，服务需要 50 秒回答 initialize，仍在 45 秒被取消。反过来设为 5 秒，也不会取得承诺的 5 秒初始化边界。这不是“启动缓慢一定发生”的猜测，而是设置值不影响生产预算的调用事实，与 MC01 等待通知的问题独立。

**上游对照：** [daemon.py:149](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/daemon.py#L149) 对首次初始化读取该字段，并传给 `_rpc(timeout=...)`。它的 60 秒默认和 900 秒范围不能无解释地当成本项目原生客户端事实。

**修复方向与验收：** 明确启动/初始化、发现和工具调用各自预算，连接真正消费设置值，界面默认和错误信息保持一致。用可控延迟的真实服务分别验证较短与较长设置，保存设置、时间线和取消结果；保留不按配置无限等待的边界。

## 现有保护与未完成范围

当前已有 HTTP 字节上限、STDIO 行上限、工具目录数量/页数上限、schema/description 上限、参数 header 编码和优先级、协程取消、结果 offload/prune、重载 generation 检查。这些保护在源码中可见，报告没有把它们说成不存在；本轮核对的是其覆盖范围与具体缺口。

MC01/02/10 的上游对照来自固定 git 对象内容，只读 `git show`，未 fetch、执行 Python 服务或恢复 PRoot。上游也不是合规证明。OAuth 完整状态机、MCP Server 入站调用及服务通知导致目录更新仍留待后续全文审阅，本轮不从客户端搜索片段推断它们已审完。

## 全文覆盖记录

本轮新增全文阅读 **26 个文件、3,428 行**；既有全文文件 MCPProvider.kt 重新核对，不重复计数。累计全文覆盖 **223 / 1546 个纳入文件、45,703 行**；仍有 12 个 partial 和 1311 个 not_registered，未完成全仓阅读。

- 生产文件 10 个：AgentToolDefinition.kt、MCPRepository.kt、MCPBoundedLineReader.kt、MCPClientCodec.kt、MCPClientSession.kt、MCPHttpTransport.kt、MCPStdioTransport.kt、McpToolHeaders.kt、SessionConcurrencyManager.kt、MCPToolHandler.kt。
- 测试源码 15 个：mcp/client 下全部 11 个测试文件；AgentToolDefinitionMcpShapeTest.kt、MCPPromptFragmentTest.kt、MCPRepositoryHotReloadTest.kt、MCPToolResultBoundsTest.kt。
- fixture 1 个：mcp/tools-list-contract/repo-observed.json。

覆盖范围、SHA-256 和逐文件状态见[台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。关联 UI 与 ChatViewModel 只核对片段；没有把搜索结果当作全文覆盖。源码行数按台账的文本行计算，末尾无换行的最后一行也计入。
