# 会话管理、分组、复制与搜索静态审查

日期：2026-10-05。当前源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。对照仓库：本地 `/tmp/openminis-review-20261004`，openminis 固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。没有更新这两个仓库，也没有修改产品代码。

本轮新增 12 项静态发现，另建报告，不追加到旧报告。P1 表示可能扩大破坏性操作的目标范围；P2 表示功能、状态或数据语义错误；P3 表示显示准确性问题。以下触发步骤是待执行的端到端验收设计，不是设备实测结果。

| 编号 | 优先级 | 问题 |
|---|---|---|
| SG01 | P2 | “取消全选”仍然执行全选 |
| SG02 | P1 | 搜索后的全选包含未显示的会话 |
| SG03 | P2 | 旧分组建议的晚到结果进入新的选择窗口 |
| SG04 | P2 | 搜索结果不随数据库中的会话变化刷新 |
| SG05 | P2 | 列表复制入口遗漏设置和压缩记录 |
| SG06 | P2 | 标题生成的晚到响应覆盖后续手动编辑 |
| SG07 | P2 | 分组删除提示的数量与实际删除集合可以不同 |
| SG08 | P2 | 删除分组中的当前会话没有清理聊天选择 |
| SG09 | P2 | 批量导出按钮的处理函数为空 |
| SG10 | P2 | Unicode 小写转换后的索引被用于原字符串，存在越界路径 |
| SG11 | P2 | 列表未订阅时，新会话滚动事件被丢弃 |
| SG12 | P3 | 相对时间和日期分桶不会随时间推进更新 |

## SG01 — “取消全选”没有取消动作

[选择栏按钮](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:701)根据选择数量切换“全选／取消全选”文案，但始终调用同一个 `selectAll()`。[实现](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:359)只把全部会话 ID 写入选择集合，没有反向分支。

未搜索时，进入选择模式、全选，再点击“取消全选”，会话仍然全部选中。该问题与 SG02 的搜索范围错误独立。固定上游也存在同样的按钮和实现。

修复方向与待执行验收：按钮行为应与当前文案一致。用两个已保存会话依次执行全选和取消全选，保存每一步可见勾选状态及选择 ID，确认第二步集合为空。

## SG02 — 搜索后的全选扩大了批量操作目标

[显示列表](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:161)在搜索时使用 `searchResults`；[全选](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:360)却使用 `_allSessions`。搜索状态下可以从行菜单进入选择模式，进入选择不会清除查询。随后，[删除](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:368)和[移动到分组](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:415)都使用这个全库选择集合。

例如三个会话只有一个匹配查询，点击全选会选中三个，后续批量删除或移动也针对三个。工具栏数量会反映集合大小，但未匹配的目标行并未显示；不能把可见搜索结果视为操作范围。这里没有声称实际发生过误删。固定上游沿用相同数据源错误。

修复方向与待执行验收：明确全选针对当前可操作的显示集合，并排除未持久化占位行。用三个会话、一个匹配结果走搜索、全选、分组及删除入口，保存确认界面、选择 ID 和操作后的数据库记录，确认隐藏会话不被包含。

## SG03 — 分组建议没有绑定当前请求

[关闭选择窗口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:427)重置全局建议状态，却不取消请求，也没有使请求版本失效。[异步建议](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:468)完成后直接更新这些状态，包括建议正文、失败标志和忙碌标志。

选择 A 启动建议、关闭窗口，再为 B 打开窗口；A 的晚到结果会出现在 B 的窗口。创建建议会[回填当前输入字段](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/GroupPickerSheet.kt:102)；合并建议提供[可点击的目标分组](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/GroupPickerSheet.kt:195)，而实际提交使用[当前请求的会话 ID](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:631)。旧建议因此可能被用于 B。仍需用户点击确认，不是自动移动。旧请求的 finally 还可能提前清除 B 的忙碌标志。固定上游的 ViewModel 同样没有请求归属检查。

