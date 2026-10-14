# 屏幕控制、文字后备输入与动作证据静态审计

日期：2026-10-05。源码基线：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮独立归档，不向旧报告补写。

本轮新增 **6 项 P2 问题**，其中剪贴板后备输入和坐标默认值不一致有明确的数据流反例；其余问题涉及不完整观察、读取预算、滚动证据与排队触摸的目标归属。只做静态排查：没有修改产品代码，没有运行构建、测试、模拟器、adb、Root 或设备任务。

## 新问题索引

| 编号 | 问题 | 影响 |
|---|---|---|
| SC01 | 后备粘贴把完整结果当成插入片段 | 非空输入框可能重复原文；整值替换、清空也不能保证 |
| SC02 | 同一工具的默认坐标空间随显示路由改变 | 虚拟屏缩小截图后的点击、滑动可能发生倍数偏移 |
| SC03 | 物理屏文本等待忽略窗口完整性和扫描深度限制 | 读取失败或搜索不到的文本被当成已消失 |
| SC04 | 过滤观察只有输出预算，没有节点读取预算 | 不匹配的节点仍全部遍历，持锁时间和 Binder 工作量失控 |
| SC05 | 滚动锚点截断后仍用局部唯一性推断运动 | 可能把错误配对当成已观察到的滚动或方向错误 |
| SC06 | 排队触摸在发送时改用当前显示 ID | 换屏后，原屏幕的积压手势可能被送到新屏幕 |

## SC01 — 剪贴板后备没有保持“替换整值”和“插入片段”的区别

[AndroidUiController.insertText:414](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:414)根据原值、插入片段和当前选择区计算 plan.text。这是编辑完成后的完整字符串。直接 ACTION_SET_TEXT 写入它是正确的；但该动作拒绝后，[428 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:428)把同一个 plan.text 交给 pasteViaClipboard。

[pasteViaClipboard:470](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:470)仅设置剪贴板、请求焦点并 ACTION_PASTE，没有设置全选或恢复指定选择区。Android 普通编辑框粘贴替换的是当前选择区，而不是整个字段。该平台语义已核对固定版本的 [AOSP TextView ACTION_PASTE 实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/widget/TextView.java)及 [TextViewOnReceiveContentListener.replaceSelection](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/widget/TextViewOnReceiveContentListener.java)。

明确反例：原文 `abcd`，光标在 2，插入 `XY`。plan.text 是 `abXYcd`；目标编辑框拒绝 ACTION_SET_TEXT、支持正常粘贴且保留光标时，后备把 `abXYcd` 插入原文，结果为 `ababXYcdcd`，不是 `abXYcd`。后续 placeCursor 再把光标移到计划位置，不能撤销重复内容。

[setText:359](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:359)也采用相同 helper：已有 `abcd`、光标在末尾，要求 set_text=`XY`，后备可能成为 `abcdXY`。要求清空时，粘贴空串同样不等于删除整个字段。

verifyNodeText 可以返回 false，证据层会标 TEXT_UNVERIFIED；因此不是声称系统已验证文本正确。缺陷是后备执行了不同的编辑，并且外层仍以粘贴被接受作为 success。[TextEditPlannerTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/accessibility/TextEditPlannerTest.kt)和 [ClipboardRestorePolicyTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/accessibility/ClipboardRestorePolicyTest.kt)分别覆盖字符串规划与借用剪贴板标签，没有覆盖实际后备编辑。

处理方向：插入后备保持原选择区并只粘贴 insertedText；整值后备只有在全选得到确认后才粘贴整值。不能确认选择区/全选时明确拒绝。复用现有编辑和动作边界。

## SC02 — 工具说明默认 screenshot，虚拟屏默认 screen

[AndroidAgentTools.kt:105](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:105)定义同一个 android_ui 参数：coordinateSpace 默认 screenshot，表示最近截图的像素。物理屏 [click:320](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:320)及 [scroll:543](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:543)遵守 UiCoordinateSpace.DEFAULT。

