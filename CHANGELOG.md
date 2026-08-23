# 变更记录

版本变更从 `1.01-beta` 开始记录。早期开发史不在此处,以 Git 历史为准。

## [Unreleased] — Root-only 重构（P1-P5）

整体替换 Alpine+PRoot 为 Root-only 新架构（架构审核请求文档全量落地）。

### 核心

- **minisd**（Rust, `src/native/minisd`）：Root Broker——unix socket + SO_PEERCRED +
  policy 门控 + watchdog/flock + capset 清零 + 超时 SIGKILL + kill(-pgid) 进程树 +
  /sys 只读失败中止 + 真实时钟 + path guard + cloudflared supervisor；
- **Ubuntu 24.04 arm64 chroot**：unshare+mount+chroot；guest uid=App uid(10381)、
  capabilities 清空；workspace=App filesDir/minis（Q16 修订）；出网经 minisd 根代理
  `127.0.0.1:18787`（Q6 修订，B8 硬化：拒内网/回环+头/并发上限）；App 自动拉起
  （ensureMinisdUp，subshell 分离 PPID=1）
- **Tool Runtime**：ToolRegistry/ToolExecutor + 四级权限（LOCAL_ONLY/MCP_ALLOWED/
  MCP_CONFIRM/MCP_DENIED）+ ProviderRouter/LinuxProvider + `root.shell` handler +
  危险命令拦截接入 shell 路径
- **MCP Server**：127.0.0.1:18789 `POST /mcp`；Bearer 多 token+scope；tools/list 按
  caller 过滤；confirm 队列（120s TTL 一次性）+ 手机通知；MCPKeepAliveService 前台保活

### 拆除

- PRoot/Alpine 全套（deps/proot、libproot loader、PRootKernel/PersistentShell/
  ShellExecutor、prepare_alpine_rootfs.sh 等）；
- RemoteAccessServer（Web Remote 停用，RemoteAccessService no-op）；
- Shizuku 后端（PrivilegedCommandRunner 永不选 SHIZUKU）。

### 质量

- App 全量 947 测试 / 14 OpenAI 环境基线零回归；minisd 47 cargo 测试绿；
- 真机验证：SELinux Enforcing 零 denial；exec ~147ms；Ubuntu 冷启动 267ms；
  confirm 通知真机弹出；锁屏冻结已知坑（前台服务保活对策）。

## [1.01-beta.2] — 2026-08-23

修复 Web 图片气泡把图片块显示成「附加内容块」的嵌套数组 bug。

### 图片

- `nativeMessageToDsh` 把 `resolveImageRefs` 的结果整体 `put` 进 content,导致 DSH
  收到 `[text, [imageBlock]]`(嵌套数组),第二个元素被 `contentParts` 归为未知块,
  渲染成「附加内容块」JSON 而非图片;
- 改为 `appendFlatBlocks` 把每个 image block 摊平为独立 content 元素;
- `DshImageBlockTest` 新增两用例:平铺结构与 DSH `contentParts` 三分分类(text/image/rest)
  钉死,嵌套数组场景回归防复发。

## [1.01-beta.1] — 2026-08-23

修复轮:Web 图片链路两处阻断、原生 DSH 统计投影定稿,并补齐 App/Web 图片协议测试。

### 图片(Web↔App 同一 MediaStore 权威源)

- **修复 live/history image block 死代码**:`nativeEventToMuxFrame` 全部调用点(history +
  两个 mux 推送路径)现在传入宿主 context,`resolveImageRefs` 不再因 context 为 null 而
  丢弃所有图片块;
- **修复 `session.attachment` 协议**:`data` 从 0-255 整数数组改为标准 base64 字符串,
  符合 bundled DSH 的 `data: string()` schema 与 runtime `atob()` 解码;
- 抽出可单测的 `imageAttachmentProto` / `encodeAttachmentData`,新增 `DshImageBlockTest`
  (7 用例,全部通过);
- 其余管线(MediaRef 持久化、attachmentId==MediaRef.id、legacy backfill)沿用 1.01-beta 实现。

### Stats

- 沿 1.01-beta 的 `sessionStats`/`tokenUsage` 投影,本轮仅验证与 bundled DSH StatsLine
  字段逐项一致(原始整数,不预格式化)。

### 测试

- `DshImageBlockTest` 7/7、`DshSessionStatsTest` 6/6、`com.openminis.app.remote.*` 全部通过;
- 全量单测仅 14 个既有 OpenAI MockWebServer 环境基线失败(与本次改动无关)。

## [1.01-beta] — 2026-08-23

首个公开版本,Android arm64 开发/自测构建。详见 [RELEASE-NOTES.md](RELEASE-NOTES.md)。

### Minis Web

- 正式 DeepSeek Harness Client Plugin `@openminis/minis-client-settings`
  (`web/minis-client-plugin/`):官方 `settings.section` slot 注册「Minis 控制台」,
  复用 `createSnapshotStore` 与 locale 基础设施,纯 React 投影 Android 权威状态;
- `@deepseek-ai/dsh-client-ui-settings-general` 使用上游官方产物;无 DOM 桥、
  MutationObserver 或未托管轮询;
- 12 个控制台页(overview/providers/skills/mcp/memory/system/scheduled/agent/web/
  device/diagnostics/advanced)全部保留。

### Android Debug / Root 能力

- 六个高内聚工具:`android_capabilities`、`android_app`、`android_ui`、`android_logs`、
  `android_diagnose`、`android_deploy`;
- 只读能力矩阵(root/privileged shell/UI/debug/execution/包可见性逐项探测,
  `AVAILABLE/PARTIAL/UNAVAILABLE/REQUIRES_USER_GRANT`);
- `PrivilegedCommandRunner`:Root `su` 主动探测后端与 Shizuku 复用,按操作选择;
  install/uninstall/clear/root 操作走一次性审批;
- Accessibility 观察 generation/ref + 窗口指纹 + `STALE_UI_REF`;Unicode 输入优先
  `ACTION_SET_TEXT`,`ACTION_PASTE` 回退会保存并恢复剪贴板;
- logcat 游标(mark → 操作 → read since,含 PID/boot 变更检测),watch 复用作业系统,
  大输出自动落盘;
- APK 部署按真实 Gradle output 元数据发现/检查;明确拒绝安装自身
  (self-update continuous execution 标记 UNSUPPORTED);
- native chroot/mount 仅实验性 `probe_native_chroot`;PRoot 保持默认执行环境。

### 基础能力

- Android Agent 运行时、PRoot+Alpine 沙箱、持久 shell、文件工具、Goal/Todo/Plan、
  Skills/MCP/记忆/定时任务、桌面宠物与数字助手;
- Web Remote:登录、逐能力 RPC 授权、会话事件 WebSocket、Tunnel、URL 导入 SSRF 防护;
- 工具超时、执行意图检查点、作业系统、Token 计量、上下文压力、结果修剪/落盘。
