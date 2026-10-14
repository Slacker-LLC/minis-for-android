# 快捷测试凭据、TTS音色参数与模型选择组件静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。全文新增5文件、1419行，确认2项问题。只做静态审查并查询公开官方协议文档，没有修改产品源码，没有运行构建、测试、模拟器、adb、Root、设备、登录、云模型或语音请求，未读取凭据。旧报告不改。

## QT01 / P2：免密兼容端点在快捷测试入口被错误拦截

[QuickTestSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/QuickTestSheet.kt:454) 找到instance后，第456—457行直接loadApiKey，null即返回“No API key configured for this provider.”，尚未创建Provider或发请求。真实调用入口包括 [ModelEntryDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt:406)、ShadowVoiceDetailScreen第120行和UnifiedModelPickerSheet第592行，不是无调用的测试helper。

[ProviderConfig.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:284) 明确允许API-key模式、customBaseURL非空、OpenAI/Anthropic兼容类型使用空key。[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2395) usableApiKey为允许免密的instance把未保存key转换为合法空字符串；QuickTest没有复用它。

静态反例：配置无认证的本地OpenAI兼容端点，不保存dummy key，选择其有效文本模型并打开QuickTestSheet。performTest在第457行直接失败，端点是否实际可用完全没有被测试，和管理页的configured规则不一致。只限定“未存key为null”，已经显式存空串时loadApiKey可能返回空串，不夸大为全部空key必失败。正式有密端点缺key仍应拒绝；OAuth不是本项免密范围。

这是此前CF10模型列表刷新缺口的另一个实际入口：同属未复用凭据规则，但本项具体影响快捷测试，不重复新增一项刷新问题，也不把朗读的缺key检查再拆第三个编号。方向是复用已有usableApiKey，而不是删除所有认证检查。

