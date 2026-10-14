# 会话列表全文审阅：搜索、标题与分组操作修复边界

日期：2026-10-07，固定远端HEAD `a94f98c97d0c478eff8530e9e7758d3b93216b83`。全文6文件、1538行。新建独立报告及台账，保留旧190份报告/全部旧台账。只静态阅读，未修改代码、编写/运行测试、请求模型、构建、模拟器、adb/Root/设备或云任务。

本轮不新增独立缺陷编号：核实SG01/02/03/04/10的修复，并补充SG06备用标题分支的原子性残留。SG11的事件缓冲问题仍在。

## SG01、SG02：全选的初始范围和切换动作已修

[SessionListViewModel.kt:384](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L384)toggleSelectAll使用displayedSessions IDs及toggledSelection。当前可见集合未全选则补齐，全选则移除；不会把搜索未显示的整库ID新加入选择。此前选中、后来被搜索隐藏的ID刻意保留，这是现有测试明确规定的行为，不能把它与旧“首次全选自动选中隐藏会话”混为一谈。

批量deleteSelected保存当前selectedIds快照后逐个执行，立即清UI选择；没有整体事务、逐项失败反馈或撤销。中途失败可能只完成前缀，但本轮没有故障运行证据，不声称批量操作要求全成全败。普通删除入口已经在上一报告核实同一Store释放，不重复计F01。

## SG03：建议晚到污染新窗口的主路径已有取消保护

[SessionListViewModel.kt:456](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L456)dismissGroupPicker取消suggestJob并清建议/错误/busy。[SessionListViewModel.kt:513](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L513)结果写回进入Main，先检查isActive；旧窗口关闭后不会仅靠晚到结果直接覆盖新窗口状态。runGroupSuggestion内部若干catch Exception仍可能吞取消后继续候选扫描，但外层CancellationException重抛与Main检查是真实保护，不能笼统说完全无取消处理。

建议只包含选择会话的title/category及现有组的name/description/样例标题，不发送本轮完整消息正文；仍有所有现有组数量和标题长度的提示词预算候选。候选模型按Light优先，随后按session.modelId找primary再遍历其它eligible；这不等于精确按会话entry绑定选择Provider，真实错误计费需结合配置和选择语义再确认。

applyGroupChoice快照request后异步写库，创建组与分配会话为两次操作；失败可能留下空组，成功后dismiss当前picker。如果用户在提交期间打开另一picker，是否有未禁用的可达交错需完整UI核对。本轮不在仅有VM方法的基础上重开SG03编号。

## SG04：数据库变化进入搜索刷新链

[SessionListViewModel.kt:332](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L332)搜索把_allSessions纳入combine，300ms debounce后collectLatest执行查询和snippet构造。displayedSessions同时用liveSearchResults按当前_allSessions移除已删除行、更新存活实体，保持查询结果顺序，不新造匹配行。

搜索正文snippet仍逐会话loadMessages并扫描，查询/片段整体没有本类的页数或时间预算；取消与Room返回行为需后续真实长库验证。错误异常没有本类的用户反馈/重试边界，未凭没有catch就宣称正常搜索必然崩溃。

## SG10：Unicode坐标混用已修

