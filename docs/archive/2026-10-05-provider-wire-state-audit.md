# Provider 请求转换、流式状态与工具参数修复审计

日期：2026-10-05。当前源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮另建报告，不改旧报告，不改产品代码，不运行构建、测试、模拟器、adb、Root、设备或云任务。

本轮新增 **6 项 P2 问题**。反例是当前源码的数据流推导；没有把它们表述为真实服务或设备上已经复现。全文新增 20 个文件、4,272 行，累计 580/1,546 个纳入范围的文件、119,426 行；全库审计仍未完成。

## 新问题索引

| 编号 | 问题 | 影响 |
|---|---|---|
| PW01 | 必填数组被修复成字符串，校验通过但执行器按数组读取 | 合法待办列表被清空，仍报告成功 |
| PW02 | Gemini 流式响应不转发 inlineData | 聊天生成的图片、音频丢失，非流式入口表现不同 |
| PW03 | Gemini 工具结果只序列化文字 | read_image、屏幕截图等结果的像素未交给模型 |
| PW04 | Gemini 在没有 finishReason 的 EOF 上合成 end_turn | 部分响应或空断流被当成正常结束 |
| PW05 | Anthropic 用量增量覆盖完整状态，上下文又漏算缓存 | 输入、缓存用量丢失，上下文压力被低估 |
| PW06 | Anthropic 未保存并回传完整的签名思考块 | 开启思考的工具续跑缺少服务要求的历史内容 |

## PW01 — 参数修复与执行器不使用同一个参数表示

[ToolJsonRepair.kt:62](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ToolJsonRepair.kt:62)遍历所有 required 字段，跳过 String/null，其余一律调用 toString 后写回。这里没有按字段的 schema 类型决定是否转换，合法 JSONArray/JSONObject 也被替换为字符串。

现役 [AgentTodoHandler:428](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:428)明确声明 todos 是必填 array。[ChatViewModel.kt:9304](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9304)在 preflight 之前执行修复，[9369 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9369)将已变更的 args 重新序列化。随后的 [ToolCallValidator.asArray:209](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolCallValidator.kt:209)可以重新解析字符串并判定其符合 array，但 validate 不改 args，未把解析后的数组交给执行器。

具体反例：模型按 schema 发出 `{"tool_title":"更新待办","todos":[{"title":"完成审计","status":"in_progress"}]}`。修复后 todos 成为 JSON 字符串；校验仍通过；ToolExecutor/AgentTodoHandler 原样转交 argsJson。[TodoTool.kt:24](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/TodoTool.kt:24)的 optJSONArray 返回 null，items 留为空列表。随后 [AgentStateStore.todoReplace:117](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentStateStore.kt:117)删除该 session 的旧待办，TodoTool 返回 success=true、0 item(s)。既没有输入无效错误，也没有按模型给出的完整列表更新。

这不是“校验器没有支持数组”：它支持，也正因仅局部解析而与执行器产生分歧。[ToolPreflightTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/ToolPreflightTest.kt)只直接调用 preflight，没有把修复和真正执行串起来。数值/布尔转换是否能被具体工具正确接受要逐入口核对，不在本项中泛化为所有工具都坏。

方向：保留已经符合 schema 的原生类型，修复只能产生执行器实际使用的表示。待办缺失/类型错误须拒绝，不能当作显式空列表。真正的空数组仍可按工具合同清空列表。本轮未修改。

## PW02 — Gemini 流式分支遗漏生成媒体

[GeminiProvider.kt:142](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:142)到 168 行的每个 SSE 响应只提取 text/thinking、functionCall、usage 和 finishReason，没有调用 extractInlineMedia，也没有发送 LLMStreamChunk.MediaAttachment。

同一 Provider 的 [非流式路径:83](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:83)会提取附件；[extractInlineMedia:417](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:417)已经实现 inlineData 的 MIME、base64 和媒体类型转换。请求端 [349 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:349)也会按模型输出能力声明 AUDIO 或 TEXT/IMAGE，不能把遗漏解释成该 Provider 完全不支持媒体输出。

