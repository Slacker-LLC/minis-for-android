# 旧子Agent与Ralph入口、报告合同静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮 **没有新增已确认生产缺陷**；全文读取2文件、386行，补充潜伏解析候选和既有问题复核。只记录问题，未改产品源码，未运行测试、构建、模拟器、设备、Root、云或任何Agent任务。旧报告不改。

## 旧模式SubagentTool完整调用核验

[SubagentTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/SubagentTool.kt:45) 校验prompt、深度上限、App实例后通过AgentRunner.ensureSession创建新的child，并标记内存depth；标题/source更新为尽力操作。prompt采用wait=true与SubagentLimits预算。返回答复限定60000字符并显式注明截断，Completed采用ignoreCase，必须非空且未timedOut才返回成功；失败路径保留partial输出与child ID。

[ToolRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:459) 的AgentSubagentHandler先按SOURCE_SUB_AGENT拒绝roster child再委派；只有SubAgents关闭时进入本轮SubagentTool。旧工具写入source是字面subagent，不能拿它冒充使用SOURCE_SUB_AGENT的roster child路径。内部depth仍约束同一进程旧调用链；进程重启重置是代码明确的设计，不单凭内存map就指控现役每次绕过深度上限。

catch CancellationException仅重抛，未取消已启动child，是旧F20。现役 [HeadlessChatRunner.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:212) 只在内部timeout得到null时request.cancel；外部取消等待者不在同一清理分支。这次全文复核与旧报告一致，不重复新增，也不将旧模式结果扩大到roster/Bot所有执行链。

本工具没有传parentSessionId给ensureSession或prompt作为配置继承，不能仅凭函数参数名称宣称父模型、权限、记忆和workspace全部继承。实际child配置由既有runner和repository决定；共享/隔离合同必须按真实session backing证明，不能为了实现“共享”退回全局workspace。

## Ralph的已知阻断点仍在

[RalphTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt:164) 用大小写敏感的completed判断完成状态；现役HeadlessChatRunner第229行返回Completed，[AgentRunner.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt:173) 原样映射status。因此正常首回合仍在报告解析前被拒绝。这是F19已记录的直接阻断，不新增计数。

F19已同时记录两项后置风险：每轮新session却提示/var/minis/workspace跨轮共享，与session隔离不符；parseReport按最后一个左花括号和右花括号截取，合法JSON字符串内花括号会误截。此次逐行核对仍存在；不声称当前正常多轮已运行并暴露这些后置现象。Ralph取消不回收child仍归F20。

## 新补充的潜伏报告校验候选，不计新增确认生产项

源码头声明无效/过大report应拒绝、不可静默截短。[RalphTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt:206) 构造RalphReport时，summary先take(2000)，其它字段先take(8192)，第214行才检查重新序列化后的总长。summary为2500普通字符、其它字段短、status合法时，原本超出summary合同的报告会先被截成2000再通过总长检查，静默丢掉500字符。

此外continue缺nextSteps、blocked缺blockedReason都未按提示中的required条件拒绝；optString默认空串，status与summary检查通过后仍可构造report。字段非String的宽松转换也是候选。本轮把这些记作后置验证缺口；当前Completed大小写阻断尚未修复，没有将潜伏解析问题包装成当前正常回合已接受的结果。

activeRuns的get检查和increment分开，表面不是CAS；是否能在现役并行调度中同时通过要看入口执行线程和是否存在挂起点，本段检查到increment之间无suspend调用，不凭两个语句就宣称单线程协程必然发生并发嵌套。parentSessionId在Ralph中未使用，嵌套限制是全进程activeRuns，独立会话也会互相挡住，这是声明的“already active”行为，未自行定义为必须允许并行。

## 固定OpenMinis与测试证据范围

只读本地 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git树，未fetch；没有本轮两个文件同路径实现。不能借Harness移植注释证明报告校验、取消或目录合同正确。没有执行minisd/PRoot或恢复旧runtime。

src/test、src/androidTest检索没有这两个类的直接引用；这只是文本证据，不表示所有其它Agent测试均不存在，也不证明实际模型委派通过。未新增或运行测试。相关runner、registry与旧报告仅片段复核，不重复登记全文。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [RalphTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/RalphTool.kt) | 249 |
| [SubagentTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/SubagentTool.kt) | 137 |

累计全文 **1195 / 1546文件、230323行**；partial13，not_registered338，excluded124。全仓通读未完成。两份文件独立完整返回，所有读取与检索结果已检查。

完成时核对全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前106份报告哈希、本报告文件链接与行号、台账批次总计。只新建本报告并更新覆盖台账。
