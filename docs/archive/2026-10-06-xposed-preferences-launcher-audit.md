# Xposed 偏好读写与 HyperOS launcher 全文审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮完整阅读 9 个文件、1181 行，**未新增已确认独立缺陷**。独立报告，旧报告原文保持不变。没有修改产品代码，没有运行测试、Root、LSPosed、模拟器、设备或云任务，没有读取真实偏好或凭据。

## 当前源码事实

ModuleSettingsStore 的写入使用框架 remote preferences，local SharedPreferences 为镜像。无 remote 时拒绝写入；Boolean/string 写入只有 commit 返回 true 才更新镜像并返回成功。SystemEnhanceScreen 的三个开关及助手目标选择都在成功返回后更新 UI，失败会提示，框架未连接时入口禁用。没有把“写 local 就等于 hook 收到”作为当前实现事实。

MinisApp 的服务 bind/death 回调连接到 attachService/detachService；detach 只清理同一个 service 实例，避免旧实例死亡直接清空后来的绑定。attach 会尝试 reconcile，connected 表示拿到了远端偏好，不表示全部迁移写入成功。reconcile 不返回 commit 状态，外层也不报告迁移失败；但正常读取在有 remote 时优先使用远端，因此不能单凭 connected=true 就声称 UI 必然显示未生效值。

**默认值存在例外，需要纠正笼统叙述：** ModulePrefs 的 GESTURE_BAR_CIRCLE_TO_SEARCH 默认 true；读 key 抛异常和 reader 不存在都会回退到该 true。其它已列 Boolean 默认 false。ModulePrefsTest 明确固定这个例外，故不是源码偶然漏写：不能据注释把本模块概括成“所有开关读取失败均关闭，完全保持 OEM 行为”。若用户曾关闭该开关但当前进程读不到设置，HyperOsSearchTrigger 仍可能按默认 true 尝试启动搜索。本轮记录确切语义与限制，不将默认开启这一已明确实现的产品选择擅自改成缺陷。

HyperOsLauncherHooks 在 gesture manager、invokeOmni、event helper 中选择第一个能注册的入口，再回退 legacy；没有在这些入口同时成功注册多套搜索。搜索 trigger 不可用或失败时返回 false，拦截器继续 ROM 方法；成功才返回符合已匹配 method 类型的 takeover result。当前 helper 对任意非 void 类型返回 true，但三个直接调用入口分别检查 void 或 boolean；不能凭纯 helper 对 Int 的测试就断言生产必然类型错误。尚未阅读 legacy/long-press 的完整链，不将其登记为全文或推断已验证。

助手角色 ON_RESUME 复查与 ActivityResult 回调只刷新状态；当前页面没有展示角色行，不将这些剩余状态变量当作新的功能缺陷。RootStatusSection 使用 RootAccess.state；本轮没有请求 Root 或改变默认助手。

## 候选与待继续核对

remote 读取并非所有路径都捕获异常；重新绑定失败时旧 service/remote 是否被及时清理，依赖框架回调时序。仍需检查固定 libxposed 服务实现和实际生命周期，不能把可能抛错泛化成当前可复现崩溃。reconcile 镜像优先在每次 attach 都执行，外部 remote 变化与旧镜像的协调策略也需要继续核对真实写入方。此次不提前新增计数。

三个已有测试文件只静态阅读，覆盖默认值、不可读 fallback、写入拒绝/接受、镜像迁移和返回类型。FakePrefs 的失败 commit 不修改内存；其行为不证明真实跨进程框架实现完全同样，也不替代断连/重新绑定设备验收。未执行测试。

固定 OpenMinis `/tmp/openminis-review-20261004`，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读 Git tree，没有找到本轮 Xposed 偏好/launcher 同路径文件，因此没有对应实现的差异结论。未 fetch；Eta 出处仍仅是源码声明，本轮未下载或验证 Eta 固定提交。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [SystemEnhanceScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SystemEnhanceScreen.kt) | 297 |
| [ModulePrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/ModulePrefs.kt) | 92 |
| [ModuleSettingsStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/ModuleSettingsStore.kt) | 195 |
| [HyperOsGesturePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsGesturePolicy.kt) | 19 |
| [HyperOsLauncherHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsLauncherHooks.kt) | 155 |
| [HyperOsSearchTrigger.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsSearchTrigger.kt) | 53 |
| [ModulePrefsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/ModulePrefsTest.kt) | 123 |
| [ModuleSettingsStoreTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/ModuleSettingsStoreTest.kt) | 215 |
| [HyperOsGesturePolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/hyperos/HyperOsGesturePolicyTest.kt) | 32 |

累计全文 **1032 / 1546 个文件、210368 行**；partial 13，not_registered 501，excluded 124。全仓通读尚未完成；关联 MinisApp 仅复核既有 full 文件片段，不重复计数。

完成时核对全部 1670 tracked 路径、SHA-256、字节数和正数文本行数，本仓/固定上游 HEAD、tracked/staged 空 diff，此前 77 份报告哈希，本报告文件链接，以及台账全文与批次总计。只写新报告和覆盖台账。
