# 敏感值、提示词快照与分句：已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读三个已有测试，未读取界面密码、提示词文件或执行测试；只新增报告及台账。没有新增已确认缺陷。

## 界面敏感值

UiSensitiveValuePolicyTest 对真实redactAccessibilityValue传入password=true，验证实际值、圆点及空串均返回空；普通标签保留。它没有真实AccessibilityNodeInfo或UI树遍历，不能证明password标志识别正确，也不覆盖contentDescription、截图、OCR、日志等其他数据路径。两例只能支持该纯函数的指定布尔输入语义，不宣称所有密码界面已隐藏。

## 提示词快照

SystemPromptBuilderCacheTest 构造SoulFile并调用identitySection，断言名字、body和Response style片段存在。文件名叫CacheTest，但没有缓存命中/失效、并发快照、文件读次数或不同会话隔离断言。它证明传入对象能够渲染指定片段，不能单凭测试名证明整个构建器缓存正确。“without file access”也没有I/O监视器或拒绝访问注入，应结合实际identitySection实现核对。

## 分句

SentenceSplitterTest 覆盖保留尾标点、无标点整句保留、空白输入为空、ASCII/CJK分隔符、尾部残句、trim及换行不作为分句终止符。测试明确要求分句与TTS分块终止符集合不同，不应为去重而擅自统一二者。

用例没有小数点、缩写、连续标点、Unicode组合字符或真实语音纠正diff。当前断言支持指定字符串结果，不证明所有自然语言句子边界和用户编辑保真。未运行测试，未改规则，也不凭缺用例新立Bug。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/tools/android/UiSensitiveValuePolicyTest.kt | 18 |
| src/android/app/src/test/java/com/openminis/app/agent/SystemPromptBuilderCacheTest.kt | 25 |
| src/android/app/src/test/java/com/openminis/app/shared/SentenceSplitterTest.kt | 54 |

三个现版文件完整读取。新增全文3文件、97行；已全文重读0文件。其它rg定位不计全文。

累计全文1408 / 1758文件，212049行；partial 4，未登记346。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-sensitive-prompt-tests.json)。

校验旧241报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
