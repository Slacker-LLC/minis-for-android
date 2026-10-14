# Headless与聊天RPC全文审阅：请求结果和附件文件身份

日期：2026-10-07。固定远端HEAD `a94f98c97d0c478eff8530e9e7758d3b93216b83`。本轮全文2文件、1347行，另重读已有全文AgentTurnHandle及其测试93行，不重复累计。独立新报告及台账，保留旧191份报告/全部旧台账。未修改代码、编写/运行测试、调用RPC/模型/端点、读取凭据、构建、操作模拟器/adb/Root/设备/云资源。

新增1项静态确认P2：RAC01（相同路径生成条件成立时的覆盖）。CR01/CR02/CR05是旧问题状态，不重新编号。这里讨论已授权调试调用的语义，不能把RPC缺陷描述成未授权远程入口。

## RAC01 / P2：毫秒时间戳不是附件唯一身份，同路径后写覆盖前写

[ChatMutationMethods.kt:465](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L465)parseAttachments为每个附件生成System.currentTimeMillis()−name的缓存文件名；outputStream直接创建/截断目标。名字经过取最后路径段、字符清洗和64字符截断，但没有每请求/每条目的唯一后缀、独占创建或重名拒绝。

合法输入可以包含两个同名note.txt，内容分别甲与乙；也可以原名不同但清洗/截断结果相同。如果两次取时间戳处在同一毫秒，二者目标文件相同，第二次写入覆盖甲。out里两个InputAttachment都引用这个文件的URI；解析完批次之后才交给AgentRunner.prompt，因此前一附件并没有在覆盖前复制成独立内容，消费时两个条目都读乙。

也可能发生跨并发RPC请求的同路径生成；此目录全局共用，不含requestId或sessionId。本轮不声称每次普通调用都能撞到同一毫秒、不同会话都泄漏，也未运行真实时钟或并发复现。确认的是文件身份规则不保证独立性，条件成立时明确有覆盖行为，且单批次亦可触发。

与CR05区分：CR05是拒绝/失败请求留下缓存及提前修改绑定；RAC01是两份被接受的不同附件在存储身份上合并，修复失败清理不能自动解决。文件名清洗是真实路径保护，8项/单项16MiB/批次32MiB限制是真实预算，但这些不能防文件重名覆盖。

记录方向：复用现有能保证每条目唯一文件身份的创建方式，显示名与存储名分开；保留原路径清洗和大小限制，不新增通用文件服务。未来授权验收通过公开chat.prompt传相同显示名及清洗碰撞的不同小内容，核验每条目独立URI、文件摘要和模型附件内容；再覆盖并发请求及失败清理，保存可复核工件。当前只记录，不实现或运行。

固定上游HEAD`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`的[ChatMutationMethods438—467](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L438)同样使用时间戳−name和普通outputStream。该身份问题双方保留；本地额外清洗/批次限制改善其它边界，不能据此认定身份唯一。仅读438—468、未fetch。

## CR01/CR02/CR05仍有当前实现证据

applyModelOverride仍只更新数据库binding/modelId，未切换已加载VM；RPC prompt/retry先执行它，之后才校验附件、thinkingLevel和提交。无显式modelEntryId还可能选择Main并写绑定，没有恢复原绑定。runner另有selectModel调用真实selectEntry，但prompt参数没有复用这条实时切换路径。CR01不能凭提供独立selectModel接口结案。

[ChatMutationMethods.kt:105](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L105)prompt返回userMessageId仍取数据库最后一条user，未采用本次AgentTurnHandle.userMessageId。Busy不插入本次消息却仍可能回显旧消息ID；等待期间后来的输入也可能成为最后一条。runner内部responseAfter用请求ID限制答案，是实际改善，但不能修正外层CR02元数据。

附件逐项写缓存，后项超限/非法thinkingLevel时未删除已写前缀；拒绝前已经创建session或改变binding。CR05仍在。坏base64、空bytes及非对象条目被跳过，不按每附件返回错误；是否应整批拒绝属产品校验语义，未另立项。

## 请求完成与取消的实际保证

