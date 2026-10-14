# 聊天时间标签、指示器与辅助组件静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文6文件、662行，确认1项P3显示问题。仅静态阅读，没有修改产品源码、运行测试/构建/模拟器/adb/Root/设备/云/模型，没有读取凭据。新建独立报告，旧报告不改。

## TI01 / P3：跨午夜后，保留中的时间分隔标签不会更新今天/昨天

[ChatIndicators.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatIndicators.kt:215) 的TimeDividerLine用remember(epochMs)缓存整个格式化字符串，第217行只以消息时间为缓存键。第232—241行的formatChatTimestamp却依赖当前日期：System.currentTimeMillis决定今天/昨天，Calendar与DateFormat使用当前默认时区和格式。这些依赖不会成为缓存失效条件，也没有驱动日期变更的状态或定时刷新。

真实入口 [ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3811) 直接把item.epochMs交给TimeDividerLine。[ChatFlatItems.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatFlatItems.kt:504) 的条目key为time:messageId，第680行时间取message.createdAtMs；ChatScreen第3428—3430行按该稳定key渲染列表。因此普通消息状态更新或列表重建仍可以保留同一条目与同一epochMs，不能保证重新格式化。

静态反例：23:59展示当天消息，标签含“今天”；保持该条目在composition中跨过午夜，即使父层再次重组，remember(epochMs)仍返回原字符串，继续显示“今天”，而同一formatter若重新调用应返回“昨天”。继续保留一天也会沿用同一相对日期。这里只确认条目保留期间的缓存错误；条目滚出后被销毁、重新进入页面或Activity重建时可能重新计算，不宣称永久错误。没有通过模拟器复现。

方向：让相对日期格式化订阅实际日期变化，缓存键包含相关日期/时区依赖；不能只去掉remember而没有刷新触发，因为午夜本身不会自动引发Compose重组。不需要修改消息持久时间。

## 其它组件核对与候选边界

ChatToolFormatting全文121行：图标、颜色与标题仍匹配旧shell/file等名称，现役linux.file.*可能落入默认展示。这与上一轮WP01旧工具身份匹配属于同一类，本轮不另加编号。时长短格式有取整边界，未据此断言执行时长损坏；共享SimpleDateFormat是否有并发调用，需要线程调用证据，本轮不计入确定问题。

MessageBoundsRegistry全文64行：markdownAt只按选择框纵向中心匹配条目，第47—49行没有验证整段选择属于同一消息。实际MinisMarkdownTextToolbar第124行使用该结果；跨消息选择是否能进入该路径、具体选择框语义与复制行为还需完整选择控制链核对。没有仅凭注释宣称已确认复制错误，也没有把未使用的markdownFor当生产故障。

AlwaysStretchOverscroll全文49行：外层noop scroll返回零，用于内容不足时仍有拉伸效果，内部effect透传；未确认重复附着或实际滚动失效。

AudioWaveformView全文69行：levels为空提前返回，正常数值夹到0—1；实际ChatScreen第5088行传入识别器audioLevels且heightDp=28。未用不存在的负高度调用制造崩溃，也未把过密柱形宽度作为未经界面验证的确定问题。

ReplySpeechControlBar全文116行：ChatScreen第4224—4228行实际接入replySpeechState、暂停和关闭回调，不能说控制条未接线。完成态按钮与isActive的真实状态生命周期未在本轮完整追踪，不另加“完成后暂停”问题。

ChatIndicators全文243行：压缩进度每秒更新并保留取消回调；Soul名字来自可观察flow。跳动点使用padding/offset的布局动画，与上游图层动画不同，可能增加重组/布局工作，但本轮没有性能实测，不报确定卡顿。工作状态固定文字是展示策略，不推导出执行状态丢失。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其ChatIndicators.kt已完整查看：没有TimeDividerLine/formatChatTimestamp，所以不能说TI01是直接沿用的同函数上游缺陷，也不能推断上游没有其它时间标签。其跳动点采用共享decorative tick与graphicsLayer.translationY，提供静态实现差异；没有据此要求把未经测量的动画全部改写。tree存在MessageBoundsRegistry同路径文件，本轮只确认存在，没有声称完成内容对照。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [ChatToolFormatting.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolFormatting.kt:1) | 121 |
| [ChatIndicators.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatIndicators.kt:1) | 243 |
| [MessageBoundsRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MessageBoundsRegistry.kt:1) | 64 |
| [AlwaysStretchOverscroll.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/AlwaysStretchOverscroll.kt:1) | 49 |
| [AudioWaveformView.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/AudioWaveformView.kt:1) | 69 |
| [ReplySpeechControlBar.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ReplySpeechControlBar.kt:1) | 116 |

全文输出完整。ChatScreen、ChatFlatItems和文本选择工具栏仅做相关片段核对，不升级覆盖状态。两次限定路径搜索无匹配/不存在路径后已用真实ChatFlatItems与实际调用点核对，不把搜索失败当功能缺失。

累计全文 **1282 / 1546文件、244846行**；partial13，not_registered251，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前121份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
