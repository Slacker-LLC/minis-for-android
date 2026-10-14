# 旧所有权迁移模型与 Root Runtime 防回归审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮 **没有新增已确认生产缺陷**；全文阅读 6 个文件、479 行。只做静态排查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。独立报告，旧报告不改。

## OwnershipMigrationManager：旧声明不等于生产迁移

[OwnershipMigrationManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/migration/OwnershipMigrationManager.kt:7) 声称六阶段 FD-based 事务迁移，包含冻结旧进程、递归 openat/fchown、WAL、fsync、commit 与逆向 rollback。全文实际仅有常量/枚举、身份 stat 命令、输出解析、锁 JSON、JournalEntry 文本编码/解析、内存 rollback 列表与相对路径判断，没有执行这些阶段的 launcher。

src 中排除该对象和对应测试后检索其引用及 .migration_in_progress/.migration_committed 无命中。旧主报告 F04/旧清单已确认同一未接线声明，本轮补足全文覆盖，不重复计数。[07-OWNERSHIP-MIGRATION.md](/home/jiale/projects/minis-for-android/docs/contracts/07-OWNERSHIP-MIGRATION.md:32) 明确现役迁移在 UbuntuKernel.ensureReady，使用固定 source、cp/chown、App 私有 marker，不能宣称逐文件 WAL/rollback。现役 [UbuntuKernel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:661) marker 也是 .root-data-migrated-v1，与该旧模型不同。

纯 helper 的相对路径允许空字符串、JournalEntry 使用 tab 分隔而未对文件名 tab/newline 设编码、parse 丢弃坏行、resolveIdentityCommand 未引用 packageName，都是候选边界；对象无生产调用，不能把它们描述成现役 Root 命令注入或迁移丢文件。未执行 stat/chown，也没有操作旧 Root-owned 用户数据。

87 行已有测试验证 stat 字符串、App UID 下限、锁字段、rollback 过滤/逆序与相对路径否定值；没有执行 freeze/FD/WAL 迁移。不能用这些纯测试宣称事务迁移已经完成或崩溃恢复已通过。

## root.shell 参数入口与现役分层

[RootShellHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/RootShellHandler.kt:23) 定义 tool basename、args 数组、timeout_ms，无 raw command。execute 拒绝非 JSON、空 tool、非数组 args、非字符串/NUL 参数，再委托 PrivilegedCommandRunner，使用 rootOnly=true 和已有风险分类。未知 timeout 类型经 optLong 的语义还需 ToolTimeoutPolicy 一起核对；未凭单点转换报真实超时突破。

[PrivilegedCommandRunner.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/PrivilegedCommandRunner.kt:328) 的实际 argv 校验继续拒绝 slash/NUL、过多参数和过长 UTF-8 参数。工具 basename 的真实存在/受信目录解析、退出/超时/进程组清理边界在已有 runner 审查中处理；本轮没有把结构化 argv 等同“所有 Android system 工具都没有危险行为”，也没有重新计先前相关发现。

现役 [MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:540) 注册 RootShellHandler 与 shell_root 别名。handler 按 runner unavailableReason/timeout 分类失败；成功输出合并 stdout/stderr，按 exitCode==0 返回最终 success。没有为失败响应自动再次执行 Root 命令。

74 行测试检查 schema、local-only/MCP 隐藏、alias discoverability 和标准 Agent toolset；它们不执行 Root 或证明真实设备进程组回收。项目合同允许 local-only 的结构化 system tool 能力，本轮不擅自按旧通用禁 Root RPC 的描述移除该授权入口。

## 旧资产与包边界测试能证明什么

LegacyRuntimeAssetGuardTest 52 行只检查固定旧目录/文件不存在：assets/default_mount、assets/minisd-policy.json、runtime/minisd、runtime/distribution；本轮 tracked 路径检索也没有这些路径。它不检查所有字符串、二进制/压缩包内容、Gradle 生成物或其它重新命名的旧实现，不能凭四个路径不存在就宣称整个项目无遗留 minisd/PRoot。

RuntimePackageBoundaryTest 31 行检查现役类包名、兼容壳包名和 HOST_MINIS/HOST_ROOTFS 字符串，不启动 Ubuntu、不校验 UID/GID、mount 或实际 chroot 所有权。这些是代码边界守护，真实 Root/SELinux/OEM 行为仍未在本轮验证。现有测试只静态阅读，不新增或运行测试。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。树检索没有本轮 OwnershipMigrationManager、RootShellHandler、LegacyRuntimeAssetGuard、RuntimePackageBoundary 同名路径，不提供同路径对照。不能把上游旧 runtime 接回本地替代当前合同，也没有据此宣称上游完全没有 Root/migration 能力。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [OwnershipMigrationManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/migration/OwnershipMigrationManager.kt) | 132 |
| [RootShellHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/RootShellHandler.kt) | 103 |
| [LegacyRuntimeAssetGuardTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/LegacyRuntimeAssetGuardTest.kt) | 52 |
| [RuntimePackageBoundaryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/RuntimePackageBoundaryTest.kt) | 31 |
| [OwnershipMigrationManagerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/migration/OwnershipMigrationManagerTest.kt) | 87 |
| [RootShellHandlerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/RootShellHandlerTest.kt) | 74 |

累计全文 **1158 / 1546 个文件、225094 行**；partial 13，not_registered 375，excluded 124。全仓通读尚未完成。合同和已 full 调用方只复核，不重复计数。没有新增缺陷不意味着这些模块已完成设备验收。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 97 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，不访问真实 Root 数据、偏好、凭据或云资源。
