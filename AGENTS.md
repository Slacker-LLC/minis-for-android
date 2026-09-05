# Minis for Android — Agent 宪法

中文合同定义应保持的行为边界；**当前源码与测试定义 `main` 实际已经实现了什么**。发现两者不一致时，先确认当前代码与测试，再把差异记录到 `docs/contracts/06-CURRENT-GAPS.md`；不要仅凭过时文档把已经正确的实现改回旧阶段。

完整工程规则见 `docs/contracts/05-ENGINEERING.md`。

## 这是什么

独立产品：**面向已 Root Android 设备的 AI Agent Runtime**。

- 组织：Slacker-LLC
- 域名：slacker.llc
- 仓库：`Slacker-LLC/minis-for-android`
- 当前 `applicationId`：`llc.slacker.minis`
- 当前 Android/Kotlin namespace：`com.openminis.app`
- 许可证：GPL-3.0（法律来源只写在 `PROVENANCE.md`）

`applicationId` 已完成迁移。namespace 与安装身份可以不同；不要把全库 Java/Kotlin package 重命名当成隐含任务。

产品运行时是 KernelSU/root + `minisd` + Ubuntu 24.04 chroot。不要恢复 PRoot、Alpine 或其它 userspace 模拟执行后端，除非维护者明确重新定义产品范围。

## 必读合同

| 文件 | 用途 |
|---|---|
| `docs/contracts/00-IDENTITY.md` | 当前产品与 Android 身份 |
| `docs/contracts/01-ARCHITECTURE.md` | Root/minisd/Ubuntu 架构与职责边界 |
| `docs/contracts/02-CONSTRAINTS.md` | 硬限制（fail-closed） |
| `docs/contracts/03-STORAGE-CONTRACT.md` | `/data/adb/minis` 持久化真源 |
| `docs/contracts/04-SECURITY-CONTRACT.md` | Root / MCP / 网络 / 密钥边界 |
| `docs/contracts/05-ENGINEERING.md` | 工程流程、验证与上游参考边界 |
| `docs/contracts/06-CURRENT-GAPS.md` | 当前 `main` 已确认缺口 |
| `docs/contracts/07-OWNERSHIP-MIGRATION.md` | 中断安全的属主校正规则 |

改行为前先读对应合同。改存储/runtime 前必须读 `03`、`06`、`07`。

## 硬规则

1. 持久化 Linux 用户数据只认 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`。rootfs 是可替换运行时，不是用户数据。
2. Guest UID/GID 使用设备实际分配的 App identity，禁止写死 `10000`。
3. 新的 Root 执行必须走 `minisd` 结构化 RPC。禁止新增“模型输出直接进入 `su -c`”的通道。
4. 产品运行时不引入 PRoot/Alpine 兼容路径；不要为了兼容关闭 SELinux。
5. Session 执行必须保持 session workspace/namespace 语义；不要用全局 `/workspace` 绕过 session 隔离。
6. `applicationId = llc.slacker.minis` 是当前事实。不要把它写成未来迁移；也不要顺手迁移 `com.openminis.app` namespace。
7. 合同写长期边界，`06-CURRENT-GAPS.md` 写当前已确认差异。历史 Issue/PR/计划文档不能覆盖当前源码与测试。
8. 安全改动必须有拒绝、越界或失败关闭等否定用例，不能只有成功路径。
9. 不要删除源文件版权头，不要改 `LICENSE` 为非 GPL。法律归属只收缩到 `PROVENANCE.md`。
10. 以最小充分变更完成当前任务，不为未经证明的未来场景建立大型基础设施。

## 编辑前

- 直接阅读相关代码、测试和配置，不基于搜索片段或历史 PR 猜测当前行为。
- 明确结果、非目标、最小文件集和验证方式。
- 当前 `main` 的最终代码比“某个 PR 曾经合并过什么”更能证明现状。

## 编辑中

- 优先复用现有 Repository、Room、broker、session、权限和测试边界。
- 在根因处修复，不围绕错误假设堆补丁。
- 只有第二个真实调用者或明确需求出现时才新增抽象。
- 保留请求范围外的行为；不要顺手做 namespace 重命名、运行时双栈或理论型安全重构。
- 替换旧实现后删除死路径；只有明确兼容需求才保留双实现。

## 需要额外确认的操作

只读发现始终允许。未获授权时，不要：

- 实质性扩展范围或触及无关文件；
- 添加依赖、框架、服务或新测试基础设施；
- 更改公共 API、存储格式或线格式；
- 删除/覆盖用户数据、重写历史或强制推送；
- 同时保留两套等价运行时实现。

## 测试

- 运行会实际覆盖改动行为的最窄现有测试。
- 优先扩展相关现有测试，而不是新建测试体系。
- Release/R8/JNI 敏感改动必须验证 Release 路径，Debug 通过不能替代。
- Runtime/Root/网络问题要区分单元证据与真实设备验收，不伪造真机结论。

## 完成意味着

- 请求行为与验收标准满足；
- 相关检查结果可复核；
- diff 没有无关清理、调试代码、备份副本或临时文件；
- 文档与最终分支实际代码一致；
- 未验证的设备行为被明确写成未验证，而不是“已完成”。

## 验证

文档：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

其它命令见 `docs/contracts/05-ENGINEERING.md` 与 `CONTRIBUTING.zh-CN.md`。
