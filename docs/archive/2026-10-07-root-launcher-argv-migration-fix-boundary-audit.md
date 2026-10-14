# Root 启动器、结构化argv和旧数据迁移修复边界

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮完整读取6文件、1119行；不新增缺陷编号，确认旧RU01仍存在，补足root.shell拒绝规则与迁移调用链的远端覆盖。未修改产品代码，未创建/执行测试、构建、模拟器、adb、Root、真实设备、guest或云任务，未读取凭据。此前157份报告及全部旧台账保持原样。

## RU01仍存在：保留上限不覆盖单行构造

[DirectRootRunner.kt:81](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt#L81) stdout使用bufferedReader().useLines，stderr同样处理；先生成完整line才交BoundedText。[DirectRootRunner.kt:265](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt#L265) BoundedText只把保留前缀限制在1048576字符。缓冲满后仍持续读每条完整行，再丢弃。极长无换行输出的临时内存仍未被这个上限约束，超时也不提供截止前的堆分配上限。旧RU01无需新编号，不声称已做内存耗尽复现。

[PrivilegedCommandRunner.kt:265](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L265) AppCommandRunner同样用useLines后限长。实际现役调用方是AndroidLogManager第196行与AndroidDiagnoseController第143行：Root读取日志失败时退回App UID命令。这两处固定logcat命令的真实输出最大行长没有实机证据，本轮不把读取层同类候选另计为已复现缺陷。

DirectRootRunner普通返回最多等待stdout/stderr线程各1秒，再取缓冲；如果子孙进程保留管道，daemon reader可能仍存活，结果也不表示所有后续输出均读取完成。正常退出只destroy直接Process，timeout/cancel/error才做group cleanup；受控调用的后台生命周期是否允许保留需按调用方判断，不能单凭这一点宣称所有Root任务正常结束都漏进程。RootPersistentShell已有有界分块行读取，但本轮不执行它，也不擅自改本runner。

旧“输出被截短却没有truncated字段”的图像扫描问题保留在已有chat-image-scan报告。本轮不把同一1048576前缀机制再计一项。

## 结构化root.shell：旧直接解释器反例已被拒绝

[RootShellHandler.kt:65](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/runtime/RootShellHandler.kt#L65) handler在调用runner之前执行genericRootToolDenial。[PrivilegedCommandRunner.kt:384](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L384) 拒绝shell、su、多调用分发器、执行包装器、解释器与可逃逸编辑器，另限制find/tar/sqlite3/ip的若干执行选项。旧F10的sh -c、su -c、toybox sh直接反例已不再被此入口接受。这个结论限定已检查拒绝规则，不等于denylist证明所有受信Android工具和所有参数都无法执行其它代码；不能凭工具名集合宣称通用Root语义完全封闭。

[PrivilegedCommandRunner.kt:328](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L328) validateRootArgv拒绝空argv、slash/NUL、超过32个参数以及单参数UTF-8超过4096字节；[PrivilegedCommandRunner.kt:406](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L406) 只从/system/bin、/system/xbin、/vendor/bin寻找可执行普通文件。handler拒绝非字符串数组项，DirectRootRunner.runArgv对每项shellQuote后构造exec。正确引用保护参数边界，但不是对工具内部语义的替代。

风险classify与effectiveRisk当前用于日志；不是这里的权限批准门。真正ToolExecutor第224行附近有ApprovalSeam，第237行进入ProviderRouter/handler，本轮仅核对片段，不能把风险枚举当新增安全边界。local-only/MCP隐藏在已有权限层处理，本轮test源码验证schema/发现过滤，未测试实际远程调用或设备Root。

ToolTimeoutPolicy第37行给root.shell默认30秒、最大120秒。RootShellHandler将timeout/failureKind映射成工具结果；真实run rootOnly仍只影响不可用错误文案，整个run执行DirectRootRunner，没有Shizuku分支。旧Android工具所有权报告已记录这一事实，不重复编号。

## 进程组清理有实现，也有证据边界

DirectRootRunner要求/system/bin/setsid，缺失则exit125；launcher在Root-owned runtime目录写PID marker。取消/超时/error调用独立su cleanup，尝试TERM/KILL负PID的进程组，再退回直接Process.destroyForcibly。cleanup只等900ms，失败是best effort，结果没有单独的cleanup成功证明。

[DirectRootRunner.kt:208](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt#L208) stale cleanup检查marker PID格式、当前PID/PGID、cmdline以及可选environment token，然后只对匹配进程发信号。它比“看到PID就kill”多身份检查；/proc读取与发信号不是原子事务，不能提升为绝对没有PID复用窗口。当前没有设备失败反例，不另报。

内部runScript接受字符串，是产品基础设施launcher，不是公开模型raw command schema。是否所有调用方都构造受控脚本仍应逐模块核对；本轮完整阅读launcher不等于UbuntuKernel、provisioner、网络helper均已全文复核。

## LegacyDataMigration仍有生产职责

[LegacyDataMigration.kt:13](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/LegacyDataMigration.kt#L13) marker是App filesDir下minis/.root-data-migrated-v1；对象只负责完成判断和进程内listeners，真实迁移在UbuntuKernel。[UbuntuKernel.kt:677](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L677) 固定旧源/新App-owned目标映射，迁移脚本成功后写marker，再notifyComplete。

[MinisApp.kt:470](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L470) 注册listener，完成后reloadFromDisk技能并刷新SoulStore缓存；[SkillRepository.kt:1799](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt#L1799) 未迁移前避免把暂时为空的App-owned技能目录当成应删除的数据库记录。这个旧数据兼容链仍有作用，不能因为legacy名字删除。它也不意味着恢复PRoot/Alpine/minisd生产执行栈。

listener未提供移除接口，但现役注册点是Application启动；未确认在同一进程多次注册，不能据容器永久持有直接报可见泄漏。notifyComplete吞单个listener异常并继续后续listener；marker成功与缓存刷新成功是两个事实，刷新失败是否触发其它恢复还需具体repository闭环。本轮不将这些候选计新问题。

旧OwnershipMigrationManager的FD/WAL事务模型未接线问题沿用历史报告，不把LegacyDataMigration视为实现了那套事务阶段。

## ProviderRouter与已有测试范围

ToolProvider59行完整读取：providers是按插入顺序的linkedMap，route取第一个匹配prefix，reset清空；无并发同步。MinisApp第829行reset/register仅LinuxProvider，ToolExecutor按canonical tool名路由。当前没有生产运行中反复注册的证据，不将启动注册容器直接报为并发崩溃。root.shell无额外provider包装，实际handler仍通过共同工具执行层。

RootShellHandlerTest148行完整读取：schema无command字段、权限local-only/MCP隐藏、shell_root alias、本地Agent schema，以及21组拒绝参数和8组允许分类。否定用例直接调用真实handler，断言拒绝理由不是JVM缺/system导致not found，避免把找不到工具当拒绝规则通过。允许用例只调用genericRootToolDenial，并不启动Root或证明命令实际成功。没有执行这些测试。

## OpenMinis对照与覆盖

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，只核对现有Git树。本轮五个生产模块同名文件不存在，不提供同路径实现对照；不能推导上游完全没有Root或迁移能力，也不把旧broker/PRoot接回当前代码。

| 本轮新增全文文件 | 行数 |
|---|---:|
| [DirectRootRunner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt#L1) | 278 |
| [LegacyDataMigration.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/LegacyDataMigration.kt#L1) | 30 |
| [PrivilegedCommandRunner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L1) | 496 |
| [RootShellHandler.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/runtime/RootShellHandler.kt#L1) | 108 |
| [ToolProvider.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolProvider.kt#L1) | 59 |
| [RootShellHandlerTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/tools/runtime/RootShellHandlerTest.kt#L1) | 148 |

PrivilegedCommandRunner分1—255/256—496，其余文件完整输出无截断。调用方和旧报告仅相关片段，不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-root-runner.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-root-runner.json)。累计全文 **1236 / 1652文件、201737行**；partial4、not_registered412、excluded124，tracked1776。全库通读仍未完成。

交付校验旧157份报告和全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines及证据行号。只新增本报告和新台账，旧文件留给Claude处理。
