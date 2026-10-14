# 浏览器动作、DOM 提取与结果预算静态审计

日期：2026-10-06。项目 HEAD：d64e7c06d8159be90737dd9ba03dd9bd5855f339。

本轮新增 **9 项问题：8 项 P2、1 项 P3**。新增全文阅读 24 个文件、3920 行。只做静态阅读，没有修改产品代码、运行测试或构建、启动模拟器、访问设备或使用云资源。文中的触发样本和运算量均为源码推导，未实际运行。

参考版本固定为 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)，提交 b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c，只读本机已有 Git 对象，没有 fetch、运行或修改上游。

## 现役调用链

聊天从 [ChatViewModel.kt:9912](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9912) 解析 BrowserActionInput，再调用 BrowserTabPool。guest CLI 从 [BrowserUseOffloadHandler.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt:70) 使用同一解析器，第 99 行调用共享池。两者都进入 [BrowserUseManager.kt:580](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:580) 分发动作。

[BrowserTabPool.kt:558](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:558) 在执行结束后才限制结果文本。[BrowserPayloadLimiter.kt:148](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserPayloadLimiter.kt:148) 只裁剪 text，不限制页面扫描、下载字节、图片或执行期间的内存。

WebView 的创建、渲染和 Android 回调需要原生桥。本轮问题在具体动作和数据处理，不据此要求把 WebView 本身搬进 Ubuntu；沙箱内开发工作流和工具安装归属沿用已有独立报告。

## BR01 — P2：空文本被当成缺参，无法清空输入框

[BrowserActionInput.kt:80](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserActionInput.kt:80) 将 text 的空字符串转为 null。[BrowserUseManager.kt:929](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:929) 随即返回 type requires 'text'。

**触发：** 已有内容的输入框，调用 action=type、selector=#q、text=""。请求表达的是清空，实际被拒绝。DOM 层 [BrowserDomScripts.kt:532](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:532) 本来会把传入值交给原生 value setter，能够接受空串，但这个分支到不了。聊天和 CLI 的 JSON 入口均受影响。

应区别字段缺失、JSON null 与合法空字符串；后续 E2E 检查普通输入框、textarea、contenteditable 清空后实际值、框架状态与缺参拒绝。现有 BrowserActionInputTest 只覆盖非空 hi，没有覆盖清空。未执行验证。

## BR02 — P2：“已验证选择器”在生成和格式化两层失真

[BrowserDomScripts.kt:101](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:101) 先把 ID 截到 180 字符，再做 CSS.escape；[110 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:110) 只检查匹配数量为一，没有确认唯一匹配就是原元素。

**样本一：** 两个可见按钮的 ID 分别为 180 个 a，以及同一前缀再加 b。描述后一个按钮时，生成的选择器会指向前一个。数量检查通过，却把另一元素的选择器和当前元素的文字、坐标装进同一行。

**样本二：** 唯一按钮的 ID 为 170 个 a，页面侧生成正确的 171 字符选择器。[BrowserElementListFormatter.kt:81](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserElementListFormatter.kt:81) 又用普通文字 helper 截到 160 字符；[104 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserElementListFormatter.kt:104) 还折叠空白、替换引号。Agent 拿到的已经不是验证过的选择器。路径超出预算时，[DOM 第 137 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:137) 也会直接返回未经唯一性验证的截断路径。

[工具说明第 15 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BrowserUseTool.kt:15) 承诺 verified selector，但后续 click 的 [resolveTarget:207](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:207) 直接取 querySelector 的首个结果，无法纠正这类失真。应按实际目标身份验证完整选择器，超预算时省略并说明；不能把机器可执行字段按展示文字处理。后续 E2E 保存行结果和实际被点击元素，未执行。

## BR03 — P2：局部内容限制不传播截断状态，分页无法补回正文

