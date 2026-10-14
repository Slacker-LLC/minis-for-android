# 文件搜索完整性与Ralph报告合同全文复核

日期：2026-10-07。固定远端主线`ea620e9ec51a246ce76cdec45a8d43109462f472`。本轮新增全文4文件、963行。独立新报告与台账，旧194份报告及所有旧台账保留。未改产品代码、写/运行测试、构建、启动模拟器/adb/Root/设备/云资源、读取凭据、调用工具/模型端点。

RU08主要条件已修；RU09保留不存在路径的剩余负例。Ralph旧F19三项阻断改善，旧F20外部取消已有request.cancel链；原报告中超长summary校验候选现已在正常完成后可达，首次明确编号RHC01，不能当成与旧候选无关的新根因。

## RU08：匹配前耗尽结果预算已修，保留扫描上限与完整性标记

[LinuxFileOpsTools.kt:63](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt#L63)walkTree把scanned与matches分别计数，按500项读每个目录并广度优先递归；只有accept命中才消耗maxMatches。搜索scanBudget5000、结果上限200；先出现150个不匹配文件不会把100结果预算用光。

[LinuxFileOpsTools.kt:245](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt#L245)结果有scanned及truncated；命中上限或扫描上限即标提前停止。因此0结果只在未截断时才能解释为所扫描树未命中。恰好达到限制也保守标truncated，不把这种保守标记作为故障。

ExternalMountAccess.walk已有分页；LinuxFileOps对其先扫描至5000再过滤，结果匹配限制不会再把前100个不匹配项当全部搜索空间。这里只核对实际普通目录与既有外部遍历链，未运行SAF/文件系统变更中的分页一致性，不能宣称是数据库式快照。

FileSearchCompletenessTest124行7例用真实LinuxFileOps/WorkspaceFileClient、临时session backing，覆盖匹配在填充文件后、递归深层、结果上限、完整零匹配，以及grep普通/超大/命中上限。比测试内重写算法更直接，但本轮仅阅读，未执行；没有真SAF权限撤销、目录变化、缺失路径或实际App注册执行证据。

## RU09剩余：不存在路径被报告成完整无匹配

大文件4MiB限制、读取异常、扫描提前停止、命中上限现在都进入skipped_count/scan_truncated/hit_limit_reached，[LinuxFileOpsTools.kt:341](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt#L341)complete由这些状态合成。原“超大文件静默跳过却宣称完整”的主要条件已修，不能原样宣称仍未改。

但是[WorkspaceFileClient.kt:188](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt#L188)info对不存在或无法取得Attributes的目标返回exists=false，不带type；[LinuxFileOpsTools.kt:278](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt#L278)grep没有检查exists，只按type是否dir决定遍历或单文件。不存在路径进入后者，其WorkspaceEntry.type为空；随后type!=file直接continue，没有skip计数或错误。

静态明确负例：调用linux.file.grep，path=/workspace/no-such-file.txt，合法pattern=needle，此路径不存在。metadata为exists=false，文件项为空type，没有执行读取，三种不完整标记均false，最终success=true、count=0、complete=true。这不是实际搜索了一个已存在文件后没找到匹配，也未把目标缺失告诉模型。某些metadata读取错误被infoOrNull压成null亦可能走同分支，但本轮只以确定不存在路径闭环，不泛化所有权限错误。

该问题仍归RU09“不完整/无法扫描却当完整零结果”，不重新立项。记录方向：先明确目录/文件存在及可扫描类型，目标缺失/不可读返回明确错误或不完整原因；保留目前的大文件、扫描与命中预算。未来授权验收从真实工具入口覆盖不存在文件/目录、非普通文件、权限拒绝、删除竞态及正常无匹配，保存工具JSON与目录状态。未实施。

WorkspaceFileClient.list把SecureFileAccess返回的next_offset交给调用者；现役普通list不会静默跳过某些条目后制造不足500的分页结果，因此不只凭walkTree按items.length判断结束就虚构短页漏扫。目录在分页期间变更不是一致快照，仍需具体真实竞态证据。

## RHC01 / P2：Ralph的超长summary先截短后校验，违背拒绝超限报告的合同

旧报告[子Agent与Ralph合同](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-subagent-ralph-entry-report-contract-audit.md)已将此记为后置候选，当时正常Completed大小写被F19阻断。本版[RalphTool.kt:177](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt#L177)用result.completed接收正常完成，后续parseReport当前可达，故本轮明确记录该现役缺口，首次分配RHC01，不算独立于旧候选的第二个问题。

[RalphTool.kt:225](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt#L225)检查status合法、summary非空后，构造RalphReport时summary.take(2000)，其它字段分别take(8192)，随后才检查重新序列化总长8192。提示及源码头的规则却是summary不超过2000、无效/超限报告必须失败，不能静默截断。

明确反例：正常Completed回答包含合法JSON，status=complete，summary为2500个普通字符，其余字段为空或很短。原summary超限500字符；先take后得到2000，总JSON小于8192，parseReport返回报告，execute返回成功并展示截短摘要，未提示曾丢掉500字符。这是静态确定接受路径，不宣称已运行模型产生此回答。

记录方向：在修改字段前对原始规范化字段和总handoff做限制校验，超限明确失败；保留有界上下文，不用无限放大报告解决。未来授权验收以受控child回复覆盖2000/2001/2500字符摘要、总体超限及转义字符，核验是否明确拒绝且内容不静默改变；保存原始JSON及父工具结果。本轮未编写或运行测试。

continue缺nextSteps、blocked缺blockedReason仍未按提示的required拒绝；这些同属旧报告已有字段校验候选，单独记录具体行为，不借一轮新增多个重复编号。新lastJsonObject能回退尝试更早的左括号，不再只截最后一个左括号，解决字符串里含花括号的旧例；不能把这个解析改善认定为字段长度合同也已满足。

## Ralph与父子生命周期的已修范围

AgentRunner.PromptResult.completed现在严格认status=Completed且未timedOut；Ralph用此属性，旧F19正常回合大小写阻断已修。[RalphTool.kt:38](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt#L38)跨轮状态明确是/var/minis/shared/ralph/runId，App侧目录取hostShared；每轮新session的/workspace不再被承诺跨轮可见。目录mkdirs为best-effort，无法创建时仍要求后续child自行访问实际共享目录；无具体失败设备证据不新增故障。

[HeadlessChatRunner.kt:210](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt#L210)父等待被取消时现在request.cancel后重抛，真实链已进入child请求取消；旧F20“完全没传取消”的说法不再适用。外部取消仍没有等待child完整退出才重抛；超时最多等待5秒并有streamExited事实。Ralph只判completed，不能从cancel请求发出推导任何具体Root/OEM进程已退出，本轮不重复之前生命周期报告。

activeRuns是全进程限制，检查与increment分开但期间无suspend；是否真实并行线程竞争需运行入口证据，不把两个语句直接说成同线程协程必然嵌套。runId取UUID前8字符的碰撞及状态目录创建检查是候选，未虚构实际跨运行覆盖。

RalphReportParsingTest40行3例核对花括号字符串、多个报告选最后、无JSON返回null及PromptResult.completed各状态；没有实际AgentRunner.prompt、跨轮产物、取消、parseReport长度或required字段的端到端证据。未执行这些测试。

## 文件操作与权限边界

copy/move/delete/append复用WorkspaceFileClient，外部SAF通过ExternalMountAccess，跨内外边界copy/move显式拒绝。writable检查SessionPermissionStore及read-only mount，move同时检查源和目标，copy检查目标。不能把Host-side App-owned文件工具直接描述成通用Root host文件API；写入仍是App UID与现役安全文件访问边界。

append使用FileMutationQueue，旧队列键/别名/取消候选已有报告，不重复编号。多处catch Throwable会把CancellationException转工具失败，是否导致真实取消后继续后续副作用要结合各调用/Job边界；不能由catch本身宣称所有copy都完整不可取消。grepRegex在行上执行未另有regex时间预算，是已有资源边界候选，未发送消耗输入。

headTail先读最大50MiB再取最多1000行，当前不是流式尾部查找；超预算由readAll拒绝，不是默默只读文件前50MiB。因此不能误报tail必然返回文件头。合法大文本的内存放大需真实运行证据，本轮不运行压力验证。

## 生产接线、上游与覆盖

MinisApp568—578实际注册linux.file.* handlers；search_files alias明确属于android.media.files，linux.file.search没有抢占它。AgentTools FULL合并canonical工具，Ralph也直接列入列表，所以本报告不是孤立未接线类的推断。读取注册片段不把整个MinisApp升为全文。

固定OpenMinis`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`树无同名LinuxFileOpsTools.kt/RalphTool.kt。本轮只核对既有Git树路径，未fetch，不将旧上游PRoot实现恢复成修复方案。

| 新增全文 | 行数 |
|---|---:|
| [LinuxFileOpsTools.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt#L1) | 520 |
| [RalphTool.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt#L1) | 279 |
| [FileSearchCompletenessTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/tools/FileSearchCompletenessTest.kt#L1) | 124 |
| [RalphReportParsingTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/tools/RalphReportParsingTest.kt#L1) | 40 |

LinuxFileOpsTools1—260、261—520；RalphTool1—160、161—279；两个测试完整输出。首次Ralph组合输出截断后已补读，搜索和相关片段不升级全文。

新台账：[2026-10-07-code-review-coverage-ea620e9e-file-search-ralph.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-ea620e9e-file-search-ralph.json)。累计全文 **1387 / 1664文件、234734行**；partial10、尚未登记267、excluded124，tracked1788。全库通读尚未完成。

交付核验旧194份报告及所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged代码diff、1788快照blob/hash/字节/行数及引用行号。只新增本文和新版台账。
