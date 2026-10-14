# Minis for Android — 编辑页与保存流程新增排查

日期：2026-10-04。独立报告，保留此前三份报告原文。

## 范围与证据

- 本地基线：`main` / `d64e7c06d8159be90737dd9ba03dd9bd5855f339`，以当前源码为准。
- 上游对照：`/tmp/openminis-review-20261004`，固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。
- 本报告记录用户指出的键盘问题，并新增五项编辑、加载及保存问题，不重复计算之前的 55 项。
- 以下结论来自静态调用链。键盘表现由用户反馈，源码确认布局差异；异步时序和设备表现没有完成端到端复现。
- 期间误把排查当成修复，加入的两行 `imePadding` 代码已经撤回；误启动的构建已结束，本次启动的模拟器和测试均已停止，临时测试目录已清理。最终产品源码没有改动，也不把这些误操作当作缺陷验证证据。
- 本轮只交付本报告。文中的修复方向和验收步骤均是建议，没有实施。

| 编号 | 优先级 | 问题 | 证据性质 |
|---|---|---|---|
| UI01 | P2 | 记忆文件编辑页缺少键盘避让 | 用户反馈 + 布局源码对照 |
| UI02 | P2 | 记忆和系统提示词编辑页返回时直接丢弃未保存内容 | 明确控制流 |
| UI03 | P1 | 记忆读取失败被当成空文件，仍允许保存覆盖；加载前也能保存 | 明确错误映射；加载窗口为时序风险 |
| UI04 | P1 | 保存每日记忆的旧草稿会覆盖后台新写入的记忆 | 明确先后操作序列 |
| UI05 | P2 | 系统提示词写盘失败仍更新缓存、显示已保存 | 明确失败控制流 |
| UI06 | P2 | 系统提示词保存期间继续输入，完成回调会清掉后续草稿 | 明确异步窗口，未做时序复现 |

<a id="ui01"></a>
## UI01 — 记忆文件编辑页缺少键盘避让

**用户现象：** 输入时键盘有时没有把页面顶上去，记忆文件编辑页可见，系统提示词编辑页正常。

**源码对照：**

- [MemoryManagementScreen.kt:330](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:330)：`MemoryFileEditScreen` 自建 `Scaffold`，内容容器只有 `fillMaxSize → padding(padding) → padding(horizontal)`，没有处理 IME inset。
- [MemoryManagementScreen.kt:350](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:350)：输入框使用 `weight(1f)`，依赖父容器提供正确的可用高度。
- [SystemPromptSettingsScreen.kt:63](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:63)：系统提示词页使用 `SettingsScaffold`。
- [SettingsComponents.kt:305](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsComponents.kt:305)：共享容器有 `imePadding()`，键盘弹出时缩小内容区。
- [MainActivity.kt:565](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:565)：窗口启用了 edge-to-edge；[AndroidManifest.xml:205](/home/jiale/projects/minis-for-android/src/android/app/src/main/AndroidManifest.xml:205) 虽配置 `adjustResize`，记忆页仍缺少正常设置页已经使用的 Compose inset 处理。

**影响：** 记忆编辑区可能延伸到键盘背后，底部文字、光标和全局记忆说明被遮挡。具体遮挡高度随系统、输入法、导航模式及窗口大小变化，不能把静态差异写成所有设备必现。

**上游对照：** 固定上游的 `MemoryFileEditScreen` 也自建容器且没有 `imePadding`，共享 `SettingsScaffold` 则有，属于共同遗漏。

**修复方向：** 让这个独立编辑容器复用现有的 IME inset 处理，保留顶部保存按钮和长文本编辑布局。

**后续验收建议：** 全局/每日记忆分别测试短文本、长文本末尾输入、键盘重复弹出与收起；对照系统提示词页，保存编辑区、光标与键盘边界截图。本轮未执行。

<a id="ui02"></a>
## UI02 — 返回直接丢弃未保存内容

**触发：** 修改记忆文件或系统提示词，不点保存，点顶部返回或在键盘收起后执行系统返回，再重新进入。

