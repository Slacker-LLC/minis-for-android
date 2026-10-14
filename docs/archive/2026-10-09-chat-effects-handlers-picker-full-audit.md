# 聊天效果、发送操作及媒体选择全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。只做静态审查；旧209份报告与全部台账不覆盖。未改产品代码、编写/执行测试、构建或使用设备/模拟器/Root/云资源。

## QI01补充：界面发送拒绝时，正文也会丢失

原引用输入报告仅确认takeQuotedText在发送接受前清引用，且谨慎说明正文可能仍在。本轮完整阅读ChatScreenHandlers后补齐UI调用链：rememberPerformSendOrEnqueue先结束slash、setInputText(空串)、释放输入焦点，再调用sendMessage(rawText,delivery)，没有返回接受结果或恢复输入。

ChatScreenComposer.kt:1680—1703 按钮仅以hasContent启用，:243—256上滑发送以文本和手势判断；两条都进入该handler，没有压缩中禁止提交。ChatViewModelSend.kt:396附近的_isCompacting拒绝显示系统提示并return，不保存rawText为pending，也不恢复_inputText。因此有正文、压缩中点击发送（或上滑）时，正文被清空且未提交。这是同一“接受前消费输入”的影响范围补充，保留QI01，不另加重复问题编号。

正常context pressure的ASK_USER/COMPACT_THEN_SEND明确持有pendingSendText，不混同直接压缩中拒绝。无Provider路径同样先清输入，但其UI可达性仍需完整屏幕状态复核，本轮确认触发以压缩中为准。记录方向仍为接受/明确持有pending后消费输入，或拒绝恢复；交Claude处理，不实施。

## 分享、预览与选择入口

ShareBufferEffect按version消费share buffer，在本地draft累积多个text而非反复使用旧inputText；附件复制失败累计toast，并仅清本share对应文件。它仍在消费后进行处理，途中异常/取消后的恢复要结合store完整生命周期，未从局部推断全部分享丢失。

SessionDisposableEffect标记活动session，退出时跳过配置变化的空会话清理。SessionLaunchEffect匹配pending.sessionId才消费HTML预览，按session/path摘要缓存后读文件，失败记日志。effect只以sessionId为key，同session后到达的pending是否由导航重新挂载驱动，需完整导航链验证，保留候选不新增确认问题。

URL handler使用当前sessionId解析并区分图像/视频/HTML/其它文件及外部scheme；Markdown gallery从assistant正文抽取图片，minis解析失败落到明确缺失文件而非全局取文件。未把这证明为所有缓存更新或跨session路径安全验收。

媒体picker按上限附加；视频走DOCUMENT。相机提前置overlay suppress，回调和launch失败释放；基于输出文件存在且非空决定成功以兼容OEM返回值。进程死亡后pending状态恢复、URI读取授权寿命仍需状态拥有者核验，不凭本文件判定全部可靠。

QuoteCard最多三行、可选移除操作，纯展示不消费引用。四文件全文输出无截断；Composer仅核对237—267及1670—1718，Send仅核对365—420，不计额外全文。新增问题计数为0，QI01证据及影响范围扩展。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenEffects.kt | 143 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenHandlers.kt | 179 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatQuoteCard.kt | 73 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenPickers.kt | 210 |

四个现版文件完整读取。新增全文4文件、605行；已全文重读0文件。其它rg定位不计全文。

累计全文1323 / 1758文件，200034行；partial 4，未登记431。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-chat-effects.json)。

校验旧209报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
