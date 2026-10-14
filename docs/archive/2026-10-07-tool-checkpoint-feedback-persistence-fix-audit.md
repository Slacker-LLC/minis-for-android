# 工具执行意图与消息反馈持久化修复复核

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。全文静态读取5文件、628行；另读取当前ChatViewModel和MinisApp的实际调用片段。旧183份报告及全部覆盖台账保持原样。本轮核实F32/F33、CR06/CR07修复范围，无新增足够证据支持的独立缺陷编号。未修改源码、编写或执行测试、构建、操作模拟器/adb/Root/设备/云资源或真实反馈/检查点文件。

## F32的执行意图敏感参数副本已修复

[ToolCheckpointStore.kt:54](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/ToolCheckpointStore.kt#L54) recordIntentTo先调用ToolSensitivePolicy.isSensitive；敏感调用写ARGUMENTS_PLACEHOLDER，其余保留前4000字符。当前策略识别MCP命名前缀及既有敏感canonical/alias。恢复提醒只读toolName，不用被截断的args重新执行。

ToolCheckpointRecoveryTest的敏感用例实际调用recordIntentTo，以mcp.vault.read/get_setting两个名字和测试canary核对文件不含秘密，普通linux.file.read参数保留。没有读取用户凭据或运行该测试。

原F32同时涉及其它日志、事件、run checkpoint副本。本轮只能确认ToolCheckpointStore这一入口；不能由它已脱敏就宣布全部副本链关闭。已有RunCheckpointStore/SessionEventHub等审计继续按各自证据判断。

## F33正常完成记录累积与删除未接线已修复

[ToolCheckpointStore.kt:92](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/ToolCheckpointStore.kt#L92) settle只保留未在本批callIds中的pending，旧done行和坏行移除；无剩余则删文件，否则经fsync临时文件和ATOMIC_MOVE替换。原“正常完成也一直保留done、按全部历史持续重写”的实现不再存在。仍pending的未知执行不能为缩减文件直接删除，这是保留恢复证据的需要。

[MinisApp.kt:448](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L448) ChatRepository删除回调在UbuntuPaths.deleteSession后调用ToolCheckpointStore.clearSession。旧完全无生产调用的根因已补，不能继续按历史报告声称删除没有接线。清理忽略File.delete的Boolean，guest目录清理先抛异常时也不能仅据这段代码保证后一步完成；这些失败边界没有本轮设备证据，不说所有删除路径均已验证。

markDoneBatch不再使用success区分done/done-failed，因为结果是否成功不影响“已持久确认、不再unknown”的生命周期。不是丢失恢复所需的成功状态：真正结果由Room保存。

## 现役恢复确认順序与未知结果提示

[ChatViewModel.kt:8585](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L8585)工具执行前写intent。[ChatViewModel.kt:9233](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L9233)先await Room追加工具结果，再markDone；不是执行返回就提前抹掉意图。崩溃恰好发生于Room成功之后、markDone之前，[ChatViewModel.kt:2529](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L2529)从有dbMessageId的ToolResult reconcile，随后drainPending读剩余记录。

drainPending/readPending不改文件，历史预览或重复上下文检查不消费未送达的unknown警告。剩余pending转为模型可见Text，提示先核实真实作用，不能盲目重做；没有自动重放工具参数。

仍需保留的候选边界：intent追加appendText没有显式fd.sync；写失败捕获后工具仍执行；读取错误返回emptyList；settle会丢弃无法解析的行。上述限制此前已有候选记录，本轮没有电源中断/存储失败/损坏行的端到端证据，不将“先调用写入”扩展成所有失败下的持久恢复保证，也不宣称已发生重复付款或副作用。

## CR06写入失败被吞、损坏备份失败继续覆盖的旧反例已修

[MessageFeedbackStore.kt:93](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/MessageFeedbackStore.kt#L93) saveAll不再runCatching丢弃结果，临时写入失败向上传播。损坏文件备份必须删除旧备份/rename成功，否则抛IOException，停止覆盖原件。[FeedbackRpcMethods.kt:20](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/debug/FeedbackRpcMethods.kt#L20)反馈put以及delete把IOException转换为RPCException，不继续返回成功。

writeAtomic仍在rename失败后退到plain write，没有fsync或与Room同事务；原“失败仍返回成功”的根因已经修，不意味着所有进程中断都保证文件原子持久。当前未证明正常App私有文件系统会走rename失败回退，不强行新立项。读取损坏时原文件保留，成功备份后新建内容是当前策略。

## CR07真实消息的会话归属已在RPC入口补齐

[FeedbackRpcMethods.kt:18](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/debug/FeedbackRpcMethods.kt#L18)先通过messageById取得真实sessionId，再交给Store.put；新消息反馈不再一律sessionId为空。Store普通put若这次未提供归属，保留原有归属，listForSession据此统计。不存在的messageId仍允许创建无归属反馈；历史无归属记录也不在读取时反查迁移，不能把修复声明扩大为全量历史已补齐。

putDsh/deleteDsh目前主源码检索只有Store定义及内部cas调用，没有找到外部现役接线。因此旧“无version的记录每读一次生成随机version、没有落盘升级”的候选仍在，但不凭未接通的DSH入口另报线上CAS故障。MessageFeedbackRow只有定义，无法据两个普通put调用就认定气泡反馈按钮已经生产可达。

## 既有测试真正覆盖的范围

ToolCheckpointRecoveryTest62行的5例覆盖原子替换、60条pending多次重读不消费、done不报unknown、敏感参数占位、settle只保留无关pending并最终删除。原子替换例直接调用helper，并非进程崩溃/断电，也不走Room→markDone真实链。

MessageFeedbackPersistenceTest64行的4例直接调用生产Store：保存与session重读、后续不传session保留归属、父目录缺失时抛IOException、损坏备份被非空目录挡住时保留原件。未实际调用FeedbackRpcMethods、真实Room或原生/feedback菜单；读取测试源码不等于端到端验证通过。未新增单元测试或执行任何既有测试。

固定OpenMinisHEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1查既有Git树，未找到ToolCheckpointStore/MessageFeedbackStore同名文件。只标明本轮无同名直接对照，不推断其它模块/平台没有等价能力，不fetch或运行上游。

## 本轮覆盖与交付校验

| 新增全文文件 | 行数 |
|---|---:|
| [FeedbackRpcMethods.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/debug/FeedbackRpcMethods.kt#L1) | 56 |
| [MessageFeedbackStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/MessageFeedbackStore.kt#L1) | 287 |
| [ToolCheckpointStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/ToolCheckpointStore.kt#L1) | 159 |
| [MessageFeedbackPersistenceTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/MessageFeedbackPersistenceTest.kt#L1) | 64 |
| [ToolCheckpointRecoveryTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/ToolCheckpointRecoveryTest.kt#L1) | 62 |

五文件首尾完整读取，原始全文输出无截断。ChatViewModel登记2510—2572、8566—8602、9191—9244，仍partial；MinisApp登记443—462，提升为partial，不计全文。一次较大组合检索/旧报告/策略复核输出截断，随后单独补读引用关键入口；没有把该截断输出计为新全文。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-checkpoint-feedback.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-checkpoint-feedback.json)。累计全文 **1338 / 1655文件、224247行**；partial6、not_registered311、excluded124，tracked1779。全库通读仍未完成。

交付校验旧183份报告及全部旧台账hash、本地/远端/上游固定HEAD、tracked/staged无代码diff、1779快照Git blob/hash/bytes/lines及证据行号。仅新增本文与台账。