- [MemoryManagementScreen.kt:283](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:283)：记忆草稿仅在页面 `remember` 状态中。
- [MemoryManagementScreen.kt:303](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:303)：返回直接调用 `onBack`，没有 dirty 检查、草稿持久化或丢弃确认。
- [AppNavigation.kt:1388](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1388)：该回调直接弹出页面。
- [SystemPromptSettingsScreen.kt:53](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:53)：系统提示词草稿也只保存在 `remember` 中；`:65` 直接使用返回回调，已计算的 `dirty` 没有保护退出。
- [SoulSettingsScreen.kt:228](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SoulSettingsScreen.kt:228)：同项目的 SOUL 编辑页已同时保护顶部返回和系统返回，可作为现有模式参考。

**影响：** 用户误触返回会丢掉本次输入，重新进入只读到旧文件。键盘是否避让正常与草稿是否受到保护是两项独立问题。

**上游对照：** 固定上游记忆页同样直接返回，没有草稿离开保护。固定提交没有本地这两个自定义系统提示词文件，不把本地新增路径说成上游已有修复。

**修复方向：** 复用现有 SOUL 页的 dirty/退出确认模式，覆盖两种返回入口。

**后续验收建议：** 修改后分别从顶部、系统返回退出；取消离开应保留输入，确认丢弃后应保留原文件。保存退出不应再次询问。本轮未执行。

<a id="ui03"></a>
## UI03 — 读取失败显示为空，仍能覆盖原文件

**完整链路：**

- [MemoryRepository.kt:450](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:450)：读取异常被 `runCatching(...).getOrNull()` 吞掉。
- [MemoryRepository.kt:338](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:338)：公开 `readFile` 又把 `null` 转成空字符串，无法区分空文件、不存在和读取失败。
- [MemoryManagementScreen.kt:295](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:295)：页面把这个结果直接装入编辑框，没有读取错误状态。
- [MemoryManagementScreen.kt:307](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:307)：保存一直可点击，空字符串也会传给整文件写入。
- [MemoryRepository.kt:468](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:468)：写入不要求此前读取成功。

**一个可明确构造的失败条件：** [WorkspaceFileClient.kt:23](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:23) 将完整读取限制为 50 MiB；[SecureFileAccess.kt:67](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:67) 对超限文件抛错。若 app-owned 记忆目录里已有由 guest 等途径写入的 51 MiB 普通文件，编辑页会显示为空。保存一个小草稿或空字符串时，[SecureFileAccess.kt:113](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:113) 检查的是新内容长度，随后替换原文件；原有内容因此被覆盖。这个样本只是静态推导，没有实际创建大文件。

**另一个同根因窗口：** 初始 `content = ""`，读取放在 IO 协程中，读完前输入框和保存按钮都已可操作。此时保存会使用初始空草稿，迟到的读取结果也会直接替换用户已经输入的草稿。触发依赖 IO 时序，本轮没有做压力复现。

**上游对照：** 上游 `readFile` 同样把读取异常映射为空，但没有本地的 50 MiB 文件 API 边界；上游页面读取也没有本地这一层 `withContext(IO)`。不能把本地异步窗口和大小限制原样归因于上游。

**修复方向：** 区分加载中、有效内容、文件不存在及读取失败；未拿到有效基线时禁止提交覆盖，不用空文本代表错误。

**后续验收建议：** 延迟读取、拒绝读取、超限文件、真正空文件分别进入页面；失败时原文件摘要不变，成功读取前的输入不得被静默覆盖。本轮未执行。

<a id="ui04"></a>
## UI04 — 旧每日记忆草稿覆盖后台新条目

**无需同时抢锁的操作序列：**

1. 用户进入当天记忆文件，编辑页读取内容 A。
2. 另一个会话的 Agent 成功执行 `memory_write`，把新条目 B 写到同一天的文件，磁盘变成 B + A。
3. 用户在仍打开的编辑页修改 A 后保存，磁盘被替换成 A′，B 消失。

**证据：**

- [MemoryTools.kt:115](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/MemoryTools.kt:115)：实际工具调用 `repository.writeMemory`。
- [MemoryRepository.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:44)：按当天日期确定文件；`:57` 用 `entry + existing` 生成并保存新内容。
- [MemoryManagementScreen.kt:295](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:295)：编辑页只在文件名变化时读一次，没有订阅后续修改。
- [MemoryManagementScreen.kt:308](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:308)：保存提交本地旧草稿。
- [MemoryRepository.kt:340](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:340)：整文件保存没有比较原始版本、摘要或修改时间。

