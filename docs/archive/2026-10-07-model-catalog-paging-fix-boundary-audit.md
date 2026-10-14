# 模型目录分页修复与传输策略边界复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮新增全文6文件、811行。没有新增重复问题编号；核验旧MC01—MC04的修复范围，记录MC02/MC03残留。只读审查，没有改产品源码、创建或执行测试、构建、模拟器、adb、Root、设备、云任务、真实凭据或模型目录网络请求。此前150份报告及全部旧台账不改，留给Claude处理。

## 旧问题修复状态

原编号来自[模型目录审计](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-model-catalog-refresh-audit.md)。

| 编号 | 当前静态结论 |
|---|---|
| MC01 Anthropic日志含x-api-key值 | 原日志入口已修：只打印request.headers.names()，不打印header值 |
| MC02 Anthropic不读取后续页 | 已有游标遍历、20页预算和失败返回空；但缺data的后续页可让部分目录冒充完整结果，未完全闭环 |
| MC03 Gemini翻页失败提交部分目录 | API已拒绝后续失败及超页预算，不再直接返回已收集列表；Repository备用目录替换仍需区分失败与成功快照 |
| MC04 Gemini官方能力字段丢失 | 当前解析仍只构造名称/厂商，inputTokenLimit/outputTokenLimit/thinking未进入LLMModel，旧入口仍在 |

MC05/MC06本轮未完整重新核对相关派生索引/元数据选择实现，不补写修复结论。

## MC02残留：后续页不是目录仍可提交首批模型

