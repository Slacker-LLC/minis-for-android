# 模型目录、刷新完整性与能力映射审计

日期：2026-10-05。项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮只读审计，独立新建报告；不修改产品代码或旧报告，不运行构建、测试、模拟器、adb、Root、设备或云任务，不读取任何真实凭据。

本轮新增 **6 项问题：1 项 P1、5 项 P2**。新增全文阅读 15 个文件、2,268 行，累计 595/1,546 个纳入范围的文件、121,694 行。全库审计仍未完成。以下反例来自源码和官方接口合同，没有声称已在真实账号或设备复现。

## 问题索引

| 编号 | 级别 | 问题 | 可达影响 |
|---|---|---|---|
| MC01 | P1 | Anthropic 官方 API Key 随请求头写入日志 | 刷新模型时产生含密钥的日志记录 |
| MC02 | P2 | Anthropic 忽略分页游标和 has_more | 后续页模型遗漏，刷新替换已有目录 |
| MC03 | P2 | Gemini 后续页失败仍返回部分列表 | 不完整结果被标为成功，旧模型条目被删除 |
| MC04 | P2 | Gemini 丢弃接口给出的 token 限制和 thinking | 新模型可能使用错误预算、隐藏思考开关 |
| MC05 | P2 | models.dev 更新没有失效发布日期索引 | 模型信息更新后排序仍使用旧目录 |
| MC06 | P2 | 单模型与批量模型采用不同的元数据选择规则 | 同一模型在刷新和 model_use 列表中能力不一致 |

## MC01 — 官方 Anthropic 密钥进入请求日志

[AnthropicModelsApi.kt:91](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt:91)在非 OAuth、官方地址分支写入 `x-api-key`；[95 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt:95)随后无条件把整个 `request.headers` 插入 `Log.d`。现役 [ProviderRepository.kt:2173](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2173)会调用此入口，没有自定义地址的实例使用官方分支。

