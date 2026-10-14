# Minis for Android — 记忆条目与文件预览新增排查

日期：2026-10-05。独立报告，不修改此前四份报告。

## 范围与结论

- 本地基线：`main` / `d64e7c06d8159be90737dd9ba03dd9bd5855f339`，以当前工作树源码为准。
- 对照上游：`/tmp/openminis-review-20261004`，固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。
- 本轮新增八项，重点是条目身份、列表与磁盘一致性、guest 文件缓存和预览读取。没有重复计入此前的键盘、退出草稿、整文件旧草稿覆盖等问题。
- 全部来自只读源码排查。没有修改产品代码，没有构建、启动模拟器、执行设备命令、运行测试或制造大文件。下文操作序列是复现建议与静态推导，不是设备通过记录。

| 编号 | 优先级 | 新发现 |
|---|---|---|
| MP01 | P2 | 旧条目仍提供编辑、撤销按钮，仓库却只支持今天和昨天 |
| MP02 | P1 | 正文被当作条目身份，可能修改或删除另一条同文记忆 |
| MP03 | P2 | 编辑后弹层和列表使用不同记录副本，后续操作使列表失真 |
| MP04 | P2 | guest 缓存被当作真实图片目录，画廊混入其它会话图片 |
| MP05 | P2 | Markdown 预览静默截掉 512,000 字节之后的内容 |
| MP06 | P2 | 预览限制没有限制读取量，打印还在主线程读完整文件 |
| MP07 | P2 | CSV 合法多行单元格被拆成错误记录 |
| MP08 | P2 | 另存为拿不到输出流，仍提示保存成功 |

<a id="mp01"></a>
## MP01 — 旧条目的操作按钮没有同步仓库的日期限制

**入口：** [ChatScreen.kt:6433](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:6433) 将真实记忆弹层的编辑、撤销回调接到 ViewModel；[ChatViewModel.kt:1183](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1183) 与 [ChatViewModel.kt:1247](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1247) 分别调用仓库的条目操作。

**根因：** [MemoryRepository.kt:425](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:425) 的 `candidateDateStrings()` 仅返回操作当天、前 24 小时的日期字符串；编辑和撤销都只遍历这两个文件，不使用原条目写入日期。

**具体序列：** 一个会话 ViewModel 保留了 2026-10-02 的操作记录，到 2026-10-05 用户再编辑或撤销这条记录。即使 `2026-10-02.md` 中的原文仍完整存在，仓库只查看 10-05 和 10-04，返回 `NotFound`。用户仍可通过整文件编辑修改旧文件，本项指操作日志里的条目级功能。

**范围限制：** 本轮没有证明旧操作日志会跨进程持久化。上述序列要求该记录仍在当前 ViewModel 中；不声称所有重新打开的历史会话都能看到这些操作记录。

**上游与设计边界：** 固定上游的仓库注释明确说两天限制是主动设计，避免撤销按钮静默修改更早的记忆。不能仅因旧条目返回 NotFound 就判定限制本身错误。实际问题是当前弹层 [SessionMemorySheet.kt:207](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:207) 只检查 `writtenContent != null`，仍提供编辑、保存和撤销按钮，没有把条目日期或不支持的原因反映到操作状态中。上游弹层也有相同缺口。

**修复方向与后续验收：** 若保留两天限制，按真实条目日期禁用不支持的操作并解释原因；是否扩大支持范围属于另一个产品决定。保留跨日期会话，区分超出支持日期、文件不存在及已经撤销三种结果。仅建议，未执行。

<a id="mp02"></a>
## MP02 — 相同正文的另一条记忆会被当作目标

- [SessionMemorySheet.kt:458](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:458)：`MemoryToolRecord` 没有文件日期、entry ID 或 tool-use ID。
- [ChatViewModel.kt:1181](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1181)：撤销仅取 `writtenContent` 传给仓库；编辑也是同样的正文定位。
- [MemoryRepository.kt:366](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:366)：按今天、昨天的顺序遍历，再按文件内标记顺序查找。
- [MemoryRepository.kt:376](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:376)：正文经 `trim()` 后相等就删除第一个匹配项；[MemoryRepository.kt:408](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:408) 的编辑使用相同匹配条件。

**具体序列：** 昨天会话 A 写入 X，今天会话 B 又写入 X。用户在 A 的记忆弹层撤销昨天那条记录，仓库先找到今天 B 的 X，于是删除 B 的条目，昨天 A 的条目仍在。编辑昨天那条记录，也会把今天 B 的正文改掉。两次调用完全串行即可发生，不依赖线程竞争。

**影响与边界：** 条目身份、时间与 UI 操作归属失真，可能错误删除或修改另一会话产生的记忆。记忆文件本来就是全局共享的，本项不是证明某个调用方突破 App UID 或远程权限边界。

**上游：** 固定上游也是按正文匹配第一个条目，问题共有。

