# 整会话复制事务与委派分页复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`，完整静态阅读3文件、608行。保留此前167份报告和旧台账，只新建本文及本轮台账。无产品代码修改、测试编写/执行、构建、模拟器/adb/Root/设备/云任务。并发与中断后果均由源码顺序推导，未宣称设备复现。

## SFC01 / P2 — 整会话复制中途失败留下部分副本

[SessionForkManager.kt:83](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt#L83) duplicateSession先读取source/messages，第91行创建并插入新session，再分别写category、binding、memory、overrides/thinking，第131行循环appendMessage，随后复制compact markers。函数没有外层withTransaction，也没有异常/取消时删除新session的回滚逻辑。

真实入口[SessionListViewModel.kt:1115](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L1115) 在viewModelScope.launch内调用该函数；没有外围数据库事务或失败清理。ChatRepository.createSession第48行直接insertSession，appendMessage第472行调用DAO的单条append事务。单个append的原子性不能覆盖整个复制。

静态触发顺序：新session和前k条消息已提交；下一次挂起的数据库操作遇取消/错误，复制退出；已提交的新session与前缀继续存在，后续消息和压缩记录未复制。副本在复制开始时就具有正常会话身份，未有“复制中/失败”的持久状态。结果可以是貌似完整、实际缺尾部上下文的会话。源会话不因此被删除，不能说复制失败损坏源。

这与旧SG05字段未接线不同：SG05当前已修，SFC01是成功部分提交后的失败窗口。建议复用消息处分叉已使用的Room事务来覆盖完整读取和写入；如有非数据库副作用则按实际边界评估，不先新增抽象。未来验收在复制第k条之后故意失败/取消，核验副本不存在或有明确失败状态；成功路径仍完整复制设置与marker引用。本轮只记录，不实现或运行。

## SG05原字段漏复制入口已修

当前SessionListViewModel第1121行调用SessionForkManager.duplicateSession，并传本地化标题，替代旧简化复制。manager完整复制category、modelBinding、memoryEnabled=0、sessionOverrides、thinkingOverride，生成全新message ID并重映射compact markers。旧“关闭记忆的副本变开启”原反例已修。

marker的非空引用不能映射时跳过而不制造悬挂ID；legacy无ID时保留sort fallback。没有根据这点宣称所有历史marker语义已设备验证。消息复制传partsJson/tokenUsage/reasoningContent，但没有传modelSnapshot，历史模型归属字段是否应保留需产品定义；仅候选，不新增确定编号。

## 消息处分叉有不同的实际保护

[SessionForkManager.kt:213](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt#L213) forkSessionAtMessage先使用进程级AtomicBoolean guard，finally释放；其读取、建session、设置、消息与marker写入包在AppDatabase已初始化实例的withTransaction内。manager每次新建也共享guard，能拒绝重叠用户分叉。目标必须在源会话内且role=assistant。

不能把SFC01扩大到此方法，亦不能说整个类完全没有事务。copyMemory严格拒绝空名、dot/dotdot、斜杠/反斜杠/NUL，写固定全局memory路径，静态搜索未找到现役copyMemory调用方；不因为sessionId为空就给它新增现役会话越界问题。copySkill复用已有importFromContent，不运行或导入内容。

## 委派持久层的分页和幂等保护

[BotDelegationRepository.kt:25](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/BotDelegationRepository.kt#L25) enqueue先按source_session/source_tool查existing，插入使用IGNORE，冲突后再查已有记录返回。BotDelegationEntity第13行有组合唯一索引，竞争请求不会仅靠前置查询去重。prompt截到12000、结果24000、error2000，depth强制1；实际工具入口对sourceTool ID另有校验，不能由repository参数可null直接宣称生产幂等缺失。

[BotDelegationDao.kt:42](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/db/BotDelegationDao.kt#L42) dispatchable分页按(created_at,id)严格向前，包含QUEUED/WAITING_TARGET且source_turn_settled=1；terminal分页也按同一稳定键及delivered_at=null。repository将limit限定1—100，以前页末行作游标。

当前Coordinator第66/307行forEachPage用于恢复投递与全量派发，第574行即使处理后行仍未改变也推进到page.last，短页结束。首批32条不是硬性总上限；不能根据批大小报告“第33条永不恢复”。查询在运行中变化不是一个一致快照，新增/变更游标前的行要靠后续轮次，不凭此推导必然丢任务。

claim/finish/cancel具有status条件更新及Boolean affected-row反馈，source_turn_settled门槛真实存在；terminal finish不会覆盖已CANCELLED。markDelivered自身仅要求delivered_at=null，没有terminal状态限制，但当前调用方投递终态，未证明可对运行中行错误调用，候选不新增编号。

Coordinator第346行已有根任务STOPPED状态检查，旧BC02的排队领取前检查原反例有改善。只读此片段不足证明正在执行成员取消或检查后启动竞争闭环，BC02仍待完整生命周期复核，不能宣布所有停止语义已修。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取现有SessionForkManager第82—105行，无fetch。其逐条append复制同形，不由上游存在同实现证明当前复制事务正确。未全文复核上游Bot协调实现，当前SQL与调用方优先作为事实。

## 全文覆盖和文件保留

| 新增全文 | 行数 |
|---|---:|
| [SessionForkManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt#L1) | 380 |
| [BotDelegationDao.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/db/BotDelegationDao.kt#L1) | 86 |
| [BotDelegationRepository.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/BotDelegationRepository.kt#L1) | 142 |

SessionForkManager分1—200/201—380，Repository1—142、Dao1—86完整读取。其它caller/DB/entity及旧报告仅片段核对，不追加全文。

新台账：[2026-10-07-code-review-coverage-37158d18-session-delegation.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-session-delegation.json)。累计全文 **1277 / 1652文件、210429行**；partial4、not_registered371、excluded124，tracked1776。全库通读未完成。

交付核验旧167份报告与旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines和证据行号。只新建本文及本轮台账。
