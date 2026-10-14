# Codex与xAI目录发现、图像路由和优先级范围审查

日期：2026-10-07。固定远端 `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文4文件、573行，无新增确认产品问题。仅静态阅读，没有改代码、创建/运行测试、构建、模拟器、adb、Root、设备、云任务、模型网络请求或真实凭据操作。此前154份报告及旧台账保持原样。

## Codex目录：鉴权拒绝与普通失败区分

CodexLiveCatalogTest110行完整阅读，验证backend priority排序、display name、effort小写与image模态、空effort保持unknown、隐藏/无slug/重复slug过滤、坏body为空、live替换bundled文本而保留独立图像路由。测试调用解析/合并函数，不是登录、网络目录刷新到Room再到picker的全过程。

CodexImageAndDiscoveryTest153行完整阅读，目录请求用内存interceptor捕获真实builder，检查client_version/Version/Openai-Beta/Originator/Bearer/account header一致；401/403应抛InvalidApiKey，500或不可读/空列表为空。实际[OpenAIModelsApi.kt:93](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIModelsApi.kt#L93) 401/403抛LLMError，catch LLMError重新抛；第50行fetchCodexCatalog不吞该错误，普通空结果由merge保留bundled。因此不能以“有fallback”推断鉴权拒绝也被伪装为成功。

现役ProviderRepository的Codex OAuth分支第2153行在一般fetch try外调用fetchCodexCatalog，账号拒绝会向调用方传播。manual bearer走静态目录是明确的凭据类型区分，不声称任意bearer可调用账号目录。这里没有检查真实账号权益或最新线上模型表。

解析器seen.add在visibility检查前执行：若同slug先隐藏后可见，后者也被去重；这是重复不一致数据条件的候选，缺少实际backend合同/数据证据，不另编号。live经能力规则及models.dev enrichment，最终字段优先级与旧MC06关联，不凭纯parse测试证明远端所有能力均权威保留。

## OAuth图像路由的测试边界

CodexImageAndDiscoveryTest通过反射替换client，预置image_generation_call完成事件，并比较返回字节。它覆盖2.5变体把model放进hosted tool对象、老gpt-image-2不显式tool model、固定承载模型与独立image-only路由目录。请求不会走实际登录服务，本轮也未运行。

fixture只是PNG头加少量字节，证明base64及mediaAttachments字节传递，不证明合法图像解码/保存、UI展示、取消清理、输出大小限制或真实服务可用。测试注释里的上游版本和历史行为是背景，不能替代本轮运行证据；不把仓库中的模型名称当作最新公开产品信息。

## xAI动态目录：已走live请求，fallback是明确策略

XAIDynamicCatalogTest149行全文，用真实OpenAIModelsApi+MockWebServer调用与Repository同形的fetch.ifEmpty fallback，检查未捆绑的新ID、给定base的/v1/models、401和坏JSON返回xAI目录、捆绑ID去重。实际[ProviderRepository.kt:2206](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt#L2206) xAI分支使用显式api.x.ai/v1默认base，避免null落到OpenAI；返回空时用xAI静态模型。不是无条件只显示捆绑列表的旧入口。

但测试不是Repository实例，也没有检验目录落盘、旧model entry/slot、刷新状态UI或OAuth续期。401 fallback在本套测试中被明确接受，与Codex的拒绝登录策略不同；没有自动把这项产品选择报成新Bug。fallback可能替换旧live目录仍是此前完整性家族需按产品策略核验的问题，本轮不重复编号。代码注释声称曾手动探测线上401不是本轮探测，未调用api.x.ai。

## Priority：开关与供应商资格同时生效

XAIPriorityProcessingTest161行全文，在body层分别验证eligible+FastMode才注入priority，关闭开关或不合资格不发；同一个provider在开关变化后下一请求读取新状态。[OpenAIProvider.kt:123](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L123) resolvedServiceTier满足同一条件，Chat第1987行/Responses第3059行都使用它；[ProviderFactory.kt:140](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/ProviderFactory.kt#L140) xAI OAuth与API-key两个构造分支均设置supportsPriorityProcessing=true。

测试直接赋eligible，不验证工厂资格判定、设置持久化、模型/代理是否接受service_tier或服务端实际选择何种计费档位。没有查询价格或账号账单，也不宣称priority一定被服务端执行。FastMode是全局策略并非每实例设置，不能仅因共享开关自动报配置串扰。测试tearDown恢复缓存false，未把测试状态写入用户真实偏好。

## 固定上游与覆盖边界

固定OpenMinis HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，只读已存在对象，没有fetch。其OpenAIModelsApi搜索未找到本轮同名fetchModelsCodexOAuth/fetchCodexCatalog/mergeCodexCatalog方法；此结论限定该文件，不能扩大成上游所有语言/模块均无动态目录能力。本地新增功能以固定远端源码为准，不替换Runtime架构。

OpenAIModelsApi267行已有全文记录，本轮按1—124与125—267完整复核，不重复计数。Repository/ProviderFactory/OpenAIProvider只看片段；四个新测试均完整输出无截断，未执行。无新确认问题不影响通读进度。

| 新增全文文件 | 行数 |
|---|---:|
| [CodexImageAndDiscoveryTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/CodexImageAndDiscoveryTest.kt#L1) | 153 |
| [CodexLiveCatalogTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/CodexLiveCatalogTest.kt#L1) | 110 |
| [XAIDynamicCatalogTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/XAIDynamicCatalogTest.kt#L1) | 149 |
| [XAIPriorityProcessingTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/XAIPriorityProcessingTest.kt#L1) | 161 |

新台账：[2026-10-07-code-review-coverage-37158d18-codex-xai-catalog.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-codex-xai-catalog.json)。累计全文 **1223 / 1652文件、198249行**；partial4、not_registered425、excluded124，tracked1776。全库通读未完成。

交付核验旧154份报告和全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照Git blob/hash/bytes/lines与证据行号。仅新建本报告和新台账。
