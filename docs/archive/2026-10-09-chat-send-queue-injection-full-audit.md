# 发送、注入、队列排空与重试实现全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。旧205份报告和全部旧台账保留。只读源码/Git，未改产品代码、运行测试或使用设备/模拟器/Root/云资源/凭据。

## QD01 / P2：注入时回写旧消息快照，覆盖期间新增或撤回的气泡

ChatViewModelSend:117先从_messages.value构造msgsAfterUnqueue，之后ensureSession、附件准备、commitMessage及suspend appendMessage完成，:201在Main直接_messages.value=msgsAfterUnqueue。两次列表读写跨越挂起，不是原子状态变更。

明确时序：STEER消息A被取出，得到旧消息列表S；写库等待期间用户发送QUEUE消息B，enqueuePrompt把B加入_promptQueue及_messages并发messageCreated事件；A提交完成后把S写回，只添加A及下个assistant。B仍在_promptQueue里，但刚显示的B气泡消失。用户暂时无法通过该气泡切换投递或撤回；不是B从队列/数据库被彻底删除。若期间撤回其它旧QUEUE消息，旧快照还能重新显示该已撤回的气泡，但其队列记录已不存在。

关键是提交后对当前_messages按本批queuedIds移除占位，而非跨挂起保存整份列表再替换；不建议新增第二消息真源。未在设备复现，以上是现役状态写入与挂起调用的可达交错。PendingDeliveryTest只测过滤函数，不能覆盖这个读写窗口。只记录，交Claude处理。

## 其它候选与正常边界

inject/drain在持久提交前从_promptQueue取走本批；prepare/persist异常时没有本函数内重新入队。inject的空combinedParts返回null也不恢复本批，旧UI占位仍可能存在；需要结合调用方异常恢复、附件只读失败及取消策略继续核验，不另报第二项完整数据丢失。

drain复用旧占位而把多个排队请求持久成一行，没有把该行dbMessageId放进新增LLMMessage。UI数量、DB身份和后续retry定位需要消费者证明，不把合并设计本身当Bug。普通send的programmatic请求已隔离用户composer附件、编辑id和粘贴文本，不沿用旧“后台发送抢用户输入”条件。

send同步claim streaming，LAZY sendJob先bind/登记再start；忙session对程序请求明确session_busy，普通用户消息入队。普通send只有Completed时drain，failure/cancel不自动视为成功。retry保留完成tool blocks、清孤儿toolResult，并修复持久配对；其持久清理读取/写入是否需事务仍是旧历史一致性边界，未额外操作数据库。

setInlineError会flush streaming、清awaiting并异步更新last assistant；实际行身份及fire-and-forget写入交错需继续核对，不仅凭last定位新报跨会话写入。retryLast在验证provider前修改UI/history，no-provider早退的影响需配置变更入口证据。AppRestart全文14行只启动App launch intent再exit当前进程；未实际重启，不能据这两个调用证明任意OEM都已新进程成功启动。

## 覆盖

ChatViewModelSend1047行现版全文：前轮30—105，本轮1—29、106—350、351—650、651—850、851—1047；完整输出无截断。AppRestart完整14行。没有将AgentLoop和Repo检索算全文。全库仍未通读完。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSend.kt | 1047 |
| src/android/app/src/main/java/com/openminis/app/AppRestart.kt | 14 |

两个现版文件完整读取。新增全文2文件、1061行；已全文重读0文件。其它rg定位不计全文。

累计全文1313 / 1758文件，197339行；partial 6，未登记439。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-chat-send.json)。

校验旧205报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
