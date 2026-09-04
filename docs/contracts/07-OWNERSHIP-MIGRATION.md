# 07 — 属主校正与身份恢复合同

> 关联：`00-IDENTITY.md`、`03-STORAGE-CONTRACT.md`

本文件保留原文件名以避免旧链接失效，但当前合同不再要求“六阶段事务级属主迁移”。`applicationId = llc.slacker.minis` 已经是当前事实；这里解决的是 `/data/adb/minis` 用户数据 owner 与当前 App identity 不一致时，如何安全恢复。

## 目标

需要满足四件事：

1. 只修改 Minis canonical 用户数据；
2. 使用当前设备真实 App UID/GID；
3. 不跟随符号链接、不逃出允许根；
4. 中断后可以重新运行并继续收敛，不要求逆向回滚已经成功的 `chown`。

## 允许处理的用户数据根

只允许：

```text
/data/adb/minis/workspace
/data/adb/minis/sessions
/data/adb/minis/memory
/data/adb/minis/skills
/data/adb/minis/shared
/data/adb/minis/home
```

`rootfs`、`run`、`log` 使用各自 runtime 权限，不得因为用户数据 owner reconcile 被整体递归改成 App UID/GID。

## 当前 App UID/GID

必须从当前安装/runtime 的真实身份取得 UID/GID，并验证结果有效。任何文档示例数字都不是合同值；禁止写死 `10000`、旧安装 UID 或假定 UID/GID 恒定。

具体获取方式应优先复用现有 minisd/Android runtime 已经验证的 identity 来源，而不是再建立一套独立包名→UID 推断逻辑。

## 幂等前向校正

推荐流程：

```text
resolve current App UID/GID
  ↓
validate canonical allowed roots
  ↓
walk without following symlinks
  ↓
for each allowed entry:
  owner already correct → skip
  owner mismatch        → fchown/chown through safe existing primitive
  invalid/symlink/escape/error → fail closed
  ↓
optional completion/version marker
```

只修改 owner 不匹配项。已经正确的条目不需要重复写。

中途崩溃、断电或进程死亡后，下次启动从允许根重新扫描即可：之前已修好的条目会被跳过，未修好的继续处理。算法通过“重复执行得到同一最终状态”实现恢复。

## 路径安全

- 不跟随 symlink；
- 不接受 `..`、NUL 或 canonical escape；
- 递归遍历必须始终锚定在允许根；
- 如现有 native 层已有 fd-relative/openat 类安全遍历能力，应复用；
- 任一无法证明安全包含的条目必须失败关闭，而不是跳出 root 后继续 `chown`。

## 并发

属主校正期间不得让 guest 同时依赖一半旧 owner、一半新 owner 的不确定状态。可以复用现有 runtime startup/recovery 串行化或最窄排他锁，目标只是避免并发访问，不要求建立新的跨文件事务引擎。

## 可选完成标记

如果启动成本需要优化，可以保存一个简单的 layout/ownership version 或 completion marker。标记只能作为“已完成该版本检查”的优化，不能代替真实路径/owner 校验，也不能让错误 owner 永久跳过修复。

## 明确不要求

当前没有证据支持默认引入以下复杂机制：

- 六阶段 PREPARE/FREEZE/FD-MIGRATION/FSYNC/COMMIT/CLEANUP 状态机；
- 每个文件记录 old/new UID/GID 的 WAL；
- 逐项逆向 owner rollback；
- 为 owner 迁移预留 20% 数据量磁盘空间；
- 为一次 ownership reconcile 建立跨 Room/filesystem 事务框架。

如果未来出现明确需求，例如必须在两个 Android 应用身份之间**可逆**迁移同一份数据，再单独设计迁移协议并给出真实恢复测试；不要提前把该复杂度放进日常 runtime。

## 验收

- owner 已正确：重复执行不改变数据；
- 部分 owner 错误：只修不匹配项；
- 中途被终止：再次执行能收敛到正确 owner；
- symlink/escape：拒绝且不修改允许根外文件；
- session 深层目录：保持在 sessions root 内并完成校正；
- rootfs/run/log：不被用户数据 reconcile 错误递归 chown；
- 最终 UID/GID 来自当前实际 App identity，而不是固定数字。
