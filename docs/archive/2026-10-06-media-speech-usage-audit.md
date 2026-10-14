# 媒体库、朗读与应用使用记录审计

日期：2026-10-06。项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮只做静态审计，新建独立报告；不修改产品代码、旧报告及合同，不运行构建、测试、模拟器、adb、Root、设备或云任务，不读取凭据、真实媒体、语音或使用记录。

新增 **10 项 P2 问题**。全文新增 10 个文件、2,834 行，累计 **635/1,546 个纳入范围的文件、130,754 行**；另有 13 个部分读取、898 个未登记，124 个排除项。全库排查仍未完成。反例均为静态推导，未描述成真机复现。

## 问题索引

| 编号 | 问题 | 影响 |
|---|---|---|
| MP01 | 应用使用记录没有按包汇总 | 按单日峰值排名，同一应用重复占用结果名额 |
| MP02 | search_recordings 只是普通音频查询别名 | 录音搜索返回音乐等非录音，筛选参数没有正确入口 |
| MP03 | 所有媒体查询只检查图片权限 | 视频、音频所需权限未声明和请求，正常查询不可用或不完整 |
| MP04 | 照片导入把 guest 路径当成宿主路径 | 会话素材无法按正常路径导入，相对路径不使用调用 cwd |
| MP05 | 导入失败不清理已创建媒体行，发布结果也不检查 | 遗留 pending 半成品，或声称成功但未发布 |
| MP06 | 语音目录枚举被取消时未释放临时 TTS 引擎 | 停止工具/退出页面可能遗留引擎资源 |
| MP07 | 停止朗读没有使合成中的句子失效 | 关闭、静音或开始录音后仍可能重新播放 |
| MP08 | 朗读与照片导出使用共享固定暂存文件 | 不同播放器可能读错音频，并发照片导出可能读到不完整文件 |
| MP09 | 非法媒体日期被当作没有日期限制 | 查询静默扩大范围，返回不符合要求的媒体 |
| MP10 | 视频缩略导出直接复制原视频却标为 JPEG | 返回 .jpg 文件和错误格式，后续图片处理无法正常使用 |

## 现役入口

[MinisApp.kt:594](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:594) 注册音频查询和 search_recordings 别名；[687 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:687) 之后注册 TTS、媒体图片、应用使用记录和外部 MediaSession 工具。原生 android-photos 在 [872 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:872) 注册。结构化媒体查询实际调用 PhotosOffloadHandler，不是另一个数据库实现。

朗读由 ReadAloudPlayer 路由至配置的语音 Provider 或系统 TTS。ChatScreen 的 [3136 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3136) 与 [5712 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:5712) 分别提供选择文本与回复朗读的播放器。此前已报告工具放置和旧媒体播放器 workspace 问题，本轮不重复计数。

## MP01 — 查询多天，却按单个日统计行排序

[AndroidUsageOps.query:40](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidUsageTools.kt:40) 用 INTERVAL_DAILY 查询整个 days 窗口，随后直接按每行 totalTimeInForeground 排序、take(limit)，将每行作为一个 app 输出。没有按 packageName 合并，也没有输出每行对应的日区间。

