# 引用输入、粘贴与外部消息接收全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。旧206份报告及所有旧台账保留。静态阅读；未改产品源码、编写/执行测试、构建、使用设备/模拟器/Root/云资源/凭据。

## QI01 / P2：发送尚未获接受就清空引用，拒绝路径不恢复

ChatViewModelInput:122 takeQuotedText先读待引用列表，只要正文或附件非空就清空_quotedTexts，再返回拼好的Markdown。ChatViewModelSend:349/353的两个公开sendMessage入口先调用它，之后才进入内部send检查。

内部send在_isCompacting时提示稍后发送并直接返回，在currentProvider=null时显示无Provider并返回。两条拒绝路径没有持久用户消息、没有入队，也不把已清空的quotes或拼合text恢复到输入状态。用户本来的正文可能仍在，但已选引用卡片丢失，再次发送不带引用。明确条件为有待引用内容、有正文/附件并进入上述拒绝；不声称普通成功发送会丢引用，也没有设备复现。

Context pressure的ASK_USER/COMPACT_THEN_SEND会保存拼好的pendingSendText，与直接拒绝路径不同，不能一起说全部丢失。programmatic submitPrompt调用内部send，不消费用户quotes。引用-only且没有正文/附件时takeQuotedText原样返回且保留quotes，这是明确设计，不另报不能单发引用。

记录方向：发送接受或明确持有pending输入后再消费引用；拒绝时保留原卡片。优先复用现有发送状态，不新增第二份composer真源。只记录，交Claude修。

## 正常行为与其它边界

quoteIntoInput trim、限6000字符、去重，最多5条保留最近；withQuotes每行加Markdown引用标记，空行用裸>保持同一块。splitLeadingQuotes把连续前导引用块分离，普通正文后引用保持正文。对全部手写Markdown前导blockquote与应用引用卡片并不区分，当前是格式规则，未要求原始选择身份，不能由此报所有普通blockquote错误。

QuoteForReplyTest五例真实调用格式化/拆分helper，覆盖空行、嵌套标记、多引用及正文尾部引用；没有调用takeQuotedText或发送拒绝，所以不能证明QI01不存在。仅阅读，未运行。

输入文件其它逻辑：粘贴超大文本以UUID缓存txt作为附件，失败返回null；短粘贴用递增ID暂存。acceptExternalMessage校验session，将消息入并发待合并队列，等待sessionLoaded再补UI；mergeExternalHistory在历史边界按dbMessageId去重。待处理消息poll后mapper异常是否重放、文件缓存回收、多个外部消息时序仍需消费者核验，不仅凭一个helper宣布完整恢复可靠。

stream flush helper取消trailingJob及按保留ID清理；完整调用是否覆盖每条终止路径需各拥有者，未拿注释当全部调用证据。子代理roster在功能关闭/child session时不暴露，只有Auto配置才列可选择模型，固定模型不可用仅显示提示，运行解析仍须Runtime验证。

ChatViewModelEdit只核对1—90行：clearChat确实清_quotes及queue，异步删除消息/marker；其余部分未完整阅读，本轮不能登记全文。首次组合cat截断后Input190—260已单独补读，引用和前190行完整可见；已读文件范围严格按证据登记。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelInput.kt | 260 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/QuoteForReplyTest.kt | 36 |

两个现版文件完整读取。新增全文2文件、296行；已全文重读0文件。其它rg定位不计全文。

累计全文1315 / 1758文件，197635行；partial 7，未登记436。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-quote-input.json)。

校验旧206报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
