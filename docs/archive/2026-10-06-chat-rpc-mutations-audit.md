# 聊天 RPC、模型覆盖与消息反馈审计

日期：2026-10-06。项目 HEAD：d64e7c06d8159be90737dd9ba03dd9bd5855f339。

本轮新增 **7 项静态确认问题（6 项 P2、1 项 P3）**，全文读完 8 个文件、1988 行。只新增本报告并更新覆盖台账，不修改产品源码和旧报告；没有运行接口、测试、构建、模拟器、adb、设备或云任务，没有读取凭据。下列反例来自源码推导，尚未执行端到端验证。

聊天 RPC 的实际入口是 DebugServer→DebugRPCHandler；上一轮已核对 Debug 启动限制与令牌校验。本轮讨论已授权调用的语义、数据和失败处理，不把调试数据接口描述成未授权入口。

| 编号 | 优先级 | 问题 |
|---|---|---|
| CR01 | P2 | 模型覆盖修改数据库，缓存 VM 仍可使用旧模型，回显却是新模型 |
| CR02 | P2 | prompt 返回的 userMessageId 来自会话最后一条用户消息，不属于本次请求 |
| CR03 | P3 | includeTools=false 仍返回完整工具 parts |
| CR04 | P2 | 消息附件投影不识别现役 mediaRef |
| CR05 | P2 | 附件校验失败留下已写缓存，且请求在校验前已经修改会话 |
| CR06 | P2 | 消息反馈落盘失败被吞掉，接口仍返回成功 |
| CR07 | P2 | 新反馈没有会话归属，原生 /feedback 查不到它 |

## CR01：模型覆盖与实际执行模型不一致

[ChatMutationMethods.kt:81](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt:81) 在 prompt 前调用 applyModelOverride，retry 的第 133 行也走同一路径。[HeadlessChatRunner.kt:111](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:111) 只写数据库绑定，没有切换 VM 的 currentModel/currentProvider，也没有结束后的恢复步骤。未显式指定模型时，它还会尝试解析 Main 默认模型并改绑定。

[providerFor:52](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:52) 返回同一会话的缓存 VM。VM 的绑定恢复发生在 [loadSession:4473](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4473)；[配置监听:4130](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4130) 观察 Provider 配置，没有观察此处数据库绑定变化，正常已有 currentProvider 时不会重新加载绑定。[ChatRepository.kt:129](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:129) 也只是 DAO 更新。

**反例：** 会话 VM 已加载模型 A，随后 chat.prompt 指定 B。在 Provider 配置不变、A 仍有效的条件下，数据库改为 B，本轮仍用 A；[RPC 第 106 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt:106) 优先回显 overrideName，即 B。冷启动后又按数据库选择 B。Busy 拒绝、附件校验失败之前也已经发生这次绑定修改。

已有 [selectEntry:5073](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5073) 同时更新执行对象和持久绑定；应明确覆盖是单次还是持久切换，并让接受、执行和回显遵守同一语义。注释所称“update + restore”不能代替实际恢复逻辑。该项不重复旧报告的失败回合状态和取消归属问题。

## CR02：消息 ID 与请求脱离

[ChatMutationMethods.kt:103](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt:103) 在返回前重新加载全部消息，选 lastOrNull(user)，直接作为本次 userMessageId。

[HeadlessChatRunner.kt:203](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:203) 遇到正在 streaming 的会话直接返回 Busy，没有插入本次用户消息。RPC 仍返回已有最后一条 user 的 ID，同时 prompt 字段是本次未接受的文字。调用方用此 ID 跟踪请求、标注或重试，会关联到旧请求。等待期间若另一个输入已提交，最后一条 user 也可能属于后来的请求。

runner 内部已经用 [request.userMessageId:225](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:225) 限定回答范围，但没有把该 ID 交给 RPC。应返回本次已接受请求拥有的 ID，拒绝时明确为空；不要靠“最后一条”补全。这与旧报告的返回旧回答问题不同：当前 runner 的回答已有请求边界，本项是外层元数据仍未采用该边界。

## CR03：关闭工具详情没有过滤原始 parts

[ChatDebugMethods.kt:108](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatDebugMethods.kt:108) 接收 includeTools，[第 138 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatDebugMethods.kt:138) 却无条件返回完整 parts，仅第 149 行的规范化 toolCalls/toolResults 受开关控制。

[DebugMethodRegistry.kt:536](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt:536) 描述的是是否包含工具调用／结果块。传 false 后，长工具结果仍完整进入响应，文本客户端不能据此减少工具内容和传输体积。这里不把它当作敏感信息权限开关或未经授权泄露。应使开关与字段过滤一致，或者明确它仅控制派生索引。

## CR04：附件字段继续使用旧 schema

