# 04 — 安全合同

本项目是高权限 Agent：Root、无障碍、包管理、MCP、凭证都是安全边界，不是方便开关。

## 调用方隔离

本地 Agent、MCP 调用方、Android 服务和 direct runtime 内部 Root 基础设施是不同调用方。UI 勾选不是安全边界；执行入口必须再检查授权。

未知工具默认拒绝。仅本地的工具不得暴露给 MCP。

## Root

- 当前不存在生产 Root broker / Root RPC。Root 只用于 App 自有 direct Ubuntu 基础设施。
- `DirectRootRunner` 是 internal launcher，只能执行 App 构造的受控脚本；禁止 Agent、MCP、Provider 或模型输出直接进入它的 Root 命令参数。
- Root 动作应限制在 rootfs 探测/修复、namespace/bind/chroot、受控 legacy 数据迁移、固定网络代理启动及同类明确基础设施需求。
- Guest shell 进入 chroot 后必须通过 `setpriv --reuid=<appUid> --regid=<appUid> --clear-groups`，并清空 inheritable、ambient、bounding capabilities。
- 不允许为了“兼容旧接口”增加任意 root shell、通用 argv/command RPC 或旧 broker shim。
- Root-provider identity 只是可用性信号；uid=0 不代表 SELinux、mount 或 Linux capability 一定允许操作。

## Root 网络代理

`minis-root-network-proxy` 是单用途 Root helper，不是 daemon command surface。

- 监听固定为 `127.0.0.1:18787`；非该地址启动必须拒绝。
- 仅接受 HTTP absolute-form 和 CONNECT 出站转发。
- 不提供 shell、文件系统、配置 RPC、动态插件或命令执行接口。
- 普通 loopback/private/link-local/broadcast 目标必须拒绝；`198.18.0.0/15` 仅作为明确 VPN Fake-IP 兼容例外。
- 请求头和并发必须有界；网络错误不得扩大为 Root 命令能力。

## MCP

- 默认绑定 loopback。
- Bearer 认证。
- 工具可见性按 token/调用方过滤。
- 敏感调用可要求用户确认。
- 禁止向远程调用方提供任意 root shell 或不受限 host 文件系统。

## 网络与凭证

- 密钥不得进仓库、不得经诊断 API 返回。
- 明文 HTTP 仅允许经应用层显式策略的本地/可信源；NSC 全开不等于策略全开。
- DebugServer 仅 loopback 且仅 debug 构建。
- Guest 通过固定 loopback Root proxy 出站不等于允许远程访问该 proxy。

## Ubuntu 边界

chroot 不是 VM。guest 与 Android 共享内核；不要在文档或 UI 里把它宣传成强沙箱。

- Root 建立 namespace/binds/chroot；任意 Agent/guest 代码以 App UID/GID 且无 Linux capabilities 运行。
- host/guest mounts 必须显式构造；session 数据只能绑定对应 session backing。
- SAF external mounts 必须按 grant 权限决定 read-only/writable。
- rootfs 输入必须经过 pin/checksum/manifest 校验；rootfs repair 不得覆盖 App-owned 用户数据。

## 构建边界

runtime payload 是 rootfs-only；Root 网络代理单独构建和验证。构建与发布不得重新引入旧 broker 二进制、socket、manifest 字段或 Android client/protocol 类型。负向 guard 中允许保留这些字符串，用来阻止回归。