**修复方向与后续验收：** 写入结果、操作记录和磁盘条目需要一致的身份；不同日期/会话写入同文后，编辑和撤销只能作用于选中项。仅建议，未执行。

<a id="mp03"></a>
## MP03 — 保存后生成两个副本，列表更新与撤销不再可靠

**第一次保存的源码路径：**

- [ChatViewModel.kt:1249](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1249)：用引用相等 `it === record` 找旧行，再生成一个更新 `writtenContent` 和 `preview` 的副本放进列表。
- [SessionMemorySheet.kt:226](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:226)：弹层另外用 `record.copy(writtenContent = pending)` 生成自己的副本，没有接收列表中新对象，也没有同步预览。
- [ChatViewModel.kt:1185](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1185)：撤销成功后又用结构相等的 `list - record` 移除列表行。

| 对象 | 第一次保存后正文 | 第一次保存后预览 | 来源 |
|---|---|---|---|
| 原记录 O | alpha | alpha | 编辑前 |
| 列表记录 L | beta | beta | ViewModel 的 copy |
| 详情记录 D | beta | alpha | 弹层的另一次 copy |

**两个明确后果：**

1. 留在同一详情页，再保存 beta → gamma。磁盘更新，但传给 ViewModel 的是 D；列表里的 L 与 D 引用不同，`===` 无法匹配，列表仍显示 beta。
2. 第一次保存后立即撤销 beta。仓库可以成功删除磁盘条目，但 L 和 D 的 `preview` 不同，结构比较不相等，操作日志行没有移除。

第二个序列要求修改了预览所取的首个非空行；第一个序列不要求预览变化。两者都不是此前 UI06 的异步保存草稿丢失，而是成功保存后对象身份与更新字段不一致。

**上游：** 固定上游 ViewModel 与弹层保留相同的两副本模式，问题共有。

**修复方向与后续验收：** 复用统一记录身份和更新结果，连续编辑两次、编辑后撤销、返回列表再进入均应与磁盘一致。仅建议，未执行。

<a id="mp04"></a>
## MP04 — 画廊扫描的是所有 guest 预览缓存

**当前可达路径：**

- [AppNavigation.kt:1166](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1166)：聊天文件浏览器使用带 `guestSessionId` 的 guest 文件路径。
- [FileBrowserViewModel.kt:550](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt:550)：不同 session/path 的文件只通过摘要区分名字，统一放在 `<cacheDir>/guest-file-browser/<digest>-<safeName>`，没有按来源目录分开。
- [FilePreviewScreen.kt:120](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:120)：点击预览后，仅把选中的 guest 文件复制到上述缓存文件。
- [FilePreviewScreen.kt:183](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:183)：图片实际走 `collectImageGallery(item.file)`。
- [FilePreviewScreen.kt:1201](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:1201)：该函数枚举缓存文件的宿主父目录，将所有图片当作源文件的兄弟文件。

**具体序列：** 先预览会话 A 的 `a.png`，再打开会话 B 的 `b.png`，且期间缓存尚未被清理。第二次画廊会纳入 A 的图片。B 真实同目录里没有预览过的 `c.png` 则没有被复制到缓存，不会出现在画廊中。

**其它可见后果：** [FilePreviewScreen.kt:1214](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:1214) 用缓存文件名作图片说明，暴露摘要前缀而非原始名称。图片集合取决于过去预览过什么，而非用户正在浏览哪个目录。

**边界：** 这是 App 内浏览来源混淆；没有证明其它 UID 文件被读取或图片被自动外发。

**上游：** 上游画廊也扫描宿主父目录，但上游 `FileItem` 是实际宿主文件，没有本地新增的 `guestPath`/`guestSessionId` 及扁平缓存适配。迁到 guest 文件 API 后，旧的“父目录即源目录”假设已不成立。

**修复方向与后续验收：** 画廊集合应来自当前 session 的真实 guest 目录及原始名字，不能借整个预览缓存推断。分别准备两个会话、两个目录，验证同目录未预览图片和其它目录已缓存图片。仅建议，未执行。

<a id="mp05"></a>
## MP05 — Markdown 截断没有任何提示

- [FilePreviewScreen.kt:489](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:489)：Markdown 只解码前 `MAX_TEXT_PREVIEW_BYTES = 512_000` 字节。
- [FilePreviewScreen.kt:502](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:502)：直接把截断文本送给 `MarkdownDocument`，没有截断状态或提示。
- [FilePreviewScreen.kt:449](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:449)：普通文本页已有截断提示，是可复用的现有行为。

**触发与影响：** 打开超过 512,000 字节的 Markdown，文件尾部不存在于渲染输入中，滚动也无法看到；页面呈现为完整文档，用户可能误以为文件后半部分已经丢失。截断落在代码块或段落中，还可能改变结尾的渲染。原文件本身没有被修改，分享、另存为与预览应分别判断，不能把本项写成磁盘数据已删除。

