# 联系人、短信与通话记录状态审计

审计开始：2026-10-05；报告完成：2026-10-06。项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮只做静态审计，独立新建报告；不修改产品代码、旧报告或合同，不运行构建、测试、模拟器、adb、Root、设备或云任务，不读取凭据及真实联系人、短信、通话记录。

新增 **5 项 P2 问题**。全文覆盖新增 7 个文件、971 行，其中 ContactsOffloadHandler 从部分读取提升为全文，其余 6 个文件首次登记全文。累计全文 **618/1,546 个纳入范围的文件、127,155 行**；另有 14 个部分读取、914 个尚未登记，124 个排除项。全库排查仍未完成。下面的反例均为静态推导，没有把它们描述成设备复现。

## 问题索引

| 编号 | 问题 | 影响 |
|---|---|---|
| CT01 | 联系人更新忽略 insert/update 返回结果 | 没有提交修改，也可能返回 updated=true |
| CT02 | 同一次联系人更新逐字段独立提交 | 后续字段失败，前面的修改保留，结果没有报告部分完成 |
| CT03 | 创建联系人用 RawContacts ID 替代 Contacts ID | 查询失败后返回错误身份，后续操作可能指向另一联系人 |
| CT04 | null cursor 被当成成功空数据 | 联系人搜索、短信、验证码和通话记录读取可能掩盖查询失败 |
| CT05 | 验证码按关键词起点距离选择数字 | 前面的金额或订单号可能被当成验证码 |

## 现役入口与范围

结构化联系人工具的 [contactsSearch:214](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:214) 和 [contactsManage:222](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidSystemTools.kt:222) 将命令转交现役 ContactsOffloadHandler；管理工具包含 create/update/delete。短信、验证码及通话记录在 [AndroidTelephonyTools.kt:244](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt:244) 之后定义独立结构化工具，直接读取 Android ContentResolver。

此前已经报告过工具放置于 App 本体的职责问题，本轮不重复计数。这里检查这些现役工具对真实结果、身份和失败状态的解释。Android ContentResolver 是平台能力；其存在本身不能证明工具应迁移或删除。

## CT01 — 更新没有验证实际写入结果

[upsertData:291](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:291) 查询已有 Data 行，随后执行 insert 或 update。[305 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:305) 不检查 insert 返回 URI，[307 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:307) 不检查 update 的受影响行数。函数没有返回提交状态。只要没有抛异常，外层 [283 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:283) 就退出 0 并输出 updated=true。

