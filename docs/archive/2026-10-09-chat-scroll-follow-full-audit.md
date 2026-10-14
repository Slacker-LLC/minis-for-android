# 聊天滚动跟随、历史跳转与测试范围全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。ChatScreenScroll 730行分1—250、251—500、501—730完整阅读；UpButtonTurnWalkTest 223行完整阅读。未改产品源码、编写/运行测试、构建、模拟器、设备、Root或云资源。旧214份报告与全部台账保持原样。

## 当前自动跟随保护

nearBottom使用firstVisibleItemIndex=0与offset阈值；不依赖visibleItems窗口一定非空。contentOverflows合并canScroll、项目数量及可见项总高度。用户Drag开始清跳转链，结束记录中断时间及离底状态；ScrollEffect6在惯性滚动结束时再次检查离底，避免手指离开时仍近底而惯性随后移走的旧窗口。

stream增长信号合并canonical与overlay，涵盖文本长度、工具状态/种类/ID及等待标记；仅streaming、未主动离底、没有滚动且离中断至少1000ms才跟随。glide每帧有步长及120帧上限，cold无平均高度时直接定位。不能把其概括为每token无条件置底，也不能凭注释宣布所有帧率/实际设备体验合格。

流结束220ms后仅跟随者重pin，再900ms复查；历史阅读者不强制恢复。布局漂移处理和交互结束处理另有门槛。多个effect分别处理不同信号，实际竞争顺序需Compose/设备证据，本轮无运行验证。

## 向前用户轮跳转

先从当前可见key提取messageId并找当前user锚点；重复点击沿lastJumped继续，已完整可见的user跳过。目标不在视口时从row0逐段扫描当前layout的可见最大index，最多200次；失败恢复原index/offset，成功读取目标尺寸及padding计算落点。

因此极长展开历史中目标超过200个扫描窗口，按钮可能恢复原位而未找到；此上限确实存在，但尚未核验实际加载窗口、每message fragment数量和生产可达规模，保持候选。扫描期间布局/消息变化导致保存index失效、targetIndex变化也需要真实数据源交错，本轮不凭任意列表构造登记确认故障。

上批冻结前缀是否改变Markdown keys的候选仍在；当前流结束对历史阅读者不强制恢复，不补足全/分段转换等价性，也不证明一定可见跳动。沿此前候选继续处理，不新增重复项。

## 已有测试不能代替真实视口扫描

UpButtonTurnWalkTest的pickTarget是测试内复制，验证用户锚点、连续上翻、floor及key提取。没有调用生产Composable，也未包含生产fullyVisibleUserIds跳过规则。

seekRowIndex的新算法直接rows.indexOf(targetKey)，没有生产逐次tracedScrollToItem/测量、200扫描上限、动态layout或恢复操作。因此该用例能说明旧stride模型漏目标，不能证明当前真实扫描在所有场景都找到目标。正文注释中的设备历史是历史数据，不是本轮运行证据。测试和生产当前unknown topRow都fallback0，以实际源码断言为准，不按旧注释把它报成回退最新。

本轮新增确认缺陷0；两文件完成现版全文，候选与测试范围明确记录。未将rg/片段阅读计成额外全文。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenScroll.kt | 730 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/UpButtonTurnWalkTest.kt | 223 |

两个现版文件完整读取。新增全文2文件、953行；已全文重读0文件。其它rg定位不计全文。

累计全文1335 / 1758文件，203951行；partial 4，未登记419。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-scroll.json)。

校验旧214报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
