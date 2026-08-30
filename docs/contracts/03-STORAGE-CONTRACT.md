# 03 — 存储合同

这是 Linux guest 持久化的**唯一**真源合同。App 文件工具、minisd bind、guest 路径必须指向同一组 host 目录。

## 固定 host 布局

根：`/data/adb/minis`

| Host | Guest / 用途 |
|---|---|
| `/data/adb/minis/workspace` | `/workspace` |
| `/data/adb/minis/sessions` | 每 session 的 workspace 等 |
| `/data/adb/minis/memory` | `/memory` |
| `/data/adb/minis/skills` | `/skills` |
| `/data/adb/minis/shared` | `/shared` |
| `/data/adb/minis/home` | `/home/minis` |
| `/data/adb/minis/rootfs` | Ubuntu rootfs（可替换，不是用户数据） |
| `/data/adb/minis/run` | broker 运行时状态 |
| `/data/adb/minis/log` | broker 日志 |

用户数据目录 owner 为 **真实 App UID/GID**（不是写死的 10000），mode `0700`。rootfs / run / log 使用 root 运行时权限。

## 必须拒绝

- 把 App 私有存储（含其隔离层）当作 guest 持久化真源
- tmpfs / tmpfs_data 作为上述用户数据目录的 backing
- `ubuntu.start` 传入与上表不同的 workspace / sessions / memory / skills / shared / home / rootfs
- 符号链接充当持久化源
- 先删当前可用 rootfs 再原地覆盖（升级必须原子，用户数据必须留下）

## Session

有效 `session_id` 时，minisd 在 `/data/adb/minis/sessions/<id>/` 下准备 `workspace`、`attachments`、`offloads`、`browser`，并保持同样的包含与所有权检查。

## App 侧义务

Android 的路径解析、文件工具、附件、备份必须通过与上表一致的 host 路径访问 guest 数据。禁止 App 写一套目录、minisd 绑另一套目录。

下一轮代码工作只做这件事：让 App 与 minisd 都遵守本表。现状见 `06-CURRENT-GAPS.md`。
