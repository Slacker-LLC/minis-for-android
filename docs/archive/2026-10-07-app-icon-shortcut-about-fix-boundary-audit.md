# 应用图标、快捷方式与关于页修复边界复核

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。本轮完整静态阅读4文件、355行。旧173份报告和全部覆盖台账原样保留，另建本文与台账。未修改代码、编写或执行测试、构建、启动模拟器、操作adb/Root/设备或云资源。

## BG10原报告路径已修复

[AppIconRepository.kt:65](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/AppIconRepository.kt#L65) apply先调用switchAliases，只有全部成功才持久化选择并返回true。[AppIconRepository.kt:83](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/AppIconRepository.kt#L83) 先启用目标，再禁用其它alias；异常后尝试恢复原选择，返回false。[AppearanceScreen.kt:441](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/settings/AppearanceScreen.kt#L441) 页面仅在apply返回true时更新selectedAppIcon。原来的先改变选中状态、忽略失败，以及禁用旧入口后才启用目标的路径均已改变。

回滚明确是best effort，恢复过程的异常被忽略，不能将此修复表述为任何PackageManager持续故障下均保证恢复。current仍只读偏好，目标等于偏好时直接返回；进程在alias切换和偏好保存间退出的状态恢复是后续候选，本轮没有证明真实设备触发或启动补偿缺失，不新增问题编号。

Manifest的三个alias均target MainActivity且包含快捷方式metadata，默认Auto启用。ComponentName使用实际Context包名加namespace类名；com.openminis.app不是在此处冒充applicationId，不报包名错误。

## 现有图标测试的证据范围

[AppIconSwitchTest.kt:21](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/repository/AppIconSwitchTest.kt#L21) 三例直接调用实际switchAliases并注入Fake状态setter：验证目标先启用、第二次调用失败后恢复、首次调用失败后恢复。Fake只在指定一次调用失败，后续恢复都成功；不能证明恢复调用再次失败或进程死亡后的系统状态。没有运行这些测试，也没有把Fake当成真实launcher或Release设备证据。

## WA04仍在：快捷方式请求成功被当成固定完成

[ShortcutPinner.kt:54](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/webapp/ShortcutPinner.kt#L54) 仍将requestPinShortcut的Boolean直接返回，并传入null结果回调。现役[AddToHomeSheet.kt:424](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/webapp/AddToHomeSheet.kt#L424) 在true时显示webapp_pin_success，随后关闭表单。源码没有新增固定完成确认。因此沿用旧WA04，不另编号或重复计数；旧报告对平台请求接受与用户实际确认的区分仍适用。

ShortcutPinner已检查launcher支持，系统调用异常会反馈失败；显式WebAppActivity Intent携带实体ID，使用NEW_TASK|CLEAR_TASK，不能仅因Activity复用模式断言普通固定快捷方式必定打开旧内容。快捷方式构造在runCatching外，但当前title的上游校验需结合完整创建链，未仅凭异常捕获范围新增崩溃结论。

## 关于页

[AboutScreen.kt:104](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/settings/AboutScreen.kt#L104) 三项链接直接调用openExternalUrl，更新入口调用CheckUpdateSection，版本取BuildConfig；openUrl兼容入口也委托现有helper，没有在此新增独立浏览器或下载器。这里只确认接线，不宣称远端链接或更新下载已实测。

页面图标由getApplicationIcon转换并remember(context)，没有订阅alias选择；关于页是否要求显示所选launcher图标没有明确合同，本轮不将视觉差异强行定为新缺陷。没有发现本轮足够证据支持的其它新增问题。

## OpenMinis固定对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，以GIT_NO_LAZY_FETCH=1完整读取既有AppIconRepository Git对象，没有fetch。上游按枚举顺序逐个设置alias，失败只记录日志返回false，没有当前switchAliases的目标优先和回滚。当前修复真实改善旧实现，不能以旧版本继续判BG10未修。此对照不计入当前仓库全文覆盖。

## 覆盖与保留

| 本轮新增全文 | 行数 |
|---|---:|
| [AppIconRepository.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/data/repository/AppIconRepository.kt#L1) | 97 |
| [AboutScreen.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/settings/AboutScreen.kt#L1) | 158 |
| [ShortcutPinner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/webapp/ShortcutPinner.kt#L1) | 57 |
| [AppIconSwitchTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/repository/AppIconSwitchTest.kt#L1) | 43 |

上述文件从首行到末行完整读取。AppearanceScreen、AddToHomeSheet和Manifest只复核相关片段，不升级全文覆盖。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-icon-shortcut.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-icon-shortcut.json)。累计全文 **1298 / 1655文件、215339行**；partial5、not_registered352、excluded124，tracked1779。全库通读仍未完成。

交付前校验旧173份报告及全部台账hash、当前本地/远端/上游HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines及证据行号。只新建本轮报告和台账，不覆盖以前文件。
