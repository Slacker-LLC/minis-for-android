# 07 — 六阶段事务级属主迁移协议 (Ownership Migration Contract)

> 规范依据：`docs/contracts/00-IDENTITY.md` 与 `docs/contracts/03-STORAGE-CONTRACT.md`

## 背景与问题

当 Android 包名（`applicationId`）从 `dev.openminispet.android` 切换至 `llc.slacker.minis` 时，Android 系统会将新应用视为一个**全新的应用身份**，并由底层分配全新的 Linux UID 与 GID（例如旧包为 `10100`，新包为 `10245`）。

而 Minis for Android 的核心持久化真源固定在 `/data/adb/minis`。粗暴的 `chown -R` 存在非原子性、并发符号链接逃逸漏洞，且在断电或异常中止时无法得知哪些文件被修改，导致不可逆的不可控损坏。

## 六阶段事务协议

```text
1. PREPARE ──► 2. FREEZE ──► 3. FD-MIGRATION ──► 4. FSYNC ──► 5. COMMIT ──► 6. CLEANUP
 (动态查 UID)   (加排他锁)    (openat+fchown+WAL) (落盘元数据) (写Commit标记)  (释放锁/解冻)
```

### 1. Stage 1: Prepare (环境预检与 UID/GID 动态获取)
- 通过内核文件系统对新包私有目录执行 `stat -c "%u %g" /data/user/0/llc.slacker.minis` 获取真实分配的 UID 与 GID，杜绝硬编码或仅依赖 `pm list packages -U`（后者不提供 GID 证明）；
- 校验 `/data/adb/minis` 分区剩余磁盘空间（大于当前数据量 20%）。

### 2. Stage 2: Freeze Old Access (冻结访问与排他锁)
- 停止旧应用服务并终止 guest `minisd` 守护进程；
- 创建 `/data/adb/minis/.migration_in_progress` 排他锁文件，包含源包名、目标包名、分配 UID/GID 和时间戳元数据。

### 3. Stage 3: FD-Based Ownership Migration (基于文件描述符的递归迁移与 WAL 日志)
- 使用 `openat` / `openat2` 配合 `O_NOFOLLOW` 递归打开目录与文件；
- 对已打开的实体文件描述符调用 `fchown`，杜绝 TOCTOU 路径替换逃逸；
- 记录逐项迁移日志 WAL（`.migration_journal.log`）：
  ```text
  relative_path 	 old_uid 	 old_gid 	 new_uid 	 new_gid 	 status
  ```

### 4. Stage 4: Fsync (元数据强制落盘)
- 对 `/data/adb/minis` 及其所有子目录调用 `fsync()`，确保文件系统元数据与 WAL 日志持久化到物理介质。

### 5. Stage 5: Commit Marker (事务提交标记)
- 原子写入并 `fsync` 标记文件 `/data/adb/minis/.migration_committed`。

### 6. Stage 6: Cleanup & Release (收尾与解冻)
- 移除 `.migration_in_progress` 锁文件与 `.migration_journal.log`；拉起新包 `llc.slacker.minis`。

## 断电自愈回滚机制 (Crash Self-Healing & Reversible Rollback)

若在 Stage 5 之前发生任何异常、崩溃或设备掉电：
1. 系统启动时检测到存在 `.migration_in_progress` 但**缺少** `.migration_committed`；
2. 读取 `.migration_journal.log`，提取所有标记为 `MIGRATED` 的条目；
3. 按逆序（自底向上）对已修改文件执行精确的 `fchown(fd, old_uid, old_gid)` 逆向回滚；
4. 回滚全部成功后清理未完成锁，向用户明确报告迁移中断并维持 fail-closed 安全边界。
