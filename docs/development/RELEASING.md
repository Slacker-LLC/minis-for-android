# 版本与发版

`main` 就是当前版本，没有单独的分支。**只有历史版本才有自己的分支**：一个版本一个分支，比如当前是 2.0 时，才会有 `release/v1.0`。

## 版本号

| 种类 | `versionName` | tag | GitHub Release |
|---|---|---|---|
| beta | `X.Y-beta.N`、`X.Y.Z-beta.N` | `vX.Y-beta.N` | **prerelease** |
| 正式版 | `X.Y` | `vX.Y` | 正式 |
| 补丁 | `X.Y.Z`（`Z ≥ 1`） | `vX.Y.Z` | 正式 |
| 开发（可选，不发布） | `X.Y-dev` | 不打 tag | 不发 |

- 不写 `X.Y.0`：第一个版本就叫 `X.Y`。
- `versionName` 只在 [`src/android/app/build.gradle.kts`](../../src/android/app/build.gradle.kts) 的 `appVersionName` 设置，别处不写。
  `main` 的 `versionName` 只在发版提交里改；两次发版之间保持上一次发布的版本。
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
  | `1.1-beta.1` | 1010001 |
  | `1.1` | 1010099 |

- `VersionSchemeTest` 固定这套方案，并断言 `versionCode` 的顺序与应用内更新器的排序一致。

## 分支

- **`main`**：当前版本。它的 beta、正式版和补丁都直接在 `main` 上打 tag。所有改动经 PR 进入 `main`。
- **`release/vX.Y`**：历史版本 `X.Y` 的分支，长期保留，不删。只在这个版本被新版本取代、`main` 要离开它的时候才创建；之后该版本的补丁（`X.Y.Z`）在这里发。
  - 分支名带 `release/` 前缀，是因为 tag 也叫 `vX.Y`：分支和 tag 同名时 `git push origin vX.Y`、`git checkout vX.Y` 会报 ambiguous。
  - 修复先进 `main`，再 `git cherry-pick -x` 到 `release/vX.Y`；`release/*` 不合回 `main`。
  - 对它的改动同样走 PR（base 选该分支），CI 通过后再合并。

没有 `release/v1.0`，直到 `main` 要离开 1.0。

## 流程

### 发 beta、正式版、补丁（当前版本，在 `main` 上）

1. 提一个发版 PR：`appVersionName` 改为目标版本，`CHANGELOG.md` 加 `## <版本> — 日期`，合并。
2. 在合并后的 `main` 上预检、构建、打 tag、发布（见下）。

beta 与正式版用同一条流程：`1.1-beta.1 → 1.1-beta.2 → 1.1`。

### 让一个版本成为历史（创建它的分支）

在 `main` 离开 `X.Y` 之前，也就是把 `appVersionName` 改成下一个版本的第一个 beta **之前**，从 `X.Y` 最新的 tag 切出分支：

```bash
git branch release/vX.Y vX.Y        # 补丁线已有 vX.Y.Z 时，用最新的那个 tag
git push origin release/vX.Y
```

之后 `X.Y` 的补丁只在这个分支上发：cherry-pick 修复、`appVersionName` 改为 `X.Y.Z`、同样预检和打 tag。

### 预检、打 tag、发布

```bash
scripts/release-check.sh vX.Y-beta.1
```

脚本只检查、只打印命令，不会打 tag 或推送。它核对：tag 与 `appVersionName` 一致；当前在对的分支（当前版本在 `main`，已成为历史的版本在它自己的 `release/vX.Y`）；工作区干净；`HEAD` 就是远端同名分支的末端；tag 尚不存在；`CHANGELOG.md` 有对应标题。

然后用生产密钥构建、验证，再打 tag：

```bash
scripts/verify-android-release.sh path/to/app-release.apk
git tag -a vX.Y -m "Minis for Android X.Y" && git push origin vX.Y
gh release create vX.Y --verify-tag --title "X.Y" --notes-file NOTES.md path/to/app-release.apk
```

beta 加 `--prerelease`。

## 重发一个已发布的版本（覆盖）

