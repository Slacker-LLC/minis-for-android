# 聊天列表时间传播、大内容保护与已有测试静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文6文件、1744行，确认2项P3时间展示问题。仅静态阅读，没有修改产品源码、运行测试/构建/模拟器/adb/Root/设备/云/模型，没有读取凭据。新建独立报告，旧报告不改。

## FC01 / P3：本轮起点变量从未赋值，工作耗时不从用户消息开始

[ChatFlatItems.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFlatItems.kt:661) 初始化turnStartedAtMs=0。全文中只有此声明和第757行读取，没有赋值。第682—690行遇到用户消息只添加bubble并continue，遗漏记录当前轮起点。第757行于是永远回退message.createdAtMs，即助手消息自身时间。

[WorkProcess.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/WorkProcess.kt:140) 优先用messageCreatedAtMs和messageUpdatedAtMs计算时长，其规则明确本轮从用户发送到最后回复写入。[ChatWorkProcessUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatWorkProcessUI.kt:283) 把结果绘制为“用时”。静态反例：已有持久用户消息时间100000、助手创建110000、助手完成120000，带工具步骤；列表构建传110000而不是100000，工作时长10秒，而整轮20秒。这里只确认口径与实现不一致，不说工具自身计时或执行超时被改动。

实际ChatScreen第2880行构建冻结前缀，第2961行以fromIndex=splitIdx构建实时后缀。所以处理方向不能仅在for循环的user分支赋值：后缀可能从助手开始，需要从fromIndex以前恢复最近用户起点，保持分段构建和整段构建一致。本轮未修改。

TurnWorkRowTest直接测试buildAssistantTurnEntries，验证工具间跨度，没有经过带用户消息的buildFlatChatItems；WorkProcessGroupingTest也直接测试分组helper。因此它们不覆盖这次时间传播遗漏，未运行。

## FC02 / P3：新消息UI对象未填时间，时间分隔行要等数据库恢复才出现

[ChatModels.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModels.kt:128) createdAtMs默认0、updatedAtMs默认null。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6792) 的真实发送路径已取得persistedUser，但构造userMsg只填id、正文、附件，没有填persistedUser时间；第6801行直接发布到_messages。第7904—7910行新建assistantPlaceholder也没有时间，现有消息重置分支保留原值。

[ChatFlatItems.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFlatItems.kt:676) 只为非system且createdAtMs>0的消息生成时间标签；零值消息不会被第678行上一消息时间回看选中。静态反例：打开空会话后正常发送第一条消息，发布的用户和新助手对象时间都为0，列表没有开场时间分隔。已有历史会话停顿超过一小时再发送，也不会因这个新消息产生预期的新分隔行。

持久化并未漏写时间：ChatRepository第412行appendMessage记录createdAt=now；[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12410) 的数据库恢复mapper明确填entity.createdAt和updatedAt。因此重开会话恢复后，时间标签可能出现，同一会话的实时展示与恢复展示不一致。没有声称数据库时间为0或消息未保存。

该缺口也使新建助手的工作时长暂时只能回退到工具内存计时，恢复后改用DB时间；它属于同一UI时间来源遗漏，不另计第三项。修复方向：实时对象沿用已持久回执的时间并在更新时保持正确结束时间，不能只用任意UI重组时的当前时刻掩盖缺失。

此项与上一轮TI01不同：FC02是时间未传入导致标签不生成；TI01是标签已生成但跨午夜不刷新，互不替代。

## 大内容保护核对

LargeContentGuard全文372行。frozen内容超过32000字符折叠，预览8000；stream超过8000或breaker激活显示4000字符尾部，结束后恢复正常渲染或折叠。明确告知降级，并未修改消息原文。ChatScreen第3625、3648、3825行分别对text、mdblock、legacy内容调用；真实常用markdown路径按fragment保护，不能把整条消息字符总和直接代入单fragment阈值。

展开为用户显式操作，export写完整content并通过FileProvider分享，返回true的含义仅为chooser启动，函数注释也明确如此，不把未获得收件App完成回执作为“谎报已收到”。导出在点击回调同步writeText，大文件可能阻塞UI，未实测耗时/卡顿，保留性能候选。cache临时文件没有就地清理，不能在缺少全局cache生命周期证据时断言永久泄漏。

formatByteSize以字符数近似字节数，中文可能低估真实UTF-8大小；这里源码明确是提示近似，未将其等同文件预算或正式存储统计。rememberSaveable展开状态在导航保留时可能继续存在，需要真实navigation saveState边界，未把注释声称“重开reset”直接作为缺陷证据。

## 列表等值、边界和候选

ChatFlatItems全文937行。AssistantMarkdownBlock第381—382行仅比较rawText/messageMarkdown长度，AssistantLegacyContent也如此。相同ID/flags下等长改写会被判相等；ChatScreen第2760行flatItems是默认mutableStateOf，列表相等时可抑制发布。WorkProcessRow比较content/title长度、状态和ID，也没有比较具体内容/参数/时间。

这是确切的比较局限，但当前正常流以追加为主，regenerate会先清空并改变streaming flags，不能只构造同长度对象就宣称用户重新生成必然卡住。尚需沿实际保留ID且仅等长改写的生产事件闭环，本轮保持候选。不同raw内容可被忽略这一点值得后续核对，未运行Compose或测试。

时间分隔先于phantom empty助手过滤，时间有效的空助手可能留下孤立时间行；实际恢复mapper第12399行会过滤空助手，所以本轮不凭builder人工输入断言生产已有孤立行。thinkingLevel=null的可见内容判断与currentThinkingEnabled可能不一致，但新助手实际捕获turnThinkingLevel，需要恢复/继续流时序才能判断是否出现空白等待，本轮不新增编号。

UiMessagesSubListCmeTest全文113行明确自身仅验证ArrayList.subList机制，不复现历史设备CME。没有将其注释内旧设备结论当本轮运行证据，也没有把复制列表当已证明根因修复。FlatItemsTimeDividerTest全文33行验证开场、一小时阈值与零时间，人工构造有效时间，未覆盖实际发送对象的FC02。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其ChatFlatItems没有turnStartedAtMs/TimeDivider/createdAtMs匹配，本轮时间功能无法以同函数上游行为作修复模板；不能说上游没有其它时间展示。其AssistantMarkdownBlock也以长度比较rawText/messageMarkdown，属于同源候选。

其LargeContentGuard相关阈值/折叠与导出writeText/chooser逻辑相同，字节显示也明确字符近似。仅片段比较，不声称完成所有上游聊天链路审查。与PRoot/minisd无关，不恢复退役路径。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [LargeContentGuard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/LargeContentGuard.kt:1) | 372 |
| [ChatFlatItems.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFlatItems.kt:1) | 937 |
| [FlatItemsTimeDividerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/FlatItemsTimeDividerTest.kt:1) | 33 |
| [TurnWorkRowTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/TurnWorkRowTest.kt:1) | 89 |
| [WorkProcessGroupingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/WorkProcessGroupingTest.kt:1) | 200 |
| [UiMessagesSubListCmeTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/UiMessagesSubListCmeTest.kt:1) | 113 |

全文输出完整；ChatFlatItems分1—320、321—640、641—937阅读。ChatScreen/ChatViewModel仅做相关片段复核，不升级partial覆盖。

累计全文 **1291 / 1546文件、247463行**；partial13，not_registered242，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前123份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