虚拟屏 [coordinateAction:286](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:286)与 [scroll:317](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:317)却用 `optString("coordinateSpace", "screen")`。该工具的统一参数说明没有说明默认值因显示路由而变化。虚拟屏截图又确实支持 [scale:447](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/VirtualScreenUiBackend.kt:447)。

例如实际屏幕 720×1600，取 scale=0.5 的 360×800 截图。按工具定义省略 coordinateSpace，发送截图中心 x=180、y=400，预期实际中心 360、800；虚拟屏却把它解释为实际 180、400。滑动的起点及 delta 也采用同一错误默认值。没有截图时，默认操作本应按统一说明拒绝，虚拟屏却可以直接按 screen 接受。

处理方向：统一省略参数时的规则，并让工具说明、物理/虚拟执行器及结果元数据一致。显式 coordinateSpace=screen 或 screenshot 的换算路径已存在，不需要另建转换层。

这与上一轮 VS04 的尺寸缓存错误不同：本项即使缓存尺寸完全正确，仍会因为默认空间不同发生偏移。

## SC03 — 文本消失等待绕过了窗口可观测性判断

[AndroidUiController.waitFor:767](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:767)调用 service.rootNodes().any，然后以 `present != disappear` 确认匹配。[containsText:793](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:793)对 null 或超过 30 层的节点返回 false，没有“未完整搜索”的结果。

[MinisAccessibilityService.rootNodes:215](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/MinisAccessibilityService.kt:215)只返回 visibleWindowSet().roots，丢弃 complete、blockedUnknownWindows 和 unreadableWindows。平台窗口列表无法读取且 active root 也不可读时，返回空 roots；窗口集合原本已经标为 incomplete，但 wait 没有使用这个信息。因此 `mode=disappear` 可以第一轮就报告 matched=true，而实际只是看不到窗口。超过深度上限的文本也有同样的错误结论。

相比之下，[observe:147](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:147)会调用统一 windowSetRefusal；[waitForPackage:824](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:824)会把未知前台交给 PackageWaitPolicy，未知不计为匹配。已有失败关闭基础设施没有被文本等待复用。

处理方向：等待区分匹配、确定不匹配与未知；检查窗口集合并保留树搜索完整性。看到一个匹配可以证明 appear，未完整搜索不能证明 disappear。物理屏的本入口单独记录；旧 DC03 是 guest CLI 的逐窗口 any 判断，上一轮 VS06 是虚拟屏响应预算和错误传播，都不是这段控制器实现。

## SC04 — 文本过滤后，观察遍历失去有效的读取上限

[AndroidUiObservationRegistry.observe:133](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiObservationRegistry.kt:133)首先用 fingerprint 扫描最多 2,000 个节点，这是有限的；但随后的 walk 是另一遍读取。

[walk:139](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiObservationRegistry.kt:139)仅检查输出 nodes.length() 与 maxDepth。节点不匹配过滤器，或者不满足 interactiveOnly/可见条件，就不加入 nodes，却仍遍历其子节点。maxNodes 在工具说明中确实是“最大返回节点数”，不能把输出限制误认为读取限制。

结果是：在不超过 maxDepth 的宽树中，查询一个不存在的 textFilter/resourceIdFilter，返回数一直为 0，整个树都会被读取；fingerprint 已经 truncated 也不会终止这一遍观察。2,000 节点 fingerprint 上限没有保护这段 walk。节点数量受当前 UI 树大小影响，没有本方法统一的读取计数或截止时间。

observe 本身带 @Synchronized，resolve、changeEvidence、sampleAnchors 共用同一对象锁。大量节点的同步 Binder 读取既拖慢本次 observe，也阻塞其它需要该注册表的请求。当前工程没有直接覆盖 AndroidUiObservationRegistry 的设备集成测试，不能由 UiGenerationFence 的纯策略测试证明这里有界。

