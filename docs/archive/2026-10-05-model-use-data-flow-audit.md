# model_use 参数、透传与输出数据流审计

日期：2026-10-05。项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮只做静态审计，新建独立报告，不改产品代码或旧报告，不运行构建、测试、模拟器、adb、Root、设备或云任务，不读取真实凭据。

新增 **7 项 P2 问题**。全文新增 3 个文件、2,182 行，累计 598/1,546 个纳入范围的文件、123,876 行。全库审计尚未完成；以下反例为源码数据流推导，未实连模型服务或设备复现。

## 新问题索引

| 编号 | 问题 | 影响 |
|---|---|---|
| MU01 | 标准 JSON 中部分参数没有按帮助说明转换 | Gemini 图片大小/比例等无效，system/temperature/max_tokens 被忽略 |
| MU02 | 原始透传的 merge 基线只保留文字 | 已成功解析的 input_audio、image_url 不进入请求 |
| MU03 | 未生成媒体也写文件并返回成功 | 文本/空字节覆盖 .png/.mp3 等输出，Agent 得到假成功 |
| MU04 | 自动保存附件失败被吞掉 | 已生成的媒体丢失，调用结果仍退出 0 |
| MU05 | 模型 404 被当作图片端点不存在 | 对整个实例持久化错误降级，影响其它模型 |
| MU06 | 显式 --system-file 读不到仍执行请求 | 系统提示词静默丢失 |
| MU07 | search 忽略 --provider | 带供应商筛选的搜索返回其它实例模型 |

## 现役入口和已有保护

[MinisApp.kt:879](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:879)将本轮读取的 ModelUseOffloadHandler 注册为 `minis-model-use`。它并非孤立的历史文件。模型选择限定在 resolvedAgentLoopEntries；文件读写显式传入 request.sessionId，经 WorkspaceFileClient/App-owned 映射处理。没有用上游 PRootKernel 路径作为本项目修复方向，也没有把 App 原生 Provider 桥接本身认定为违规工具。

当前还会过滤自定义请求头、拒绝不支持的音频 Provider，并拒绝相对 --output。这些保护不能解决以下数据转换与状态错误。

## MU01 — 标准参数说明与实际转换不符

[帮助文本:1752](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1752)说明 JSON 中 messages/system/temperature/max_tokens/generation_config 会自动转换；[Gemini 提示:1315](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1315)和 [示例:1826](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1826)明确要求在 generation_config 中传 aspect_ratio、image_size 等参数。

但 [cmdRun:195](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:195)只从 CLI --system/--system-file 和 role=system 消息得到提示词；[262 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:262)只从 CLI 读取 max-tokens、temperature。parseMessages 对 JSON 顶层只取 messages，没有传出其他标准字段。

generation_config 的解析只发生在 OpenAI 图片专用分支。Gemini 在 [1061 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1061)直接退出这个分支，随后普通 [sendMessage:384](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:384)没有传入任何图片配置。[GeminiProvider.kt:334](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:334)自行构造 generationConfig，仅写预算、温度、思考和输出模态，没有 imageConfig/比例/大小的补偿入口。

静态反例：按内置 Gemini 示例传 `generation_config={aspect_ratio:"16:9",image_size:"2K"}`，请求仍只带 Provider 默认配置。普通 JSON 传 `system:"请用中文"`、temperature=0、max_tokens=128，也分别没有进入 systemPrompt/temperature/maxTokens，后者使用 CLI 缺省 4096。role=system 和 CLI 选项本身可用，不应写成系统提示词功能整体失效。

方向：标准输入解析应返回后续实际需要的配置，并定义 CLI 与 JSON 的优先级；对声明支持的 Gemini 参数建立真实映射，未支持字段至少明确拒绝或反馈。不能只继续在帮助文本中扩大能力。

## MU02 — merge 透传丢掉已解析的多模态内容

[parseMessageArray:1543](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1543)会解析图片；[1554 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1554)解析音频并校验 Base64。cmdRun 保留 audios 到 LLMMessage.audioParts，提取最后 user 的 imageParts，并执行音频能力/Provider 检查。

之后 passthrough.active 分支进入 [performRawPassthrough:314](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:314)。该函数没有接收 imageParts；[930 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:930)只用 m.role 和 m.content 构造 messages，没有序列化 m.audioParts。merge 模式声称基于转换后的标准输入，但实际基线仅为文本。

可达反例：可用的 OpenAI-compatible 音频模型、合法 input_audio 内容块，再附加 `passthrough={body_mode:"merge",body:{seed:42}}`。音频验证通过，最终请求仅有文字和 seed。若内容只有音频，content 还是空字符串。合法图片块在同样路径也丢失。body 明确重新写入完整 messages 可以绕过这项，但不应要求调用方重写本来承诺转换的基线。

本项限定 merge；replace 的合同就是由调用方提供原始 body，不能要求它自动转换。普通模式保留音频，因此两条路径会因无关的 envelope 开关而改变输入语义。方向：复用已有的完整标准序列化；若 merge 无法支持某种输入，必须在发请求前显式报错。