[collectVisibleText:172](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:172) 对每个文本节点最多取 2000 字符，只有总字符、节点数量或时间预算耗尽才设置 truncated。被截掉的同一节点尾部没有登记。

**触发：** 一个可见段落含单个 4000 字符、无多余空白的文本节点。get_text 最终得到 2000 字符；[text:747](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:747) 以已经缩短的字符串计算 text_length 与 next_offset，source_truncated 仍为 false。默认窗口就报告读到了结尾；继续分页也无法得到原段落后半部分。

get_readable 的 [文本节点分支:280](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:280) 同样静默截 2000 字符。表格 [248 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:248) 的 60 行、12 列，以及列表 [323 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:323) 的 200 个子元素限制也不设置截断状态。

应让各局部限制保留真实完整性状态，并让分页对应可恢复的源内容。现在的测试主要检查脚本包含字段和模板片段，不能证明长节点内容完整。后续 E2E 对比长单节点、61 行表格和长列表的源文、全部分页结果及截断标记，未执行。

## BR04 — P2：readable 的代码块丢失换行和缩进

[pre 分支:295](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:295) 用 visibleText 读取代码，再包成 Markdown 围栏。visibleText 调用上述 collector，而 [cleanInline:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:44) 先把制表符、换行和空格序列全部合成一个空格。最后的 [cleanBlock:48](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:48) 又对整份 Markdown 删除行首空白、压缩连续空格。

**触发：** 页面 pre 内是两行 Python，第二行缩进。返回的围栏内容变成单行，原缩进消失。对 Python、YAML、Makefile 或命令示例，这会改变内容含义，不能仅以结果仍带围栏判断代码结构保留。

应让预格式文本保留自身换行、缩进，并避免整份 Markdown 的通用清理再次改写代码区。后续 E2E 对比含 tab、空行、缩进的代码样本与提取结果，未执行。

## BR05 — P2：任何 clip-path 都被判隐藏，正常控件无法操作

