# 流式解析目标、恢复状态与渐显计数静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文6文件、1073行。确认1项**内部计数算法错误**，尚未证明用户可见文字停滞；不把它计作已确认显示故障。仅静态阅读，没有修改产品源码，没有运行构建、测试、模拟器、adb、设备、Root、云、模型或登录请求，未读取凭据。旧报告不改。

## SF01：正常小帧间隔的分数进度被每帧丢弃

[RevealProgress.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/RevealProgress.kt:279) advance把整数revealedGraphemes转Float作为current，加本帧步长；第285行再toInt，未保存小数余量。smoothRevealSpeed第160行下限36 graphemes/s。

静态反例：ingest一个普通ASCII字符，progress=0、target=1、backlog=1；每帧elapsedSeconds=1/60秒，speed=max(36,1/0.2)=36，所以next=0.6，再toInt=0。重复任意多次同等帧，输入状态仍progress=0，永远没有积累到1。在120Hz下单步更小，类似。该推导只代入现有函数，没有运行测试。实际帧间隔偶尔变大可以跳过此停滞，不声称所有设备帧永远完全相同。

[StreamingFade.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingFade.kt:181) 真实FadeController.tick每帧把elapsedSeconds送给reveal.advance；revealedGraphemes只在本类第98行作为测试/诊断getter暴露。全main检索没有把该计数用作截断文字或控制alpha的其它消费方。tick另用rangeStartNanos、350ms fade与stagger计算alpha；overlay使用alphas而非revealedGraphemes，[StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:366) 将overlay结果显示。因此确认的是内部时钟与其“已显示进度”语义失真，**不能据此声称聊天文字不显示、显示丢字或帧循环永不停止**。

RevealProgressTest第153—164行使用MAX_REVEAL_FRAME_SECONDS=0.05连续推进；36*0.05=1.8，取整仍能推进，无法覆盖正常60Hz短步长的问题。其它测试覆盖浮点helper，不等于覆盖整数controller的跨帧累计。只读未运行或编写测试。

方向：如果保留此诊断时钟，应保留分数累计、只在对外计数时取整；若确认没有实际诊断消费者，也可以按最小实现原则评估是否需要它。不能修一个未用于绘制的计数后宣称改善了实际动画。没有实施。

## Conflated解析链与终态核对

StreamingMarkdownTargets全文95行：CONFLATED只保留最新待处理target，已在解析的target继续完成。第70—73行允许新内容只append时发布旧prefix，rewrite则丢旧结果；第63—69行另拒绝被新内容取代的complete snapshot，避免旧final误报。

[StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:597) 调用consumer，实际parse放Dispatchers.Default，publish写blocks；第620行LaunchedEffect提交displayContent/isStreaming。另一路第1010行也有consumer调用，本轮未将整个3856行文件升级为full。消费loop可能在publish后才到的新rewrite之前暂时显示旧prefix，这属于普通异步结果窗口，不能说它已经保证“所有时刻绝无旧结果”，也没有证明数据持久化被旧parse覆盖。

channel.close保留已缓冲工作，receiveCatching在无剩余时返回；submit忽略trySend失败，组件关闭后应由生命周期阻止使用。没有真实close后继续提交调用链，不新增丢终态缺陷。parse异常没有在通用consumer内处理，实际parse错误与上层恢复边界仍需完整核对，不用假设异常输入制造崩溃。

StreamingMarkdownTargetsTest全文144行，验证过期待处理合并、append继续发布、rewrite丢弃、complete被新内容取代、empty closed channel、holder最新值。测试未运行；没有真实Markdown布局、线程并发或屏幕显示证据。

## 恢复状态与渐显显示边界

StreamingMarkdownRestore全文56行：begin/pause递增generation；completeLayout拒绝旧generation、缺baseline与未追上布局，成功清baseline一次。允许rendered是baseline扩展且为current前缀，这是有意接受已追上的中间布局，不等于要求显示最新全部content。isStreamingMarkdownTargetComplete需要stream结束、snapshotComplete且内容完全相同。

StreamingFade全文288行：append分词、160个in-flight上限、350ms渐显，tick完成删除并行range/start/alpha；rewrite按生存prefix剪范围，不依赖已失效offset。160超限清ranges直接显示opaque，是明确保护，不当丢字。overlay追加原base注解，只覆盖动画期color，完成返回base；暂时覆盖链接色是显示策略差异，尚无具体不可达链接证据。

RevealProgress全文288行：增量字符边界向前回扫两个boundary，非append完整重建；commonUtf16PrefixLength之后调用alignedOffsetFloor，不能仅因某个helper边界情况就说所有emoji被截一半。RevealProgressTest的emoji测试比较增量结果与同一BreakIterator全量结果，而不是独立验证全部Unicode grapheme规范；因此两边一致也不能证明所有平台都正确处理ZWJ。未运行任何平台实验。

当前rememberFadeController无初始文本seed或跨节点“已经显示”记录；节点在流中重建可能重新从0渐显已有文本。需要查真实Lazy列表节点dispose、复用和当前stream入口，保留候选，不按所有重组都会重建controller计数。renderedText与诊断clock不能当实际像素显示证据。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其tree只有StreamingFade同路径，没有本轮Restore、Targets、RevealProgress三个同路径，不等于全上游没有类似功能。

上游StreamingFade第70—98行存在FadeBirth和fadeBirthPlan，区分FadeAll、SeedAll、SeedPrefix，第101—110行说明fragment已有文本记录及LRU边界。本地没有这套出生seed，作为重入候选对照；没有全文核实所有上游调用方，不能据helper存在宣布上游已完全解决重入或要求整体移植。SF01的本地RevealProgress不存在同路径上游，未宣称是上游同函数的错误。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [StreamingMarkdownRestore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownRestore.kt:1) | 56 |
| [StreamingMarkdownTargets.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownTargets.kt:1) | 95 |
| [StreamingFade.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingFade.kt:1) | 288 |
| [RevealProgress.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/RevealProgress.kt:1) | 288 |
| [StreamingMarkdownTargetsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/StreamingMarkdownTargetsTest.kt:1) | 144 |
| [RevealProgressTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/RevealProgressTest.kt:1) | 202 |

全文输出完整，Fade/Reveal分别按1—170、171—288分段读取，其它文件片段不重复计数或升级partial。累计全文 **1269 / 1546文件、242915行**；partial13，not_registered264，excluded124。全仓通读未完成。

完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前119份报告哈希、本报告文件链接行号和全文批次总计。只新建本报告并更新台账。
