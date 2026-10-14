# Provider请求：已有测试范围全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，未启动MockWebServer或发送任何请求，未运行测试；只新增报告与台账。没有新增已确认产品缺陷。

## 空key刷新

EmptyKeyRefreshTest 只检查 ProviderInstance.allowsEmptyAPIKey：自定义OpenAI/Anthropic兼容URL允许空key、未设或空白URL拒绝、OAuth拒绝、Gemini/OpenRouter拒绝。它没有调用 refreshModels、usableApiKey 或真实加密存储，不能证明空key刷新完整请求已经成功，也没有验证URL是否真为自建而非官方地址。测试注释的历史原因作为背景，不替代现版调用链。

## 推理字段

MistralReasoningFieldTest 设计为真实OpenAIProvider加本地MockWebServer，取首个请求JSON检查历史reasoning_content抑制，含大小写和非Mistral对照。URL通过path放置mistral.ai触发供应商谓词，实际host仍为loopback；所以它测字符串识别及请求构造，不能证明host身份精确识别或真实服务端接受。

请求调用异常被runCatching忽略，随后无显式超时takeRequest。若在发请求前失败，测试可能等待而非给出清楚拒绝；若发出后响应解析失败，字段断言仍可能通过。这里只记录测试诊断范围，不新增生产Bug。未执行测试。

## 缓存控制

OpenRouterCacheControlTest 取首个请求检查Anthropic前缀的cache_control存在且type=ephemeral，其他模型、其他兼容路径和Mistral不含此字段，大小写模型前缀接受。没有实际缓存命中、计费或usage数据，因此不能用这些断言证明节省成本已发生。

标题所称非Anthropic请求“byte-identical”实际只断言cache_control不存在，没有原始字节或全部字段比较。OpenRouter供应商标记也通过URL path子串触发，不是精确host身份测试。真实服务当前协议要求需另查官方来源；本轮不根据测试注释作外部协议事实结论。

两个MockWebServer文件都预排四份响应以容纳重试，只读取首请求，没有断言实际重试次数或整轮成功；不把首请求格式检查扩大为可靠性端到端验证。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/provider/EmptyKeyRefreshTest.kt | 106 |
| src/android/app/src/test/java/com/openminis/app/provider/MistralReasoningFieldTest.kt | 137 |
| src/android/app/src/test/java/com/openminis/app/provider/OpenRouterCacheControlTest.kt | 154 |

三个现版文件完整读取。新增全文3文件、397行；已全文重读0文件。其它rg定位不计全文。

累计全文1395 / 1758文件，211281行；partial 4，未登记359。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-provider-request-tests.json)。

校验旧236报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
