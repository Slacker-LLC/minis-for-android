# 思考规则编辑、等级映射保留与发送复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。本轮新增全文3文件、1473行；新增TSE01一项，复核MT05/MT06/MT07。仅静态阅读，不改产品代码、不编写或运行测试、不构建、不启动模拟器/adb/Root/设备/云任务，不写实际规则或调用模型。此前151份报告与所有旧台账保持原样，供Claude处理。

## TSE01 · P2 · 编辑已有CustomPath规则时丢掉未展示的等级映射

[ThinkingRuleEditor.kt:73](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/ThinkingRuleEditor.kt#L73) 只从seed.values读取HIGH到highValue；[ThinkingRuleEditor.kt:85](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/ThinkingRuleEditor.kt#L85) 保存重新构造CustomPath.values，只生成HIGH单项或emptyMap。界面没有LOW/MEDIUM/XHIGH/MAX/ULTRA映射输入，也没有带回seed中这些既有映射。

这不是只存在于任意手造对象：[ThinkingRulesCollection.kt:122](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/config/collections/ThinkingRulesCollection.kt#L122) 公开wireFormat writer接受完整JSON并用ThinkingRuleCoding解码；第160行add亦支持嵌套wire_format对象。[ThinkingRuleCoding.kt:94](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleCoding.kt#L94) decodeCustomPath遍历所有合法ThinkingLevel；第55行编码也保留所有映射。因此可经受授权的配置入口保存LOW=fast/HIGH=deep这样的合法规则，再进入原生设置编辑该规则。

[ThinkingRulesSection.kt:118](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/settings/ThinkingRulesSection.kt#L118) 用已存rule作seed；第224行保存到同一existingId。[ProviderRepository.kt:1678](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt#L1678) 更新该Room行，随后republishThinkingCache，并非只临时修改显示。普通静态序列：原规则LOW=fast、HIGH=deep → 进入编辑仅改名称或不改直接Save → values永久剩HIGH=deep → [ThinkingRuleResolver.kt:511](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt#L511) LOW只查format.values[LOW]得到null，不再写入原字段。HIGH仍可用，重启后的LOW也不会自动恢复。

与旧MT07不同：本项不经过配置get/set往返字符串化，损失发生在原生UI重建，合法seed已在存储里。MT05修复前该格式会抛错，现在格式已经能发，丢等级的实际影响也进入请求发送链。不是说所有思考字段都消失或所有请求失败；确认的是被删映射对应等级不再发送原有值。

方向记录：编辑时保留未展示的既有等级映射，只替换本页负责的HIGH；明确删除值才删除，格式切换有自己的语义。本轮未实现，也未运行UI复现。EditorFormatsEmitTest直接安装规则对象后调用resolver，不经过原生editor或Room，因此不能证明编辑往返正确。

## 旧问题修复范围

原编号来自[模型设置与思考规则审计](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-model-settings-thinking-audit.md)。

| 编号 | 当前静态状态 |
|---|---|
| MT05 可选格式导致构造抛错 | BooleanToggle/ExtraBodyToggle/CustomPath已实现，原直接抛错分支已修 |
| MT06 root/nested Effort的offValue不生效 | 仍在；root使用ctx.offEffort，nested OFF直接return |
| MT07 规则JSON读取丢嵌套类型 | 仍在；configValueFromJson继续逐字段Str(o.get(k).toString()) |

[ThinkingRuleResolver.kt:498](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt#L498) BooleanToggle与ExtraBodyToggle调用putPath写level.isEnabled；CustomPath开启按当前等级values、关闭按format.offValue，缺值省略。未知Gemini/Anthropic格式在此OpenAI emitter只日志并省略，不再抛IllegalStateException。真实[OpenAIProvider.kt:2627](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L2627) Chat Completions请求调用apply，instanceId来自thinkingRuleInstanceId，cache按实例查规则，用户规则仍排内置之前。只核对这个入口，不据此保证Responses/Gemini/Anthropic每条路径都遵从用户规则。

MT06仍由[ThinkingRuleResolver.kt:334](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt#L334) offEffort决定root关闭值；格式里的offValue没有读取。nested第339行OFF直接return。CustomPath.offValue现在正确读取不能证明两种Effort的旧问题也已修。必要的MiMo/Agnes/off allowlist防护与用户显式规则语义需协调，不能为修配置项而无条件给所有厂商发送同一个值。

MT07通过本轮全文复读215行ThinkingRulesCollection确认：第191行把values对象字符串化，writer只在ConfigValue.Obj情况下恢复对象，get后原样set仍丢映射。新增TSE01不替代或覆盖这个原问题。

## Resolver与现有测试的边界

完整阅读708行resolver：首匹配规则获胜；空wireFormat退默认root effort；Mistral、OpenRouter、OpenAI、Qwen/DashScope、统一网关、DeepSeek规则按明确优先级排列；xAI空effort声明只在限定vendor分支省略。custom rules允许覆盖内置，没有把用户主动覆盖当成越权。trace只报告新增顶层键，覆盖已有键不出现在emittedKeys；属于可观测性候选，未无证据追加用户行为故障。

Qwen预算按maxTokens严格小于上限，maxTokens<2省略预算；OFF不发Qwen开关是代码明确保留的当前行为，本轮不把历史注释替代厂商协议证据。Gemini专用模态先排除，3.x/2.5各自生成tier/budget；family识别部分仍用原始大小写、未知family不发，是进一步证据候选，不声称所有新模型不可用。Anthropic由真实Provider能力方法决定adaptive/budget，未读厂商账号配置或调用服务。

ThinkingRulesRegressionTest673行完整阅读：用真实OpenAIProvider+MockWebServer读取序列化请求，覆盖Mistral控制字段/历史禁止及Responses分支对照、Venice、GLM稀疏tier、ULTRA、MiMo关闭、Ark、DeepSeek兄弟字段、Qwen预算、历史reasoning回传和部分Gemini映射。capture用runCatching后只takeRequest第一请求，不等于验证整个重试/响应完成链；未创建或运行测试，源码中的历史设备结论不当成本轮真机证据。

ThinkingEditorFormatsEmitTest92行完整阅读：三类格式正/负值、CustomPath缺等级省略、putPath保留对象sibling。它明确断言缺等级不发，未覆盖UI保存时本来存在的等级被删除。原组合输出有截断，已单独重读该测试，未把截断读算全文。putPath遇非JSONObject中间节点会替换节点，字段路径校验/关键字段冲突属于需全链路核验的候选；没有臆造执行代码或数据外泄。

## 固定OpenMinis对照

只读固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git对象，GIT_NO_LAZY_FETCH=1，无fetch。上游ThinkingRuleEditor第65—102行同样只保留HIGH映射，TSE01有共同编辑逻辑。上游CustomPath emitter第673行使用当前tier缺值时fallback HIGH；本地第511行没有这个fallback，行为不同，因此不能说本地LOW丢字段的发送后果与上游完全相同。即使上游fallback继续发deep，原LOW=fast仍在编辑保存时被丢掉。

上游已实现这三种可选格式，可作为旧MT05的对照；未将其其它参数验证/上游iOS规则要求整体套到本项目，也不拿上游当运行时权限架构替代。

## 新增全文覆盖

| 文件 | 行数 |
|---|---:|
| [ThinkingRuleResolver.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt#L1) | 708 |
| [ThinkingRulesRegressionTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/ThinkingRulesRegressionTest.kt#L1) | 673 |
| [ThinkingEditorFormatsEmitTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/provider/thinking/ThinkingEditorFormatsEmitTest.kt#L1) | 92 |

resolver单独完整输出；RegressionTest分1—335、336—673完整阅读。已有full的ThinkingRuleEditor/ThinkingRuleCoding/ThinkingRulesCollection本轮全文复核不重复累加；ThinkingRulesSection、Repository、OpenAIProvider、BackupImporter只看片段，上游片段不计本仓覆盖。

新台账：[2026-10-07-code-review-coverage-37158d18-thinking-editor.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-thinking-editor.json)。累计全文 **1209 / 1652文件、195938行**；partial4、not_registered439、excluded124，tracked1776。全库通读仍未完成。

交付核验旧151份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照Git blob/hash/bytes/lines、证据行号。仅新建本报告和新覆盖台账。
