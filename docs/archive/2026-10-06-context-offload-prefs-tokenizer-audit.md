# 上下文卸载、Token 估算与偏好状态审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2**；全文阅读 12 个文件、915 行。静态审查，未改产品源码，未运行测试/构建/模拟器/设备/Root/云任务。独立报告，旧报告不改。

## CO01 / P2：卸载写入失败仍替换原内容，模型拿到不存在的保存路径

[ContextOffload.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt:59) 的 offloadContent 和 [ContextOffload.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt:93) 的 offloadImage 调用现役 WorkspaceFileClient 写文件；捕获 Exception 后返回空字符串。文件超上限、空间/权限失败等都会走这条路径，不能保证副本存在。stub 无条件写“saved to”，即使 linuxPath 为空。

现役主循环 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7966) 调用 offloadContextIfNeeded。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7623) 写 ToolResult 文本，图片也分别卸载；[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7638) 不检查最终 linuxPath 是否非空，就替换 content 并清除 imageData。ToolUse 与裸 ImageData 分支同样无保存成功判断。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7672) 写回内存 agentHistory，随后扣 candidate.tokens 并记录 offloadedCount。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7565) 下次看到 OFFLOADED_PREFIX 就跳过，不再尝试原内容。

静态反例：保护范围之外有一个 >500 字符的 ToolResult，当前上下文触发卸载；WorkspaceFileClient 写入因空间不足失败，返回空路径。原结果被内存占位替换，下一轮模型见“已保存”但无法按空路径取回，日志还报告释放成功；重复卸载不会恢复它。文本和图片仅一部分写入成功时，单路径占位也不能证明全部原内容已保存。

影响限定本轮模型上下文及卸载恢复语义；该段没有把原始数据库消息删除，不能据此宣称数据库全部历史永久丢失。持久消息可能仍可供其它恢复路径重新加载，但当前主循环并未因失败恢复/保留原 part。取消异常也属于 Exception，不应被一概当作普通保存失败后继续占位，作为同一失败处理方向记录，不另计。

修复方向：仅在必要副本全部保存且得到有效路径后替换，失败明确保留/反馈；不能因需要减小上下文就虚报磁盘存储。实际无法满足预算时应走现有明确错误/压缩边界。未修改代码。

## CO02 / P2：工具 ID 只取后 12 字符，两个不同结果可覆盖同一个卸载文件

[ContextOffload.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt:37) 的 shortToolId 取最后 12 字符；文本文件名为 sanitized toolName + shortId + ext，图片为 image + shortId + MIME ext。没有完整 ID 的映射、碰撞检查或独占创建。WorkspaceFileClient 的普通 writeBytes 会替换已有目标。

静态输入：同一 session、同一 toolName 的两个不同完整 ID A_123456789012 与 B_123456789012，内容分别甲与乙。它们都得到同一路径，第二次成功写入覆盖甲；第一条模型占位仍指这个路径，回读变成乙。完整 ID 独特不能保证其后缀独特。sanitize 后同名工具也有相同路径风险。

这里不声称随机 Provider ID 经常碰撞、不把它说成跨 session 泄漏；现役 backing 仍按 sessionId 隔离。确认的是文件身份只保留后缀而没有满足独特性的保证，接口合法输入存在确定覆盖。同一工具调用重试是否应覆盖需要定义，不等同两个不同调用应共用文件。

spillIfOversized 另用 UUID 文件名，不属于这个具体问题。修复方向：复用能区分完整调用身份的文件命名或持久映射，保证文档引用不会被无关调用替换；同时处理同一个 tool ID 的多份内容。只记录。

## Token 与其它偏好核对

BPETokenizer 当前生产用 countTokens/countImageTokens；全文 main/java 检索未找到 loadVocabularyFromAssets 的实际调用，故当前走 codepoint/3 启发式。注释的“precise”及误差范围没有本轮校准证据，不把未加载词表描述为真实 cl100k 分词。词表 opt-in 分支没有 pre-tokenization、合并时重复切片等候选，现阶段无调用，暂不计现役故障。高代理字符没有检查后继是否低代理，也是边界候选；本轮没有运行输入验证。

EnhancedCachePrefs 只持久化一次确认，不持久化每个 ViewModel 的 toggle，是代码描述的产品选择，不据此判为状态丢失。FastModePrefs 与 StepsPresentationPrefs 在 MinisApp prime，前者 volatile cache，后者 StateFlow；AssistantHomePrefs 在 AppNavigation prime。AssistantQuickAction 对未知项/重复项过滤，最后一张卡不可移除，问候与内存显示纯规则已全文核对。

DeviceIdentity 初次读写没有同步，在首次并发调用时可能产生多个 UUID；真实首次并发生产调用尚未确认，不扩大为已发生多设备身份错乱。加密偏好通过已有 safeCreate，不读取实际偏好或密钥。AppLinks 的链接统一当前仓库身份，测试覆盖相应 URL 字符串；不实际访问 releases 或检查当前线上隐私内容。

三个已有测试共 205 行只静态阅读，未运行。主页测试尾部因合并输出截断已单独重读；没有为审查编写任何测试。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。上游 ContextOffload 同样短化 ID、文件写失败返回空；主循环相应 10361—10408 的 ToolResult/ToolUse/ImageData 分支也未拒绝空路径，保留 CO01/CO02 条件。上游另一个读回优化入口在 4332 判断 empty 并返回 false，不应把该局部正确检查当作主循环已解决。

上游普通宿主 File 与本地 WorkspaceFileClient 的文件边界不同；不建议恢复旧宿主直写。上游新占位/argument notice 与本地旧占位的语义差异需要其它轮次审查，本轮不将其与保存失败问题合并。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [AppLinks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/AppLinks.kt) | 26 |
| [AssistantHomePrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/AssistantHomePrefs.kt) | 58 |
| [BPETokenizer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/BPETokenizer.kt) | 139 |
| [ContextOffload.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/ContextOffload.kt) | 143 |
| [DeviceIdentity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/DeviceIdentity.kt) | 59 |
| [EnhancedCachePrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/EnhancedCachePrefs.kt) | 29 |
| [FastModePrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/FastModePrefs.kt) | 57 |
| [StepsPresentationPrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/StepsPresentationPrefs.kt) | 80 |
| [AssistantHomeModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/home/AssistantHomeModel.kt) | 119 |
| [AppLinksTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/AppLinksTest.kt) | 59 |
| [StepsPresentationTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/StepsPresentationTest.kt) | 39 |
| [AssistantQuickActionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/home/AssistantQuickActionTest.kt) | 107 |

累计全文 **1118 / 1546 个文件、219632 行**；partial 13，not_registered 415，excluded 124。全仓通读尚未完成。ChatViewModel 仍 partial，只追加干净已读片段，不冒充全文；其它调用方不重复计数。主页模型与测试的关键截断处已修复，全文读取登记在本报告。

完成时核对全部 1670 tracked 路径、SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 88 份报告哈希、本报告链接/行号及批次全文总计。只新建本报告并更新覆盖台账。
