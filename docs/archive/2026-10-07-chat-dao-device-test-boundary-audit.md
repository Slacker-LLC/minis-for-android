# 会话DAO与设备测试证据边界复核

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。本轮完整静态阅读ChatDao及6份现有androidTest，共7文件、956行。旧176份报告与全部台账原样保留。未修改代码、编写或执行测试、构建、启动模拟器/adb/Root/设备/云资源。本轮没有新增足够证据支持的独立问题。

## 草稿删除重新检查已落实

[ChatDao.kt:94](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L94) deleteSessionIfUnusedDraft在DELETE语句内再次约束title/folder/bot/source/pinned为空且NOT EXISTS消息。[ChatRepository.kt:153](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L153) prune先读取年龄足够的候选，再调用条件删除；仅真正删除后释放对应session。

[DraftPruneRaceTest.kt:46](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/DraftPruneRaceTest.kt#L46) 现有测试先取得候选，再插入第一条消息，然后执行同一DAO条件删除并核验行未删；另覆盖普通旧草稿与标题/固定保护。这是对原候选过期窗口的直接源码用例，未执行。没有把“SQL保护已有消息”扩大为“所有UI尚未发送的内存草稿都已保存”，也没有验证运行中任务仍未写消息的情况。

## 删除级联与更新现有行的边界

DAO.deleteSession是一条DELETE。MessageEntity、CompactMarkerEntity和SessionEventEntity均声明session外键CASCADE，Repository不再先单独删消息再删session。[SessionDeleteCascadeTest.kt:35](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/SessionDeleteCascadeTest.kt#L35) 设备测试实际构造内存AppDatabase，删除一会话并核验其消息归零、另一会话消息保留。

测试不插入marker/events，不覆盖guest文件、工具进程、checkpoint等非数据库清理，不能据此声称所有附属资源已同步清理。Repository删除后的onSessionDeleted处于独立IO边界，不在同一SQL事务，本轮未重新证明相关sidecar旧问题是否关闭。

[ChatDao.kt:118](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L118) insertSession仍为REPLACE，但新增updateSession用于已有行原地更新。不能只见DAO保留REPLACE便断言备份仍使用它，也不能只见新增Update就宣称所有调用方均已迁移。旧F37备份问题须复核Importer真实路径，本轮不重计或关闭。

## 生成标题条件写回存在，调用方仍须分别判断

[ChatDao.kt:134](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L134) updateGeneratedTitleIfUnchanged把expectedTitle/expectedCategory比较放在UPDATE条件内，并返回变更行数。Repository.updateGeneratedTitle返回rows>0。

[GeneratedTitleWriteTest.kt:38](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/GeneratedTitleWriteTest.kt#L38) 四例覆盖未变化接受、手动改标题拒绝、手动改category拒绝、初始null接受，是真实DAO/Repository调用。没有异步Provider或自动标题流程。此前SG06报告已区分手动重新生成与自动成功写回，这些SQL测试不能证明未调用条件方法的自动分支也受保护，沿用原修复边界。

IFNULL将null和空字符串视为相同，符合本方法采用的等价条件；未提出产品要求区分两者，不强行列竞态。也没有凭已有title条件就宣称全部metadata有CAS保护。

## 复制成功测试不能覆盖SFC01失败窗口

[SessionDuplicateTest.kt:35](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/SessionDuplicateTest.kt#L35) 实际调用SessionForkManager.duplicateSession，核验title/category/memory/thinking/binding与两条消息、源会话未改。测试没有compact marker、session overrides、roleplay等全部字段断言，也没有中途异常或取消。

此前SFC01确认duplicateSession没有整个复制过程的外层事务；当前成功测试不触发该窗口。DAO.appendMessageWithPreview虽有@Transaction，保护单条追加与preview，不能让若干次追加与新session创建自动成为一个整体事务。沿用SFC01，不新编号。

## 字面搜索与不可读行占位

DAO.searchSessions用LIKE pattern及ESCAPE，Repository调用likeContains；[ChatRepository.kt:1141](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1141) helper依次转义反斜线、百分号和下划线。[SessionSearchLiteralTest.kt:39](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/SessionSearchLiteralTest.kt#L39) 测试覆盖下划线、百分号及单独百分号，防止通配符扩大结果。没有反斜线或坏JSON显示测试；也不由此推断会话列表搜索结果自动刷新。

[ChatDao.kt:521](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L521) messageHeaderAt只取id/role/createdAt/sortOrder，避开parts payload。Repository.loadMessages遇到特定CursorWindow错误逐行回退，unreadableRow尝试保留真实身份与位置，生成说明占位。

[UnreadableRowTest.kt:35](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/UnreadableRowTest.kt#L35) 测试绕过append预算插入3MB旧行，再通过Repository读取，并断言总数/role/id及后续消息保留。这是设备环境源码用例，不是本轮运行结果；文件所假定的2MB窗口不能作为所有OEM的固定事实。头部读取也失败时Repository构造替代id而非保留真实id，故占位真实性有明确边界。

OFFSET加载与count/page分离没有跨页一致快照；此前相关历史分页报告已记录同类并发边界，本轮未新增证据将其升级为另一编号。

## 其它DAO边界与OpenMinis对照

appendMessageWithPreview在事务内取nextSortOrder、insert、updateLastMessage；ifAbsent存在时还要求session与role匹配。generated title、migration批次事务、会话事件裁剪、带日期分页/count共享日期条件均真实存在，不凭历史注释认定未实现。

上游固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取既有ChatDao94—128行，无fetch。该片段insertSession同为REPLACE，title为普通UPDATE；当前新增原地Update与条件generated-title SQL是真实本地变化。上游片段没有全库调用证据，不扩写为上游全部title路径必然覆盖用户编辑，也不计当前全文覆盖。

## 覆盖与旧文件保留

| 本轮新增全文 | 行数 |
|---|---:|
| [DraftPruneRaceTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/DraftPruneRaceTest.kt#L1) | 73 |
| [GeneratedTitleWriteTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/GeneratedTitleWriteTest.kt#L1) | 70 |
| [SessionDeleteCascadeTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/SessionDeleteCascadeTest.kt#L1) | 48 |
| [SessionDuplicateTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/SessionDuplicateTest.kt#L1) | 57 |
| [SessionSearchLiteralTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/SessionSearchLiteralTest.kt#L1) | 51 |
| [UnreadableRowTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/data/db/UnreadableRowTest.kt#L1) | 54 |
| [ChatDao.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L1) | 603 |

DAO分1—310、311—603完整读取，六份测试各自首尾完整读取，无截断。Repository、SessionForkManager及entity只核对片段，不提升为全文。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-chat-dao.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-chat-dao.json)。累计全文 **1314 / 1655文件、218932行**；partial5、not_registered336、excluded124，tracked1779。全库通读尚未完成。

交付校验旧176份报告/全部旧台账hash，固定本地/远端/上游HEAD，tracked/staged空diff，1779快照Git blob/hash/bytes/lines及证据行号。只新增本文与本轮台账。
