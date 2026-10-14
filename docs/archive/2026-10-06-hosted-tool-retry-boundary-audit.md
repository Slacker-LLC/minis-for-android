# 托管工具事件与模型重试边界审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2**；全文阅读 6 个文件、724 行。只读静态审查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。独立报告，旧报告不改。

## HR01 / P2：托管工具已开始执行，主循环仍认为没有效果并允许重发

[HostedWebSearchPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/HostedWebSearchPolicy.kt:24) 在模型明确启用 hostedWebSearch 时加入 web_search，现役 Responses 请求构造在 [OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:3074) 调用该策略。因此 classifier 第 76 行和主循环第 7939 行所称“Minis runs no provider-hosted tools”已不符合这条现役路径。

[OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:1058) 解析 response.<kind>_call.<phase>，通过 Ledger 发送 HostedToolActivity 开始/结束事件。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8449) 接到事件后更新 info 展示行，没有设置任何请求效果标志。主循环在 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7942) 初始化 roundToolExecuted=false、roundTurnCommitted=false；前者只在第 9471 行本地 executeTool 前设置，后者只在第 9605 行完整 turn 持久化后设置。

[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8790) 调用 LLMFailureClassifier.decide 时只传这两个标志。[LLMFailureClassifier.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/LLMFailureClassifier.kt:268) 的 terminal 保护依赖 effects.any；没有本地工具和已提交 turn 时，托管搜索事件不能触发这个保护。[OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:1206) 的 response.failed/server_error 抛 TransientError；classifier 将其判为可重试，[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8802) 倒计时后 rollbackFailedAttempt 并 continue，同一个请求再次发给 Provider。

静态序列：新一轮启用了 hostedWebSearch；Provider 报 web_search_call.in_progress，随后报 response.failed/server_error，尚无本地工具或完整 turn 提交。界面已展示搜索开始，但 effects 两项仍 false，请求自动重新发送，新请求仍包含 web_search。客户端没有复用已开始的托管调用或把该事实纳入重试决策，存在重复搜索的条件。

影响限定为托管工具调用可能重复、搜索进度/结果与重试语义不一致及额外远端工作。并不证明每次重试模型都会搜索，也不推断具体收费或上游服务具备/缺乏某种未核验幂等保证；没有实际发起搜索。不把它描述为本地 shell/Root 操作重复，更不推断远端搜索能任意修改用户数据。

修复方向：将现有 HostedToolActivity 的开始事实接入请求效果与恢复策略，明确托管工作开始后是停止、恢复同一响应还是有可证明幂等的重试。复用现有分类器边界，不因单纯展示行而假定没有远端执行。本轮只记录。

## 其它核对与未计数候选

LLMRetryPolicy 的正常返回、两次重试上限、抖动和取消传播已全文阅读；不因 BASE_DELAYS_MS 有未用的第三项就计功能缺陷。该 helper 只问 retryable，是否适用于其它调用的部分流输出还需沿各调用方继续核对。

LLMFailureClassifier 的额度/上下文文本覆盖、精确错误码、HTTP 状态提取、TLS/协议失败拒绝重试、已提交输出/本地工具效果保护及 overflow 上限已全文核对。classifyHttp 将 429 标为限流，而 classifyStreamError 和 typed TransientError 的 rate_limit_exceeded 可以走重试；OpenAIProvider 第 1206 行明确选择后者。本轮记为入口策略差异待核对，不把它自动判为故障。classifyHttp/classifyStreamError 在本轮 main/java 检索中没有分类器之外的实际调用，不能凭无调用 helper 的反例夸大现役问题。

HostedCallEventPolicy 的未知阶段忽略、调用 ID 优先级、同一 ID 开始/结束去重、先收到结束时补开始规则，与已有测试相符。HostedWebSearchPolicy 关闭时保留原数组，开启时不重复加入既有 web_search，并复制新增而不改变输入数组。测试合计 165 行，只静态阅读；不覆盖主循环重试后的托管调用语义，未运行或新增测试。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。该树没有本轮四个 helper 的同名路径；对上游同路径 ChatViewModel/OpenAIProvider 检索 HostedToolActivity 和 hostedWebSearch 也没有命中。这个快照不能提供同一托管事件与重试组合的直接对照，不据此认定上游所有错误恢复正确。源码注释中的 Eta 来源只作为来源声明，没有在本轮独立验证其实现或论文主张。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [HostedCallEventPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/HostedCallEventPolicy.kt) | 70 |
| [HostedWebSearchPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/HostedWebSearchPolicy.kt) | 32 |
| [LLMFailureClassifier.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/LLMFailureClassifier.kt) | 388 |
| [LLMRetryPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/LLMRetryPolicy.kt) | 69 |
| [HostedCallEventPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/HostedCallEventPolicyTest.kt) | 96 |
| [HostedWebSearchPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/HostedWebSearchPolicyTest.kt) | 69 |

累计全文 **1127 / 1546 个文件、220952 行**；partial 13，not_registered 406，excluded 124。全仓通读尚未完成。ChatViewModel 仍 partial，仅登记干净已读片段；已 full 的 Provider 调用方不重复统计。初次输出截断的重试分支和请求构造已单独重读；未将截断搜索结果当成全量证据。

完成时核对全部 1670 tracked 路径的 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 90 份报告哈希、本报告文件链接/行号及全文批次总计。只新建本报告并更新覆盖台账，不读取真实账户、凭据或用户偏好。
