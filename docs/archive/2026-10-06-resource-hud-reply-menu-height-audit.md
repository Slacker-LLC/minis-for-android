# 资源HUD、回复用量及长按菜单高度静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增 **1项P3确认缺陷**；全文读取5文件、646行。未修改产品源码，未运行测试、构建、模拟器、设备、Root、云或模型请求，未采集真实进程资源。旧报告不改。

## RM01 / P3：回复长按菜单缺少滚动，低高度窗口下操作列表不能完整访问

现役入口 [ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3270) 打开AssistantMessageMenu，传真实回复预览和actions。[ChatMessageMenu.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatMessageMenu.kt:82) 用fillMaxSize的Box扣除status/nav inset，内部第92行普通Column同时放回复预览、操作Column和用量脚注，没有verticalScroll/LazyColumn或其它滚动入口。

预览最多10行、lineHeight=27.2sp及上下24dp padding。操作第121—133行一共8项，每行第146行固定height(46dp)，仅操作行已需368dp，加6dp分隔和普通细线；还有预览、10dp间距、可选脚注。可用高度小于这些内容时，子项因Column剩余高度约束被压缩/裁切，后面的分享/删除等动作无法全部呈现，用户不能滚动到它们。

静态反例限定为手机横屏或分屏中扣inset后约300dp可用高度：即使无长回复，操作列表自身也超过可用高度。MainActivity Manifest第198—203行未限制固定竖屏，resizeableActivity=true，声明处理orientation/screenSize变化；这不是只有无效超小测试参数才能进入的布局。长回复/较大字体进一步挤占操作高度。没有实测具体设备哪一行最先不可见，确认的是无足够高度、无滚动/紧凑布局通路，不能断言所有常规竖屏都失败。

方向：为内容提供有界滚动或按可用高度收缩预览，保留菜单动作可达性；不是继续减小字号或删除功能。本轮没有改UI。固定OpenMinis没有ChatMessageMenu同路径，未用上游其它旧菜单作同实现对照。

## 资源监控口径与主线程采样候选

SystemResourceMonitor第159—170行读/proc/self/stat，以最后右括号避开comm字段里的空格/括号，utime/stime尾下标11/12对应字段14/15。第134—136行计算IRIX百分比100%=一个核，没有除核数；第70行旧说明仍写cores但实现不除，不能据注释说多核百分比计算错误。窗口5个、每2秒采样，实际约10秒。

监控明确只采Android App PID，guest进程是独立PID；内存为App PSS、总量为设备物理内存。ChatComposerWidgets第474/765行与ChatToolDetailUI第484/590—597行在shell工具HUD显示CPU/MEM，但没有给出guest聚合。本轮不把产品刻意选择App口径直接算guest资源统计Bug，也不提出恢复旧minisd/proot。

[SystemResourceMonitor.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SystemResourceMonitor.kt:228) 与第232行sampleOnce在LaunchedEffect中同步执行，未withContext；包含File.readText、Debug.getMemoryInfo和ActivityManager取内存。主线程同步采样是确定事实，延迟/卡顿程度未测量，本轮列性能候选、不增加第二项已复现卡顿。固定上游同文件第275—283行已使用withContext(IO)采样、main更新tick；不能用上游修复注释代替本地设备帧耗时证据。

active=false时reset清CPU基线，LaunchedEffect重启取消轮询，离开composition也会取消；不能说退出页面后必然永久采样。App后台但composition仍在且isLive=true的行为要进一步生命周期证据。reset不清memUsed/Total，但显示旧内存是否误导需核对调用布局，不新增缺陷。

## 回复用量与缓存比例复核

ChatReplyUsage解析5个持久化字段，坏JSON/全零返回null，lastReplyUsage从末尾找最后有效用量。这是明确“最后有用量的turn”口径，不是整个多工具回复累加；没有把它与session总量混为一谈。cacheHitPercent用Long计算input+cacheRead+cacheCreation，避免Int和百分比乘法溢出。

OpenAIProvider第2540—2554行将完整prompt减cacheRead后存freshInput，latestContextTokens保留完整prompt；所以本地ChatReplyUsage将三类相加不是这里重复算缓存。Anthropic增量usage覆盖丢字段的问题已有PW报告，本轮不重复计数。负数/超Int不可信JSON可显示负值、时区变化remember(reply)不更新时钟等仍为输入/刷新边界候选，未证明正常持久化必然产生这些数据。

ChatReplyUsageTest全文70行，覆盖字段、缩写、summary、缓存比例、最后turn及坏JSON/全零，未运行。ThinkingLevelLocalized对OFF/LOW/MEDIUM/HIGH/XHIGH/MAX/ULTRA完整映射，不追用户已让其它Agent处理的翻译清理。

菜单BlurBehindDialog使用DialogWindowProvider定位窗口，进入/退出设置与清除blur flag；不能套用图片查看页另一个walker的候选。cross-window blur是否随系统动态变化需要UI证据，未新增。操作先onDismiss再执行回调，单凭先后顺序不能说回调永远丢失。

## 全文覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [SystemResourceMonitor.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SystemResourceMonitor.kt:1) | 241 |
| [ChatReplyUsage.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatReplyUsage.kt:1) | 87 |
| [ThinkingLevelLocalized.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ThinkingLevelLocalized.kt:1) | 27 |
| [ChatMessageMenu.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatMessageMenu.kt:1) | 221 |
| [ChatReplyUsageTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/ChatReplyUsageTest.kt:1) | 70 |

五文件均完整输出；片段合并输出截断后已独立重读菜单入口/Manifest/Provider相关行，未登记大文件全文。只读固定上游 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。

累计全文 **1231 / 1546文件、235982行**；partial13，not_registered302，excluded124。全仓通读未完成。完成时核验全部1670 tracked路径SHA-256、字节数与正数文本行数、本仓/上游HEAD、tracked/staged空diff、此前113份报告哈希、本报告链接行号、全文批次总计。只新建本报告并更新台账。