**影响：** 后台已经报告写入成功的记忆被之后的正常 UI 保存抹掉。单纯给写文件加锁不能解决这个先后序列，因为三步可以完全串行。

**上游对照：** 固定上游也是读一次、整文件保存，没有旧草稿冲突检查，问题共有。本项对象是记忆文件，与此前 N04 的例程运行回执不同。

**修复方向：** 在实际提交边界检查页面的原始版本；发生变化时明确处理冲突，不能直接覆盖或用简单拼接冒充通用文本合并。

**后续验收建议：** 保持当天记忆编辑页打开，从另一会话写入唯一条目，再保存编辑；新条目应保留或保存被明确拒绝。保留前后文件及工具结果。本轮未执行。

<a id="ui05"></a>
## UI05 — 系统提示词写盘失败仍显示已保存

- [CustomPromptStore.kt:79](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt:79)：尝试写临时文件并提交。
- [CustomPromptStore.kt:93](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt:93)：任何异常只记日志，没有抛出或返回失败。
- [CustomPromptStore.kt:96](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/prompt/CustomPromptStore.kt:96)：即使失败，也无条件把新文本发布到 `cache` 和 `textFlow`。
- [SystemPromptSettingsScreen.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:70)：调用返回后直接清空草稿，dirty 消失，页面按更新后的 flow 显示已保存。

**触发与影响：** 写入因空间不足等原因失败，UI 仍呈现成功；当前进程可能使用新缓存，重启或重新从磁盘刷新后又回到旧内容。清空操作也有同样问题：`:81` 删除失败只记日志，随后缓存仍被清空，旧文件留在磁盘。

**上游对照：** 固定上游提交没有 `CustomPromptStore.kt` 和 `SystemPromptSettingsScreen.kt`，本项以本地新增功能的当前实现为准。

**修复方向：** 只有持久化成功才更新已保存状态；失败保留草稿并给出可见错误，清空也要有一致的结果语义。

**后续验收建议：** 分别制造写入失败、删除失败，保存/清空后不能显示成功，原文件应完整；正常保存后重启仍能读到新内容。本轮未执行。

<a id="ui06"></a>
## UI06 — 保存完成清掉保存期间的新输入

**操作序列：** 系统提示词草稿为 A，点保存；在 IO 写入完成之前继续输入，草稿变为 AB；保存完成后编辑框回到 A，新增的 B 消失。

- [SystemPromptSettingsScreen.kt:68](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:68)：保存回调提交此次回调捕获的 `current`，写入在 IO 协程完成。
- [SystemPromptSettingsScreen.kt:84](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:84)：编辑框在保存期间继续接受修改，没有 saving 状态或输入禁用。
- [SystemPromptSettingsScreen.kt:71](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:71)：完成后无条件 `draft = null`，没有判断当前草稿是否还是此次提交的版本。
- [SystemPromptSettingsScreen.kt:59](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemPromptSettingsScreen.kt:59)：草稿清空后展示 `saved`，即刚保存的 A。

**限制：** 需要输入事件落在异步保存窗口内；短文件和空闲设备不一定容易触发。本项是源码确认的时序风险，不是已经完成的设备复现；与 UI05 的写盘失败无关，写入完全成功也可能发生。

**上游对照：** 固定上游没有这条自定义系统提示词编辑链路。

**修复方向：** 完成回调只结束自己提交的草稿版本；保存期间产生的新草稿应继续显示并保持未保存状态，或明确阻止保存期间编辑。

**后续验收建议：** 在受控延迟保存时继续输入，成功后新字符仍可见且标为未保存；再次保存后重启内容完整。本轮未执行。

## 本轮交付状态

已记录 1 项用户反馈和 5 项新增发现。仅新增本报告；产品源码已恢复，未新增单元测试，未继续启动模拟器、构建或设备测试。以后是否修复、如何验收，等待独立的明确修改请求。