[visible:72](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserDomScripts.kt:72) 只要元素或祖先的 clipPath 不等于 none，就立即返回 false；它没有检查裁剪区域是否实际遮掉内容。[W3C CSS Masking 定义](https://www.w3.org/TR/css-masking-1/#clipping-paths) 说明裁剪保留区域内的绘制内容，裁剪本身不等于整元素隐藏。

**触发：** 一个尺寸正常、位于页面内的按钮使用 clip-path: inset(0)。页面仍可呈现按钮，脚本却把它判隐藏。enabled/editable 复用 visible，于是 click/type/hover 被拒绝；selectorState 的等待也找不到它，正文提取则跳过该元素的文本。find_elements 本身仍可能列出这行，造成“列得出但操作不了”。

应区分完全隐藏与部分／无实际损失的裁剪，而不是按属性存在一概拒绝。后续 E2E 对比 inset(0)、圆形裁剪与完全裁掉内容三种页面，保留截图和动作结果，未执行。

## BR06 — P2：DOM 骨架的深度限制在全量昂贵处理之后才生效

[BrowserUseJS.kt:125](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseJS.kt:125) 先取得整页所有元素，逐个计算可见性、叶节点与布局，再生成完整代表节点集合。[182 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseJS.kt:182) 为每个节点扫描前面全部节点，用 contains 查找父节点。没有扫描数量或截止时间预算。

**运算量推导：** 若页面产生 5000 个代表节点，该内层循环要经过 12,497,500 次节点对检查；这是循环计数，不是耗时实测。max_depth 即使设为 1，仍要经过这些步骤，直到 [204 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseJS.kt:204) 才裁树深度。[Manager 第 1097 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1097) 没有在页面脚本执行前施加 DOM 工作预算。

最终裁结果文本无法保护 renderer 计算或中间集合。应在遍历和建树阶段限制工作量、使用实际父链，并明确报告中止。后续 E2E 使用分档的大页面验证执行时间、响应性及截断状态，未执行。

## BR07 — P2：fetch 全量下载并多次复制，结果限制无法约束内存

[BrowserUseJS.kt:237](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseJS.kt:237) await fetch 后直接 arrayBuffer，随后构建 Uint8Array、逐字节累加二进制字符串、生成 Base64，再装入 JSON。没有 Content-Length 拒绝、累计字节上限或下载流预算。

现役 [BrowserUseManager.kt:1124](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1124) 调用这个 helper；[1139 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1139) 在 App 再解码成完整 ByteArray，第 1161 行把字节交给结果对象。CLI [BrowserUseOffloadHandler.kt:282](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt:282) 随后才持久化到 guest。

大文件会先经历 renderer 与 App 的全量分配／复制，再走最终文件写入。60 秒 Promise 等待、CLI 的 90 秒协程超时和 12 KiB 结果文字预算都不是字节上限；本轮没有测量 OOM 阈值，也不宣称下载多少就必定崩溃。

应在读取前及读取过程中限制累计字节，并让超时／取消结束下载工作。后续 E2E 覆盖大于上限、无 Content-Length、分块响应与取消后的请求终止，保留字节计数和文件结果，未执行。

## BR08 — P2：弹窗消息能挤掉原动作结果，事件数量上限不等于字节预算

[BrowserUseManager.kt:566](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:566) 把原始 message/defaultText 放进队列，没有长度处理。[InterceptedDialogQueue.kt:60](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/InterceptedDialogQueue.kt:60) 只限制最多 20 条；[90 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/InterceptedDialogQueue.kt:90) 将原始字符串完整拼成报告。

[BrowserTabPool.kt:677](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:677) 把报告放在原结果之前，随后才按整段文本取前缀。[boundText:140](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserPayloadLimiter.kt:140) 只保留前部。

**触发：** 页面产生一条 20000 个 ASCII 字符的 alert，随后动作得到正常结果。最终 12 KiB 大部分或全部被弹窗报告占用，原结果和后置的 tab_id 文本可能完全消失；结构字段 success 不会因此改变。队列也会保留并复制这条大字符串，所以数量限制没有完整约束内存。

应先约束每条事件及总报告字节，并给原动作结果和必要上下文留出独立预算。现有测试验证数量淘汰和报告以“Original tool result below”结尾，没有覆盖与最终预算组合。后续 E2E 保存长弹窗、原结果与最终工具输出，未执行。

## BR09 — P3：七天历史保留期不会在读取时生效

[BrowserHistory.kt:48](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserHistory.kt:48) 对同 URL 连续访问直接返回，早于 pruneOld；[55 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserHistory.kt:55) 的 getEntries、search、uniqueDomains 也没有按当前时间过滤。清理只有新增不同 URL 与初始化 load 时发生。

**触发：** App 进程长期存活且七天没有新增不同 URL，历史界面仍能读出过期条目；反复访问最后一个 URL 既不刷新时间，也不触发清理。[BrowserHistorySheet.kt:74](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserHistorySheet.kt:74) 直接使用这些查询。load 虽然清内存，但第 135 行之后没有将清理结果写回文件，磁盘保留也不能据此保证。

这是浏览器独立持有者的具体缺口，与旧 ND09 通知历史属于同类策略错误；没有将旧通知问题重复编号。应明确实际保留约定并让查询／持久化执行它。后续 E2E 推进时间、读取历史、重复同 URL 并检查磁盘与界面，未执行。

## 上游对照与未另计的问题

| 范围 | 固定上游证据 | 判断 |
|---|---|---|
| 空文本 | [BrowserActionInput.kt:58](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserActionInput.kt#L58) 同样 ifEmpty 转 null | BR01 是共有问题 |
| 骨架工作量 | [BrowserUseJS.kt:331](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseJS.kt#L331)、第 393–416 行 | BR06 全量扫描、双循环与事后裁深度为共有问题 |
| 全量下载 | [BrowserUseJS.kt:448](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseJS.kt#L448) 同样 arrayBuffer／Base64 | BR07 核心下载策略为共有问题 |
| 弹窗前置 | [InterceptedDialogQueue.kt:60](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/InterceptedDialogQueue.kt#L60)、[BrowserTabPool.kt:890](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt#L890) | 不限单条长度和前置报告为共有；本地最终前缀裁剪导致 BR08 的结果丢失 |
| 七天历史 | [BrowserHistory.kt:45](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserHistory.kt#L45) 同一清理时机 | BR09 是共有问题 |

固定上游 browser 目录树未列出本地 BrowserDomScripts、BrowserElementListFormatter、BrowserPayloadLimiter 等同名文件。本轮只据当前实现确认 BR02–05，没有用同名文件缺失推断上游没有其它等价实现；源码中的 Eta 移植备注也不作为已验证来源证明。

本轮另核对到 [BrowserUseManager.kt:167](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:167) 的无请求身份 JS Deferred 槽，与既有 KM01 的迟到回调问题属于同一种结构；本轮作为新的调用点记录，不增加一个重复编号。浏览器各异步动作、取消清理和完整生命周期留待全文读 Manager 时继续追踪。

execute_js 的“运行时错误会再次执行 body”猜测不成立：[evaluateBridged:1056](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1056) 仅在未编译时返回 null，运行时错误和超时返回具体结果，停止回退。未把该猜测计为问题。输出最终上限确实存在，也未错误声称 Agent 一定收到无限长的文本。

## 本轮全文覆盖

下表前 12 项在 src/android/app/src/main/java/com/openminis/app/browser/；后 9 项测试在 src/android/app/src/test/java/com/openminis/app/browser/。台账逐项保存完整路径、行数和 SHA-256。

| 文件 | 全文行数 |
|---|---:|
| BrowserAction.kt | 113 |
| BrowserActionInput.kt | 137 |
| BrowserActionResult.kt | 44 |
| BrowserPayloadLimiter.kt | 168 |
| BrowserTextWindowPolicy.kt | 76 |
| BrowserSelectorWaitPolicy.kt | 35 |
| BrowserElementListFormatter.kt | 114 |
| InterceptedDialogQueue.kt | 114 |
| BrowserHistory.kt | 148 |
| BrowserHistoryPolicy.kt | 28 |
| BrowserUseJS.kt | 257 |
| BrowserDomScripts.kt | 764 |
| BrowserActionInputTest.kt | 64 |
| BrowserDomScriptsTest.kt | 253 |
| BrowserElementListFormatterTest.kt | 178 |
| BrowserHistoryPolicyTest.kt | 47 |
| BrowserPayloadLimiterTest.kt | 212 |
| BrowserSelectorWaitPolicyTest.kt | 54 |
| BrowserTextWindowPolicyTest.kt | 110 |
| BrowserUseJsWrapperTest.kt | 40 |
| InterceptedDialogQueueTest.kt | 160 |
| scripts/test_browser_js_syntax.py | 133 |
| src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt | 509 |
| src/android/app/src/main/java/com/openminis/app/tools/BrowserUseTool.kt | 162 |
| 合计 | 3920 |

Manager、TabPool、HistorySheet 和 ChatViewModel 本轮仅增加相关片段记录，不计全文。读取测试和语法检查脚本不等于执行它们，也不等于设备验证通过。

累计全文 **730/1546 个纳入文件、150601 行**；另有 16 个部分阅读、800 个尚未登记全文。全库目标仍未完成。覆盖台账：[2026-10-05-code-review-coverage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

交付前复核全部 1670 个 tracked 路径及 SHA-256、HEAD、tracked／staged diff、此前 45 份报告原哈希，以及本报告本地证据链接和行号。只新增本报告、更新覆盖台账；旧报告和产品文件保持原值。仍未验证实际 renderer 性能、Android 版本差异及设备表现。
