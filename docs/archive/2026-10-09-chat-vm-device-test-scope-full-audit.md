# 真实ViewModel与Room测试支撑、附件和Bot回合测试全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。三文件完整静态阅读，无截断。旧213报告和全部台账保留。未执行测试、构建、设备、模拟器、Root、云或真实Provider网络请求，未改产品代码。

## 测试真正经过的层级

ChatVmHarness创建in-memory Room、ChatRepository及真实ChatViewModel，等待sessionLoaded后反射替换currentProvider/currentModel，并设置chatOnlyForNextTurn。ScriptedProvider在stream入口记录LLMMessage列表和工具名单；普通sendMessageClamped仅给标题返回固定结果。它可以观察进入Provider抽象入口的消息，不覆盖真实Provider序列化、HTTP、设备guest或工具执行。

finally清ViewModelStore，等stream退出最多5000ms，然后清SessionEventHub并关闭db。等待返回Boolean没有assert，超时也继续关闭db；残留异步工作对测试可靠性的影响需要实际失败时序，不凭这一行登记生产Bug。该代码只测试环境支撑，不将其当App生产数据库关闭路径。

## 文本附件测试的验证盲区

AttachedTextReachesModelTest覆盖长粘贴文件和小文本文件，检查marker进入userTexts及XML结束标签未原样出现。userTexts把每条user.contentParts的Text和user.content一起拼接。因此只要任一表示含正文就通过；若content完整而parts仍占位，真实Provider优先读parts的旧CT01形状也可能通过这个断言。

这属于验证能力限制，不能据此说当前CT01又出现；前批实际生产发送/准备链已有修复证据。脚本Provider也没有走Anthropic/OpenAI/Gemini各自wire格式。结束标签测试只拒绝一个完整字面组合，未验证全部XML转义/解析边界。未运行，不能宣称真实设备附件已验收。

## Bot回合测试范围及遗漏

BotTurnExecutionTest使用独立同形真实VM/Room支撑，ControlledProvider提供complete/error/wait/partial/length模式。断言包含前成功不污染后失败、cancel结果与退出、外部receipt同id两次只显示/入请求一次、partial不是Completed、length为NeedsAttention、retry失败与busy拒绝及取消、programmatic prompt保留编辑身份/附件/原消息。

这些用例真实检查结果与状态，并非只验证方法名称。它们没有覆盖重试/工具块重跑中QUEUEDelivery提交再成功结束的后续drain，所以不能证明QD02不存在。程序化草稿测试没有quotedTexts断言，引用隔离还需要现版内部send不消费引用的源码证据；不能将这个用例扩大为所有composer状态都验证过。

ControlledProvider.waiting是一次性的CompletableDeferred，多次wait时旧完成值不能表示新请求已进入；后续取消retry用固定delay500ms而非新一轮到达屏障。未执行，是否造成测试偶发失效保持测试候选；不推断产品重试本身一定失败。

## 交付结论

新增确认生产问题0。本轮补足三个现版文件全文，明确测试能证明与不能证明的范围；旧CT01、QD02和程序化输入问题沿原编号/修复证据处理，不重复计数。所有测试均仅阅读。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/androidTest/java/com/openminis/app/ui/chat/ChatVmHarness.kt | 101 |
| src/android/app/src/androidTest/java/com/openminis/app/ui/chat/AttachedTextReachesModelTest.kt | 67 |
| src/android/app/src/androidTest/java/com/openminis/app/ui/chat/BotTurnExecutionTest.kt | 198 |

三个现版文件完整读取。新增全文3文件、366行；已全文重读0文件。其它rg定位不计全文。

累计全文1333 / 1758文件，202998行；partial 4，未登记421。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-vm-tests.json)。

校验旧213报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
