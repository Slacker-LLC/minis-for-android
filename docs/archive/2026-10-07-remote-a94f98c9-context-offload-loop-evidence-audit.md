# 上下文卸载与循环内策略抽取：当前缺口及测试边界

日期：2026-10-07。固定远端HEAD `a94f98c97d0c478eff8530e9e7758d3b93216b83`（#141）。新建独立报告及覆盖台账，保留旧188份报告和全部旧台账。新增全文7文件、902行；另外重读已有全文ContextOffload与ContextPolicy，共234行，不重复累计。仅静态审查，未修改产品代码、编写/运行测试、构建、启动模拟器、操作adb/Root/设备/云资源。

本轮新增1项P2：COL01。CO01、CO02是旧问题的当前状态复核，不重新计数。

## COL01 / P2：卸载后仍按旧读数判断上下文，能够错误停止可继续的轮次

[ChatViewModel.kt:6496](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L6496)每轮先调用ContextOffloader，传入上一响应的lastContextTokens及可变agentHistory。[ContextOffloader.kt:267](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ContextOffloader.kt#L267)成功处理候选后替换history中的part，再减少自己的局部currentTokens；函数不返回新读数，也不更新ViewModel的_lastTurnContextTokens。

接着[ChatViewModel.kt:6511](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L6511)调用inLoopContextCheck。该方法[ChatViewModel.kt:2720](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L2720)仍从_lastTurnContextTokens取值，交给[InLoopContextPolicy.kt:38](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/InLoopContextPolicy.kt#L38)决定是否压缩或停止。[ChatViewModel.kt:7214](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L7214)只有后续响应usage才更新上一请求的读数。两次调用之间没有基于已卸载history重新测量的步骤，因此“检查发生在卸载之后”不等于“检查使用卸载后的大小”。

静态反例：模型窗口32768，上一响应报告输入30000 tokens；保护的最后4条消息之外存在可卸载的大工具结果。落盘成功并替换后，实际待发请求连同固定提示/工具schema已经低于22768，而_lastTurnContextTokens仍为30000。ContextPolicy在32K—64K档把exhaust线设为window−10000，即22768；InLoopContextPolicy于是返回STOP_EXHAUSTED，ViewModel进入停止分支，本来能发送的后续请求不被发送。

对可压缩的大窗口，相同旧读数会额外触发压缩，消耗摘要请求和压缩次数；本轮没有据此声称所有3次预算都必然消耗。卸载失败的问题单独归CO01，COL01的反例要求保存成功，不能靠修复保存失败处理解决。

修复方向仅记录：循环策略应判断当前将发送的请求，并沿用既有上下文预算/测量边界，考虑system prompt、工具schema、保留尾部和新工具结果。不能把局部currentTokens日志直接当作准确新读数；它扣掉整个候选，没有加回stub/剩余参数且使用不同估算尺度。

后续授权验收应以真实VM/Room和脚本Provider构造32K及大窗口两组场景，保存上一usage、卸载前后实际请求、策略决定、Provider调用次数与卸载文件摘要；验证成功卸载后继续、卸载不足仍停止/压缩、保存失败保留原文。本轮没有执行这些场景，也没有新增测试。

## OpenMinis在该边界已有不同实现

固定既有上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读现有对象，没有fetch。[OpenMinis ChatViewModel:10917](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L10917)卸载入口传measureOutboundContextTokens而非旧lastContextTokens；[OpenMinis ChatViewModel:5562](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L5562)循环检查重新取contextMeasurement().measured。上游压缩后还重新测量，并记录压缩是否有进展。

这说明COL01不是“所有实现都只能用上一响应”的限制。本轮仅比较上述职责，不建议整体搬上游ViewModel，也不迁移其旧sandbox/PRoot或宿主文件边界。上游本轮读取5550—5620、10902—10945、10183—10215、10377—10422片段，不计为本库全文。

## CO01、CO02在新远端仍未修复

[ContextOffload.kt:64](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt#L64)文本/图片写失败依旧catch Exception后返回空字符串。[ContextOffloader.kt:233](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ContextOffloader.kt#L233)结果分支仍生成“saved to”stub并清除imageData；工具输入和裸图片也没有保存成功才替换的校验。写失败、部分副本失败及取消传播边界保留原CO01，影响当前模型上下文；这里没有删除数据库原始消息，不宣称历史全部永久丢失。

[ContextOffload.kt:38](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt#L38)文件名仍只保留toolId末12字符，同session同toolName不同完整ID但相同后缀会得到同一目标。后写覆盖前写，旧stub回读到另一结果，CO02仍在。spillIfOversized使用UUID且写失败向上传播，不属于同一个后缀覆盖或空路径分支。

ToolResult同时拥有大文本和大图片时可以写两份文件，但stub只提供一个路径；小图片也会在文本卸载时清除。此处的“全部内容可恢复”语义仍需与CO01记录的单路径恢复边界合并评估，不单独重报图片缺口。

## 抽取的真实边界

ContextOffloader负责估算、候选选择、调用保存和内存替换；InLoopContextPolicy负责纯策略及3次压缩上限。它们确实减少了ViewModel职责，但runAgentLoop、压缩等待、状态/数据库更新仍在VM，不能认定Agent Engine已完全脱离UI。

保护范围是最后4条message，不是完整最新用户轮或全部未回答tool batch。工具输入只识别file_write/file_edit的content，大工具结果与裸图片也可候选。遍历从history起点开始，未在此类处理压缩锚点；候选是否实际发送、未答调用是否会重放，需要结合完整effective history链继续核对，不在这一轮仅凭候选代码宣布已执行错误工具。

force路径把target设为0，但仍以currentTokens<=target终止；当前调用方未传force，本轮没有将这个未用分支上升为现役确定问题。字符估算不包含固定提示/schema，token排序依赖OutgoingHistory/BPETokenizer；不把注释“precise”视作真实校准证据。

## 已有测试源码实际验证什么

ContextOffloaderTest29行2例只验证文本字符估算、空history和长度单调。第一例名称声称包含tool input/output，实际仅创建Text；没有调用offloadIfNeeded、保存路径、候选替换、失败、图片或token回传。测试名不能补足覆盖。

InLoopContextPolicyTest41行5例覆盖未知读数/窗口、低压力、可压缩及预算用尽、32K停止。它只验证传入数字的分类，没验证该数字是否来自当前待发请求，因而不能证明COL01不存在。

本轮另外全文读取现有Android测试及helper，共495行：

- ChatVmHarness：真实ChatViewModel和内存Room，反射注入脚本Provider，等待sessionLoaded，finally清VM、等stream退出并关DB。
- AgentLoopCharacterizationTest：9例覆盖工具执行和下一请求回放、未提供工具拒绝、chat-only、跨轮历史、短对话不压缩、摘要插入、首次标题/已有标题、附件存储及清空。
- BotTurnExecutionTest：5例覆盖前次成功不污染失败、取消退出、回执去重、partial/length结果、retry自己结果和busy拒绝、后台prompt保留用户草稿/编辑。

这些测试可作为真实VM/Room行为证据来源，但本轮未运行。脚本Provider没有产生context usage，也没有构造成功卸载后策略重新评估，不能当作COL01的现有否定用例。它们也没有覆盖Root/SELinux/VPN/OEM或Release。

附件例只在成功路径file.delete，断言失败时cache文件可能留下；helper忽略awaitStreamExit返回后关闭DB，真实超时后是否仍写DB需另证。把这些列为测试隔离候选，不混入产品必现缺陷。

## 全文覆盖及版本迁移

相对上一快照，1个已有文件ChatViewModel变化，4个新增文件，无删除。变化VM清除上一片段，只登记2690—2790、6470—6545、7200—7235；diff不算全文。其余同SHA-256文件继承覆盖。

| 本轮新增全文覆盖 | 行数 |
|---|---:|
| [AgentLoopCharacterizationTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/androidTest/java/com/openminis/app/ui/chat/AgentLoopCharacterizationTest.kt#L1) | 198 |
| [BotTurnExecutionTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/androidTest/java/com/openminis/app/ui/chat/BotTurnExecutionTest.kt#L1) | 197 |
| [ChatVmHarness.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/androidTest/java/com/openminis/app/ui/chat/ChatVmHarness.kt#L1) | 100 |
| [ContextOffloader.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/ContextOffloader.kt#L1) | 289 |
| [InLoopContextPolicy.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/chat/InLoopContextPolicy.kt#L1) | 48 |
| [ContextOffloaderTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/chat/ContextOffloaderTest.kt#L1) | 29 |
| [InLoopContextPolicyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/chat/InLoopContextPolicyTest.kt#L1) | 41 |

新台账：[2026-10-07-code-review-coverage-a94f98c9-context-loop.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-a94f98c9-context-loop.json)。累计全文 **1360 / 1662文件、226963行**；partial9、尚未登记293、排除124，tracked1786。全库通读未完成。

交付校验旧188份报告/全部旧台账hash、本地及固定远端/上游HEAD、tracked/staged代码diff、1786快照Git blob/SHA-256/字节/行数和引用行号。只新建本文与台账。
