# 无障碍保护控制、恢复与服务列表更新审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2**；全文阅读 6 个文件、1690 行。静态审查，未改产品源码，未运行测试、构建、模拟器、设备或云任务。独立新报告，旧报告不改。

## AP01 / P2：恢复请求排队成功被当作恢复完成，失败后跳过修复提示

系统端 [AccessibilityServiceEnforcer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityServiceEnforcer.kt:353) 的 ACTION_RECOVER 在保护开启、服务信息有效且 scheduleRuntimeRecovery 返回 true 时回复 RESULT_APPLIED。[AccessibilityServiceEnforcer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityServiceEnforcer.kt:383) 仅确认 Runnable 成功入队，已有任务排队也返回 true；真正恢复在后续执行，异常仅记日志。它不确认设置已恢复，更不确认服务连接。

客户端管理器 [AccessibilityRecoveryManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/AccessibilityRecoveryManager.kt:269) 接收 APPLIED 后等待最多 5 秒，得到 bound；但 [AccessibilityRecoveryManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/AccessibilityRecoveryManager.kt:287) 更新撤权状态后只记录 bound，[AccessibilityRecoveryManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/AccessibilityRecoveryManager.kt:290) 无条件返回 true。[AccessibilityRecoveryManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/AccessibilityRecoveryManager.kt:391) 随即结束 ensureGrantOrPrompt，跳过 Shizuku/系统设置修复提示，即使 bound=false 且重新读取仍显示授权缺失。

静态失败路径：广播已接受；后台设置写入失败（enforce 明确处理 putString=false），或后台恢复抛异常；5 秒后 service 仍为空且授权仍缺失。管理器仍返回 true，恢复提示不会出现。较慢但最终恢复成功的情况不能据此判失败，本项限定已确认未恢复或仍不可用时的反馈与回退缺口。

[AccessibilityOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:136) 依赖该结果决定是否走拒绝反馈；具体动作仍取得实际服务实例，因此不能声称点击/读屏在服务不存在时也会成功。影响是用户没有得到本应提供的恢复选择，后续操作仍会因服务不可用失败。上游 repairWithShizuku 同样在写入成功后不要求 bound=true，但它至少先检查两次真实写入的 exitCode；本地模块路径连“写入完成”也没有从 APPLIED 获得。不是将两者等同的新问题。

修复方向：区分接受请求、恢复设置和服务可用；等待后核对真实状态，仍未恢复时进入现有失败/提示流程，避免重复写入或强制重建。这里只记录，未修改或编写测试。

## AP02 / P2：二次读取仍不能阻止服务列表并发更新丢失

[AccessibilityServiceEnforcer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityServiceEnforcer.kt:703) 的 mergeLatestAccessibilitySetting 在缺少 Minis 时再次读取列表，然后返回合并字符串；[AccessibilityServiceEnforcer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityServiceEnforcer.kt:623) 之后另一次调用 putString 写入整份列表。removeLatestAccessibilitySetting 同样先读取快照，后由重绑流程写入去除 Minis 的整份列表。它们没有与系统设置界面、其它服务设置更新共享原子修改边界。代码注释也明确 Settings 没有公开 CAS；二次读只能缩小窗口。

静态交错：第二次读取为 A（Minis 缺失）；系统设置在它之后保存 A:B（用户刚启用其它服务 B）；保护端随后写 A:Minis。B 从列表消失。反向交错也可把用户刚禁用的 A 重新加入。重绑临时移除的读后写也存在相同窗口。ContentObserver 在覆盖后看到的是已被覆盖的新值，没有被保留的 B 可供它恢复。后台 Handler 只串行本模块任务，不能串行外部写入者。

这是源码允许的丢更新条件，尚无真实设备并发复现或频率证据；不宣称每次保护都会关闭其它服务。此前 DC16 是 Shizuku 读取抛错后回退空列表的确定覆盖，本项为 Xposed 正常读取成功后的并发写入，触发点不同，不重复计数。

修复方向：核对当前 Android/OEM 是否有可复用、共享锁下的服务集合修改入口；没有可靠原子入口时明确冲突处理边界，避免把二次读取宣称为保留其它服务的保证。仅增加本模块锁或写后重读无法单独解决外部并发写。本轮不提出未经验证的系统 hook 实现。

## 已核对边界、已有测试与上游

控制 receiver 要求签名权限，另校验 ordered broadcast、协议版本、实际发送 UID 与启用中的 App UID；Android 14 以下无法获得发送 UID 时使用拒绝值。服务校验要求目标 enabled/exported 与 BIND_ACCESSIBILITY_SERVICE。未发现这条控制链允许任意 UID 的证据。

恢复有设置退避、注册重试上限、重绑次数/冷却以及连接状态检查；不能说它无限忙循环。客户端阻止主线程阻塞恢复。普通合并/删除 helper 按 ComponentName 精确匹配并保留快照中的其它项，不存在角色子串匹配类缺陷。上述正确性不能排除 AP01/AP02。

已有两个测试只静态阅读，覆盖结果码、UID/协议拒绝、组件匹配、退避与重试计数；没有验证异步排队后写入失败的端到端反馈，也没有覆盖外部列表写入交错。未运行或新增测试。

固定 OpenMinis `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 只读 Git 对象，未 fetch。其 AccessibilityRecoveryManager 的 Shizuku 写入先检查 exitCode，再等待连接并返回写入成功；ensureGrantOrPrompt 直接提示，没有本地模块优先恢复分支。前轮已核对固定 tree 未见这些 Xposed system 同路径实现，故不把 AP01/AP02 归为共享上游缺陷。Eta 来源只作为代码注释声明，未经本轮独立核实。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [AccessibilityProtectionClient.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityProtectionClient.kt) | 179 |
| [AccessibilityProtectionHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityProtectionHooks.kt) | 166 |
| [AccessibilityProtectionProtocol.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityProtectionProtocol.kt) | 61 |
| [AccessibilityServiceEnforcer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AccessibilityServiceEnforcer.kt) | 1060 |
| [AccessibilityProtectionClientTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/system/AccessibilityProtectionClientTest.kt) | 43 |
| [AccessibilityServiceEnforcerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/system/AccessibilityServiceEnforcerTest.kt) | 181 |

累计全文 **1075 / 1546 个文件、215892 行**；partial 13，not_registered 458，excluded 124。全仓通读尚未完成。管理器和 offload handler 已全文登记，本轮片段复核不重复计数。Enforcer 按五段全文阅读，未遗留截断。

完成时核对全部 1670 tracked 路径、SHA-256、字节数和正数文本行数，固定本仓/上游 HEAD，tracked/staged 空 diff，此前 82 份报告哈希，本报告链接/行号，以及全文与批次总计。只新增本报告并更新覆盖台账。
