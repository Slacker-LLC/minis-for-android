# 模态与token汇总：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读源码和已有测试，未运行测试或模型请求；仅新增报告及台账。没有新增已确认缺陷。

## 模态推断边界

VoiceModality 仅在输入、输出模态均为 null 时按模型ID及显示名猜专用语音能力；ASR匹配优先于TTS，返回完整audio→text或text→audio配对。任何一侧已经显式提供则不补另一侧，避免覆盖显式信息，但不代表不完整API数据必然补齐。

语音种子形状判断要求归一化后恰好单audio且另一侧null。源码注释中“API模型不会该形状”是设计假设，不是对全部Provider真实返回的证据；后续清理调用需结合来源判断。tts子串和displayName启发式可能误判命名，但没有真实catalog反例，本轮不新立错误清理缺陷。

hasAudioInput/Output及hasImageInput/Output都基于归一化列表；未知输出不会被当成图像输出。这里没有自动请求或下载功能，也不能用模态谓词证明实际协议支持。

## 汇总口径

SessionTokenStatsCalc 累加input/output/cache读取与创建字段，忽略无法解析的行，使用最后正数 latestContextTokens。loops取工具类block总数与assistant消息数的较大值；排除text/info，但thinking等其他kind会计入，因此是近似展示值，不能当作真实Provider请求次数或重试次数。此前测试报告已记录此口径，本轮不重复新编号。

JSON逐字段累加在一个try中，没有负数、溢出或时间排序校验；正常usage来源是否保证这些条件需按持久化调用追踪，不仅凭接受异常数字认定真实统计已错。

## 图像槽测试范围

ImageSlotTest 验证图像输出能力和picker过滤、image槽entries/withEntries、旧序列化数据缺省空槽、ModelSlots JSON往返。所谓backup format测试只是序列化往返，没有调用BackupExporter/Importer、凭据加密、数据库或文件恢复，不能作为完整备份端到端证据。未运行这些测试。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/data/model/VoiceModality.kt | 126 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/SessionTokenStatsCalc.kt | 35 |
| src/android/app/src/test/java/com/openminis/app/data/ImageSlotTest.kt | 53 |

三个现版文件完整读取。新增全文3文件、214行；已全文重读0文件。其它rg定位不计全文。

累计全文1376 / 1758文件，209873行；partial 4，未登记378。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-modality-stats.json)。

校验旧230报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