修复方向与待执行验收：所有结果和结束状态绑定发起请求的身份。延迟 A 的响应，关闭并打开 B，再先后完成两个响应；保存字段、建议目标和忙碌状态，确认 A 不影响 B。

## SG04 — 搜索结果在会话变化后仍使用旧快照

[查询收集器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:308)只观察查询字符串和搜索开关，一次调用 `searchSessions()` 后缓存结果。[显示合并器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:161)虽订阅全部会话，搜索时却直接返回缓存结果，没有重新查询或核对已经删除的 ID。

保持查询不变，在搜索结果中删除或重命名会话，数据库变化不会刷新匹配集合；删除的行可以继续显示。新消息造成匹配关系变化，也不能依靠该链自动更新。关闭搜索或修改查询可以再次查询，因此问题是缓存失效条件不足。固定上游虽然更换了搜索返回结构，仍只由查询和搜索开关驱动。

修复方向与待执行验收：让搜索结果响应相关会话／消息变更。保持查询不变，依次删除匹配会话、把标题改为不匹配、加入匹配消息，保存可见 ID、摘要和数据库事实，确认结果及时一致。

## SG05 — 可用的复制实现没有接入列表入口

列表的[复制回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:957)调用 [ViewModel.duplicateSession](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:1067)。该方法只创建标题、modelId，然后复制消息；不复制 category、modelBinding、memoryEnabled、sessionOverrides、thinkingOverride 或 compact markers。[createSession 的默认记忆开关为 true](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:25)，所以关闭记忆的原会话，其副本会重新开启。

项目已有另一份 [SessionForkManager.duplicateSession](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt:83)，明确复制这些设置，并映射压缩记录中的新消息 ID；列表入口没有调用它。固定上游也存在这两个不同实现及相同绕过关系。这里确认的是入口行为差异，不代表另一个实现已通过完整复制、事务或设备验证。

修复方向与待执行验收：合并真实调用入口，复用已有复制能力并核对完整的数据边界。创建含关闭记忆、模型绑定、会话覆盖设置、思考覆盖和两次压缩记录的会话，从列表复制，保存源／副本字段、压缩引用和首次请求证据。未把共享媒体引用直接认定为“删除原会话就丢图片”，因为当前删除链未证明这个结果。

## SG06 — 生成标题可以覆盖后续手动修改

[标题生成成功分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:911)无条件写入标题和分类，没有校验请求发起后用户是否已编辑。[编辑页保存按钮](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:2887)在生成期间仍可用；[同步 liveSession 的 effect](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:2855)还会直接覆盖本地草稿字段。

启动重新生成后手动改名并保存，较晚到达的模型结果会覆盖已保存的名称／分类；若仍在输入，liveSession 更新也会覆盖未保存草稿。生成失败时的兜底路径有部分已有标题检查，不能保护上述成功分支。固定上游同样无条件写回和同步草稿。

修复方向与待执行验收：区分生成请求版本、数据库编辑版本与当前草稿是否已修改。延迟模型响应，分别验证手动保存和未保存编辑；保存最终字段及界面输入，确认后续手动编辑优先，同时未编辑的正常生成仍可生效。

## SG07 — 删除确认使用旧数量，执行使用新成员

[删除窗口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/FolderActionDialogs.kt:120)保存的是打开时的 `(folder, count)`，提示和确认回调使用这个快照，只把 folderId 交给删除动作。[实际执行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:692)在确认后重新查询当前成员，删除所有查到的会话。

如果窗口打开期间，另一条会话管理链改变该组成员，确认文案仍显示旧数量，实际删除集合却已变化。这里不否定“删除整个分组”的业务含义；问题是破坏性操作的确认事实没有跟随实际目标变化，也没有校验快照。固定上游的内联确认窗口同样保存旧数量并只传 folderId。

修复方向与待执行验收：让确认信息反映实际集合，或在提交时检测成员变化后重新确认。打开 N 个成员的删除窗口，再通过另一入口加入一个会话，保存确认前后成员 ID、文案和实际删除 ID，确认二者一致。

