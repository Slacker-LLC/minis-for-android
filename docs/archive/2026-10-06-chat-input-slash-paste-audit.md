# 聊天输入、Slash 菜单与长文本粘贴静态审查

日期：2026-10-06。独立新报告，不追加或修改既有报告。本轮新增 4 项问题：3 项 P2、1 项 P3。

本地目标 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 对照固定为 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，读取既有只读 checkout，未拉取或运行上游。本文依据真实调用链和分支行为记录问题；没有修改产品代码，没有执行测试、构建、模拟器、adb、Root、设备或云端操作。

## CI01 · P2 · MCP 快捷条目生成了，但点击分发遗漏 MCP 类型

[菜单生成](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSlashExt.kt:155)遍历当前 session 已启用的服务器，生成 `id = "mcp:<server>"`、`isMcp = true` 的条目。真正的[执行入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1734)只把 `isSkill` 当作填入输入框的条目；随后内置命令分支不认识 `mcp:*`，走到 `unrecognized`，返回旧草稿或空字符串。

[点击入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:4369)和[键盘 Tab 入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:5288)都将该返回值写回输入框。因此在输入框里输入 `/` 后选择 MCP 服务器，菜单关闭但服务器命令没有填入；通过已有正文打开菜单时，正文会恢复，却没有期望的服务器前缀。问题位于 UI 分发，不能据此断言 MCP 工具本身无法执行。

OpenMinis 在相同函数中已经采用 [`cmd.isSkill || cmd.isMcp`](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L3228)。这是可以确认的本地遗漏上游修复。

修复方向记录：让 MCP 行进入已有填充路径，同时核对点击后的焦点处理仍仅判断 `isSkill` 的入口。这里只记录，不实施修改。

## CI02 · P2 · Slash 菜单打开后修改正文，会被打开前的草稿覆盖

[打开菜单](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSlashExt.kt:248)保存一次 `savedInputBeforeSlash`，将编辑器改成 `/ 原稿`。[编辑回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:5250)允许用户继续修改这段正文，但[菜单状态更新](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSlashExt.kt:185)只更新过滤词，不更新保存的正文。

之后[取消菜单](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSlashExt.kt:269)无条件恢复保存的旧文本；[选择 Skill](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1752)将前缀接到旧文本；执行内置动作也在[返回路径](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1788)恢复旧文本。`currentInput` 虽然被传入执行入口，却没有参与正文恢复。

静态复现步骤：输入 `原稿` → 按 Slash 按钮，得到 `/ 原稿` → 将正文改成 `/ 新稿` → 按[附件按钮关闭菜单](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:5457)，输入框回到 `原稿`。选择 Skill 时同样会得到 `/skill 原稿`，而不是编辑后的正文。这里丢失的是菜单打开期间新做的编辑。

删除开头 Slash 时已有清除保存状态的分支；正常发送也有 `endSlashSessionForSend()` 清理，不能把这两种路径误报为同一缺口。已有 `SlashSendClearsInputTest` 使用简化状态模型，针对发送后的清理，不能证明正文编辑后恢复正确。

OpenMinis 的[Skill 恢复分支](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L3241)也仍拼接保存的旧正文，本轮没有发现可直接对照的上游解决方案。

修复方向记录：菜单关闭或执行时根据当前正文恢复，明确区分菜单过滤词与可编辑正文，避免用打开时快照覆盖后续编辑。

## CI03 · P3 · 长文本替换更长选区时，净长度门槛跳过折叠和文件化

[长文本识别入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PastedTextBuffer.kt:330)先计算 `new.length - old.length`，当差值小于或等于零时直接返回，尚未识别实际插入的文本。

例子：全选 2000 个 `旧` 字，粘贴 1201 个 `中` 字。插入文本满足[非英文长文本门槛](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PastedTextBuffer.kt:288)，但净长度减少 799，直接返回原始新值，`stash` 完全不会被调用。同理，以超过 15000 字的文本替换更长的选区，会绕过[文件化回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:5237)。

正文仍保留，问题是同一份长文本根据选区长度不同，折叠与文件化行为不一致。已有粘贴测试覆盖的长文本选区替换，其替换后净长度为正，不覆盖这个分支。

