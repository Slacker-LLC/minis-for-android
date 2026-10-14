# 文件修改工具远端修复闭环审计

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文7文件、732行。仅静态阅读源码和现有测试；没有修改产品代码、编写/运行测试、制造测试文件、构建、模拟器、adb、Root、设备或云任务。此前143份报告与所有旧台账保留，新建本文及新版覆盖记录。

## 旧六项的当前状态

旧编号来自[文件编辑、并发写入与输出保留静态审计](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-file-mutation-output-audit.md)。以下“修复”是所述缺口在固定源码中的闭合，不是设备端已验收；不覆盖旧报告或改其历史结论。

| 原编号 | 当前静态状态 | 核对证据 |
|---|---|---|
| FO01 重叠出现误判唯一 | 原案例已修 | FileEditEngine.allIndices每次推进idx+1，包含自重叠候选；测试新增ababa/aba与aaa/aa拒绝 |
| FO02 同一文件别名不同锁 | 原公开别名案例已修 | 两工具均调用keyFor，锁键为解析后root与components，消除了原始path及不必要session区别 |
| FO03 非法UTF-8重写损坏 | 原非法字节案例已修 | FileEditTool使用REPORT解码，解码异常在调用writeBytes前返回失败 |
| FO04 null编辑项被丢弃 | 原null/非object案例已修 | parseEdits逐项检查optJSONObject，失败抛出并拒绝整个请求 |
| FO05 create_dirs=false无效 | 仍存在 | 仍在schema声明，但execute未读取；后端依然ensureParent |
| FO06 异步锁永久累积 | 原无回收缺口已修 | SuspendEntry.users统计持有/等待者，finally归零才移除 |

## 修复调用链与范围

[FileEditEngine.kt:106](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/internal/FileEditEngine.kt#L106) exact候选枚举从idx+1继续，两个候选直接在findUniqueMatch拒绝；不同edit区域的重叠检查仍保留。测试不再只有分开的x x x，新增自重叠反例和唯一成功例。

[FileMutationQueue.kt:50](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt#L50) keyFor调用UbuntuPaths.resolveSecureForFileAccess。[UbuntuPaths.kt:194](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt#L194) 同一session的/workspace、/var/minis/workspace以及相对路径都归相同hostSessions根与sessionId/workspace/components；global绑定也按root/components生成键。FileEditTool第75行、FileWriteTool第74行都在此键上锁住读/检查/写。[FileMutationQueueTest.kt:44](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/tools/internal/FileMutationQueueTest.kt#L44) aliases测试验证前三种键一致，另有真实withKey并发序列断言。这个锁仅覆盖本进程使用队列的调用，不能据此宣称shell、其它进程、硬链接或任意外部写入都被隔离。

[FileEditTool.kt:90](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt#L90) strict UTF-8 decoder在读取整文件后设置REPORT，CharacterCodingException返回明确失败，旧ff字节被替换字符重编码的路径不再提交。合规UTF-8的行尾规范化仍是算法的一部分，混合CRLF/LF与旧CR-only文件是否需要逐字节保留属于另一个约束问题，不因这次编码拒绝就宣称所有原始字节都完全保留。

[FileEditTool.kt:167](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt#L167) parseEdits的null/非object项现在抛异常，不再continue，外层失败返回且写入尚未发生。合法空new_text删除仍受支持。新FileEditToolRequestTest直接调用生产parser，覆盖两种字段命名和null前后位置/非object；它不经过Provider、preflight、Registry或真实磁盘，不能替代整个请求路径的端到端验收。缺失字段、显式null字段的处理仍需要结合入口schema；本轮没有把旧FO04静默丢弃项扩张为所有输入形式都已拒绝。

[FileMutationQueue.kt:55](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt#L55) withKey在guard内增加users，持锁或等待时均保留条目，finally同一guard内减少，零时才remove，避免无条件解锁删除导致等待者与新调用使用两把锁。测试增加空闲条目回收和六个withKey调用串行；没有取消排队、异常抛出和设备长时间使用的实测工件，故不声称全生命周期运行验证完成。

## FO05仍在：公开参数没有影响执行

[FileWriteTool.kt:23](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt#L23) create_dirs仍承诺默认false、true时建父目录；execute只读path/content/append/expected_sha256/tool_title。[WorkspaceFileClient.kt:76](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt#L76) writeBytes/appendBytes分发SecureFileAccess；[SecureFileAccess.kt:120](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt#L120) 覆盖经writeAtomic，第346行进入withParentAfterEnsure；append第130行直接withParentAfterEnsure，第385行无条件ensureParent。第366行dropLast叶子后ensureDirectories，第375行调用SecureFileOps.ensureDirectories。

因此传false仍会创建缺失父目录，与旧FO05同一契约差异，不新增编号、不声称权限越界。记录方向仍是实现开关或按真实设计移除无效开关并改描述；本轮不修改代码。

## 其它全文核对与上游对照

多编辑基于同一原始快照匹配、反向替换，保留BOM及所检测的CRLF/LF风格，模糊匹配新增尾部换行差最多一与effectiveEnd保留后续空行。已有EngineTest仅有smart quotes/trailing whitespace的模糊样例，没有新尾部换行规则的多案例；未运行它，未凭测试名字当作该新规则的完整验收。

legacy replace_all仍单独做精确批量替换；FileWrite expected_sha256在锁内读取并核对后写入。工具diff最多返回12000 UTF-16单元并避开拆分surrogate pair，但完整diff先在内存构造，沿用旧预算线索，不重复计实际OOM。execute广义catch也会收到取消异常，此轮仅记录候选，未闭合为取消后继续写入或队列损坏。

固定OpenMinis HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的对照沿用旧报告已读取的固定对象证据：上游旧单编辑也漏自重叠、readText/writeText未严格拒绝非法字节，无本地edits数组与这套队列，create_dirs文案同样与实际建目录不一致。本轮不重新fetch上游，也不把本地新增修复说成上游已有。

## 覆盖与交付

| 新增全文文件 | 行数 |
|---|---:|
| [FileEditTool.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt#L1) | 192 |
| [FileWriteTool.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt#L1) | 118 |
| [FileEditEngine.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/internal/FileEditEngine.kt#L1) | 165 |
| [FileMutationQueue.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/internal/FileMutationQueue.kt#L1) | 70 |
| [FileEditToolRequestTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/tools/FileEditToolRequestTest.kt#L1) | 37 |
| [FileEditEngineTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/tools/internal/FileEditEngineTest.kt#L1) | 72 |
| [FileMutationQueueTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/tools/internal/FileMutationQueueTest.kt#L1) | 78 |

首轮合并输出截断，随后分别重新完整读取FileEditTool、FileWriteTool和三测试；Engine与Queue也再次完整输出，全文依据来自后续无截断读取。路径解析、客户端、文件后端及registry只看片段，不升级全文。

新的累计台账：[2026-10-07-code-review-coverage-37158d18-file-mutation.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-file-mutation.json)。全文 **1164 / 1652文件、188042行**；partial4、not_registered484、excluded124，tracked1776，全库阅读仍在继续。

交付核对旧143份报告和所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776个远端快照Git blob/hash/bytes/lines以及本文证据行号。只新增两份文件。
