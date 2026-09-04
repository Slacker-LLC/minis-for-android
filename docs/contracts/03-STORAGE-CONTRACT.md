# 03 — 存储合同

这是 Linux guest 持久化的唯一真源合同。App 文件工具、minisd bind、session、附件与 guest 路径必须对齐同一组 host 数据源。

## 固定 host 布局

根：`/data/adb/minis`

| Host | Guest / 用途 |
|---|---|
| `/data/adb/minis/workspace` | 非 session 流程显式使用的 `/workspace` backing |
| `/data/adb/minis/sessions` | 每 session 的 workspace / attachments / offloads / browser 等 |
| `/data/adb/minis/memory` | `/memory` |
| `/data/adb/minis/skills` | `/skills` |
| `/data/adb/minis/shared` | `/shared` |
| `/data/adb/minis/home` | `/home/minis` |
| `/data/adb/minis/rootfs` | Ubuntu rootfs（可替换运行时，不是用户数据） |
| `/data/adb/minis/run` | broker/runtime 状态 |
| `/data/adb/minis/log` | broker/runtime 日志 |

用户数据目录 owner 使用设备实际 App UID/GID，不能写死 `10000`；用户数据目录 mode 为 `0700`。rootfs/run/log 使用 runtime 定义的 root/服务权限。

## 必须拒绝

- 把 App 私有目录或临时缓存当成 guest 持久化真源；
- tmpfs/tmpfs_data 作为 workspace、sessions、memory、skills、shared、home 的持久 backing；
- `ubuntu.start` 或其它运行时入口用替代路径重定义上述真源；
- `..`、NUL、符号链接或 canonical path 逃逸；
- session 路径跳出 `/data/adb/minis/sessions/<session_id>/`；
- rootfs 升级/回滚覆盖、删除或替换用户数据目录；
- App 文件层与 minisd 分别维护互不一致的 guest 真源。

## Session

有效 `session_id` 时，执行、文件访问与附件相关路径必须使用对应 session backing。Terminal、Agent shell、Markdown/媒体链接和文件工具如果表示同一 session，应看到一致的 workspace。

## Rootfs

`/data/adb/minis/rootfs` 可以由受控 runtime transaction 替换或回滚，但它不拥有用户数据生命周期。rootfs 损坏恢复不能通过删除 `/data/adb/minis` 整体完成。

## Ownership

启动/恢复时发现用户数据 owner 与当前 App UID/GID 不一致，应按 `07-OWNERSHIP-MIGRATION.md` 做受限、幂等的前向校正：只遍历允许根、拒绝 symlink follow、只修改不匹配项、中断后可重新运行。

当前合同不要求为属主校正建立逐文件 WAL、20% 额外空间、六阶段事务或逆向 owner rollback。除非出现明确的跨身份可逆迁移需求，否则不要引入这些机制。

## App 侧义务

Android 的 guest 文件解析、附件、备份/恢复、预览和 staging 必须明确区分：

- canonical guest/user data；
- App-local cache/staging；
- SAF 等外部授权域。

三者不能因为都“是文件路径”就互相替代。