**上游：** 固定上游同样截断 Markdown 且未展示截断提示；本地换成 `MarkdownDocument` 后保留了这一缺口。

**修复方向与后续验收：** 与普通文本统一展示实际截断范围和完整文件入口；在阈值前后放不同标记，验证提示与尾部可达性。仅建议，未执行。

<a id="mp06"></a>
## MP06 — 预览上限没有约束完整读取，打印同步阻塞 UI

**读取方式：**

- [FilePreviewScreen.kt:421](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:421)：文本先 `readBytes()` 读完整文件，再截取展示前缀。
- [FilePreviewScreen.kt:488](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:488) 与 [FilePreviewScreen.kt:761](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:761)：Markdown 和 JSON 同样先完整读取。
- [FilePreviewScreen.kt:268](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:268)：打印按钮直接调用普通函数 `printFile`。
- [FilePreviewScreen.kt:1107](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:1107)：非 HTML 打印也先 `readBytes()`，这个读取没有调到 IO 协程，因此发生在 UI 事件线程。

**具体后果：** 即使只展示约 0.5 MB，打开一个 40 MiB 文本仍会分配和读取完整字节数组。guest 入口虽然受文件 API 的 50 MiB 上限保护，预览的 512,000 字节常量仍没有约束实际读取量。文本预览读取在 IO 线程，本项不把它误说成主线程；同步打印读取才直接阻塞 UI。

**附带输出问题：** 同一个打印分支把前 512,000 字节包成 HTML 交给打印框架，没有补读余下内容，也没有告知打印范围；不能把此输出当成完整文件打印。

**限制：** 本轮没有测量耗时、内存峰值、ANR 或 OOM，不能声称某个设备已经崩溃。静态确认的是无必要的完整读取、打印线程位置和打印内容上限。

**上游：** 固定上游保留相同的先完整读取再截断流程，以及同步打印读取。

**修复方向与后续验收：** 预览应有界读取，打印的文件读取移出 UI 线程并明确完整输出策略；对大文件检查读取量、界面响应和最终打印内容。仅建议，未执行。

<a id="mp07"></a>
## MP07 — CSV 的一条逻辑记录被按物理行拆开

- [FilePreviewScreen.kt:709](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:709)：按 `readLine()` 逐个物理行读取，每行直接加入一条表格记录。
- [FileContentRenderers.kt:199](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileContentRenderers.kt:199)：`parseCsvLine` 每次调用重新设 `inQuotes = false`，不能跨物理行继续被引号包裹的字段。
- [FilePreviewScreen.kt:710](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:710)：200 条上限也计的是物理行，可能在一个逻辑字段中途截断。

**最小输入：**

```csv
name,note
alice,"first line
second line"
```

这应是表头加一条数据记录，note 字段内含换行。当前入口会产生表头、`[alice, first line]`、`[second line]` 三行。未闭合引号也不会触发解析失败提示，用户看到的是错位表格。已有的同一行引号转义和分隔符处理并不解决跨行问题。

**上游：** 上游也是逐行调用相同解析器；本地把 helper 抽到共享渲染文件，没有改变这一限制。

**修复方向与后续验收：** 按 CSV 逻辑记录解析，跨行保留引号状态，并按逻辑记录计数；覆盖多行字段、双引号转义、逗号和截断边界。仅建议，未执行。

<a id="mp08"></a>
## MP08 — 输出流为 null 仍显示另存为成功

**入口与根因：** [FilePreviewScreen.kt:160](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FilePreviewScreen.kt:160) 的 SAF 另存为回调使用 `openOutputStream(uri)?.use { ... }`，随后无条件返回 `true`。若结果为 null，复制块完全不执行，但 `ok` 仍为 true，`:172` 选择成功提示。

**影响与限制：** 在 document provider 无法提供输出流却返回 null 的失败路径，页面提示成功，文件内容实际没有复制。抛异常的路径已有失败提示，不受本项影响；用户取消 picker 的 null URI 也已经正确提前返回。本轮没有模拟 document provider 或执行导出。

**上游：** 固定上游使用相同的 nullable stream 分支，问题共有。

**修复方向与后续验收：** 成功必须意味着拿到输出流且复制完成；分别验证 null stream、抛异常、用户取消和正常输出，核对提示与输出文件摘要。仅建议，未执行。

## 排除项与边界

- 核对了 SOUL 存储路径：`SoulStore.GUEST_PATH` 与记忆仓库均指向 `/var/minis/memory/SOUL.md`，未发现“弹层编辑写错目录”的证据，不列为问题。
- `ImagePreview` 有完整 Bitmap 解码，但当前图片分类先进入 `ImageGalleryViewer` 并返回，画廊使用 Coil；不以这段被前置分支绕过的实现声称当前图片预览必然出现大图 OOM。
- 本报告只提出问题及后续处理方向，当前源码和此前四份报告保持原样。
