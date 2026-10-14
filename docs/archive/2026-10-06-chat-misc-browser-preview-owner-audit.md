# 聊天辅助视图、浏览器实时预览归属与消息操作测试静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文3文件、1228行，确认1项P3预览归属问题。只读源码与已有测试，不改产品代码、不运行测试/构建/模拟器/adb/Root/设备/云/模型、不读取凭据。独立报告，旧报告不改。

## MV01 / P3：运行中的浏览器工具预览显示当前选中页，而非该调用的页

[ChatMiscViews.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatMiscViews.kt:855) rememberBrowserLiveSnapshot只验证toolName和运行状态，produceState按block.id/tabPool区分实例，却在第872、876行从tabPool.activeManager取图，没有解析工具目标tab_id或读取调用已获取的tab身份。

[BrowserTabPool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:147) activeManager按全局selectedTabId选择manager；第814行每次acquireTab把它改为新获取的tab。第585—624行支持显式tab独立锁及navigate/fetch默认并行分流；第673—686行实际执行使用获取到的tab.manager，并给执行结果盖真实tab.id。这说明执行页和当前选中页是两个可不同的身份，而非始终只有一个WebView。

静态交错：工具A仍在tab 1执行navigate，工具B取得tab 2并更新selectedTabId。此时A的实时preview在下一次轮询读取tab 2.manager，显示B的页面。即便没有工具B，BrowserSheet第463行用户选择标签调用selectTab，第974—977行也会更改selectedTabId。工具A执行本身仍用tab 1，结果ID也可正确，本项不说导航被路由错或截图被回填给模型。

真实显示入口 [ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:2407) 注入viewModel.browserTabPool；[ChatComposerWidgets.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatComposerWidgets.kt:693) 浮动工具缩略图与 [ChatToolDetailUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt:850) 浏览器详情都优先liveBitmap。因此错误不是未接线的helper：运行中的A标题/URL旁可显示B画面。PENDING也会poll，尚未获取tab的调用可能显示既有当前页；属于同一归属缺口，不另计编号。

方向：preview沿用真实工具执行的tab归属，尚未获取身份时显示明确占位；不能简单根据“当前选中页”作为工具证据。结束态已不poll、会回退自身保存截图，不泛化成所有历史截图串页。本轮没有操作浏览器或复现截图。

## 旧表格私有实现与现役摘要/恢复入口

ChatMiscViews全文1022行。BorderedMarkdownTable是private，第277行定义，全仓只有定义，没有调用；parseInlineMarkdown只被这个私有表格调用。其fallback按pipe拆分可能丢空cell、代码/强调支持有限，图片复制未检查compress返回值、使用通配grant，均不能在缺少现役调用时当真实表格用户缺陷。本轮没有删除死路径；它属于应继续区分的旧实现残留，与minisd/PRoot生产恢复无关。

FallbackInfoBlock为现役ChatScreen第3808行相关分支使用；detail以toolArgs非空显示，compact才提供onRevert。CompactSummarySheet正文SelectionContainer带verticalScroll且weight(1f)，底部撤回按钮位于独立区域，不把长summary误报按钮必然被挤出；复制反馈1500ms、撤回明确确认。真正撤回数据/状态的缺口已由此前context/recovery报告核对，本轮不重复。

ResumeBanner实际ChatScreen第3411行调用viewModel.resume，条件canResume且非streaming、无全局/末助手错误；crash warning是提示，按钮仍一次点击恢复，未凭历史注释要求新增确认。是否某类同时有error且可resume的状态缺入口需其它错误动作共同判断，不能只根据这一个banner条件断言全局无恢复。

SwipeToSendHint只是显示progress、提示文本与位置。progress/armFraction用于alpha clamp，isEnqueue切换“释放排队”，实际发送由外层手势管理；未将提示组件里无send调用误报未实现。

## 已有消息操作测试的真实覆盖边界

AssistantActionBarMoreActionsTest全文78行。删除行为用测试文件自己的SimpleMessage和deleteSingleMessage函数，没有调用ChatViewModel或ChatRepository；选择测试仅按TextShardId.messageId过滤，没有触发真实SelectionController。这些断言不能证明生产删除持久化或跨fragment选择正确。share测试实际调用MarkdownClipboard，但只测标题/强调/链接，未覆盖MC01/MC02的代码字面内容和---普通行，不重复计数。

AssistantMessageActionBarTest全文128行。按钮顺序断言比较测试自己构造的枚举列表，more菜单只数自建四项列表，不读取真实UI。其它测试确实调用FlatChatItem/ buildFlatChatItems验证action row key、生成位置和stream/error抑制。应分清测试自洽与生产数据转换覆盖，不能统称全部测试都是无效，也不能用按钮顺序测试证明实际视觉/点击顺序。未运行、未新增测试。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其ChatMiscViews第878—906行同样按block.id建state并从activeManager捕获，MV01为同源预览归属缺口。没有把上游单函数相同推断成其所有浏览器执行路由也相同，修复应沿本地真实tab获取链。

两次相关片段输出因预算截断，acquireTab更新选择、真实tab执行、UI选择与详情preview均已单独重读，未把截断输出登记全文；新增3份全文输出均完整。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [ChatMiscViews.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatMiscViews.kt:1) | 1022 |
| [AssistantActionBarMoreActionsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/AssistantActionBarMoreActionsTest.kt:1) | 78 |
| [AssistantMessageActionBarTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/AssistantMessageActionBarTest.kt:1) | 128 |

ChatMiscViews分1—340、341—685、686—1022读取；ChatToolDetailUI仅做850行附近调用片段，不升级覆盖。ChatScreen仍partial。

累计全文 **1302 / 1546文件、250695行**；partial13，not_registered231，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前126份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
