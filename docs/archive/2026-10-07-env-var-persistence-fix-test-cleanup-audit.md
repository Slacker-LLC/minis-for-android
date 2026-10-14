# 环境变量持久化修复与设备测试清理复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`，完整静态阅读3文件、730行。此前165份报告及所有台账不覆盖，另建本文。没有修改代码、编写或运行测试、构建、模拟器/adb/Root/设备/云任务，没有读取或操作真实凭据。以下均为源码证据，不宣称设备复现。

## EVT01 / P3 — 设备测试恢复元数据后再找测试项，漏删加密测试值

[EnvVarRepositoryInstrumentedTest.kt:29](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/data/repository/EnvVarRepositoryInstrumentedTest.kt#L29) tearDown先删除当前元数据，恢复original，再构造新EnvVarRepository，从恢复后的entries查找created中的随机ENVTEST key，找到才调用delete。正常测试开始前元数据不含本次随机key，因此不会调用delete。

具体顺序：multilineUnicodeAndQuotedValuesAreStoredExactly第41行成功add随机k；测试后恢复旧env-vars.json，新repo的entries回到原列表，不含k；清理循环跳过k，env_var_values中的k仍在。若开始时没有元数据，tearDown删除文件后entries更是空列表。同样影响成功创建EXISTING的不可读元数据测试；控制字符拒绝或add回滚用例本来不留下value，不能说每例都写入垃圾。

影响限定测试清理：加密prefs累积没有元数据的虚构值，测试“恢复现场”与实际存储不一致。allAsDict只遍历元数据，所以这些测试值通常不注入guest；没有真实用户秘密泄漏证据。此问题独立于旧EV03生产事务缺口，新增编号EVT01。

建议方向：清理必须持有能定位本次写入值的原测试记录，在恢复元数据前完成删除，并明确核验测试前后元数据及测试key不存在。验收应包含有/无原元数据两种环境，成功创建后退出和失败后退出；本轮未实现或执行。测试对metadata本身会操作真实App存储，不应凭“throw-away keys”忽略这一点。

## EV01与EV02原反例已修

[EnvVarRepository.kt:73](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt#L73) isValidValue拒绝NUL/C0/C1控制字符，允许tab/newline及普通Unicode；add/update存原值，不再sanitize过滤。旧EV01的中文/PEM换行静默消失原反例已修。名称trim/uppercase、重复key忽略大小写仍是明确产品规则，不新报为静默秘密值改写。

[EnvironmentVariablesScreen.kt:285](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/EnvironmentVariablesScreen.kt#L285) 编辑初始valueLoaded=false，异步读取前value字段禁用、canSave要求valueLoaded；失败显示加载错误，取消异常重新抛出。旧EV02“读取前输入被覆盖/直接保存空占位值”原反例已修。正常新增允许空value，不把有意支持空值当成无效校验。

可见值列表visibleValues按key缓存，编辑成功后未更新缓存；在变量保持可见且key不变时，列表可能继续展示旧value，重新切换显隐会重读。只作为展示一致性候选记录，不与秘密保存丢失混为一谈。

## EV03事务与错误状态：主要修复，删除仍有失败边界

[EnvVarRepository.kt:84](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt#L84) add/update串行化，encryptedPrefs.commit与saveMetadata失败会回滚内存和对应value，saveMetadata使用旁侧tmp+rename替换，返回Boolean。loadMetadata失败设置metadataUnreadable，CRUD拒绝，isReadable供表单禁用保存；旧“坏元数据被空列表覆盖”原反例已阻断。

[EnvVarRepository.kt:133](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt#L133) delete先saveMetadata、失败恢复entries；成功后调用encryptedPrefs.remove.commit但忽略Boolean，仍返回true，previousValue也未用于恢复。因此prefs删除落盘失败时，旧秘密可能残留而元数据已移除。沿用EV03部分闭环状态，不再新增同族编号；不存在元数据时不会被allAsDict注入，不宣称旧值必然仍可执行。

表单保存Boolean失败显示saveFailed；列表delete调用第246行忽略delete返回值并关闭确认框，没有失败提示。列表entries空时仍只显示“空列表”文案，未按isReadable区分读取失败；编辑表单才展示load error。是原错误反馈范围残余，不把其说成坏文件仍可覆盖。

add/update的回滚commit返回同样未检查，进程在两种存储写入之间被杀也不具跨文件原子事务。tmp rename只保证元数据替换边界，不能宣称加密prefs与JSON所有崩溃次序都完整事务；没有设备崩溃证据，不另报常规保存必然损坏。

## EV04：仓库写入串行已修，调用方读改写仍可竞争

add/update/delete都synchronized(this)，正常MinisApp第461行持有共享repo，修复原同对象CRUD read-modify-write覆盖问题。但[EnvVarsCollection.kt:109](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/collections/EnvVarsCollection.kt#L109) note writer仍先getValue，再update，把秘密旧值作为参数写回。

实际允许的交错：备注writer读value=A；UI update在仓库锁内成功改为B；备注writer之后取得update锁，以先前A和新note提交，最终秘密回退A。单个update锁并不覆盖调用方getValue→update全过程，旧EV04这部分未修；不把新锁描述成完全无并发保护。allAsDict读取也未取得同一锁，可以观察正在改写的entries/value组合，候选边界不扩大为必然跨会话泄漏。

表单保存期间无in-flight禁用，重复新增第二次会被重复key检查拒绝；未基于重复点按推导确定重复数据。

## 现有设备测试证明范围

设备测试完整覆盖原值Unicode/引号/换行、控制字符拒绝、metadata tmp路径失败回滚、不可读metadata禁止写。它们使用真实加密prefs，属于有价值的源码验收设计；未测试delete的commit=false、备注读改写交错、跨存储崩溃或清理后测试key消失。不能仅因测试存在宣布这些边界通过，本轮完全未运行。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取现有EnvVarRepository第55—150行，无fetch。上游仍ASCII/tab过滤、异步apply和直接metadata writeText，当前在原值及错误传播明显改善，不应回退照搬。备注竞争核对当前真实调用方，无需根据上游推断。

## 全文覆盖及保留

| 新增全文文件 | 行数 |
|---|---:|
| [EnvVarRepositoryInstrumentedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/data/repository/EnvVarRepositoryInstrumentedTest.kt#L1) | 92 |
| [EnvVarRepository.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/EnvVarRepository.kt#L1) | 215 |
| [EnvironmentVariablesScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/EnvironmentVariablesScreen.kt#L1) | 423 |

Repository1—215、设备测试1—92全文；UI分1—220/221—423。其它调用方只片段核对，不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-env-var.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-env-var.json)。累计全文 **1271 / 1652文件、209256行**；partial4、not_registered377、excluded124，tracked1776。全库通读未完成。

交付校验旧165份报告及旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines和证据行号。只新建本文与本轮台账。
