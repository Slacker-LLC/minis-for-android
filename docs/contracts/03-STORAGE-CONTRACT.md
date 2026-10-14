# 03 — 存储合同

显式本机并存开发构建（`-PminisDevBuild=true`）使用独立包名 `llc.slacker.minis.dev` 与 Root runtime 根 `/data/adb/minis-dev`。本文生产路径 `/data/adb/minis` 仍属于正式包；开发包不读取其 legacy 用户数据，不重置其 rootfs，也不清理其进程记录。App-owned backing 仍从各自的 `Context.filesDir` 派生。

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

会话文件清理按用户选择的分类执行。工作区顶层 attachments/offloads/browser 别名不能被当作工作成果重复计量或连带删除。输入包含该会话 guest 附件与 App 按日期保存的媒体；媒体遍历和删除不得跟随符号链接。计量失败应标明未知，不能显示为完整的零字节。

确认清理后，先暂停该会话非终态目标、关闭对应执行 owner 并等待收尾，在删除期间保留关闭的 owner，最后释放。对话、目标与历史完成证据保留，所删除文件的当前可用性另行核查。清理不扩展到其它会话、全局资料、外部授权目录或 Root-owned runtime；真实 guest 进程、Root/OEM 行为仍需设备证据。

## 记忆与技能资料

记忆文件仍由既有全局 App-owned backing 持有，不另建 Bot 或任务记忆数据库。新记录保存独立 entry ID、真实执行 session 来源和背景类型；未知旧来源不推断。用户偏好与任务事实均是背景资料，不能变成当前指令。编辑与撤销须保持记录身份和原文条件，不能凭相同正文修改其它会话记录；共享 Repository 的读改写与编辑器条件保存使用同一串行边界。

GLOBAL.md 为用户维护、Agent 只读。关闭记忆的会话不自动注入或写入；近期日志注入遵循实际上下文窗口预算，持久任务/约束/计划独立从 Room 恢复，不靠摘要或记忆猜测当前目标。技能只披露启用项的元数据与路径，正文按需读取；读取主 SKILL.md 可复用已有使用记录，子资源及空预览不算主技能使用。

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

## Task、输入与交付的持久边界

任务与历史验收继续使用 Room Task；待投递输入保存在既有 messages，旧历史字段为 null，不从聊天内容猜测创建任务。附件/粘贴资料在接受前保存，历史截断不得删除尚未领取输入；明确清空/删除沿原会话删除边界。

SAF 证据保留原文档 URI 与持久授权，不能转换为 guest/host 路径或用字符串前缀代替树内授权。交付前流式核验实际内容，撤权/变化失败关闭。明确全员共享复制到现役 App-owned shared backing，历史指纹不宣称当前仍存在。

备份保存任务预算、计划、证据和关联未知检查点。恢复不覆盖现役同 ID 任务、不派发旧动作；非终态暂停，检查点完整导入与实际用户现场复核完成后才能显式继续。预算不归零，旧执行身份不伪造，恢复输入先审查。具体当前实现和验收见 [整体说明](../development/M-SERIES-INTEGRATION.md)。