默认做法是发补丁（`X.Y.Z`），不改已发布的 tag。只有所有者明确决定"用当前 `main` 重做 `X.Y`"时才覆盖，并按下面做；这是对外可见、难以撤回的操作，所以要先留退路：

1. 把现有的 APK 和 Release 说明下载到本地备份（`gh release download vX.Y`、`gh release view vX.Y --json body`）。
2. 文档、`CHANGELOG.md` 先经 PR 合并；在 `CHANGELOG.md` 的该版本下写一节"重新构建"，说明与首次发布的差别。
3. 在合并后的 `main` 上用生产密钥构建，`scripts/verify-android-release.sh` 通过后再动 tag。
4. 移动 tag（`Protect release tags` 只允许管理员）：`git tag -fa vX.Y -m "Minis for Android X.Y"`，`git push --force origin vX.Y`。
5. `gh release edit vX.Y --notes-file NOTES.md`，再 `gh release upload vX.Y <新 apk> --clobber`，并核对 Release 上的 APK SHA-256 与本地一致。

覆盖发布的限制：`versionName` 与 `versionCode` 不变（1.0 是 1000099），所以已安装旧 1.0 的设备不会被应用内更新器提示更新，只能手动安装新 APK；签名密钥相同，可直接覆盖安装并保留数据。需要让用户自动收到更新时，应改发 `X.Y.1`。

## APK 与签名

- 只有用项目生产密钥签名的构建才能作为 APK 附在 Release 上。构建需要环境变量 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`，缺任何一个都会失败，而不是回退到 debug 密钥。
- 没有可用的生产密钥时，可以先发不带 APK 的 Release，之后用 `gh release upload` 补；应用内更新器看到没有 APK 的版本时只会提示"有新版但未附 APK"。
- 一个 Release 只附一个 `.apk`（更新器取第一个）。
- 密钥与 APK 不进 Git。

## 应用内更新通道

更新器读取 GitHub Releases 列表，按版本顺序判断：

- **稳定版构建**只会被推送稳定版，看不到 prerelease。
- **beta / dev 构建**能看到 prerelease 和正式版：`1.1-beta.1 → 1.1-beta.2 → 1.1`。
- 所以 beta 在 GitHub 上必须标成 prerelease，否则稳定版用户会被推送。
- 想试 beta 的稳定版用户手动安装 beta 的 APK；之后它按 beta 通道收更新。
- 已知：发 1.0 之前的 `1.01-beta.*` 构建会把 `1.01` 视为比 `1.0` 新，不会被推送 1.0，手动安装即可（`versionCode` 更高，原地升级并保留数据）。

## CI、分支保护与仓库权限

仓库 `Slacker-LLC/minis-for-android` 属于个人账号 `Slacker-LLC`，不是组织：账号所有者就是管理员，目前也是唯一的协作者，没有团队。**只有所有者/管理员能合并 PR、创建或更新分支、移动或删除发版 tag**；其他人只能 fork 后提 PR。

- CI：PR 以及 `main`、`release/**` 的 push 都跑。新建的 release 分支第一次 push 会按全量改动跑完整流水线。
- 规则集 `Protect main and release branches`（`main` 与 `release/**`）：必须经 PR；必须通过 `Android build, test and release checks`、`Documentation provenance guard`、`Build cleanup guard`；只有拥有绕过权限的人能创建或更新这些分支；禁止强推和删除。绕过权限只给仓库管理员角色，且模式是“仅通过 PR”：管理员也不能直接往 `main` 推送，只能合并 PR。
- 规则集 `Protect release tags`（`v*`）：只有管理员能创建、更新、删除，没人能移动一个已发布的 tag。
- 合并方式只允许 squash 与 rebase，不允许 merge commit。
- Actions：默认令牌只读、不能批准 PR；外部贡献者的 fork PR 触发 CI 前都要人工批准。
- 安全：已开启私密漏洞报告（见 [SECURITY.md](../../SECURITY.md)）、密钥扫描与推送保护、Dependabot 安全更新。

以后加协作者：即使给了 Write 权限，规则集也不允许他们合并到 `main`/`release/**` 或打 tag；需要让某人能合并，就把他提升为管理员，或在规则集的绕过名单里加他。
