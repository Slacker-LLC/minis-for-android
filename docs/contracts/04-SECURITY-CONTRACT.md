# 04 — 安全合同

本项目是高权限 Agent：Root、无障碍、包管理、MCP、凭证都是安全边界，不是方便开关。

## 调用方隔离

本地 Agent、MCP 调用方、Android 服务、minisd 客户端是不同调用方。UI 勾选不是安全边界；执行入口必须再检查授权。

未知工具默认拒绝。仅本地的工具不得暴露给 MCP。

## Root

- `minisd` 是目标上的唯一 Root 执行出口。
- `root.exec` 使用编译期工具白名单；策略只能再收窄。
- Confirm 必须绑定**完整 argv/请求内容**，一次性，用后作废；改参数必须重新批准。
- Agent 不得自行切换到「完全访问」。该模式若存在，只能由用户在设置里打开，并持续显示警告。
- 现状仍存在绕过 minisd 的 `su -c` 通道，见 `06-CURRENT-GAPS.md`。新代码禁止再加一条。

## minisd IPC

- 私有 Unix socket；生产路径不要用 world-writable 模式。
- Peer 身份校验；`--once` / skip-peer 不得进入生产启动路径。
- 有界帧、有界输出、超时杀进程树。
- `uid=0` 的 peer 仍须受方法白名单约束。

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

## Ubuntu 边界

chroot 不是 VM。guest 逃出即宿主机。不要在文档或 UI 里把它宣传成强沙箱。
