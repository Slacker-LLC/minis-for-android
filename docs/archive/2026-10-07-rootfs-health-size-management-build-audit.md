# Rootfs健康、大小管理与构建脚本复核

日期：2026-10-07。本轮固定审查快照 `37158d188a190416f2b16b45dfb112ecf53cdd7b`；交付时origin/main已被外部更新为`0b96f7aa2a46c313b896a6f66501586e7caba81a`，本文不是新远端结论。完整静态阅读4文件、1047行。旧171份报告与旧台账保留，只新建本文和台账。未改代码、执行构建脚本/测试、下载rootfs、启动模拟器/adb/Root/设备或云任务。

## RFS01 / P3 — 健康rootfs的大小栏没有探测，持续显示加载

[RootfsManagementViewModel.kt:31](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementViewModel.kt#L31) withHealth将健康时rootfsSize设为health.sizeBytes ?: 0。refresh仅manager.checkHealth→withHealth，不调用manager.getRootfsSize。

manager.checkHealth第43行返回UbuntuKernel.inspectRootfs。当前[UbuntuKernel.kt:208](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L208) inspectRootfsLocked调用固定probe后返回evaluateProbeOutput；RootfsManager.validateMetadata生成HEALTHY时未赋sizeBytes，RootfsHealth默认null。runtime/sandbox生产范围检索没有sizeBytes赋值。install后refresh也走同链，所以常规健康状态rootfsSize仍0。

[RootfsManagementScreen.kt:142](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementScreen.kt#L142) 安装健康时展示大小行：size>0显示格式化大小，否则CircularProgressIndicator。没有另一个挂起的大小任务，spinner并非真实探测中。已有[RootfsManager.kt:87](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt#L87) getRootfsSize通过UbuntuKernel.measureRootfsSize执行固定du探测，StorageManagementScreen第85行使用，rootfs管理页未接入。

影响限定大小展示永久等待，健康/是否安装判定本身不被这个0覆盖。建议复用已有有界大小探测，区分loading、available、unavailable，避免未知值被当成继续加载。未来验收健康rootfs、探测超时/Root不可用均结束加载并给准确状态；本轮未接线或执行。

## TR07必需布局节点类型仍未闭环

[RootfsManager.kt:304](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt#L304) noSymlinkLayoutChecks每层仅-e与! -L，除root自身外不要求所需目录-d、文件-f。buildRepairCommand的NEW验证同形；特定workspace/memory/proc等由目录损坏为普通文件时仍可能通过probe。旧TR07仍在，不能因加入符号链接检查认为类型检查也完成。

/etc/os-release有专门允许标准相对symlink规则，非symlink分支仍-e而非-f；shell检查另有-x。Root-owned树不等于每种损坏都不会发生，后续bind拒绝也不等于健康页已正确分类。没有修改Root-owned内容或构造测试布局。

metadata要求Ubuntu24.04、arm64、base和64hex upstream hash；这证明字段兼容格式，不重算完整rootfs目录或证明实际基础包hash。Root-owned可替换state的定位正确，不凭metadata非完整文件验签直接新增供应链故障。

## 安装、重置与进度

installIfNeeded先probe，健康直接Installed、ROOT_UNAVAILABLE变Failed不盲目修复，其它状态经UbuntuRuntime.withRuntimeStopped→ensureRootfs。ViewModel第112行安装后再checkHealth，以health决定lastOperationSuccess；不会仅凭函数正常返回就宣布Root不可用安装成功。

reset经withRuntimeStopped→resetRootfs，失败抛异常；当前Kernel固定/data/adb/minis/rootfs并rm受控runtime树，成功invalidateGuestCli。用户App-owned数据不在这个树内，不能按旧PRoot数据模型解释为删除所有记忆/工作空间。reset成功manager状态Idle，不是立即重装成功。

progress collector只在Installed/Failed自取消，Idle忽略；reset成功回Idle后collector可继续等到下一次安装/VM清除，installIfNeeded抛异常也可能未发终态。作为进度job生命周期候选，不宣称必然长久内存泄漏。它下一次observe会cancel旧job，VM scope清除会cancel。

UI按isProcessing禁用安装/重置，ViewModel方法没有内部重入guard；manager/kernel runtime gate另提供基础维护串行。没有实际重复调用证据，不新增“所有重入破坏rootfs”。refresh runCatching吞异常且不显示新错误，可能保留旧健康；isInstalled正常值有health判断，避免过度扩张。

## 构建脚本真实保护与界限

[build-ubuntu-rootfs.sh:22](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/scripts/build-ubuntu-rootfs.sh#L22) 默认24.04.3有固定SHA256，非默认release必须显式EXPECTED_BASE_SHA256；下载checksum后精确匹配base文件名、格式、pin，再验证archive。缓存base并不跳过hash检查。没有运行curl、tar或删除STAGE。

脚本生成App所需layout、rootfs metadata、archive hash/manifest，tar sort/mtime/owner和gzip -n有确定性设置。BASE_URL被插入JSON文本未结构化转义、WORK/DIST是可覆写构建路径，涉及构建操作者输入，未找到非受信远端调用链，不强行报Root执行漏洞。

passwd/group模板有10000占位条目，但当前Kernel.currentAppIdentity与chroot实际App UID/GID另有生产选择；模板不是setpriv实际执行UID，不能仅检索10000就宣布违反真实身份合同。本轮不重审全部bind/provision，亦不将这个模板当成可恢复旧PRoot理由。

全局/workspace别名在每个mount namespace由session backing决定，不能从模板symlink推出所有session共享同一workspace。rootfs必需命令由UbuntuProvisioner设备上补齐，manifest requiredCommands不等于base archive已包含，未把它们混为事实。

## 固定OpenMinis对照与证据范围

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。GIT_NO_LAZY_FETCH=1读取现有RootfsManagementViewModel第85—120行，无fetch；上游install正常返回直接success，当前health后判定有实际改善。未复制上游PRoot管理或执行任何操作。

既有recovery/size测试此前已全文覆盖，本轮未新增测试阅读或运行；不能把生成字符串检查当成真实Root布局/SELinux/设备修复证据。上轮TCL01的退出中终端join缺口也影响此处runtime停止屏障，本轮不重复编号。

## 全文覆盖及保留

| 新增全文 | 行数 |
|---|---:|
| [build-ubuntu-rootfs.sh:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/scripts/build-ubuntu-rootfs.sh#L1) | 198 |
| [RootfsManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt#L1) | 375 |
| [RootfsManagementScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementScreen.kt#L1) | 307 |
| [RootfsManagementViewModel.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/sandbox/RootfsManagementViewModel.kt#L1) | 167 |

Manager分1—195/196—375，UI分1—180/181—307，ViewModel1—167、构建脚本1—198全文。Kernel/Health/其它caller片段不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-rootfs-management.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-rootfs-management.json)。累计全文 **1291 / 1652文件、214125行**；partial4、not_registered357、excluded124，tracked1776。全库通读未完成。

交付核验旧171份报告与所有旧台账hash、固定本地/上游HEAD及旧审查提交对象、记录变化后的远端ref、tracked/staged空diff、1776快照blob/hash/bytes/lines及证据行号。只新建本文与台账。