[ChatDebugMethods.kt:143](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatDebugMethods.kt:143) 只把 image/file/attachment 加入 attachments。现役保存逻辑 [ChatViewModel.kt:11020](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:11020) 写的是 `type=mediaRef`，MIME、relativePath 和文件名在 value。

因此正常保存的图片、PDF、音频等在 chat.messages.list 的 attachments 投影中消失；纯附件消息又没有 content，依赖此投影的消费者会得到无正文、无附件的消息。原始 parts 仍保留引用，**没有证据说明附件字节已丢失**。这是聊天 RPC 的独立读取入口；旧 EX02 的导出附件计数问题不在本轮重复计数。

本地已修正工具轨迹的 camelCase 投影，还需让附件使用同一现役 schema，并保留明确需要的旧格式兼容。

## CR05：拒绝请求也会留下会话和附件副作用

[ChatMutationMethods.kt:77](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt:77) 先创建／改绑定，再校验附件和 thinkingLevel。附件函数 [第 465 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt:465) 逐项写入缓存；后项超限抛错，没有清理之前已经创建的文件。thinkingLevel 的校验甚至发生在整个附件批次已写完之后。

**反例：** 两个 16 MiB 合法文件后再附一小文件，累计超过 32 MiB；调用拒绝，但前两个文件已经落盘。或者附件均合法而 thinkingLevel 拼错，接口拒绝，却保留全部缓存和此前会话修改。已有会话 Busy 时，附件也先落盘再进入 runner 的 Busy 判断。

全量搜索 rpc-attachments 的生产代码只找到此写入点，没找到按请求成功、拒绝或 session 删除清理这些文件的逻辑。系统可以回收 cache、用户也可能手动清理，但这不能提供本次失败请求的回滚。批次上限只限制单次，反复拒绝仍会累计。

本地确实新增了 8 项／16 MiB／32 MiB 限制和文件名清洗，不能再声称完全没预算或此处仍可直接目录穿越。应先完成无副作用的参数校验，并把暂存文件纳入请求所有权和失败清理。后续验收要对比拒绝前后会话字段、缓存文件集合及摘要，而不只看 RPC 错误。

## CR06：反馈保存失败仍报成功

[MessageFeedbackStore.kt:103](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/MessageFeedbackStore.kt:103) 把整个文件提交包在 runCatching 内，却不检查或传播 Result；[put:142](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/MessageFeedbackStore.kt:142) 仍返回新 Feedback。[FeedbackRpcMethods.kt:18](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/FeedbackRpcMethods.kt:18) 随后返回 ok=true。

磁盘空间不足或临时文件写入失败时，调用方看到成功，下次读取却没有新反馈；delete 也可能返回成功而原反馈仍在。此 Store 没有持久内存缓存可替代落盘。损坏文件备份的 renameTo／delete 返回值也未严格检查，备份失败后继续覆盖原件，不能保证注释承诺的保留损坏证据。

应让真实提交结果决定成功响应，并在备份失败时保留原件。既有 [MessageFeedbackCasTest.kt:17](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/MessageFeedbackCasTest.kt:17) 只调用内存 Map 上的 casPut/casDelete，没有覆盖文件提交；本轮仅阅读，没有执行或新增测试。

## CR07：反馈写入没有会话归属，原生统计为空

现役 [FeedbackRpcMethods.put:18](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/FeedbackRpcMethods.kt:18) 只传 messageId/kind/note；[MessageFeedbackStore.put:139](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/MessageFeedbackStore.kt:139) 只继承旧反馈的 sessionId。新条目的 sessionId 为空，Store 没有按消息查询所属会话。

原生 [ChatViewModel.kt:1775](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1775) 的 feedback 菜单进入 AgentCommandRegistry；[commandFeedbackText:291](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/remote/AgentCommandRegistry.kt:291) 调 listForSession，[Store 第 196 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/MessageFeedbackStore.kt:196) 只接受 sessionId 完全相等。

给真实会话消息通过 chat.feedback.put 写入赞／踩后，listForMessages 可以查到，原生反馈菜单却显示“当前会话暂无消息反馈”。有历史归属的条目仍可被统计，本项不声称所有反馈一定为空。

应该从真实消息确定并保存归属，兼容未知归属旧记录时明确处理。putDsh 虽有 sessionId 参数，但本项目主源码中没有调用它，不能拿该方法的存在当成现役写入已接通。MessageFeedbackRow 同样没有找到现役调用方，不据它宣称原生气泡反馈按钮已经可达。

## 与固定 OpenMinis 的比较

比较对象为 OpenMinis/OpenMinis，固定提交 b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c。只读本地已有对象，没有拉取或运行上游。

