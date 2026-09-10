# 02 — 硬限制

违反任一条都视为错误，不能用“先跑起来”绕过。

## Fail-closed

下列任一不满足时必须失败关闭，禁止静默降级：

- 身份、策略、路径包含、签名、checksum、凭证；
- Root 授权、rootfs 校验、direct chroot 前置命令或 guest `setpriv` 不可用；
- 当前配置明确要求网络兼容 helper 时，该 helper 无法就绪；
- Release 没有完整 `RELEASE_*` 签名材料；
- 安全存储初始化失败。

网络 helper 是否需要特权身份、是否是当前设备的必需路径，是网络兼容条件，不得被写成“Root/chroot 天然依赖代理”。

## Root 与特权

- Root 只允许执行 App 构造的受控基础设施动作；禁止新增 Agent/MCP/模型可控的任意 Root shell、Root RPC 或通用 broker。
- `DirectRootRunner` 不是工具 API。任何来自模型或远程调用方的原始命令字符串都不得直接进入 `runScript` / `su -c`。
- `root.shell` 对 local Agent 和 MCP 都必须拒绝；若未来改变，需要单独安全设计与维护者明确授权。
- Guest 命令进入 chroot 后必须使用真实 App UID/GID，并清空 supplementary groups 与 Linux capabilities。
- 禁止恢复旧 broker、PRoot/Alpine 双栈或为了兼容全局关闭 SELinux。
- Accessibility、悬浮窗、默认助手、通知监听、安装未知应用等能力必须由用户/系统独立授权。

## 路径与存储

- Guest 路径拒绝 `..`、NUL、canonical escape 与相关 symlink escape。
- 现役 guest 用户数据使用 App 私有 backing；`/data/adb/minis/rootfs` 只存 Root-owned rootfs。旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 不得重新成为现役真源。
- SAF 授权目录与 App 私有数据、Root-owned rootfs 是不同信任域。
- Session 路径必须保持在对应 session backing 内。
- 工具输出必须有界或溢写到受控存储。

## 网络

- 当前 loopback 代理只能监听 `127.0.0.1:18787`，不得开放 LAN/public bind，也不得增加命令、文件、插件或通用 RPC 面。
- HTTP/CONNECT 代理本身不需要 Root；使用特权身份只能作为 Android UID/VPN/BPF 出站兼容机制，不能借此扩大 Root 权限面。
- 代理默认拒绝普通 loopback/private/link-local/broadcast 目标；仅保留明确的 VPN Fake-IP 兼容范围。
- 云 Provider、OAuth、更新元数据、带凭证请求走 HTTPS；禁止隐式公网明文 HTTP fallback。
- 本地 MCP Server 默认 loopback，需要 bearer；禁止向远程调用方暴露任意 Root shell 或不受限 host 文件系统。

## 构建与发布

- runtime payload 只包含 Ubuntu rootfs + rootfs manifest；网络 helper 是独立 native artifact。
- 构建、CI 和 APK/AAB 中不得重新出现已删除 broker 的二进制、socket、runtime package 或 build task；负向 regression guard / 历史归档文字除外。
- 仓库 source-first，不承诺 GitHub Release 对应 `versionName`。
- 禁止把 APK/AAB、密钥、OAuth 机密提交进 Git。

## 进程死亡

副作用操作必须区分：未开始 / 干净失败 / 已完成 / 结果未知。未知结果禁止盲目重试。