## MU03 — 没有媒体仍返回成功，还可覆盖原文件

普通路径 [414 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:414)取 firstMedia；当用户要求媒体后缀而响应没有媒体时，[436 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:436)仍将 response.text 写入输出路径，最后 [475 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:475)退出 0。结果还包含 output_file。

图片专用路径 [writeImageResult:1209](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1209)同样退回写文字，仅附加 warning=`Image endpoint returned no image data.`，退出码仍为 0。实际 [OpenAIProvider.parseImageGenerationsResult:1896](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:1896)遇到 `data=[]` 会返回空媒体的 LLMResponse，使该反例在接口返回 2xx 时可达。

具体条件：具有图像输出能力的模型，CLI 指定 --output=/var/minis/workspace/out.png，服务返回拒绝说明文字或合法空 data。原来的 out.png 可被原子替换成文字/零字节文件；没有原文件时则新建一个打不开的“图片”，Agent 仍收到成功。WorkspaceFileClient/SecureFileAccess 的原子写只能保障提交完整，不会识别内容与输出目的不符。

这与上一轮 Gemini 流式媒体丢失不同：本项读取非流式响应后的输出处理，即使 Provider 正确返回“没有媒体”也会假成功。上游已有明确修复：普通媒体必需分支和图片专用分支先返回 no_media_generated、退出 1、不写输出文件；当前缺少这段判断。

方向：先核对请求所需媒体是否真正存在，再提交文件；保留服务文字作为失败原因。媒体为必需时不能用普通文本成功替代。

## MU04 — 自动附件保存失败没有进入结果

用户未指定 --output 时，普通 [447 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:447)和图片专用 [1241 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1241)调用 writeGuestBytes。catch(Throwable) 仅 Log.w，然后 continue；结果只收集成功写出的附件，没有 failed_files/warnings 或失败状态，仍退出 0。

静态反例：服务已经返回一张图片，目标文件名可生成，但文件写入因存储耗尽或权限错误抛异常。普通路径返回 model/text，没有任何 media_files 或错误；图片专用路径因 firstMedia 已非 null，连“无图片数据”的 warning 也不会出现。多张图片时部分失败也无从得知漏了哪些结果。

范围限定为 writeGuestBytes 内的异常；uniqueGuestPath 在 try 外抛错会走外层失败，不能写成所有自动输出失败都被吞。指定 --output 时两条路径也已有显式非零返回，本项是自动保存分支的不一致。

原始透传的输出失败另有 output_error 字段，说明此项目已有可反馈部分失败的形态。方向：保留成功文件并报告失败项；全部必需媒体都未落盘时返回失败，不让日志成为唯一证据。没有要求丢弃已成功生成的媒体或再次付费请求。

## MU05 — 模型不存在污染实例级图片端点缓存

[looksLikeEndpointMissing:1025](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1025)只要 message 含 404 或 not found 就判定“端点不存在”。它没有排除 model_not_found 等业务错误。虽然注释要求模型/鉴权错误不降级，实际 [1171 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1171)接收 ProviderError 后就采用这一宽泛判断，随后缓存 chatCompletions 并继续普通聊天请求。

图片接口 [OpenAIProvider.kt:1749](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:1749)使用 mapHttpError；[3497 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:3497)把 JSON error.message 格式化为 `[status] message`，404 会成为 ProviderError。因此合法的“HTTP 404、模型不存在”必然命中上述分支；甚至其它状态的 not found 文本也可能命中。

反例限定在支持 text+image 输出、无输入图片、有效图片模式为 auto 的模型 A。服务具有正常图片端点，但没有 A，返回 404/model not found。调用缓存整个 provider instance 为 chatCompletions。[ProviderRepository.setImageEndpointResolved:1066](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1066)保存配置；下一次同实例的可用混合模型 B 经 [1116 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1116)读取该缓存，直接走聊天，不再探测实际可用的图片端点。

纯图片生成器会强制 imagesGenerations，不能用它作为 auto 降级反例；缓存也不是不可恢复的永久状态，但会跨调用/重载产生错误路线，直到用户清理或重新配置。方向：保留结构化错误语义，只对有端点缺失证据的情况降级，模型错误不得改变实例的能力结论。

## MU06 — 系统提示词文件读失败变成未提供

[cmdRun:195](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:195)从 --system-file 调用 readLinuxPath。该函数 [1464 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1464)捕获所有异常返回 null；cmdRun 不区分“没传文件”与“显式文件读取失败”，继续使用 inlineSystem 或 null 请求模型。

与其相邻的 --input 路径有明确失败处理：resolveInput 返回 null 时 [201 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:201)退出 2。但系统提示词文件没有。反例是 --system-file 指向不存在/不可读的绝对路径，同时 --input 可读；执行仍调用 Provider，缺失本次指定的行为约束、格式或任务规则，没有提示文件未读成功。

方向：仅在没有指定文件时允许缺省；显式读取失败在网络请求前返回可纠正错误。--system 明确优先于 --system-file 时仍按既有优先级处理，真正的空文件与不可读文件也应区别。

## MU07 — search 的供应商筛选参数没有生效

