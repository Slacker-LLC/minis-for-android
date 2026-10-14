# ChatRepository全文复核：截断循环与修复边界

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。全文静态读取4文件、1286行。旧181份报告及全部旧覆盖台账保持原样。新增独立问题CFR01；其它沿用既有编号更新判断，不重复计数。未修改产品代码、编写或执行测试、构建、启动模拟器、adb、Root、设备或云任务。

## CFR01 — P2：超长消息截断可以进入不收敛循环

[ChatRepository.kt:1047](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1047) 在序列化长度超过500000时，反复选择最长字符串。只有字符串长度不超过1000才退出；实际替换却是至少保留1000字符，再追加截断标记。没有检查新值是否变短，没有迭代边界或协程取消检查。

可静态推导的输入：合法toolResult的snapshot内包含501个字符串，每个1001字符。字符串本身就有501501字符，加上JSON结构必然超过500000。首个被选字段被替换成1000字符加标记，其长度反而超过1001，下一轮仍选这个字段。标记包含当前字段的四位长度；再替换一次后，字段进入固定长度和固定内容，后续循环不再改变JSON，总长度仍超限。

这个反例不依赖异常JSON、超长ID或特殊字符；嵌套数组字符串由[ChatRepository.kt:1073](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1073)递归收集。[ChatRepository.kt:452](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L452) appendMessage在构造MessageEntity和DAO事务前同步调用截断，命中时消息追加无法返回，且每轮重复序列化全部parts。取消调用协程不能靠此无挂起点循环及时停止。未据此断言所有聊天都卡死、UI线程必然ANR或已有用户数据丢失。

这是结构化截断修复新增的终止性问题，与DB02的历史语义丢失分开登记。修复方向是保证每次迭代严格减少序列化长度，并为无法在保留结构条件下达标的情况提供有界退出策略；标记应纳入实际预算，不能用1000字符的内容下限替代可收敛性。

待后续授权的端到端验收：通过真实消息追加入口持久化上述形状的工具结果，保存输入摘要、追加开始/结束事件、落库内容、会话恢复与取消响应证据；确认追加有限时间返回、结构及缺失提示可解释。当前没有执行复现或任何测试。

## DB02只是部分修复，不能按“保留JSON”直接结案

[ChatRepository.kt:1041](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1041)不再一律将整个超长数组包装成text。普通“短ID、短工具名、一个很长输出”的情况确实保留数组、类型和配对，比旧实现进步。

但[ChatRepository.kt:1065](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1065)收集所有字符串值，没有区分正文、工具input、签名、ID或媒体身份。很长的工具input若以JSON字符串保存，截断后外层parts仍合法，input内部却可变成不完整JSON；较长签名或其它必须原样保存的身份字段也无豁免。因此保持外层JSON形状不等于保持历史语义。

当数组由大量不超过1000字符的字段组成时，循环直接break，[ChatRepository.kt:1057](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1057)仍回退为单个text，类型/配对丢失的旧反例继续存在。[ChatRepository.kt:1082](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1082)回退先取500000字符，再加标记和JSON包装，最终序列化长度还会超过500000；本报告不把字符限额误写为固定字节或CursorWindow上限。以上归入旧DB02剩余边界，不新增重复编号。

DB03仍成立：[ChatRepository.kt:466](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L466) reasoningContent未受正文限额约束；updateMessageParts也直接写入。不能凭此次截断改动宣称整行读取问题已杜绝。

## 已修复的加载、删除和搜索字符边界

DB04原“逐行读取失败就丢行、整页失败提前终止”的路径已改变。[ChatRepository.kt:368](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L368)为失败行构造占位，查询轻量header保留真实ID/role/sortOrder；正常页后继续按扫描行数推进。已有设备测试此前另报告复核，本轮没有重复运行。header自身失败时仍生成替代ID，这是更窄的候选边界，当前没有足够正常生产触发证据另行立项。

DB09草稿清理使用带仍为空条件的删除，DB12会话删除[ChatRepository.kt:167](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L167)只调用一次dao.deleteSession，利用关联表级联，不再先单独删消息。数据库原反例已修；外部资源完整释放仍需按原有生命周期报告分别判断。

DB06的百分号、下划线和反斜杠字面量转义[ChatRepository.kt:1141](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1141)已增加，相关查询配套ESCAPE。它不修复DB07：查询仍在编码后的parts_json做预筛选，用户可见正文中的引号/反斜杠仍可能与编码表示不同。

DB05仍存在：[ChatRepository.kt:740](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L740)只过滤提取后为空的正文，[ChatRepository.kt:859](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L859)找不到关键词仍返回前缀；只在隐藏元数据命中的非空消息依旧可能占额度，limit×3候选读取也未继续补足。

## 导出投影修复及剩余快照边界

F39的CLI --full投影已保留顺序、工具输入和完整工具结果，再按请求的50000字符上限明确标记截断。短投影仍允许摘要，这是两种不同用途，不能把短模式当作full修复失败。

F40的扫描口径[ChatRepository.kt:803](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L803)已经改为rows.size、nextOffset=offset+rows.size，避免按过滤后的items.size续页。count、OFFSET分页和多页导出仍没有统一读取快照；并发增删造成页间漂移这一剩余边界不因游标字段修复而消失。

## 测试实际覆盖与OpenMinis对照

ChatRepositoryTruncationTest73行的5例涵盖短ID的长输出、文本加短tool_use、两个大字段、嵌套长字符串和非数组回退。没有大量约1000字符字段、进度不变、长input内部JSON、签名或回退输出限额断言；使用的tool_result/tool_use扁平形状也没有直接覆盖所有现役camelCase value编码。测试名称和成功断言不能证明未覆盖分支已安全。

LikeContainsTest25行的3例覆盖ASCII、转义顺序和反斜杠。SessionsExportProjectionTest46行的2例使用camelCase/value形状，覆盖full顺序、1200字符工具输出及短模式缺失标记。全部仅阅读，未执行；没有新增单元测试。

固定OpenMinisHEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，仅GIT_NO_LAZY_FETCH=1读取既有Git对象。其截断helper仍直接包装原JSON前缀为text，没有本项目新增的最长字段循环；故CFR01是当前修复实现的独立缺口，旧上游不能作为完整修复方案。其追加预览在preview为空时touchSession，这个行为对照此前DB01已有登记，本轮不重复编号。未fetch上游或调用运行时。

## 本轮覆盖及交付校验

| 新增全文文件 | 行数 |
|---|---:|
| [ChatRepository.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L1) | 1142 |
| [ChatRepositoryTruncationTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/repository/ChatRepositoryTruncationTest.kt#L1) | 73 |
| [LikeContainsTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/repository/LikeContainsTest.kt#L1) | 25 |
| [SessionsExportProjectionTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/repository/SessionsExportProjectionTest.kt#L1) | 46 |

ChatRepository按1—290、291—580、581—870、871—1142分段完整读取，其余三文件首尾完整读取，无截断；DAO和上游只复核相关片段，不增加全文覆盖。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-chat-repository.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-chat-repository.json)。累计全文 **1329 / 1655文件、223025行**；partial5、not_registered321、excluded124，tracked1779。全库通读仍未完成。

交付校验保留旧181份报告及全部旧台账hash；固定本地/远端/上游HEAD；tracked与staged无代码diff；1779个快照的Git blob/hash/bytes/lines；引用行号有效。仅新增本报告和覆盖台账。
