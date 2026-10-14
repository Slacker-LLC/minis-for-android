# Responses结束事件、历史回放与测试覆盖范围审查

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮新增全文5文件、845行；无新增确认产品问题，测试盲点单独说明，不凑新编号。只读现有源码与测试，未改产品代码、创建/运行测试、构建、模拟器、adb、Root、设备、云任务或真实模型/账号请求。此前153份报告和全部旧台账不覆盖。

## 结束事件：正常完成与真实截断保持区分

ResponsesApiFinishedTest155行完整阅读，覆盖completed无DONE、completed加DONE仅一次Finished、工具结束原因tool_use、仅文本delta后EOF无Finished。真实[OpenAIProvider.kt:1251](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L1251) completed确定finishReason并标记sawFinishReason；第1479行尾部只在sawFinishReason且未发Finished时补发。不能把没有DONE等同连接中断，也不能把只有部分文本的EOF当成已完成。

一个明确测试边界：tool calls yield tool_use样例直接发送output_item.done而不发送added，而且只断言Finished.stopReason。[OpenAIProvider.kt:1183](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L1183) function_call done必须先在responsesToolCalls找到acc，否则continue；该样例没有acc，实际不会生成ToolCallComplete，但completed.output仍包含function_call使stopReason变tool_use。所以此用例只能证明结束原因映射，不能证明工具构造、派发、结果回传成功。未单凭缺少added的非完整事件序列确认兼容端点产品故障，也未把它当成端到端执行工具证据。

尾部对已added但未done的acc会第1442行flush为ToolCallComplete，解析失败变empty JSONObject；这是已有fallback策略，需要结合工具preflight/参数拒绝链评估，不能仅凭flush称工具必定危险执行或工具必定丢失。OpenAIProvider还未登记全文，本轮不拿这些片段冒充完整审查。

## incomplete：保留可见答案与空答案失败

ResponsesIncompletePartialTest202行完整阅读：部分文本遇max_output_tokens或content_filter保持并以length结束；空/空白/只有reasoning仍报错；正常完成不受影响。[OpenAIProvider.kt:1226](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L1226) 读取incomplete_details.reason；第1230行以responsesOutputText.isNotBlank决定保留，非可见reasoning不计入这个变量，与样例一致。没有在本轮跑fixture，不承诺所有代理事件形态相同。

incomplete的usage不在此分支发送、存在tool/media但无文本时的决策，以及content_filter统一显示length的产品语义均需更多调用链证据；这里只记录边界候选，不把“测试未包含”直接当成Bug。测试未证明异常后ViewModel会如何落盘、恢复或fallback；文件开头的历史现场报告不当成本轮设备实测。

## 加密输出项：捕获与回放不等于存储全过程验证

ResponsesOpaqueItemReplayTest207行完整阅读：已有providerOutputItems中的reasoning JSON先于function_call回放，保留id/encrypted_content；缺项或非法JSON走重建；流式reasoning item含非空encrypted_content才发ProviderOutputItem，无加密内容不捕获。[OpenAIProvider.kt:1175](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L1175) 捕获条件与测试一致；第3100行回放遍历可解析JSON直接input.put，后续文本及ToolUse重建。

这套测试分别检验stream→chunk和LLMMessage→请求body，没有串起真实agent loop、Room持久化、进程重启、compaction和跨provider fallback。不能凭单文件断言全会话生命周期保存正确，也不能因没有这一整条测试就宣称加密内容已经丢失。非法但可解析的item类型、当前模型与历史item兼容性仍需更多证据，未另编号。

## 温度与顶层图片

ResponsesApiTemperatureOverrideTest81行完整阅读：非reasoning模型temperature=0.35到请求，reasoning模型省略temperature。[OpenAIProvider.kt:3039](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L3039) supportsTemperatureOverride gate对应；测试未覆盖OAuth、每个session配置入口、额外body覆盖和不同厂商所有温度语义。

ResponsesTopLevelImageTest200行完整阅读：顶层imageParts到input_image字符串dataURL，多图及原文本保留，非vision保留read_image占位而无pixels，无图仍是普通文本shape。实际[OpenAIProvider.kt:3237](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L3237) 与当前user turn合并顶层图片；structured ImageData第3188行也有图像转换/压缩边界。测试的8字节fixture不是可解码的真实图片，只证明请求结构和载荷存在，不证明图片压缩、尺寸、MIME有效或视觉端点能处理；没有将它写成真机视觉验收。

## 固定OpenMinis对照与范围

只读固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的已有Git对象，GIT_NO_LAZY_FETCH=1，无fetch。上游OpenAIProvider第1292行function_call done也要求先有acc；因此结束原因测试的有限证明范围不能通过上游同样逻辑自动扩大。未全文比较上游五套测试，不宣称上游与本地全量一致，也不改变Direct Ubuntu架构边界。

五个测试本轮均完整无截断输出。生产终止/回放片段初次组合截断后，已单独阅读所需尾部和图片范围；生产源仍按片段处理，不升级全文。只有完整阅读才登记新增覆盖。

| 新增全文文件 | 行数 |
|---|---:|
| [ResponsesApiFinishedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ResponsesApiFinishedTest.kt#L1) | 155 |
| [ResponsesApiTemperatureOverrideTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ResponsesApiTemperatureOverrideTest.kt#L1) | 81 |
| [ResponsesIncompletePartialTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ResponsesIncompletePartialTest.kt#L1) | 202 |
| [ResponsesOpaqueItemReplayTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ResponsesOpaqueItemReplayTest.kt#L1) | 207 |
| [ResponsesTopLevelImageTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ResponsesTopLevelImageTest.kt#L1) | 200 |

新台账：[2026-10-07-code-review-coverage-37158d18-responses-lifecycle.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-responses-lifecycle.json)。累计全文 **1219 / 1652文件、197676行**；partial4、not_registered429、excluded124，tracked1776。全库通读未完成，目标继续保持。

交付前核验旧153份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照Git blob/hash/bytes/lines与证据行号。仅新建本报告和本轮新台账。
