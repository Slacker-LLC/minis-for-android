# 助手消息、思考全文入口与流式跟随静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文3文件、1257行，确认3项P3思考内容显示问题。只读源码与已有测试，不改产品代码、不运行测试/构建/模拟器/adb/Root/设备/云/模型、不读取凭据。独立报告，旧报告不改。

## 现役范围

本轮ThinkingBlock问题限定单独步骤呈现路径。[ChatInputSettingsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ChatInputSettingsScreen.kt:233) 用户关闭分组即可切换PER_TOOL；ChatFlatItems在PER_TOOL产生AssistantThinking，[ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3687) 实际调用ThinkingBlock。默认GROUPED使用另一个工作过程内容渲染，不把本轮三个缺口泛化成默认分组视图都相同。

## AT01 / P3：8,000至100,000字符思考只显示尾部，组件内没有全文入口

[ChatAssistantMessageUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt:648) 设thinkingWindowSize=8000、thinkingHardCap=100000。第715—729行当内容超过window时只取尾部8000附近字符（换行截点最多额外移200字符），第788—797行提示“显示最后…”。第682—685行“查看全文”入口只在overHardCap，也就是超过100000时显示；第814—819行全文dialog也仅在overHardCap&&showFullContent时创建。

静态场景：冻结思考有20000字符，展开最多看最后8000，前12000无法通过这个思考组件的滚动或点击查看。没有显示全文按钮，也没有该文本的复制/导出入口；到100001字符反而出现全文入口。截断是性能保护，不是问题本身，问题是中间长度区间缺少与大内容区间一致的可取全文路径。

原始block.content未修改，数据库/历史导出可能保留原内容，故不声称永久数据删除或任何地方都无法取得。方向：保持小窗口渲染，把现有全文viewer入口覆盖所有被截断的内容，无需放弃长度保护。未修改实现。

## AT02 / P3：流式思考的全文窗口只在创建时写入文本

[ChatAssistantMessageUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt:850) AndroidView的factory创建ScrollView/TextView，第859行只在factory设置text=content；没有update回调。外层content变化、主题变化不会通过此factory重跑来更新已创建的TextView。

真实流式增长 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8334) 每个ThinkingDelta以copy(content=turnThinking.toString())替换同ID块，随后updateAssistantMessage发布。ThinkingBlock在isStreaming时也允许超过100000字符打开全文dialog，第819行将最新block.content传入dialog，但AndroidView保留旧View。

静态交错：思考到100001字符时点查看全文，View显示这一刻文本；随后同一个thinking块继续增长到120000，dialog仍显示100001版本，没有追加最后19999。关闭再打开可能重新创建View并拿到新内容。只确认已打开窗口未更新，不说原思考停止增长。方向：沿AndroidView既有update边界同步内容并保持用户阅读位置，避免每次整段增长强制拉回尾部。

## AT03 / P3：自动跟随监听读取普通不可变字段，后续流式增长不会重新发出

[ChatAssistantMessageUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt:767) LaunchedEffect只key(scrollState,block.id,isStreaming)，第769行snapshotFlow读取block.content.length。[ChatModels.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModels.kt:262) AssistantBlock是data class、content为val String；增长是ChatViewModel第8334行copy新对象，不是修改可观察snapshot state中的同一字段。

因此effect首次运行的closure持有当时block，读取其普通String长度不产生可观察状态依赖；之后同ID、isStreaming仍true的重组不重启effect，也不会把旧closure换成新block。snapshotFlow仅首次发出，不会按每个新token增长执行第777行scrollTo。scrollState的maxValue变化也不是该snapshotFlow的读取项。

静态场景：同一思考块展开，文本不断增长并超过300dp内层可见高度，用户尚未手动滚动离开；按源码自动跟随动作只在effect开始时发生一次，后续增长没有触发。用户手动操作/块切换等可能重启或改变位置，不能说所有滚动永远无效。实际设备滚动效果未运行；确认的是自动跟随事件源与不可变更新方式不一致。

方向：用最新可观察内容/布局状态驱动跟随，保持当前userScrolledAway门槛；只把更多key加到记忆内容不解决旧effect监听源。未运行或新增测试。

## 工具行、错误和助手动作核对

ChatAssistantMessageUI全文1093行。BoundsTrackedBlock登记window坐标并在dispose按msg/slot删除；内容更新的坐标回调是否必定重发仍需实际布局边界，不升级上轮消息归属候选。

InlineErrorBanner将正文限制3行但长按可复制完整error；retry设置localRetrying=true后没有在callback失败/未启动时恢复。真实父层调用retryLast，是否拒绝而保持同error需追踪所有前置状态，保留候选，不凭Unit回调断言已经可复现永久禁用。

ToolCallPill PENDING/RUNNING/STREAMING显示spinner，stop调用父层全局cancel，详情与长按复制回调真实接线；formatToolDetailsForClipboard JSON格式化失败保留raw，输出name/id/input/status/result，并未只复制截断title。旧toolIcon名称匹配属于WP01同类，不重复编号。

AssistantMessageActionBar全文已读，44dp触摸框与两套copy/more菜单明确分工。isGenerating旋转无独立计时器，不据动画形状断言生成逻辑卡住。ChatScreen第3848行仅READING算isSpeakingThis，而toggleReplySpeech把READING/PAUSED都视为当前朗读；暂停时按钮图标与点击停止语义可能不一致，需其它长按动作共同核对，保留候选。

## 已有测试与OpenMinis对照

RegenerateAssistantMessageTest全文107行：前五项用测试自己的SimpleMsg/resolvePrecedingUserMessageId，不调用真实regenerate；最后四项调用ChatViewModel.canRetryMessage检查空文本和provider gate。不能用前五项证明生产截断/持久化/重试正确，也不统称所有断言无效。DeleteFromMessageCommitTest全文57行确实调用runAfterDatabaseDelete验证DB先行、失败与取消不提交UI状态，是有效helper边界；它不证明所有真实删除调用都使用helper或真实Room事务行为。未运行。

只读固定 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其ChatAssistantMessageUI第1090—1092行相同8000/100000门槛，第1277行仅hard cap全文窗口，第1235行相同snapshotFlow block.content.length，第1326行AndroidView factory写text=content。这三个实现缺口为同源保留；本轮不声称已经核对上游每条流式调用路径。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [ChatAssistantMessageUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt:1) | 1093 |
| [RegenerateAssistantMessageTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/RegenerateAssistantMessageTest.kt:1) | 107 |
| [DeleteFromMessageCommitTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/DeleteFromMessageCommitTest.kt:1) | 57 |

全文输出完整，AssistantMessageUI分1—330、331—660、661—940、941—1093读取。旧AppearanceSettingsScreen路径不存在后已定位现役ChatInputSettingsScreen；未把路径不存在当设置未实现。ChatScreen/ChatViewModel仍partial，相关片段不升级。

累计全文 **1305 / 1546文件、251952行**；partial13，not_registered228，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前127份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
