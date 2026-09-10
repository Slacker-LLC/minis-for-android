# 02 — 硬限制

违反任一条都视为错误，不能用“先跑起来”绕过。

## Fail-closed

下列任一不满足时必须失败关闭，禁止静默降级：

- 身份、策略、路径包含、签名、checksum、凭证；
- Root 授权、rootfs 校验、direct chroot 前置命令或 guest `setpriv` 不可用；
- Root 网络代理在需要 direct Ubuntu runtime 时无法就绪；
- Release 没有完整 `RELEASE_*` 签名材料（禁止回退 debug 密钥）；
- 安全存储初始化失败（禁止把密钥改存明文）。

## Root 与特权

- Root 只允许执行 App 构造的受控基础设施动作；禁止新增 Agent/MCP/模型可控的任意 Root shell、Root RPC 或通用 broker。
- `DirectRootRunner` 不是工具 API。任何来自模型或远程调用方的原始命令字符串都不得直接进入 `runScript` / `su -c`。
- Guest 命令进入 chroot 后必须使用真实 App UID/GID，并清空 supplementary groups 与 capabilities。
- 禁止恢复旧 broker、PRoot/Alpine 双栈或为了兼容全局关闭 SELinux。
- 无障碍、悬浮窗、默认助手、通知监听、装未知应用等能力必须由用户/系统独立授权。

## 路径与存储

- Guest 路径拒绝 `..`、NUL、canonical escape 与相关 symlink escape。
- 现役 guest 用户数据使用 App 私有 backing；`/data/adb/minis/rootfs` 只存 Root-owned rootfs。旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}` 不得重新成为现役真源。
- SAF 授权目录与 App 私有数据、Root-owned rootfs 是不同信任域，禁止互相当成可直接替换的 POSIX 路径。
- Session 路径必须保持在对应 session backing 内。
- 工具输出必须有界或溢写到受控存储。

## 网络

- Root 网络代理只能监听 `127.0.0.1:18787`，不得开放 LAN/public bind，也不得增加命令、文件或通用 RPC 面。
- 代理默认拒绝 loopback/private/link-local/broadcast 目标；仅保留明确的 VPN Fake-IP 兼容范围。
- 云 Provider、OAuth、更新元数据、带凭证请求走 HTTPS；禁止隐式公网明文 HTTP fallback。
- 本地 MCP Server 默认 loopback，需要 bearer；禁止向远程调用方暴露任意 root shell 或不受限 host 文件系统。

## 构建与发布

- runtime payload 只包含 Ubuntu rootfs + rootfs manifest；Root 网络代理是独立 native artifact，不得重新塞回 broker payload 字段。
- 构建、CI 和 APK/AAB 中不得出现 `libminisd.so`、`minisd-arm64-v8a`、`runtime.minisd` 或 `minisd.sock`，负向回归 guard 文本除外。
- 仓库 source-first，不承诺 GitHub Release 对应 `versionName`。
- 禁止把 APK/AAB、密钥、OAuth 机密提交进 Git。

## 进程死亡

副作用操作必须能区分：未开始 / 干净失败 / 已完成 / 结果未知。未知结果禁止盲目重试。
