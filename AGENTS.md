# Minis for Android — Agent 宪法

中文合同定义长期行为边界；最终目标分支源码与测试定义当前实现事实。发现不一致时，先核对代码/测试，再更新合同或把真实缺口记录到 `docs/contracts/06-CURRENT-GAPS.md`。

完整工程规则见 `docs/contracts/05-ENGINEERING.md`。

## 这是什么

独立产品：**面向已 Root Android 设备的 AI Agent Runtime**。

- 组织：Slacker-LLC
- 域名：slacker.llc
- 仓库：`Slacker-LLC/minis-for-android`
- `applicationId`：`llc.slacker.minis`
- Android/Kotlin namespace：`com.openminis.app`
- 许可证：GPL-3.0（法律来源只写在 `PROVENANCE.md`）

现役 Linux runtime 是 Android App 自有执行协调 + Ubuntu 24.04 direct chroot。PRoot、Alpine 与旧特权 broker 已退出生产路径。

## 必读合同

| 文件 | 用途 |
|---|---|
| `docs/contracts/00-IDENTITY.md` | 产品与 Android 身份 |
| `docs/contracts/01-ARCHITECTURE.md` | Direct Ubuntu 架构与职责边界 |
| `docs/contracts/02-CONSTRAINTS.md` | Fail-closed 硬限制 |
| `docs/contracts/03-STORAGE-CONTRACT.md` | App-owned guest 数据与 Root-owned rootfs |
| `docs/contracts/04-SECURITY-CONTRACT.md` | Root / MCP / 网络 / 密钥边界 |
| `docs/contracts/05-ENGINEERING.md` | 工程、验证与参考边界 |
| `docs/contracts/06-CURRENT-GAPS.md` | 当前缺口与历史基线说明 |
| `docs/contracts/07-OWNERSHIP-MIGRATION.md` | 旧 Root-owned 用户数据一次性迁移 |
| `docs/contracts/08-BOT-COORDINATION.md` | Bot 协调合同 |

## 硬规则

1. 现役 guest 用户数据由 App 私有目录持有，host backing 从 `Context.filesDir` 派生；`/data/adb/minis/rootfs` 是 Root-owned、可替换 runtime state。
2. Guest 使用设备实际 App UID/GID；进入 chroot 后必须清空 supplementary groups 与 Linux capabilities，禁止写死 `10000`。
3. `DirectRootRunner` 只允许执行 App 构造的受控基础设施动作：rootfs、namespace、bind、chroot、必要探测/修复和受控 legacy migration。按上游权限模型，本地 Agent 可使用结构化 `root.shell`（tool basename + args）调用受信 Android system 工具；该能力必须 local-only、有参数/超时/输出/进程组边界，不接受 raw command、host 文件 API 或通用 Root RPC，MCP/远程调用方不可见。
4. 网络代理和 Root/chroot 是不同概念。HTTP/CONNECT 代理协议本身不要求 Root；当前 helper 仅因 Android 出站 UID/VPN/BPF 兼容需求可由特权身份启动。不得把它扩展成命令、文件或通用 RPC 服务。
5. 产品运行时不恢复 PRoot/Alpine 双栈；不要为了兼容全局关闭 SELinux。
6. Session 执行必须保持对应 session workspace 语义，不能用全局 `/workspace` 绕过隔离。
7. `applicationId = llc.slacker.minis` 是当前事实；不要顺手迁移 `com.openminis.app` namespace。
8. 合同写长期边界，`06-CURRENT-GAPS.md` 写当前已确认差异；历史 Issue/PR/计划不能覆盖最终源码与测试。
9. 安全改动必须有拒绝、越界或失败关闭等否定用例，不能只有成功路径。
10. 不删除源文件版权头，不把 GPL 改成其它许可证；法律来源只维护在 `PROVENANCE.md`。

## Direct Ubuntu / Root 约束

```text
Android app
  → ExecutionCoordinator / App-owned shell lifecycle
  → UbuntuKernel / DirectRootRunner
  → su → setsid → unshare -m → bind mounts → chroot
  → setpriv(real App UID/GID, clear groups, drop capabilities)
  → Ubuntu 24.04 bash
```

`DirectRootRunner` 是内部基础设施 launcher，只能执行 App 构造的固定/受控脚本。`root.shell` 不得成为本地 Agent、MCP、Provider 或模型的通用 Root 执行入口。

## 网络兼容

当前 `minis-root-network-proxy` 固定监听 `127.0.0.1:18787`，只提供有界 HTTP absolute-form / CONNECT 转发。它是独立兼容 helper，不是 Direct Ubuntu 的权限边界，也不是 Bot/MCP 通信通道。设备若能让 App-UID guest 直接获得正确网络，不应为了形式继续把“Root 网络代理”当成 Root/chroot 的固有步骤。

## 编辑与验证

- 先读目标分支源码、测试和相关合同，不基于历史 PR 猜测。
- 优先复用 Repository、Room、session、权限和测试边界。
- 替换旧实现后删除死路径；只有明确兼容需求才保留双实现。
- Runtime/Root/网络问题必须区分 CI/宿主测试与真实设备证据。
- Release/R8/JNI 敏感改动必须验证 Release 路径，Debug 不能替代。

## 完成意味着

- 请求行为与验收标准满足；
- 相关检查可复核；
- diff 无无关清理、调试代码、备份副本或临时文件；
- 现役文档与最终分支代码一致；
- 历史文档明确标为历史；
- 未验证的 Root/SELinux/VPN/BPF/OEM 设备行为明确写成未验证。
