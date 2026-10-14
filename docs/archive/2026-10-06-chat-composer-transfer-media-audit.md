# 聊天发送正文、草稿转移与附件展示静态审查

日期：2026-10-06。本轮新建独立报告，新增 5 项问题：4 项 P2、1 项 P3。产品源码及旧报告不作修改。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 对照固定为 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。仅阅读当前工作区与既有上游 Git 对象，没有构建、执行测试、启动模拟器、使用 adb/Root/设备/云资源或读取凭据。本轮延续全代码通读目标，尚未完成。

## CT01 · P2 · 粘贴正文虽已展开，实际 Provider 请求仍使用占位符

新鲜发送路径先[生成并持久化粘贴正文](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6778)，随后把原始 `trimmed` 放入[结构化文本 part](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6814)，只有 `LLMMessage.content` 使用[展开后的 `pasted.modelText`](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6824)。例如输入框为 `请分析 [Pasted#1]`、缓冲区 #1 为 2000 字正文，消息的两种表示已经不一致。

[请求入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8272)将这份历史交给 Provider。无图片样本在图片预算函数中[直接返回原消息](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2700)，工具配对修复也不改普通文本 part。Provider 对结构化文本的实际使用已核对：

- [OpenAI Chat Completions](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:2236)拼接 `contentParts` 的文本。
- [OpenAI Responses](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:3270)同样拼接文本 part。
- [Anthropic](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt:557)非空 parts 时遍历并发送 `part.text`。
- [Gemini](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:224)同样优先发送结构化 part。

因此这些路径向模型发送的是 `请分析 [Pasted#1]`，而不是粘贴正文。正文已经落盘，不能说数据库丢失了内容；问题是当轮模型请求遗漏，界面与存储看起来成功。存在文件也不证明模型会发现或读取它。

两个排队路径也有同根因：[中途注入](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6422)和[结束后排队消耗](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6585)先用原始 prompt 生成文本 parts；后续仅让 `content` 使用展开正文，分别在[6480 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6480)与[6613 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6613)仍传旧 `combinedParts`。

OpenMinis 的[新鲜发送](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L9317)已经让 `content` 与首个文本 part 共用 `modelBody`；两个[排队注入](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L8831)、[排队消耗](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L9001)也会替换正文 parts。本地遗漏了这些处理。不能整体照搬上游代码：本地持久化后的粘贴消费和取消清理已有更完整边界，修正文表示时仍应保留它们，并明确区分正文、图片路径提示和文件清单。

已有 [PasteMediaRefTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/PasteMediaRefTest.kt:232)的 Producer inventory 以手写枚举和布尔常量声称各入口展开；它并不从真实 ViewModel 构建 Provider 请求。对枚举数量和属性的断言不能覆盖上述 `contentParts` 漏洞。本轮只读测试，没有执行或新增测试。

修复方向记录：同一份展开正文生成 `content` 和正文 parts，并核对首次发送、中途注入、排队消耗和磁盘回放的一致性；保留图片、文件清单与取消时文件所有权。

## CT02 · P2 · 移动草稿只搬占位符，没有搬对应的粘贴内容

实际“Move to…”入口由[分享注入标志](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:4639)显示；分享进入的草稿仍可继续编辑和粘贴。[转移构造](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:6184)只携带 `inputText` 与普通 attachments，[PendingTransfer](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt:125)也没有粘贴缓冲区字段。源端[清空文本和附件](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:6193)后导航；目标端只[追加文字与普通附件](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:683)。

每个 ViewModel 的[粘贴 ID 从 1 开始](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:817)，正文实际留在本 ViewModel 的 `_pastedTexts` 中。[发送转换](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:11040)只读取当前 ViewModel 的缓冲区。具体触发：A 的分享草稿里粘贴一段文本，形成 `[Pasted#1]` → 移动到新的 B → B 没有 #1，标记作为普通文字发送，原正文不会随草稿到达。

