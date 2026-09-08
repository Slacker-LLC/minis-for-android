# 04 — 安全合同

本项目是高权限 Agent：Root、无障碍、包管理、MCP、凭证都是安全边界，不是方便开关。

## 调用方隔离

本地 Agent、MCP 调用方、Android 服务、minisd 客户端是不同调用方。UI 勾选不是安全边界；执行入口必须再检查授权。

未知工具默认拒绝。仅本地的工具不得暴露给 MCP。

## 权限交互

- App 面向用户的工具权限管理跟随上游 OpenMinis；不要另加一套 Root“标准模式 / 完全访问”或其它全局权限模式。
- Android 系统强制的运行时权限、无障碍、Restricted Settings、悬浮窗等仍由系统授权流程处理。
- Fork 自有的 Root/minisd/Ubuntu 能力只作为执行后端存在，不改变上游权限页面与交互语义。

## Root

- `minisd` 是目标上的唯一 Root 执行出口。
- App 不提供用户可切换的 Root 标准/完全访问模式。旧模式存储仅作兼容清理，不再形成产品权限状态。
- `root.fullExec` 与 `root.exec` 使用结构化 `{tool,args,timeout_ms,execution_id}`；不得接收原始 `command`。工具只能从可信 Android 系统目录解析。
- `root.fullExec` 的 confirm ticket 是 App 与 minisd 之间的内部一次性协议，不作为第二套用户权限管理 UI 暴露；ticket 必须绑定完整 method + params，一次性，用后作废，参数不匹配、过期或重复使用均拒绝。
- Agent 工具参数不得包含 `access_mode` 或其它模式切换入口。
- 为安装、启动、探测或修复 minisd/rootfs 而保留的受控 bootstrap/recovery `su -c` 只能执行静态 App-owned 命令；不得承载 Agent 提供的命令或 argv。剩余范围见 `06-CURRENT-GAPS.md`。

## minisd IPC

- 私有 Unix socket；生产路径不要用 world-writable 模式。
- Peer 身份校验；`--once` / skip-peer 不得进入生产启动路径。
- 有界帧、有界输出、超时杀进程树。
- `root.exec` 与 `root.fullExec` 的 socket 请求都必须在阻塞 worker 中执行，避免长命令阻塞 broker 接收循环。
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
