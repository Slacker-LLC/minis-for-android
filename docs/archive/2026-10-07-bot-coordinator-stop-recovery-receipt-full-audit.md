# Bot协调器全文复核：停止、恢复分页与回执

日期：2026-10-07，固定远端`ea620e9ec51a246ce76cdec45a8d43109462f472`。本轮新增全文4文件、816行；已全文BotTaskRepository88行重读不累计，其它DAO/界面只核对关联片段。旧195份报告/全部旧台账不覆盖，独立新报告及台账。仅静态阅读，没有改代码、编写/运行测试、模型/工具调用、构建、模拟器/adb/Root/设备/云资源或凭据读取。

本轮无新增缺陷编号。F23分页、BC03条件写回已有修复；BC02停止语义还有剩余，BC09/BC10在最新实现仍有证据。不能以缺陷数量代替覆盖，也不能把旧问题重新编号。

## F23：首32条之后的委派/终态回执已有明确分页

[BotDelegationCoordinator.kt:66](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt#L66)启动恢复对listUndeliveredTerminal使用forEachPage，dispatchAll同样分页listDispatchable。cursor按上一页最后元素推进，即使deliverReceipt失败、行未变化也不从同一页重来。DAO使用created_at/id二元keyset，并有稳定排序；同毫秒多行不只用时间戳翻页。

BotRecoveryPagingTest34行2例调用生产forEachPage，用75条整数验证三次fetch，以及64条保持不变验证两满页后空页结束。BotDelegationPagingTest72行1例使用真实Room inMemory/DAO/Repository，插75条QUEUED、70条未投递COMPLETED和1条已投递，三条共享同毫秒，核对两个查询全量一次按序到达。

这两组实际覆盖不同层：前者是生产pager加假数据，后者是查询/游标，后者collect循环写在测试里，并没有启动生产Coordinator。均未运行，不称设备恢复已验收，也不把它们当进程中断、正在变化的任务集、回执持久化和Provider执行完整证据。

## BC03：较晚派工写回不再复活已停止根任务

[BotDelegationCoordinator.kt:210](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt#L210)enqueue末尾改用updateStateIfWakeable，失败则取消刚建委派并回执，返回task_not_continuable。[BotTaskDao.kt:36](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/data/db/BotTaskDao.kt#L36)SQL只允许ACTIVE/NEEDS_USER状态转换；PAUSED/CANCELLED不会被较晚的EXECUTING写回覆盖。

publishInboxEvent亦使用同一条件写回，对停止状态保留。BotTaskStopTest60行3例分别验证ACTIVE能进入EXECUTING，PAUSED/CANCELLED后相同更新返回false且状态保持。它调用真实Room/Repository，验证该条件更新，不是实际pause/cancel按钮及正在运行worker的端到端测试。

因此旧BC03这两处已修，不能继续原样报无条件复活。recordDelegation也有wakeable条件及额度限制；无需以尚未覆盖整个状态机为由否认这些真实保护。

## BC02剩余：停止数据库记录还没有取消运行成员；检查到领取仍有窗口

[BotsScreen.kt:304](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/ui/bots/BotsScreen.kt#L304)真实暂停/取消按钮仅调用BotTaskRepository.pause/cancel；[BotTaskRepository.kt:64](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/data/repository/BotTaskRepository.kt#L64)只进入dao.stop，[BotTaskDao.kt:54](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/data/db/BotTaskDao.kt#L54)更新stop_generation、status和updated_at，没有向成员请求句柄发取消。

[BotDelegationCoordinator.kt:345](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt#L345)现在派发前检查根任务STOPPED_ROOT_STATUSES，停在排队状态、稍后才取得目标的旧反例确有改善。但是检查只读一次：之后读取Bot、解析模型、创建目标session、获取Bot锁再claim，均有挂起/数据库交错机会。[BotDelegationDao.kt:58](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/data/db/BotDelegationDao.kt#L58)claim仅要求该委派settled且QUEUED/WAITING_TARGET，没有关联根任务状态/stop_generation。

明确剩余交错：dispatch检查根为ACTIVE；用户在目标session创建/模型解析期间cancel根；随后委派claim仍满足自身条件，进入RUNNING并调用AgentRunner.prompt。全局target mutex只串同目标派发，根任务pause/cancel没有使用它，不能消除这条竞态。

运行中的成员也没有从根停止按钮进入Coordinator worker取消；Coordinator的cancel(application,targetSession)只在worker自身协程取消或抛异常时发生。源回合失败时取消未启动委派是另一条链，不等同根任务运行中取消。

这仍归BC02，不重新编号。未来授权验收需在检查后/claim前停根、以及RUNNING时点取消，核验没有新启动且执行句柄收到取消、清理退出和结果未知状态保留。暂停对已运行成员是否允许继续需明确产品约定，不能自行扩大为暂停必须终止所有既有执行；取消必须与真实工作状态一致。只记录，未执行。

## BC09/BC10：回执重试与来源删除仍未完成当前进程闭环

[BotDelegationCoordinator.kt:505](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt#L505)appendMessage带bot-receipt稳定幂等key，再notifyExternalMessage、publishInboxEvent、markDelivered、唤醒调度；整个序列runCatching没有检查失败结果、记录重试或当前进程后续扫描。暂时投递失败仍保留未送达终态，下一次明确启动恢复能重试，但本次进程没有周期/失败驱动重试。旧BC09保持，不否定真实幂等卡片保护。

[BotDelegationCoordinator.kt:477](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt#L477)来源session不存在时只markDelivered后返回，没有把所属根任务终结或标需要处理。旧BC10仍可能根记录ACTIVE而无后续复盘事件；保留审计本身合理，问题是活动状态没有随无法继续的事实收口。没有实际删用户session或任务。

另一候选顺序是markDelivered之后调用onInboxEventPublished，若那步异常，记录已投递而当前唤醒失败；需要结合BotWakeDispatcher的启动恢复扫描和该调用真实异常路径闭环，不能仅凭先后顺序新报永久丢事件。异常吞掉的问题已归BC09，不重复计。

## 配额、身份、目标锁与恢复真实范围

enqueue校验来源Bot启用、来源session存在、深度、非自身目标、目标启用、prompt/toolId非空、每源run最多4条；来源与task查询要求相同sourceSessionId。roster清洗名称并限制长度，主模型需文本输出。这些是真实handler层判断，不只依赖工具schema可见性。

普通根任务goal仍来自首次delegation prompt，复盘根任务由WakeTaskRegistry选定；旧BC04目标/验收标准缺口不是本次条件写回自动解决。revision/delegation额度分别写入，多对象提交失败的语义还需按原报告链继续核对，不虚构跨数据库事务已实现。

recover取消source未settle委派，把RUNNING转FAILED/outcomeUnknown并投递，不自动重新执行此前RUNNING模型回合；随后扫描允许派发队列和终态回执，最后调用wake恢复。这个成员恢复保证与自动复盘BC05/BC06属于不同路径，全文Coordinator不能替代完整WakeDispatcher证据。

目标锁无法取得时删除预建session，进入WAITING_TARGET且计attempt，等待available后重派；claim失败释放外部锁并删目标session。正常执行以PromptResult.streamExited决定立即释放或等实际流退出。延后等待协程没有额外finally释放，异常时的锁回收仍需实际awaitStreamExit失败语义，本轮不将候选写为确定泄漏。

整个Coordinator是Application上的SupervisorJob+IO scope，内存waiting/target锁仅运行投影，Room才是委派权威。target锁表不回收但Bot数量有限，不能据ConcurrentHashMap直接报无限内存增长。没有另建Root/MCP/broker通信通道。

## 上游、全文与交付

固定OpenMinis`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`既有Git树无同名BotDelegationCoordinator。本轮仅核对路径，无fetch/运行上游，也不把不同上游模块当本地Bot状态机实现。

| 新增全文 | 行数 |
|---|---:|
| [BotDelegationPagingTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/androidTest/java/com/openminis/app/data/db/BotDelegationPagingTest.kt#L1) | 72 |
| [BotTaskStopTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/androidTest/java/com/openminis/app/data/db/BotTaskStopTest.kt#L1) | 60 |
| [BotDelegationCoordinator.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt#L1) | 650 |
| [BotRecoveryPagingTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/tools/BotRecoveryPagingTest.kt#L1) | 34 |

Coordinator1—220、221—440、441—650；三个测试完整输出。组合旧报告定位输出截断后两个instrumented测试独立重读，未把截断或检索算全文。合同08用于边界对照，不重复计源文件。

新台账：[2026-10-07-code-review-coverage-ea620e9e-bot-coordinator.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-ea620e9e-bot-coordinator.json)。累计全文 **1391 / 1664文件、235550行**；partial10、尚未登记263、excluded124，tracked1788。全库通读仍未完成。

交付校验旧195份报告/全部旧台账hash、本地/远端/上游HEAD、tracked/staged代码diff、1788快照blob/hash/字节/行数及本报告引用行号。只新增本文和新版覆盖台账。
