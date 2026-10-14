# 内联音视频与全屏播放错误状态静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文1文件、661行；确认1项P3。静态阅读，没有修改产品代码、编写/运行测试、播放媒体、构建、模拟器、adb、Root、设备、模型或云任务。没有读取凭据和真实会话文件；独立新报告，旧报告不改。

## IME01 / P3：全屏视频吞掉播放错误而保留播放状态，错误后控制栏仍自动隐藏

[InlineMediaPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/media/InlineMediaPlayer.kt:461) FullscreenVideoContent的setOnErrorListener只Log.w并返回true，没有设置错误状态、isPlaying=false或showControls=true。[InlineMediaPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/media/InlineMediaPlayer.kt:387) isPlaying初值true；onPrepared也设true，正常completion才设false。第410行LaunchedEffect(showControls,isPlaying,scrubbing)在播放状态下等待3秒后隐藏控制栏，因此准备/解码错误发生时，UI仍显示暂停图标、时长可能0:00，并在定时器到期后隐去控制栏。播放中出错时也停留在旧播放状态。

[AOSP VideoView源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/widget/VideoView.java)第531—575行error listener先将内部状态置STATE_ERROR，然后调用外部listener；返回true便立即结束，跳过系统错误dialog和dialog确认后的completion通知。第604—608行说明同一语义。[Android MediaPlayer.OnErrorListener文档](https://developer.android.com/reference/android/media/MediaPlayer.OnErrorListener)也定义true为已处理错误。因此不是“后台日志之外系统还一定显示错误”的正常回退；这里App宣称已处理，却没有用户可见反馈。AOSP master是平台参考，不声称所有OEM实现逐行相同，本地没有任何错误UI/状态更新是明确源码事实。

真实入口 [ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:2291) 点击视频文件设置previewVideoFile，第6649行调用MinisFullscreenVideoPlayer；[StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:2536) RenderMdVideo同样调用该dialog。静态触发前提为可解析本地媒体File进入dialog后发生VideoView准备或播放错误，未要求模型、Root或旧minisd参与；未实际生成损坏媒体或测量设备失败。

结果是用户遇到不播放的黑屏/停帧但无错误解释，操作状态与实际播放器不一致。点视频仍能重新显示控制栏，系统返回/onDismiss仍能退出；没有将其说成无法退出的死锁或崩溃。也没有把每个慢prepare都判为出错：错误callback触发是前提。

修复方向：在错误callback同步更新播放/错误状态，显示失败原因和可退出/重试入口，避免错误状态继续自动隐藏必要操作；保留正常completion行为。未实施。

## 音频、视频生命周期与不计数候选

InlineAudioPlayer第92行remember创建MediaPlayer，第94行DisposableEffect(filePath)同步setDataSource/prepare，成功时设时长/完成回调，异常写error；dispose有stop/release。这与旧KM08技能Markdown音频的同步prepare属于同类UI线程风险，新调用点不另计编号。当前InlineAudioPlayer失败也进入该DisposableEffect的onDispose，与KM08中失败返回null、丢掉新建player的逻辑不同，不能照抄“失败对象必然泄漏”。

AudioPreview [FilePreviewScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:561) 调本组件；[AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1209) FilePreviewHolder.currentItem经dialog进入FilePreviewScreen。mediaPlayer remember未key filePath，而effect换path时会release同一个对象，构成参数切换候选，但常规退出/新建预览dialog会重新创建composition；本轮没有闭合正常操作中同slot直接换音频的调用，不计现役新缺陷。

InlineVideoPlayer第253行AndroidView仅factory设置URI，没有update，onError第281行返回false，与全屏true不同，保留VideoView默认错误反馈，不能将IME01扩展到所有视频。factory内尺寸调整依赖layoutParams；未在设备上确认特定时序空layoutParams，未计崩溃。资源释放不能只凭无显式onRelease就判必漏，VideoView的Surface生命周期也管理player；本轮没有实际detach/reuse或后台播放证据。

FullscreenVideoContent处理dialog窗口的沉浸状态，dispose恢复system bars及亮色图标设置；自动隐藏control、拖拽关闭、分享使用FileProvider。分享fallback file://、后台暂停/音频焦点、AndroidView缺update、底栏insets等候选仍需具体边界，本轮不凭候选追加缺陷。对dialog window的恢复与Activity window不能混为同一个对象。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游同路径InlineMediaPlayer第458—460行同样在全屏onError只记录日志并true；第279—282行inline视频false。IME01为双方共有错误状态缺口，不宣称本地独有回归。上游音频remember/effect设计也相同；不因移植注释就假定额外生命周期修复存在。

## 覆盖与完整性

[InlineMediaPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/media/InlineMediaPlayer.kt:1) 全文661行，分1—230、231—460、461—661完整读取。调用方此前覆盖，本轮只复读相关片段，不新增其全文计数。一次FilePreviewScreen混合查询截断，随后实际AudioPreview/VideoPreview、nav/dialog、chat和Markdown入口单独重读，截断段不作为全文证明。

公开平台源码查阅只为确认error listener返回语义，没有运行远端代码；AOSP googlesource路径访问失败，随后aosp-mirror的VideoView源码成功提供完整相关实现。MediaPlayer文档不能代替VideoView的dialog路径，结论使用后者闭合。

累计全文 **1355 / 1546文件、263016行**；partial13、not_registered178、excluded124，全仓通读尚未完成。交付核验全部1670 tracked路径hash/bytes/正数文本行数、两仓HEAD、tracked/staged空diff、此前137份报告哈希、新报告绝对链接/行号与全文批次总计。只新增本文并更新覆盖台账。
