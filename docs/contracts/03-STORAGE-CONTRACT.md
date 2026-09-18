# 03 — 存储合同

这是 direct Ubuntu runtime 的现役存储合同。App 文件工具、session、附件和 guest bind 必须对齐同一组 App-owned backing；Root-owned rootfs 与用户数据分离。

## 现役 host 布局

以下路径均从当前 App 的 `Context.filesDir` 派生，不把绝对 Android data 路径写死为跨安装合同：

| Host backing | Guest / 用途 |
|---|---|
| `<filesDir>/minis/workspace` | 非 session 流程显式使用的 `/workspace` |
| `<filesDir>/minis-sessions/<session_id>/workspace` | session `/workspace` |
| `<filesDir>/minis-sessions/<session_id>/{attachments,offloads,browser}` | session 附件/异步产物/浏览器数据 |
| `<filesDir>/minis-global/memory` | `/memory`、`/var/minis/memory` |
| `<filesDir>/minis-global/skills` | `/skills`、`/var/minis/skills` |
| `<filesDir>/minis-global/shared` | `/shared`、`/var/minis/shared` |
| `<filesDir>/minis-global/mcp-servers` | `/var/minis/mcp-servers` |
| `<filesDir>/minis/home` | `/home/minis` |
| `/data/adb/minis/rootfs` | Ubuntu rootfs；Root-owned、可替换运行时，不是用户数据 |

这些 App-owned 目录使用当前安装实际 App UID/GID。禁止写死 `10000`。

## 旧 Root-owned 数据

历史版本可能存在：

```text
/data/adb/minis/workspace
/data/adb/minis/sessions
/data/adb/minis/memory
/data/adb/minis/skills
/data/adb/minis/shared
/data/adb/minis/home
/data/adb/minis/mcp-servers
```

它们只作为 legacy migration source。启动时 direct runtime 可以把内容复制到上述 App 私有 backing，并把目标 owner 校正为当前 App UID/GID；成功后写入 `<filesDir>/minis/.root-data-migrated-v1`。迁移源不能继续作为现役 guest 真源，也不能覆盖更新后的 App-owned 数据。

## 必须拒绝

- 把旧 `/data/adb/minis/*` 用户目录重新定义为当前 runtime backing；
- 把 cache/staging 当成持久 guest 真源；
- session 路径跳出对应 `<filesDir>/minis-sessions/<session_id>/`；
- `..`、NUL、canonical escape；
- rootfs 升级/回滚覆盖、删除或替换 App-owned 用户数据；
- Android 文件层与 Ubuntu bind 使用互不一致的 backing。

## Session

有效 `session_id` 时，执行、文件访问、附件、offload 和 browser 路径必须使用对应 session backing。Terminal、Agent shell、Markdown/媒体链接和文件工具如果表示同一 session，应看到一致的 workspace。

## Rootfs

`/data/adb/minis/rootfs` 可以由受控 runtime repair 替换。rootfs 损坏恢复不能删除 App 私有用户数据，也不能通过整体清空 `/data/adb/minis` 来“修复”。

## SAF 外部目录

SAF 授权目录是独立信任域。当前 direct runtime 从持久 SAF grant 解析实际 host 路径，并只在 per-shell mount namespace 中 bind 到 `/var/minis/mounts/<name>`。read-only grant 必须以 read-only bind 进入 guest。

App 文件解析对 SAF external mounts 必须重新从 grant 解析，不能把它们塞进普通 App-owned `File` alias 或假设与 rootfs 同一权限域。

## App 侧义务

Android 的 guest 文件解析、附件、备份/恢复、预览和 staging 必须明确区分：

- App-owned canonical guest/user data；
- App-local cache/staging；
- SAF 外部授权域；
- Root-owned rootfs。

四者不能因为都“是文件路径”就互相替代。
