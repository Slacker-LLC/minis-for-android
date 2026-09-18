# 07 — 旧 Root-owned 数据迁移合同

> 关联：`00-IDENTITY.md`、`03-STORAGE-CONTRACT.md`

本文件保留原文件名以避免旧链接失效。当前职责不是“属主校正当前 canonical 数据”，而是把旧版本位于 `/data/adb/minis` 的 Root-owned 用户数据一次性迁入 App 私有存储。

## 迁移源

只允许以下固定历史路径作为 source：

```text
/data/adb/minis/workspace
/data/adb/minis/sessions
/data/adb/minis/memory
/data/adb/minis/skills
/data/adb/minis/shared
/data/adb/minis/home
/data/adb/minis/mcp-servers
```

`/data/adb/minis/rootfs` 不是用户数据迁移源，不能被复制到 App 私有目录或递归改成 App owner。

## 迁移目标

目标由 `UbuntuPaths.initialize(context)` 从当前 `Context.filesDir` 派生：workspace、sessions、global memory/skills/shared/mcp-servers、home。目标 owner 使用当前安装的真实 App UID/GID，禁止固定 `10000`。

## 当前实现顺序

迁移发生在 `UbuntuKernel.ensureReady()` 的串行化启动路径中，并早于 guest shell 启动：

```text
marker 已存在
  → 跳过 legacy copy

marker 不存在
  → 对每个固定 source：source 不存在则跳过
  → 校验 destination 及其已有子树不含 symlink
  → mkdir -p destination
  → cp -a source/. destination/
  → chown -R 当前 App UID:GID destination
  → 所有 source 成功后写 <filesDir>/minis/.root-data-migrated-v1
```

任一 Root copy/chown 失败时 runtime readiness 失败，完成标记不得写入。只有全部复制成功后才写 marker。

## 语义边界

- 这是一次性前向迁移，不是长期双向同步。
- marker 写入后，旧 `/data/adb/minis/*` 用户目录不再参与运行时 path resolution 或 bind source 选择。
- 旧 source 默认保留，用于避免迁移本身执行破坏性删除；后续清理必须是独立、明确授权的任务。
- 当前实现使用固定 source 常量和 `cp -a`，并在 Root copy 前拒绝 destination 及其已有子树中的 symlink；没有逐文件 WAL、逆向 rollback 或 fd-relative 事务遍历，文档不得宣称这些尚未实现的保证。
- 因此迁移期间不应并发启动 guest 或允许同一目标被其它写入者修改。当前 `UbuntuKernel` readiness mutex 和迁移发生在 shell 启动前是必要前提。

## 不允许

- 把任意用户输入路径加入 source/destination；
- 将 legacy source 重新定义为现役真源；
- marker 未成功写入时假装迁移完成；
- 迁移失败后静默继续启动 Ubuntu；
- 为了“更保险”递归修改整个 `/data/adb/minis` owner；
- 在本任务中自动删除旧 source。

## 验收

- source 全不存在：迁移成功并可写完成 marker；
- 部分 source 存在：只复制固定存在项；
- copy/chown 失败：runtime fail-closed，marker 不写；
- marker 已存在：不得再次从 legacy source 覆盖 App-owned 数据；
- 最终目标 owner 来自当前实际 App UID/GID；
- rootfs 不参与用户数据迁移。
