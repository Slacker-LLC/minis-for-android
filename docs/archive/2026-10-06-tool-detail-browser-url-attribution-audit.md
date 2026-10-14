# 工具详情页、浏览器地址归属与分页静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文1文件、1689行，确认1项P3。仅静态阅读，不改产品源码、不执行测试/构建/模拟器/adb/Root/设备/云/模型，不访问账号或凭据。新建独立报告，旧报告不改。

## TD01 / P3：浏览器详情会把非浏览器工具的URL显示为当前页面，顶部按钮却打开另一个地址

[ChatToolDetailUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt:833) resolvedUrl在当前记录没有browserURL和args.url时，从此前所有工具记录回溯browserURL或toolArgs.url；第839—842行没有检查prev.toolName。URL胶囊第926行还允许复制该地址。

顶部操作的browserActionUrl在 [ChatToolDetailUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt:381) 同样回溯，但第388行显式跳过非browser_use记录。[ChatToolDetailUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt:418) 点击时将该地址交给onOpenBrowserForUrl。因此不是两个位置共享同一个结果：正文可能显示并复制B，顶部按钮却打开A。

静态可达反例：同一聊天依次有browser_use访问A、android.web.fetch读取B、browser_use get_text（不带url）失败并返回无pageURL的结果。正文回溯最近的fetch参数显示B；顶部跳过fetch找到A，点击打开A。fetch不改变浏览器页面，B不能作为该次浏览器调用的页面证据。

真实接线：[AndroidSystemTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:377) AndroidUrlFetchHandler声明android.web.fetch及必填url；[MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:658) 将它注册到现役ToolRegistry。[ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3989) lastToolBlocks汇集所有assistant工具，而不是仅浏览器；第4101行将其传给详情页，第4106行回调打开session浏览器。

无pageURL的失败也有真实来源：[BrowserTabPool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:654) 同tab锁等待超时直接返回BrowserActionResult.error，没有经过第686行正常dispatch的pageURL填充；[BrowserActionResult.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserActionResult.kt:38) error保留pageURL默认null。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9972) 将结果pageURL传回，[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9546) 更新工具记录。这里只推导满足该条件时的界面归属错误，没有制造锁竞争或实际联网。

作用域限于当前记录URL缺失时；成功dispatch通常已携带真实pageURL，不声称所有浏览器详情都错，也不声称工具执行访问了fetch地址。修复方向：两个显示入口复用同一个浏览器记录限定的地址解析，并明确继承地址不是失败调用的实测结果。本轮不实施修改。

## 截图缓存疑点收敛：不计新缺陷

[ChatToolDetailUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt:862) savedBitmap的produceState key没有block.id，看起来可能在两个imageFilePath=null的记录之间沿用旧截图。但底部上一条/下一条第1280、1330行每次只移动一个索引；两条相邻无截图记录通常共享同一个最近截图。要跨过另一条有截图记录，正常逐页组合会改变imageFilePath key；切到非browser_use分支也会移除该producer。未证明正常单步分页能稳定复现“不同最近截图却key不变”，所以撤下这一初步疑点，不把它写成确认问题。

快速多次点击在一帧内合并、历史列表变动等仍需具体可达证据，未借假设补计数。此前MV01已确认的activeManager实时截图归属问题保持原报告；这里没有重复编号。

## 其余全文核对

- 旧shell_execute/file_read/file_write/file_edit名称分支与canonical工具的落差，归入既有WP01；file_edit参数预测成功外观归入WP02，不新增数量。工具详情保留旧名称不能证明minisd/PRoot恢复生产路径。
- default分支有SelectionContainer、URL linkify及增量展示；EditorCard自身建立selection作用域。read_image异步解码，浏览器图片按bitmap比例展示。没有把IO解码误写为主线程同步解码。
- LazyRevealToolText按bodyText重置展示窗口，分40行、首批最多5块，load more/all可继续。10KiB阈值在加入整块后检查，单长行可能超过；这是软阈值而非严格字节上限，未实测卡顿，不新增OOM/ANR结论。
- 同一详情页共用outputScrollState，分页未显式归零；大结果之间可能延续滚动位置，保留交互核对候选，不以偏好直接判为数据丢失。
- SubAgentDefinition.TOOL_NAME真实接入SubAgentCard，解析失败退回普通输出；现有stop/steer/resume入口已在此前报告复核。静态检查不触发子Agent、终端或网页。

## 固定OpenMinis对照

只读固定 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游同路径第378—400行的顶部回溯同样限定browser_use，第841—862行正文回溯同样未限定；相同URL归属差异也存在。对照仅声明相关片段，不把上游文件登记为本仓全文覆盖。

上游截图分支使用DisplayBitmapLimits.decodeFileBounded，本地直接BitmapFactory.decodeFile；这与TD01是不同维度。是否需要统一显示像素预算须结合图片产物边界，未把实现差异自动当已发生内存崩溃。曾有一次整份上游输出截断，相关地址/截图区间随后单独读取，不以截断输出声称全文审阅。

## 覆盖与完整性复核

新增全文 [ChatToolDetailUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt:1)，1689行，分1—330、331—660、661—995、996—1325、1326—1689完整阅读。相关调用方仅片段复核，不重复全文计数。没有运行Compose、解码真实截图、执行任何被审测试或联网工具；TD01是真实接线支持的静态反例，设备画面尚未采集。

累计全文 **1319 / 1546文件、255245行**；partial13、not_registered214、excluded124，全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256/字节数/正数文本行数、两仓固定HEAD、tracked/staged空diff、此前129份报告哈希、新报告绝对链接与行号、全文批次总计。只新增本报告和更新覆盖台账。
