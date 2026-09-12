# 04 — 安全合同

本项目是高权限 Agent：Root、Accessibility、包管理、MCP、凭证与设备控制都是独立安全边界。

## 调用方隔离

本地 Agent、MCP 调用方、Android 服务、Direct Ubuntu 内部 Root 基础设施和网络兼容 helper 是不同调用方/能力域。UI 勾选不是安全边界；执行入口必须再次检查授权。

未知工具默认拒绝。仅本地工具不得暴露给 MCP。

## Root

- 当前不存在生产 Root broker / 通用 Root RPC。
- `DirectRootRunner` 是 internal launcher，直接脚本入口只服务于 App 构造的受控基础设施；本地 Agent 的 Root 请求走结构化 `root.shell`，由可信 Android system executable 解析、argv 边界、超时/输出上限和进程清理统一约束。
- `root.shell` 是 local-only：本地 Agent 可以使用它完成需要 Root 的 Android 工具动作，MCP 不可见且不可调用；它不接受 `command` 字符串，不提供 host 文件或通用 RPC 面。
- `DirectRootRunner` 的内部脚本动作限于确实需要权限的 rootfs 探测/修复、namespace/bind/chroot、受控 legacy 数据迁移及同类明确基础设施需求；本地 Agent 的 `root.shell` 只走结构化 Android tool/argv 合同，不得被扩展成 raw shell、host 文件或通用 RPC。
- Guest shell 进入 chroot 后必须通过 `setpriv` 切到真实 App UID/GID，清空 supplementary groups 与 Linux capabilities。
- Root-provider identity 只是可用性信号；uid=0 不代表 SELinux、mount、namespace 或 capability 一定允许操作。

## 网络兼容 helper

`minis-root-network-proxy` 是单用途 loopback HTTP/CONNECT helper。名称中的 `root` 反映当前 Android 部署可让它以特权身份建立出站 socket，**不是协议要求，也不是 Direct Ubuntu chroot 的安全边界本身**。

- 监听固定为 `127.0.0.1:18787`；
- 仅接受 HTTP absolute-form 和 CONNECT；
- 不提供 shell、文件系统、插件、动态配置 RPC 或命令执行接口；
- 普通 loopback/private/link-local/broadcast 目标拒绝；`198.18.0.0/15` 仅作为明确 VPN Fake-IP 兼容例外；
- 请求头、并发、错误处理必须有界；
- 使用特权出站身份只能解决网络兼容，不能扩大成通用 Root 能力。

若目标设备上的 App-UID guest 能直接稳定联网，Direct Ubuntu 本身并不要求通过特权代理才能成立。

## MCP

- 默认绑定 loopback；
- Bearer 认证；
- 工具可见性按 token/调用方过滤；
- 敏感调用可要求用户确认；
- 禁止向远程调用方提供任意 Root shell 或不受限 host 文件系统。

## 网络与凭证

- 密钥不得进仓库、不得经诊断 API 返回、不得写入未脱敏日志；
- 明文 HTTP 只允许应用层显式策略允许的本地/可信源；
- DebugServer 仅 loopback 且仅 debug 构建；
- 本地网络兼容 helper 不得暴露为远程代理服务。

## Ubuntu 边界

chroot 不是 VM。guest 与 Android 共享内核。

- Root 建立必要 namespace/binds/chroot；任意 Agent/guest 代码以 App UID/GID 且无 Linux capabilities 运行；
- host/guest mounts 必须显式构造；session 数据只绑定对应 session backing；
- SAF external mounts 按 grant 权限决定 read-only/writable；
- rootfs 输入必须经过 pin/checksum/manifest 校验；rootfs repair 不得覆盖 App-owned 用户数据。

## 构建边界

runtime payload 是 rootfs-only；网络 helper 单独构建和验证。构建与发布不得重新引入旧 broker 二进制、socket、manifest 字段或 Android client/protocol 类型。负向 guard 与历史归档中允许保留旧字符串用于阻止回归和解释历史。
