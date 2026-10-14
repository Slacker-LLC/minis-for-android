# 思考参数快照与真实请求入口的覆盖范围审查

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮全文5文件、893行；没有新增确认问题，不为增加数量重复登记已有缺陷。只读源码和现有测试，没有创建或执行测试、构建、修改产品代码、启动模拟器/adb/设备/Root/云任务，也未调用账号或模型服务。此前152份报告与旧台账原样保留。

## 核对结果：等级限制没有被调用名绕过

ThinkingWireGoldenSnapshotTest调用sendMessageClamped，容易据此推断测试直接绕过LLMProvider的等级限制。但实际[OpenAIProvider.kt:597](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L597) sendMessageClamped内部第614行调用公共streamMessage；[LLMProvider.kt:83](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/LLMProvider.kt#L83) 再执行clampThinkingLevel，第121行按catalogMaxThinkingLevel限制等级。因此gpt-5类MAX/ULTRA在快照里显示xhigh，与该真实链路一致，不能仅凭方法名把快照判成必然失效。

这也解释了两种快照观察层不同：OpenAI套件驱动真实provider请求，包含上游等级限制与endpoint判断；Gemini/Anthropic套件直接调用resolver shape函数，不执行最终请求builder、温度/输出配置伴随字段或网络协议。后者不能证明完整请求始终符合厂商接口，也不能验证用户规则编辑保存。

## 两套快照逐行核对

ThinkingWireGoldenSnapshotTest369行包含22组model/endpoint组合，每组7等级，共154行期望。观察键限reasoning_effort、reasoning、thinking、enable_thinking、thinking_budget、extra_body；非观察字段即使出错也不会直接导致这个快照失败。交叉组合覆盖Qwen×统一网关、GPT5×DashScope、MiMo×统一网关、Qwen/DeepSeek×OpenRouter，能检查优先级交互；不是所有模型/代理/自定义规则的穷举。

emit安装真实OpenAIProvider并捕获首个MockWebServer请求；runCatching吞掉请求后的异常，再只读取第一个request，测试验证首请求思考字段，不等于完整响应处理/重试成功。部分注释还描述旧行为，当前EXPECTED已明确MiMo/Seed上限，未把注释与期望文案差异当作新的产品故障。没有执行快照或生成新期望。

ThinkingWireGeminiAnthropicSnapshotTest315行包含14个Gemini ID×7等级和8个Anthropic ID×7等级×2输出预算，共210行期望。Gemini覆盖专用TTS/image/embedding与family重叠、2.5/3.x、未知/latest；Anthropic覆盖adaptive与旧预算形态及点号/连字符ID。canonical对JSONObject排序，但对返回Map使用toString，观察的是抽象shape字符串；未据此确认厂商最新协议、所有Android版本一致性或真实账号结果。

真实[GeminiProvider.kt:376](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt#L376) buildThinkingConfig调用resolver；[AnthropicProvider.kt:366](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt#L366) 请求调用anthropicThinkingShape后由provider加伴随字段。前者测试不验证maxTokens与thinkingBudget整体约束，后者shape也不代替display/output_config/temperature最终组合验证。未知family的null和OFF不能完全关闭某些模型是现有明确策略，未在缺少新协议证据时重复报故障。

## Astra与DeepSeek测试的实际范围

Gpt6AstraTest165行：读取本地规则/静态目录、MockWebServer模型发现、API-key自动Responses路径及工具字段、移除过时temperature/logprobs参数、OAuth各等级与account header、带namespace模型保留代理传输、显式DeepSeek Responses选项。OAuth测试用反射替换client并加内存interceptor，fixture请求不走真实端点；这是测试源码意图，本轮并未运行。

[OpenAIProvider.kt:384](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L384) exact Astra ID的默认路由与namespace区分对应测试；[OpenAIProvider.kt:2353](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L2353) applyAstraRequestContract在passthrough后删不接受字段并限制effort，还处理include中的logprobs与旧cache retention字段。现有测试不涵盖任意额外JSON类型、完整代理兼容矩阵或Release反射行为。未把测试里的能力数字当成当前OpenAI产品事实；这里只审查本仓声明与本仓测试。

DeepSeekMissingReasoningTest36行调用真实provider，准备assistant ToolUse但reasoningContent=null，要求错误含reasoning is missing且server.requestCount=0。[OpenAIProvider.kt:2088](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L2088) 正好在thinking启用、DeepSeek模型、null reasoning及ToolUse组合时抛ProviderError；empty string分支随后保留空字符串，不能把null拒绝泛化成所有空reasoning都被拒绝。这个单例未覆盖完整tool-result history、OFF、Mistral、reasoning已有/丢失后的恢复；已有其它测试/报告应分别评估，不能声称项目没有保护或全部已验证。

ProviderTestFixtures8行只强制MockWebServer URL host为127.0.0.1，避免反向DNS主机名导致本地HTTP策略不匹配；没有部署真实网络服务，也不构成真实TLS/OEM网络证据。

## 固定OpenMinis与未计数候选

只读上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 已有Git对象，GIT_NO_LAZY_FETCH=1，没有fetch。上游OpenAIProvider第749—773行同样在sendMessageClamped内部调用streamMessage，方法名误读排除有对应证据。其余上游快照没有本轮全文复核，不宣称逐行完全相同，也不套用上游Runtime架构。

首请求捕获对后续失败的可见性、不带实例自定义规则的快照矩阵、额外字段类型和最终厂商请求约束均是覆盖边界，不凭测试少一个组合就自动新增产品Bug。上一轮TSE01/MT06/MT07不会被这五文件的正常形态测试覆盖，保持旧编号及独立报告不改。

## 全文覆盖登记

| 本轮新增全文文件 | 行数 |
|---|---:|
| [DeepSeekMissingReasoningTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/DeepSeekMissingReasoningTest.kt#L1) | 36 |
| [Gpt6AstraTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/Gpt6AstraTest.kt#L1) | 165 |
| [ProviderTestFixtures.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ProviderTestFixtures.kt#L1) | 8 |
| [ThinkingWireGeminiAnthropicSnapshotTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ThinkingWireGeminiAnthropicSnapshotTest.kt#L1) | 315 |
| [ThinkingWireGoldenSnapshotTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ThinkingWireGoldenSnapshotTest.kt#L1) | 369 |

五文件完整输出无截断；相关provider仅看片段，不登记3763行OpenAIProvider已全文。初次生产片段组合输出截断后已按实际所需短范围复读，未将不完整读取升级全文。片段或方法名字不能代替全库通读。

新台账：[2026-10-07-code-review-coverage-37158d18-thinking-wire-snapshots.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-thinking-wire-snapshots.json)。累计全文 **1214 / 1652文件、196831行**；partial4、not_registered434、excluded124，tracked1776。全库审查尚未完成，目标继续保持。

交付核验旧152份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照Git blob/hash/bytes/lines及证据行号。仅新建报告与新台账。
