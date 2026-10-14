# 文件提及缓存、扫描预算与修复边界

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。完整静态阅读2文件、578行。旧177份报告及全部台账原样保留，另建本文与台账。未修改代码、编写或运行测试、扫描用户文件、构建、模拟器/adb/Root/设备/云操作。本轮没有新增独立缺陷编号。

## MI05 Guest部分失败与挂载变化已有处理，外挂读取失败仍遗漏

[FileMentionIndex.kt:111](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L111) cacheUsable要求同session、TTL内且有entries。缓存命中后仍异步读取mountsKey，与上次名字/host root路径集合比较，变化则重扫。原报告所说“命中缓存完全不看挂载变化”已不成立。

[FileMentionIndex.kt:236](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L236) info异常及[FileMentionIndex.kt:295](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L295) guest list异常设置scanHadFailure，CancellationException重抛；当前token的finally在失败时清lastScanAt。Guest瞬时IO失败的原十分钟冻结路径已补重试。

[FileMentionIndex.kt:345](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L345) 外挂目录scanDir仍是dir.listFiles() ?: continue，没有标记scanHadFailure。mount根始终加入collected，因此读取失败后仍可能产生非空且新鲜的索引，重开菜单直到TTL都只检查挂载集合相同，不重试目录内容。沿用MI05的部分失败缓存问题，不新编号，不宣称底层授权被绕过。

scanHadFailure是各次scan共享字段；mountsKey调用处于try/finally之前，抛异常可跳过状态清理。这些是后续候选，本轮未证明实际provider抛异常或取消旧任务修改新失败标记的完整触发，不新增问题。

## MI06逐根重复预算已修，根条目刻意作为额外入口

[FileMentionIndex.kt:156](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L156) 第一层使用全局剩余量，第二层扣collected.size，第三层继续扣同一总量。layerEntries在各根之间扣scanned.size，原五个根各自5000的25000子项问题已消除。

每个root和mount仍总是添加自入口，即使预算耗尽。根入口没有在同层remaining中扣除，因此GLOBAL_SCAN_BUDGET不是包括所有根条目的严格5000上限；这属于代码明确保留的入口例外，不将它等同旧五倍子项预算，也未测性能或宣称OOM。扫描可能截断时没有显式完整性状态，需结合产品要求判断，不强行新增缺陷。

## MI07旧finally清新状态原窗口已修

[FileMentionIndex.kt:214](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L214) finally现在只有currentScanToken==token才清isScanning和失效时间。publish在计算前及main提交前两次检查token，旧结果不能依靠这条旧finally路径清掉当前扫描状态。沿用旧MI07记录为原窗口已修。

此判断不证明所有scan入口/元数据都有token隔离：开始前写isScanning、scanHadFailure、lastMountsKey以及异步mount比较仍是共享状态。没有足够当前调用时序证据就不扩写为新session实际显示旧文件。

## 测试名称与实际覆盖必须区分

[FileMentionIndexCacheTest.kt:22](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/FileMentionIndexCacheTest.kt#L22) 第一例只测试纯predicate真值，第二例用不安全session ID检查空结果和状态收拢。这两例不能验证真实WorkspaceFileClient目录扫描或缓存内容。

第三例名称说“changed mount set ... even when cache is fresh”，但两次调用都是../escape，mountsProvider始终返回emptyList，entries始终为空。第二次refresh因此走hasEntries=false的重扫入口，而不是fresh非空缓存后的异步mount比较；没有实际改变mount set。它证明provider至少调用两次，不能证明“挂载变化使新鲜非空缓存失效”的新分支。只记录已有测试证据范围，未改或运行测试。

后续验收应经真实提及菜单构造有条目的有效session缓存，再在TTL内添加/改名/移除mount，并覆盖外挂listFiles失败后恢复、guest部分失败恢复、替代扫描状态。保存扫描token、根集合、列表及重扫时间；本轮未执行。

## 调用链、排序与访问边界

[ChatViewModel.kt:1366](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L1366) 当前index的mountsProvider来自RuntimePathRegistry；[ChatViewModelMentionExt.kt:129](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelMentionExt.kt#L129) 打开提及菜单调用refreshIfNeeded。只核对片段，不计这两份全文。

Guest session必须UbuntuPaths.isSafeSessionId，文件读取走WorkspaceFileClient而非root raw shell。外挂扫描使用提供的File root，深度3；索引只是候选路径，不等于文件访问权限。不凭索引的host File路径推断任意host文件API或PRoot回归。

实际匹配按10000/9000/7000/6000/4000/2000/1000分层，scope/root/recency附加最多730，仍小于最小1000差距，因此更强name tier不会被这些附加分反超。不用旧注释里的1000/800等数值报排序Bug。隐藏目录与固定跳过目录属于当前扫描策略，不声称所有文件都会被索引。

## OpenMinis固定对照与覆盖

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取既有FileMentionIndex92—119行，无fetch。该版本fresh且非空直接return，没有mount比较；本地当前分支是真实变化。上游片段不计本地全文，也不推断其整个目录访问边界。

| 本轮新增全文 | 行数 |
|---|---:|
| [FileMentionIndex.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/FileMentionIndex.kt#L1) | 522 |
| [FileMentionIndexCacheTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/FileMentionIndexCacheTest.kt#L1) | 56 |

FileMentionIndex分1—270、271—522及测试1—56完整读取，无截断。新台账：[2026-10-07-code-review-coverage-0b96f7aa-mention-index.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-mention-index.json)。累计全文 **1316 / 1655文件、219510行**；partial5、not_registered334、excluded124，tracked1779。全库通读未完成。

交付校验旧177份报告与全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines及证据行号。仅新建本文与台账。