[AnthropicModelsApi.kt:205](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt#L205) collectAllPages只要求body可解析成JSONObject。data用optJSONArray读取，缺失或类型错误仅跳过追加；[AnthropicModelsApi.kt:192](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt#L192) pageNext把缺has_more默认成false。累计pages>1后，Done会生成包含merged的正常data对象，掩盖本页缺少目录数组。

明确静态反例：第一页 `{"data":[{"id":"model-A"}],"has_more":true,"last_id":"model-A"}`；第二页HTTP 200且body `{"error":{"message":"temporary gateway error"}}`。第二页是可解析JSON，data缺失且has_more缺失；循环把它认作Done，返回第一批model-A。parseAndCache接受这个人工合并的非空data；[ProviderRepository.kt:2230](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt#L2230) 对非空列表replaceEntries并返回true。原普通model-B若不在结果中，[ProviderRepository.kt:1321](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt#L1321) 删除该instance的旧列表后只加入新结果、剩余自定义项和明确语音模板项，B不受保护。

这是HTTP200内容畸形/兼容网关错误的条件性反例，不声称官方服务当前发送这种响应。合法多页成功、HTTP失败、无游标、超过20页这些原缺口已改善；不能把“支持翻页”当成每页都验证完整性。仍属于MC02的不完整目录替换问题，不新计。方向记录：每一页都必须是可接受的模型页后才能提交合并快照；单页/后续页错误不能在重包装时消失。

AnthropicModelsPagingTest全文44行，只覆盖pageNext的Done、cursor、URL编码及缺游标，并未调用完整collectAllPages/Repository；缺data后续页与旧目录保留不在其验证范围。未运行或补写测试。

## MC03：API失败关闭不等于调用链保留旧目录

[GeminiModelsApi.kt:70](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt#L70) 后续网络异常、HTTP失败、解析失败都返回emptyList；第98行仍有pageToken则也返回emptyList，旧直接提交部分collected的分支已修。GeminiModelsPageTest全文复读，解析器对错误JSON返回null；该文件已有full记录，不重复累加。

但[ProviderRepository.kt:2243](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt#L2243) refreshModels在emptyList后统一调用ModelsDevApi.fetchModels；备用结果非空就replaceEntries并true，没有携带“真实目录已翻页中断”的原因。[ModelsDevApi.kt:55](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/ModelsDevApi.kt#L55) 按精确API或host取注册目录。因此在备用注册目录匹配且缺少某个现有普通模型的条件下，失败仍可变成替换成功，不能承诺所有失败都保持现有目录。无备用匹配时保留原目录并返回false，不能泛称所有失败都会删模型。

选择性核对当前捆绑JSON的google/anthropic条目，api均未提供；没有用这个捆绑快照证明官方实例当前必然命中备用目录。运行时也可加载缓存/刷新的注册表，完整风险以匹配条件限定。这是旧失败完整性家族的调用方残留，不新计。是否允许备用目录覆盖用户已有实时目录需要明确产品策略，不能靠API里“caller keeps its current catalog”的注释证明已经实现。

## 传输策略与更新下载的复核

ProviderTransportPolicy全文232行已full，本轮再完整阅读，不重复计数。requireAllowedInstanceBase要求HTTP必须来自显式API-key自定义本地/私有origin，且与持久化批准origin完全一致；OAuth不允许HTTP。ProviderRepository刷新入口第2128行先调用该校验，因此不能只因AnthropicModelsApi.clientForBase本身没有批准字段，就报生产刷新绕过批准。

HTTP client关闭所有自动重定向；HTTPS关闭scheme变化。protectedHttpsBuilder有直接请求HTTPS拦截器。因此上一轮UpdateChecker.download虽未单独requireHttps，实际protected client仍拒绝直接HTTP，不能把缺少重复校验写成明文下载漏洞。

ProviderTransportPolicyTest全文239行覆盖公开HTTP、未批准本地HTTP、OAuth HTTP、origin/port/scheme变化拒绝、HTTP redirect配置、直接HTTP零请求的MockWebServer断言、secondary URL同origin边界。测试仅被阅读，没有运行；HTTPS降级用例只断言client设置，没有真实TLS重定向端到端执行。local hostname属于策略的字面判断，没有DNS解析目标校验，不能将其描述成出站网络隔离或设备实测防线。

## 缓存与其它候选

ProviderModelsCache61行与AnthropicModelsCache54行完整阅读。凭据组合只以SHA256文件名存储，Entry正文保存models/savedAt；不是原始API key缓存文件。7日TTL使用墙钟，文件writeText非原子但decode失败回网络；没有据此确认目录必然持久丢失。真实Repository上述调用均未传context，缓存并未用于这条手动刷新路径，不能把TTL当成其当前故障持续时间。

Anthropic首响应body.string未通过response.use包围，body读取通常会关闭source；异常关闭行为需库语义证据，未仅凭缺use另报连接泄漏。总字节/模型数预算、body.string内存上限和重复cursor超页预算仍可进一步核对，当前有20页/5页限制，不能把页数有限写成字节数有限。Gemini第一请求网络异常仍向Repository传播而后续为空；取消与fallback语义需独立闭环，不因为catch宽泛就另计取消缺陷。

## 固定OpenMinis对照

只读已存在Git对象，GIT_NO_LAZY_FETCH=1，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，没有fetch。上游Anthropic第95行仍打印整个headers，第128行成功即parseAndCache，没有本地新增多页收集器。上游Gemini第45—104行仍为单次目录请求，没有本地分页循环。当地修复确有进展；上游旧代码不能证明本地新增分页的错误页校验已经正确，也不把本地分页残留归因于Direct Ubuntu。

## 新全文覆盖

| 文件 | 行数 |
|---|---:|
| [ProviderModelsCache.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/ProviderModelsCache.kt#L1) | 61 |
| [AnthropicModelsApi.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt#L1) | 274 |
| [AnthropicModelsCache.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsCache.kt#L1) | 54 |
| [GeminiModelsApi.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt#L1) | 139 |
| [AnthropicModelsPagingTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/AnthropicModelsPagingTest.kt#L1) | 44 |
| [ProviderTransportPolicyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ProviderTransportPolicyTest.kt#L1) | 239 |

六文件完整阅读无截断。ProviderTransportPolicy/GeminiModelsPageTest全文复核不重复计数；Repository、ModelsDevApi及上游只读相关片段，捆绑JSON为选择性字段核对，不将其算成全文通读。

新台账：[2026-10-07-code-review-coverage-37158d18-model-paging.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-model-paging.json)。累计全文 **1206 / 1652文件、194465行**；partial4、not_registered442、excluded124，tracked1776。全库通读尚未完成。

交付核验旧150份报告与全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照Git blob/hash/bytes/lines及证据行号。仅新建报告与本轮新台账。
