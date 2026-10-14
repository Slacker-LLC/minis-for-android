# 指定消息重试、工具块重跑与编辑删除全文审查

2026-10-09，固定主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`；本轮 ls-remote 确认远端 main 相同。只做静态阅读，未改产品代码、执行测试、构建或使用设备、模拟器、Root、云资源。以前报告和台账全部保留。

## QD02 / P2：指定消息重试和工具块重跑成功后遗漏 QUEUE 消息接续

ChatViewModelEdit.kt:365、647 两个入口都启动共享 runRerunStreamTail（669—790）。它调用 runAgentLoop，记录结果，再释放 session/并发槽并清 streaming。成功路径没有调用 drainQueuedPrompts。

当前 ChatModels.kt:221 的 dueAtStepBoundary 只选择 STEER；ChatViewModelAgentLoop.kt:1118 的步边界注入也使用这个筛选。用户在上述重试/重跑期间选择 QUEUE 的输入不会进入该轮，成功结束后也没有新一轮接续。队列项及等待气泡仍存在，必须等其它发送/恢复动作带动，不能把这描述为永久删除消息。

对照：ChatViewModelSend.kt:633 的普通发送和 :998 的 retryLast 都在 Completed 后调用 drainQueuedPrompts。Lifecycle.kt:78 的 resumeQueueAfterCancel 是取消动作触发，不能补成功完成路径；ChatViewModel.kt:1457、1478 的 streaming 观察者只清生成状态和发状态事件，没有队列接续。

静态复核步骤：从旧用户消息重试（或工具块重跑），运行中提交 delivery=QUEUE，让 runAgentLoop 返回 Completed；跟踪共享 tail 到 streaming=false，会发现 queue 未消费且没有安排后续 drain。未执行设备复现。建议在相同成功条件和现有 Bot turn 锁/并发槽生命周期内复用现有队列接续，交 Claude 处理。

## 其它核对结果及范围

deleteSingleAssistantMessage 与 deleteFromMessage 使用数据库删除提交后再更新 UI 的 helper；指定消息 retry 和 truncateBeforeEdit 的 UI/记忆撤销发生在数据库操作之前。后者的失败反馈和 ordinal 截断映射需要结合既有历史问题继续核验，本轮不重复登记或直接称全部删除路径已修复。

工具块重跑定位持久 tool_use，按块位置保留前缀、删除后续并重建历史；缺少锚点时终止设置。退化到用户重试的快捷路径有内容及前驱条件。已有防止旧 streamJob 清新一轮 streaming 的身份检查，本轮未把它列为缺陷。

本轮接续上一批已读 1—90 行，完整读取 Edit 91—1146；末段 1051—1146 已单独补读。Lifecycle 1—125、AgentLoop 1090—1130、ViewModel 1440—1510 仅核对调用，不据此增加全文计数。grep 仅定位，未运行测试。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelEdit.kt | 1146 |

一个现版文件完整读取。新增全文1文件、1146行；已全文重读0文件。其它rg定位不计全文。

累计全文1316 / 1758文件，198781行；partial 6，未登记436。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-retry-rerun.json)。

校验旧207报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
