# 会话持久化、历史加载、搜索与数据库续查

日期：2026-10-05。当前项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`；固定 openminis 对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，本地目录 `/tmp/openminis-review-20261004`。本轮只静态读取，新增独立报告，不修改产品代码或旧报告。

新增 12 项发现。P1 用于持久数据或结构化历史可能被破坏的路径，P2 用于功能／状态错误。下文的端到端步骤全部待执行，不能作为设备复现或测试通过证据。

| 编号 | 优先级 | 问题 |
|---|---|---|
| DB01 | P2 | 工具结果把已有会话预览写为空，上游的保护在当前项目丢失 |
| DB02 | P1 | 超长消息被整体改成 text，工具 ID 和内容类型无法恢复 |
| DB03 | P2 | 防止大消息的写入边界没有覆盖 reasoning_content 等整行数据 |
| DB04 | P1 | 大行读取失败直接丢行，整页丢空还会提前结束后续加载 |
| DB05 | P2 | CLI 内容搜索返回只有 JSON 元数据命中的消息 |
| DB06 | P2 | 普通关键词中的百分号和下划线被当作 SQL 通配符 |
| DB07 | P2 | 在序列化 JSON 上预筛选，漏掉含转义字符的正文命中 |
| DB08 | P2 | 分类取消选中后，保存仍保留原分类 |
| DB09 | P1 | 空草稿清理读取候选后不重新检查，可能删除刚收到消息的会话 |
| DB10 | P2 | 无效日期参数被当成未提供过滤条件 |
| DB11 | P2 | CLI 日期格式器跨线程共用，并继续使用初始化时区 |
| DB12 | P2 | 会话删除先单独提交消息删除，失败窗口会留下半删除状态 |

## DB01 — 新的追加事务丢掉了非空预览保护

[appendMessage](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:421)把 `extractTextPreview(capped)` 直接传给 [appendMessageWithPreview](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:266)。后者无条件更新 last_message，SQL 没有保留旧值的分支。

工具结果本身不会生成预览：提取器只处理 text、mediaRef、toolUse。[PreviewFlickerTest](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/PreviewFlickerTest.kt:36)也明确断言工具结果返回 null。于是运行前写入的工具标题或上一条消息预览，在工具结果落库时被清空；追加空内容或只有被剥离提醒的文本也可能清空预览。

固定上游 ChatRepository 583–588 行保留 `preview != null` 时更新预览，否则仅 touchSession 的分支。本项目新增事务有正确的原子性价值，但没有保留这个行为。既有 PreviewFlickerTest 只测静态 helper；ChatRepositoryAppendTest 和 ChatAppendTransactionTest 验证事务调用／回滚／并发顺序，没有覆盖 null 预览保留。

修复方向与待执行验收：保留追加事务，事务内区分“无新预览”和“明确清空”。先写入工具标题，再追加工具结果，通过实际 Repository 入口观察数据库与列表，保存前后 last_message、updated_at 和可见文案，确认预览不变且活动时间更新。

## DB02 — 大小保护破坏结构化历史，而非只缩短输出

[写入大小判断](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:401)在 partsJson 超过 500,000 个字符时调用 [buildTruncatedPartsJson](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:931)。它截取原 JSON 的前缀，再把整段 JSON 字符串包进一个 text part，不保留原来的 part 类型、toolUseId、name、签名或媒体引用。

生产链可达：[工具结果持久化](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10406)把一个批次的多个结果及 snapshot 编成同一条消息，然后交给 appendMessage；[助手回合](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10331)也走该入口。冷启动[按 type 重建上下文](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:12481)，已变成 text 的内容无法再恢复为原工具记录。

例如结果行超过阈值时，先前 toolUse 仍可能存在，而该行不再含对应 ToolResult；超长助手行也可能丢失 ToolUse 类型。后续历史修复即使补占位或剪掉不完整配对，也不能恢复被丢失的结果。这里没有断言所有 Provider 都必然返回 400。助手路径还把截断前的 partsJson 交给 checkpoint，Room 与恢复记录可出现不同历史。固定上游同样采用这种整体包装方式。

修复方向与待执行验收：按内容类型限额或保存外置内容引用，保留 ID、配对、签名和媒体身份。通过实际聊天产生跨阈值的助手／工具结果批次，保存落库前后结构、冷启动后的历史和 Provider 请求，确认结构身份及缺失标志仍正确。

## DB03 — partsJson 限额不是整行读取上限

[消息实体](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/MessageEntity.kt:25)同时包含 parts_json、reasoning_content、token_usage 等字段。appendMessage 只限制 partsJson，[reasoningContent 直接写入](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:415)；[助手持久化入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10346)直接传入回合推理内容。

另外，[备份恢复](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:475)直接 insertMessage，保留 parts 和 reasoningContent，绕过 Repository 的正文限制。因此新插入或新恢复的行仍可能超过设备 CursorWindow 能容纳的单行，不能依据正文限制就声称已经杜绝所有大行。SELECT * 分页也没有降低单行的字段总量。500,000 是字符数限制，本报告不把它当成固定字节上限，也不声称所有设备的窗口大小相同。

固定上游的正文限额和 reasoningContent 写入方式相同。恢复保留原文有数据完整性价值，不能以任意丢掉推理数据作为修复。

修复方向与待执行验收：对整行存储／读取建立可保留数据的边界，包括外置大字段或按字段读取。通过公开恢复入口导入短正文、长推理的会话，保存导入记录、原数据摘要和打开／搜索／读取结果，确认数据可取回且不依赖忽略大行。未运行该验收。

## DB04 — 大行回退没有占位，也可能跳过后续正常页

[loadMessages](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:296)遇到窗口异常后改为逐行读取；但 [loadPageRowByRow](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:339)对失败行返回 null 后 `continue`，没有注释声称的 proxy MessageEntity，也没有返回缺失清单。

返回列表因此少了数据库中的消息，调用方仍按完整历史使用。[SessionHistoryLoader](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionHistoryLoader.kt:14)原样把这些行交给聊天恢复。若整页逐行回退都失败，page 为空，[外层直接 break](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:332)，后面正常页也不再读取。原始行未被数据库删除，但显示、分支复制和上下文重建会基于不完整数据，且没有向用户说明。

固定上游回退同样跳过失败行。SessionHistoryLoaderTest 覆盖正常分页、取消和畸形 JSON，但没有让单行读取抛窗口异常，因此不覆盖此缺口。畸形 JSON 行确实保留，不能把它与根本读不出的行混为一谈。

修复方向与待执行验收：保留行身份和明确的不可读状态，或拒绝把不完整加载结果作为完整历史；空的失败页不等于数据库末尾。恢复含大行和后续正常页的会话，保存数据库 ID／排序、可见行和加载状态，确认不会静默少行或跳过正常尾部。

## DB05 — 内容搜索没有校验实际内容命中

[searchMessages](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:649)用 parts_json LIKE 取候选，再提取可见内容，但只检查提取结果是否为空。[keywordSnippet](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:823)没有找到关键词时仍返回正文前缀，因此非空消息不会被剔除。

同一行 text 为普通问候，工具调用 ID 或隐藏签名含 `needle`，搜索 needle 会返回与它无关的正文摘要。多个关键词在 SQL 元数据中分别匹配时，返回正文也不必满足 AND 条件。当前 UI 的 [ChatDao.searchSessions](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:170)也直接匹配整段 JSON。CLI 的 limit×3 候选上限又没有继续翻页：若最新候选都是提取后为空的提醒或元数据行，更早的有效内容即使存在也可能取不到。

固定上游 CLI 保留相同逻辑；其 UI 另有 SessionSearch 的可见字段过滤和无可见命中剔除，当前项目没有对应入口。这部分改进可供参考，但不能据此宣称上游 CLI 已解决。

修复方向与待执行验收：明确哪些字段属于可搜索内容，逐个校验 AND 关键词，并在候选过滤后继续有界查找。通过 CLI／界面搜索包含正文、只含元数据、只含提醒和多个关键词的会话，保存返回 ID 与真实命中字段，确认假命中不占据结果额度。

## DB06 — 关键词里的 SQL 通配符未转义

[UI 查询包装](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:272)、[CLI list](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:596)和 [CLI search](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:660)直接把输入包在 `%...%` 中，没有 ESCAPE 或普通字符转义。SQLite 的 `%` 匹配任意长度字符串，`_` 匹配单个字符，参数绑定不会改变这一规则。[SQLite 官方文档](https://www.sqlite.org/lang_expr.html#like)。

例如搜索软件标识符 `foo_bar` 会把 `fooXbar` 当成候选；查询单个 `%` 可以匹配几乎全部非空 JSON。这些入口说明的是关键词搜索，没有声明输入是 SQL 模式。问题不是 SQL 注入：ID 和关键词都使用占位符绑定。固定上游的相关模式构造同样没有转义；其额外内容检查可能剔除部分假命中，不能替代统一的字面量模式语义。

修复方向与待执行验收：普通搜索按字面量处理通配字符，若要支持模式需明确独立语义。创建含 `foo_bar`、`fooXbar`、`100%` 和普通文本的消息，通过公开入口搜索，保存命中 ID，确认特殊字符只匹配它本身。

## DB07 — JSON 预筛选漏掉正文的转义字符命中

上述查询在解码 JSON 前做 LIKE。正文的双引号、反斜杠等字符已经由持久化编码器转义；搜索输入则仍是用户看到的原始文本。SQLite 的 LIKE 不会解码 JSON。[文本编码](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/AssistantTurnCodec.kt:69)和 [UI 查询](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:170)明确使用不同表示。

例如正文包含带双引号的 `"yes"`，落库 JSON 中引号前含转义反斜杠；查询包含两个实际引号的 `"yes"` 不再连续匹配原始 JSON。类似地，查询 Windows 路径中的单个反斜杠，与 JSON 里的双反斜杠不同。候选阶段丢掉的行，后续 Kotlin 内容提取无法找回。查询匹配会话标题时可能仍返回，复现应让命中只存在于消息正文。

固定上游 CLI 同样在原始 JSON 上匹配；上游 UI 的可见字段 SQL 仍以 raw-JSON LIKE 为前置条件，不能直接当作本项完整修复。

修复方向与待执行验收：在解码后的搜索内容上匹配，或使用不排除真实内容命中的候选策略。通过聊天发送带引号／反斜杠的文本，再按屏幕显示的原样搜索，保存原始正文、编码数据和命中 ID，确认无需用户手动输入 JSON 转义形式。

## DB08 — UI 的清除分类意图被 COALESCE 吞掉

[分类格子](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:2929)允许再次点击当前分类，把 selectedCategory 设为 null；[保存回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:1270)把 null 一路交给 Repository。DAO 却使用 [category = COALESCE(:category, category)](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:102)，把 null 解释成保留原值。

界面显示未选择分类后点击保存，重新打开仍是原分类。与上一轮 SG06 的标题生成竞态不同，此问题在没有任何异步生成时也发生。固定上游沿用相同 COALESCE。

修复方向与待执行验收：区分“用户明确清除分类”与“标题生成未提供新分类”，不要直接把所有 null 调用都改成清除。保存一个已有分类的会话，再取消选中并保存，记录数据库 category 和重开编辑页状态；同时核对仅生成标题时的保留语义。

## DB09 — 旧空草稿成为清理候选后，不再检查是否已有内容

[pruneEmptyDrafts](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:142)先查询满足年龄和空消息条件的 ID，然后逐个调用普通 deleteSession。查询后的删除没有重复检查该会话是否仍为空。[启动入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:953)在独立 IO scope 中异步运行，并没有等待它完成后才允许打开聊天。

十分钟前创建的空会话在查询时进入候选，随后收到首条消息／被用户恢复编辑，清理仍按旧 ID 删除它。十分钟门槛保护新创建的行，不能保护旧行在查询之后收到内容。真实触发依赖并发时序；本轮未声称发生过用户数据损失。此处只据当前项目链确认，没有用上游假设替代调用事实。

修复方向与待执行验收：删除动作本身必须保持“仍是未使用草稿”的条件，协调数据库删除与外部目录清理的结果。未来在公开启动／聊天链中延迟清理提交，在候选查询后保存首条消息，记录时间、候选和最终会话／目录状态，确认新内容会使候选失效。

## DB10 — 非法日期会扩大查询范围，还允许只消费有效前缀

[parseDate](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt:237)将所有解析异常变成 null；list、search、messages 都把该值直接作为可选边界。输入 `--start nonsense` 或不存在的日期，结果就是不设置对应边界，仍可返回 ok=true，而不是 INVALID_ARGS。

代码已设置 isLenient=false，所以不会宽松地把非法日数自动滚入下个月；问题是失败后的处理。另一个同入口缺口是未校验完整字符串：DateFormat.parse(String) 可以只消费开头，因此有效日期加尾随垃圾仍可能被接受。[Android DateFormat 文档](https://developer.android.com/reference/java/text/DateFormat#parse(java.lang.String))。固定上游 parseDate 同样返回可空值并忽略解析失败。

修复方向与待执行验收：区分“没提供”与“提供但无效”，严格消费全部输入后再执行查询。通过三个子命令分别传入无效、尾随垃圾、有效和缺省日期，保存退出码、响应及数据库查询范围，确认错误参数不能成为无界过滤。

## DB11 — CLI 的日期格式器作用域超过了单次请求

[DATE_FMT](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt:299)是 companion object 内的可变 SimpleDateFormat；list、search、messages 无锁地共用。现役 [GuestCommandBridge 为请求启动独立线程](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:263)，[dispatch 直接调用共享 handler](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:226)，MinisApp 只注册一个 SessionsOffloadHandler。并行查询可同时进入 format。

Android 要求 SimpleDateFormat 的并发访问额外同步。[官方文档](https://developer.android.com/reference/java/text/SimpleDateFormat#synchronization)。因此时间字符串可能串值或格式化失败；handler 外层 catch 会把异常包装成失败响应，不能据此断言会直接杀死 App。另一个作用域问题是 DATE_FMT 只在初始化时取得默认时区，而 parseDate 每次取得当前时区；进程存活期间更换时区后，过滤条件和输出时间可以使用不同时区。

固定上游也共用该格式器。旧报告 ND05 审查的是 AppLogger 的另一组格式器，本项是独立的 CLI 输出入口，未把日志问题重复计数。

修复方向与待执行验收：每次请求使用一致、可并发安全的时间／时区上下文。未来并行查询两个时间明显不同的会话，再在受控设备改变时区后重复查询，保存原始 epoch、系统时区、过滤范围及输出，确认各响应内部一致且不串值。本轮未执行并发命令或调时区。

## DB12 — 会话删除的两个数据库步骤没有共同事务

[deleteSession](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:148)先调用 dao.deleteMessages，再调用 dao.deleteSession，两条语句分别提交，没有 @Transaction 或同一数据库事务。[MessageEntity 的 session 外键](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/MessageEntity.kt:11)已经声明级联删除。

第一步完成后、第二步执行前取消／进程中断，或者第二步失败，会留下会话行和可能的 compact markers，却丢掉全部消息。无条件预览字段也没有在这次消息删除中刷新，列表可以继续显示旧预览。单独每个 SQL 是原子的，不等于这一业务操作原子。它与 F01 的其它 runtime 持有者清理、F35 的备份恢复事务问题不同。

修复方向与待执行验收：数据库内删除会话及其关联行应形成一个提交边界，利用既有级联或现有事务能力；随后再协调外部资源删除。通过公开删除入口在两个写步骤之间制造受控中断／失败，保留重启后的会话、消息、marker 及外部目录状态，确认不会发布半删除状态。无需为此新增数据库抽象层。

## 已核对的保护、去重与验证限制

当前 appendMessageWithPreview 有事务；nextSortOrder 在事务内分配；幂等回执的读取／写入也在该边界。没有把它们描述成无并发保护。AppDatabase 的 1→23 升级迁移已逐项静态读完，注册链覆盖这些版本，22→23 收口 characters 索引；不能仅因注释滞后就报告升级缺失。DatabaseVersionGuard 复用 APP_DATABASE_VERSION，没有独立硬编码数字漂移。

MIGRATION_14_13 和既有测试验证的是特定旧数据库形状及数据列保留；当前产品版本为 23，不能把手工 SQL 迁移测试视为“所有 Room 降级都已支持”。BotMigrationTest 部分用简化数据库直接调用 migration，其它部分用新建内存数据库；未证明每条真实旧库路径到最新版本均通过。

全文阅读了 WebAppShortcut、Character 的基础数据文件，没有据此把未核对的业务层行为列为新缺陷。没有重复计数 F39 的 CLI 工具输出提前截断、F40 的分页口径／缺少快照、SG04 的搜索缓存失效、SG05 的复制设置丢失、SG10 的 Unicode 索引混用，以及备份报告中已经登记的恢复事务和越界问题。

本轮读取 9 个既有测试文件，未执行任何测试。没有编写单元测试，也没有运行构建、模拟器、adb、Root、设备或云任务；未操作账号、读取凭据、拉取上游或启动子 Agent。上述端到端验收全部待用户另行授权执行，不能把本报告当成已修复状态。

## 本轮全文覆盖

全文读取 25 个新登记文件、3,359 行：ChatRepository 由 partial 提升，其余 24 个由 not_registered 提升。另全文重核 ChatDao 548 行，它此前已为 full，不重复计入增量。关联 ChatViewModel、MinisApp、BackupImporter 仅登记实际读取片段。

| 文件 | 行数 |
|---|---:|
| [ChatRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt) | 977 |
| [AppDatabase.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/AppDatabase.kt) | 555 |
| [MessageEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/MessageEntity.kt) | 46 |
| [CompactMarkerEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/CompactMarkerEntity.kt) | 57 |
| [FolderEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/FolderEntity.kt) | 69 |
| [SessionEventEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/SessionEventEntity.kt) | 43 |
| [DatabaseVersionGuard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/DatabaseVersionGuard.kt) | 37 |
| [UsageRecord.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/UsageRecord.kt) | 24 |
| [WebAppShortcutDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/WebAppShortcutDao.kt) | 29 |
| [WebAppShortcutEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/WebAppShortcutEntity.kt) | 37 |
| [WebAppShortcutRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/WebAppShortcutRepository.kt) | 52 |
| [CharacterDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/CharacterDao.kt) | 29 |
| [CharacterEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/CharacterEntity.kt) | 34 |
| [SessionHistoryLoader.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionHistoryLoader.kt) | 20 |
| [AssistantTurnCodec.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/AssistantTurnCodec.kt) | 103 |
| [SessionsOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SessionsOffloadHandler.kt) | 358 |
| [ChatAppendTransactionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/androidTest/java/com/openminis/app/data/db/ChatAppendTransactionTest.kt) | 81 |
| [BotMigrationTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/androidTest/java/com/openminis/app/data/db/BotMigrationTest.kt) | 233 |
| [MessageAttributionMigrationTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/androidTest/java/com/openminis/app/data/db/MessageAttributionMigrationTest.kt) | 138 |
| [DatabaseVersionGuardTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/db/DatabaseVersionGuardTest.kt) | 89 |
| [ChatRepositoryAppendTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/ChatRepositoryAppendTest.kt) | 29 |
| [ChatRepositoryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/ChatRepositoryTest.kt) | 63 |
| [PreviewFlickerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/PreviewFlickerTest.kt) | 97 |
| [AssistantTurnCodecTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/AssistantTurnCodecTest.kt) | 82 |
| [SessionHistoryLoaderTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/SessionHistoryLoaderTest.kt) | 77 |

累计全文 **403 / 1,546 文件、91,564 行**；另有 partial 20 个，not_registered 1,123 个。tracked 总清单仍为 1,670 个，排除 124 个文档／二进制等既定范围外文件。全库阅读目标尚未完成。[逐文件覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)记录路径、行数、SHA-256 和阅读范围。此前 26 份归档 Markdown 内容保持不变，源码未变化。
