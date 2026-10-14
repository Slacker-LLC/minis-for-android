# 短信验证码与应用使用统计修复边界

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。全文静态读取5文件、598行。旧185份报告及全部覆盖台账保持原样。本轮沿用CT04/CT05和媒体使用报告MP01，未新增独立缺陷编号。未修改代码、编写或运行测试、查询短信/验证码/通话记录/UsageStats、读取真实个人数据、构建或操作模拟器/adb/Root/设备/云资源。

## CT05原距离反例已修，但前置验证码仍有确定误选路径

[SmsCodeExtractionPolicy.kt:42](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/SmsCodeExtractionPolicy.kt#L42)先找首个验证码关键词，优先选择关键词结束之后最近的4—8位数字，仅在后方没有候选时才选前方数字。因此旧“金额1000验证码为：123456”不再按起点距离选1000；新增测试明确覆盖该中文布局和英文长关键词布局。

剩余反例：`123456 is your verification code. Order amount 9999`。123456紧贴语义，位于关键词前；9999是关键词后的金额。after集合非空，函数必选9999，根本不比较前置验证码。这不是无验证码语境的账单，且是当前实现明确支持的前置英文布局加普通金额尾句。多个关键词也仍只取首个match，不表示已经解决全部候选关联。

[AndroidTelephonyTools.kt:148](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt#L148)直接把选中数字作为code返回，不附正文供模型复核；只返回code/sender/timestamp是该工具的重要隐私边界，不建议为修复扩大短信原文暴露。沿用CT05部分修复，不重复编号。

方向：在关键词和数字之间保留句子/关系信息，前后候选不明确时明确标记歧义或拒绝唯一值；不能仅凭“后方有数字”就认为它是验证码。待后续授权的端到端验收应通过真实公开读取入口比较前置验证码、后置验证码、金额尾句、多个关键词及无关键词消息，保存脱敏输入与实际返回，不使用用户真实验证码。本轮未执行。

## CT04：短信、验证码、通话null Cursor不再装成空数据

AndroidTelephonyOps的三次ContentResolver.query都有明确null分支，返回success=false及provider无数据/可能不可用说明；有效Cursor零行仍可返回成功空数组。原这些入口的null合并为空成功反例已处理，不能据此宣布联系人其它入口也修复，本轮未重读ContactsOffloadHandler。

运行时READ_SMS/READ_CALL_LOG缺失和SecurityException仍返回denied/error JSON，外层ToolExecutionResult.success=true。旧CT04已明确此状态可辨，不将它重新包装成“成功空短信”或新权限绕过。其它provider异常/缺列是否由统一执行边界正确转成工具失败仍需调用链证据；不宣称已造成App崩溃。

验证码max_age_minutes经handler clamp在1—1440，默认10；最多返回10项，正文不进入结果。短信/通话各最多100项；通话min_duration_sec经占位符传入query，不拼接任意SQL。

## 查询时间窗口与扫描预算候选

[AndroidTelephonyTools.kt:133](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt#L133)验证码query没有date selection，按date DESC取全部inbox；超过cutoff时continue，未利用排序break。最近验证码少于10条时，仍可走完整个旧短信cursor。输出10条上限不能证明扫描10行，这个事实有源码证据；具体历史规模/耗时未测，本轮作为性能候选，不强行升级为设备卡顿或无限循环。

cutoff没有限制未来时间戳，重复验证码未去重，首次关键词假语境可能误触发，均需明确产品语义和输入证据后再立项；不能把它们推断成已经泄漏正文。

## 媒体使用报告MP01：按包汇总排名与null状态已修

[AndroidUsageTools.kt:26](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/AndroidUsageTools.kt#L26) aggregateUsage先剔除零/负foreground行，按packageName合并sum，lastUsed取最大，再按总时长降序/包名字典序排序，最后take(limit)。原“每天一行导致同应用重复、单日60分钟胜过七天30分钟”的反例已处理。

queryUsageStats返回null现在明确usage_unavailable、success=false；权限非MODE_ALLOWED则usage_access_required。label查不到退到包名，不把package visibility受限当成没有使用记录。days夹1—90、limit夹1—100。

本报告的MP01明确指2026-10-06-media-speech-usage-audit.md中的应用使用统计条目，避免与其它旧报告的同前缀编号混淆。按日平台统计与请求精确时刻的区间精度仍需单独解释；本轮不声称毫秒级事件窗口精确恢复，也不调用系统Usage Access设置。

## 既有测试与入口对照

SmsCodeExtractionPolicyTest85行8例涵盖中文/英文/OTP/one-time关键词、无语境、4—8位边界、窗口clamp、金额前缀、单纯前置验证码及后方候选优先；没有“前置验证码加后方金额”的反例，没有ContentResolver/权限/null Cursor集成。不能据单纯前置例通过断言认为前后混合已正确。

AggregateUsageTest36行4例覆盖七日聚合排名、limit按应用计、零时长剔除、相同时长稳定排序。没有真实UsageStatsManager、权限、null、设备未解锁或平台区间的端到端证据。测试全部只读，未执行或新增。

[MinisApp.kt:707](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L707)注册app_usage，[MinisApp.kt:718](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L718)注册read_sms_code alias，短信及通话也正常注册。它们读Android系统provider，属于现役平台桥接边界；不是新增guest开发工具安装器，更不能仅凭放在App源码就归为应该恢复PRoot的旧工具。

固定OpenMinisHEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1查既有Git树，没有本轮Telephony/SmsCode/UsageTools同名文件。只证明无同名直接对照，不推断不同模块或平台不存在等价实现。Eta移植声明按当前注释记录，未将其作为算法正确证明；未fetch上游。

## 全文覆盖及交付核验

| 新增全文文件 | 行数 |
|---|---:|
| [AndroidTelephonyTools.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/AndroidTelephonyTools.kt#L1) | 320 |
| [AndroidUsageTools.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/AndroidUsageTools.kt#L1) | 105 |
| [SmsCodeExtractionPolicy.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/SmsCodeExtractionPolicy.kt#L1) | 52 |
| [AggregateUsageTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/AggregateUsageTest.kt#L1) | 36 |
| [SmsCodeExtractionPolicyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/SmsCodeExtractionPolicyTest.kt#L1) | 85 |

五文件首尾完整读，原始全文输出无截断。后续组合行号/检索输出截断不算全文，MinisApp700—722已单独补读，仍partial。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-telephony-usage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-telephony-usage.json)。累计全文 **1349 / 1655文件、226000行**；partial6、not_registered300、excluded124，tracked1779。全库通读尚未完成。

交付校验旧185份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged无代码diff、1779快照Git blob/hash/bytes/lines及引用行号。仅新增本报告与台账。
