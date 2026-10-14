# Contextual search 调用方与系统电源键审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P1**；全文阅读 6 个文件、655 行。只读源码及已有测试，独立新报告，旧报告不改；未运行测试、Root/设备、模拟器、LSPosed 或云任务，未安装应用或触发搜索，未改产品代码。

## CS01 / P1：ColorOS direct 的包名白名单免除权限检查，却不验证系统应用身份

[ContextualSearchCallerPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/ContextualSearchCallerPolicy.kt:19) 将 SystemUI 与 com.coloros.colordirectservice 列入 PRIVILEGED_CALLERS。[ContextualSearchCallerPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/ContextualSearchCallerPolicy.kt:36) 对该集合仅检查包名，完全不要求 packageName 在 systemPackages 中，也不要求 gestureEnabled。相邻 HyperOS 分支则同时检查开关与系统包身份。

[ContextualSearchHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/ContextualSearchHooks.kt:194) 从 Binder UID 查询真实关联包，并计算其中的系统应用集合；这避免了请求自己伪报包名，但 policy 第一分支仍忽略已计算的系统身份。[ContextualSearchHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/ContextualSearchHooks.kt:179) 若 function 为 startContextualSearch 且 allowsCallingUid=true，会直接返回 null，跳过原 enforcePermission。

静态条件：设备的系统镜像未安装 com.coloros.colordirectservice，普通应用恰好使用该尚未占用包名；本模块在 system_server 中已安装匹配的 contextual-search hook。其 Binder UID 的 callingPackages 含该名字、systemPackages 不含，policy 仍允许，且手势开关关闭也一样。此判断并不证明它是厂商手势服务。没有运行安装、Binder 调用或任何权限绕过实验。

已有 ContextualSearchCallerPolicyTest 的首个测试显式要求 ColorOS direct 在 systemPackages 为空、gestureEnabled=false 时返回 true，说明缺口也被测试固化；但这个预期不能证明非系统同名应用可信。HyperOS 同名侧载应用已有拒绝测试，ColorOS 分支没有对应拒绝。

[AOSP 固定源码 393c96de 的 ContextualSearchManagerService](https://android.googlesource.com/platform/frameworks/base/%2B/393c96de0020e54d7fb56858a509250c7cadcd36/services/contextualsearch/java/com/android/server/contextualsearch/ContextualSearchManagerService.java)在 startContextualSearch 入口调用 enforcePermission，原方法检查 ACCESS_CONTEXTUAL_SEARCH 或临时授权，不满足则抛 SecurityException。这里确认本地 hook 会绕过这一步，不能把调用包名白名单视为等价权限证明。源码版本不是用户设备 ROM，故未声称所有 ROM 均可触发，也未声称已经窃取截图、访问其它数据或获得通用系统权限。

在真实 ColorOS 的同名系统包已安装且签名占据包身份时，普通应用不能直接替换它；本项限定该名字未由真实系统包占用的设备/用户环境，以及 hook 的目标方法存在。模块对 system_server 的注册没有按 ColorOS 设备先过滤，因此不能仅靠 OEM 命名假定这些前提永久排除。

修复方向：免除权限的调用方也必须核对可信系统身份或更强的签名/UID 依据；身份不成立或无法确认时继续原权限检查。保持 shared UID 的实际权限边界，并加入非系统同名包与开关状态的否定验收。这里只记录，没有修改 hook 或写测试。

## 其余核对与去重

系统 PowerKeyHooks 只匹配 speech handler 的 assist message，OEM target/缺少 PWM 或 Context/launch=false 时继续原路径；成功后记录 uptime 并重放 OEM haptic。它同样调用 AssistantLaunch，旧 HA01 的角色子串匹配问题仍存在，不再计作新问题。

ContextualSearchHooks 的配置门、bootstrap、Google provider 包名替换及权限入口属于不同 hook。bootstrap 会先检查 service alive，再尝试平台 startService；“调用返回”后也复查 Binder alive。运行时服务启动、Google provider 支持与截图效果没有设备证据。模块默认手势开关语义沿用前报告，不再次计数。

两个已有测试只静态阅读，覆盖 assist message/去重及调用方集合规则。没有运行。CS01 的验证范围应包括真实系统包、非系统同名包、其它普通包、shared UID 和权限原检查保留；本轮不执行设备验证。

固定 OpenMinis `/tmp/openminis-review-20261004`，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读 tree 未找到本轮 Xposed system PowerKey/ContextualSearch 同路径实现，未作共享缺陷结论。未 fetch；Eta 来源仅是源码声明。AOSP 首次查询用了错误的 services/core 路径，返回不可用；上述引用改为搜索核对到的 services/contextualsearch 固定源码，仅据其中权限方法作比较。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ContextualSearchCallerPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/ContextualSearchCallerPolicy.kt) | 39 |
| [ContextualSearchHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/ContextualSearchHooks.kt) | 256 |
| [PowerKeyHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/PowerKeyHooks.kt) | 186 |
| [PowerKeyPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/PowerKeyPolicy.kt) | 30 |
| [ContextualSearchCallerPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/system/ContextualSearchCallerPolicyTest.kt) | 100 |
| [PowerKeyPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/system/PowerKeyPolicyTest.kt) | 44 |

累计全文 **1069 / 1546 个文件、214202 行**；partial 13，not_registered 464，excluded 124。全仓通读尚未完成；此前 full 的 HookGroups/AssistantLaunch 只片段复核不重复计数。

完成时核对全部 1670 tracked 路径、SHA-256、字节数和正数文本行数，本仓/固定上游 HEAD、tracked/staged 空 diff，此前 81 份报告哈希，本报告文件链接与行号，以及台账全文与批次总计。只写新报告和覆盖台账。
