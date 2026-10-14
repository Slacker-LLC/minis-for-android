# 设备上下文、定位新鲜度与模型映射审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2**，全文阅读 9 个文件、706 行。只做静态审查；未改产品源码，未运行测试/构建/模拟器/设备/Root/云任务，未读取实际定位或账号配置。独立报告，旧报告不改。

## CT01 / P2：定位按墙钟排序和计龄，旧位置可能被当作最新且年龄为零

[DeviceContextTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/DeviceContextTool.kt:119) 从多个已启用 provider 获取 lastKnownLocation，以 it.time 最大值选 freshest。[DeviceContextTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/DeviceContextTool.kt:131) 再用 currentTimeMillis-location.time 计算 age_s，负数直接夹为零。

静态条件：同一开机周期中，provider A 在墙钟 T 时取得定位；之后设备时间回拨一小时，provider B 又产生一个实际更晚的定位，墙钟却小于 T。两个 provider 都启用且返回缓存时，maxByOrNull 选中更旧的 A；当前墙钟小于 A.time，年龄被夹为 0。响应仍是 status=last_known，模型收到旧坐标与零年龄。即使没有第二个 provider，旧缓存也可能因回拨被显示为零年龄。

[Android Location 官方文档](https://developer.android.com/reference/android/location/Location#getTime())说明不同 provider 的 time 不保证来自同一时钟，设备墙钟可以前后变化，不应用它排序比较定位。[elapsedRealtimeNanos](https://developer.android.com/reference/android/location/Location#getElapsedRealtimeNanos())可在同一开机周期内可靠比较，LocationManager 产生的位置会提供该时间。这里问题不只限手动改时间，GPS 与其它 provider 的时钟差也会影响墙钟排序。

工具描述承诺的是“最新已知定位”，允许缓存，不要求每次获取新 fix；本项不是因缓存存在就判错，而是最新选择与 age 的证据不可靠。MinisApp 590 实际注册 DeviceContextHandler，结果供 Agent 读取。没有设备复现、实际坐标、年龄误差测量或定位权限变更。

修复方向：在当前开机周期使用定位的单调时间选择和计龄，保留 wall-clock 时间作展示；明确跨重启、无效时间与未知年龄的反馈，不把未知/负差强制包装成新鲜。复用平台已有字段，不新增定位请求。这里只记录。

## 其余设备上下文核对

时间环境以传入 instant/zone 输出 ISO 时间、UTC offset、weekday、locale 和 DST，formatter 每次新建，不共享 SimpleDateFormat。已有测试覆盖上海日期、纽约冬夏偏移和中英文星期，只读未运行。

定位先检查 fine/coarse 已有权限；无权限给状态与 hint，不主动请求权限或定位更新。缺缓存、单 provider 读取失败有 unavailable/忽略处理，不能说任何失败都会使整个结果崩溃。foreground 仅在 Accessibility 已连接且 PackageWindowVisibility.VISIBLE 时报告包名，未知状态不会捏造前台应用。

screen/battery 缺 manager 时部分字段落到 false/-1，网络 transport 在同时含 VPN 与 Wi-Fi/cellular 时优先前者底层类型，均属于状态口径候选；未证明正常设备一定缺 manager，也没有将 transport 等同“绕过 VPN”。状态对象没有明确模拟全部平台故障，本轮不新增这一类缺陷。

## 模型、标题与旧 Web 映射核对

ModelSelectionResolver.resolve 支持 entryId/baseModelId，返回真实 instance label 或 instanceId，wire 使用 entryId；unknown 返回 null。若多个实例有相同 baseModelId，firstOrNull 会选首项，entryId 与另一项 baseId 冲突也可能被首项抢先。但全量 main/java 引用检索只见对象定义，没有当前生产调用，调用仅在 ModelIdentityAndCommandRegistryTest。因此不将这种歧义报告为现役模型跑错，不用过时注释替代调用链证据。

DshReasoningCatalog 同样只有测试调用。supportsReasoning=false 不输出推理块，null 保守保留能力；OFF-first 到 ceiling 的映射符合已有测试。注释明确旧 Web 工作台下线，本轮登记遗留纯映射的覆盖，不提出恢复 DSH/旧服务器。

ChatTitleNormalizer 仍被 ChatDebugMethods、ChatMutationMethods、HeadlessChatRunner 使用，将 null/JSONObject.NULL/blank 规范为空字符串；不是与两个纯映射同样无生产调用。非字符串的 toString 是已有明确策略，未据此认定异常标题已污染数据库。

ModelIdentityAndCommandRegistryTest 160 行亦全文读取，覆盖单模型身份、wire、tri-state、默认 plan 与命令列表；部分断言依赖手写 handled 集合，不能证明真实所有 handler 已执行。本轮没把测试名当端到端证据，没有运行或新增测试。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，完整 tree 和 tools/remote 子树检索未见本轮同名 DeviceContext/ChatTitle/ModelSelection/DshReasoning 实现，未 fetch。不据此声称上游没有其它设备上下文或模型身份处理。Eta 来源仅为本地注释声明，本轮未独立核实。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ChatTitleNormalizer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/remote/ChatTitleNormalizer.kt) | 25 |
| [DshReasoningCatalog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/remote/DshReasoningCatalog.kt) | 71 |
| [ModelSelectionResolver.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/remote/ModelSelectionResolver.kt) | 71 |
| [DeviceContextTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/DeviceContextTool.kt) | 154 |
| [DeviceContextPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/context/DeviceContextPolicy.kt) | 46 |
| [ChatTitleNormalizerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/remote/ChatTitleNormalizerTest.kt) | 36 |
| [DshReasoningCatalogTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/remote/DshReasoningCatalogTest.kt) | 66 |
| [ModelIdentityAndCommandRegistryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/remote/ModelIdentityAndCommandRegistryTest.kt) | 160 |
| [DeviceContextPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/context/DeviceContextPolicyTest.kt) | 77 |

累计全文 **1106 / 1546 个文件、218717 行**；partial 13，not_registered 427，excluded 124。全仓通读尚未完成。九个文件全文输出均无未修复截断；MinisApp/调用方仅片段引用复核，不重复计数或升级 partial。

完成时核对全部 1670 tracked 路径、SHA-256、字节数及正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 87 份报告哈希、本报告链接/行号及批次全文总计。只新建本报告并更新覆盖台账。
