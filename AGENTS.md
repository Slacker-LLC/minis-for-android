# Minis for Android — Agent 宪法

中文合同定义应保持的行为边界；最终目标分支源码与测试定义当前实现事实。发现两者不一致时，先核对代码和测试，再更新合同或把真实缺口记录到 `docs/contracts/06-CURRENT-GAPS.md`。

完整工程规则见 `docs/contracts/05-ENGINEERING.md`。

## 这是什么

独立产品：**面向已 Root Android 设备的 AI Agent Runtime**。

- 组织：Slacker-LLC
- 域名：slacker.llc
- 仓库：`Slacker-LLC/minis-for-android`
- 当前 `applicationId`：`llc.slacker.minis`
- 当前 Android/Kotlin namespace：`com.openminis.app`
- 许可证：GPL-3.0（法律来源只写在 `PROVENANCE.md`）

产品运行时是 KernelSU/root + Android App 自有执行协调 + Ubuntu 24.04 chroot。旧 root broker 已退出生产路径。不要恢复 PRoot、Alpine 或 broker 双栈，除非维护者明确重新定义产品范围。

## 必读合同

| 文件 | 用途 |
|---|---|
| `docs/contracts/00-IDENTITY.md` | 当前产品与 Android 身份 |
| `docs/contracts/01-ARCHITECTURE.md` | Direct Root / Ubuntu 架构与职责边界 |
| `docs/contracts/02-CONSTRAINTS.md` | 硬限制（fail-closed） |
| `docs/contracts/03-STORAGE-CONTRACT.md` | App-owned guest 数据与 Root-owned rootfs |
| `docs/contracts/04-SECURITY-CONTRACT.md` | Root / MCP / 网络 / 密钥边界 |
| `docs/contracts/05-ENGINEERING.md` | 工程流程、验证与上游参考边界 |
| `docs/contracts/06-CURRENT-GAPS.md` | 已确认缺口与历史审计基线 |
| `docs/contracts/07-OWNERSHIP-MIGRATION.md` | 旧 Root-owned 数据的一次性迁移 |
| `docs/contracts/08-BOT-COORDINATION.md` | Bot 协调合同 |

## 硬规则

1. 现役 guest 用户数据由 App 私有目录持有，host backing 从 Android `Context.filesDir` 派生；`/data/adb/minis/rootfs` 是 Root-owned、可替换运行时。旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}` 只作为迁移源，不得重新成为现役真源。
2. Guest 进程使用设备实际 App UID/GID，并通过 `setpriv` 清空 groups/capabilities；禁止写死 `10000`。
3. Root 只负责 rootfs、namespace、bind、chroot、受控迁移和固定 loopback 网络代理等 App-owned 基础设施。禁止新增模型/工具可控的任意 Root shell、Root RPC 或通用 broker。
4. 产品运行时不引入 PRoot/Alpine 兼容路径；不要为了兼容关闭 SELinux。
5. Session 执行必须保持对应 session workspace 语义；不要用全局 `/workspace` 绕过 session 隔离。
6. `applicationId = llc.slacker.minis` 是当前事实；不要顺手迁移 `com.openminis.app` namespace。
7. 合同写长期边界，`06-CURRENT-GAPS.md` 写当前已确认差异；历史 Issue/PR/计划不能覆盖最终源码与测试。
8. 安全改动必须有拒绝、越界或失败关闭等否定用例，不能只有成功路径。
9. 不要删除源文件版权头，不要改 `LICENSE` 为非 GPL。法律归属只收缩到 `PROVENANCE.md`。
10. 以最小充分变更完成当前任务，不为未经证明的未来场景建立大型基础设施。

## Root / runtime 约束

当前 direct chain：

```text
Android app
  → ExecutionCoordinator / App-owned shell lifecycle
  → UbuntuKernel / DirectRootRunner
  → su → setsid → unshare -m → bind mounts → chroot
  → setpriv(App UID/GID, no supplementary groups, no capabilities)
  → bash
```

`DirectRootRunner` 是内部基础设施 launcher，只能接收 App 构造的固定/受控脚本。Agent、MCP、Provider、模型输出不得直接成为它的 Root 命令输入。

Root 网络兼容由独立 `minis-root-network-proxy` 提供：仅监听 `127.0.0.1:18787`，只承担 HTTP/CONNECT 出站转发，不提供命令、文件或通用 RPC 面。

## 编辑与验证

- 先读当前目标分支代码、测试和相关合同，不基于历史 PR 猜测。
- 优先复用 Repository、Room、session、权限和测试边界。
- 替换旧实现后删除死路径；只有明确兼容需求才保留双实现。
- Runtime/Root/网络问题必须区分 CI/宿主测试与真实设备证据。
- Release/R8/JNI 敏感改动必须验证 Release 路径，Debug 不能替代。

## 完成意味着

- 请求行为与验收标准满足；
- 相关检查结果可复核；
- diff 没有无关清理、调试代码、备份副本或临时文件；
- 文档与最终分支实际代码一致；
- 未验证的 Root/SELinux/VPN/OEM 设备行为明确写成未验证。
