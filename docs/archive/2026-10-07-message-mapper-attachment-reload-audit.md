# ChatMessageMapper全文与附件重载问题复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文2文件、555行；没有另计新缺陷，复核CT03仍在。仅静态阅读，没有修改代码、编写/运行测试、请求模型、生成附件或访问真实会话/数据库、构建/模拟器/adb/Root/设备/云任务。旧141份报告和所有旧台账保留，仅新建本文与累计台账。

## CT03仍存在：非图片附件独占消息重载后被UI过滤

[ChatMessageMapper.kt:195](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatMessageMapper.kt#L195) mediaRef恢复中，user非image MIME的引用加入restoredAttachmentUris，名字加入restoredAttachmentNames；text显示先stripAttachedFilesXml隐藏清单。第240行过滤USER仅检查text.isBlank和restoredImageUris.isEmpty，不检查已恢复的restoredAttachmentUris。

因此合法且仍在磁盘的PDF/TXT附件独占用户消息：文件引用恢复成功、XML被隐藏、正文空、图片列表空，随后mapNotNull返回null，整个用户行不出现在列表。不是附件文件被删，也不是模型历史必然丢失；mapToLlm保留XML Text并跳过非图片inline bytes，两个投影职责不同。

[ChatViewModel.kt:4031](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L4031) 当前loadSession先SessionHistoryLoader.load再调用messageMapper.toChatMessages；第4047行单独toLLMMessage。因此这是现役加载入口，新模块提取没有把旧过滤规则修正。使用独立UI和LLM映射本身合理，缺口在可见内容判定。

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的ChatViewModel第17206行同样只看图片列表。与旧报告CT03为同一问题，保持编号，不因文件搬迁再算一项。

## 新测试覆盖和未覆盖

ChatMessageMapperTest全文136行调用生产mapper，覆盖纯文本user/assistant、reminder/attachment XML展示隐藏、toolResult-only隐藏、成功/失败/取消工具回执合并、连续assistant折叠与sourceDbIds、reasoning、坏JSON短UI占位、持久error以及LLM XML内容块保留。

测试mediaBaseDir固定/tmp/none，没有真实mediaRef文件、图片或非图片附件的可见行断言，因此不能证明CT03已修复。XML-only测试与附件恢复为不同输入形状；去掉XML显示的断言合理，不能用它代替纯附件用户行存在性验证。测试使用真实mapper而非第二套镜像逻辑，没有运行它。

## 全文核对的其它路径与边界

UI先扫描user toolResults，以toolUseId关联assistant blocks；可见输出保留末80行，状态含success/failed/cancelled。没有回执时默认SUCCESS属于需继续结合checkpoint恢复提示和产品状态语义复核的候选，本轮未计为工具实际执行成功伪报。

连续assistant折叠保留最后id、所有sourceDbIds、去重block.id最后状态与最大updatedAtMs，terminal error由后行优先。数据库仍保留每行，不能以UI合并推断DB原历史删改。删除/编辑是否正确使用sourceDbIds在之前报告有独立入口，本轮不重复扩张。

UI坏JSON展示短占位；LLM映射异常时仍将原partsJson放入Text。结构化元素中途坏形状可能留已有parts再加原JSON、恢复图片file.readBytes有体积候选、mediaRef relativePath需存储生产者/边界约束，这些需要继续闭合调用与已有报告去重，不凭一段catch直接新增泄密/OOM/越界结论。未读取真实媒体或制造异常记录。

mapToLlm对包含附件XML的Text保留contentPart但不累计到content；纯caption另积入content。这进一步印证上一轮OHS01所述双表示实际存在，但不再次编号摘要丢失。工具输入非法JSON降为JSONObject()，签名恢复、音频引用非inline等行为必须对照真实存储合同，未凭与UI不同就断言数据损坏。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| [ChatMessageMapper.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatMessageMapper.kt#L1) | 419 |
| [ChatMessageMapperTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/chat/ChatMessageMapperTest.kt#L1) | 136 |

mapper1—220、221—419完整读取；测试完整cat，无截断。一次旧报告混合搜索输出截断，随后CT03专门重读，搜索不算全文证据。ChatViewModel加载片段、上游过滤行只用于闭环，不升级其它文件。

新的累计台账：[2026-10-07-code-review-coverage-37158d18-message-mapper.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-message-mapper.json)。全文 **1151 / 1652文件、186456行**；partial4、not_registered497、excluded124，tracked1776，全库目标继续。

交付核验旧141份报告及所有旧台账hash、local旧HEAD/空tracked和staged diff、固定origin/main、1776快照文件逐Git blob/hash/bytes/lines、新全文555行与本文固定证据行号。没有覆盖上一轮新版台账。
