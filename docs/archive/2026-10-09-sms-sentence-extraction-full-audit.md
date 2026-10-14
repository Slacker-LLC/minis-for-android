# 短信验证码句子关联：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，不读取真实短信、不运行提取函数或测试、不操作设备；仅新增报告及台账。

## CT05：已修反例与剩余条件

[旧修复边界报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-sms-code-usage-aggregation-fix-boundary-audit.md)中的 `123456 is your verification code. Order amount 9999` 已由句子范围优先处理修复。现版已有测试直接断言该英文及中文尾句布局，不应继续报告旧反例仍然成立。

现版第59—60行在同句内仍无条件优先关键词后的数字，再考虑关键词前的数字。将尾句改成同句：`123456 is your verification code, order amount 9999`，两个数字都在句子范围中，after(inSentence) 选9999，前面的验证码不参与比较。中文 `123456 是您的验证码，订单金额 9999 元` 同样如此。逗号不属于 SENTENCE_END。这是静态确定的函数误选反例，沿用 CT05，不另增编号；没有声称真实短信中已发生误登录。

候选改进方向：保留前后数字的语义关系，遇到金额等竞争候选时拒绝唯一确定值或返回歧义；不要仅加某种标点，遗漏正常布局。

## 测试与调用范围

现版 SmsCodeExtractionPolicyTest 全文覆盖关键词、数字长度、无关键词拒绝、窗口clamp、前后布局、句子优先、换行回退和小数点。最后的回退测试明确允许 `Ref 777777. This is your OTP!` 返回777777，因此跨句兜底是既有设计，不能把所有跨句数字都解释成已修复或意外行为。没有同句前置验证码加金额的否定用例。

只取第一个关键词，以及关键词句没有数字时从其他句取数字，仍是需要产品语义约束的候选；不据此扩大为正文泄漏。

AndroidTelephonyTools 第110—170行仅局部核对：调用 codeIn，将结果放入 code/sender/timestamp_ms，正文不进入该输出；查询 null Cursor 明确失败。旧全历史扫描候选仍可见：date DESC但过期消息 continue，没有 selection 或 break。未测耗时，不新立性能缺陷。此局部重读不计本轮全文文件。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/tools/SmsCodeExtractionPolicy.kt | 62 |
| src/android/app/src/test/java/com/openminis/app/tools/SmsCodeExtractionPolicyTest.kt | 108 |

两个现版文件完整读取。新增全文2文件、170行；已全文重读0文件。其它rg定位不计全文。

累计全文1361 / 1758文件，208569行；partial 4，未登记393。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-sms-policy.json)。

校验旧224报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
