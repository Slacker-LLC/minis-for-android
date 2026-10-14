# 模型错误呈现与等待消息数据模型全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。旧204份报告和所有旧台账保留。仅静态阅读；未改产品代码、编写/运行测试、构建、使用模拟器/设备/Root/云资源/凭据或请求Provider。

## 错误展示和运行决策是两个边界

LLMErrorPresenter只接LLMError，读取开头三位状态码、HTTP/error字样或Anthropic方括号类型。ProviderError按状态分类认证、限流、请求无效、未找到、过大、拒绝与服务端错误；明确typed Network/Decoding/RateLimited维持原语义。summary输出本地资源文字，present补最多240字符原详情，普通Throwable/OTHER回落原message。

此对象负责显示，不改变LLMError.isRetryable/isFallbackable。LLMError定义重试仅Network/Transient，fallback为RateLimited/InvalidApiKey/ProviderError。不能由显示413就推断重试、压缩或fallback策略已经改变；实际AgentLoop决策需另外核对。

classify(TransientError)始终SERVER，详情即使带429也不重新改kind；ProviderError的状态regex可从任意文本HTTP/error片段提取。异常类型与Provider真实HTTP封装的一致性需要各Provider当前源码证明，本轮不凭人工任意字符串宣布实际错误分类必然误导。OTHER不受240字符限制，但这是明确回落，是否需要统一限制仍为候选。

setInlineError(Throwable)真实调用present；compaction的summary使用已检索到，但大型compaction文件未全文登记。没有读用户真实错误日志，不宣称已复现任何API失败。

## 等待/转向的数据契约已区分

ChatModels明确PendingDelivery.STEER/QUEUE，QueuedPrompt默认STEER，ChatMessage有queuedDelivery快照。dueAtStepBoundary只过滤STEER并维持原list顺序。PendingDeliveryTest三例调用真实helper：混合队列顺序、仅QUEUE时空、copy切换及默认值。

ChatViewModelSend30—105已核对enqueue、切换、撤回与边界取队列：enqueue在streaming时捕获附件、添加queue与UI占位，切换更新两种状态，注入只取dueAtStepBoundary，QUEUE留给drain。没有把“仅一个过滤helper有测试”夸大为完整持久化、取消、重启后队列都正确。Send仍是partial，本轮未读完整drain和退出分支，全库目标继续推进。

queued prompt id由毫秒时间加随机数，队列/UI更新为读改写；实际线程入口、重复事件和运行回合交错还需完整调用链证据，不以随机碰撞理论或缺锁直接新增故障。内部bridge用两份历史固定文字匹配，role=assistant及trim后完全相等才隐藏；旧注释/任务标签不属于本轮问题范围。

## 测试的实际覆盖

LLMErrorPresenterTest四例真实调用statusOf/classify，覆盖常见Provider格式与typed异常、非LLM异常；没有Context资源、present截断、真实网络失败或Provider映射测试。ChatModels全部300行读完，但只是数据层；模型、工具状态、附件名称/URI及streamingById真正拥有者仍需各消费者。LLMError全文件重读，如已全文不重复累计。所有测试仅阅读，未运行。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/LLMErrorPresenter.kt | 100 |
| src/android/app/src/main/java/com/openminis/app/data/model/LLMError.kt | 34 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModels.kt | 300 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/LLMErrorPresenterTest.kt | 45 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/PendingDeliveryTest.kt | 36 |

五文件完整输出，无截断。新增全文4文件、481行；已全文重读1文件。Send仅片段，rg定位不计全文。

累计全文1311 / 1758文件，196278行；partial 7，未登记440。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-error-queue.json)。

校验旧204报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
