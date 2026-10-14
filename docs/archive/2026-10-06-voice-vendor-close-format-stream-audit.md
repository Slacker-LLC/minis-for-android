# 语音厂商关闭、格式与流完整性静态审查

日期：2026-10-06。新增 3 项 P2 问题。只新建独立报告、更新阅读台账，不修改产品代码或旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读取现有 Git 对象，没有 fetch 或执行上游。没有构建、执行测试、模拟器、adb、Root、设备、云任务、账号、凭据或子 Agent 操作。以下是静态可达行为，不是实际厂商请求或设备复现结果。

## VV01 · P2 · 讯飞 TTS 缺关闭终态，最后一帧前关闭可留下永久等待

[讯飞合成](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:373)通过 suspendCancellableCoroutine 等待 listener 完成。listener 只覆盖 onOpen、onMessage、onFailure；成功依赖 data.status==2，错误依赖明确错误帧或 onFailure。没有 onClosing/onClosed 的 continuation 收尾，也没有整个合成的 deadline。

静态触发：握手成功，服务端在发出 status=2 前正常关闭，或关闭前的最后消息没有可解析 data。关闭回调不在这个 listener 中处理，cont 不会因“已关闭但未完成”自动 resume。合成保持挂起，直到外部明确取消。客户端的读超时不等于该已结束网络连接上 continuation 的业务终态，不能依赖一次 onFailure 来覆盖所有正常关闭路径。

[朗读调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:469)直接等待 synthesize，没有 withTimeout；isSynthesizing 的 finally 也只有调用返回或抛出/取消后才执行。于是可能持续显示加载并占住顺序合成队列。本项不把它说成进程无法取消，也不重复旧 MP07 的 stop 后返回音频仍播放。

[已有取消处理](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:420)会取消 socket，这是合理路径，但取消处理不能代替服务端正常关闭的失败终态。

固定上游 [VoiceProviderVendors.kt:448](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt#L448)也只覆盖相同三种回调。共享缺口。

修复方向记录：在既有 continuation 边界处理成功、错误、关闭、取消的互斥终态，并给整个合成明确 deadline；未收到完整终态的正常关闭应失败，不能默认返回半段音频。

## VV02 · P2 · 讯飞 ASR 把完整 WAV 容器标为 raw PCM

[录音结束后的候选链](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:238)先将 PCM 包装为 WAV，然后 [256 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:256)把这份 wav 传给 VoiceInputRequest。请求类型 [audioData 说明](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceModels.kt:14)也明确是 16 kHz mono WAV。VoiceProvider.wrapPcm16InWav 会添加 44 字节 RIFF/WAVE 头，之后才是原 PCM。

但 [讯飞构造](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:312)声明 encoding=raw、16 kHz、mono、16 bit，并直接对整个 request.audioData base64，没有取出 WAV 的 data chunk。这使编码声明和实际 payload 不一致：raw PCM 输入含 RIFF、WAVE 等容器头字节。

这一差异可在本地构造出的请求字节上确定，不需要真实 key。服务端是否直接拒绝、把头部当短噪声或进行兼容容器嗅探，本轮没有联网验证，因此不声称所有讯飞请求一定失败。作为适配器，它没有履行自身声明的 raw 格式转换，识别可靠性依赖未声明的容错。

固定上游 [VoiceProviderVendors.kt:387](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt#L387)同样 encoding=raw 并原样转发 request.audioData；这里只确认共享构造缺口，不据此证明上游录音调用链同样传 WAV。

修复方向记录：让当前请求边界明确区分容器音频与原始 PCM；按照适配器的实际协议构造对应 payload。若解析 WAV，必须读取真实 data chunk 及格式，而不是对任意输入都机械去掉 44 字节。

## VV03 · P2 · OpenRouter 流错误或提前 EOF 后仍返回已有音频成功

[OpenRouter SSE 循环](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:980)把 EOF 和 [DONE] 都作为普通 break。JSON 仅查看 choices，顶层 error 没有传播；坏 JSON/base64 被跳过。循环退出后，只要 pcmBytes 非空，就 [包装 WAV 返回](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt:1014)，没有验证正常完成，也不保留 partial 状态。

静态反例：HTTP 200 SSE 先发一帧 audio.data，随后发顶层 error 并结束连接。第一帧进入缓冲，error 因无 choices 被跳过，EOF break；返回带正确 WAV 头的前缀音频，朗读调用方据非空结果判成功。纯 PCM 前缀包装成独立 WAV 后可播放，不会天然靠容器损坏来暴露文本尚未合成完。

HTTP 非成功状态已有 Auth/Http 错误处理，完全没有音频也会抛 Parse；本项针对“HTTP 成功后流内失败/提前结束且已有前缀”的具体路径。它与旧 VA02 的豆包 JSON 帧错误同属完整性问题，但这里是另一现役 SSE parser，检查 EOF、顶层 error 与最终完成状态的缺口此前未记录。

[现有 OpenRouter 测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/voice/OpenRouterVoiceRoutingTest.kt:73)只验证 WAV wrapper 和路由 predicate，没有经过 synthesize 的 SSE 读取与终态决策。本轮未运行测试。

固定上游 [VoiceProviderVendors.kt:1115](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt#L1115)相同处理 EOF、[DONE] 与 choices，非空 PCM 返回成功。共享缺口。

修复方向记录：流内错误与完整结束需要进入现有合成结果边界；缺失正常终态时应保留明确失败/不完整状态，不能把已有 prefix 作为整句完成。避免自动重播整个文本造成重复播放。

## 已核对但不扩成问题的事项

完整补读 MiniMax、讯飞、Gemini、ElevenLabs、Deepgram、Azure、MiMo、OpenRouter。MiniMax 处理 base_resp 并兼容 hex/base64；MiMo 区分 API model 与 voice id，现有两条测试覆盖这一请求构造。没有因未调用真实 API 就猜测厂商端点、模型或可用性现状。

讯飞签名、语种编码、其他厂商 voice/model 约定还有外部协议核对价值；本轮不将未验证的服务端协议推断列成新问题。基础同步 HTTP 取消属于已有报告方向，不重复计数。旧 VA01 固定目标、VA02 豆包错误帧也没有重复新增。

## 覆盖与核验

本轮新增全文 4 个文件、1242 行，其中 VoiceProviderVendors 从 partial 补为 full：

- [src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceModels.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceModels.kt)：87 行。
- [src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProviderVendors.kt)：1016 行。
- [src/android/app/src/test/java/com/openminis/app/provider/voice/MimoVoiceRoutingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/voice/MimoVoiceRoutingTest.kt)：46 行。
- [src/android/app/src/test/java/com/openminis/app/provider/voice/OpenRouterVoiceRoutingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/voice/OpenRouterVoiceRoutingTest.kt)：93 行。

VoiceProviderVendors 本轮读 1—170、272—455、456—655、656—825、826—1016 行，加上同一 SHA 下前一轮已完整读取的 171—271 行，覆盖全部 1016 行。其余三份源码本轮完整读取。交叉复核已全文记录的 ProviderSpeechRecognitionEngine 226—262/429—454、ReadAloudPlayer 合成等待及取消边界、VoiceProvider 的 WAV wrapper。

累计全文 943 / 1546 个文件、191807 行；partial 12，not_registered 591，排除项 124。全仓通读尚未完成。

重新核对所有 1670 个跟踪路径、SHA256、字节数、正整数文本行数；62 份旧报告摘要未变；HEAD 固定，产品 tracked/staged diff 为空。新报告引用文件和行号、台账批次与总数核验。未执行测试，未获取厂商网络或设备证据。
