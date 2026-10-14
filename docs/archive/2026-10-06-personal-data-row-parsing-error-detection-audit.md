# 个人数据查询的行解析与错误识别静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2项P2确认缺陷**；全文读取5文件、465行。只排查记录，未改产品源码，未运行测试、构建、模拟器、设备、Root、云或个人数据查询任务。旧报告不改。

## PD01 / P2：按物理行解析导致多行笔记正文和字段静默丢失

[ColorOsPersonalDataTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ColorOsPersonalDataTools.kt:74) 查询ColorOS笔记的raw_title、raw_text、update_time等列；[PersonalDataQueryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PersonalDataQueryTools.kt:103) 把成功命令的stdout交给parseRows，随后不校验行完整性就返回ok=true。

[PersonalDataContentParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PersonalDataContentParser.kt:23) 先lineSequence，再仅保留startsWith Row: 的行，每列正则也仅在这一个物理行中匹配。正文换行不是一个新记录，却被这个处理拆开；后续物理行及其上的真实列全部被丢弃。

[AOSP Content.java 的 QueryCommand 输出实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/content/src/com/android/commands/content/Content.java)直接将cursor字符串加进column=value，列间加逗号，再println；没有把值内换行转义。此次核对的页面blob为 `f0ac53014bd37640cd8d80d8a9c1c989346437c6`，查询输出位置在源码QueryCommand。该证据支持原始输出格式，不证明具体OEM provider一定可访问。

静态反例：一条合法笔记raw_text是“第一行”换行“第二行”，后面有update_time=123。content输出的首个物理行包含Row: 0和raw_text=第一行，下一物理行是第二行及后续列。parseRows只返回raw_text=第一行，update_time等列不在首行就缺失。items.size仍为1，limit=10时truncated=false；结果把不完整正文表现为完整成功项。原厂数据库没有被修改或删除，确认丢失发生在工具返回值。

同一个文本协议还有分隔符歧义：正文包含逗号后紧跟另一已声明列名及等号，会提前结束该列；正文新行以Row:开头可能被当新项。AOSP把数据库NULL输出为大写NULL，parser只忽略小写null，存在类型丢失候选。它们均属于这个非结构化输出的解析边界，不另拆成新问题计数。

方向：选择可无歧义保留字符串的输出/桥接格式，或者明确处理多行与不完整结果并标记截断，不能只用物理行数证明记录完整。没有修改查询协议，也未读取真实笔记。

## PD02 / P2：正常内容含异常字样就被误判为provider失败

[PersonalDataContentParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PersonalDataContentParser.kt:16) 在stdout和stderr上都做无位置限制的contains，匹配Error while accessing provider、IllegalArgumentException、SecurityException等字样。[PersonalDataQueryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PersonalDataQueryTools.kt:91) 即使命令result.success=true，匹配到字样仍返回PERSONAL_DATA_UNAVAILABLE、success=false，并完全不解析items。

静态反例：开发者保存一条单行笔记，正文是“今天排查 java.lang.SecurityException: 权限错误”。provider正常返回该笔记、退出码0、stderr空。正常stdout行的raw_text含这段合法文字，hasProviderFailure返回true，整个查询失败；用户无法通过这个工具读取该条内容。录音摘要包含同样讨论文字也经过相同路径。

这是错误判定错误作用于业务数据，不是provider真的拒绝，也不是权限绕过。与PD01不同，它不需要正文换行，修好多行解析也无法消除无位置contains误判。方向：分离provider诊断与记录内容，根据已确认的错误输出结构识别失败，并保留普通字段中的异常描述。没有实际调用provider。

## 查询参数、权限与测试证据范围

PersonalDataQueryPolicy将limit约束1—30，keyword最多200字符；likeClause顺序转义反斜线、百分号、下划线和单引号，固定where与keyword分别括号合并。URI、projection、sort来自固定handler声明，PrivilegedCommandRunner接受argv而不是拼接raw shell；本轮没有发现新的任意SQL/命令参数入口。

PersonalDataQueryTools解析失败JSON降为默认参数；错误类型optString/optInt也可能被宽松转换，暂列参数校验候选。实际上层schema和受信调用约束尚需逐入口证明，不把所有无效JSON描述为能绕过权限。

limit在获取并解析全部stdout后才take，不会下推给provider；truncated仅比较已解析items数量。本轮没有以此推断具体厂商数据库规模或设备卡顿。现有AndroidCommandResult没有输出截断字段，完整性仍需结合受控runner的边界；不能拿其它AppCommandRunner的上限直接当成这条Root路径事实。

生产注册在 [MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:615)，三个handler及alias确实进入ToolRegistry。它们与ColorOsMemoryTools的Xposed数据库桥接是不同实现；已有CM01/CM02预算/状态问题不重复计算。工具属于Android系统访问边界，不据此自行修改App/guest分工。

PersonalDataContentParserTest全文43行，仅测试真实异常与No result found、普通逗号正文，不含多行正文或异常字样合法正文。PersonalDataQueryPolicyTest全文64行验证SQL转义、OR/AND、行数和keyword长度。测试只静态读取，未执行；不能以这些fixture宣称真实Root或OEM provider验收通过。

## OpenMinis对照与全文覆盖

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git树，未fetch；没有本轮三个生产文件同路径实现。源码Eta来源按注释记录，未读取Eta实际源码，不借移植说明证明解析正确。没有恢复minisd/PRoot。

| 本轮全文文件 | 行数 |
|---|---:|
| [ColorOsPersonalDataTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ColorOsPersonalDataTools.kt) | 202 |
| [PersonalDataContentParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PersonalDataContentParser.kt) | 41 |
| [PersonalDataQueryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/PersonalDataQueryTools.kt) | 115 |
| [PersonalDataContentParserTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/PersonalDataContentParserTest.kt) | 43 |
| [PersonalDataQueryPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/PersonalDataQueryPolicyTest.kt) | 64 |

累计全文 **1193 / 1546文件、229937行**；partial13，not_registered340，excluded124。全仓通读未完成。所有文件全文返回完整，已有full调用方只片段复核，不重复登记。

完成时核对全部1670 tracked路径SHA-256、字节数和正数文本行数，固定本仓/上游HEAD、tracked/staged空diff、此前105份报告哈希、新报告文件链接及行号、覆盖批次总计。只新建本报告并更新覆盖台账，没有访问真实个人数据、音频、账号或凭据。
