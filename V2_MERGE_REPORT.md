# OpenMinis Pet + Pi Agent + Web Remote V2 合并报告

日期：2026-08-20

## 目标

在上一版“Pet Runtime + Pi-style Agent + Web Remote”合并源码上继续完成四组改造，同时保持 `applicationId = dev.openminispet.android`，与官方 OpenMinis 共存：

1. 桌面宠物聊天改成真正独立的 Android 悬浮小窗，不阻断其他 App，也不暂停 Agent / Persistent Shell；失焦或返回键关闭。
2. 桌面宠物语音彻底复用 OpenMinis 已有 Voice Input / Voice Output Provider、Model、API 配置，不建立宠物专用 API 配置。
3. Pet / Web Remote 的新增原生 UI 复用 OpenMinis 自己的 Settings 组件；Web 页面也统一到相同 grouped-settings 视觉语言。
4. Web Remote 增加真实登录、默认回环监听以及 Cloudflare Tunnel 管理，满足无公网 IP 的域名远程管理。

## 主要实现

### 1. Pet Chat mini-window

新增：

- `pet/PetChatWindowView.kt`

修改：

- `pet/PetOverlayService.kt`

原先输入区与宠物本体共用同一个 Overlay；现在拆成两个窗口：

- 宠物 Sprite 窗口始终保持 `FLAG_NOT_FOCUSABLE`，不会抢走其他 App 的输入焦点。
- 聊天窗口单独使用 `TYPE_APPLICATION_OVERLAY`，带 `FLAG_NOT_TOUCH_MODAL`，只在用户聊天/打字时获得焦点。
- 点击其他 App 后，聊天窗口收到失焦并关闭。
- Android 返回键和软键盘返回键都会关闭聊天窗口。
- 小窗支持拖动。
- 小窗关闭只关闭 UI，不会 cancel 正在执行的 `askJob`；Agent / Session / Persistent Shell 继续运行。若回复回来时小窗已经关闭，宠物气泡仍可显示结果。

### 2. Pet voice 与 App Voice 配置打通

修改：

- `pet/PetOverlayService.kt`
- `speech/ReadAloudPlayer.kt`
- `pet/PetControlActivity.kt`
- `pet/PetControlScreen.kt`
- `pet/PetPreferences.kt`

语音输入每次开麦都会重新读取 App 的 ProviderRepository：

- `ensureDefaultVoiceInputGroup()`
- `ensureDefaultVoiceOutputGroup()`
- `resolveVoiceInputChoice()`
- 根据 App 当前选择切换 system/provider recognition engine
- 保留 App 的 `systemPreferOffline` 行为

语音回答通过新增的 `ReadAloudPlayer.speakConversation()` 进入原有 Voice Output 路径，因此复用同一个 Provider / Model / API Key / system fallback。

宠物设置中删除了重复的“独立读回复”偏好，改成只展示“跟随 App Voice Input / Voice Output”。

### 3. UI 一致性

`PetControlScreen` 改为直接复用 OpenMinis 的：

- `SettingsSection`
- `SettingsRow`

`RemoteAccessSettingsScreen` 同样使用：

- `SettingsScaffold`
- `SettingsSection`
- `SettingsSwitchRow`
- `SettingsRow`
- `SettingsCardBlock`

Pet mini-window 根据 OpenMinis `getThemeMode()` 跟随 App 的亮色/暗色模式，并采用源码同一组 grouped background、card、primary 等色值。

Web Remote 的 `index.html / app.css / app.js` 也改成 grouped settings 风格，颜色与 OpenMinis Light/Dark theme 对齐。

### 4. Web Remote 登录与网络暴露模型

修改：

- `remote/RemoteAccessPrefs.kt`
- `remote/RemoteAccessServer.kt`
- `remote/RemoteAccessService.kt`
- `ui/settings/RemoteAccessSettingsScreen.kt`
- `assets/remote/index.html`
- `assets/remote/app.css`
- `assets/remote/app.js`

安全行为：

- Web Remote 默认关闭。
- 未设置本机登录密码时，前台服务拒绝启动 Web Remote。
- 默认 bind `127.0.0.1`；只有显式开启“局域网访问”才 bind `0.0.0.0`。
- 用户名默认 `admin`，可修改。
- 密码使用随机 16-byte salt + PBKDF2-HMAC-SHA256，210,000 iterations，256-bit derived key；不保存明文密码。
- 登录成功后浏览器只拿 12 小时随机 Session Cookie：`HttpOnly; SameSite=Strict`，经 Cloudflare / HTTPS 访问时增加 `Secure`。
- 浏览器不再把长期 Bearer Token 放进 `localStorage`。
- 备用 Bearer Token 仍保留给 CLI / 自动化，并走 App 现有 `EncryptedPrefsFactory`。
- Cloudflare Tunnel Token 同样走加密偏好，网页 GET API 永远不返回 Token 原文。
- Cookie 认证的 POST / PUT / PATCH / DELETE 额外执行 same-origin 检查。
- 登录连续失败 5 次后进入 60 秒锁定。
- CSP、X-Frame-Options、nosniff、no-referrer、Permissions-Policy 等响应头保留/加强。

