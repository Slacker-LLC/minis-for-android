# 网络监听与内容性能诊断静态审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2、1 项 P3**；全文阅读 7 个文件、872 行。未改产品源码、未运行测试/性能测量/构建/模拟器/设备或云任务。独立新报告，旧报告不改。

## DG01 / P2：诊断表格正则跨行吞空白并有重叠量词，在界面路径增加长文本回溯风险

[ContentDiag.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/ContentDiag.kt:48) 的 TABLE_SEP 以 multiline 行首开始，但使用可跨行的空白量词，且可选竖线两侧是两个相邻可匹配同一段空白的星号量词。[ContentDiag.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/ContentDiag.kt:71) 对完整文本执行 findAll/count，没有输入上限或逐行边界。它不能据注释被视为无条件 O(n) 扫描。

[Android Pattern 官方文档](https://developer.android.com/reference/java/util/regex/Pattern)定义空白字符包含换行，并说明 multiline 让行首锚点在换行后匹配；multiline 不把其它字符类限制到单行。基于这两点与本地模式的静态推断：大量连续空白行后接不能满足表格分隔符的普通字符，首个空白量词可扫描多行，再在后续必需的连字符匹配失败时回退；可选竖线缺席时，前后两个空白量词还存在不同分配方式，后续行首会再次尝试剩余文本。该表达式允许重复扫描/回溯，没有线性工作量保证。这不是本轮执行的压测，未给出具体秒数、复杂度实测或确定 ANR 阈值。

影响链：[LargeContentGuard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/LargeContentGuard.kt:228) 在 Compose remember 中为 streaming degrade 直接 summarize(content)，没有使用注释声称的尾部窗口；[LargeContentGuard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/LargeContentGuard.kt:269) 的 DisposableEffect 同样直接摘要；[ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3524) 在布局后的冷开诊断中逐条摘要达到 5000 字符的消息。这里诊断工作发生在 UI 生命周期路径，无后台调度或摘要字符预算。长度门槛是下限，不能保护大输入。用于定位渲染卡顿的诊断因而可能自己增加卡顿，与实际 Markdown 解析的慢路径不同。

修复方向：采用有单行边界的扫描、避免重叠空白量词，并限制诊断成本；需要完整统计时复用后台/缓存边界。后续验证应比较等长度普通文本、空白长段和空白行尾不匹配输入，保存界面线程及耗时证据。本轮不执行或编写测试。

## DG02 / P3：重开会话不重置行组合计数，性能里程碑漏报或带旧时间

[PerfLongCtx.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/PerfLongCtx.kt:57) 的 click 只更新 clickNsBySession/lastNsBySession；rowComposeCount、rowComposeStartNs 和 rowComposeTypes 没有重置。[PerfLongCtx.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/PerfLongCtx.kt:95) 按 sessionId 的已有计数继续加，只有计数恰好为 10/50/200 才报告。[PerfLongCtx.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/PerfLongCtx.kt:82) 的 end 也只移除 lastNs。

静态反例：第一次打开同一 session 已组合 200 行，再退出并点回该 session；click 更新点击时间，但 row counter 从 200 继续，所有固定里程碑均不会再次命中，第二次打开没有“前 10/50/200 行”证据。若首次只组合 5 行，第二次累计到 10 时记录的 sinceFirstRowMs 仍从首次首行计时，包含离开期间，byType 也混合两次打开。

[ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3454) 的行 compose 实际调用这个 helper；SessionListScreen 的点击入口实际调用 click，不是无调用方代码。计数还在每次组合时递增，不能把它当唯一行数。影响是诊断结果失真/缺失，不是会话消息丢失或真实渲染必然变慢。该文件注释说重进会再次到里程碑，但实现没有完成对应重置。

修复方向：按一次真实进入生命周期重置或代际隔离行统计，明确重复组合与唯一行口径，并清理不再需要的 session 统计。没有修改诊断代码。

## 网络监听核对与不计数候选

[NetworkMonitor.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/network/NetworkMonitor.kt:76) 已避免重复注册；当前优先 default network callback，失败才改用请求所有匹配网络的 fallback，停止时注销。三个长期 LLM provider 共享连接池，evictAll 清理的是空闲连接，不证明正在使用的 streaming call 已取消；本轮没有把它说成请求取消器。

全量引用检索只发现本类写 NetworkStatus，无生产代码读取 networkMonitor.status；不能将其状态误差直接报告为模型被阻止、自动重试被错误关闭或界面弹错。fallback 不聚合 Network 集合，任一 onLost 会设为 disconnected，是条件性候选；正常 default callback 的语义不能与 fallback 混用，且当前状态无人消费，本轮不计为用户功能缺陷。

DNS 回调创建 IO task，但 RootfsManager 使用现有 DnsRefreshCoordinator，再交给 UbuntuKernel 的锁和受控配置写入；没有仅凭 launch 就宣称确定乱序覆盖。网络能力 INTERNET 与端到端 Provider 可用也不同，不额外把未检查 VALIDATED 当普遍缺陷。

## 其它诊断链及上游对照

MemorySnapshot 的 proc 读取失败保留 -1，Java/native 指标单列；EnvironmentBanner 不输出 IMEI/序列号/账号。LargeAllocProbe 仅观察而不限制分配，其 report/栈构造仍可能在严重 OOM 时失败，缺实际证据，不计为正常恢复保证问题。OpenAIProvider 当前给 sizeBytes 传的是 String.length，字节标签不精确；本轮作为诊断口径候选记录，没有推断模型预算被它改变。ContentDiag 的 marker 是单个全局值，不能代表屏上所有消息，未推断真实数据泄漏。

StreamPerfMonitor 的生产 collector 有 finally flush，不能按“没有空 stream tick”宣称永不输出。多页并发时全局状态是否互相覆盖尚需确定实际并存路径，不计新问题。没有发现针对本轮这些诊断对象的同名已有测试；全文只读，没有新增或运行测试。

固定 OpenMinis `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`：ContentDiag 相应正则/完整摘要与 PerfLongCtx 的 click/行计数函数相同，DG01/DG02 在该固定版本也保留。上游 NetworkMonitor 原来直接 registerNetworkCallback 且不支持幂等 start，本地已有 default 优先、fallback、LinkProperties 更新和失败处理；不能将上游旧监听当修复模板。只读 Git 对象，未 fetch。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ContentDiag.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/ContentDiag.kt) | 108 |
| [EnvironmentBanner.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/EnvironmentBanner.kt) | 69 |
| [LargeAllocProbe.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/LargeAllocProbe.kt) | 104 |
| [MemorySnapshot.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/MemorySnapshot.kt) | 108 |
| [PerfLongCtx.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/PerfLongCtx.kt) | 142 |
| [StreamPerfMonitor.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/StreamPerfMonitor.kt) | 99 |
| [NetworkMonitor.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/network/NetworkMonitor.kt) | 242 |

累计全文 **1091 / 1546 个文件、217150 行**；partial 13，not_registered 442，excluded 124。全仓通读尚未完成。其它调用方只片段复核，不升级全文；截断的冷开诊断片段已单独重读。OpenAIProvider 保持原 partial 状态。

完成时核对全部 1670 tracked 路径、SHA-256、字节数及正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff，此前 84 份报告哈希，本报告链接/行号及批次全文总计。只新建本报告并更新覆盖台账。
