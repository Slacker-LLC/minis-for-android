# 聊天顶部操作、列表构建效果与工作行测试全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。四文件全文静态阅读；未修改产品代码、运行测试、构建、模拟器、设备、Root或云资源。旧210份报告与所有旧台账保留。本轮没有新增确认缺陷。

## 列表构建与跟随边界

FlatItemsBuildEffect以messages、sessionId、步骤展示和thinking开关重启；combine固定messages与live streaming，conflate/sample每80ms发布。首个stream overlay位置以前取take快照到Default构建，后缀使用完整merged列表及fromIndex/seedKeys构建，保持跨边界回看和key去重的设计入口。split位置相同时复用冻结rows；正常结束/取消都结算perf状态。

这证明构建不全在主线程，不能仅因大列表存在宣称同步构建ANR。但buildFlatChatItems的所有前后邻接条件尚未在当前版本全文复核，冻结前缀与整段构建的等价性仍待该文件阅读。旧flat时间传播问题也不能只靠effect层宣布解决。

冷预热并行读取最多16个Markdown fragment、累计字符预算96000；预算在加入前检查，因此最后一个fragment能让总量超过预算。是否有实际超大单fragment需结合fragment切分/预热helper，暂列性能边界候选，不凭数字差异认定崩溃。

TrailingPinEffect只针对末尾tool/typing/work-process/queued bubble，每key一次，检查userScrolledAway、滚动中及1000ms中断宽限；还要求发送宽限或正在streaming。不会每次文字增长都无条件置底。因effect只以flatItems为key，宽限到期本身不重新触发，后续是否由实时tick或其它跟随effect补上，需完整scroll文件，不另报所有流式跟随失效。

## 顶部与基础组件

ChatTopBar用isBlankDraft抑制无意义新草稿，显示当前Bot名与状态；浏览器/会话文件/虚拟屏及清空确认通过已有回调和状态。未把点击菜单等同已经执行清空，clearChat行为见前批Edit全文审查。

ChatScreenConstants提供附件50上限、发送/结束宽限、字体比例调整的slash picker高度、scrollbar、thinking badge及sheet。scrollbar基于可见项平均高度估算，仅供指示，未把估计值当精确文档位置。ThinkingLevelSheet prepend OFF并滤掉已有OFF；其它available重复项没有distinct，但调用方是否提供重复需进一步核对，不凭注释称全部去重或登记确认UI缺陷。

## 已有测试的真实范围

TurnWorkRowTest四例真实调用buildAssistantTurnEntries，当前预期叙述single与各段process交替，而非注释所称把全部叙述折进单一work row；源码断言定义当前测试意图。另核对只有最后process携带turn clock/live、纯text无process、结束在tool时保留开头text。

未运行该测试，也没有测试整个Compose冻结/实时合并、滚动或设备布局。注释噪音和历史描述清理由Claude处理，本轮不将它们计作新问题。ChatFlatItems仅rg定位，未计全文；其完整渲染数据流继续待读。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenTopBar.kt | 196 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenFlatItems.kt | 317 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenConstants.kt | 388 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/TurnWorkRowTest.kt | 98 |

四个现版文件完整读取。新增全文4文件、999行；已全文重读0文件。其它rg定位不计全文。

累计全文1327 / 1758文件，201033行；partial 4，未登记427。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-flat-effects.json)。

校验旧210报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
