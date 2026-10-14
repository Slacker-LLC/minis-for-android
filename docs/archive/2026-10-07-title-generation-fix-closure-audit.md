# 标题生成模块与已有修复闭环静态复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`，没有再次改目标版本。本轮补完SessionTitleGenerator并读两测试，新增全文3文件、742行；没有另计新缺陷，确认此前标题竞态只在部分调用修复。只静态阅读，没有修改产品代码、编写/运行测试、请求模型、构建、模拟器、adb、Root、设备或云任务。旧140份报告与所有旧覆盖台账保持不动，新建本文及独立累计台账。

## SG06复核：手动重新生成已有条件写回，自动成功分支仍无条件覆盖

远端 [SessionListViewModel.kt:952](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L952) 手动generateTitleFromStore在模型回复后调用updateGeneratedTitle，携带请求开始时的session.title/category；false则记录superseded并不写回。[ChatRepository.kt:126](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L126) 委托DAO条件UPDATE；[ChatDao.kt:131](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L131) WHERE除id还比较expectedTitle/expectedCategory。原报告手动“重新生成晚到覆盖已保存改名”的成功分支已增加数据库内的条件判断，比单独get再update更能拒绝并发编辑。没有运行Room或手动改名操作，不宣称未保存输入草稿的所有同步问题一并修复。

自动标题的新模块 [SessionTitleGenerator.kt:251](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionTitleGenerator.kt#L251) 取得非空模型标题后，仍直接updateSessionTitleAndCategory(sid,title,category)，没有携带期望值或重读是否已手动改名。[ChatRepository.kt:121](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L121) 与 [ChatDao.kt:123](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L123) 对id无条件UPDATE。模块第78行currentTitle检查在请求前，不能保护请求期间的用户改名。

实际调用 [ChatViewModel.kt:8685](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L8685) 首轮与第9106行完成尾调用titleGenerator.generateIfNeeded；第434—449行Host以真实sessionId读消息/当前标题并把自动成功标题写回UI。用户手动改名 [SessionListViewModel.kt:783](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L783) 使用同一数据库id。静态顺序：自动请求始于New Chat → 用户改名保存“我的项目” → 模型成功返回“Generated Project” → 第253行无条件写回，原手动名被覆盖。这里是同一标题竞态家族的自动入口残留，不以新模块文件名另计一项。

失败fallback第379行读取当前title，有实名则跳过；取消finally以NonCancellable执行fallback。因此不能笼统说所有失败/取消都会覆盖已有title，也不能把手动路径的条件UPDATE误认为自动路径已复用。fallback的check/write仍是两步，是否需要进一步原子保护留待具体并发闭环；本轮没有将这个较窄窗口另起编号。

修复方向记录：自动生成成功应复用已有带期望title/category的数据库条件写回，并只在实际写入时发布UI结果。未实施。

## 分组、返回解析与测试边界

SessionTitleGenerator提示词最多30个组、name40/description100字符，以promptSafe规范化，渲染名碰撞时不提供歧义选项；返回folder先真实名称匹配，再sanitized唯一匹配，最终setFolderIfUnfiled，避免覆盖已经手动分组的session。ChatRepository.findFolderByName返回最新同名组，名称不是唯一约束；组在模型等待期间变化等候选未凭注释直接计错分组。

SessionTitleGeneratorTest全文53行调用生产parseTitleResponse，覆盖JSON、围栏、folder null字符串/JSON null、坏JSON正则fallback、纯文本50字符和空返回。它没有Host、CoroutineScope、Provider延迟、Room或手动改名，因此不能用它证明SG06自动分支已受保护。没有运行测试。

AutoGroupPromptSafetyTest全文127行调用生产promptSafe，覆盖ASCII/中文、注入分隔字符、空白/长度、幂等、Unicode separator/quote/bidi controls、截断碰撞。最后一个case只证明sanitize会碰撞，不调用实际group list构建/DAO匹配；去重/唯一性在本轮源码中核对，不把纯字符串断言当数据库分组正确性的实证。

普通title/category日志仍记录模型文本，OAuth provider解析可能异步刷新，scope取消后fallback会写数据库，这些要结合已有隐私/认证/生命周期报告与真实调用进一步复核；本轮不重复泛报日志、也不拿取消fallback当误写其它session的证据。标题返回值长度/JSON null等候选需要对应产品约束，不强行凑新缺陷。

## 固定OpenMinis对照

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的ChatViewModel第16143—16150行自动标题成功分支同样无条件updateSessionTitleAndCategory并更新UI。自动分支残留是双方共有风险，不说新提取引入回归；远端手动路径的条件更新按最终源码核对，不依据修复提交标题宣布自动调用也已修好。只读固定Git对象，未fetch上游。

## 覆盖与交付检查

| 全文文件 | 行数 |
|---|---:|
| [SessionTitleGenerator.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionTitleGenerator.kt#L1) | 562 |
| [SessionTitleGeneratorTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/chat/SessionTitleGeneratorTest.kt#L1) | 53 |
| [AutoGroupPromptSafetyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/chat/AutoGroupPromptSafetyTest.kt#L1) | 127 |

标题模块前100行上一轮已单独完整读取，本轮101—270、271—440、441—562完整输出；两测试完整cat，无截断。相关ChatViewModel/SessionListViewModel/Repository/DAO只看调用片段，不升级全文。

新累计台账：[2026-10-07-code-review-coverage-37158d18-title-generator.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-title-generator.json)。当前全文 **1149 / 1652文件、185901行**；partial4、not_registered499、excluded124，tracked1776。仍未完成全库通读。

交付核验旧140份报告hash、所有旧台账hash、本地旧HEAD/空tracked与staged diff、固定origin/main、1776快照文件与Git对象一致、台账bytes/hash/lines、新全文742行及报告证据行号。本轮仅新增两份文件。
