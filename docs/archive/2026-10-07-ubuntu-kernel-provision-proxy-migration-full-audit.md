# Ubuntu核心全文审查：启动、包准备、网络代理与迁移恢复

日期：2026-10-07。固定远端主线`ea620e9ec51a246ce76cdec45a8d43109462f472`。新增全文5文件、1511行；既有全文UbuntuMountPolicy33行、LegacyDataMigration30行、UbuntuProvisionerTest95行、RootNetworkProxyTest77行重读不重复计数。旧193份报告及所有旧台账保留，独立新建本报告与覆盖台账。未修改产品代码、编写/执行测试、构建、运行模拟器/adb/Root/设备/云资源、读取凭据或请求真实端点。

本轮不新增缺陷编号。确认旧F15存在尚未覆盖的同目录中断恢复条件，精确区分已修范围；其它生命周期和维护边界按静态证据记录，不把候选写成真机复现。

## F15 / P2：整树进度修复不覆盖同一树复制中断后的链接拒绝

[UbuntuKernel.kt:749](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L749)现役迁移新增App-owned .legacy-migration进度目录，每个固定树有DONE文件。[UbuntuKernel.kt:755](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L755)看到DONE即跳过此树，且拒绝进度路径为符号链接。后续目录失败、或最终总marker未写成功时，已完成树因此可以跳过；旧F15“前一树完成后，后一树失败就永远拒绝前一树”的情形已改善。

然而DONE只在cp和chown完成之后写。[UbuntuKernel.kt:761](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L761)每次未完成树重试仍先find目标树中的任意符号链接，有链接就return75；[UbuntuKernel.kt:765](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L765)cp -a -n保留源树链接，没有在复制开始前生成可恢复的分阶段身份信息。

具体剩余条件：某一合法旧源含普通相对符号链接；本次复制把它放进目标后，复制同树其它文件失败或执行被中断，或者随后chown失败；该树DONE尚未写入。用户消除空间/IO等失败原因后重试，DONE不存在，目标已有上次复制的链接，find非空，于是在再次复制前返回75。总marker不能写，runtime readiness仍失败。

这是同一旧F15的剩余恢复窗口，不重新计数。并不声称任何含链接的迁移都失败：一次完整成功的树会写DONE，普通重试可跳过。也不声称chown -R一定跟随链接，更不建议取消目标链接保护；合同明确Root复制前应拒绝不可信目标链接。

记录方向：在现有受控迁移中明确区分“本轮迁移已引入的部分树”与“原先就存在的目标内容”，制定中断后的恢复/暂存提交规则，同时保留新用户文件和Root no-follow边界。不能用简单删除整棵App-owned目标或放开所有链接解决。未来授权设备验收分别覆盖整树成功后其它树失败、同树复制到链接后失败、chown失败、总marker写失败，保存两轮退出码、逐树DONE/总marker及文件摘要；当前没有执行这些操作。

UbuntuKernelPrivilegeDropTest88行的迁移断言检验cp -a -n、DONE跳过及链接检查字符串都在，没有执行实际copy，不覆盖这一时序。测试名称“resumes past trees it already copied”只支持已完成树的规则，不证明任意部分复制可恢复。

## 当前执行边界与旧系统的真实关系

[UbuntuKernel.kt:123](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L123)启动先准备App backing、探测Root、清理自身旧进程标记、检查/修复rootfs、验证unshare/mount/chroot/setsid/setpriv，再进行包准备、一次性旧数据迁移及guest CLI安装。ready不是仅检查某个daemon socket。

[UbuntuKernel.kt:71](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L71)读实际进程UID/GID并校验package UID；[UbuntuKernel.kt:84](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L84)通过chroot/setpriv执行--clear-groups、清inheritable/ambient/bounding capabilities、--no-new-privs，再env -i启动guest。passwd/group里的数字随真实身份写入，不据历史占位10000认定实际执行写死身份。

[UbuntuKernel.kt:554](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L554)有效session使用对应workspace/attachments/offloads/browser，/tmp绑定该session offloads；global memory/skills/shared/home另有明确App-owned来源。外部SAF目录核对持久read grant，并在缺写授权/配置只读时remount bind ro。mount namespace先设递归private；/dev、/proc、/sys完整绑定并不构成VM或强隔离容器，合同已声明这点。

interactive路径保留forkpty控制终端，不额外setsid；noninteractive仍隔离进程组。这是有意实现差异，不因总架构图写setsid就把交互终端直接报成权限逃逸。未来真机PTY/进程组/SELinux能力仍未验证。

旧/data/adb/minis的用户目录仅在固定迁移映射中使用；rootfs不列为用户数据复制源，目标owner来自真实身份，cp -n保留现有新目标同名文件。旧源不删除，符合一次性前向迁移合同，不能把其保留本身当现役PRoot/Alpine/minisd恢复。

## 网络代理的信任判断与生命周期候选

