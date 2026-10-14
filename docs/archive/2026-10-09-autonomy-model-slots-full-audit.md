# 自主模式与模型槽位：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。仅静态阅读，未执行测试、配置写入或模型请求；只新增报告与覆盖台账。未新增已确认缺陷。

## 自主模式默认值变化

现版 AutonomyMode.DEFAULT 明确为 FULL；缺失存储值返回 FULL，未知非空字符串返回 SMART。旧[自主模式报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-autonomy-permission-grants-audit.md)所写缺失值回落 SMART 已不是现状。已有测试直接期待 null→FULL、未知字符串→SMART，不把明确默认策略改变擅自列成安全Bug。

ASK 对所有配置变更确认；SMART 对秘密字段或非 NORMAL 风险确认；FULL跳过此处确认。此处只是配置确认策略，不等于任何工具权限都绕过。MinisApp 第538行确实初始化，设置页第432行调用 setMode；两者只定位，不计全文。

Store 先保存 prefs 引用，再读取模式；setMode 的持久化调用和 StateFlow 更新分开。已有测试只覆盖纯函数，未验证初始化、真实存储异常、进程重启和写入失败。对于“unreadable 回落 SMART”描述，只能确认未知字符串被处理，不能概括所有读取异常。未构造损坏文件，不新增启动崩溃结论。

## 模型槽位写入

ModelSlotsCollection 拒绝增删固定槽位。entries writer 要求字符串数组、按ID去重、逐项检查兼容后调用 repo.setSlotEntries；普通条目必须存在且非隐藏，分别校验文本／图像输入输出／音频输入输出。系统虚拟语音条目只可进入相应音频槽。

本层没有要求 Provider 已启用或有凭据，这不自动等于误选：允许保存暂不可用配置和运行时可用模型筛选是不同职责，必须结合后续解析才判问题。兼容检查使用一次 config 快照，随后的仓库写入需要进一步核对并发更新语义；本轮不把局部快照差异直接判成丢配置。

字段标记 SENSITIVE，与 SMART 的非 NORMAL 确认规则一致；revertable 属性不证明所有并发回滚都正确。description 列举遗漏 image 槽，仅记录描述范围，不重复登记注释噪音类问题。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/config/AutonomyStore.kt | 69 |
| src/android/app/src/main/java/com/openminis/app/config/collections/ModelSlotsCollection.kt | 99 |
| src/android/app/src/test/java/com/openminis/app/config/AutonomyPolicyTest.kt | 44 |

三个现版文件完整读取。新增全文3文件、212行；已全文重读0文件。其它rg定位不计全文。

累计全文1369 / 1758文件，209174行；partial 4，未登记385。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-autonomy-slots.json)。

校验旧227报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
