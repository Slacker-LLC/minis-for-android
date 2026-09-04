# 05 — 工程规范

## 怎么判断“当前事实”

```text
最终 master 源码与测试 → 当前实现事实
AGENTS.md + docs/contracts/* → 应保持的长期行为边界
06-CURRENT-GAPS.md → 当前实现与合同的已确认差异
README / 专题 docs → 面向读者的说明
历史 Issue/PR/计划/archive → 历史证据，不是当前事实
```

已经合并过的 PR 不能证明某个修复今天仍存在；集成、stacked PR 或后续 merge 可能让代码回归。审查和修复都必须读取最终目标分支实际代码。

合同也不能因为陈旧就凌驾于当前事实：发现合同与代码冲突时，先确认当前实现与维护者意图。若代码是缺陷，修代码并更新 gap；若合同已过时，更新合同，不要把正确代码改回旧阶段。

## 分支与 PR

- 默认基于最新 `master`；stacked PR 按明确 base/head 工作。
- 一次 PR 只解决一个问题或一条可独立验收边界。
- 不把 namespace 重命名、runtime、存储、MCP、网络等无关变化混在一个修复中。
- 不 force-push、不重写历史，除非维护者对该具体操作明确授权。
- 合并前核对最终 diff；合并后需要验证最终 `master` 的关键行为，而不是只看 PR 页面曾显示通过。

## 当前 Android 身份

- `applicationId = llc.slacker.minis` 已经是当前实现。
- `namespace = com.openminis.app` 仍是当前实现。
- 两者不同不是 bug；全库 Kotlin/Java package 重命名必须作为明确独立任务，不得在日常修复中顺手进行。

## Runtime 边界

- 产品 runtime：Root + `minisd` + Ubuntu 24.04 chroot。
- 不恢复 PRoot/Alpine 兼容层，除非产品范围被明确重新定义。
- 持久化真源：`/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`。
- UID/GID 动态取得，禁止固定 `10000`。
- Session 相关入口必须保持 session workspace/namespace 语义。
- 新 Root 执行只走结构化 `minisd` 边界；bootstrap/recovery 中的受控静态特权动作不得变成模型可控 shell。

## 外部实现参考

其它实现只能按功能选择性参考，不能整体替换本仓库已经明确分叉的 runtime/storage/PTY/network 架构。共享 UI、Chat、Provider、Markdown、语音等修复可以在核对本仓库实际代码后移植；Root/runtime 方案必须服从本仓库 contracts。

## 文档

- 行为或长期边界改变时，同步更新对应中文合同。
- 当前已确认缺陷进入 `06-CURRENT-GAPS.md`；修复合并并在最终 master 验证后再删除。
- 历史 Issue/PR 实施文档保留历史语境，不承担动态状态列表职责。
- `DEVELOPMENT-STATUS.md` 若带 SHA，只代表该 SHA 的快照；master 前进后要重新核对。
- 法律来源只在 `PROVENANCE.md` 维护。

## 验证（按改动范围取最小充分集）

文档：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

Android：

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
```

Release/R8/JNI 敏感改动还必须跑对应 Release 构建/检查；Debug 通过不能替代 Release 证据。

`minisd`：

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

Root、mount、SELinux、VPN/DNS、OEM 生命周期等设备行为，只有在明确设备上实际验证后才能声称通过。

## Agent 工作方式

- 先读当前目标分支代码、测试和相关合同，再决定方案。
- 优先最小修复；不要为理论风险自动建立大型基础设施。
- 复现与真实用户影响优先于架构洁癖。
- 已修问题如果在最终 master 回归，按当前代码重新修复并补能防止再次回归的最窄测试。
