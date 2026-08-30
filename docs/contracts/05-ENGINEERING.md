# 05 — 工程规范

## 权威顺序

```text
源码与测试
  > AGENTS.md 与 docs/contracts/*（中文合同）
  > README.zh-CN.md 与 CONTRIBUTING.zh-CN.md
  > 英文 README / CHANGELOG / 英文 docs
  > docs/archive/ 与 PROVENANCE.md
```

合同描述目标。`06-CURRENT-GAPS.md` 描述现状。禁止把 Gaps 里的未完成项写成已实现。

## 分支与 PR

- 默认基于 `master`。
- **冻结**：在存储合同落地前，不要再开平行 Draft 同时改 runtime / 身份 / 发行管线。
- 已存在的 Draft 不在本轮合并。先合宪法，下一轮只修存储真源。
- 一次 PR 只碰一条合同。不要在同一 PR 里改包名又改 minisd 又改文档叙事。

## 身份迁移

目标包名已在 `00-IDENTITY.md` 冻结为 `llc.slacker.minis`。实施必须单独立项：含旧安装是否放弃、数据是否导出。禁止全库盲替换。

## 文档

- 新增/变更行为：先改对应中文合同，再改代码，再改测试。
- 产品入口：`README.zh-CN.md`。
- 法律入口：`PROVENANCE.md`（允许出现来源项目名；其它现行文档不允许把来源项目当产品身份）。
- Issue/PR 用中文把问题说清楚；不要用空标题当远程排障工单。

## 验证（按改动范围取最小集）

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

`minisd`：

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

真机测试只在明确授权的设备上跑。

## Agent 工作方式

- 先读 `AGENTS.md` 与相关合同，再改代码。
- 发现合同与代码不符：更新 `06-CURRENT-GAPS.md`，不要偷偷改合同去迁就错误实现（除非维护者明确废弃该合同）。
- 不要提交密钥、APK、或一次性「one-shot」workflow 当常设 CI。
