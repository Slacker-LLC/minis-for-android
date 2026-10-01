# 版本与发版

一个版本一个分支。`main` 是开发线，`release/X.Y` 是某个版本的线；beta、正式版和补丁都在这条线上打 tag。

## 版本号

| 种类 | `versionName` | tag | GitHub Release |
|---|---|---|---|
| 开发（仅 `main`） | `X.Y-dev` | 不打 tag | 不发 |
| beta | `X.Y-beta.N`、`X.Y.Z-beta.N` | `vX.Y-beta.N` | **prerelease** |
| 正式版 | `X.Y` | `vX.Y` | 正式 |
| 补丁 | `X.Y.Z`（`Z ≥ 1`） | `vX.Y.Z` | 正式 |

- 不写 `X.Y.0`：第一个版本就叫 `X.Y`。
- `versionName` 只在 [`src/android/app/build.gradle.kts`](../../src/android/app/build.gradle.kts) 的 `appVersionName` 设置，别处不写。
- `versionCode` 由它推导，不要手填：

  ```text
  versionCode = major × 1 000 000 + minor × 10 000 + patch × 100 + stage
  stage       = 0（-dev） | N（-beta.N，1..98） | 99（正式）
  ```

  所以顺序恒为 `X.Y-dev < X.Y-beta.N < X.Y < X.Y.1-beta.1 < X.Y.1`，且 `1.0.1` 一定小于 `1.1-beta.1`。
  `minor`、`patch` 限 0..99，`beta` 限 1..98；格式不对时构建直接失败。

  | `versionName` | `versionCode` |
  |---|---|
  | `1.0` | 1000099 |
  | `1.0.1` | 1000199 |
  | `1.1-dev` | 1010000 |
  | `1.1-beta.1` | 1010001 |
  | `1.1` | 1010099 |

- `VersionSchemeTest` 固定这套方案，并断言 `versionCode` 的顺序与应用内更新器的排序一致。

## 分支

- **`main`**：开发线，永远是最高版本，`versionName` 为 `X.Y-dev`（`X.Y` 是下一个要发的版本）。所有改动先经 PR 进入 `main`。
- **`release/X.Y`**：一个版本一个分支，长期保留，不删。该版本的 beta、正式版、补丁（`X.Y.Z`）都在这里打 tag。
  - 这条线上不做新功能，只收修复。
  - **修复先进 `main`，再 `git cherry-pick -x` 到 `release/X.Y`**；`release/*` 不合回 `main`。
  - 对 `release/X.Y` 的改动同样走 PR（base 选该分支），CI 通过后再合并。
- 当前：`release/1.0`（正式版 `1.0`）。

`main` 保持最高版本是有意的：开发者在手机上反复安装 `main` 的构建，如果它的 `versionCode` 低于已装的 beta，就会因降级而装不上。

## 流程

### 开一个新版本（进入 beta）

1. 从 `main` 切分支：`git switch -c release/X.Y main`，推送。
2. 在 `release/X.Y` 上提交 `chore(release): X.Y-beta.1`：把 `appVersionName` 改为 `X.Y-beta.1`，在 `CHANGELOG.md` 加 `## X.Y-beta.1 — 日期`。走 PR，base 为 `release/X.Y`。
3. 在 `main` 上提 PR，把 `appVersionName` 改为 `X.(Y+1)-dev`。
4. 合并后预检、打 tag、发布（见下）。

### 下一个 beta、正式版、补丁

- 下一个 beta：把修复 cherry-pick 到 `release/X.Y`，`appVersionName` 改为 `X.Y-beta.2`，同上。
- 正式版：`appVersionName` 改为 `X.Y`，`CHANGELOG.md` 加 `## X.Y — 日期`，tag `vX.Y`，Release 不勾 prerelease。
- 补丁：在 `release/X.Y` 上 cherry-pick 修复，`appVersionName` 改为 `X.Y.Z`，tag `vX.Y.Z`。

### 预检、打 tag、发布

```bash
scripts/release-check.sh vX.Y-beta.1
```

脚本只检查、只打印命令，不会打 tag 或推送。它核对：tag 与 `appVersionName` 一致、当前在 `release/X.Y`、工作区干净、`HEAD` 就是 `origin/release/X.Y` 的末端、tag 尚不存在、`CHANGELOG.md` 有对应标题。

然后用生产密钥构建，验证，再打 tag：

```bash
scripts/verify-android-release.sh path/to/app-release.apk
git tag -a vX.Y -m "Minis for Android X.Y" && git push origin vX.Y
gh release create vX.Y --verify-tag --title "X.Y" --notes-file NOTES.md path/to/app-release.apk
```

beta 加 `--prerelease`。

## APK 与签名

- 只有用项目生产密钥签名的构建才能作为 APK 附在 Release 上。构建需要环境变量 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`，缺任何一个都会失败，而不是回退到 debug 密钥。
- 一个 Release 只附一个 `.apk`（更新器取第一个）。
- 密钥与 APK 不进 Git。

## 应用内更新通道

更新器读取 GitHub Releases 列表，按版本顺序判断：

- **稳定版构建**只会被推送稳定版，看不到 prerelease。
- **beta / dev 构建**能看到 prerelease 和正式版：`1.1-beta.1 → 1.1-beta.2 → 1.1`。
- 所以 beta 在 GitHub 上必须标成 prerelease，否则稳定版用户会被推送。
- 想试 beta 的稳定版用户手动安装 beta 的 APK；之后它按 beta 通道收更新。
- 已知：发 1.0 之前的 `1.01-beta.*` 构建会把 `1.01` 视为比 `1.0` 新，不会被推送 1.0，手动安装即可（`versionCode` 更高，原地升级并保留数据）。

## CI

PR 以及 `main`、`release/**` 的 push 都跑 CI。新建的 release 分支第一次 push 会按全量改动跑完整流水线。

## 建议的仓库设置（未启用）

对 `main` 和 `release/**` 开启分支保护：必须经 PR、必须通过状态检查、禁止强推和删除。这是仓库设置，由仓库所有者决定。