## SG08 — 分组删除遗漏当前聊天的导航清理

抽屉的[单会话删除](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionDrawer.kt:388)和[批量删除](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionDrawer.kt:409)在包含当前会话时调用 `onNewChat()`；[分组删除回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionDrawer.kt:418)没有对应检查。

[手机聊天容器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/ChatSplitScaffold.kt:968)独立保存 currentSessionId；初始化 effect 只依赖 initialSessionId，抽屉删除回调没有修改这个选择。与此同时，[分组删除会释放成员的 ViewModel](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:694)。删除包含当前聊天的分组后，可见聊天仍指向已经删除的 ID，没有切换到有效会话或草稿。不能据此断言所有重组情况下都会永久持有同一个已释放 ViewModel。

修复方向与待执行验收：统一所有删除入口对当前选择的处理。当前会话 B 属于 F，分别走单删、批量删和删组，保存删除后的导航选择、可见消息和 ViewModel 状态，确认不继续显示删除目标。与旧报告 F01 的其它运行时持有者清理问题分开计数。本项以当前项目手机抽屉链为证据，未声称上游存在完全相同入口。

## SG09 — 批量导出是可点击的空功能

[选择栏传入的 onExport](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:1140)为 `{ /* TODO: export */ }`，而[按钮](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:1553)在存在选择时正常启用。点击不会启动导出，也没有未实现反馈。单会话导出实现不能覆盖这个入口。

固定 openminis 同文件 1305–1332 行有实际批量导出链：筛选已保存的选择行、调用 SessionExporter.exportToZip、进度、错误反馈和分享。这里只确认上游源码有实现，未验证其运行正确性。这是本轮明确的两仓库功能差异。

修复方向与待执行验收：先核对当前会话／guest 文件语义，再复用适合当前架构的导出能力。选择两个会话点击导出，保存导出过程、ZIP 清单及两个会话的内容，确认按钮真正完成所显示的功能。

## SG10 — 大小写不敏感搜索存在字符串索引越界

[高亮 helper](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SearchHighlight.kt:24)在 lowercase 后的字符串上找位置，却把该位置用于原字符串的 substring。[消息摘要](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:1146)和 [snippetAround](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SearchHighlight.kt:48)也采用相同索引混用。

Kotlin 官方说明 lowercase 支持一对多映射，转换后长度可能变化；`İ` 转为 `i` 加组合点即是这种情况。[官方 API 文档](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html)。标题 `İA` 查询 `A` 时，转换后的命中位置为 2，但原字符串长度为 2；高亮随后尝试取到位置 3，形成越界异常路径。正文前置足够多的 `İ` 时，摘要的 start 也可以超过原字符串长度。固定上游的 helper 同样存在此错误。

修复方向与待执行验收：保留原字符串的匹配坐标，不能假定转换后长度不变。通过会话搜索输入 `İA` 及包含重复 `İ` 的长正文，保存界面、异常日志和摘要结果，确认无异常且高亮位置正确。本轮未执行该复现。

## SG11 — extraBufferCapacity 不保存无订阅者期间的事件

[newTopSessionEvent](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:252)的注释期望在列表离开期间仍能保存事件，但参数是 replay=0、extraBufferCapacity=1。官方文档规定没有订阅者时只保留 replay 值；额外缓冲容量不是后来订阅者的离线队列。[MutableSharedFlow 官方文档](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-mutable-shared-flow.html)。

[列表订阅](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:597)随 composable 存在；[新会话检测](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt:1110)则更新已知 ID 后 tryEmit。列表未组合、ViewModel 仍存活时创建新会话，事件丢失；返回列表不会再次识别该 ID 为新会话，预期自动滚到顶部不发生。本项限定在确实没有订阅者的路径，宽屏列表持续组合时不一定触发。固定上游同样使用 replay=0。

