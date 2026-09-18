# 安全模型

本文解释现役安全边界，不额外改变权限策略。长期约束见[安全合同](contracts/04-SECURITY-CONTRACT.md)，实现事实看源码、否定用例与[当前缺口](contracts/06-CURRENT-GAPS.md)。

## 不同权限不能混为一谈

本地 Agent、MCP 调用方、Android API、无障碍、Shizuku、Root 基础设施和网络兼容 helper 是不同能力域。工具复用现有注册表和权限/审批层；UI 勾选不代替执行授权，未知工具默认拒绝，仅本地工具不向 MCP 暴露。

安全检查围绕已知身份、路径、凭证和调用边界，并有失败/越界测试；不能为了“安全”随意删除正常功能、建立重复权限层或全局关闭 SELinux。

## Direct Root 与 Guest

`DirectRootRunner` 是 App-owned 内部 launcher，用于受控 rootfs 探测/修复、mount namespace、bind/chroot、迁移及必要维护。没有生产通用 Root broker/RPC。

本地 Agent 可经工具层调用结构化 root.shell，输入为 Android executable basename 与 argv，经过可信系统目录解析、参数/输出/超时限制和进程清理；不接受模型原始命令字符串，不提供 host 文件 API，对 MCP 隐藏。

普通 Guest 命令通过 setpriv 切到实际 App UID/GID，清空附加组和 capabilities。chroot 共享 Android 内核，不是完整容器安全边界。uid=0 不证明 SELinux、namespace 或 mount 一定允许。

## 文件与数据

- `/data/adb/minis/rootfs` 为 Root-owned 可替换运行状态；用户数据为 Context.filesDir 派生的 App-owned backing。
- 有效 session 只使用对应 backing，不用全局 workspace 旁路。
- 文件层检查路径穿越、NUL、canonical escape 与相关 symlink escape；缓存/staging、SAF、App 数据和 rootfs 是不同信任域。
- SAF 以有效 grant 为准，只读授权以只读 bind 进入 guest。
- rootfs 按 pin/checksum/manifest 校验，修复不覆盖用户数据；大输出有界或溢写到受控目录。

## 本地服务与凭证

Provider key、OAuth/MCP/DebugServer token 与签名材料不得进仓库、诊断输出或未脱敏日志；安全存储失败不得降级写明文。

- DebugServer 仅 Debug 构建、仅 loopback，并要求 token。
- 本地 MCP server 默认 loopback，要求 bearer，按调用方过滤工具，保留敏感操作审批；不提供任意 Root shell 或不受限 host 文件访问。
- Guest 命令桥以独立 token 验证 Android handler 调用，不是网络代理或通用 Root RPC。

## 网络兼容代理

`minis-root-network-proxy` 仅监听 `127.0.0.1:18787`，只做 HTTP absolute-form / CONNECT，限制请求头与并发，不提供 shell、文件、插件或动态配置 RPC。普通 loopback/private/link-local/broadcast 目标拒绝，`198.18.0.0/15` 只保留 VPN Fake-IP 兼容。

HTTP/CONNECT 本身不要求 Root。当前 helper 可用特权身份建立出站 socket，只为兼容 Android UID/VPN/BPF 策略，不是 chroot 权限边界，也不得暴露为远程代理。不能用 Guest 命令桥的 token 测试代替代理自身访问边界测试。

云 Provider、OAuth、更新与带凭证请求应使用 HTTPS；本地/私有 HTTP 端点遵循应用显式策略，不允许隐式公网明文 fallback 或凭证请求 HTTPS→HTTP 降级跳转。

## 构建、恢复与验收

Release 缺少生产签名必须失败，不能回退 Debug key。runtime payload 只含 rootfs/manifest，网络 helper 单独构建/验证；旧 broker 二进制、socket 和 package 由守卫防止回归。APK/AAB 与密钥不提交到 Git。

恢复须区分未开始、干净失败、已完成、结果未知；不能盲目重试结果未知的副作用。

CI/宿主测试可证明输入完整性、解析/策略、打包和编译行为，不能代替 Root 授权、SELinux、真实挂载、VPN/DNS/BPF/Fake-IP 与 OEM 生命周期的设备证据。本轮文档更新不代表新增了这些实测结果。