prompt/retry/rerun现在用各自AgentTurnHandle结果，Completed/Cancelled/Failed/NeedsAttention/Rejected分别映射。AgentTurnHandle只在绑定Job完成后完成Deferred，已有3例测试分别覆盖一个请求取消不影响另一个、等子清理结束才完成、拒绝及NeedsAttention不会变成功。这些测试已全文重读但未运行，且属于Job/helper层，不代替VM端到端证据。

prompt的responseAfter仅看本次user之后、下一非空user之前最后一条assistant；这保护了旧“拒绝后返回上次回答”的主要原因。tool-block rerun仍取最终数据库最后assistant，实际截断/失败/并发外部回执时是否对应该rerun需更完整VM时序，不凭正常路径直接报旧回答必现。

超时取消request后最多再等5秒，允许streamExited=false返回；外层取消request后立即重抛，没有NonCancellable退出等待。旧SC03及取消生命周期问题需按调用方持续跟踪，不能从注释“timeout never leaves running”推导无限保证。

[ChatMutationMethods.kt:109](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L109)prompt/retry/rerun RPC响应没有序列化streamExited，状态Timeout因此不能让客户端知道清理是否仍在执行。AgentRunner内部保留此字段，不能说全系统完全不提供退出事实，也不将遗漏字段独立重报成已确认权限逃逸。

retry的deletedMessageCount先按旧列表大小计算；no_provider分支也返回这个预测值，未执行删除。rerun用before−最终msgs.size，期间新增消息可影响结果。沿用已有headless删除数量记录，不重复编号。

## 压缩与快照边界候选

compact等待isCompacting出现后消失，用summary字符串比较，但changed与未changed最终都返回Completed；失败与成功而摘要相同的情况不靠该布尔值区分。RPC compactBefore额外返回marker数量及wrote，不能把Completed单独当已写新摘要证明。若调用开始/结束都落在首次观察之前，等待true可能错过后给NoOp；需要真实VM/脚本Provider场景再核实，不在未运行情况下宣称已复现。

compactRevert短轮询marker数量，本轮没有仅凭runner“同步”注释新增重复问题。cancel只请求cancelStream并返回此前wasRunning，不等完整Job退出；delete先cancel再删再forget，不视为已验证安全等待退出。

sessionSnapshotWithWatermark先在IO读durableMessages（limit2000）及durableStatus，再在事件锁内合成materialized tail。锁保证tail与waterline一致，不等于此前数据库读取也处在同一事务水位；长会话、tail裁剪及数据库读取期间变更如何恢复仍需完整SessionEventHub和客户端重放合同核对。totalCount在此为合成结果长度，不能单凭字段名字当整库计数。

只调用已知模型selector仍不能证明modelName/实际请求立刻一致；uiPrompt在sendMessage之后直接返回ok=true而没有请求结果，也需按调试接口意图区分接受与执行完成。均保留具体候选，不强行扩充缺陷数量。

## 可达性、全文和交付

DebugRPCHandler155—174实际接线prompt/uiPrompt/retry/rerun/status/cancel/selectModel/thinking/delete/compact。DebugMethodRegistry698—730列出附件base64与模型覆盖参数；这些已全文文件的片段复核不重复累计。未访问真实DebugServer或调用任何接口。

| 本轮全文 | 行数 |
|---|---:|
| [ChatMutationMethods.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/ChatMutationMethods.kt#L1) | 529 |
| [HeadlessChatRunner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt#L1) | 818 |

HeadlessChatRunner1—240、241—480、481—720、721—818完整读取；ChatMutationMethods1—260、261—529完整读取。检索组合输出部分截断仅用于定位，核心全文已独立无截断读取。AgentTurnHandle40行及测试53行重读不重复计数。

新台账：[2026-10-07-code-review-coverage-a94f98c9-headless-rpc.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-a94f98c9-headless-rpc.json)。累计全文 **1373 / 1662文件、230484行**；partial11、尚未登记278、excluded124，tracked1786。全库通读未完成。

交付校验旧191份报告/全部旧台账hash、本地/远端/上游HEAD、tracked/staged代码diff、1786快照blob/SHA-256/字节/行数及引用行号。只新建本文与台账。
