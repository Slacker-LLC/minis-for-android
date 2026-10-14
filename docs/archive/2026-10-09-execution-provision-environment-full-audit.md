# 执行协调、Ubuntu维护及环境广播全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。本轮只静态阅读，旧208份报告和所有台账保持原样，未改产品代码、运行测试、构建或动用设备/Root/云资源。

## ECR02 / P2：代理广播能在环境注入与命令启动之间清掉用户变量

这是2026-10-07执行协调报告中环境广播候选的证据补充，现登记为确认的静态可达缺陷，不重复计算ECR01。

ExecutionCoordinator.execute 在session.mutex内，将完整用户env及proxy覆盖送入shell.applyEnvironment，并将全部键记入lastInjectedKeys；接着检查generation再调用shell.executeCommand。broadcastProxyChange（348—374）不取session.mutex，它只保留代理键构造新env，却把previous的全部旧键传入applyEnvironment。

RootPersistentShell.kt:144—161 对 previousKeys - environment.keys 执行unset；applyEnvironment和主命令分别调用executeCommand，其commandLock（167）只锁各自操作。MinisApp.kt:1110—1115 在真实 PROXY_CHANGE_ACTION 广播中独立launch调用，具备并行入口。

明确交错：A普通execute先成功export用户变量FOO并记录键；A主命令开始前，B代理广播取得shell的commandLock，使用只有代理变量的新env和含FOO的previous，执行unset FOO；B完成后A执行依赖FOO的命令，看到未设置值。A持有session.mutex不能挡住B。下一次execute会恢复用户变量，因此不称为永久删除配置；正在执行的主命令也不会被直接插入export，因为底层commandLock有效。问题限定于两个独立操作之间的窗口。

记录方向：让环境变更与会话命令遵守同一协调边界，代理广播的previous集合也应只作用代理键。优先复用现有mutex/环境管理，交Claude处理。未做竞争测试或设备复现。

## 旧问题与当前保护

ECR01仍存在：execute的Exception catch在session.mutex.withLock外，按sessionId无条件remove/stop当时映射的shell；未绑定失败请求拥有的实例。当前代码增加后台job入口及输出脱敏，没有改变该异常清理根因，保留旧编号。

当前普通返回与后台onLine使用shellOutputForModel：先终端清理，再按Privacy设置遮盖环境值，再限长。ShellOutputRedactionTest四个已有用例检查普通秘密、ANSI分割、限长前遮盖及空遮盖；仅阅读。普通execute的lineCallback直接传给shell，是否脱敏仍取决于调用方，不能由最终返回保护推断所有流式消费者安全。

newJobProcess是独立RootPersistentShell，session停止与runtime停止依靠ShellJobs的kill入口；用户Stop明确定义后台继续运行。此前JSI01/JSI02输入管道问题不在此重复编号，亦未把所有后台取消语义都称缺失。

## Ubuntu维护边界

UbuntuProvisioner全文233行：探测marker及必需命令，失败30秒退避；DNS准备、apt维护、维护后复探均有失败结果。apt处于短生命周期私有mount namespace，参数由App构造；env -i重设固定guest环境，无minisd/keeper/RPC启动。代理只在当前helper提供URI时注入，具有网络超时和总调用超时。

UbuntuProvisionerTest全文阅读，仅检查时间规则、包列表及生成脚本字符串，不证明设备mount、SELinux、网络或实际包安装成功。未执行测试或任何生成脚本。不能据字符串检查宣布真实Root/chroot已验收；APT01包名语义问题保持独立旧报告。

四文件均完整输出无截断；MinisApp及RootPersistentShell只核对上述片段，不新记全文。旧版本报告不覆盖，现版hash覆盖以新台账为准。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt | 375 |
| src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuProvisioner.kt | 233 |
| src/android/app/src/test/java/com/openminis/app/runtime/ubuntu/UbuntuProvisionerTest.kt | 95 |
| src/android/app/src/test/java/com/openminis/app/runtime/ShellOutputRedactionTest.kt | 40 |

四个现版文件完整读取。新增全文3文件、648行；已全文重读1文件。其它rg定位不计全文。

累计全文1319 / 1758文件，199429行；partial 4，未登记435。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-execution-provision.json)。

校验旧208报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
