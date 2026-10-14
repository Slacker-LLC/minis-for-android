# 会话CLI日期修复与旧NativeOffload退役核对

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。全文静态读取4文件、594行，另重读已登记OffloadArgs及相关桥接片段。旧182份报告及全部旧覆盖台账保持原样。本轮沿用DB10、DB11、GH02和F40核实修复状态，无新的独立缺陷编号。未修改代码、编写或运行测试、运行CLI、构建、启动模拟器、adb、Root、设备或云任务。

## DB10：错误日期与尾随垃圾已拒绝，缺值/空值仍扩大查询

[SessionsOffloadHandler.kt:55](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt#L55)在分发list/search/messages前验证非空日期；[SessionsOffloadHandler.kt:317](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt#L317)使用非宽松SimpleDateFormat和ParsePosition要求消费全部trim后的字符串。nonsense、不存在的日期、有效日期加xyz的旧输入不再直接变成无边界查询，返回INVALID_ARGS及退出码2。

剩余边界是参数“存在但没有有效内容”。OffloadArgs将末尾独立的--start或后接其它flag的--start登记为布尔flag，get("start")返回null；--start=登记空字符串；--start "   "登记空白字符串。handler只在!raw.isNullOrBlank()时校验，因此三者跳过错误分支，parseDate再次将其视为null。--end同理。这些输入可以让查询继续返回ok=true并省略本来显式指定的时间边界。

沿用DB10部分修复，不新编号。未声称没有提供日期的合法调用需要报错，也未把缺值问题描述成SQL注入或权限越界。修复方向是区分参数不存在与存在但缺少值，并在查询前统一拒绝后者，复用现有parser能力即可。

待后续授权验收：通过三个CLI子命令分别输入独立--start、--start=、纯空白值、--end缺值、有效日期及完全省略日期，保留退出码/JSON和命中时间范围；确认仅合法缺省可以无过滤。没有执行这些命令。

## DB11：共享格式器竞态及初始化时区固化已修复

[SessionsOffloadHandler.kt:331](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt#L331)每次格式化新建SimpleDateFormat，默认时区也每次取得；parseStrictDate同样局部创建。旧多线程共享同一可变formatter及进程初始化后一直使用旧时区的根因不再存在。

一次请求内每个字段分别读取默认时区，parseEndDate又用Calendar.getInstance取得时区，没有冻结统一请求时区。如果用户恰在请求处理中更换时区，仍是更窄的候选；这不是原共享formatter竞态，当前不据此另报生产故障。

结束日按Calendar增加一天再减1毫秒，保留跨夏令时日长变化的实现。没有将它误写成固定24小时，更没有修改系统时区验证。

## F40：handler已经消费扫描游标，未建立导出快照

cmdMessages调用loadMessagePageWithCursor及同范围count，输出scanned、next_offset和has_more，offset按实际存储行推进；过滤后可见消息数不足limit不会导致把items.size当成扫描量。

这两次数据库读取以及多页查询仍各自独立。原F40的分页口径已经修复，页间并发增删与total漂移仍需单独判断；本轮不重复编号。--full传入50000字符上限，与此前完整读取Repository的投影修复一致，普通模式为600字符。

## GH02：PRoot时期旧接收器确已删除，NativeOffload名仅保留注册表

[NativeOffload.kt:46](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt#L46) NativeOffloadServer现在只持有ConcurrentHashMap注册表、查找/注册方法和旧回复文件清理，没有abstract socket、accept线程、magic/version请求解析、pid/env身份接受或旧文件回包实现。不能继续把旧报告GH02的无认证socket说成当前仍在运行。

MinisApp现役[MinisApp.kt:893](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L893)注册会话handler，[MinisApp.kt:923](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L923)启动旧回复文件清理线程，没有恢复NativeOffloadServer.start。[GuestCommandBridge.kt:234](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt#L234)通过现役handler.handle构造请求，保留sessionId/cwd/stdin上下文。本轮只复核桥接片段，不以此声明其认证与所有handler已经完整通过验证。

NativeOffload的79行保留Request/Result/Handler类型，是App与guest原生功能桥的边界；它本身不拥有Ubuntu进程、mount namespace或chroot生命周期。类名残留不等于minisd/PRoot复活。

旧回复清理只遍历App-owned workspace及sessions的offloads目录，删除以.native-offload-开头的普通文件；没有执行清理。listFiles返回null与File.delete=false没有主动反馈这一较窄诊断候选未形成运行失败证据，不另立项。没有因此把受控一次性清理当成现役旧回包路径。

## 测试范围与固定OpenMinis对照

SessionsCliDatesTest66行的5例覆盖本地午夜、不同显式时区的epoch差、非法日期/尾随垃圾、显式时区格式化与并发helper格式化。非法日期例调用parseStrictDate，未调用handler.handle；名称“notAnUnboundedFilter”不证明缺值参数被入口拒绝。没有handler三子命令、parseEndDate夏令时日界、默认时区运行中改变或查询范围的端到端证据。

NativeOffloadRegistryTest57行的2例覆盖注册后查询/调用，以及旧前缀文件被删、普通文件保留。没有真实生产生命周期启动，也未证明跨UID/SELinux行为；注册表测试的临时名字留在全局map是测试隔离候选，当前未发现相撞用例，不编造失败。全部只阅读，未执行或新增测试。

固定OpenMinisHEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。最初按当前runtime/guest路径查找无同路径文件，随后Git树找到sandbox/offload/SessionsOffloadHandler.kt及sandbox/NativeOffload.kt，仅读取既有对象，无fetch。旧上游parseDate仍忽略失败且不要求完整消费；NativeOffload仍有abstract socket start/accept生命周期。当前生产实现已退出这条旧路径，不能拿旧上游接收器替代当前事实，也不能把上游旧实现作为日期修复依据。

## 全文覆盖与交付核验

| 本轮新增全文 | 行数 |
|---|---:|
| [NativeOffload.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt#L1) | 79 |
| [SessionsOffloadHandler.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt#L1) | 392 |
| [NativeOffloadRegistryTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/runtime/guest/NativeOffloadRegistryTest.kt#L1) | 57 |
| [SessionsCliDatesTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/runtime/guest/SessionsCliDatesTest.kt#L1) | 66 |

四文件首尾完整读取，输出无截断。OffloadArgs已全文登记，本轮重读不重复计数；桥接、启动及上游只是相关片段，不新增全文覆盖。一次组合检索输出截断后，已单独补读用于判断的日期及旧入口证据；搜索结果本身不当作全文。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-sessions-offload.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-sessions-offload.json)。累计全文 **1333 / 1655文件、223619行**；partial5、not_registered317、excluded124，tracked1779。全部代码通读仍未完成。

交付校验旧182份报告及全部旧台账hash、本地/远端/上游固定HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines及引用行号。仅新增本报告与新台账。
