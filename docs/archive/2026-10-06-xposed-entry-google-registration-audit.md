# Xposed 入口、Google 配置与注册基础设施全文审查

日期：2026-10-06。本仓固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮完整阅读 19 个文件、1374 行，**未新增已确认缺陷**。独立报告，旧报告不修改；只静态阅读，没有执行测试、加载模块、操作 LSPosed、Root、设备或云资源，也没有修改产品代码。

## 核对结果与证据边界

MinisXposedModule 的入口声明与 META-INF/xposed/java_init.list 一致。module.prop 声明 API 102、staticScope 与 protective exception mode；scope.list 的九个目标与 ModuleTargets.SCOPED_PACKAGES 对应。应用自引用 OWN_PACKAGE 为 llc.slacker.minis。这里确认源配置一致，不等于当前 APK 已被框架加载或 Release 打包验证通过。

onModuleLoaded 记录进程，非系统进程且不匹配目标时 detach；onPackageReady 再通过 installsInProcess 过滤。SystemUI、launcher、ColorOS memory 限于主进程，Google 搜索及其他声明目标按包名或包名加冒号匹配。system_server 经单独回调分发。没有从本轮实现看到 Google 身份改写注册到所有目标进程的证据。

HookGroups 将 GoogleEligibilityHooks 注册在 GOOGLE_SEARCH_PACKAGE；GoogleSpoofProfile 只匹配一个完整 property key 和两个完整 feature 名。Build 五个字段的改写逐项报告 installed/missing/failed；SystemProperties 三种读取入口及 hasSystemFeature 的声明重载逐项注册。实际 Google app 是否读取这些字段、平台是否允许反射/Unsafe 写入、优化代码是否仍缓存旧值，均无本轮设备证据，不能把安装报告等同于 Google 功能可用。

HookRegistrar 在每次注册失败时记录 failed，取消该注册 key 的占位；同一 registrar 的同 executable/id 重复注册会被拒绝。HookSupport 的 class/member 查找按相关入口处理缺失与 LinkageError。HookInstallJournal 捕获 Exception 并记录失败；它不负责吞掉 Error，不能泛称任何故障都会被该层处理。

模块 target 在安装前加入 installedTargets，失败后本进程不会重试；这可能是值得核对的恢复设计，但目前没有确定的重新触发入口与应重试合同，不据此新增缺陷。注册去重只限当前 registrar，而模块另有 target 去重；未证明实际生产会重复叠加同一 hook。

LogSafety 提供类型/稳定 token 格式化，LogThrottle 按 key/window 控制重复输出。它们并非全局强制日志过滤；HookLogger 普通接口仍接受字符串。后续各 OEM hook 需要检查调用内容，不能因 helper 存在即断言全部日志安全。Google profile 的常量和模块目标本身不涉及真实凭据。本轮未读取用户偏好或设备日志。

## 已有测试及 OpenMinis 对照

四个既有测试文件静态核对了 Google key/feature 的精确匹配、target 进程规则、模块身份字符串和注册顺序/空 target 拒绝。未运行测试。它们不覆盖 Android 反射写入、libxposed API 实际行为、Google 客户端资格判断、OEM 启动时序或设备功耗。

固定已有 OpenMinis 对照目录 `/tmp/openminis-review-20261004`，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读其 Git tree 未找到同路径 Xposed Google/入口/registrar 文件，因此本轮不作同实现优劣结论。未 fetch。源注释指向 Eta 的移植出处，不能用注释替代对 Eta 固定源码的核验；本轮未另下载 Eta。

## 全文覆盖

| 本轮全文文件 | 行数 |
|---|---:|
| [HookGroupRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/HookGroupRegistry.kt) | 44 |
| [HookGroups.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/HookGroups.kt) | 81 |
| [HookInstallStatus.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/HookInstallStatus.kt) | 82 |
| [HookLogger.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/HookLogger.kt) | 63 |
| [HookRegistrar.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/HookRegistrar.kt) | 126 |
| [HookSupport.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/HookSupport.kt) | 172 |
| [LogSafety.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/LogSafety.kt) | 37 |
| [LogThrottle.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/LogThrottle.kt) | 25 |
| [MinisXposedModule.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/MinisXposedModule.kt) | 123 |
| [ModuleTargets.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/ModuleTargets.kt) | 95 |
| [GoogleEligibilityHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/google/GoogleEligibilityHooks.kt) | 205 |
| [GoogleSpoofProfile.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/google/GoogleSpoofProfile.kt) | 47 |
| [java_init.list](/home/jiale/projects/minis-for-android/src/android/app/src/main/resources/META-INF/xposed/java_init.list) | 1 |
| [module.prop](/home/jiale/projects/minis-for-android/src/android/app/src/main/resources/META-INF/xposed/module.prop) | 4 |
| [scope.list](/home/jiale/projects/minis-for-android/src/android/app/src/main/resources/META-INF/xposed/scope.list) | 9 |
| [HookGroupRegistryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/HookGroupRegistryTest.kt) | 47 |
| [ModuleTargetsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/ModuleTargetsTest.kt) | 120 |
| [XposedIdentityTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/XposedIdentityTest.kt) | 45 |
| [GoogleSpoofProfileTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/google/GoogleSpoofProfileTest.kt) | 48 |

累计全文 **1023 / 1546 个文件、209187 行**；partial 13，not_registered 510，excluded 124。全仓通读仍未完成；其他 OEM hook、模块偏好与设置尚待继续阅读，本轮不提前登记。

完成时核对全部 1670 tracked 路径、SHA-256、字节数与正数文本行数，本仓/固定上游 HEAD、tracked/staged 空 diff，此前 76 份报告哈希，本报告文件链接，以及台账全文与批次总计。没有修改任何产品源码或测试。
