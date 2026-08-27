# 参与贡献

欢迎为 Minis for Android 提交 Issue 与 Pull Request。

## 报告问题

Issue：<https://github.com/Slacker-LLC/minis-for-android/issues>

请尽量包含：

- 应用版本 / versionCode 与 Android 版本；
- 设备型号、ROM，以及是否 Root（可注明 Magisk / KernelSU / APatch）；
- 精确重现步骤、期望结果与实际结果；
- 相关 Provider / 模型；
- 已脱敏的应用日志或崩溃元数据。

禁止提交 API Key、OAuth token、MCP token、DebugServer token、私密文件内容或无关手机数据。

## 提交 Pull Request

提交前：

1. 基于当前 `master`；
2. 保持 Android App 为 Agent、会话、工具和数据的唯一权威运行时，不新增第二套远程 Agent 数据源；
3. 新工具必须经过现有 Tool Registry / ToolPermissionManager / runtime gate，不允许绕过统一权限入口；
4. 敏感工具必须明确 caller scope，远程调用按需要使用确认或 LOCAL_ONLY；
5. Root 能力必须经过 `minisd` 或现有受控 privileged backend，不新增裸 `su -c <模型输出>` 通道；
6. 有副作用操作必须复用 Approval / ToolCheckpoint / Job / ToolResult 等现有治理机制；
7. 更新与改动对应的测试和当前文档；
8. 保留第三方许可证、来源和 GPL 义务。

## 当前架构红线

### Android Runtime

- Android App 是唯一的 Agent Runtime 和状态源；
- 不重复实现第二套 Accessibility、Job、审批、检查点、Token 计量或 MCP 权限系统；
- Root、Shizuku/AXManager/Sui、Accessibility 是独立能力，不构成简单的权限等级链；
- 能力判断必须来自真实探测，不能只凭 provider 名称或 `uid=0` 推断全部能力。

### Linux Runtime

当前 Linux 执行环境是：

```text
minisd Root Broker
  ↓
unshare + mount + chroot
  ↓
Ubuntu 24.04 userspace
```

- Alpine + PRoot 已从当前架构删除，不要重新引入旧 PRoot 运行时作为并行实现；
- `minisd` 是 Root TCB，任何新增 RPC 都必须最小权限、可审计、fail-closed；
- runtime policy 不应成为绕过编译时权限上限的通用扩权入口；
- 不允许为了可用性关闭全局 SELinux；
- mount/chroot/root process 操作必须有明确边界和测试。

### MCP 与远程能力

- 当前远程工具面是 MCP Server；旧 Web Remote / Cloudflare Tunnel 已删除；
- 不重新添加第二套 Web-only Agent/runtime/database；
- 不通过远程面暴露任意 Root shell、任意文件系统、凭据或无审批的高风险 Android 操作；
- MCPProvider 接入外部 Server 时也必须进入现有工具注册和权限体系。

### 厂商适配

基础能力应优先来自通用 Android API 与通用 Root / privileged backend。

厂商专属能力可以实现，但必须：

1. 执行前检测设备、系统特性或目标 API 是否存在；
2. 非匹配设备返回结构化 unsupported，而不是崩溃；
3. 对隐藏 API/反射做版本保护和异常降级；
4. 只能作为可选增强，缺失时不能破坏基础能力；
5. 厂商设置页跳转失败时回退到通用 Android 设置页。

## 安全要求

这是高权限 Agent 项目。涉及以下区域时按安全改动处理：

- `src/native/minisd/`；
- Root / Shizuku / Accessibility；
- MCP Server / MCPProvider；
- Provider credential / OAuth；
- 文件、mount、workspace 边界；
- APK 安装、Intent、联系人、日历、短信、通话记录等敏感 Android 能力；
- DebugServer 与 release 打包。

安全相关改动应包含负向测试：不仅证明“允许的能运行”，还要证明“不允许的确实被拒绝”。

## 验证检查

至少运行与你改动相关的最窄检查。

Android：

```bash
cd src/android
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Android instrumentation test：

```bash
./gradlew :app:assembleDebugAndroidTest
```

只在明确授权的真机/模拟器上运行 connected tests。

minisd：

```bash
cd src/native/minisd
cargo fmt --check
cargo test
cargo clippy -- -D warnings
```

如果修改 rootfs 构建：

```bash
./scripts/build-ubuntu-rootfs.sh
```

并验证来源、hash、manifest 和失败路径。

## 文档

当前事实来源优先级：

```text
源码与测试
  > 当前架构/安全文档
  > README / Release Notes
  > archive / upstream 历史资料
```

相关文档：

- [README.md](README.md)
- [BUILD-CN.md](BUILD-CN.md)
- [docs/README.md](docs/README.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
