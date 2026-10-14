# 确认框正文空间、菜单与提示消息静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文6文件、1021行，确认1项布局问题。只做静态阅读，没有修改产品源码，没有运行测试、构建、模拟器、adb、Root、设备、云或登录请求，未读取凭据。新建独立报告，旧报告不改。

## DL01 / P3：字符串确认框长正文挤占动作区域，无法滚动到确认按钮

[MinisAlertDialog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisAlertDialog.kt:92) 把卡片高度限制为screenHeightDp的85%；第118行容器只是Column。字符串重载第176—203行先布局title/text，正文没有scroll、maxLines或weight限制，随后第206—225行才布局按钮，没有给动作区预留空间。后布局动作会被前面的长正文耗尽可用高度。

真实入口：[ProviderListScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderListScreen.kt:363) 删除Provider确认用字符串重载，text通过provider_detail_delete_provider_confirm插入target.label。[AddProviderScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/AddProviderScreen.kt:365) 名称字段直接接受输入，没有长度截断；第577行只以ifBlank替换空名称。ProviderRepository.addInstance第849—852行保存instance，未限制label长度。合法用户输入的长名称可以进入真实删除确认正文，不依赖假设无效数据库或未调用组件。

静态复核场景：保存一段足够长、能在有限卡片宽度换行的Provider名称，例如数千个中文字符；然后在Provider列表滑动删除。正文测量使用卡片剩余高度，按钮随后无可用正常行高；卡片无纵向滚动入口，不能滑到确认动作。低高度横屏、分屏或字体放大让阈值更低。这里不声称每一个普通短确认框都有问题，也不声称所有按钮必在某个精确像素位置裁切；确认的是长正文与有限高度下没有保留动作的布局路径。

字符串重载和slot重载不同：第255行slot标题/正文区域有weight(1f, fill=false)，会为其后的非weighted按钮让出空间。不能把本项直接泛化到所有slot弹窗。slot正文仍未统一滚动，具体调用方可以自行给滚动容器，需要逐个评估。

修复方向：字符串正文区域可滚动，并限制它只使用扣除动作后的剩余空间，确保确认/取消可达。无需修改Provider存储语义来掩盖通用组件缺口。本轮没有实施。此前RM01是AssistantMessageMenu整列无滚动，本项是不同的通用确认框及实际删除入口，不重复记忆编辑页IME问题。

## 固定OpenMinis对照

只读已有 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游MinisAlertDialog总123行，第62—120行也用Column按title、text、动作顺序布局，没有正文滚动或动作预留；没有本地同样的85%卡片高度代码，不能说两边精确裁切阈值相同。可确认上游片段也未提供此字符串布局的可靠动作保留方案，不拿旧UI整体作为本地修复模板。

上游MinisMenu第127—143行与本地第133—149行同样按物理左右和窗口范围计算位置。其tree没有MinisToast、MinisBanner、MinisActionSheet这三份同路径文件，不等于整个上游完全没有提示或动作表实现。

## 菜单、动作表与横幅核对

MinisMenu全文255行：Popup默认focusable=true；expanded=false直接return，退出动画配置不会让已移除Popup继续停留，这与注释声明的即时关闭一致。内部Column第204行有verticalScroll，不能套RM01断言所有长菜单都无滚动。位置计算对x/y做窗口钳制，popup较大时实际约束和系统window行为尚未实测，未宣称所有大菜单溢出屏幕。

calculatePosition收到layoutDirection却未使用，始终把alignEnd当物理右边，offset.x也不随RTL反转；Manifest第194行supportsRtl=true，SessionListScreen第738行用默认MinisMenu。物理定位与逻辑start/end行为存在差异，但alignEnd注释明确指RIGHT边，是否需要全菜单RTL镜像仍应对照界面要求，不把这个差异直接新增故障。动画origin始终取上方角，即使菜单翻到anchor上方也不改变；只记录效果差异，不按功能失败计数。

MinisActionSheet全文134行：actions点击先onDismiss再action.onClick，取消直接onDismiss，卡片内背景点击空回调防止整卡误关闭。没有状态或异步任务管理职责。动作列无scroll，理论长action列表可能超高；真实调用方是短菜单，未构造无限actions增加新缺陷。没有统一的显式背景scrim代码，但Android Dialog可有窗口dim，未实测不能说必然没有遮罩。

MinisBanner全文128行：actionLabel与onAction均非空才显示动作；MinisEmptyState同规则，未发现空callback被调用。正文权重和动作布局保留，字号/触摸面积、大字体行为仍需要可达性证据，未把视觉padding直接等同实际touch target或所有布局都溢出。

## Toast生命周期候选：没有把附着等同可见的证明

[MinisToast.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisToast.kt:42) 全局SharedFlow、8条extra buffer与hosts计数。show在hosts>0且tryEmit成功时不调用系统Toast；MinisToastHost的DisposableEffect只在composition进出时增减hosts，第67—71行每条展示1800ms，未显式绑定STARTED/RESUMED。

真实宿主 [MainActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:626) 位于App root composition。ImageGalleryViewer第304—311行异步图片保存完成后调用show。需要进一步验证Activity退后台但composition保留期间的collector、计时与回前台行为：附着计数不能单独证明消息当时可见，可能导致反馈在不可见窗口消费。然而本轮未执行Activity生命周期或设备实验，保留为候选，不计成已实测丢提示，也没有声称后台Service全部使用MinisToast。

SharedFlow默认无replay，hostAttached到collect建立的短窗口、缓冲满后的fallback也值得核对，但未找到具体事件时序保证会丢，故不新增另一项。多个host可能同时消费，但当前main只有一处host入口，不能将假设多Activity当已存在重复提示。

## 源码规则测试的边界

MinisDialogUsageGuardTest全文114行，只检查源码中原生AlertDialog/ModalBottomSheet调用与导入、某些sheet配色正则。它不验证长正文测量、按钮可达、实际窗口dim、RTL、动画或后台消息消费。本轮只读，未运行或新增测试，也没有因源码规则存在就认定布局正确。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [MinisAlertDialog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisAlertDialog.kt:1) | 295 |
| [MinisActionSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisActionSheet.kt:1) | 134 |
| [MinisMenu.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisMenu.kt:1) | 255 |
| [MinisBanner.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisBanner.kt:1) | 128 |
| [MinisToast.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/components/MinisToast.kt:1) | 95 |
| [MinisDialogUsageGuardTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/components/MinisDialogUsageGuardTest.kt:1) | 114 |

合并输出两次提示截断，MinisMenu全文与AlertDialog181—295已独立重读；Toast、Banner与测试全文合并重读输出完整。所有新增full均有完整输出，其它片段文件不升级覆盖。

累计全文 **1255 / 1546文件、240662行**；partial13，not_registered278，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前117份报告哈希、新报告链接行号与全文批次总计。只新增本报告并更新覆盖台账。