若 B 已有自己的 #1，按 ID 匹配反而可能把从 A 移来的标记映射到 B 的正文并消费 B 的缓存。源缓存不一定立即删除，不能称内容永久不可恢复；但转移后的草稿已经不再代表用户移动的内容。此问题独立于旧报告的 headless 消费用户附件，也独立于 CT01 的 Provider 文本选择；即使 CT01 修复，目标缓冲区仍是错的。

OpenMinis 的[转移结构](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt#L534)、[发送转移](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt#L7374)及[接收转移](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt#L793)同样没有携带 pasted buffer，属于共享缺口。目标 ID、别名匹配及 300 秒 TTL 已存在，本轮不把它们误报为缺失。

修复方向记录：草稿转移必须让标记和内容共同转移并处理目标 ID 冲突，或在转移前将标记转成不会依赖源 ViewModel 的内容/附件。原有目标地址与一次性消费规则仍要保留。

## CT03 · P2 · 非图片附件独占的消息，重新加载后被过滤为不可见

无正文、只带 PDF/TXT 等附件的消息允许发送，并创建带附件列表的[即时用户消息](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6792)；[用户气泡](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatUserMessageUI.kt:356)可以独立渲染这些附件。存储中写入媒体引用及[文件清单文本](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:11063)。

重新加载时，[投影](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12346)把非图片引用恢复到 `restoredAttachmentUris`，并[去掉清单 XML](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12298)以避免把内部清单显示为正文。随后[空消息过滤](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12396)只检查正文和 `restoredImageUris`，完全不看已恢复的非图片附件，因此将该用户消息返回为 null。

静态样本：发送仅一份 PDF、不加说明 → 当时看到 PDF 卡片 → 重新加载会话 → 用户消息和 PDF 卡片在列表中消失。数据库行和文件仍在，不是存储删除；模型回放也不能仅凭这个 UI 过滤断言丢失。图片独占消息已经获得保护，问题是同一保护遗漏文件附件。

OpenMinis 的[对应过滤](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L17206)仍只看图片列表，属于共享缺口。旧报告关于仅附件 headless retry 的接受条件是另一入口，此处新增的是列表重建的可见性问题。

修复方向记录：可见内容判定纳入非图片附件，继续过滤真正仅含 toolResult 或隐藏系统提示的内部消息。

## CT04 · P2 · 超过 4 KiB 的文件允许内联预览，却永远停在加载状态

聊天 Markdown 的[文件附件渲染分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:2115)实际调用本地 `ChatFileAttachmentView`。[初次读取](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFileAttachmentView.kt:95)遇到文件长度超过 4096 字节只设置 `isShortContent = false`，不读取内容；读取失败或文件不可用也留下空内容。

但是[紧凑卡片](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFileAttachmentView.kt:142)对所有文本类文件都开放内联预览，[点击动作](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFileAttachmentView.kt:246)仅切换展示模式，没有加载工作。进入后[正文分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFileAttachmentView.kt:365)把 `content == null && csvRows == null` 一律显示为“加载中”。读取 effect 只依赖 `fileKey`，模式切换不会重新触发，后续没有任务能够结束这个状态。

样本：聊天中的有效 5 KiB `.txt` 链接 → 紧凑卡片 → 点击“预览” → 永久显示“加载中”。完整查看按钮仍有路由，此处仅指内联预览；文件本身不会被改动。文件读取失败也被伪装成等待状态，是相同缺少状态区分的表现。

固定上游树里不存在 `ChatFileAttachmentView.kt` 这个文件；不能称 OpenMinis 相同组件已修复，或直接把本地缺口判作共享问题。

修复方向记录：点击时执行有界加载，或将不能内联的情况路由到完整预览；区分等待、失败和限额，不让完成后的“未加载”一直显示为进行中。

## CT05 · P3 · 移动目标列表的星期显示提前一天

[MoveToSessionSheet.relativeDate](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MoveToSessionSheet.kt:295)在“不是今天/昨天、距今少于七天”分支对 `Calendar.DAY_OF_WEEK` 减 1 后索引 `DateFormatSymbols.weekdays`。官方 API 要求直接用 `Calendar.SUNDAY`、`Calendar.MONDAY` 等常量索引这份数组，而不是减一。[Android DateFormatSymbols 文档](https://developer.android.com/reference/java/text/DateFormatSymbols)

例如星期一的历史会话，进入该分支会取到星期日标签；星期日会索引到非星期名称的 0 位。主列表的[对应实现](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:405)已直接使用 `DAY_OF_WEEK`，因此同一个会话在主列表和移动目标列表会显示不同星期。刚刚、分钟、小时、昨天和七天外的日期分支不受影响。

OpenMinis 的[移动目标实现](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/MoveToSessionSheet.kt#L378)也保留减一，属于共享缺口。修复方向是使用正确索引并复用现有正确行为，不需新增日期抽象。

## 已核实的边界与留待后续事项

- 编辑消息恢复图片/非图片附件时，有 images-first 名称偏移与 MIME 后备；不能仅因并行列表存在就称当前编辑路径总会错配。已有测试复制了配对函数，不能证明整个持久化与 UI 调用链正确。
- ChatLinkResolver 在 IO 中解析，保留字面 `+`，优先一次百分号解码后的实际文件，首次不可解析时才尝试第二次。session guest 文件有 session-scoped staging；后续缓存刷新与文件 URL 边界仍需结合全部 Markdown/预览代码，不为数量增加泛化结论。
- 图片缩略图解码已转入 IO；模型耗时使用现役调用方的单调时钟，区分首事件与首内容。未把已有处理误报为缺失，也未做设备性能测量。
- 不重复上一份 Slash 菜单、选区替换净长度门槛和输入回调同步写文件问题。不涉及用户让另一工具处理的注释、基础设施重复、Lint 清理。

## 覆盖与复核

本轮新增完整阅读 12 份文件、3838 行：

| 文件 | 全文行数 |
| --- | ---: |
| [ChatComposerWidgets.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatComposerWidgets.kt:1) | 1057 |
| [ChatFileAttachmentView.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFileAttachmentView.kt:1) | 504 |
| [ChatLinkResolver.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatLinkResolver.kt:1) | 216 |
| [ChatUserMessageUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatUserMessageUI.kt:1) | 778 |
| [ModelTurnTelemetry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ModelTurnTelemetry.kt:1) | 66 |
| [MoveToSessionSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MoveToSessionSheet.kt:1) | 300 |
| [ChatLinkResolverPathTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/ChatLinkResolverPathTest.kt:1) | 73 |
| [EditRestoreAttachmentsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/EditRestoreAttachmentsTest.kt:1) | 158 |
| [ModelTurnTelemetryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/ModelTurnTelemetryTest.kt:1) | 57 |
| [MoveToStashBindingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/MoveToStashBindingTest.kt:1) | 89 |
| [PasteMediaRefTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/PasteMediaRefTest.kt:1) | 409 |
| [SelectionReplacementTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/SelectionReplacementTest.kt:1) | 131 |

另外按真实读取区间继续扩展 `ChatViewModel.kt`、`ChatScreen.kt`，将 `StreamingMarkdownText.kt` 登记为部分阅读，均未计入全文数。Provider/Store/Helpers/SessionList 等此前已经全文阅读的文件只核对相关调用。

[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)当前全文 **799 / 1546 份纳入文件**，部分阅读 **14** 份，未登记 **733** 份；当前版本全文累计 **169779 行**。1670 个受跟踪路径及 SHA256、固定 HEAD、当前 tracked/staged diff、52 份既有报告 SHA256、本文本地引用路径和行号、固定上游引用均复核。tracked/staged diff 为空，写入范围只有本报告和覆盖台账。上轮属于外部变化的文件仍是已复核的 HEAD 内容，没有在本轮改动。

本文触发样本是从源码分支推导，未执行测试、网络模型请求或设备复现。全代码通读仍未完成，目标保持进行中。