[RootNetworkProxy.kt:61](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootNetworkProxy.kt#L61)已有Process只有同时活着、READY为true、TCP端口可连接才复用；无自身READY-confirmed子进程而端口已占用，明确返回冲突。启动认证值通过stdin写给固定libminisnetproxy.so，不放启动参数；pid文件位于Root-owned状态目录，避免App cache marker写入路径。

[RootNetworkProxy.kt:138](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootNetworkProxy.kt#L138)等该子进程发精确READY声明后才信任TCP探测。proxyEnv只在该Process活着且READY时返回认证URI，进程不存在就返回空map。UbuntuKernel把helper失败作为兼容路径不可用警告，继续App-UID guest direct networking；不会据无认证的遗留监听者注入apt代理，也没有在本模块增加Root命令/文件RPC。

这里完整核对的是Kotlin启动/拥有者链，并不重新宣称C++代理的所有目标/DNS/CONNECT协议或VPN行为已验收。token在PROXY_URI和环境值里是既有设计；旧PTY环境广播问题TR01此前另有修复范围报告，本轮不重复计数。

stop/clear使用Root pid marker杀进程组并销毁直接拥有的launcher。普通cleanup脚本只检查PID数字，不验证当前cmdline；启动时stale cleanup另有cmdline/environment匹配，二者不是同一保证。进程早退后PID复用与TERM/KILL间交错仍是需要受控环境证据的候选，未直接报成误杀实际发生。

ensureReady清理旧子进程后repeat20的return@repeat只跳过当前迭代，并非整个循环提前退出；会保留固定短等待。它随后仍检查端口冲突，故不能由这个控制流直接推导必然错误信任其它监听者。stop循环可在端口释放时return@withLock。

代理配置是shell启动时快照；helper中途停止或重新启动时，已有shell的环境是否同步要看ExecutionCoordinator及当前终端广播完整链，不能由proxyEnv返回空map推导已启动进程自动清除了旧环境。本轮不把该局部候选算新增故障。

## UbuntuProvisioner：受控维护与准备状态边界

[UbuntuProvisioner.kt:49](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuProvisioner.kt#L49)先要求marker和命令面probe，成功清失败时间；失败后30秒backoff，过期再尝试，不累积锁死。准备DNS后，只有RootNetworkProxy.proxyEnv提供当前认证URI才加apt代理；无helper用direct路径。

[UbuntuProvisioner.kt:189](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuProvisioner.kt#L189)固定BASE_PACKAGES和apt网络重试/30秒连接预算，15秒探测、780秒总准备预算。Root仅在短期namespace内执行固定apt基础维护，普通Agent命令仍走降权guest。没有keeper或通用Root daemon。所有安装成功后写provisioned，随后再probe，缺必需工具返回失败。

probe执行guest python -m pip --version而非直接在Android host跑glibc ELF，这解决两种运行域的差别。静态probe未逐项验证venv模块或所有包实际版本；不能把路径可执行和一次pip调用扩大成全部命令功能正确，也没有具体失败设备反例据此新增条目。

维护日志截取部分stderr/stdout，apt的异常是否会回显认证userinfo需结合实际错误格式；本轮不请求网络、不生成认证canary，也不凭URI存在就宣称日志已泄露。失败backoff用墙钟，时钟回退可能延长等待，暂保留边界观察。

## Rootfs配置与已有测试范围

Kernel的managed write仅允许RootfsManager白名单、拒绝NUL/超64KiB内容、检查rootfs与父目录不是链接、备份及临时rename，restore拒绝目标/备份链接。resolv.conf允许先移除发行版链接本身再原子替换。restore先删目标再mv备份，mv异常中断窗口是候选，Root-owned父目录保护不能被描述成完全无保护。

Rootfs修复先停止终端并请求协调器停当前命令，随后分阶段替换rootfs、invalidate guest CLI并触发镜像重应用callback。UbuntuRuntime现役lifecycleLock包住prepareLaunch与withRuntimeStopped，因此局部Kernel.resetRootfs未自己停止进程不等于产品入口毫无生命周期保护。此前F13重应用修复范围不重复登记。

UbuntuManagedConfigTest86行7例核对allowlist、脚本quote/临时文件/备份、DNS链接替换、NUL拒绝、pip/npm guest位置及PATH。UbuntuKernelPrivilegeDropTest5例核对PTY launch、真实不同UID/GID、capabilities/no-new-privs、身份文件与迁移字符串。均不实际执行Root/mount/chroot/复制。

重读UbuntuProvisionerTest95行5例和RootNetworkProxyTest77行7例，分别测试脚本/包列表/backoff/代理注入/probe字符串以及URI/READY/env/launch/cleanup字符串；proxyEnv absent例并未创建真实子进程。没有执行任何测试，不能当网络、包安装、进程退出、升级迁移端到端证据。

## OpenMinis与覆盖交付

固定上游`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`现有Git树中没有runtime/ubuntu目录。本轮不提供这些新增模块的同路径上游实现对照，不将其解释为上游没有任何运行时能力，也不建议恢复旧双栈。仅读既有上游树，未fetch。

| 新增全文 | 行数 |
|---|---:|
| [RootNetworkProxy.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootNetworkProxy.kt#L1) | 293 |
| [UbuntuKernel.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L1) | 819 |
| [UbuntuProvisioner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuProvisioner.kt#L1) | 225 |
| [UbuntuKernelPrivilegeDropTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuKernelPrivilegeDropTest.kt#L1) | 88 |
| [UbuntuManagedConfigTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/ea620e9ec51a246ce76cdec45a8d43109462f472/src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuManagedConfigTest.kt#L1) | 86 |

UbuntuKernel分1—210、211—420、421—630、631—819完整读取；另四文件完整输出无截断。定位旧报告和相关片段的组合输出截断，不用于扩大全文覆盖。契约01/03/07读作边界核对，不重复累计源文件。

新台账：[2026-10-07-code-review-coverage-ea620e9e-ubuntu-kernel.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-ea620e9e-ubuntu-kernel.json)。累计全文 **1383 / 1664文件、233771行**；partial10、尚未登记271、excluded124，tracked1788。全库通读仍未完成。

交付核验旧193份报告及全部旧台账hash、本地/远端/上游固定HEAD、tracked/staged代码diff、1788快照blob/SHA-256/字节/行数与本报告行号。只新增本文和新台账。
