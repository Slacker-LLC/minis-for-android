# 远端 main 更新后的首次修复状态复核

日期：2026-10-07。用户要求查看已修复许多问题的远端，并保留旧文件交Claude处理。本轮只新建本报告及新版覆盖台账，旧138份报告、旧覆盖台账和产品源码均未改。

本地工作区仍为 `d64e7c06d8159be90737dd9ba03dd9bd5855f339`，不pull/checkout/reset。读取origin HEAD并fetch main，固定本轮远端审查对象为 `37158d188a190416f2b16b45dfb112ecf53cdd7b`；比旧基线多103个提交。git diff显示366个路径变化，228个旧路径内容改变、127个新增、21个删除（重命名会按Git展示规则折叠，不把两种路径计量混同）。提交标题仅用于定位，状态判断看最终源码，不把103个提交都算已逐个复核。

只读快照 `/tmp/minis-remote-audit-37158d18` 来源于固定Git对象。没有运行项目脚本、编译、测试、模拟器、adb、Root、设备、模型或云任务。网络操作仅查看/fetch用户指定仓库；不读取凭据。没有合并、推送或更改远端。

## 本轮已核对的旧问题

| 问题 | 当前判断 | 最终源码证据与边界 |
|---|---|---|
| F11 pip旧配置路径 | 原路径缺口已修复 | [MirrorSettingsScreen.kt:94](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt#L94) 改为etc/pip.conf，不再etc/pip/pip.conf；下层允许路径同步变更。未执行pip config debug，不能声称设备验证已通过。 |
| F12 npm写root HOME | 原路径缺口已修复 | [MirrorSettingsScreen.kt:95](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sandbox/MirrorSettingsScreen.kt#L95) 改为etc/npmrc，不再root/.npmrc。现役guest HOME仍/home/minis；此修复针对全局配置位置，不声称覆盖用户npm配置优先级的所有情况。 |
| F13 rootfs重建后镜像不重应用 | 重应用部分已修复；自动检测部分仍未接回 | [MinisApp.kt:492](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L492) 注册onRootfsReplaced并调用applyAllActiveMirrors；[UbuntuKernel.kt:300](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L300) 在替换后healthy时调callback。旧报告“两个方法均只有声明”已不再适用于applyAllActiveMirrors。autoDetectOnceIfNeeded仍无生产调用，rootfs.freshInstall仍不是现役安装写入链。 |
| F10 root.shell直接选择shell/解释器 | 原直接反例已封堵，整体边界仍需复核 | [RootShellHandler.kt:65](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/runtime/RootShellHandler.kt#L65) 在执行前genericRootToolDenial；[PrivilegedCommandRunner.kt:384](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt#L384) 拒绝所列codeRunningTools与相关选项。已读集合含sh/su/解释器/分发与exec wrappers，不再将旧sh -c反例报告为当前可执行。此为denylist，不能据此宣布所有Android工具语义均不存在执行绕过；没有做设备Root试验。 |
| STE01 技能可写大单文件后无法再编辑 | 仍存在 | SkillPackageInstaller.kt与旧基线字节完全相同，写请求仍只有总量限制，PRESERVE仍先4 MiB读取全树再过滤替换条目。此前真实编辑入口仍writeSkillFile/PRESERVE，未发现新增单条限制。 |
| SZA01 跳过归档成员无展开预算 | 仍存在 | SkillArchiveReader.kt与旧基线字节完全相同；跳过目录/隐藏项closeEntry未走readBounded/totalBytes。未执行压缩样本。 |
| IME01 全屏视频错误吞掉提示且播放状态不变 | 仍存在 | InlineMediaPlayer.kt字节完全相同；全屏onError仍Log并true，不更新isPlaying/error/showControls。未播放损坏媒体。 |
| DCL01 调试CLI丢失位置参数中的选项 | 仍存在 | runtime/guest/DebugOffloadHandler.kt字节完全相同，parser仍去掉选项后以rest构造exec等argv。DEBUG边界不变，未将它写成Release问题。 |

以上“已修复”均限表内原反例/路径，不能替代整项设备验收。F11/F12下层路径允许列表和镜像生成已经复核；本轮不重复在线查pip/npm规范，其位置依据与限制已记录在旧报告。本轮没有按提交标题宣布memory/backup/provider/agent loop全部已修复，它们待独立复核。

## 镜像页本轮全文与剩余候选

旧版MirrorSettingsScreen.kt已完整读取1—280、281—555、556—816；远端对应文件818行只有完整diff中的4增2删：两行路径说明、pip/npm两个路径替换。因此以旧全文加完整变更核对覆盖远端所有行，不将UbuntuKernel/MinisApp/RootShellHandler的片段阅读升级为全文。

镜像页仍先改状态/持久化偏好，再异步写配置，失败只日志，属于F11原报告已明确的反馈缺口，不在本轮另增编号。runTest先awaitAll再逐个推进progress，表示结果在所有请求返回后才集中呈现，不把HEAD延迟称下载吞吐量。所有请求有connect/read/call timeout，catch生成失败结果；没有凭页面离开仍测速的singleton scope断言无限后台任务。

APT当前探测noble Release，非单凭旧alpine preference key判旧Alpine启用。categoryFromKey(alpine)明确映射UBUNTU_APT；生成的是Ubuntu deb822配置。autoDetect方法未接回是遗留方法可达性问题，不是PRoot/minisd恢复生产。

trusted-host、恢复无原文件备份、配置操作顺序与取消竞态等候选本轮没有闭合新反例，未计新缺陷；未访问测速目标或操作真实rootfs。

## 新版覆盖基线

新增台账：[2026-10-07-code-review-coverage-37158d18.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18.json)。旧台账完全保留，其1355/1546的完成度属于旧HEAD；不能套到远端新版。

新版仅在SHA-256完全相同时继承旧文件阅读状态。发生变化的已读文件重置为not_registered；新文件按既有代码/资源/fixture范围纳入；移除文件列在transition，不继续计数。仅本轮完整复核的MirrorSettingsScreen提升为full。

新版1776 tracked路径，1652纳入范围、124 excluded；全文 **1142 / 1652文件、184426行**，partial4、not_registered506。1145个未变已读路径（full/partial）继承旧记录；另新增镜像页全文。这是版本切换后的真实覆盖变化，不是把旧阅读抹掉，也不是全库阅读已完成。

快照最初通过git archive生成，逐blob复核发现gradlew.bat工作树换行与Git对象不同；随后从git cat-file --batch取得所有原始blob，仅修正/tmp快照内容，再逐文件检查Git blob SHA-1及台账SHA-256/bytes/lines。原工作区无改动，错误快照没有被当成新版代码事实。

交付检查：本地/远端固定HEAD、local tracked/staged空diff、旧138份报告hash、旧覆盖台账全部1670来源hash/bytes/lines及批次总计、新版1776对象逐blob匹配、新报告所有本地链接/行号与固定GitHub证据行号。新版不把旧批次总计当新版全文总计。全库目标继续，后续按新版对象继续阅读。