项目使用 [OkHttp 4.12.0](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:557)。该版本 [Headers.toString](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Headers.kt#L184)会查询 [isSensitiveHeader](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/Util.kt#L231)，默认脱敏集合没有 `x-api-key`。因此本项不是“所有请求头都泄漏”：Authorization/Bearer 在这个 toString 路径中会被隐藏，官方 API Key 不会。

触发条件是用户以官方 API Key 刷新 Anthropic 模型目录。源码没有 Debug gate；没有运行 Release 构建或提取设备日志，因此不把它写成所有安装包上都已验证的日志外泄，也不声称普通远程调用者可直接读取 logcat。实际风险是凭据被复制进调试、诊断或日志收集链路。

方向：此日志只输出无凭据的请求摘要，不能依靠 OkHttp 的默认敏感头名单覆盖厂商密钥。本轮未改代码。

## MC02 — Anthropic 列表只读取第一页

[buildURL:185](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt:185)固定发送 `limit=512`；[124 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt:124)在首个成功端点立即返回。上面的 candidateBases 循环是在探测兼容端点路径，不是在翻页。[parseAndCache:141](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt:141)只读取 data，不读取 has_more/last_id，也没有 after_id 请求。

Anthropic 的 [Models List 合同](https://platform.claude.com/docs/en/api/typescript/models/list)定义游标分页，响应含 has_more/last_id，limit 是单页大小。512 本身在合法范围内；问题在于把一页当成完整目录。

有条件反例：某官方或兼容端点返回非空 data 且 has_more=true。下一页永远没有被请求；[ProviderRepository.kt:2223](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2223)将其作为成功结果执行 replaceEntries。该函数 [1301 行附近](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1301)只额外保留未返回的自定义条目和特定语音模板，普通旧条目没有保留。这不仅是漏展示新模型，也可移除此前存在、恰好落在后续页的模型。

未查询真实账户，不能断言当前官方目录超过 512 个模型。兼容端点也不一定采用 Anthropic 分页；本项限定在确实返回分页信号的端点。方向是完成游标遍历，并将完整性与普通非空列表区别表示。

## MC03 — Gemini 翻页中断仍提交部分目录

[GeminiModelsApi.kt:55](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt:55)已经支持最多 5 页、每页 1,000 项。但是后续页 execute 异常、HTTP 失败、无 body 或解析失败时，[69、75、88 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt:69)直接 break，随后 [95 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt:95)只要已收集列表非空，就返回它。达到页数上限但仍有 nextPageToken 也没有完整性标识。这里不包含 body.string 自身抛异常的分支，后者会向调用方传播。

现役刷新 [ProviderRepository.kt:2181](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2181)不传 context，接收列表后同样走非空→replaceEntries→true。具体反例：第一页得到模型 A，仍有下一页；第二页返回 503。刷新报告成功，原来属于第二页的普通模型 B 从配置列表移除。用户看到的成功状态没有说明目录不完整。

API 的可选 context 分支还会把部分列表写入 ProviderModelsCache，但上述生产调用没有启用这个缓存，不能把其七天 TTL 直接描述成正常刷新故障的持续时间。正常路径的损失来自 Repository 对模型配置的替换。

[GeminiModelsPageTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/GeminiModelsPageTest.kt)验证单页解析和 nextPageToken 提取，没有串起后续页失败与旧目录保留。方向：不完整刷新必须给调用方明确状态；可以保留或合并已有目录，但不应伪装成完整快照进行删除。

## MC04 — Gemini 自带的能力信息没有进入模型

[parseModelsPage:114](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt:114)读取 name、displayName、supportedGenerationMethods，然后 [124 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt:124)仅构造 `LLMModel(name, displayName, "Google")`。它丢弃了响应的 inputTokenLimit、outputTokenLimit、thinking。Google 的 [Model 定义](https://ai.google.dev/api/models)明确给出这些字段的输入上限、输出上限和思考能力语义。

后续 models.dev enrichment 能补上已收录的模型，但不能保障新 ID、临时版本或目录尚未收录的 ID。构造性反例：模型 `gemini-future-lite` 支持 generateContent，官方响应给出输入上限 32,768、输出上限 2,048、thinking=true；本地/外部目录均没有该 ID 的具体预算。解析后这些字段都是 null。[LLMModel.contextWindowTokens:131](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt:131)推断为 1,000,000；当前通用 gemini- 规则仅声明 maxThinkingLevel，未提供此预算或 supportsReasoning。

[LLMProvider.kt:30](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/LLMProvider.kt:30)在缺失输出上限时默认 16,384，[ChatViewModel.dynamicMaxTokens:7388](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7388)据此计算请求预算；[1480 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1480)又要求 supportsReasoning==true 才显示思考能力。结果是预算可能超过服务限制，同时漏掉接口已经声明的能力。此反例是假定合法接口数据后的静态推导，不是声称这个虚构模型真实存在，也不是声称全部现有 Gemini 模型都错误。

方向：在发现阶段保存官方能力字段，并明确官方数据与外部目录的优先级；不能把忽略已知数据产生的空值再用型号猜测补回来。

## MC05 — 新目录没有刷新排序索引

[ModelReleaseIndex.ensureIndex:65](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ModelReleaseIndex.kt:65)首次从 models.dev registrySnapshot 建立表，此后 built=true 时直接返回。[invalidate:89](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ModelReleaseIndex.kt:89)已存在，但当前 main/test Kotlin 源码检索没有调用点。[ModelsDevApi.refreshFromNetwork:288](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ModelsDevApi.kt:288)成功替换 cachedRegistry 和时间戳时也不调用它。

因此，先从捆绑目录完成一次排序，再成功后台刷新目录，之后排序仍使用第一次的发布日期/价格表。[ProviderRepository.releaseRankOrder:1111](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1111)正是这张表的生产消费者。新收录模型可获得最新 enrichment，却仍因旧索引查不到发布日期而落到未知项排序；已有日期纠正也不能生效。这不需要并发竞态，顺序发生即可。

[ModelReleaseIndexTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/ModelReleaseIndexTest.kt)覆盖日期解析和比较，没有验证刷新前后索引一致性。方向：目录切换与派生索引失效要共享更新边界，或者索引明确绑定目录版本。本轮未执行刷新或 UI 验证。

## MC06 — 同一模型的单个查询与批量刷新采用不同数据

[ModelsDevApi.enrichModel:104](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ModelsDevApi.kt:104)在 provider 映射未命中时，遍历 registry，取第一个同 ID 条目。[enrichModels:133](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ModelsDevApi.kt:133)则按 provider key 排序，优先选含 reasoningEffortValues 的条目。[applyDevData:145](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ModelsDevApi.kt:145)会用所选记录覆盖原有非空 contextWindow/maxOutputTokens 等数据，并非只补空值。

这已经有实际不同入口：刷新 API 使用批量 enrichment；[ModelUseManager.entryDict:62](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ModelUseManager.kt:62)在 model_use 列表读取时再次调用单个 enrichment，[88 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ModelUseManager.kt:88)输出再次计算的 context_window。

条件是 provider 映射未命中且同 ID 在多个目录记录中具有不同能力。简化反例：registry 遍历顺序 B、A，两者都有 effort 值，B 的上下文为 2M，A 为 1M。批量选择排序后的 A，单个查询选择 B；同一条目会报告两种上下文预算。捆绑 [models-dev-api.json](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/models-dev-api.json:1)选择性 JSON 核对也发现 glm-5.2 的记录确实不同：neuralwatt 为 context/output 1,048,560，aihubmix 为 context 1,000,000、output 128,000。这里只说明重复记录存在实际差异，不把原始 JSON 顺序当作 Android JSONObject 运行时遍历顺序，也不声称所有设备固定选到 neuralwatt。

方向：单模型和批量调用共用同一选择规则；不能只在其中一条路径保证稳定。本项有明确上游对照。

## 固定 openminis 对照

上游固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，目录 `/tmp/openminis-review-20261004`。只读已有源码对象，不运行、更新或补拉上游。对照仅算专题证据，不算本项目全文覆盖。

- 上游 AnthropicModelsApi 的 x-api-key 和整段 headers 日志相同，首个成功结果也立即返回，URL 同样 limit=512。MC01/MC02 为已核对的共同缺口。
- 上游 GeminiModelsApi 仍是一次请求，没有当前 MAX_PAGES 翻页循环；它也只用名称/生成方法构造 LLMModel。当前分页是改进，MC03 是当前部分结果提交的问题，不能写成上游具有同一翻页实现；MC04 的字段丢失两边共有。
- 上游 ModelReleaseIndex 同样有 built 缓存与 invalidate，已读刷新赋值片段也没有调用失效方法。本轮不以不完整的上游全库搜索证明“任何地方都无调用”；MC05 的生产调用缺口以本项目源码为准。
- 上游 ModelsDevApi 的 enrichModel/enrichModels 都调用 resolveDevEntry，其精确命中 fallback 共用按 key 排序、优先 effort 元数据的选择过程。当前单个入口未带入这段统一实现，MC06 有可直接核对的移植差异。上游还包含别名和聚合策略，不据此要求全部照搬。

## 排除项、验证边界与验收方向

不重复记录注释噪音、OkHttp 重复、SharedPreferences/helper 复制和 lint 基线。没有把 Antigravity 模型文件存在视为生产入口已开放；没有把 XAI 明确的静态目录当作分页失败。ModelRules 的严格解析、远端 HTTPS/大小/时限约束及原子缓存写入已经核对，不因规则文件可更新就推断为执行代码。发现无效单条规则被丢弃是当前显式策略，不另报整份配置拒绝问题。

本轮没有编写或运行测试。建议后续经授权的端到端验收保留以下可重复工件：

| 链路 | 最小可复核证据 |
|---|---|
| 官方 Anthropic 模型刷新 | 使用专用假密钥标记，记录请求头和日志；日志不得出现标记 |
| Anthropic 多页 / Gemini 后续页失败 | 响应页、游标、刷新状态、前后目录及已选条目；完整读取成功，不完整结果不删除旧条目 |
| Gemini 目录未收录新模型 | 接口能力响应、持久化模型、聊天最终请求预算和能力 UI |
| models.dev 更新后排序 | 两个目录版本摘要、更新前后 rank 与排序；无需进程重启即可使用新日期 |
| 重复模型元数据选择 | 同一目录下批量刷新与单个 model_use 输出；预算和能力来源一致 |

## 全文覆盖登记

以下文件逐行完整阅读；长文件分段读取，片段搜索和选择性 JSON 检查不计入全文覆盖。

| 文件（均在 Android 源目录） | 行数 |
|---|---:|
| main/java/com/openminis/app/provider/ModelsDevApi.kt | 507 |
| main/java/com/openminis/app/provider/ModelReleaseIndex.kt | 183 |
| main/java/com/openminis/app/provider/anthropic/AnthropicModelsApi.kt | 223 |
| main/java/com/openminis/app/provider/anthropic/AnthropicModelsCache.kt | 57 |
| main/java/com/openminis/app/provider/antigravity/AntigravityModelsApi.kt | 124 |
| main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt | 136 |
| main/java/com/openminis/app/provider/openrouter/OpenRouterModelsApi.kt | 123 |
| main/java/com/openminis/app/provider/rules/ModelRules.kt | 262 |
| main/java/com/openminis/app/provider/rules/ModelRulesProvider.kt | 191 |
| main/java/com/openminis/app/provider/xai/XAIModelsApi.kt | 29 |
| main/java/com/openminis/app/offload/ModelUseManager.kt | 91 |
| test/java/com/openminis/app/provider/GeminiModelsPageTest.kt | 49 |
| test/java/com/openminis/app/provider/ModelReleaseIndexTest.kt | 138 |
| test/java/com/openminis/app/provider/ModelRulesParserTest.kt | 119 |
| test/java/com/openminis/app/provider/ModelRulesTestFixtures.kt | 36 |
| **新增合计** | **2,268** |

另全文复核已登记的 LLMModel.kt（240 行）和 ProviderModelsCache.kt（64 行），不重复累加。Repository 和 ChatViewModel 的调用片段、捆绑 JSON 的选择性字段检查、上游及官方文档均不计为新增全文文件。

覆盖台账：[2026-10-05-code-review-coverage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。全库 tracked 1,670 个，纳入范围 1,546 个：全文 595、部分 15、尚无全文登记 936；范围排除 124。全文累计 121,694 行。文件内容发生变化时需按 SHA-256 重新核对，当前记录不代表修复或功能验证通过。
