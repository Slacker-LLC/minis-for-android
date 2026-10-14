# 安全文件访问全文复核：路径身份、短读与覆盖风险

日期：2026-10-07。固定审阅快照HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。交付时origin/main已由其它进程更新至`9e523652b7ea80d7cc5f50e346f7038059896c0f`（子代理选模型提交）；本轮四文件Git blob均未改变。当前计数仍对应原快照，新HEAD范围与变化文件将在下一批显式切换并重核，不把旧覆盖冒充新HEAD完整覆盖。全文静态读取4文件、813行。旧186份报告及全部覆盖台账保持原样。本轮沿用RU05/RU06及BF05/BF09核实状态，无新增独立问题编号。未修改代码、编写或运行测试、创建测试目录/链接、读写真实用户文件、构建、操作模拟器/adb/Root/设备/云资源。

## RU05全局别名的同文件及后代关系已修

[SecureFileAccess.kt:586](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt#L586) samePath/isDescendant现在把normalized root路径各段和components拼成物理位置身份列表。不同root分解但同位置的/workspace/attachments/a与/var/minis/attachments/a可比较相等，copy/move的早期检查不再误把自己的源删作目标；目录内别名目标也可识别为descendant并拒绝。

这里使用路径段前缀，不是简单字符串startsWith，不会把workspace-other误作workspace的子目录。root中的..会normalize；它并非inode相等或realpath/hardlink身份比较，当前没有据此保证所有重叠挂载/同inode别名均识别。原RU05两个确定别名反例的根因已经处理。

SecureFilePathIdentityTest49行5例覆盖两种root分解、不同文件、跨root后代、同路径不是自身后代/相似前缀兄弟以及root中的..。设备测试也有相同文件move、目录别名后代copy的实际WorkspaceFileClient入口源码。

## RU06短读零尾部已修，但读取并非内容快照

[SecureFileAccess.kt:62](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt#L62) readChunk检查实际read<count，返回copyOf(read)，size改为offset+read并标eof；readAll同样按真实读取数裁剪。[SecureFileAccess.kt:556](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt#L556) readFully返回已读字节数，不再仅退出后留下预分配零尾部。

ReadFullyTest54行4例覆盖缓冲小于文件、缓冲大于文件、空文件、偏移超过结尾。名字“file shrank after stat”实际是预先只写2字节、分配5字节buffer再调用helper，没有在stat/read之间做真实并发缩短。这仍能验证短读计数，不能当作生产并发快照验证。

stat与open仍分开，增长/替换文件时并未建立版本一致读取；readAll按初始size分配，增长可能只取初始长度。保留这些内容一致性候选，不重复登记旧补零问题，也不将目录句柄保护解释为文件内容不可变。

## BF05仍存在：覆盖可递归删目录，也有先删后移窗口

[SecureFileAccess.kt:346](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt#L346) writeAtomic仅拒绝leaf为空的可信root，未检查非空leaf是不是目录，随后deleteEntryIfPresent再move临时文件。[SecureFileAccess.kt:533](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt#L533)删除helper遇到目录会递归删除子项再删目录；它不只用于显式delete，也用于writeStream/copyFile替换。

因此普通覆盖目标若为/workspace/project目录，底层依然能删除项目树并改成普通文件；appendBytes有普通文件/链接检查，不属于这个覆盖反例。上层可选revision或权限检查不能自动替代目标类型判断。

即使目标为普通文件，delete成功与move成功之间存在目标缺失窗口；move失败时finally删除临时文件，不还原旧目标。readToFile也先SafeFileTree删除目的地再rename。先写临时文件和NOFOLLOW不等于原子替换，名称writeAtomic不能作为已修复证据。沿用BF05，不重新计数。

待后续授权的端到端验收应通过真实公开file_write/文件API覆盖一次性目录、普通文件、取消/提交失败和允许的新建，保存目录树/摘要/错误码；确认普通写入拒绝目录且失败保留旧数据。当前未制造任何故障或运行命令。

## BF09仍未看到旧文件元数据继承

writeAtomic/writeStream/copyFile创建新的临时inode后替换，未读取/恢复已有目标权限；append不换inode。原BF09的“编辑内容时旧执行位等元数据没有保留”仍有实现证据。本轮不重复引用平台创建mode，也不声称所有OEM最终mode固定为某值；真实权限结果尚未设备验证。

## 路径及文件类型保护的实际范围

withParent逐级从SecureDirectoryStream打开子目录且NOFOLLOW，最终读操作也NOFOLLOW；read/copy/move拒绝源链接，delete链接只删链接本身。可信root空components拒绝delete，源到后代的copy/move拒绝。设备NIO若不支持SecureDirectoryStream，openRoot关闭普通stream并返回IO_ERROR，不降级到无约束File操作。

openRoot先stat root再Files.newDirectoryStream，属于可信root的全局入口，不是“任何位置都完全没有全局路径访问”；root自身的替换竞争还需结合UbuntuPaths约束和同UID威胁模型。ensureDirectories交给SecureFileOps native helper，不能仅读本文件宣布全部native路径正确。未据这些候选添加越权结论。

list先收集/排序全部目录名再分页；输出limit不限制排序前的扫描/内存。目录递归copy没有整棵树预算，copyFile单文件使用MAX_FILE_BYTES和实际流字节检查。各上层用途有不同预算，未凭底层实现就声称已发生OOM或无限递归。

attributesOrNull将异常变为null，infoOrNull亦然，可能把不可读和不存在合并；其调用意图需逐业务判断。写通道短写检查、流式maxBytes以及finally关闭目录句柄是真实保护，但都不能修复BF05的业务覆盖语义。

## 设备测试覆盖与证据限制

SecureFileAccessInstrumentedTest100行4例调用生产WorkspaceFileClient：基本写读、同文件alias move、copy后代拒绝、链接逃逸拒绝且外部marker保留。测试创建App-owned布局并在tearDown恢复真实UbuntuPaths；本轮没有运行它或创建其目录。

alias move例把异常runCatching丢弃，后续只核验源内容仍在，不能据此证明move返回成功。后代copy只检查有异常和没有递归子目录，不限定业务错误码。链接例创建失败可assume跳过，并接受部分平台文件异常；实际运行结果与跳过数均未知。

测试没有普通file_write覆盖现有目录、先删后移失败、执行位继承、trusted root替换或真实并发短读。因此不能凭这些测试源码将BF05/BF09结案，也不能拿Debug/JVM阅读代替Android/OEM/Release证据。未新增单元测试。

## OpenMinis对照及覆盖

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1查既有Git树无SecureFileAccess同名文件，未fetch。旧报告已有上游FileWriteTool对照，本轮不重新借旧普通File写法作为解决方案：本项目必须保留App-owned/session/NOFOLLOW约束，不能回退旧PRoot/通用宿主路径API。

| 本轮新增全文 | 行数 |
|---|---:|
| [SecureFileAccessInstrumentedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/androidTest/java/com/openminis/app/runtime/files/SecureFileAccessInstrumentedTest.kt#L1) | 100 |
| [SecureFileAccess.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt#L1) | 610 |
| [ReadFullyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/runtime/files/ReadFullyTest.kt#L1) | 54 |
| [SecureFilePathIdentityTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/runtime/files/SecureFilePathIdentityTest.kt#L1) | 49 |

SecureFileAccess按1—305、306—610完整读取，三测试合并输出首尾完整无截断。后续检索/旧报告片段有组合截断，只用于定位，当前核心代码已完整读取，不把它计作额外全文。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-secure-file.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-secure-file.json)。累计全文 **1353 / 1655文件、226813行**；partial6、not_registered296、excluded124，tracked1779。全库通读仍未完成。

交付校验旧186份报告及全部旧台账hash、固定本地/上游HEAD、原快照commit及交付时观测origin/main、tracked/staged空代码diff、1779快照Git blob/hash/bytes/lines与引用行号。仅新增本报告及台账。
