# Direct Ubuntu 执行环境

本文描述 main 的执行与生命周期。长期边界见[架构合同](contracts/01-ARCHITECTURE.md)，设备验收见[实测报告](REAL-DEVICE-TEST-REPORT.md)。

## 职责与执行链

```text
Android App（session、权限、数据、工具与生命周期）
  → ExecutionCoordinator / RootPersistentShell
  → UbuntuKernel / DirectRootRunner
  → su → setsid → unshare -m → 显式 bind mount → chroot
  → setpriv（实际 App UID/GID，清空附加组与 capabilities）
  → Ubuntu 24.04 bash
```

Ubuntu 复用 Android 内核，不是虚拟机或强隔离容器。旧 PRoot/Alpine 与特权 broker 不再是生产后端。

App 持有数据库、Provider/模型、工具注册、审批、session、路径与恢复状态。`DirectRootRunner` 执行 App 构造的受控基础设施脚本，负责必要 Root 探测、rootfs 修复、mount namespace、bind/chroot 和一次性迁移。

本地 Agent 的结构化 `root.shell` 另走工具权限层，只接受可信 Android executable basename + argv；不是原始 shell 字符串、通用 Root RPC 或宿主文件 API，对 MCP 隐藏。普通 Guest bash 使用实际 App UID/GID，禁止固定为 10000；进入前清空 supplementary groups 与 Linux capabilities。

## 数据与 session

`/data/adb/minis/rootfs` 是 Root-owned 可替换运行状态，不是用户数据。用户数据由 App 持有，从 `Context.filesDir` 派生：

| App 私有 backing | Guest 用途 |
|---|---|
| `minis/workspace` | 明确无 session 时的全局 workspace |
| `minis-sessions/<session_id>/...` | 对应 session 的 workspace、attachments、offloads、browser |
| `minis-global/{memory,skills,shared}` | 全局记忆、技能与共享数据 |
| `minis-global/mcp-servers` | `/var/minis/mcp-servers` |
| `minis/home` | `/home/minis` |

Terminal、Agent shell、附件、浏览器与文件工具必须使用同一 session backing，不能拿全局 workspace 绕过。每个 shell 有独立 mount namespace；进程树退出后，其 namespace 挂载随之释放。

旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为迁移源。迁移成功才写 `.root-data-migrated-v1`；普通 rootfs 修复不清除 App 用户数据。完整路径见[存储合同](contracts/03-STORAGE-CONTRACT.md)。

## 启动与就绪

1. 初始化 App-owned 路径和 session/global backing。
2. 验证 Root、rootfs 与 setsid、unshare、mount、chroot、guest setpriv。
3. 按需修复/provision Ubuntu、准备 Guest 命令；旧数据迁移必须在 guest 写入前完成。
4. 为 shell 建立 namespace 和显式 bind，进入 chroot、降权并启动 bash。
5. 必需阶段通过后才报告可用；当前配置要求代理时，还需确认独立网络 helper 就绪。

失败不得静默恢复旧 PRoot/Alpine、换成宿主 shell 或用 Root 身份执行普通 Guest 命令。

### 基础包与命令

`UbuntuProvisioner.BASE_PACKAGES` 当前显式准备：gawk、python3、python3-pip、python3-venv、git、curl、iputils-ping、wget、ca-certificates、zip、unzip、xz-utils、zstd。这不是“所有 Linux 工具均已预装”的承诺；额外命令应先用 `command -v` 确认。

`GuestCommandBridge` 根据 `NativeOffloadServer` 注册的 handler 生成 `/usr/local/bin` 命令入口；minis-config、minis-model-use 和 URL opener 也由当前部署逻辑管理。无需把旧资产目录搬回 rootfs。基础无副作用检查：

```bash
command -v ping python3 git curl android-device minis-config
android-device info
minis-config --help
```

Android 命令存在不代表已获系统授权或每个子命令全部实测通过。**minis-mcp-cli 当前仍缺失**，原生 MCP 不能代替这个 shell 入口；见[当前缺口](contracts/06-CURRENT-GAPS.md)。

## 网络和命令桥是不同服务

`minis-root-network-proxy` 固定在 `127.0.0.1:18787` 提供 HTTP absolute-form / CONNECT。协议本身不需要 Root；当前 Android 部署可借特权出站身份兼容 App-UID guest 的 VPN/BPF/UID 限制，不提供命令、文件、插件或通用 RPC。

普通 private/loopback 目标拒绝，`198.18.0.0/15` 只保留 VPN Fake-IP 兼容。DNS/路由应跟随 Android 有效网络；宿主测试不能证明真实设备的 VPN 切换、Fake-IP、DNS 与 BPF 行为。

Guest-to-Android 命令桥是另一套带 token 鉴权的 loopback 服务，不是上述代理，也不是 Root 权限通道。不能把一个服务的认证或验收结果套到另一个服务。

## 停止、维护与异常退出

`UbuntuRuntime.stop()` 持有生命周期锁，依次等待 `TerminalSession.stopAllAndJoin()`、停止 ExecutionCoordinator 命令/shell、停止 RootNetworkProxy，再更新状态。App 进程级 NativeOffloadServer 与 GuestCommandBridge 不随单个 session Stop 关闭，应用主动 teardown 时才清理。

rootfs 替换、受管配置写入与外部挂载调整经过生命周期协调，先停止相关 guest owner 再改 Root-owned 状态。readiness 失败会使旧 shell generation 失效，避免竞态重建旧 shell。

系统强杀 App 时不能依赖正常 Stop 或 onTerminate 一定执行。下次获得 Root 授权的 readiness 会核对本 runtime 的 PID marker；仅在当前 cmdline/environment 仍匹配目标 helper 时发信号。切到后台不等于停止：活跃 Agent turn 由[前台服务](AGENT-FOREGROUND-SERVICE.md)维持，但 OEM 仍可能杀进程。

副作用结果未知时不得盲目重放。Root 身份不证明 SELinux/mount/capability 操作一定允许；不为兼容全局关闭 SELinux。授权拒绝/允许、并发 Terminal、手机重启、升级保留数据及 OEM 进程回收仍按设备矩阵逐项留证。
