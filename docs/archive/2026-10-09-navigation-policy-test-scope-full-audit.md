# 导航生命周期与可见会话：策略及已有测试全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，未执行测试或启动 UI。没有新增已确认缺陷，仅新增本报告与覆盖台账。

## 实际策略

NavigationPolicy 的 Lifecycle.awaitResumed 等待 RESUMED；NavController 版本通过 flatMapLatest 随当前 back-stack entry 变化，避免始终等待过时页面。visibleChatSessionId 优先使用已挂载 ChatScreen 的会话，再回退外层 route。shareNeedsFreshChat 将非空且非 CHAT 的路由视为需要新会话。

AppNavigation 第101—142行局部核对：首次非 Unknown 深链确实先调用 navController.awaitResumed。第270—313行的 warm-share 路径仍直接 safeNavigate，没有复用这个等待；该 effect 只以 bufferVersion 为键。生命周期未稳定时是否被导航保护丢弃、此后 buffer 是否重发，仍需结合接收器和 UI 消费路径，不把局部差异直接升级成新增丢分享 Bug。AppNavigation 局部输出不计全文。

## 已有测试能够证明的范围

NavigationPolicyTest 使用 LifecycleRegistry 验证 STARTED 等待、已 RESUMED 立即返回和取消等待；检查分享 route 分类、挂载会话优先，以及旧 ChatScreen 清理不能清空后继会话。

它没有实际 NavController：不直接验证 currentBackStackEntryFlow 切换、空 back stack、销毁时等待取消、真实导航动画或 warm-share 到达时序。最后一个测试直接调用 ChatViewModelStore，并非真实两个页面交接。不能把这些函数级断言表述成已验证全部导航返回栈和分享投递端到端行为。

Lifecycle 等待本身没有处理 DESTROYED 返回，但调用方协程取消能终止等待；具体生命周期 owner 仍需按调用点判断，本轮不认定内存泄漏或永久挂起。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/navigation/NavigationPolicy.kt | 39 |
| src/android/app/src/test/java/com/openminis/app/ui/navigation/NavigationPolicyTest.kt | 96 |

两个现版文件完整读取。新增全文2文件、135行；已全文重读0文件。其它rg定位不计全文。

累计全文1363 / 1758文件，208704行；partial 4，未登记391。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-navigation-policy.json)。

校验旧225报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