[SearchHighlight.kt:30](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SearchHighlight.kt#L30)高亮直接在原text做ignoreCase indexOf；snippetAround也使用原串索引，不再用lowercase后的坐标截原串。原İA及长串İ的确定越界根因已处理。负radius并非本轮实际调用，不为可传非法参数另报现役问题。

## SG06残留：模型成功用条件写回，失败备用标题仍先读再写

[SessionListViewModel.kt:952](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L952)成功标题调用updateGeneratedTitle，带请求开始时title/category。数据库不匹配时返回superseded且不写回，旧手动生成成功分支已修。

但[SessionListViewModel.kt:1013](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L1013)applyFallbackTitle先getSession读标题，认为仍空或New Chat后，[SessionListViewModel.kt:1031](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L1031)调用普通updateSessionTitle。静态窗口：读取New Chat → 用户手动保存“我的项目” → fallback普通UPDATE写首条消息摘要。重读只能保护重读之前的编辑，不能保护读和写之间的提交。

沿用SG06，不新增重复编号；这与自动成功分支残留同属晚到写回保护未覆盖全部调用方。方向记录：备用写入也应遵守提交时条件判断，只在实际写入后发布成功，复用现有DAO/Repository机制。后续授权验收分别延迟模型成功及fallback提交，在期间改名，保存数据库时间线与最终标题。本轮未运行。

固定既有OpenMinisHEAD`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`的[备用标题955—979](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L955)同样先get再普通update。仅读950—982片段、未fetch；不能用其相同实现证明原子性已满足。

## 分组删除提示和实际执行范围仍须区分

[FolderActionDialogs.kt:122](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/FolderActionDialogs.kt#L122)确认文字现在随memberCounts更新，不再固定开窗旧数量，是SG07的可见改进。[SessionListViewModel.kt:730](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L730)执行仍重新查询memberIds后逐个删；确认和执行不是同一成员快照，成员可在之后改变，不宣布SG07所有时序已闭环。

FolderActionDialogs确认回调立即关闭；rename/dissolve/delete动作异步执行，本层没有失败后恢复对话框和保留输入的反馈。deleteFolderWithSessions只有完成后调用onDeleted，列表[SessionListScreen.kt:1255](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt#L1255)仍未传该回调。SG08所涉当前会话导航清理需完整导航状态核对，不能仅因新增默认回调就结案。

分组rename字段有初始值和description长度上限；名字空白时Repository会拒绝，但Dialog保存动作本身未按写入结果保持显示。删除同时会话的操作与“解散组保留会话”分开，不能凭注释“唯一删除方式”误判deleteFolderWithSessions不存在。

## SG11与安全模式监听候选

newTopSessionEvent仍replay=0、extraBufferCapacity=1；列表只在组合期间collect，VM新ID检测会更新knownSessionIds。上一报告SG11的无订阅者事件丢失前提仍成立，未重复编号；本轮没有重新查网络文档或声称宽屏持续订阅也必然丢失。

安全模式分支注册clear listener后started.await，unsub不在finally；VM等待期间被清除会跳过退订。CrashFrequencyDetector的register只加入CopyOnWriteArrayList，未立即检查当前safe-mode状态。监听留存以及“检查后已清、注册错过边沿”的候选需结合真实Main调度与safe-mode入口，不计为已复现卡死。folders观察没有同样安全模式等待，不能将这个分支描述为所有Room读取都被暂停。

## 三个已有测试范围

LiveSearchResultsTest33行3例覆盖删行、存活title刷新及顺序、不凭全表发明搜索结果；SessionSelectionTest36行5例覆盖全选/取消、部分补齐、隐藏已选保留和空列表；SnippetAroundTest41行5例覆盖大小写、无匹配、İA、长串İ及两侧省略。它们调用生产helper，但不执行Room/VM/Compose整条链，没有测试Provider晚到、fallback并发、组成员变化或UI错误反馈。只阅读未运行。

## 覆盖及交付

| 本轮全文 | 行数 |
|---|---:|
| [FolderActionDialogs.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/FolderActionDialogs.kt#L1) | 165 |
| [SearchHighlight.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SearchHighlight.kt#L1) | 59 |
| [SessionListViewModel.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt#L1) | 1204 |
| [LiveSearchResultsTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/sessions/LiveSearchResultsTest.kt#L1) | 33 |
| [SessionSelectionTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/sessions/SessionSelectionTest.kt#L1) | 36 |
| [SnippetAroundTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/a94f98c97d0c478eff8530e9e7758d3b93216b83/src/android/app/src/test/java/com/openminis/app/ui/sessions/SnippetAroundTest.kt#L1) | 41 |

SessionListViewModel按1—250、251—500、501—750、751—1000、1001—1204完整读取，其它文件cat完整无截断。SessionListScreen只追加580—608与1210—1262片段；CrashFrequencyDetector85—112和Repository117—138已全文文件的片段复核不重复累计。

新台账：[2026-10-07-code-review-coverage-a94f98c9-session-list.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-a94f98c9-session-list.json)。累计全文 **1371 / 1662文件、229137行**；partial13、尚未登记278、excluded124，tracked1786。全库通读仍未完成。

交付校验旧190份报告/全部旧台账hash、本地/远端/上游HEAD、tracked/staged代码diff、1786快照Git blob/SHA-256/字节/行数和引用行号。只新建本文及台账。