[HELP:1740](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1740)和文件开头都声明 search 支持 --provider。但 [cmdSearch:93](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:93)只解析 query/modalityFilter，遍历全部可见模型，没有读取 --provider。相比之下，cmdList 使用这个参数，cmdRun 也有供应商实例范围。

有两个实例 A/B，均含 ID 中带 gemini 的模型；执行 `minis-model-use search gemini --provider A`，结果仍含 B，count 同样包含 B，且没有参数被忽略的 warning。Agent 若据此挑选条目，会误以为结果满足了供应商筛选；不等于 cmdRun 的显式 provider 校验被绕过。

方向：复用现有筛选语义，在 search 中执行它；同时明确 list/search 的名称匹配与 run 的实例标签/UUID匹配关系。本项不扩大成所有模型选择都不确定。

## 固定 openminis 对照

固定提交：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，本地只读目录 `/tmp/openminis-review-20261004`。对照文件位于上游 sandbox/offload/ModelUseOffloadHandler.kt，不因目录旧名称把本项目 runtime/guest 文件判断为旧执行栈。

- 上游 189 行同样用可空读文件结果；87–110 行的 search 也没有 provider 参数。MU06/MU07 是已核对共同缺口。
- 上游普通 sendMessage 也只接收文字/预算/温度/图像，generation_config 没有进入 Gemini 请求；930–943 行的 raw merge 基线同样只序列化文字。MU01/MU02 在读取的同路径也存在。
- 上游 419–425 行增加 mediaRequired/noMediaResult，1218–1224 行的图片专用路径也在 firstMedia 为空时返回错误；1288–1313 行定义了机器可读的 no_media_generated、退出 1。MU03 有具体的上游修复可对照，当前移植缺口不是根据注释猜测。
- 上游自动保存循环直接写文件，写异常可传播到外层 handle 失败；当前将 WorkspaceFileClient 的写异常在循环内吞掉。上游也有 attachDir 无法解析时未保存的旧逻辑，不把它称为所有落盘失败都正确处理。MU04 以本项目的明确 catch-and-continue 分支为证据。
- 上游 1034–1045 行也使用宽泛 404/not found 判断；MU05 为共同问题，不能靠完整照搬上游解决。

## 没有重复计数的线索与验证限制

普通模式只转发最后 user 回合的图片，是代码注释承认的现有限制；本轮未把多轮图片丢失另计一次。明确 --output 时只选择首个附件、按后缀保存原始字节，以及未知 modality 值放行，留作后续合同与调用方核对，不仅凭直觉新增问题。没有把所有 passthrough 当作跨主机请求入口，也没有因其可写 body 就声称权限升级。

[ImageEditRoutingMatrixTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/ImageEditRoutingMatrixTest.kt)复制条件建立自有 route 函数，实际不调用 handler；该测试不能证明 handler 全数据流通过。[OpenAIEditImageTest:49](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/OpenAIEditImageTest.kt:49)明确用 data=[] 验证请求传输，不验证生成媒体落盘；它不会覆盖 MU03/MU04。这里仅说明已读测试的证据范围，没有运行或新增任何测试，也没有把测试注释中的“设备已覆盖”当成本轮设备证据。

后续经授权的端到端验收方向（本轮未执行）：

| 场景 | 应保留的可重复工件 |
|---|---|
| JSON 标准参数、Gemini 图片配置 | 输入 JSON、最终脱敏请求体；参数实际生效或明确拒绝 |
| merge 音频/图片输入 | 内容块种类/字节摘要、最终请求；原始媒体未因 envelope 丢失 |
| 图片接口无媒体、文本拒绝 | 请求/响应、退出码、输出前后 SHA-256；失败不覆盖原媒体 |
| 自动保存一项/全部失败 | 返回文件及失败项、退出码、磁盘工件；不静默报全成功 |
| 模型 404 与真正端点 404 | 请求序列、实例缓存前后快照；模型错误不改变端点能力 |
| 不可读 system-file | 错误输出、模型请求计数；输入失败不发送请求 |
| search --provider | 两个实例的配置和过滤后结果；仅包含指定范围 |

## 全文覆盖登记

新增完整逐行阅读：

| 文件 | 行数 |
|---|---:|
| main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt | 1,842 |
| test/java/com/openminis/app/provider/ImageEditRoutingMatrixTest.kt | 146 |
| test/java/com/openminis/app/provider/OpenAIEditImageTest.kt | 194 |
| **合计** | **2,182** |

OffloadArgs.kt（71 行）已在台账完整登记，本轮全文复核，不重复计数。Provider、Repository、MinisApp 和文件客户端的调用片段、旧报告检索、上游对照不计新增全文。长文件按连续范围读取至 1842 行，输出被截断的专题证据另用更小范围补读。

覆盖台账：[2026-10-05-code-review-coverage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。全库 tracked 1,670、纳入范围 1,546；全文 598、部分 15、尚无全文登记 933、排除 124；累计全文 123,876 行。文件哈希变化时须重新核对。覆盖状态不等于产品已修复、测试通过或目标已经完成。
