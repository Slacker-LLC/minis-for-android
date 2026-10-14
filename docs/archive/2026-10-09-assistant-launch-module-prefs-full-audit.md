# 助理启动与模块设置：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，未运行Xposed、设置角色、启动Activity或测试；只新增报告和台账。

## HA01仍有源码残留

[旧身份审查](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-hyperos-gesture-assistant-identity-audit.md)记录HA01：角色字符串按包名子串判断。现版 AssistantLaunch.isConfiguredAssistant 第80—83行仍使用 contains(ownPackage)，而非解析组件后的精确包名。读取失败返回false是真实保护，但不能修复子串误认。沿用HA01，不增加编号；未在设备设置另一角色或验证手势行为。

OEM目标返回null，未知、空白和旧gemini值回OEM；MINIS需要已安装、角色检查以及action可解析。第114—127行首次可解析action启动抛异常会直接返回false，不尝试第二action；旧报告已将此作为候选，本轮不重复新立。返回false提供调用方保留系统行为的信号，不等于本文件独立证明整个hook已经恢复原手势。

## 设置读取

ModulePrefs reader引用volatile，读取时catch异常并回各key默认值；string为空白也回退。电源接管、热词修复、自动配置默认false；手势栏circle-to-search默认true，双指默认false。所以“读取失败一律关闭所有hook”的概括不成立；这是明确逐项默认策略，不擅自改成缺陷。

attachSharedPreferences adapter以contains判断后读取值，读取异常仍由isEnabled/string外层处理。defaultOf未知key为false。这里只确认读侧，不宣布跨进程设置传播与实际框架刷新已验证。

## 已有测试范围

AssistantLaunchTest 覆盖目标action列表、角色标志、OEM空目标、未知/旧值及大小写解析，没有真实Settings.Secure、组件精确身份或Activity启动失败路径。每个target被处理的用例还使用Kotlin assert，是否启用断言取决于运行配置，不作为缺陷核心证据。未运行测试，不能写成手势端到端已通过。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/xposed/AssistantLaunch.kt | 136 |
| src/android/app/src/main/java/com/openminis/app/xposed/ModulePrefs.kt | 92 |
| src/android/app/src/test/java/com/openminis/app/xposed/AssistantLaunchTest.kt | 63 |

三个现版文件完整读取。新增全文3文件、291行；已全文重读0文件。其它rg定位不计全文。

累计全文1386 / 1758文件，210560行；partial 4，未登记368。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-assistant-launch.json)。

校验旧233报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
