# Source Provenance

法律与来源记录。不是产品说明，不是架构合同，也不是持续同步政策。

## Lineage

Minis for Android 含有从开源项目 **OpenMinis** 衍生的代码：

- repository: https://github.com/OpenMinis/OpenMinis
- identifier: `OpenMinis/OpenMinis`
- license at derivation: GPL-3.0
- website: https://openminis.app

本仓库由 Slacker-LLC 独立维护。产品行为以本仓库源码、测试与中文合同为准。

**没有**把 OpenMinis 变更持续同步进本仓库的政策。若再从任何外部项目导入代码，该变更必须标明来源、保留声明、满足许可证，并适配当前架构。

## Referenced projects

以下外部项目为本仓库提供行为参考，并且**其部分代码已被移植进来**：

- Eta — repository: https://github.com/Mangi-11/Eta
  - license: PolyForm Noncommercial License 1.0.0（非商业许可）
  - license text: [third_party/eta/LICENSE](third_party/eta/LICENSE)
    （含该许可要求的 `Required Notice: Copyright © 2026 蛮吉 (Mangi-11).`）
  - 移植来源 commit：`c15de97`。作者保留著作权；这些文件按 PolyForm Noncommercial 1.0.0
    使用与分发，不代表作者对本仓库其他部分作出 GPL 授权。
  - 移植范围与逐项清单见 `THIRD_PARTY_LICENSES.md`；同一处集中声明，代码文件内不重复写许可头。
  - 使用与分发必须满足该许可：仅限非商业用途，且随附上述许可条款与 Required Notice。
  - 逐项对照见 `docs/analysis/`。

## Obligations

继续按 GPL-3.0 分发。架构或包名变化不消除原著作权与许可证义务。再分发修改后的二进制时，按 GPL-3.0 提供对应源码并保留版权与许可声明。

不要删除衍生源文件中的版权头。

See [LICENSE](LICENSE), [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), [CONTRIBUTORS.md](CONTRIBUTORS.md).

历史执行模型（已废弃）见 [docs/archive/RUNTIME-HISTORY.md](docs/archive/RUNTIME-HISTORY.md)。现行合同见 [docs/contracts/](docs/contracts/00-IDENTITY.md)。
