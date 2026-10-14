# Android 显示解析、手势与结果策略审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮 **没有新增已确认生产缺陷**；全文阅读 11 个文件、477 行。只做静态排查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。独立报告，旧报告不改。

## 显示与焦点解析

AndroidDisplaySizeParser 优先 Override size，再 Physical size，只有正数且可解析为 Int 的宽高才返回。parseOverride 只读 override。现役 [ShizukuOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:463) 调用对应方法，失败时 logicalWidth/Height=-1，并另列 dumpsys display 的 rotation。wm 的自然方向尺寸是否在旋转屏幕下等同截图/input 的当前坐标，还需协议及设备调用证据；不能用 parser 单独证明所有旋转/OEM/多显示器坐标正确。本轮不执行 wm 或 dumpsys。

FocusedWindowParser 优先 mCurrentFocus，后 mFocusedApp，跳过 null，提取组件再取 slash 前的包名。现役 [ShizukuOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:414) 仅在 dumpsys window 退出码成功时解析，解析失败输出 unknown，没有把包名子串匹配视为目标 App。组件正则与 OEM dumpsys 格式的覆盖范围需要具体返回值，未捏造未知格式证明误报。

## 手势与截图策略

GestureFallbackPolicy 只对 GESTURE_NOT_DISPATCHED 返回 true；方法名 mayFallbackToRoot 是历史名称，[AndroidUiController.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:277) 在节点 click 返回拒绝时走当前 service.dispatchGestureWithEvidence，没有在这段重新执行 Root input。本轮没有将方法名当作运行路径，也没有仅凭 performAction 返回值推断所有系统实现都提供副作用确认。

ScreenshotOutcomePolicy 按 requested/hasImage/complete 分成 NOT_REQUESTED、COMPLETE、PARTIAL、FAILED。mayFallbackToRoot 本身只有是否有排除包/关键窗口缺失的 Boolean 判定；[AndroidUiController.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiController.kt:245) 实际把它作为 complete 条件，标记被排除/未读窗口时 quality 为 partial，而非调用 Root 截屏。图片字节已存在但 offload 路径失败的结果要与 CO01 的现有保存失败记录区分，不能重复计数或宣称截图像素不存在。

ScrollAxisContract 在仅有对向轴证据时拒绝替代，legacy forward/backward 只在有垂直且无水平证据时认为垂直。ScrollGestureContract direction 表示内容方向，手指反向运动；取矩形内 20%/80% 点和另一轴中心，拒绝空/反向矩形及轴长小于2的范围。极大 Int 边界的减法溢出是孤立输入候选，真实屏幕 Rect 范围未支持这种输入，不直接计设备故障。

ShellActionOutcomePolicy 将 exit0、124、其它码分开。main/java 检索只有其定义，没有实际生产调用；不能把这份 helper 的三态分类描述成全部 shell 调用已正确接线的证据。现役具体 Root/shell deadline 仍由已有 runner 审查定义。

## 四份已有测试的证据范围

AndroidOperationRiskPolicyTest 39 行验证只读诊断、install/uninstall/clear/root setup 风险分类与 click 的可见副作用；它不实际触发审批或执行 Android 命令。

PackageWaitPolicyTest 64 行验证 appear/disappear、不可读/空前台为 UNKNOWN、大小写比较及未知 mode 的默认行为。它不模拟 OEM 焦点窗口或真实等待超时。

PrivilegedCommandRiskTest 85 行验证工具/参数风险、声明风险不降低、basename/NUL/数量/字节数边界及受信路径拒绝。RootProbeParserTest 81 行验证 su 缺失/待探测/拒绝状态、uid/gid/groups/caps/SELinux 文本解析，并拒绝从 exit0 推断 uid0。均全文静态阅读，未运行；不能证明真实 su 授权、SELinux 或进程组行为已通过。

检索 src/test 未发现七个本轮 helper 的直接引用，不代表没有其他形式的 UI/集成覆盖；四份测试涉及相关策略，不能拿来替代这七个 helper 的设备验证。没有为静态审查新增测试。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。树检索没有本轮七个 helper 同名路径，不提供同路径直接对照。Eta 来源仅按源码声明记录，不用“ported”保证实际 Android API 调用满足它的协议；没有恢复旧 runtime 或 Root replay。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [AndroidDisplaySizeParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidDisplaySizeParser.kt) | 18 |
| [FocusedWindowParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/FocusedWindowParser.kt) | 28 |
| [GestureFallbackPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/GestureFallbackPolicy.kt) | 10 |
| [ScreenshotOutcomePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/ScreenshotOutcomePolicy.kt) | 27 |
| [ScrollAxisContract.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/ScrollAxisContract.kt) | 23 |
| [ScrollGestureContract.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/ScrollGestureContract.kt) | 83 |
| [ShellActionOutcomePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/ShellActionOutcomePolicy.kt) | 19 |
| [AndroidOperationRiskPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/android/AndroidOperationRiskPolicyTest.kt) | 39 |
| [PackageWaitPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/android/PackageWaitPolicyTest.kt) | 64 |
| [PrivilegedCommandRiskTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/android/PrivilegedCommandRiskTest.kt) | 85 |
| [RootProbeParserTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/android/RootProbeParserTest.kt) | 81 |

累计全文 **1169 / 1546 个文件、225571 行**；partial 13，not_registered 364，excluded 124。全仓通读尚未完成。已 full 的 AndroidUiController/ShizukuOffloadHandler 只复核片段，不重复计数。没有新增确认缺陷不意味着所有策略设备验收完成。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 98 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，不读取真实屏幕、位置、包清单或用户数据。