修复方向与待执行验收：显式保存尚未消费的滚动意图及其目标，核对重订阅时的消费语义。先把列表滚到旧会话，离开列表后保存新会话，再返回；保存列表位置和新会话 ID，确认补消费一次且后续返回不重复跳动。

## SG12 — 相对时间与日期分组的缓存缺少时钟依赖

[日期分组](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:558)只以 folderPartition 为 remember 的 key；[行时间](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:2374)只依赖 updatedAt 和 context；[分组头时间](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:2131)同样只依赖数据时间。内部计算读取当前时间，却没有时间推进或恢复前台后的失效信号。

列表持续存在且数据未变时，“几分钟前”保持旧值；跨过午夜，“今天”的分桶仍可保留原分类。重新组合但 remember key 不变也不足以重新计算。重新进入页面或相关数据变化可能刷新部分结果，因此不是永远不更新。固定上游也有相同日期分组和行时间缓存。

修复方向与待执行验收：提供符合显示粒度的时间失效条件。未来在可控时钟的端到端环境验证跨分钟和跨日，保存时间基准、前后台状态及截图，确认文案和分桶同步。本轮未修改系统时间。

## 去重与本轮核对边界

未重新计数旧报告中的删除运行时持有者清理（F01）、文件浏览器旧请求覆盖新目录（FB01）、分享缓存／ZIP／Markdown 问题（SH、EX）和导航路由状态问题（NV）。本轮完整阅读文件浏览器，确认旧请求问题，未因此制造新编号。未把关闭的 HTML 加入主页入口当成可达功能，未把 FileProvider 路径声明测试当成完整访问控制验证。

SessionForkManager 的消息处分支已有数据库事务和取消清理；没有把它描述为完全缺少事务。四个既有测试文件只静态阅读，没有执行；它们分别覆盖建议解析、分组展开／排序、运行指示环和 provider 路径声明，不能替代上述异步界面验收。

本报告只记录问题和验收方向。没有修复代码，没有运行测试、构建、模拟器、adb、Root、设备验证或云任务；未操作账号或读取凭据。Root、SELinux、VPN、BPF 与 OEM 行为无本轮新增设备证据。

## 全文覆盖进度

本轮全文读取 15 个文件、7,457 行；其中两文件由 partial 提升为 full，其余十三文件为新增全文记录。累计全文 378 / 1,546 个纳入范围文件、88,205 行；仍有 20 个 partial 和 1,148 个 not_registered。全库 tracked 清单 1,670 个，另有 124 个按既定规则排除的文档／二进制等文件。全库阅读目标尚未完成。

| 全文文件 | 行数 |
|---|---:|
| [SessionListViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt) | 1,162 |
| [SessionListScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt) | 3,038 |
| [FolderActionDialogs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/FolderActionDialogs.kt) | 162 |
| [GroupPickerSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/GroupPickerSheet.kt) | 356 |
| [SearchHighlight.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SearchHighlight.kt) | 59 |
| [SessionDrawer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionDrawer.kt) | 895 |
| [MediaStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/storage/MediaStore.kt) | 108 |
| [SessionForkManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt) | 380 |
| [FileBrowserScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserScreen.kt) | 580 |
| [FileType.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileType.kt) | 82 |
| [FileContentRenderers.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileContentRenderers.kt) | 218 |
| [GroupSuggestionParseTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/sessions/GroupSuggestionParseTest.kt) | 159 |
| [SessionGroupAccordionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/sessions/SessionGroupAccordionTest.kt) | 114 |
| [GroupRunningRingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/sessions/GroupRunningRingTest.kt) | 89 |
| [FileProviderPathsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/sandbox/FileProviderPathsTest.kt) | 55 |

逐文件 SHA-256、实际阅读范围与状态见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。关联 Repository、MinisApp 和 ChatScreen 只补充已完整读取的片段；搜索命中和上游片段不计入本项目全文分母。先前 25 份归档文档保持原内容，台账仅登记本轮阅读进度。
