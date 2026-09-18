# 开发移植规范（Eta → minis-eta）

本文件是移植工作的操作手册：怎么复用 Eta 代码、怎么落地、怎么验证、怎么登记归属。
目标与阶段顺序见 `docs/analysis/eta-port-program.md`；当前进度见 `PROGRESS.md`。

## 硬规则

1. **直接复用 Eta 源码**。参考源在 `/tmp/eta-upstream-clone`（`Mangi-11/Eta@ c15de97`）；
   能复用就复用，不要自己造轮子、不要只抄思路。
2. **归属集中，不逐文件写许可头**。新移植文件在 `THIRD_PARTY_LICENSES.md` 的 ported-modules
   表加一行（本仓库路径 / Eta 来源），许可条款只在 `PROVENANCE.md`、`THIRD_PARTY_LICENSES.md`、
   `third_party/eta/LICENSE` 维护。
3. **不改仓库既有 GPL-3.0 授权**；移植部分按 PolyForm Noncommercial 1.0.0。
4. **不放宽安全边界**：不引入 PRoot/Alpine 与多发行版安装器；Root 只做受控基础设施动作 + 结构化
   `root.shell`；不新增 raw command、通用 Root RPC 或 MCP 可见的特权路径；安全路径必须
   fail-closed（拒绝、越界、失败关闭都要有否定用例）。
5. **不碰其它仓库**（尤其 `/home/jiale/projects/minis-for-android`），只在本项目工作树内改动。
6. **不许为了过测试删改既有用例**。测试失败先判断是实现错还是期望错：实现与 Eta 一致时改期望，
   并在提交信息里写清算术或语义依据。

## 落地 SOP

1. **读来源**：把 Eta 对应文件读完整（不要只读片段），记录路径与关键常量。
2. **找落点**：在本仓库找现有调用链与状态源；能挂到已有工具/界面/仓储上就不要新建第二套。
3. **抽纯逻辑**：把可判定的部分抽成纯 Kotlin（分组、判定、编解码、预算），便于单测与复用。
4. **写否定用例**：拒绝/越界/降级/失效路径必须覆盖（分组边界、非追加输入、偏移失效、未授权、
   超限截断、脱敏命中、非法输入等）。
5. **接入调用链**：保持现有证据语义、权限检查、脱敏与超时边界不被绕过。
6. **验证**：见下方验证矩阵；宿主结论与设备结论分开写。
7. **提交**：英文提交信息，包含动机、行为、失败路径、来源与验证行。
8. **登记**：更新 `PROGRESS.md` 与归属表；长期边界有变化时同步 `docs/contracts/`。

## 提交信息模板

```text
<type>(<scope>): <一句话行为>

<为什么原来的行为不够 / 缺陷是什么>

<现在怎么做的；关键不变量与顺序>

失败路径：<拒绝 / 回滚 / 超限 / 降级 各是什么行为>

Ported from Eta <文件路径> (Mangi-11/Eta @ c15de97); attribution in PROVENANCE.md.

Verified: :app:compileDebugKotlin + :app:testDebugUnitTest (<N> tests).
Device behaviour (Root/SELinux/OEM/LSPosed/a11y window set) unverified.
```

## 验证矩阵（按改动范围取最小充分集）

| 改动范围 | 必跑 |
|---|---|
| 文档 | `python3 scripts/test_docs_provenance.py` + `check_docs_provenance.py` |
| Android 代码 | `:app:compileDebugKotlin` + `:app:testDebugUnitTest`（PR 前）；Release/R8/JNI 敏感改动另跑对应 Release 检查 |
| UI 渲染与布局 | 上述两项 + 真机（或界面测试）观感确认，不能用 JVM 单测替代 |
| runtime / payload | `scripts/test-build-ubuntu-rootfs-verification.sh`、`check-runtime-package-boundary.sh` 等 |
| root-network-proxy | `cargo fmt/clippy/test --locked --manifest-path src/native/root-network-proxy/Cargo.toml` |

## 工程约定（踩过的坑）

- **Gradle 全机串行**：所有 Gradle 调用都加 `flock /tmp/minis-gradle.lock`；并发会抢同一份
  `~/.gradle/caches` 并触发 KSP 缓存冲突。
- `rclone.aar` 被 gitignore：新工作树里不存在，构建前从 minis-for-android 复制，提交时忽略。
- 行号会漂移：分析文档里的 `文件:行号` 只作线索，定位以符号名为准。
- 既有编译警告（deprecation / opt-in / Java nullability）不属于本次改动，不要顺手改。
- 单测全量约 1841 个用例（2026-09-18 Phase 1 落地后），串行跑约 40–70 秒；新增用例保持同一节奏，避免引入需要真机的测试。

## 阶段与验收边界

- 一个阶段一个 PR，一次只解决一条可独立验收边界（见 `docs/contracts/05-ENGINEERING.md`）。
- 阶段完成 = 清单全部落地 + 验证矩阵通过 + `PROGRESS.md` 更新 + 归属表更新。
- 真机项登记在 `PROGRESS.md` 的「未验证清单」，不在代码注释里声称已验证。
