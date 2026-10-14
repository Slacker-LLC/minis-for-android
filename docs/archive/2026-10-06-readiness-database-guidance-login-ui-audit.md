# 就绪检查、数据库版本引导与登录UI静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。全文读取5文件、777行。**本轮未确认新的生产缺陷**，记录已核对行为、候选及证据边界。没有修改产品源码，没有运行测试、构建、模拟器、设备、Root、权限请求、浏览器登录、云或模型请求，未读取凭据。旧报告不改。

## 就绪检查：可选项、重新探测和修复按钮

[SettingsReadiness.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsReadiness.kt:75) REQUIRED仅Root、AllFiles、Background、Notifications；attention只计缺少必需项，其余可选缺失仍列在结果弹窗。这是明确产品规则，有测试覆盖，不把缺Shizuku/Accessibility却显示ready直接判为错误。

probe按API读取适用权限，工具被Agent权限设置关闭时不列该工具的Android授权项；location任一fine/coarse满足，photos允许READ_MEDIA_IMAGES或VISUAL_USER_SELECTED。此photos就绪口径不代表RI02的ReadImageTool读取入口也接受单URI授权：两个实现不同，已存在工具权限缺口不能由就绪勾选证明消失，本轮不重复计数。

rememberReadiness第155—163行注册ON_RESUME observer、dispose移除，root.isGranted也作为remember key；返回系统页会重新探测。ReadinessCheckSection第261行保存一次检查结果，但第305—307行fix动作明确先result=null关闭弹窗再跳转。因此不能指称用户授权返回后同一弹窗必然停留旧结果。外部在弹窗仍打开期间改变状态没有自动刷新，是否需要实时结果是候选，不按单次快照规则强行算Bug。

修复跳转分别处理Root、Shizuku状态、all-files、accessibility、overlay、role、后台、电量、通知及应用权限页。startSettings捕获无目标设置页异常，用户可能得不到明确失败提示；实际OEM无页面/备用入口需进一步证据，不说所有跳转都必然失效。全部只读，未触发系统授权。

Shizuku、OffloadPermission等变化不单独作为remember key，若在同一composition无resume变化，banner可能保持旧值，仍需具体内部设置回程生命周期证明。RootAccess检查返回授权状态，与guest是否安装/能联网不是同一概念，不把“权限就绪”当Ubuntu全部健康证明。

SettingsReadinessTest全文75行验证顺序、所需项、必需四项、可选不计数、不适用项省略；它不测试实际Android探测或系统Intent。只读未运行。

## 新数据库版本引导入口

NewerDatabaseGuidanceScreen全文58行，仅展示版本说明并提供退出按钮，没有删除、重置或降级数据库操作。[MainActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:248) 在SHOW_NEWER_DB_GUIDANCE决策时setContent，第254行立即return，退出调用finishAndRemoveTask。不能把此页面简短等同于会继续初始化旧schema或清空新数据。

该页面不接收真实数据库版本参数，只显示CODE_DB_VERSION；这可能降低诊断信息，但没有承诺两版本都显示，不新增缺陷。底层DatabaseVersionGuard及初始化链已在此前覆盖，本轮只核对真实页面入口，不重复计数或调用数据库。低高度/大字体可达性仍需布局证据，未将所有未滚动页面直接判为RM01同等问题。

## Kimi设备码对话框及取消

[KimiDeviceLoginDialog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/KimiDeviceLoginDialog.kt:57) 由外部传userCode/verificationUrl；复制使用当前userCode，显示copied标志，verification按钮直接CustomTabsIntent.launchUrl。ProviderDetailScreen第867—873行onCancel取消kimiLoginJob并清auth；AddProviderScreen第664—670行相同。dialog自己无轮询，不能说关闭对话框必然留下一份它自己的轮询任务。

复制标志remember无userCode key，若同一次composition替换设备码，copied可能沿用；需要实际换码时序，未新增错误复制缺陷。浏览器launch没有局部try/catch和无浏览器fallback，默认桌面/设备浏览器通常存在，特定无handler设备失败仍为候选，未打开浏览器或测试登录。verificationUrl来自设备授权对象openUrl，不拿任意假设URI扩大成已确认远程命令入口。

## Agent Loop模型添加页

AddAgentLoopModelsScreen过滤已pin/hidden模型，沿config.modelEntries稳定顺序提交selectedIds交集，逐项addAgentLoopEntry。selection集合在后台配置变化时可能包含已不可选项，按钮计数仍来自selectedIds.size而实际提交只取availableEntries；需真实配置更新入口与时序，不将假设输入算确认“丢模型”。ProviderRepository第1466—1469行对已存在ID不重复添加，不能说重复确认必然复制条目。

availableEntries没有在此页按text输出筛选，AgentLoop用于跨模态model.use选择，不能套主slot的Text筛选规则认定音频/图片模型一律不该加入。禁用Provider在管理页展示也是预配置设计，需要运行时解析证明，未新增一项因候选可见就断言被运行的错误。逐项写配置潜在性能/并发旧集合问题仍为待核对边界，未执行模型测试。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其tree有NewerDatabaseGuidanceScreen、AgentLoopAddSheets、KimiDeviceLoginDialog同路径，没有SettingsReadiness同路径。上游KimiDeviceLoginDialog第143行同样直接launchUrl，作为候选处理差异对照，不以共同未catch证明正常设备一定崩溃。

## 全文覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [NewerDatabaseGuidanceScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/NewerDatabaseGuidanceScreen.kt:1) | 58 |
| [KimiDeviceLoginDialog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/KimiDeviceLoginDialog.kt:1) | 161 |
| [AgentLoopAddSheets.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/AgentLoopAddSheets.kt:1) | 136 |
| [SettingsReadiness.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SettingsReadiness.kt:1) | 347 |
| [SettingsReadinessTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/settings/SettingsReadinessTest.kt:1) | 75 |

SettingsReadiness按1—200、201—347阅读，合并输出提示截断后独立重读201—347。其它完整输出；未升级其它partial。

累计全文 **1236 / 1546文件、236759行**；partial13，not_registered297，excluded124。全仓通读未完成。完成时核验全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游HEAD、tracked/staged空diff、此前114份报告哈希、本报告链接行号、全文批次总计。只新建本报告并更新台账。