Android [ContentResolver 返回合同](https://developer.android.com/reference/android/content/ContentResolver)明确 insert 可返回 null，update 返回实际更新行数。反例：已有联系人尚无邮箱，新增邮箱时 insert 返回 null；或者查询出电话行后，该行被其它写入删除，随后 update 返回 0。两种情况下当前工具都声称更新成功。

方向：将写入结果传回调用方，成功反馈必须有实际提交证据。查询 null 也不能未经判断就作为“没有现有数据”执行插入；读取失败与不存在需要分开。create 已检查批处理首个结果的 ID，不应把本项扩大成所有联系人写入都完全不验证。

## CT02 — 多字段更新失败后留下未报告的部分修改

[doUpdate:270](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:270) 按 name、phone、email 顺序分别执行 upsertData。没有共享批处理或补偿。后续调用抛错后，[284 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:284) 只返回 permission_denied 或 contacts_provider_error，没有说明前面哪些字段已经提交。

可达交错：一次要求更改姓名、电话、邮箱；姓名 update 已成功，电话或邮箱写入遇到 Provider 故障。工具返回失败，但姓名已变更。调用方既不能从错误确定实际状态，也无法据此可靠决定恢复操作。这与 CT01 不同：即使补齐每次 insert/update 的结果校验，独立提交仍有部分修改问题。

Android [Contacts Provider 批处理合同](https://developer.android.com/identity/providers/contacts-provider#BatchModification)说明没有 yield point 的一批相关操作作为整体原子执行。方向：先解析和校验全部字段，再通过 Provider 支持的同一原子批处理提交并检查结果；若产品选择允许部分成功，必须明确返回已提交字段和剩余失败状态。不能假设单个 insert 的原子性覆盖多个独立调用。当前 create 已使用 applyBatch，可复用现有模式。

## CT03 — RawContacts ID 被冒充为聚合联系人 ID

create 在 [242 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:242) 从批处理结果取得 RawContacts 行 ID，[244 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:244) 使用 `contactIdForRaw(rawId) ?: rawId`，最终把该值写入 contact_id。映射查询 [319 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:319) 遇到 null cursor 或没有行时返回 null，触发错误替代。

Android [RawContacts 表合同](https://developer.android.google.cn/reference/android/provider/ContactsContract.RawContacts)区分原始行 `_ID` 与指向聚合 Contacts 行的 `CONTACT_ID`。两者没有数值相等保证。同一联系人也可以包含多个 raw contact。当前 get 使用 [195 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:195) 的 Contacts._ID；update 按 [314 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:314) 的 RawContacts.CONTACT_ID 查找；delete 在 [354 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:354) 使用 Contacts._ID。它们都需要聚合 ID。

条件反例：新建 raw 行 ID=10，真实聚合 ID=20；写入成功后映射查询返回 null，而已有另一个聚合联系人 ID=10。创建结果返回 contact_id=10。调用方使用该结果读取、更新或删除时，目标将是原有联系人 10。此处不声称正常 Provider 每次都会映射失败，也不声称已经发生误删；问题是映射失败时选择了另一个 ID 空间的值。

方向：单独保留 raw_contact_id；聚合 ID 不能确认时明确报告创建已提交、身份映射尚未确认，不返回可作为后续操作目标的伪 contact_id，也不要因映射失败盲目再次创建。

## CT04 — 查询失败与真实空结果合并

联系人搜索的 [159 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:159) 和 [168 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:168) 使用 `query(...)?.use`。null cursor 静默跳过；最终 [180 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:180) 可以返回 count=0、contacts=[]、退出 0。按 ID 补充资料的 [336 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:336) 同样把查询 null 与联系人不存在合并。两个搜索来源中只有一个查询失败时，还可能把不完整结果当作完整结果。

短信读取 [56 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt:56)、验证码读取 [131 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt:131)、通话记录读取 [195 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt:195) 也没有 null cursor 分支。返回分别为成功的 messages=[]/total_matching=0、ok=true/items=[]、calls=[]/total_matching=0。

[ContentResolver 查询合同](https://developer.android.com/reference/android/content/ContentResolver)说明底层 Provider 返回 null 或崩溃时，query 可以返回 null。它不是“查询成功且有零行”的 Cursor。当前联系人 list 的 [140 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:140) 和 get 的 [199 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt:199) 已明确拒绝 null，内部状态语义也不一致。

方向：仅将有效 Cursor 的零行作为空数据；null 必须返回读取不可用/失败，部分来源失败则标明结果不完整。本项不把缺少 READ_SMS/READ_CALL_LOG 权限计作空结果问题：现有权限与 SecurityException 分支有明确的 denied/error JSON，尽管外层 ToolExecutionResult 的 success 为 true，调用方仍有状态可辨认。

## CT05 — 最近数字的距离计算会偏向关键词前面的金额

[SmsCodeExtractionPolicy.codeIn:36](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/SmsCodeExtractionPolicy.kt:36) 取首个 OTP_CONTEXT，再以 `abs(number.start - context.start)` 选择 4–8 位数字。它比较的是两个起点，而不是数字与关键词整个范围或句子关系。越长的关键词，位于其后真正验证码的起点被推得越远。

静态反例：`金额1000验证码为：123456`。金额 1000 的起点为 2，关键词“验证码”的起点为 6，真实验证码 123456 的起点为 11。两项距离分别为 4 与 5，函数确定选择 1000。这条消息同时含有明确关键词和有效验证码，并非不含验证码语境的普通账单。英语长关键词也会放大同一偏差，例如订单号后跟 verification code。

[readSmsCodes:144](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt:144) 直接把提取值作为 code 返回，结果只带 sender、timestamp_ms，不带原文，因此消费该专用工具的 Agent 无法仅从此结果检查数字是否是金额。原文不返回是该工具的设计边界，本项没有建议扩大短信正文暴露。

现有 [SmsCodeExtractionPolicyTest.kt:23](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/SmsCodeExtractionPolicyTest.kt:23) 验证两种“最近数字”布局，未覆盖上述前方金额更靠近关键词起点的布局。方向：处理关键词范围及数字关联关系，多关键词或候选不明确时保留歧义状态，不能仅用起点距离输出唯一确定验证码。本轮没有执行该测试或读取真实验证码。

## OpenMinis 对照与论文边界

固定只读上游：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，路径 `src/android/app/src/main/java/com/openminis/app/sandbox/offload/ContactsOffloadHandler.kt`。

已读取并对照上游 140–275 行：上游 doSearch 在 184–186 行对 null cursor 返回退出 1；当前扩展为姓名与电话双来源搜索后丢掉了这一拒绝分支，CT04 属于可明确对照的回退。该上游片段还展示按聚合 Contacts._ID 的 get/delete 语义；没有用 raw ID 替代聚合 ID。上游树未列出本轮 AndroidTelephonyTools、SmsCodeExtractionPolicy、AndroidDialTools 的同名文件；这只说明固定快照没有这些同名实现，不推断上游不存在任何等价能力。

此前论文讨论 Harness 的工具边界和执行结果可靠性，可帮助理解这些状态缺口。论文不是 Android Provider 行为或本库故障的证据；本轮判断由现役代码、明确反例和上述 Android 主文档支持，不再次计入已报告的工具架构债。

## 全文阅读记录与验证限制

| 本轮全文文件 | 行数 | 原状态 |
|---|---:|---|
| [ContactsOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ContactsOffloadHandler.kt) | 446 | partial |
| [AndroidTelephonyTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt) | 314 | not_registered |
| [SmsCodeExtractionPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/SmsCodeExtractionPolicy.kt) | 43 | not_registered |
| [AndroidDialTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AndroidDialTools.kt) | 40 | not_registered |
| [AndroidTelephonyToolsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/AndroidTelephonyToolsTest.kt) | 44 | not_registered |
| [SmsCodeExtractionPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/SmsCodeExtractionPolicyTest.kt) | 63 | not_registered |
| [AndroidDialHandlerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/AndroidDialHandlerTest.kt) | 21 | not_registered |
| 合计 | 971 | 7 个文件 |

所有全文记录来自本轮完整分段读取；对已全文覆盖的 AndroidSystemTools 和上游片段的复核不重复计入新增覆盖。登记与累计数见 [覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。AndroidTelephonyToolsTest 主要检查工具定义与注册，不能作为 Provider 故障路径的验证证据。AndroidDial 使用 ACTION_DIAL 打开拨号界面，本轮未发现足够证据新增拨号问题。

交付核对：1,670 个受跟踪文件的清单、SHA-256 与 HEAD 一致，tracked diff 为空，36 份旧 Markdown 报告哈希未变；只新增本报告并更新覆盖台账。报告本地文件引用经存在性与行号检查。未运行任何产品测试，也未验证 OEM Provider、账户同步、权限变化或设备崩溃行为。其发生频率、具体设备表现及修复后效果仍需另行验证。
