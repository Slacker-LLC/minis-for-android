# 图片读取、加载与角色记忆全文审查：modality测试证据范围

日期：2026-10-07，固定远端`ea620e9ec51a246ce76cdec45a8d43109462f472`。本轮新增全文5文件、734行；已全文CharacterMemoryRepository73行重读不重复计数。旧196份报告和所有旧台账保留，独立新建本文及台账。未改代码、写/执行测试、构建、模拟器/adb/Root/设备/云资源、凭据读取、模型/工具请求或用户图片读取。

本轮无新增缺陷编号。RI01/RI02/RI03、IF01、RP01/RP04仍有现版证据；modality测试与实际调用点不一致，按覆盖限制记录，不据测试注释推断已修，也不强行升级为所有Provider都拒绝图片。

## 读图路径的已知问题仍在

[ReadImageTool.kt:35](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt#L35)读取path/tool_title并分类来源，整个execute没有消费prompt、调用VisionGroupResolver.describe或根据主模型能力走描述路径。LinuxReadImageHandler仍直接调用该execute。RI01“视觉组描述路由断开”保持，ATG01“无视觉时注册表合并重新暴露”另报告记录，两个边界不能互相替代。

[ReadImageTool.kt:186](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt#L186)media URI先要求READ_MEDIA_IMAGES或旧系统READ_EXTERNAL_STORAGE，没有先按已持有的单URI grant尝试打开。有效Photo Picker单URI授权并不等于全相册授权；已有RI02在此仍成立。没有实际弹系统权限或读取相册，不能把这个反例泛化为任意content URI都应该可读。

[ReadImageTool.kt:157](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt#L157)metadata尺寸取原始bounds，而size在media分支因info为空回落到压缩JPEG字节数。copyMediaImage计数total仅用于读上限，没有回填源大小，所以RI03仍是同一文件大小口径问题。guest分支info.size为源文件大小，不受该回落条件影响。

执行保留真实保护：路径分类拒绝其它content/remote；guest通过WorkspaceFileClient目录句柄访问；先bounds探测、按最长边4000采样、2000缩放，finally回收bitmap；读取有MAX_FILE_BYTES预算。不把图片缓存的存在说成Root host文件API或完全无内存边界。

读图cache key为session+path，同路径并发执行或文件内容更新可以共用槽；源staging原子替换与UI后续打开的具体时序需另闭环，不凭相同key就宣布跨session内容泄漏。压缩返回值未检查、失败缓存清理及catch Exception对取消的处理是候选，未新增实际故障。

## MinisImageFetcher：旧加号解码问题与会话显示范围

[MinisImageFetcher.kt:36](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt#L36)URLDecoder处理minis路径，mtime key亦同样处理，合法字面+会按表单规则变空格，旧IF01保持。%2B恢复为+不属于该反例，不称哈希碰撞或路径越界。

Fetcher明确只接受global guest路径与外部mount；session-scoped路径需要context但该类没有接收session参数。当前聊天renderer另有session staging，不能把这个Fetcher的拒绝扩张成所有会话图片都无法显示。MinisApp1221—1227仍注册String/Uri fetcher与mtime keyer；不是未接线类。

mtime key是uri+modified，Keyer里面runBlocking(IO)及相同mtime替换的缓存行为，仍需Coil调用线程及实际更新路径证据。普通File扩展名mime猜测与内容真实格式不一定一致，但不能没有受支持解码反例就宣布错图。固定上游MinisImageFetcher已有session参数与不同读取链，却同样保留URLDecoder语义；旧上游比较已报告，不建议恢复跨会话fallback。

## NativeVisionModalityTest不能证明现版三个调用点已统一

测试109行6例调用hasImageInput/normalizeModalityName，覆盖image_input、image、大小写、空/null、后缀标准化。其中“all three call sites agree”只是把同一个nativeVision布尔值复制到三个局部变量，再检查相等；没有实例化ViewModel或调用真实三个决定点。

测试注释称现版有currentModelHasNativeVision统一accessor且executeReadImageTool走它，但现版生产检索无该accessor/executeReadImageTool方法。[ChatViewModel.kt:990](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L990)工具暴露和[ChatViewModel.kt:8124](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L8124)visionPlaceholderFor仍直接lowercase().contains(image)。另一方面[VoiceModality.kt:90](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/data/model/VoiceModality.kt#L90)hasImageInput使用normalizedInputs，接受image_input。

因此测试能证明helper接受后缀形式，不能证明生产两个内联点也接受，更不能凭三个变量相等证明三个实际调用链一致。这个证据差异确实存在，不以测试描述覆盖源码事实。

不过OpenAIProvider1975/2938使用model.hasImageInput，且像素发送分支在supportsImages为true时走image_url；noVisionPlaceholder只在false分支作为文字使用。因此不能仅凭ViewModel误判就宣布具有image_input的模型在所有请求中被剥掉像素。tool gate又被ATG01合并影响，真实最终行为需要分Provider、工具/附件链继续核对，本轮不重复立项或捏造一次实际请求。

MergeModalitiesTest45行5例直接调用生产mergeModalities，验证保留未显示字段、开关增删及distinct。它只验证集合合成函数，不测试Repository持久化、override继承、模型保存后Provider请求或上述ViewModel检测链。未运行测试。

## 角色记忆：纯revision规则不等于并发落盘事务

CharacterMemoryDocument对当前文本计算SHA-256 revision，mutate先比revision，修改后限制64000字符；append/replaceRange/clear有明确范围和冲突返回。纯函数里冲突拒绝是真实保护。

CharacterMemoryRepository仍readText→Document.mutate→固定memory.md.tmp→rename/fallback写目标，没有把整个read-modify-write串起来。两个调用同时读同一revision可都返回Success，后写覆盖先写；已有RP01保持，不从单纯revision参数推导并发正确。readText失败回落空串是既有存储候选，未运行失败注入。

[CharacterMemoryDocument.kt:126](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryDocument.kt#L126)分页当第一条行超budget时因page为空仍加入整行，因此RP04首长行突破maxChars保持；最大文档64000与默认读12000是不同预算。query模式只加能放进预算的命中，超长命中不加入但matchedCount仍计入、hasMore=true，可能没有结果内容却无法按查询offset继续取得该长行；保留同族分页语义观察，不另加重复数量。

snapshot.lineCount忽略尾部换行，但read/replaceRange按split含末尾空条目，字段口径与编辑范围并不自动相同；没有产品调用反例不新增缺陷。读取结果持有完整snapshot.content，工具序列化是否仅返回page需要外层合同证明，不能把内存对象字段等同模型全量泄露。

## 证据与覆盖交付

固定OpenMinis`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`已有对象仅读MinisImageFetcher29—42等相关片段，未fetch或运行；不把存在不同session接口当本地全部图片链错误的证明。角色记忆来源说明不作为外部依赖代码已审阅的替代。

| 新增全文 | 行数 |
|---|---:|
| [CharacterMemoryDocument.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryDocument.kt#L1) | 179 |
| [ReadImageTool.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/ReadImageTool.kt#L1) | 244 |
| [MinisImageFetcher.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/MinisImageFetcher.kt#L1) | 157 |
| [NativeVisionModalityTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/data/NativeVisionModalityTest.kt#L1) | 109 |
| [MergeModalitiesTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/ui/settings/MergeModalitiesTest.kt#L1) | 45 |

五文件均独立/小批次完整输出；相关定位组合截断不升级覆盖。角色记忆Repository73行重读，Provider/ViewModel/模型helper只查相关片段，不算全文。

新台账：[2026-10-07-code-review-coverage-ea620e9e-image-memory.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-ea620e9e-image-memory.json)。累计全文 **1396 / 1664文件、236284行**；partial10、尚未登记258、excluded124，tracked1788。全库通读未完成。

交付核验旧196份报告和全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged代码diff、1788快照blob/hash/字节/行数和本报告引用行号。只新增本文与新版台账。