现役聊天 [ChatViewModel.kt:8297](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8297)调用 streamMessage，只有收到 [MediaAttachment:8718](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8718)才能进入媒体保存链。响应含图片和说明时，只留下说明；响应只含音频/图片时，可能落入空回答处理。底层媒体没有进入消费者，不能由 UI 的保存逻辑补救。

范围限于 Gemini 流式入口。minis-model-use 的非流式 sendMessage 路径已经可返回 mediaAttachments，不认定它也有同一遗漏。现有 GeminiProviderTest 的流式用例只覆盖文字、用量和结束原因。

方向：复用已有媒体解析并转发统一 chunk，明确流式分片的完整性与重复处理规则，不能只把输出模态声明留在请求中。

## PW03 — Gemini 丢弃 ToolResult 中的图片

模型工具结果类型 [AgentContentPart.ToolResult:25](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/AgentContentPart.kt:25)持有 imageData、imageMimeType 和 imageLinuxPath。聊天 [ChatViewModel.kt:9567](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9567)把工具返回的图片继续放进下一轮历史，默认 request image budget 也不会无条件删除全部图片。

但 [GeminiProvider.kt:257](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:257)的 ToolResult 分支，无签名时只写缩短文字，正常路径也只生成 functionResponse.response.result。两边均不读取 imageData/imageMimeType。单独的 ImageData 分支支持 inlineData，不会自动处理藏在 ToolResult 内的像素。

因此 read_image 或 UI 截图工具成功提供像素后，使用 Gemini 的下一轮模型只看到路径/尺寸/文字，无法看到该结果图片。该问题独立于 PW02：PW02 丢输出，PW03 丢工具结果输入。[ToolResultImageSerializationTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/ToolResultImageSerializationTest.kt)所有 Provider 构造都为 OpenAIProvider，没有覆盖 Gemini。Anthropic 的同类结果分支确实序列化图片，未将其一并报错。

