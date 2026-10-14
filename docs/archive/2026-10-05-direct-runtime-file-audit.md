# Minis for Android — Direct Ubuntu、终端输出与文件工具排查

日期：2026-10-05。本轮独立报告，不追加或改写此前报告。

## 范围和结论

本轮沿现役调用链完整阅读 ExecutionCoordinator、Ubuntu runtime、Root launcher、持久 shell、路径映射、安全文件 API、Linux 文件工具及相关既有测试，共 40 个文件、6691 行。没有修改产品代码、测试、合同或旧报告，没有构建、运行测试、启动模拟器、操作设备或使用云资源。下面的输入与结果均为源码推导；验收步骤未执行。

本地基线：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。上游对照为 `/tmp/openminis-review-20261004` 的固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`；使用提交树和 `git show` 对照，不以 sparse checkout 中没有文件推断上游实现不存在。

用户提出的工具归属问题在前一份 [Android 开发工具报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-android-tool-ownership-audit.md) 的 AD01 已确认：项目发现、APK 选择、日志解析与开发流程在 App 中形成策略实现，构建本身仍在 guest。此次还确认 [LinuxFileOpsTools.kt:11](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt:11) 明确采用宿主实现，search、grep、head/tail 在 App 进程内处理 guest 文件；guest CLI 包装这些入口并不改变其实际归属。搜索与文本分析可以交给沙箱工具和 Agent 管理，App 继续承担必要的 Android 权限桥接、session 路径约束和执行边界。这里不把上述架构观察重复计为新缺陷。

现役 shell 启动确实走 Direct Ubuntu，使用真实 App UID/GID 并清空 supplementary groups 与 capabilities。本轮没有发现恢复 PRoot/Alpine 双运行时或 minisd Root broker 的证据。仍有从旧 PRoot 实现继承的文本修补逻辑，其中一项现在会删除正常命令结果。

| 编号 | 优先级 | 新问题 |
|---|---|---|
| RU01 | P2 | Root runner 在完整读取单行之后才限长，读取阶段仍可能大量分配 |
| RU02 | P2 | 旧 `null` 修补规则删除有效命令输出 |
| RU03 | P2 | 带颜色／回车的长行在 4096 列后静默丢失 |
| RU04 | P2 | 遇到未知 ESC 时跨位置匹配，吞掉其后的普通文本 |
| RU05 | P1 | 全局 guest 别名绕过同路径／后代检查，move 可删源，copy 可递归复制自身 |
| RU06 | P2 | 文件并发缩短时，短读尾部被补成零字节并当作成功数据返回 |
| RU07 | P2 | 仅用当前 UTC 偏移构造 TZ，丢失时区的夏令时与历史／未来规则 |
| RU08 | P2 | search 把“结果数限制”用于筛选前的遍历，误报未找到 |
| RU09 | P2 | grep 静默跳过大文件／读取失败／未扫描范围，仍返回成功零结果 |

P1 指存在明确的数据丢失路径；P2 指正确性或资源保护缺口，不代表已经发生线上故障。RU05 的具体别名例子针对无 session 的全局文件 API，不能扩写为所有聊天会话均受影响。

## RU01 — 输出上限保护不到单行读取阶段

[DirectRootRunner.kt:81](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt:81) 和 stderr 分支使用 `bufferedReader().useLines`，先得到完整 String，再调用 [BoundedText.appendLine:269](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/DirectRootRunner.kt:269)。后者才将保留内容限制为 1,048,576 字符。

因此保留的结果确实有界，但一个极长、迟迟不换行的输出仍会在 BufferedReader 中持续积累。即使结果缓冲已满，读取线程仍构造后续完整行，然后丢弃。deadline 与进程组清理不能保证 deadline 内的堆分配有界。这里仅确认读取层缺口，不宣称做过内存耗尽复现。

对比 [RootPersistentShell.kt:217](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootPersistentShell.kt:217)，持久 guest shell 已使用按块读取并限制单行的 BoundedLineReader；无需为了此问题再建立一套输出基础设施。此项与旧 DC13 的 Shizuku 输出收集是两个实现入口，不重复计算为同一个 Root 调用。

**后续验收：** 分别覆盖普通短行、超长无换行、达到保留上限后持续输出与取消，保存峰值内存、工具结果、退出状态和进程清理证据。未执行。

## RU02 — 有效文本 `null` 被当作旧运行时噪音删除

[TerminalSanitizer.kt:38](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/terminal/TerminalSanitizer.kt:38) 删除 trim 后等于 `null` 的整行，并删除任意位置的连续 `nullnull`。这是普通字符，不是 NUL 控制字节。[ExecutionCoordinator.kt:122](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt:122) 对 shell_execute 返回内容无条件运行该清洗。

源码推导：合法 JSON 根值 `null` 加换行会变成空字符串；文件中 `nullnull` 这样的普通内容会被删除，退出码却没有因此变为失败。Agent 无法区分“命令没有输出”和“输出被 App 改写”。

固定上游 `sandbox/TerminalSanitizer.kt:70–77` 有同样规则，并明确称为 PRoot/pipe artifacts；本地将注释改成 shell/pipe，但保留了行为。这是继承的旧文本修补问题，不能据此说 PRoot 执行器仍在运行。

**方向与后续验收：** 去控制字符与去普通文本应保持清晰边界。用 JSON `null`、含 `nullnull` 的源码片段及真正 NUL 字节核对原始输出和返回输出，保存逐字节对照。未执行。

## RU03 — ANSI 长行静默截断，而普通长行不截断

[TerminalSanitizer.kt:91](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/terminal/TerminalSanitizer.kt:91) 对无 CR／ESC 的行走原样返回；含任意 CR／ESC 的行才进入 4096 列缓冲。[TerminalSanitizer.kt:114](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/terminal/TerminalSanitizer.kt:114) 超过列上限后继续推进位置，却不再保存字符，也不添加省略标记。

静态例子：普通 5000 个 A 保留 5000 个；同样内容加一个红色 ANSI 前缀和复位后，只保留 4096 个。最终 50,000 字符截断函数看到的是已经变短的字符串，无法提示这 904 个字符被删除。彩色编译诊断、长 JSON 或工具输出会因是否带颜色出现内容差异。

固定上游对应 CR 折叠使用 StringBuilder，没有这项 4096 列静默截断；上游另有预清洗总量限制。这里应保留资源边界并明确报告省略，不能直接以取消上限作为修复结论。

**后续验收：** 对比同内容的普通行、彩色行、含 CR 重绘行，包含 4095／4096／4097 列边界，记录保留内容与截断信息。未执行。

## RU04 — 未知转义后面的普通文字被一起吞掉

[TerminalSanitizer.kt:100](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/terminal/TerminalSanitizer.kt:100) 在当前 ESC 位置调用 `ANSI_REGEX.find(line, i)`。find 可以匹配后面的另一个 ESC；代码没有检查匹配起点是否等于 i，就将游标移到匹配末尾。

用 `<ESC>` 表示单个 ESC 字符：输入 `<ESC>!KEEP<ESC>[31mRED`，第一个 ESC 后的 `!` 不属于正则支持的转义，但 find 找到后面的颜色序列；`!KEEP` 被跳过，最终只剩 RED。去掉未知 ESC 后应仍保留普通文字 `!KEEP`。

固定上游在当前位置设置 matcher.region 并使用 lookingAt；未知 ESC 仅前进一字符，不跳到后面的合法序列。这是本地当前折叠实现的差异。

**后续验收：** 未知／不完整 ESC 后接普通文本和合法 ANSI 的组合，核对仅控制部分被删除；记录原始字节与清洗结果。未执行。

## RU05 — 不同 root 分解指向同一物理文件时，路径保护失效

[UbuntuPaths.kt:67](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:67) 的全局别名将 `/workspace` 映射到 hostWorkspace，将 `/var/minis/attachments` 映射到 hostWorkspace/attachments。无 session 的解析保留了不同的可信 root 分解，而 [SecureFileAccess.kt:581](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:581) 的 samePath／isDescendant 必须 root.path 相等才成立。

**同一文件 move：** `WorkspaceFileClient.move(null, "/workspace/attachments/a", "/var/minis/attachments/a")` 的源和目标实际相同，却被识别为不同路径。[SecureFileAccess.kt:221](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:221) 先删除目标，也就是源文件本身；随后的 move 找不到源，fallback copy 也找不到源。请求失败，文件已经被删除。触发不需要符号链接、目录竞态或模拟磁盘故障。

**后代目录 copy：** `copy(null, "/workspace", "/var/minis/attachments/copy")` 的目标实际上在源目录内，但不同 root 使后代检查放行。[copyDirectory:295](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:295) 先创建目标，再列举源子项；新建的目标也进入源树，递归复制又产生更深的目录。实现没有针对这种情况的树级停止条件，可能一直增长到文件系统／资源错误。

**影响边界：** [WorkspaceFileClient.kt:135](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:135) 确实允许 nullable session 并直接进入该实现。带合法 session 的两个 attachments 别名会统一到 sessions root 与相同 components，上述例子不会因此误判。本轮确认底层全局 API 缺陷，尚未证明普通文件页面会自然构造这一对别名；不宣称所有 UI move 都会丢文件，也不宣称跨 App UID 越界。

这与旧 BF05 的覆盖非原子问题不同：本项根因是同物理路径／祖先关系识别失效，正常输入即可将自己的源当作待替换目标。固定上游树中没有同名 SecureFileAccess，不能直接按上游文件实现机械替换。

**后续验收：** 在一次性数据域里覆盖同文件不同别名、同 root 同路径、后代 copy、带 session 的别名，以及有授权的重叠挂载；拒绝危险关系且源文件摘要保持不变。保留目录树、请求和结果。未执行。

## RU06 — 并发短读被当作含零字节的完整数据

[SecureFileAccess.kt:53](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:53) 先按 attributes.size 决定数组大小，随后才打开和读取文件。[readFully:547](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:547) 遇到 EOF 直接退出，不返回实际读取数、不缩短数组，也不报短读。readAll 同样返回完整预分配数组。

若 guest 在 stat 后将文件从 5 字节缩短为 2 字节，实际读取只有两字节，余下三字节保留 ByteArray 的初始零值；readChunk 还依据旧 count 和旧 size 计算结果。调用方会把这些零字节视为文件真实内容。安全目录句柄能防止路径链接逃逸，不能让可并发写入的文件内容自动成为快照。

此项是条件明确的数据读取缺陷，尚未进行并发复现；不同于旧 BF08 的文本行偏移截断问题。

**后续验收：** guest 写入与 App 分块读取并发发生，分别缩短、替换、增长文件；返回实际读取长度／明确的不一致错误，不生成未读取的字节。保存源摘要、各块字节数与组装摘要。未执行。

## RU07 — 旧 musl 兼容思路只保留当下时差

[RuntimePathRegistry.kt:123](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/RuntimePathRegistry.kt:123) 取 Android 当前时间的 offset，构造 `LCL±小时[:分钟]`，不传地理时区身份，也没有 DST 规则。[UbuntuKernel.kt:614](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:614) 将其作为 guest TZ；时区变更广播传播的仍是同一形式。

GNU libc 文档区分携带历史／未来规则的地理时区与固定偏移格式；后者仅在显式指定时才附带夏令时规则。本实现没有附加规则，因此启动时的当前墙钟可以正确，但换算同一地区另一季节的日期会使用错误偏移；长期 shell 跨 DST 转换也可能保留旧偏移。[GNU libc TZ 文档](https://sourceware.org/glibc/manual/2.42/html_node/TZ-Variable.html)

例如纽约在夏季启动时获得 `LCL+4`，冬季日期也按 UTC−4 换算，而该地区冬季规则应使用 UTC−5。固定 UTC+8 地区的当前时间不因此出错。这里只针对 guest 时区规则，不能推断 Android 原生的所有例程调度均错误。

固定上游 PRootKernel.kt:574 使用同一公式，注释说明为了避开 musl 对时区缩写的混淆；这是被移入 Ubuntu runtime 的继承逻辑。

**后续验收：** 使用带 DST 的地区与固定偏移地区，对照 Android 和 Ubuntu 对冬／夏日期的换算及长期 shell 的跨界行为。核对实际 tzdata 能力后再确定最小调整。未执行。

## RU08 — search 在匹配之前耗尽“结果”预算

公开工具把 limit 定义为最大结果数，默认 100、最大 200；见 [LinuxFileOpsTools.kt:413](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt:413)。实际 [search:195](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt:195) 把 limit 传给 entriesUnder，先收集这么多个任意目录项，再按文件名和扩展名过滤。目录本身也占该预算。

确定性静态例子：同一目录中按排序先有 100 个不匹配文件，随后有 z-target.md；默认搜索 target 返回 count=0、success=true，尽管目标在目录首个 500 项页面内。递归搜索也可能在顶层不匹配项上用完额度，没有进入目标子目录。提高到 200 只是改变漏扫位置。

[返回对象:215](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt:215) 没有 scanned、truncated 或 continuation，Agent 不知道未找到意味着未扫描。此项不是旧 BF07 的下载重名检测首屏缺陷：这里在单页内也会出错，根因是筛选前使用结果额度。

**方向与后续验收：** 按用户工具归属目标，搜索策略可以交给 guest；保留有界执行，并明确区分扫描预算与匹配结果预算。以目标位于第 101 项、递归子目录和没有目标三种目录树核对返回值和完整性提示。未执行。

## RU09 — grep 把无法完成的扫描报告为成功无匹配

[LinuxFileOpsTools.kt:264](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt:264) 对超过 4 MiB 的文件直接 continue；读取发生 Throwable 也直接 continue。指定一个确实存在且含目标文本的 5 MiB 文件，整个文件不被搜索，却在 [返回路径:288](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/LinuxFileOpsTools.kt:288) 得到 success=true、count=0，没有 skipped 或错误说明。

目录模式还限制最多 2000 个遍历项；entriesUnder 对每个目录仅读取 offset=0 的 500 项，忽略已有 next_offset。超过扫描范围、达到命中上限、读失败或因大小跳过的情况，都没有体现在结果完整性上。这里不把通用首屏问题另算一项新发现，主要缺陷是扫描被省略后仍返回没有匹配的成功结果。

限制文件大小和输出量本身合理，问题在于把“没查”与“查完没找到”合并。开发 Agent 可能据此遗漏日志错误、代码位置或配置内容。

**后续验收：** 单个超限文本、不可读文件、目录第 501 项、总项数超限、命中上限分别保留明确的不完整范围与失败／跳过原因；保存原始数据树与工具 JSON。未执行。

## 既有问题、参考边界与阅读进度

本轮重新核对但未重复计数的内容包括旧 F14 的迁移覆盖新用户文件、F15 的带链接迁移失败重试，以及 BF05/BF07 的覆盖和分页问题。没有将重复 helper、注释噪音、Lint 基线另列为新增问题。

已阅读的既有安全文件测试覆盖链接拒绝、基本读写及缺失目录错误；没有提供 RU05 的全局别名关系或 RU06 的内容并发缩短证据。既有终端测试也不能以普通 ANSI 清洗和整体截断用例替代 RU03/RU04 的特定输入。这里只评价阅读到的用例，不声称整个仓库不存在其它间接测试，更没有执行测试。

上游对照显示 RU02/RU07 是继承行为，RU03/RU04 与上游当前实现有具体差异。Root runner、SecureFileAccess 和 LinuxFileOpsTools 的本地路径没有在固定上游提交树中找到同名实现；这只是本轮实现对照范围，不等于上游没有文件或执行能力。所有移植建议仍须服从本项目 Direct Ubuntu 与权限合同。

累计可核验全文阅读为 **93／1546 个纳入范围的文件，18331 行**；另有 4 个文件仅片段核对，1449 个尚未登记全文。一般文档、许可证、二进制和批量字典另有 124 个，不计入该分母。全库阅读目标尚未完成。

对应 SHA-256、完整行范围与批次登记见 [代码阅读台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。本报告是审计快照，不修改现役合同或将任何问题标成已修复。所有真实 Root／SELinux／Android NIO／设备生命周期行为仍需之后单独授权的端到端验收。