网页中的 Settings 可以修改：

- 登录用户名
- 登录密码（需要当前密码）
- Web Remote 端口
- LAN access
- Cloudflare Tunnel 开关
- Cloudflare hostname（显示/提醒用途）
- Cloudflare Tunnel Token（write-only）

端口 / bind address 改变时会要求并触发 Web Remote restart。

### 5. Cloudflare Tunnel

新增：

- `remote/CloudflareTunnelManager.kt`

实现方式：

- 使用 OpenMinis 现有 PRoot，而不是另装一套容器。
- App 可下载 Cloudflare 官方 Linux ARM64 `cloudflared` 到 PRoot rootfs 的 `/opt/bin/cloudflared`。
- 下载大小有 80 MiB 上限；安装后会先运行 `cloudflared version` 验证它确实能在该 PRoot 中执行，失败则删除二进制并给出错误。
- remotely-managed Tunnel Token 通过 `TUNNEL_TOKEN` 环境变量传给 `cloudflared tunnel --no-autoupdate run`，不放进命令行参数。
- Web Remote 前台服务同时负责 Cloudflare connector 的生命周期。
- Tunnel 关闭 / Web Remote 停止时会停止 cloudflared。

公开域名的 authoritative Published Application route 仍由 Cloudflare 控制面管理；App 不伪造 Cloudflare Account API 权限。典型 Service URL：

`http://127.0.0.1:8765`

若 App 中修改了 Web Remote 端口，需要同步修改 Cloudflare Published Application 的 Service URL。

## 本次 V2 变更文件

共 17 个关键文件（15 修改 + 2 新增）：

- `src/android/app/src/main/assets/remote/app.css`
- `src/android/app/src/main/assets/remote/app.js`
- `src/android/app/src/main/assets/remote/index.html`
- `src/android/app/src/main/java/com/openminis/app/pet/PetChatWindowView.kt` (new)
- `src/android/app/src/main/java/com/openminis/app/pet/PetControlActivity.kt`
- `src/android/app/src/main/java/com/openminis/app/pet/PetControlScreen.kt`
- `src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt`
- `src/android/app/src/main/java/com/openminis/app/pet/PetPreferences.kt`
- `src/android/app/src/main/java/com/openminis/app/remote/CloudflareTunnelManager.kt` (new)
- `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessPrefs.kt`
- `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt`
- `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessService.kt`
- `src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/settings/RemoteAccessSettingsScreen.kt`
- `src/android/app/src/main/res/values-zh/strings.xml`
- `src/android/app/src/main/res/values/strings.xml`

## 验证记录

已通过：

- `git diff --check`
- V2 patch `git apply --check`
- 从上一版合并源码应用 V2 patch 后，整个 1722 文件工作树逐字节一致
- 从用户 Pet 源码应用旧 Pi/Web patch，再应用 V2 patch，17 个本次关键文件与最终工作树一致
- 原 Pet patch 自带 fixture / apply regression test PASS
- `node --check`：Web Remote `app.js` PASS
- HTML parser：`index.html` PASS
- 8 份 Android `strings.xml` XML parse PASS
- 两份 GitHub Actions YAML parse PASS
- 改动 Kotlin 文件使用本地 `kotlinc` 做 parser-level syntax scan，无 Kotlin 语法诊断

未完成、不能伪称完成：

- 当前容器无法执行真正 Android Gradle compile。最终树执行 `./gradlew :app:compileDebugKotlin --offline --stacktrace` 时，Gradle Wrapper 仍试图获取 `gradle-8.11.1-bin.zip`，网络解析 `services.gradle.org` 失败，实际错误为 `java.net.UnknownHostException: services.gradle.org`。
- 因此当前交付不声称 APK 已编译成功，也不声称 Pet mini-window / cloudflared 已经在用户真机完成运行验证。
- GitHub Actions builder 会在有 Android SDK 和外网的 Runner 上真正执行 `:app:testDebugUnitTest :app:assembleDebug`。

## GitHub Actions 构建链

固定基线默认 OpenMinis 1.12：

1. `apply_patch.py`：Pet Runtime
2. `pi-web-on-pet-1.12.patch`：Pi-style Agent + 第一版 Web Remote
3. `pet-pi-web-v2-on-merged.patch`：本次 mini-window / unified voice / login / Cloudflare / UI 改造
4. 构建 PRoot + Android sandbox
5. `:app:testDebugUnitTest`
6. `:app:assembleDebug`
7. 验证 applicationId、签名、ARM64 库、悬浮窗/前台服务权限
8. 上传 APK + SHA256 + BUILD-INFO
