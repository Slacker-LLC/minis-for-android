# 语音模板、适配器路由与音频帧静态审查

日期：2026-10-06。确认 2 项新的 P2 问题。本轮只新建独立报告、更新阅读台账，不修改产品代码和旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读取现有 Git 对象，没有 fetch 或执行上游。没有构建、执行测试、模拟器、adb、Root、设备、云任务、账号、凭据或子 Agent 操作。下列结果按源码静态推演，未发任何语音请求。

## VA01 · P2 · URL 子串误判厂商，并把请求改送固定域名

[模板匹配](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/VoiceProviderTemplate.kt:42)对整个 lowercase URL 做 contains；[语音工厂](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderFactory.kt:61)也只检查 openspeech.bytedance 或 volcano 子串。它没有解析 host，也不区分厂商域名与路径中的普通文字。

例如一个允许使用的 HTTPS OpenAI 兼容网关地址 https://voice-gateway.example/volcano-proxy，本应以配置地址为目标，但会被选成 DoubaoVoiceProvider；模板还会在添加/启动迁移时为它补豆包语音模型。工厂 [63 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderFactory.kt:63)没有把 customBaseURL 传入 adapter。[豆包构造器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:172)固定 base 为 https://openspeech.bytedance.com，并把这个 instance 的 key 写入 [X-Api-Key](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:176)。ASR 音频和 TTS 文本随请求一起发往这个实际目标。

因此这不只是错误模型清单或 404：用户配置的网关地址被替换，所用的实例凭据和语音内容发往另外一个服务。此反例不需要恶意输入、关闭权限或通过 HTTP 绕过限制，也不声称未经用户选择语音功能就会自动发送。Factory 的 transport gate 仍存在，但对实例原地址的校验不能证明适配器最终保持同一目标。

[真实 ASR 调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:249)和 [朗读调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:456)都使用这个 factory。其它厂商分支也有类似字符串匹配；本项只用已完整核对的豆包路径作为确定反例，不把其它所有 adapter 一概说成硬编码目标。

固定上游 [VoiceProviderFactory.kt:61](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderFactory.kt#L61)也保留相同 contains + DoubaoVoiceProvider(instance.id, apiKey) 分支。属于共享缺口，非 Direct Ubuntu 迁移新增。

修复方向记录：厂商识别需依据明确的 URL/host 或显式 adapter 配置，并明确支持代理地址的目标语义；最终请求目的地应遵守配置及当前传输策略。不在本轮修改或使用真实凭据验证。

## VA02 · P2 · TTS 中途错误帧被跳过，已有半段音频被当作成功

[豆包 synthesize](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:217)先获得整个响应，再逐行解析 JSON。[222 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:222)遇到非零 code 直接 continue；无法解析的 JSON、无 data 或坏 base64 也被跳过。最终只在 [audio.isEmpty](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:228)时抛错，有任何音频片段就返回成功，没有携带截断或失败状态。

静态反例：HTTP 成功响应先返回一帧 code=0/data=有效音频，随后返回 code 非零的错误帧。代码拼入第一帧，忽略错误帧，返回 prefix 音频。[ReadAloudPlayer](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:483)只检查 data 是否为空，随后 playBytes 并 return true。因此即便所请求文本的后半段未合成，当前接口也没有把本次合成判为失败。若 prefix 本身可播放，用户会听到被截断的朗读；音频容器不完整时可能改为播放失败，本轮没有验证具体格式。

全部帧错误时仍会抛 Parse，本项不称错误处理完全缺失；根因是“有前缀”被当作完整成功，吞掉发生在前缀之后的明确错误。旧 MP07 的停止后仍播放来自取消链，与这里的响应完整性不同，不重复计算。

固定上游 [VoiceProviderVendors.kt:297](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt#L297)相同跳过错误并仅检查空音频。本项不依赖当前厂商所有 code 的外部协议细节：实现自身已经将 code!=0 视为非成功帧，却没有将其传播为本次合成失败。

修复方向记录：区分无音频的正常结束帧与明确错误；错误发生后不能静默返回成功 prefix。完整性状态应进入现有合成/播放错误链，不通过自动重放整句制造重复朗读。

## 补读范围与未确认事项

完整阅读 voice template 的种子形状、VoiceModality 的推断和虚拟 SystemVoiceEntries；factory 会调用传输校验，基础 HTTP client 是 lazy，因此不能把 supports() 当作已发出网络请求。基础 VoiceProvider 的 ASR/TTS 构造、响应解析、URL 版本去重及 chat-ASR 也已通读。

ModelContextWindowHeuristic 只用于 slider 上限，不能单凭它的推断值称 Agent 请求使用相同窗口。ModelAttributionSnapshot、AgentTypes 和工具 API name 测试源码补读后登记全文；未执行测试。sanitizeToolId 的完整跨 Provider 输入约束仍需追踪，不在本轮仅凭 helper 判定整个工具 ID 通路正确。

没有重新计数旧模型目录分页、语音停止/取消、系统 TTS 资源释放、工具归属、OkHttp 复制或 Lint 基线。未核验真实厂商可用模型清单、报价或账户状态，也不引用网络现状猜测 API 是否能用。

## 覆盖与核验

本轮新增全文 9 个文件、1101 行：

- [src/android/app/src/main/java/com/openminis/app/data/model/AgentTypes.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/AgentTypes.kt)：81 行。
- [src/android/app/src/main/java/com/openminis/app/data/model/ModelAttributionSnapshot.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ModelAttributionSnapshot.kt)：19 行。
- [src/android/app/src/main/java/com/openminis/app/data/model/ModelContextWindowHeuristic.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ModelContextWindowHeuristic.kt)：47 行。
- [src/android/app/src/main/java/com/openminis/app/data/model/SystemVoiceEntries.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SystemVoiceEntries.kt)：77 行。
- [src/android/app/src/main/java/com/openminis/app/data/model/VoiceModality.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/VoiceModality.kt)：122 行。
- [src/android/app/src/main/java/com/openminis/app/data/model/VoiceProviderTemplate.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/VoiceProviderTemplate.kt)：240 行。
- [src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProvider.kt)：303 行。
- [src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderFactory.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderFactory.kt)：134 行。
- [src/android/app/src/test/java/com/openminis/app/data/model/AgentToolDefinitionApiNameTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/model/AgentToolDefinitionApiNameTest.kt)：78 行。

VoiceProviderVendors 只读 171—271 行，登记 partial，未计入全文。这个片段的合并输出曾截断，已单独补读。交叉复核已有全文 ProviderRepository 915—953/2041—2070、ReadAloudPlayer 451—489、ProviderSpeechRecognitionEngine 241—272。固定上游读取 factory 36—66 和豆包合成 289—309 行。

累计全文 939 / 1546 个文件、190565 行；partial 13，not_registered 594，排除项 124。全库通读尚未完成。

重新核对 1670 个跟踪路径、SHA256、字节数、正整数文本行数；61 份旧报告摘要未变；HEAD 固定，产品 tracked/staged diff 为空。新报告引用文件和行号、台账批次与累计计数核验。以上不代表测试通过、网络请求成功或设备复现。
