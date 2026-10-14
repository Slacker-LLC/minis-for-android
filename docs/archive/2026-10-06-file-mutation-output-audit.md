# 文件编辑、并发写入与输出保留静态审计

日期：2026-10-06。目标源码 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮只读产品源码与现有测试源码，仅新建本报告并更新覆盖台账；没有运行构建、测试、模拟器、adb、Root、设备或云任务。以下为静态代码推导，不能当作设备复现记录。

本轮新增全文阅读 17 个文件、1,653 行，累计全文阅读 **669／1546 个纳入范围文件，137,492 行，43.3%**；13 个仍为片段阅读，864 个尚未登记全文。完整清单及 SHA-256 见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。全库阅读仍未完成。

| 编号 | 优先级 | 新确认问题 |
|---|---|---|
| FO01 | P2 | 重叠出现的搜索文本被误判为唯一匹配 |
| FO02 | P2 | 同一文件的路径别名使用不同锁，丢失并发修改 |
| FO03 | P2 | 文件编辑有损解码后重写，损坏未编辑的字节 |
| FO04 | P2 | edits 中的 null 绕过校验并被静默丢弃，剩余编辑仍提交 |
| FO05 | P3 | create_dirs 参数无效，false 仍自动创建父目录 |
| FO06 | P3 | 生产异步写入锁按路径永久累积，结束会话也不回收 |

## FO01 · P2 · 唯一匹配检查漏掉重叠出现的位置

[FileEditEngine.kt:104](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/internal/FileEditEngine.kt:104) 枚举匹配位置时，111 行把下一次搜索起点推进整个 needle 长度；[57 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/internal/FileEditEngine.kt:57) 只要得到一个位置就接受为唯一匹配。工具声明要求 old_text 确定唯一区域，见 [FileEditTool.kt:22](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:22)。

静态例子：原文件 `ababa`，old_text=`aba`，new_text=`X`。实际匹配起点为 0 和 2；当前遍历只记录 0，随后得到 `Xba` 并成功提交。跨两个 edit 的区域重叠检查无法补救，因为这里仅有一个 edit、但它具有两个候选位置。

[现有歧义测试:33](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/internal/FileEditEngineTest.kt:33) 覆盖分开的 `x x x`，没有覆盖匹配自身重叠。方向：唯一性枚举必须包含重叠候选；不同 edit 之间的重叠拒绝仍须保留。后续经真实工具入口验证 `ababa/aba`、`aaa/aa` 及唯一匹配，歧义请求应拒绝且文件摘要不变，保存请求、响应及前后摘要。本轮未执行。

## FO02 · P2 · 锁键使用请求路径，不能代表真实目标文件

[FileWriteTool.kt:74](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt:74) 与 [FileEditTool.kt:75](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:75) 均用 `sessionId + NUL + 原始 path` 调用 withKey。后者读原文件、匹配、写回，前者声称 expected_sha256 检查和写入对进程内文件工具原子。

实际路径解析在 [UbuntuPaths.kt:83](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:83) 为 `/workspace` 和 `/var/minis/workspace` 设置相同 session 子目录；[201–213 行入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:201) 将两种请求解析成同一 trusted root 与 components，但两个请求已经取得不同的 Mutex。

交错：A 用 `/workspace/x.txt`，B 用 `/var/minis/workspace/x.txt`；二者都读到旧版 S，A 写入 S+A 后，B 仍基于 S 写入 S+B，A 的修改消失。对于两个带相同旧摘要的 file_write，二者也可能都通过检查后依次提交。这个例子只需同一 session 的公开别名，不依赖符号链接、外部进程或 Root。跨 session 的全局 shared 路径又会因 sessionId 不同分配不同锁，见 [全局 aliases:77](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:77)。

[FileMutationQueueTest.kt:21](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/internal/FileMutationQueueTest.kt:21) 测的是 canonical host path 的 withFile；生产工具使用的 withKey 是另一套 map，见 [FileMutationQueue.kt:41](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt:41)。因此该测试不能证明当前调用链已按真实文件串行。

方向：复用受控路径解析产出的稳定存储身份作为锁键，统一别名和全局文件的身份；继续使用目录句柄访问，不能退回存在检查后直接打开不受约束的 host 路径。后续经真实工具制造上述交错，保存两份调用时间线和最终内容；旧摘要写入只能有一方成功，或编辑明确拒绝冲突。本轮未执行。旧 BF 报告只将原始路径锁列为候选，本轮完成调用链和时序确认后计数。

## FO03 · P2 · UTF-8 替换解码会改坏搜索区域之外的原字节

[FileEditTool.kt:85](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:85) 将全部字节直接转为 UTF-8 String；无效输入会被替换字符代替。随后 [116 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:116) 将整个结果重新编码并写回。没有严格解码失败检查，也没有保证原字节能无损往返。

静态字节例子：原文件包含 `61 62 63 0a ff`，请求把唯一的 `abc` 改成 `ABC`。末尾 `ff` 与搜索区无关，却在解码后变成 U+FFFD，保存后成为 `ef bf bd`。这样 ASCII 区域的正常编辑也能损坏文件尾部；并非只在被替换区域发生变化。非 UTF-8 文本及含非法字节的数据均可能触发。此项不声称符合 UTF-8 的普通文本都会损坏。

方向：不支持的编码应在提交前明确拒绝并保留原文件，或仅使用能保证未编辑部分不变的受支持编码策略。后续用真实 file_edit 覆盖 UTF-8、非法序列及不同编码的输入，保存逐字节差异、响应和摘要；拒绝时字节必须完全保持。本轮未执行。

## FO04 · P2 · null 编辑项穿过本地模型校验后被忽略

