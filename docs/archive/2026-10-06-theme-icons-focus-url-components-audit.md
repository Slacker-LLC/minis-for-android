# 主题、图标、焦点滚动与URL注解静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文8文件、1180行。**本轮未确认新的生产缺陷**。只读产品代码，未运行测试、构建、模拟器、adb、Root、设备、云、模型请求或登录，未读取凭据；另建报告，旧报告不改。

## 主题设置有实际刷新链

[MainActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:520) 读取themeMode、uiStyle、accentIndex与appBaseLevel；SharedPreferences listener第525—538行更新对应Compose状态、dispose解绑，第568—572行传入MinisTheme。因此不能说强调色或App字号保存后必然要重启才生效。

[Theme.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/theme/Theme.kt:235) 实际调用withAccent，改变primary/onPrimary/container等；ChatPalette第237行仍来自固定Light/Dark调色板。minisFabColor第175行读取固定fabAccent，但全main搜索只有函数定义，无实际调用，不将它计作“现役FAB永远不跟随强调色”的缺陷。固定link/status配色不必然违背产品设置，须核对哪些元素明确承诺强调色。

withAccent第219行把onPrimaryContainer取另一主题的accent变体，可能产生某些低对比组合；实际main UI没有直接读取onPrimaryContainer，不能靠未使用色值推出用户看不清文本。SoulSettingsScreen第632行只用primaryContainer作为选中背景，需完整前景数据流，未新增确认缺陷。

scaledTypography第252—273行只缩fontSize，没有同比缩lineHeight；因此两者比例随App字号改变。但字体度量、lineHeightStyle和各调用方覆盖会影响实际渲染，未实测文字碰撞，不把公式差异当必然裁切。固定sp文本不跟随此Typography因素，也不意味着不跟随Android系统字体缩放。此前长内容/低高度报告不重复计数。

ChatColors全文201行：LocalChatPalette由MinisTheme提供；chatPagePalette仅在light下替换消息区域分层颜色，dark直接返回base，remember以base为key。没有确认内存泄漏或跨主题固定旧palette。minisSheetColor根据ChatColors.isDark取适当surface，scrim明暗规则明确。

## 焦点滚动与原有IME问题的边界

[BringIntoViewOnFocus.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/util/BringIntoViewOnFocus.kt:38) remember requester/scope，isFocused事件发起bringIntoView；没有自己增加imePadding，也没有在IME尺寸变化时显式重试。它依赖祖先滚动容器/IME布局语义。实际ProviderDetailScreen第174、271、361、1019、1125行和BrowserSettingsSheet第320、562、576行使用。这里不把“helper存在”当所有编辑页都可避让的证据，也不把未附加helper的所有字段都当已确认Bug。

onFocusEvent可能在焦点仍在时多次触发，scope任务生命周期属于composition；没有读到取消前一请求或延迟等候IME的逻辑。真实焦点与resize时序仍需验证，保留候选。不修改记忆文件编辑页，之前已确认UI01的缺口不重复新增。

## URL注解保留原始文本和回调边界

UrlLinkifier全文78行：WEB_URL匹配后只接受http/https scheme，跳过裸域名时不移动cursor，下一有效链接前append会带回全部间隙，因此不能说裸域名会从正文消失。每段LinkAnnotation记录candidate并通过onClick交给调用方，不会在该helper直接执行shell或访问任意非HTTP scheme。

[ChatToolDetailUI.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatToolDetailUI.kt:1500) 只有linkify且LocalMarkdownUrlClickHandler非null时调用，remember按shownText、urlClick、linkify缓存。可点击文字与URL同为candidate，没有独立隐藏target拼接。Patterns的标点、括号、Unicode或超长输入处理仍需具体平台样例，未猜测特定URL一定截断。本轮不把这里扩大成此前URLDecoder加号路径问题。

## 图标与测试覆盖

MinisIcons全文346行：lazy ImageVector，24dp viewport、固定描边几何、通用circle/roundRect helpers，More明确fill，其它path为stroke。SidebarPanelIcon全文50行按Canvas实际size缩放分隔线与圆角。图标不包含权限/导航/执行逻辑，具体按钮动作和无障碍名称由调用方提供。未因vector内部黑色path就说暗色图标必不可见：Icon通常另传tint。RTL镜像/图标辨识需结合实际界面要求，未新增布局故障。

InAppThemeSourceGuardTest全文79行检查isSystemInDarkTheme源码调用；UiDesignTokensTest全文104行检查指定palette/accent及四类背景对比、status/error一致性。只读未运行。它们不验证所有组合后的container前景、所有真实背景、glass叠加、实际字体、几何和焦点/IME，因此不能据这些测试代码存在宣称全产品视觉正确。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其tree有ChatColors、BringIntoViewOnFocus、UrlLinkifier同路径，没有MinisIcons、SidebarPanelIcon同路径。上游Theme第153—169行没有当前accent/uiStyle参数，第172—193行同样只缩fontSize；这仅说明该缩放规则继承，不能证明实际字体裁切或整套上游更正确。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [BringIntoViewOnFocus.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/util/BringIntoViewOnFocus.kt:1) | 48 |
| [UrlLinkifier.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/util/UrlLinkifier.kt:1) | 78 |
| [ChatColors.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/theme/ChatColors.kt:1) | 201 |
| [Theme.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/theme/Theme.kt:1) | 274 |
| [SidebarPanelIcon.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/SidebarPanelIcon.kt:1) | 50 |
| [MinisIcons.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisIcons.kt:1) | 346 |
| [InAppThemeSourceGuardTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/theme/InAppThemeSourceGuardTest.kt:1) | 79 |
| [UiDesignTokensTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/theme/UiDesignTokensTest.kt:1) | 104 |

全文输出完整，MinisIcons分1—180、181—346读取；其它已有full只片段复核，不重复计数。

累计全文 **1263 / 1546文件、241842行**；partial13，not_registered270，excluded124。全仓通读未完成。完成时核验全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前118份报告哈希、本报告链接行号与全文批次总计。只新建本报告并更新台账。
