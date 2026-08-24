# 安全设计

本文描述 Minis for Android 当前的安全模型与边界。

## 1. 凭据存储:fail-closed

- `EncryptedPrefsFactory` 在 Tink/EncryptedSharedPreferences 连续创建失败时**不得回退明文**;
  失败路径不会删除全局唯一的 AndroidKeyStore 主密钥别名或共享 Tink keyset 文件;
- Provider API Key、OAuth token、DebugServer token 全部经该工厂落盘;
- `allowBackup=false`。

## 2. 本地服务边界

- DebugServer(DEBUG 构建,`127.0.0.1:5321`)以动态 token 全连接认证,仅限开发自测,
  release 构建不携带;
- MCP Server(`127.0.0.1:18789`)Bearer token 认证,默认 fail-closed,写敏感工具必须
  手机通知确认(120s 自动拒绝);
- 两个本地服务都只监听回环地址,不经 LAN/公网暴露;
- minisd root broker 只监听 App 私有 unix socket,SO_PEERCRED + policy 门控。

## 3. 工具调用授权:显式映射

- `ToolPermissionManager` 是唯一映射表:每个工具 × caller(local_agent / mcp:<token id>)
  有一个级别(LOCAL_ONLY / MCP_ALLOWED / MCP_CONFIRM / MCP_DENIED),未登记工具**默认拒绝**;
- 未知 token 默认拒绝,scope 子集与级别上限在 token 上显式绑定;
- 日历、联系人、位置、剪贴板、Intent、设置等敏感能力对 MCP 一律 MCP_CONFIRM(或
  LOCAL_ONLY),不默认放行;
- `root.shell` 为 LOCAL_ONLY,且只接受结构化 minisd `root.exec`,不暴露 raw shell。

## 4. 文件与沙箱边界

- `UbuntuPaths` canonical path containment:`..`/symlink 逃逸在任何文件工具执行前被拒绝;
- 工作区读写默认限定 App workspace(`filesDir/minis/workspace`);外部 SAF 目录只能由
  用户在 Android 系统选择器中授权,只读挂载在工具层写保护;
- 大文件/递归限制(FileMutationQueue 串行化)防止资源耗尽;
- Web URL 导入(`SafeRemoteImporter`)仅允许安全公共 HTTPS 目标,拒绝
  localhost/私网/链路本地/CGNAT(ran chu 模拟器 198.18/15 NAT 特例除外,真机不放宽)。

## 5. Android 权限:普通 API > Shizuku > Root

- 结构化 Android 工具优先走普通 API,失败才考虑 Shizuku;Root 优先经 minisd 结构化
  `root.exec` allowlist;
- Root 探测区分被动(检测 `su` 存在,不弹窗)与主动(需要用户批准,返回真实
  uid/gid/groups/CapEff/SELinux context/mode);
- `uid=0` 不等于全能力;集成断言 `CAP_SYS_CHROOT`/`CAP_SYS_ADMIN` 后再谈 chroot/mount;
- **禁止** `setenforce 0`、修改全局 SELinux 策略、默认 bind 整个可写 `/sys`;
- chroot 不是容器;guest 以 App UID 降权运行,SELinux 全程 Enforcing;
- Root provider 名称(Magisk/KernelSU/APatch/Sui)只作为诊断元数据,不参与能力判断。

## 6. Agent 侧安全治理

- **一次性审批**(`ApprovalSeam`):政策 `ask|never`,危险 shell 命令与所有有副作用的
  Android 操作(install/uninstall/clear logs/root 授权/mount/chroot)在批准前不执行;
  无人应答超时视为取消,不运行;
- **危险命令策略**(`DangerousCommandPolicy`):明确破坏性的模式才拦截,避免误伤;
- **执行意图检查点**(`ToolCheckpointStore`):工具体执行前记录 intent,执行后标记;
  进程被杀后下轮注入 `TOOL_OUTCOME_UNKNOWN`,不盲目重试可能有副作用的操作;
- **工具超时**:`AgentToolDefinition.timeoutMs` 产生结构化 `TOOL_TIMEOUT` 结果;
- **结果治理**:`ToolResultPruner` 管理上下文修剪,`SpillPolicy` 把超大结果落盘为
  `/var/minis/offloads` 指针;`TokenMeter`/`ContextPressure` 只作告警,不作门禁;
- 环境变量值通过 `EnvVarRedactor` 在模型可见前脱敏。

## 7. 已知边界

- Debug APK 会启动 DebugServer(动态 token 认证),仅限开发自测;
- HyperOS 后台冻结、SAF、角色、悬浮窗、电池豁免必须由用户在系统界面授权;
- Root 场景(provider 授权弹窗、SELinux 拒绝、capability 缺位)需要真机验证;
- 完整设备级边界以 `android_capabilities get` 的真实探测结果为准。