[UsageStatsManager 合同](https://developer.android.com/reference/android/app/usage/UsageStatsManager)明确 queryUsageStats 可为同一包返回多个统计对象；平台另有按包汇总的 queryAndAggregateUsageStats。日统计不是整个查询窗口的应用总量。

反例：A 七天每天使用 30 分钟，总计 210 分钟；B 只使用一天 60 分钟。当前结果把 B 排在 A 前面，并返回七个 A 行，limit=3 时可能得到 B、A、A，漏掉其它应用。调用方无法从输出区分重复行日期，也不能用这些行推导 top apps 的窗口总排名。

方向：先按包聚合再排序和限制应用数量，明确平台统计区间可能扩展至完整 interval 的精度边界。另有相关状态缺口：queryUsageStats 的 null 被 orEmpty 合并为空列表；Android R 起用户尚未解锁时平台可以返回 null，不能据空列表证明没有使用记录。此相关 null 分支不再另拆编号。

## MP02 — 录音别名未带来录音语义

[MinisApp.kt:595](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:595) 将 search_audio、list_audio、search_recordings 全部登记到 AndroidMediaAudioHandler。别名机制 [ToolRegistry.kt:23](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:23) 只映射工具名，不补充参数。

音频 handler 的 [123 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidMediaStoreTools.kt:123) 始终构造 list --type audio，既未声明 recordings_only，也没有追加 --recordings。真正筛选只在 [PhotosOffloadHandler.kt:256](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:256) 于 recordingsOnly=true 时生效。

反例：媒体库有近期 Music/song.mp3 和 Recordings/note.m4a；调用 search_recordings 得到普通音频列表，音乐占用 limit，录音可能被挤掉。已有 recordings_only 参数反而在图片 handler 的 [21 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidMediaStoreTools.kt:21)：该 handler 只接受 photo/video，追加的 --recordings 不会进入 queryAudio。因此两条结构化入口都没有正确表达录音筛选。

方向：复用现有音频筛选策略，让录音入口真正带上筛选语义；工具名兼容不能丢掉能力差异。若不维护录音专用入口，就不应以等价别名声称已支持。底层原生 CLI 的 --type audio --recordings 能表达筛选，不需要另建重复查询实现。

## MP03 — 音视频能力未接上对应权限

所有子命令在 [PhotosOffloadHandler.kt:72](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:72) 进入同一个 ensureMediaPermission，发生在解析具体媒体类别之前。hasMediaPermission 的 [380 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:380) 在 Android 13+ 只检查 READ_MEDIA_IMAGES，或接受 Android 14 视觉媒体部分授权；请求列表的 [412 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:412) 同样只有图片与视觉部分权限。

当前 [AndroidManifest.xml:74](/home/jiale/projects/minis-for-android/src/android/app/src/main/AndroidManifest.xml:74) 声明 READ_MEDIA_IMAGES，未声明 READ_MEDIA_VIDEO/READ_MEDIA_AUDIO；[build.gradle.kts:223](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:223) targetSdk=35。Android [分媒体权限合同](https://developer.android.com/about/versions/13/behavior-changes-13)要求读取其它 App 创建的视频/音频时分别取得对应权限。图片权限不代表音频或视频授权，视觉部分权限更不能覆盖音频。

反例：用户允许照片，调用 android.media.audio 或 CLI list --type video；图片 gate 直接通过，但代码未请求目标媒体权限，Provider 可拒绝读取或只返回可见子集。若用户只想读取音频，当前工具又会先要求无关的照片授权。只在某些设备显示空集合，不能据此断言媒体库为空。

方向：按子命令、媒体类别、目标资产和平台版本选择实际需要的权限，声明与请求保持一致；组合 all 查询应区分可用类型与不完整结果。不要把访问 App 自己创建的媒体与读取其它 App 的媒体混为一谈，也不要通过读取操作恢复通用 Root 文件路径。

## MP04 — 导入丢失会话文件与 cwd 语义

[handle:82](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:82) 调用 handleImport(args)，没有传入 request.sessionId 或 request.cwd。[781 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:781) 直接 File(path)，再用宿主 FileInputStream 读取。

GuestCommandBridge 的 [233 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:233) 传入 argv、cwd、sessionId，未将导入路径替换成宿主路径。NativeOffload 的 [315 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:315) 也是原样构造请求。没有上层翻译可以补上 handler 丢弃的上下文。

反例：调用会话中存在 /var/minis/offloads/p.jpg 或 /workspace/p.jpg，执行 android-photos import 该路径。App 进程 File(path) 不在 guest chroot 中，找不到会话 backing，返回 no_file。相对 p.jpg 也按 App 进程工作目录读取，不使用调用方 cwd。此处不声称所有宿主路径均不可用；问题是正常 guest 路径与相对路径语义不成立。

方向：使用现有会话文件接口、安全路径解析与输入流读取；路径在调用方 cwd 下解析，保留 session 边界。export 的 [686 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:686) 已调用 WorkspaceFileClient，是现有可复用模式。[PhotosImportPathArgTest.kt:29](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/offload/PhotosImportPathArgTest.kt:29) 只断言字符串参数解析成功，不能证明该 guest 路径在真实导入中可读取。

## MP05 — 创建、写入、发布未形成完整导入生命周期

导入先 insert 并在 Android Q+ 设置 IS_PENDING=1，随后打开流、写入、最后 [844 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:844) 清除 pending。发布 update 的行数未检查。任何写流/发布异常进入 [856 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:856)，只返回错误，没有删除已创建 URI，也没有把它交给恢复逻辑。

[MediaColumns.IS_PENDING 合同](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_PENDING)说明 pending 时其它 App 不能打开文件，直到发布或到期。反例：insert 成功，openOutputStream 返回 null 或复制中途失败，工具返回 import_failed，却留下隐藏的空文件或半成品，至少持续到 Provider 清理。重试创建另一行，不会恢复此前行。另一个分支是发布 update 返回 0：代码仍输出新 ID、完整字节数和退出 0，却没有证明素材已经可见。

方向：保存本次拥有的插入 URI，失败时清理或明确记录可恢复状态；成功反馈必须检查发布结果。若 cleanup 自身失败，也要说明未完成素材，不能把 pending 清理寄托为未知的未来行为。insert=null 已有显式错误，不应误说这条分支也被当作成功。

## MP06 — 枚举取消跳过 TTS shutdown

[SystemTtsVoiceCatalog.enumerate:68](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemTtsVoiceCatalog.kt:68) 创建临时 TextToSpeech，随后等待 ready；shutdown 仅在 [76 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemTtsVoiceCatalog.kt:76) 的正常控制流执行，没有 finally。

反例：第一次请求目录正在绑定引擎，调用协程被用户停止、页面退出或上层取消；ready.await 被取消，后续 shutdown 不执行。局部的 4 秒 withTimeoutOrNull 可以处理自己的超时返回，但不能代替外层取消后的资源清理。[Kotlin 超时合同](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html)明确取消涉及结构化协程与资源丢失风险；Android [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)要求使用结束后调用 shutdown 释放引擎资源。

方向：资源创建后以 finally 保证关闭，并保留取消传播。空目录不被缓存已有合理防护，但它不解决取消泄漏。初始化失败、超时和确实没有已安装 voice 目前都可合并成空列表，工具又返回成功；空列表不能单独作为用户没有安装语音引擎的证据，此状态线索不另计编号。

## MP07 — 停止只清理已有播放，没有废弃在途合成

[ReadAloudPlayer.stop:324](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:324) 清理队列、缓冲、MediaPlayer 和 system TTS，却没有取消当前 speakOne 或增加代次标记。Provider 合成在 [467 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:467) 挂起，返回后 [487 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:487) 无条件 playBytes；没有再次核对停止/静音/录音状态。系统路径等待 awaitReady 后也直接 speak。

明确交错：句子已经通过 enqueue，正在网络合成；用户关闭自动朗读，VoiceOutputState [172 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceOutputState.kt:172) 调用 activePlayer.stop；此时 MediaPlayer 尚未创建，所以释放不到该句子的播放。网络稍后成功，旧 worker 创建并启动 MediaPlayer，关闭之后又开始说话。mute 和 suspendForCapture 也没有废弃合成中的句子，开始录音后仍可能产生反馈音频。

enqueue 的 canPlay/recognizer 校验只能拒绝后来入队的文本，不能保护已经在途的文本。方向：使停止和捕获暂停对在途工作同样生效，采用可取消的当前句子任务或明确播放代次，在合成、缓存写入与启动播放的边界检查归属。不能靠关掉整个 worker 后忘记重建来破坏后续朗读。多个已注册播放器只有 activePlayer 被 stop 的行为也需要按全局开关与手动朗读语义进一步验收，本项不依赖这个额外条件。

## MP08 — 共享暂存文件没有调用所有权

朗读 [playBytes:497](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:497) 的所有播放器均写 cacheDir/readaloud_tts.audio。每个播放器有自己的 worker，并没有全局音频缓存锁。选择文本和回复播放器可以同时存在，注册栈只决定胶囊 UI 归属，不串行执行其合成。

交错：播放器 A 写入句子 A 的音频后等待回到主线程；B 在 IO 上覆盖同一路径；A 随后 setDataSource(file.absolutePath)，实际打开 B 的音频。也可能在另一方写入途中读取到不完整文件。不能以“每个播放器内部串行”证明跨播放器共用文件安全。

照片导出的 [673 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:673) 同样用 asset ID、size 和原名拼出共享 cacheDir/photos-export 文件名，不含调用 ID/session。[NativeOffload.kt:247](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:247) 与 [GuestCommandBridge.kt:263](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:263) 都允许独立 worker 并发调用。

同素材同尺寸的两个导出会共用同一文件：A 完成复制，B 打开 outputStream 截断暂存文件，A 再从该文件写入自己的 workspace，可读到空文件/部分内容；A 清理时还可能删掉 B 正准备读取的路径。WorkspaceFileClient 的目标确实按 session 解析，本项不否认最后的目标隔离，冲突发生在此前共享暂存阶段。写入返回字节数也没有用于核对暂存复制结果。

方向：复用每调用唯一暂存文件与 finally 清理，或让每个播放器/请求持有自己的文件描述符和明确生命周期；保留会话目标解析。相同代码模式在这两条路径出现，合计一项共享暂存所有权问题，不拆成两个编号凑数。

## MP09 — 日期解析失败扩大查询范围

[resolveDateRange:1019](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:1019) 将 parseDate 返回 null 直接用作未提供边界；handleList 不区分显式非法输入与缺省输入。parseDate 也使用默认宽松 SimpleDateFormat，并未验证字符串完整消费。

反例：结构化媒体工具同时提供 start_date=not-a-date、end_date=not-a-date；适配器只验证两者共同存在，然后原生解析都返回 null，各查询不追加日期谓词，输出成功的不限时间列表。只提供一个非法边界的 CLI 调用会退化成单边或无边界。用户明确要求范围，工具却扩大读取与返回范围。

方向：任何显式日期都必须严格且完整解析，检查窗口顺序；缺省只用于参数真正不存在。读取条件不能因解析失败而悄悄移除。此前日历报告指出的宽松日期问题在另一实现中也出现；本项针对本轮首次全文读取的媒体过滤调用和范围扩大行为，不重复计入日历写入缺陷。

## MP10 — 视频 thumb/medium 导出没有产生缩略图

export 允许 original/thumb/medium，但 [677 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:677) 对任意 video 均调用 copyStream，直接复制视频字节；copyResized 只处理照片。与此同时，非 original 的 [673 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:673) 给暂存文件加 .jpg，结果 [694 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt:694) 固定声明 format=jpeg。

反例：一条当前 App 可访问的视频，执行 export --id ID --size thumb；结果退出 0，export_size=thumb、format=jpeg、linux_path 以 .jpg 结尾，实际文件仍是完整视频。消费该结果的图片解码或缩略展示没有得到请求的图片，字节数也没有按缩略意图减少。此条件不依赖其它 App 的视频读取权限，App 自己创建的可见视频同样可触发。

方向：如果视频缩略图不是当前能力，明确拒绝该 size/type 组合；如果支持，则取得真实视频帧并缩放编码，保证扩展名、format、media_type 与实际输出一致。无需因为请求缩略图就另建完整视频转码体系。

## 对照、排除项与验证限制

固定只读 OpenMinis：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。已对照 sandbox/offload/PhotosOffloadHandler.kt 的 633–730 行：上游同样 File(path)、发布结果未检查、异常后未清理 URI，所以 MP04/MP05 是共同保留的缺口，不能称为本地独有回退。SystemTtsVoiceCatalog.kt 64–85 行也保留相同取消清理缺口。VoiceOutputState.kt 152–183 行仍在字段装载前发布 prefs；本轮未将此初始化竞态另计编号。上游片段不算本库新增全文覆盖。

MediaSession 的控制结果使用 accepted=true，而不是宣称动作已经在外部播放器完成；本轮不把合法异步派发本身误判为假成功。其旧 notification listener 与新组件授权不一致的风险属于此前通知审计的同一缺口，不重报。播放位置只是平台返回值，缺乏足够平台实现证据证明它必然过时，本轮不猜测新增位置故障。

Photos 的 smart album/create-album 明确提供不支持/未创建说明，不将它们作为空成功问题。媒体 list、albums、stats 和 near 另有 query null 与合法空结果合并、部分读取故障不完整的问题，属于此前多次发现的结果状态模式；这里保留调用证据，不再拆编号。all 的结果按 photo/video/audio 顺序填满 limit，尚未证明产品要求跨类型全局时间排序，因此不以主观预期计作新 bug。

本轮未播放声音、访问媒体、执行导入删除或验证 Provider。设置 TTS voice 后是否真正被当前语音 Provider 使用、系统引擎切换后的目录缓存刷新及线程归属仍需要单独核验，不把工具保存配置等同于运行时已生效。

## 全文阅读记录

| 文件 | 行数 |
|---|---:|
| [PhotosOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/PhotosOffloadHandler.kt) | 1197 |
| [AndroidMediaSessionTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidMediaSessionTools.kt) | 168 |
| [AndroidMediaStoreTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidMediaStoreTools.kt) | 136 |
| [AndroidTtsTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidTtsTools.kt) | 106 |
| [AndroidUsageTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidUsageTools.kt) | 82 |
| [SystemTtsVoiceCatalog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemTtsVoiceCatalog.kt) | 115 |
| [VoiceOutputState.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceOutputState.kt) | 231 |
| [ReadAloudPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt) | 691 |
| [LazyReadAloudPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/LazyReadAloudPlayer.kt) | 39 |
| [PhotosImportPathArgTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/offload/PhotosImportPathArgTest.kt) | 69 |
| 合计 | 2834 |

以上均为本轮首次全文登记，长文件分段完整读取，没有用搜索命中代替全文。MediaQueryPolicy.kt 再次完整复核 63 行，不重复增加累计覆盖。其它调用链片段不计全文。记录见 [覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

交付核对：1,670 个受跟踪文件清单、哈希与 HEAD 一致，tracked diff 为空；38 份旧 Markdown 报告哈希不变。本轮只新增本报告、更新覆盖台账。本地引用经过文件存在与行号检查。产品运行行为、OEM 权限处理、真实音频竞态和媒体 Provider 失败仍未设备验证。