处理方向：把读取计数/期限与输出计数分开，达到读取边界时保留截断状态；不为填满输出而继续无限缩窄查找。维持现有有限 fingerprint 与 ref 拒绝策略。

## SC05 — 锚点采样截断后，局部唯一匹配仍被用作运动证据

[sampleAnchors:346](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiObservationRegistry.kt:346)最多扫描 2,000 个节点。队列未空时也直接根据已看到的 positions.size==1 返回锚点，没有当前采样完整性标记。

[identityKey:363](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiObservationRegistry.kt:363)由窗口、包、类、资源 ID、文本、描述和 password 组成，没有 uniqueId。扫描前半段只看到一个同键节点，不能证明其在全树唯一；重复节点可能位于预算之外，也可能前后采样分别看到不同的同键节点。

物理屏 resolve 会先对不完整 fingerprint 拒绝 ref，因此不把这里说成物理屏目标点击已经绕过完整性校验。可达条件是动作开始前树完整，动作后的 UI 扩展超过预算，且没有可用 scroll event delta；[scrollNode:643](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:643)就把不完整的后采样与前采样配对。两组同键候选都产生一致位移时，[RootScrollMotionContract.kt:17](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/RootScrollMotionContract.kt:17)的两锚点/方向一致规则仍可能通过，不能恢复丢失的身份唯一性。

[AndroidUiActionEvidence.ofScroll:110](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiActionEvidence.kt:110)会把 MOVED_BY_ANCHOR_MOTION 升为 ACCEPTED_WITH_EFFECT，或把反向位移报告为 DIRECTION_MISMATCH。错误身份配对因此进入了对模型的动作证据，不只是诊断细节不准。

处理方向：截断采样不得声称基于普通身份的全局唯一性；无法可靠配对时退回 UNVERIFIED。锚点来源与唯一身份需保持可核验，继续使用已有运动合约。此项是物理屏动作证据链，上一轮 VS02 是虚拟屏目标执行链，未将二者重复计为同一个点击问题。

## SC06 — 队列只保留坐标，发送时才决定触摸哪个显示

[VirtualScreenTouch:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenView.kt:44)只有 action/x/y/downTimeMs，没有 displayId 或显示实例。View 在收到触摸时计算坐标并 [入队:154](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenView.kt:154)。

[VirtualScreenViewer.kt:110](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenViewer.kt:110)的发送回调到工作线程实际处理事件时才读取 client.displayId，并以这个当前 ID 调用 touch。[TouchPump:51](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/VirtualScreenView.kt:51)使用无容量限制的队列；MOVE 的合并发生在消费时，屏幕切换没有清空或废弃旧事件。

具体条件：预览屏 A 的 DOWN/MOVE/UP 已入队，系统注入较慢；这期间 A 被关闭，Agent 或用户打开 B，client.displayId 已更新。剩余事件发给 B，使用 A 的坐标与 downTime。服务校验当前传入 ID 属于活动显示，只能确认 B 合法，无法知道这组事件来自 A。坐标落在 B 范围内时，不会因越界被拒绝。

这是排队动作的目标归属问题，与 VS07 的预览订阅没有重建不同：修好画面订阅并不能给旧队列补上目标身份。stop 也仅中断线程，没有对已开始的手势生成 CANCEL，作为同一触摸生命周期的后续核对点；没有本轮设备证据证明持有按下状态的具体 OEM 后果。

处理方向：在生成事件时绑定显示身份，并让整组手势归属该身份；换屏、关闭或停止时废弃旧队列并妥善终结手势。队列需要有界背压，同时保留有效手势 DOWN/UP 的顺序，不能只在发送时换用最新 ID。

## 上游对照与排除项

固定 openminis 提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。完整 tree 检索没有本轮 AndroidUiController、AndroidUiObservationRegistry、UiCoordinateSpace、TextEditPlanner、ClipboardRestorePolicy、VirtualScreenView 等同名文件。上游存在 MinisAccessibilityService；本轮核对其 rootNodes（121–131）是旧窗口/active-root 收集，其 setNodeText（268–273）只提供 ACTION_SET_TEXT，没有当前控制器的 plan.text 剪贴板后备。

