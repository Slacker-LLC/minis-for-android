# 共享目录只读声明与记忆文件管理入口静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增 **1项P2确认缺陷**；全文读取4文件、879行。仅静态审查，未修改产品源码，未构建/测试、启动模拟器、访问设备/Root/云或删除真实文件。旧报告不改。

## SR01 / P2：Skills与Memory显示只读，却能通过同一浏览入口删除文件

[SharedFoldersScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SharedFoldersScreen.kt:197) registry把skills、memory的writable置为false。列表第143行、详情 [SharedFolderDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SharedFolderDetailScreen.kt:154) 据此显示只读徽标。该文件第47—50行还明确说明这两个目录在原生浏览UI保持只读，以避免手动破坏工具维护状态。

实际浏览入口 [AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:776) 创建FileBrowserViewModel，只传cache根、label、appContext和guestRootPath=/var/minis/folderId，没有把folder.writable或readOnly传下去。FileBrowserScreen第196行所有条目均连接删除回调，第443—454行所有普通文件都显示删除菜单；确认框第215行调用deleteItem。

[FileBrowserViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt:364) 对guestPath直接调用WorkspaceFileClient.delete。[WorkspaceFileClient.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:169) 先requireWritablePath，但第250—253行只拒绝只读external mount，不拒绝全局memory/skills。UbuntuPaths第73—76行将这些guest前缀映射到App自有hostMemory/hostSkills，第103—104行backing在filesDir/minis-global下；SecureFileAccess第240—249行保护trusted root自身、采用安全parent删除，不按该页面的只读策略阻止子文件。

可达静态序列：设置→共享目录→Memory（标只读）→浏览文件→GLOBAL.md菜单→删除并确认。进入同一现役guest文件删除链，不经过MemoryRepository.deleteFile第345行“GLOBAL.md不能删除”的保护。已存在且可写的普通GLOBAL.md能够被删除；Skills的SKILL.md等普通文件亦可走该入口。没有执行删除，不声称任意目录、symlink或Root-owned rootfs都可删；只确认这个页面承诺的只读与实际文件操作不一致。用户仍需显式确认删除，问题不是无交互自动删除，也不是App UID越权。

MemoryManagementScreen第130行对isGlobal不给删除回调，说明其正规记忆管理入口保留保护；共享目录浏览器是另一入口，故只补MemoryRepository的UI防护无法覆盖此链。文件名级保护与共享目录整体只读应在正确调用边界区分，不能将所有Agent对memory/skills的写入全局禁掉——这些写入是现役功能。

方向：将只读浏览策略明确传入浏览/操作边界，在该入口禁止删除，或者产品明确允许编辑时同步实际徽标与保护规则。复用现有浏览器与guest安全API，无需恢复PRoot或另建文件服务。本轮没有实施。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游SharedFoldersScreen第196—208行同样设skills/memory writable=false；AppNavigation第813—831行创建浏览ViewModel也未传只读参数。上游FileBrowserViewModel第368—379行用host File.delete/deleteRecursively，运行链与本地guest API不同，不能仅凭它无只读参数就声称Root-owned host删除一定成功。确认共同的徽标/入口策略遗漏，实际可删除证据取本地现役App-owned guest链。

此前DocumentsProvider审查确认memory/skills在系统文件访问入口有只读检查，该provider拒绝不能保护此原生浏览入口；本轮没有重新计入DocumentsProvider已有缺陷。

## 记忆编辑及通用输入组件全文复核

MemoryManagementScreen同时含MemoryFileEditScreen：列表在LaunchedEffect(Unit)读文件，删除后重新列举，GLOBAL.md不显示删除按钮；没有根据一次性读取推断返回页面一定不刷新，需结合Navigation composition生命周期。新会话全局memory默认开关明确不回写既有session，这一设计不算异常。

编辑器初始content为空、异步readFile、未阻止加载前Save；readFile把读取失败映射为空，保存整文件并发写入风险仍在。上述问题已由UI03/UI04记录，缺IME避让及返回丢草稿已由UI01/UI02记录，本轮不新增同根因缺陷。Save按钮始终存在、显示toast/错误，不能泛化为所有保存都毫无反馈；错误message为null及取消处理仍是候选。

DialogTextField全文确认enabled/readOnly、multiline/maxLines及keyboardActions均传给BasicTextField；该helper没有替每个全屏页面处理IME inset，不能因字段自身可滚动就认为页面避让已解决。硬编码Save文字属于已指出的翻译/注释类清理范围，用户要求本轮不追这些，未新增计数。

## 全文覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [SharedFoldersScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SharedFoldersScreen.kt:1) | 216 |
| [SharedFolderDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SharedFolderDetailScreen.kt:1) | 189 |
| [MemoryManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MemoryManagementScreen.kt:1) | 375 |
| [DialogTextField.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/DialogTextField.kt:1) | 99 |

四文件均完整读取；DialogTextField合并输出出现截断提示后独立重读99行。相关浏览/删除片段单独输出复核，未升级其它partial。测试只读范围没有新增，本轮未运行或编写测试。

累计全文 **1207 / 1546文件、233300行**；partial13，not_registered326，excluded124。全仓通读未完成。完成时校验全部1670 tracked路径SHA-256、字节数与正数文本行数，固定本仓/上游HEAD、tracked/staged空diff、此前110份报告哈希、新报告链接行号及全文批次总计。只新建本报告并更新覆盖台账。
