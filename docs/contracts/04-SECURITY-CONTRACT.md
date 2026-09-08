# 04 — 安全合同

本项目是高权限 Agent：Root、无障碍、包管理、MCP、凭证都是安全边界，不是方便开关。

## 调用方隔离

本地 Agent、MCP 调用方、Android 服务、minisd 客户端是不同调用方。UI 勾选不是安全边界；执行入口必须再检查授权。

未知工具默认拒绝。仅本地的工具不得暴露给 MCP。

## Root

- `minisd` 是目标上的唯一 Root 执行出口。
- 标准模式按 App-owned 的结构化风险分类处理 Root 操作：普通操作直接执行，只有 `MUTATING` / `ROOT_SETUP` 最高风险操作进入用户请求；用户拒绝才返回拒绝。`root.exec` 是普通操作的快速路径，不能把它的 `POLICY_DENIED` 直接当成用户确认请求。
- `root.fullExec` 与 `root.exec` 使用相同的结构化 `{tool,args,timeout_ms,execution_id}`；不得接收原始 `command`。工具只能从可信 Android 系统目录解析，minisd 策略固定为 `confirm`。
- Confirm 必须保存并绑定**完整 method + params**，一次性，用后作废；参数不匹配、过期或重复使用均立即消耗确认票。
- 标准模式的最高风险操作在用户请求获准后，以完全相同的结构化请求进入 `root.fullExec`；普通操作不因为快速路径缺少某个工具而触发用户请求。
- 完全访问只能由用户在 App 设置里打开。开启后 App 层不再按风险拦截或询问，所有 Root 操作都可由 App 自动完成 `root.fullExec` 的内部确认重放；聊天页必须持续显示红色警告。
- Agent 工具参数不得包含 `access_mode` 或其它模式切换入口；`root.shell` 保持 `LOCAL_ONLY`，Agent 不能自行切换模式。
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