因此只把这些新增链路按当前实现审查，不能将 SC01 等直接归因为 openminis 的同一缺陷，也不能因没有同名文件就推断其它上游完全没有对应功能。上游只读片段不计入本项目全文覆盖。

已核实而未列为问题：物理屏 ref resolution 合并旧/新 fingerprint 截断状态并严格拒绝，不存在上一轮虚拟屏实时搜索截断的同一直接绕过；文本序列化对 password 节点的 text/description 有排除，不能把旧 guest CLI 的泄露结论直接套在原生 observe；显示路由对畸形、负值及非当前 ID 有明确拒绝；无人值守物理屏默认拒绝；ClipRestore 的标签检查避免在用户复制其它内容后覆盖它。

MinisAccessibilityHealthProvider 校验 system UID、方法名和协议版本；该文件未发现新的已确认缺陷。RestrictedSettingsManager 的 API 版本门槛及清除缓存与真实 appops 的一致性，保留为后续核对线索，未仅凭测试注释升级为问题。

VirtualScreenView 的 Binder/UI 线程交接、detached 后迟到帧、HardwareBuffer 绘制生命周期需要真实设备或明确调度证据，本轮不据理论并发直接声称已经崩溃。UiCoordinateSpaceTest 明确允许连续坐标落在宽/高边界，未将这一既有选择单独计为新缺陷。

## 待执行的端到端验收提纲

以下均未执行；仅为后续获授权修复时的验收范围。每项保留操作参数、前后实际内容、结构化响应、显示身份和步骤记录作为可重复工件。

1. 在保留正常粘贴行为、拒绝 ACTION_SET_TEXT 的非空编辑框中执行插入、选择区替换、整值替换及清空；确认目标内容精确满足请求，剪贴板恢复也正确。
2. 同一工具分别对物理屏和虚拟屏做缩小截图后的无 coordinateSpace 点击/滑动，并覆盖显式空间；默认行为与说明一致，缺截图时正确拒绝。
3. 在文本仍存在时制造窗口列表/active-root 读取失败，及超过支持搜索深度的目标；disappear 应未知或继续等待，不能提前匹配。
4. 对大宽树执行完全不匹配的过滤观察，保留读取量、截止时间及截断响应；另一会话的注册表操作不应被无限阻塞。
5. 动作前完整树、动作后超过锚点预算，放置两组重复身份并不给可用滚动事件；运动证据不能由局部唯一的错配升级为已验证效果。
6. 在触摸积压时关闭 A 并打开 B；旧手势不得落到 B。覆盖停止、持续注入延迟、队列上限和手势 CANCEL，保留输入目标 ID 证据。

## 全文覆盖与验证边界

本轮新增全文覆盖 **36 个文件、3,911 行**，其中 AndroidUiController 从 partial 升为 full。累计 **560 / 1,546** 个纳入范围的 tracked 文件全文阅读，另有 **17** 个片段阅读、**969** 个尚未全文登记；累计全文 **115,154 行**。全库 1,670 个 tracked 文件清单和 124 个排除项不变，全库审计尚未完成。

具体文件、SHA-256、行数和读取范围见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。本轮完整复读已登记的 PackageWaitPolicy 40 行，不重复增加全文分母；关联的 MinisAccessibilityService、VirtualScreenViewer、VirtualScreenUiBackend、AndroidAgentTools 等只回查片段，不声称本轮再次阅读全文。所有既有测试均只阅读，没有运行或新增。

只新增本报告并更新覆盖台账，旧归档文档保持原内容；tracked 产品源码、HEAD 和全库文件哈希在本轮交付检查中核对。本文的静态触发条件不替代 Android/OEM 真机结果，也不代表问题已修复。