- CR01：上游 [HeadlessChatRunner.kt:195](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt#L195) 的覆盖也只改数据库，调用层仍优先返回覆盖名。其默认策略是 model group，本地为 Main entry，不能直接照搬旧 group／PRoot 架构。
- CR02：上游 [ChatMutationMethods.kt:100](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L100) 同样按最后一条用户消息返回 ID。
- CR03／CR04：上游 [ChatDebugMethods.kt:137](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ChatDebugMethods.kt#L137) 同样无条件返回 parts，并只认识旧附件类型。本地新增 ChatToolTrajectoryNormalizer 修正了工具规范化字段，并未修正上述两个问题；不能说本地工具 camelCase 投影仍完全缺失。
- CR05：上游 [ChatMutationMethods.kt:438](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L438) 也逐项写缓存，没有本地新增的批次限制和文件名清洗。本地限制改善成功路径预算，但没有补齐失败清理。
- 固定对象全路径检索未找到同名 MessageFeedbackStore.kt、AgentCommandRegistry.kt。本报告只据此标明这两个 Android 模块没有同名直接对照，不推断其它平台或不同文件名一定没有等价能力。

## 已核对但不新增编号

- 会话删除未完整清理持有者、retry 的拒绝／旧回答与删除数量、后台输入消费 composer 状态，已有 F01、F20／相关 headless 项和 F25；不重复计数。压缩撤销的 VM 删除是异步的，RPC 已用短轮询等待；没有仅凭过时的“同步”注释再报一个问题。
- AgentCommandRegistry.commandCompact 使用 wait=false，却只把 Complete/Compacted 当成功，runner 正常启动返回 Running，确实存在分支状态不匹配。但现役原生 compact 菜单直接调用 compactAll，没有找到另一处把 compact 交给该注册表的生产调用；留作入口核对，不算已确认用户故障。
- Store 为无 version 的旧反馈每次读取生成随机 version，却未在读时落盘，所谓“首次加载稳定升级”不成立。当前 casPut/casDelete 只有既有测试调用，未找到现役 DSH RPC 接线；暂不声称已发生线上 CAS 冲突。sessionId 删除边界也不能仅凭未接通的 deleteDsh 推断为可达越权。
- DebugScreenshotRing 的 PixelCopy 失败和取消分支缺少明确位图回收，保留待生命周期核对；调用端 scale 已限制 0.05–1.0，不把此前 Browser viewport 的任意尺寸问题复制到这里，也不声称一定 OOM。
- LLMRequestLog 有 Release 早退、20 项和请求正文 64 KiB 截断，敏感 header 值会脱敏。本轮不把请求日志描述成无限保留或直接返回 Authorization。
- 工具轨迹既有测试覆盖当前 value 嵌套格式及旧 snake_case aliases；这证明测试源码具备相关断言，不等于本轮运行通过。

## 后续端到端验收建议（本轮未执行）

| 场景 | 可复核工件 |
|---|---|
| 已加载 A 的会话指定 B、Busy、参数拒绝、冷启动 | 请求参数、实际 Provider/model、返回名、数据库绑定前后快照 |
| Busy 与连续输入的 userMessageId | 各请求接受状态、请求拥有 ID、完整消息 ID 顺序 |
| includeTools=false 与图片／PDF／音频消息 | 原始 parts 和 RPC 投影对照，附件引用与 MIME |
| 多项附件最后一项超限、错误 thinkingLevel | 缓存文件集合／大小／摘要以及会话字段变化 |
| 反馈写失败与重读、原生反馈菜单 | 提交结果、持久文件、RPC 读取和菜单统计 |

## 全文阅读和只读复核

| 新全文文件 | 行数 |
|---|---:|
| debug/ChatDebugMethods.kt | 383 |
| debug/ChatMutationMethods.kt | 528 |
| debug/DebugScreenshotRing.kt | 114 |
| debug/LLMRequestLog.kt | 116 |
| remote/AgentCommandRegistry.kt | 345 |
| tools/MessageFeedbackStore.kt | 282 |
| test/debug/ChatToolTrajectoryNormalizerTest.kt | 96 |
| test/tools/MessageFeedbackCasTest.kt | 124 |
| 合计 | **1988** |

主源码路径从 src/android/app/src/main/java/com/openminis/app/ 起算，两个既有测试从 src/android/app/src/test/java/com/openminis/app/ 起算。关联 ChatViewModel、ChatAgentStateUI、DebugMethodRegistry 和 DebugRPCHandler 只登记本轮实际读完的片段，没有因搜索命中把大文件算成全文。

累计全文 **766 / 1546 文件、161130 行**；partial 15，not_registered 765，排除 124，Git 跟踪总数 1670。全代码阅读仍未完成。逐文件 SHA-256、实际范围和批次统计见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

复核项目 HEAD、全部 1670 个跟踪路径及 SHA-256、跟踪／暂存 diff、49 份既有报告摘要，以及本报告本地引用路径／行号和固定上游引用行号。产品源码与旧报告保持原内容。本报告和台账是本轮仅有的工作区写入。