OpenMinis 同样保留[非正净长度直接返回](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/PastedTextBuffer.kt#L328)，属于共享缺口。

修复方向记录：依据共同前后缀计算实际插入段，再区分纯删除和长文本替换，不能只依据净增长判断。

## CI04 · P2 · 超大粘贴在输入回调内同步写文件

[输入回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:5233)对已识别为长文本、且超过[15000 字符](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PastedTextBuffer.kt:273)的内容直接调用 `stashPastedTextAsFile()`；该方法同步[创建目录并写入全文](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:828)，没有切换 IO dispatcher 或异步任务后再更新附件状态。

因此用户正在输入的 UI 回调会承担整段编码及磁盘写入。此入口没有额外的粘贴长度上限；大段内容或慢存储会延长一次输入处理，形成卡顿风险。Android 官方说明，主线程上的长任务会影响绘制和输入响应，应移到工作线程。[Android 主线程说明](https://developer.android.com/topic/performance/threads)

发送准备阶段的 [PastedTextProcessor](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PastedTextProcessor.kt:1)已使用 IO dispatcher，并处理取消时清理及持久化后的文件保留；它处理的是后续阶段，不能覆盖这里在粘贴时发生的同步写入。本轮仅确认阻塞调用链，没有运行设备测量，不能宣称已复现 ANR 或给出具体耗时。

OpenMinis 的[对应写入](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L1158)同样是同步调用，属于共享缺口。

修复方向记录：文件生成放到已有异步执行模式里，附件状态回到 UI 线程提交，并保留失败回退和取消清理。

## 本轮已核实的保护与未立项线索

- 粘贴占位符展开为单遍处理，未知或数值溢出的标记保留原文；消费集合避免重复移除同一条记录。
- 发送前粘贴文件处理已有取消清理；提交后的回调失败不会删除已被消息引用的文件。这两点不列为缺失。
- Mention 菜单有 Slash 优先级与邮箱样式过滤；占位提示轮播处理了无障碍监听的注销。本轮没有足够证据将路径中的空格或提示词池调整列成新问题。
- 本轮不重复注释噪音、基础设施复制和 Lint 基线问题，也不重复上一份 RPC 附件投影与临时文件问题。

## 全文覆盖与版本变化

本轮新增完整阅读 14 份文件、2554 行；另重新完整阅读 2 份定时任务相关文件、584 行，不重复计入全文文件数。

| 文件 | 全文行数 |
| --- | ---: |
| [ChatModels.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatModels.kt:1) | 286 |
| [ChatViewModelMentionExt.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelMentionExt.kt:1) | 223 |
| [ChatViewModelSlashExt.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelSlashExt.kt:1) | 302 |
| [ComposerInputModePrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ComposerInputModePrefs.kt:1) | 35 |
| [ComposerPlaceholderRotation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ComposerPlaceholderRotation.kt:1) | 74 |
| [ComposerPlaceholderUi.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ComposerPlaceholderUi.kt:1) | 93 |
| [InputAttachment.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/InputAttachment.kt:1) | 16 |
| [PastedTextBuffer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PastedTextBuffer.kt:1) | 517 |
| [PastedTextProcessor.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PastedTextProcessor.kt:1) | 167 |
| [AppendToInputTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/AppendToInputTest.kt:1) | 117 |
| [ChatViewModelPastedTextConsumeTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/ChatViewModelPastedTextConsumeTest.kt:1) | 95 |
| [ComposerPlaceholderRotationTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/ComposerPlaceholderRotationTest.kt:1) | 191 |
| [PastePlaceholderTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/PastePlaceholderTest.kt:1) | 284 |
| [SlashSendClearsInputTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/SlashSendClearsInputTest.kt:1) | 154 |

`ChatViewModel.kt` 和 `ChatScreen.kt` 仍为部分阅读；新阅读片段已记录到[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)，搜索命中不计全文。当前全文覆盖为 **787 / 1546 份纳入文件**，部分阅读 13 份，尚未登记 746 份；当前版本全文累计 **165941 行**。

上轮观察到的外部修改涉及 `ScheduledReadOnlyPolicy.kt`（419 行）及其测试源文件（193 行）。本轮核查时两者已恢复 HEAD 内容（401 行、183 行）；已重新逐行阅读并记录内容修订历史。全文行数因此按当前版本扣回 28 行，净增加 2526 行。该变化不由本轮审查实施；旧报告保留当时快照，不改写历史。

复核范围：1670 个受 Git 跟踪路径与当前 SHA256、固定 HEAD、tracked/staged diff、51 份既有报告的 SHA256、本文本地链接及行号、上游固定提交中的引用行。最终复核时 tracked/staged diff 均为空。本轮写入仅本报告与覆盖台账；未做产品修改或运行验证。代码全量通读仍未完成。
