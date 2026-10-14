# 聊天文件范围与文件浏览器全文审查

日期2026-10-09，固定主线`3527ad8165f11c0f98d8a30f96098fc9280faa94`。保留旧200份报告和所有台账，只新增本文和新版台账。未改产品代码、编写/运行测试、构建、启动模拟器或设备、使用Root/云资源/凭据；只读源码和Git对象。

## CFS01 / P2：聊天文件首页把读取失败当成空目录，并清除错误

[FileBrowserViewModel.kt:505](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt#L505)仅在guestSessionId非null且directory=/var/minis时合成四个目录。每个info包runCatching，异常变null；listAll异常变emptyList。失败的目录因此不满足holdsFiles而被丢弃，整个流程继续到成功发布，errorMessage=null。

[WorkspaceFileClient.kt:178](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt#L178)list通过requirePath及SecureFileAccess列目录，真实I/O/路径解析错误可抛异常；listAll没有把它们都变成功空列表。所以这里不是消费“已约定空列表就是不存在”的API，而是UI自己隐藏失败。入口[AppNavigation.kt:947](https://github.com/Slacker-LLC/minis-for-android/blob/3527ad8165f11c0f98d8a30f96098fc9280faa94/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt#L947)确实传sessionId及guestRootPath=/var/minis。

条件反例：workspace实际有文件，但此次listAll由于目录访问/I/O或路径不可用失败，attachments等为空。首页返回items空、isEmpty=true、errorMessage=null，用户得到空会话的外观。单个目录失败则得到看似成功的部分列表，也没有提示遗漏。未制造真实设备失败，不声称文件被删除、权限被绕过或每次打开都会出错。

其它普通guest目录读取失败有外层catch写errorMessage；新合成首页却绕开它。记录方向：区分确实空/不存在与读取失败，部分结果保留可见错误，优先复用现有errorMessage状态，不能仅把所有异常吞成空。只记录，交Claude处理。

## CFS02 / P3：首页的有内容判定与隐藏文件开关不一致

相同holdsFiles检查只排除workspace的attachments/offloads/browser挂载点，没有过滤隐藏文件，也未调用guestItem的有效条目筛选。之后guestItem仅作用于合成的目录名字（workspace等本身不是dotfile）。因此showHidden=false时，只含.git、.cache或单个隐藏文件的workspace仍被显示；进入workspace，guestItem又会过滤所有点开头条目，显示空列表。

这是显示口径不一致，不是文件丢失，也不建议删除隐藏内容。新功能承诺只列有内容目录，至少应明确它指实际内容还是当前可见内容；按当前可见列表处理时要复用同一过滤规则。showHidden=true情况下隐藏内容可见，反例条件不成立。空子目录本身也被当内容，但尚无明确“必须递归找普通文件”需求，不扩大为第三项缺陷。

## 最新修复与范围限制

latestLoad现在在init之前初始化，解决guest浏览构造时null属性的根因；FileBrowserViewModelInitTest以UnconfinedTestDispatcher直接创建真实ViewModel，不再只验证纯helper。它只assertNotNull(uiState)，没有验证guest读取成功、内容、错误状态或实际Android导航；本轮未运行，不能宣称设备验收通过。

ChatFilesScopeTest真实调用mount点判定，验证顶层dir隐藏、文件同名保留、深层同名目录保留。没有测试合成首页、dotfile-only目录或I/O异常。名称匹配不是挂载探测，但这些三个顶层目录在现役布局是明确mount占位，不能仅凭按名字隐藏就推断普通用户目录必然丢失。

最新ticket过滤已有改善，但isCurrent到rawItems/state发布之间仍不是原子事务，setSort和IO发布也读写同一rawItems/_uiState；先列为后续交错核验候选，不仅凭AtomicLong就声称所有过期发布已彻底消失，也不重复旧列表竞态家族。

WorkspaceFileClient全文287行重读，保持目录句柄路径解析、外部只读mount写拒绝、50MiB读取/写入限制、分页listAll。uniqueChildPath仍只读前500项并吞失败，属于旧下载文件名冲突问题，未新编号。global memory/skills只读页面问题仍需结合最新入口标记逐项核验，不在本文宣布所有旧缺陷继续成立。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/sandbox/ChatFilesScope.kt | 19 |
| src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt | 628 |
| src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt | 287 |
| src/android/app/src/test/java/com/openminis/app/ui/sandbox/ChatFilesScopeTest.kt | 25 |
| src/android/app/src/test/java/com/openminis/app/ui/sandbox/FileBrowserViewModelInitTest.kt | 35 |

FileBrowserViewModel按1—220、221—430、431—628完整阅读；末段初次组合输出后单独补读。其它四文件完整输出。导航仅936—962片段，不算全文。新增全文4文件、707行；重读已全文1文件、287行，不重复累计。

累计全文1293 / 1758文件、193575行；partial 5，未登记460。全库通读仍未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-chat-files.json)。

已校验旧200份报告/所有旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数及源码tracked/staged diff；只新增报告和台账。没有覆盖旧文件。
