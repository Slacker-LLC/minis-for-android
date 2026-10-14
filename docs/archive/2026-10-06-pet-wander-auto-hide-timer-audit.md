# 宠物移动、动画和隐藏计时静态审查

日期：2026-10-06。本仓固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P3 确认缺陷**，全文阅读4个文件、498行。只记录问题，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务；旧报告保持不变。

## PB01 / P3：自主巡游不断重置自动隐藏倒计时

界面 [PetControlScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetControlScreen.kt:213) 显示“半分钟没互动就缩到屏幕边上”。现役服务 [PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:632) 同时加载 wanderEnabled 与 autoHideEnabled，已全文读取的 PetPreferences 让两项默认均为 true。

[PetBehavior.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetBehavior.kt:139) rescheduleIdleTimers 每次先取消 wanderTimer 和 tuckTimer，再把巡游排在7—18秒后、隐藏排在30秒后。stepWander 在不能移动/Agent忙/位移太小时也调用此方法；成功移动则在 [PetBehavior.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetBehavior.kt:182) 的动画完成回调再调用。动画时长 [PetBehavior.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetBehavior.kt:223) 上限9秒。

在正常及时处理回调、屏幕持续点亮、两项均开启、无用户互动的情况下，从一次重排开始，下一次巡游最多不到18秒触发；立即跳过时马上重排，正常移动时最迟约27秒完成并重排。每次都在此前30秒隐藏期限之前取消 tuckTimer。宠物自己动了一次，就被计作重新开始“用户闲置”，倒计时可以一直无法走完。

静态时间线：t=0安排巡游t=10和隐藏t=30；巡游在t=12结束，取消t=30并改为t=42；下次巡游在t=22开始、t=24结束，再改为t=54。用户从头到尾未互动，自动隐藏仍不断延后。数字只是允许范围内的源码反例，不是实测设备日志。极端 Handler 阻塞可能改变回调时序，本项不主张任何调度环境绝对不可能执行 tuck。

关闭巡游后，30秒隐藏计时器可以单独触发；[PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:691) 的菜单手动隐藏直接 tuckNow，也不依赖这段30秒定时。本项不能泛化成所有隐藏功能失效。忙碌时主动不隐藏是现有设计，确认缺口是在真正空闲且没有互动时，自主行为也不断刷新用户闲置期限。

方向：区分用户互动与自主动作的时间基准，巡游结束只安排下一次巡游，或按最后一次真实互动保留隐藏期限；继续使用现有 Handler 即可。未实施修改或新增测试。

## 动画、位置和暂停核验

PetBehavior 的动画用 uptimeMillis，未用墙上时钟；stop 移除 animation/wander/tuck 回调，pauseTimers 保留 isTucked，resumeTimers 对未隐藏状态恢复定时。取消动画并不执行 onEnd；互动后视觉状态如何恢复需要结合服务瞬态状态与手势，未只凭省略回调认定永久卡死。

PetOverlayGeometry clamp 将负位置与超出视口的坐标限制到有效范围；过大内容定位原点以保留可达区域。nearestHorizontalEdge 按中心选择最近水平边缘。本轮没有拿整数极值溢出输入当作真实屏幕尺寸，也未证明全部多显示器/旋转/OEM窗口坐标都正确。

PetAgentStateResolver 从人类可读状态字符串按 failure、review、wait 再 activeSessions 解析，可能受业务文本影响；没有具体正常生产状态串反例时，不另计误分类缺陷。

PetSpriteView 的 tick 在未 attached 或 paused 时退出，detach 移除回调并将 bitmap 置空后 recycle。setState 会重置帧并重排，实际暂停状态仍由 tick 检查。loadPet 先回收旧 bitmap 再解码；服务 [PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:581) 创建新 view，在IO线程解码成功后才挂载，因此不能把通用 loadPet 方法直接定性成该服务正在展示已回收 bitmap。设置预览也调用 loadPet，后续仍需完整核验其重复加载和异常处理。

spriteSampleSize 按144dp基准和密度下采样，未纳入用户实际scale；这可能影响分辨率与内存取舍，但未测量质量或内存峰值，不另计确定故障。onDraw 按解码后 bitmap 的网格计算帧区域，已经避免继续使用原始尺寸裁切采样后的图。

## 已有测试与固定上游对照

PetOverlayGeometryTest 全文55行，验证换大宠物重夹位置、过大宠物原点、最近水平边和四类状态解析。没有直接验证巡游/隐藏计时器；只是静态阅读，未运行。这些几何断言不证明 PB01 的闲置功能通过。

只读已有 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的 Git 树，未 fetch；上游没有本轮 pet 目录同路径代码，不提供直接同路径修复对照，也没有恢复 PRoot/minisd 或调用桌面宠物插件。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [PetBehavior.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetBehavior.kt) | 252 |
| [PetOverlayGeometry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayGeometry.kt) | 56 |
| [PetSpriteView.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetSpriteView.kt) | 135 |
| [PetOverlayGeometryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/pet/PetOverlayGeometryTest.kt) | 55 |

累计全文 **1180 / 1546 个文件、227174 行**；partial13，not_registered353，excluded124。全仓通读仍未完成。两组独立读取结果均完整，所有返回均已检查；PetControlScreen/PetOverlayService 只片段复核，不登记全文。

完成时核对全部1670 tracked路径 SHA-256、字节数与正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前101份报告哈希、本报告文件链接/行号及批次总计。只新建本报告并更新覆盖台账。