工具的 edits 元素声明为 object，且必须有 old_text/new_text，见 [FileEditTool.kt:18](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:18)。但 [ToolCallValidator.kt:105](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolCallValidator.kt:105) 对数组中的 null 直接 continue；执行时 [FileEditTool.kt:155](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:155) 又通过 optJSONObject 丢弃非 object 项。只要其余项目非空，剩余编辑照常提交。

例如合法标题、路径及 `edits=[{"old_text":"alpha","new_text":"A"},null]` 可以通过本地校验，随后只改第一项并返回成功，没有报告第二项不符合 schema。这是具体的本地模型路径，不依赖旧报告已记录的其他入口缺少统一 preflight。只含 null 的数组会被空 edits 检查拒绝，不能泛化为它也会写入。

方向：逐项验证并拒绝整个非法请求，提交前不得静默过滤请求项目；保留显式空 new_text 的合法删除语义。后续经 Provider 参数处理、preflight 和真实工具入口验证 null 项在不同位置、缺字段项及全部合法多编辑，非法请求的源文件摘要不变。保存最终实际派发参数、响应及文件差异。本轮未执行。

## FO05 · P3 · create_dirs=false 与工具契约不一致

[FileWriteTool.kt:23](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt:23) 宣称 create_dirs 默认 false，true 才创建缺失父目录；execute 根本没有读取这个字段。实际覆盖调用 [SecureFileAccess.kt:339](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:339)，由 [378 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:378) 无条件 ensureParent；append 同样经过该 helper。设置 false 不能阻止新建缺失父目录。

这是调用行为与公开参数契约的差异，不是越权证据。方向：按产品真实需求决定保留并实现开关，或删除无效开关并准确描述始终创建；避免继续向模型暴露不起作用的选择。后续从真实工具入口对缺失父目录分别传 true/false，记录目录树和响应。本轮未执行。

## FO06 · P3 · 异步文件锁条目没有回收路径

[FileMutationQueue.kt:19](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt:19) 是进程级 suspendEntries；[42 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt:42) 每个新 key 新增 Mutex，整个实现没有 remove/clear。相比之下 withFile 在无使用者后会删除条目。生产 file_write/file_edit 使用前者。

结束 session、删除文件和已完成调用都不删除 key；每次新 session 即使写同一相对路径，也会新增条目。因此 Android App 长期存活时，锁 map 与累计不同 session/path 数量一起增长。本轮只确认无界保留，不声称已测得 OOM、具体内存占用或崩溃阈值。

方向：按在用/等待者生命周期安全回收，不能简单在释放时删除而让等待者与后来调用拿到两把锁。后续经正常会话与写入入口生成、结束多个有界会话，验证空闲条目可回收、同一文件仍串行，保存生命周期及内存计数工件。本轮未执行。

## OpenMinis 固定源码对照

只读本机已有 upstream Git 对象，固定 commit：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。没有 fetch 或启动上游程序；上游片段不计入本库全文覆盖。

- FO01：上游 [FileEditTool.kt:78–85](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt#L78) 同样按 oldString.length 推进，漏掉自身重叠的出现位置。这是上游已有算法缺口；本地新增多编辑引擎仍未消除。
- FO02/FO06：上游 [FileEditTool.kt:76](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt#L76) 直接读、匹配、写，没有本地这套修改队列。不能说上游并发已正确；本地的实际锁机制与其原子性说明不一致。
- FO03：上游相同入口使用 readText，106 行 writeText，亦未严格拒绝非法 UTF-8，存在相同有损往返风险。
- FO04：该固定上游编辑工具只支持 old_string/new_string，没有本地 edits 数组；不能称上游已修复同一数组问题。
- FO05：上游 [FileWriteTool.kt:94](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt#L94) 明确无论 create_dirs 与否都创建缺失父目录，28 行却仍宣称默认 false。因此契约不一致也是历史行为，本地没有澄清。

## 去重、未升级线索与阅读边界

- 超长行被部分返回却给出 next_offset 下一行，已记录为 [BF08](/home/jiale/projects/minis-for-android/docs/archive/2026-10-04-backup-file-boundary-audit.md:219)，本轮全文复核，不新增计数。目录覆盖递归删除、非原子替换和编辑丢权限仍归旧 BF05/BF09。
- SpillPolicy.spillIfOversized 未检出生产调用，只有现有测试调用。因此它的文件名分配竞争和 host 路径提示问题只记线索，不当作现役工具已发生输出丢失。ShellOutputTruncator 同样未检出生产调用，末尾换行导致 tail 少留一行不列作生产缺陷。
- ToolResultPruner 注释声称尚未接入，实际 ToolResultBudget、MCPToolHandler、ChatViewModel 等已经调用，不能凭旧注释断言输出治理完全失效。
- 完整 diff 在截断前已经构造、file_read 即使少量行也先读完整文件，是后续资源预算核对线索；没有运行内存压力验证，不在六项中另计实际 OOM。
- 不重复检查或计数用户已明确排除的注释噪音、OkHttp/Preferences/helper 重复和 lint 基线。

本轮新全文文件：主源码 tools 下的 FileEditTool、FileReadOutputFormatter、FileReadTool、FileWriteTool；tools/internal 下的 FileEditEngine、FileMutationQueue、FileRevision、OutputRetainer、ShellOutputTruncator、SpillPolicy、ToolResultPruner；对应测试 FileReadOutputFormatterTest、FileEditEngineTest、FileMutationQueueTest、FileRevisionTest、OutputRetainerTest、ShellOutputTruncatorTest。每个文件全文范围及 hash 已登记台账。

WorkspaceFileClient、SecureFileAccess、UbuntuPaths、ToolCallValidator、ToolRegistry 等仅复核相关调用链片段，不增加本轮全文数。仅阅读现有测试源码，没有创建或执行测试。