固定OpenMinis只读对照： `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的QuickTestSheet第462行已调用usableApiKey(instance)，第450—461行说明免密端点的旧短路。当前本地仍保留loadApiKey。

## QT02 / P2：把普通TTS模型ID当音色ID，产生无效语音请求

[QuickTestSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/QuickTestSheet.kt:475) 构造VoiceOutputRequest，model和voice都传entry.model.id。代码注释把“厂商音色模板的id”推广到所有语音输出模型。

实际适配链：VoiceProviderFactory第89—90行对普通OpenAI instance返回基础VoiceProvider；[VoiceProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/voice/VoiceProvider.kt:151) 构造JSON时分别照收request.model和request.voice，第155行仅在voice为null时使用默认alloy。没有将voice等于model的普通模型值归一化。

可达配置：ModelEntryDetailScreen第365—367行允许将模型标记为audio输出，保存后同页第406行可打开快捷测试；applicableKinds第140行依据audio输出添加SPEECH_OUT。普通有效TTS模型tts-1因此产生model="tts-1", voice="tts-1"。这不是厂商音色模板，也不是任意不存在的模型。默认有效音色alloy被显式错误参数遮住。

[OpenAI官方TTS文档](https://developers.openai.com/api/docs/guides/text-to-speech)分别定义模型与音色，列出tts-1的音色如alloy、echo、nova、shimmer，不包含tts-1。本轮只查询公开文档，没有调API；确认请求违反已核对的参数约定，未声称实测HTTP状态码或所有兼容端点必然拒绝。

[ReadAloudPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:472) 朗读也将modelEntry.model.id同时作为model和voice，同一家族问题在实际播放入口存在，合并为本项。不能仅修快捷测试而留下播放调用方。MiniMax等适配器有自己的映射/兜底，不能说所有厂商模板都坏；同样不能以某个厂商已修复等值参数证明基础OpenAI路径也正确。

方向：区分真实API模型与厂商音色模板；普通TTS模型使用适配器默认音色或明确选择的音色。复用已有厂商映射，不能统一删除voice字段导致指定模板回退错误。没有改代码。

固定上游QuickTestSheet第484—486行仍同时传model.id和voice=model.id，虽附带resolvedModel；上游基础VoiceProvider第145、147行仍直写model/voice。此普通TTS参数问题在已核对片段中也是继承问题，不把resolvedModel参数存在当作已修复证据。

## 快捷测试其他分支与候选边界

QuickTestSheet按1—220、221—430、431—609全文读取。rememberCoroutineScope归composition所有，runAll发并行子任务；页面移除后scope取消，因此没有证据说所有测试退出后仍无限继续。网络取消底层同步HTTP行为是已有审查家族，本轮不再计数。

AudioReplyContent在composition写cache文件并同步prepare播放器，异常播放只写warning、isPlaying=false，成功徽章仍表示合成获得字节。真实播放失败反馈和大响应主线程耗时值得继续核对，但未将设备解码失败或实测卡顿当已发生。runAll先切Running让旧AudioReplyContent离开composition，DisposableEffect释放player/delete文件；不能未经这条状态链就认定每次重试必泄漏旧文件。ImageReply解码失败时显示字节数与无法预览提示，不能说完全无提示。

TEXT空回复与TRANSCRIPTION空识别被包装成TextReply，状态徽章成功；此测试可以只定义请求成功，是否必须验证非空/识别准确是验收规则问题，保留候选。IMAGE_GEN仅支持OpenAIProvider，其它Provider有明确unsupported显示，本轮不把所有多模态模型都要求走同一image endpoint。

VoiceProviderFactory.make在局部runCatching之前，transport gate可能抛异常；要把它算用户可达崩溃还需证实无效配置能通过现役校验到此处，未用任意构造instance新增Bug。系统TTS测试clip有15秒timeout和shutdown/delete finally，没有执行系统引擎；初始化回调时序仍未实测。

## 模型选择与按钮封装

ModelEntryPicker全文473行：按enabled instance、有效模态、ID/显示名搜索过滤；搜索时展开已折叠Provider，系统条目按方向注入、excludeIds处理，不显示QuickTest按钮。唯一modelEntryPickerItems调用为AgentLoopAddSheets第120行，没有传onQuickTest，因此该扩展内部可选快捷按钮不是本轮真实QuickTest入口。真实入口已在上文列明。

展开Provider的全部模型在一个LazyColumn item内用Column/forEach绘制，不能获得逐模型lazy构造收益；大模型目录可能较重，但没有耗时证据，不新增卡顿缺陷。折叠预览首行可直接选择是明确代码行为，不猜测应该只展开。search不会搜索instance label，本页placeholder是模型搜索，不重复此前系统音色搜索项。

MinisButton全文233行：enabled、onClick透传Material TextButton，primary/normal/destructive按明确规则选文字颜色、字重。Small32dp视觉尺寸不能直接推导实际触摸区域也是32dp，平台最小交互尺寸仍需核对，未新增无障碍Bug。MinisModalBottomSheet全文41行转发sheetState、insets、dismiss；它本身不是此前记忆编辑页的键盘问题入口。

MinisButtonUsageGuardTest全文63行，仅用源码正则检查原生Material按钮调用/导入；不能证明运行时触摸、无障碍、页面主按钮数量或所有按钮配色正确。测试未执行，也没有编写新测试。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [QuickTestSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/QuickTestSheet.kt:1) | 609 |
| [ModelEntryPicker.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/ModelEntryPicker.kt:1) | 473 |
| [MinisButton.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisButton.kt:1) | 233 |
| [MinisModalBottomSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisModalBottomSheet.kt:1) | 41 |
| [MinisButtonUsageGuardTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/components/MinisButtonUsageGuardTest.kt:1) | 63 |

上述全文输出完整；其它已有full文件只片段复核，不重复计数。一次assets检索命中超长单行JSON导致合并输出截断，随后独立重读相关源码/上游片段，未把assets登记为全文。

累计全文 **1249 / 1546文件、239641行**；partial13，not_registered284，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前116份报告哈希、本报告链接行号、全文批次总计。只新建本报告并更新台账。