方向：在签名回放与文字降级两条路径都保留工具结果像素，按目标模型采用支持的多模态表示。Google 的 [generateContent schema](https://ai.google.dev/api/generate-content.md)定义普通 Part.inlineData 与多模态 FunctionResponse 的表示；具体表示需按模型能力选择，不应把所有版本都强行改成一种嵌套形式。

## PW04 — 没有终止证据也被标为正常结束

[GeminiProvider.kt:170](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:170)在 reader.readLine 返回 EOF 后，无条件发送 Finished(lastFinishReason ?: "end_turn")。这把缺少结束原因和真正 STOP 合并了。Google [Candidate.finishReason 定义](https://ai.google.dev/api/generate-content.md)说明，字段为空表示尚未停止生成；连接 EOF 本身不能提供缺失的模型终止证据。

反例：响应仅含一条 text=`答案前半段` 的 SSE，连接随后正常关闭，没有任何 finishReason。Provider 合成 end_turn，聊天 [ChatViewModel.kt:9121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9121)的“有内容且 stopReason 为 null”中断处理因此进不去，残缺答案被按正常收尾处理。若正文为空且直接 EOF，合成的 Finished 还会让 [failOnSilentEmptyCompletion:145](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/LLMProvider.kt:145)认为已经收到终止原因，跳过原本的空断流 transient error。

[GeminiProviderTest.kt:312](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/GeminiProviderTest.kt:312)恰好要求无 finishReason 时得到 end_turn；这是锁定现状，不能证明业务正确。[StreamDropNoFinishTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/StreamDropNoFinishTest.kt)只验证 OpenAI，不能证明 Gemini 的中断保护也成立。

方向：保留未知结束状态或显式返回中断，区分真实停止、正常传输 EOF 与响应不完整；让现有重试/恢复入口得到真实信息。非流式路径也有缺原因时的默认值，本项以现役流式断流为已核对影响。

## PW05 — Anthropic 用量没有汇总，缓存没有计入上下文

第一处：[AnthropicProvider.kt:211](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt:211)解析 message_start.usage，随后 [261 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt:261)独立解析 message_delta.usage，没有保存/合并上一份。缺字段被 parseUsage 默认成 0/null。Anthropic 的 [Streaming 示例](https://platform.claude.com/docs/en/build-with-claude/streaming)明确展示 message_start 包含输入用量、message_delta 只含累计 output_tokens 的合法响应；新版本的某些事件可含更多字段，不能假定每个 delta 总是全量。

聊天 [ChatViewModel.kt:8662](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8662)直接 lastUsage=chunk.usage。所以开始事件输入 100、缓存命中 50,000，结束事件只有 output_tokens=20 时，最后记录成为输入 0、缓存 null、输出 20。现有 [AnthropicProviderTest.kt:253](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/AnthropicProviderTest.kt:253)只分别断言第一份输入与第二份输出，没有断言最后的完整快照。

第二处：[parseUsage:1009](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt:1009)把 latestContextTokens 设为 input_tokens。官方 [Prompt caching 用量定义](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)要求总输入包含普通输入、缓存读取和缓存创建。聊天 [8683 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8683)优先相信正数 latestContextTokens，仅在它缺省时才做缓存加总，因此 100 个 fresh token、50,000 个 cached token 被当成 100 的上下文压力。末尾零输入事件不会修复这个错误，只会令 lastUsage 的完整信息继续丢失；不能声称每次 delta 都把 lastContextTokens 清成零，代码会保留此前正数。

方向：按存在的字段更新每次请求的用量快照，累计输出不能按增量相加；latestContextTokens 按 Provider 的总输入语义计算。将用量完整性和缓存上下文两处修复一起验收，不新增另一套 token 状态源。

## PW06 — 思考摘要可见，但完整思考块无法回传

[AnthropicProvider.kt:225](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt:225)仅处理 text_delta、thinking_delta 和 input_json_delta，不处理 signature_delta。content_block_start 只识别 tool_use，不保存 thinking/redacted_thinking 原始块。LLMStreamChunk 与 AgentContentPart 也没有本链路保存 Anthropic 签名思考块的事件/结构；已有 ProviderOutputItem 注明用于 Responses 原始项，Anthropic 没有发出它。

[shouldEchoInterleavedThinking:535](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt:535)只让自定义端点且有 interleavedReasoningField 的模型回传无签名摘要。官方端点及严格 Claude 类端点不会回传；把 gate 放开也不够，因为此前根本没保存签名和完整块。聊天会拼接 ThinkingDelta 作为 reasoningContent，因此不是“思考正文完全没有捕获”，而是该字符串不能重建签名块。

Anthropic 的 [Thinking encryption](https://platform.claude.com/docs/en/about-claude/models/extended-thinking-models)及 [Extended thinking with tool use](https://platform.claude.com/docs/en/docs/build-with-claude/extended-thinking)要求工具续跑保留最新 assistant 回合的完整思考块，流式签名通过 signature_delta 到达。可达场景为开启思考→服务返回签名思考和 tool_use→执行工具→提交 tool_result；当前下一次请求只有 text/tool_use/result，缺少最新回合的原始思考块。按官方合同会破坏工具续跑，严格服务可拒绝；未实连服务，不声称所有代理都会返回同样错误码，也不把无工具的普通多轮一概认定为失败。

[AnthropicProviderTest.kt:551](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/AnthropicProviderTest.kt:551)验证 Claude 类模型不回传合成的无签名块，这个拒绝本身是合理的，但没有验证“原样保存已签名块→回传→工具续跑”。方向是保存并回放原始块，保留无签名兼容模型现有策略；不能伪造签名或把摘要当原始块。

## 固定 openminis 对照

上游固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，仅只读 git show/tree 检索，不更新或运行上游。

- Gemini 178–187 行也没有媒体转发，EOF 合成 end_turn，PW02/PW04 属于两边共有缺口。
- 上游 Gemini 295–326 行已经在签名正常和无签名降级两条 ToolResult 路径补上 inlineData；当前项目缺少这些语句。PW03 有具体可对照的上游修复，不能仅凭“与 iOS 同步”注释判断已移植。
- 上游 Anthropic 222/276 行分别发送独立用量，1155–1162 行仍用 inputTokens 作为 latestContextTokens；587–590 行也只有无签名兼容 gate，已核对流式 delta switch 未处理 signature_delta。PW05/PW06 在该上游也存在。
- 上游 ToolJsonRepair 62–71 行同样强制字符串化，但没有当前同路径的 TodoTool/ToolCallValidator。PW01 是旧修复 helper 与本项目当前工具、校验器组合后的明确反例，不把它写成上游同一待办故障已复现。

## 排除项与未执行的验收

已有语音纠错报告的同步 HTTP 取消问题不重复计数。没有把 OkHttp 重复、注释任务标签、legacy 计数和 lint 基线列入本轮问题。Gemini 缺 thoughtSignature 时的降级策略按当前代码记录，不仅凭旧注释认定每个历史 functionCall 都必须有签名。Anthropic 单个工具输入 JSON 解析失败会落空对象，但聊天仍有 raw tail 修复与 preflight；未把这一行直接升级为新的任意执行问题。

后续端到端验收方向（本轮未运行、未编写测试）：

| 链路 | 应保留的可重复证据 |
|---|---|
| 合法 agent.todo 数组穿过修复、校验、执行 | 原始/执行参数类型、旧/新待办、结果；正常列表不清空，显式空数组可清空，畸形参数不改状态 |
| Gemini 同一媒体响应走聊天与 model-use | 请求模态、响应字节、事件类型和最终媒体文件摘要；流式附件无漏失/重复 |
| Gemini read_image/截图续跑 | 输入图片摘要与实际下一轮请求对应，签名正常和历史降级路径都覆盖 |
| SSE 部分文字后 EOF、零字节 EOF、真实 STOP | 最终结果、终止原因和恢复入口；缺终止证据不报告完整回答 |
| Anthropic 缓存用量及 output-only delta | 逐事件用量、最终完整快照、上下文压力；缓存总量不漏算，累计输出不重复累计 |
| Anthropic 思考→工具→续跑 | 匿名化的块类型/顺序/签名摘要对应与服务状态；原始块不丢失、不修改，不保存真实密钥 |

## 全文阅读登记

以下均在本轮全文读取；GeminiProvider/AnthropicProvider 从 partial 升为 full，其余 18 个由 not_registered 升为 full。上游片段及大型调用方片段不计全文。

| 文件名（台账保留完整路径） | 行数 |
|---|---:|
| AgentContentPart.kt | 88 |
| LLMError.kt | 34 |
| LLMMessage.kt | 102 |
| LLMStreamChunk.kt | 71 |
| LLMUsage.kt | 18 |
| JsonExt.kt | 17 |
| RequestBodyMerge.kt | 43 |
| ToolJsonRepair.kt | 135 |
| AnthropicProvider.kt | 1,039 |
| GeminiProvider.kt | 506 |
| AgentStateStore.kt | 146 |
| TodoTool.kt | 46 |
| ToolCallValidator.kt | 222 |
| ToolProvider.kt | 69 |
| AnthropicProviderTest.kt | 627 |
| GeminiProviderTest.kt | 403 |
| RequestBodyMergeTest.kt | 120 |
| StreamDropNoFinishTest.kt | 99 |
| ToolResultImageSerializationTest.kt | 232 |
| ToolPreflightTest.kt | 255 |

此前已 full 的 LLMProvider（169 行）和 AgentToolDefinition（190 行）全文复核，不重复增加覆盖。当前总量：full 580、partial 15、not_registered 951、excluded 124；纳入代码/配置/资源/fixture 共 1,546，tracked 共 1,670。
