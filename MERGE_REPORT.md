# OpenMinis Pet + Pi-style Agent + Web Remote — Merge Report

## 合并基线

本目录由两条已存在的改造线合并：

- Pet 分支：通用桌面宠物运行时，安装身份 `dev.openminispet.android`
- Pi/Web 分支：Pi-style coding-agent 工具增强 + Web Remote

合并策略不是覆盖文件，而是以 Pi/Web 完整源码为主体，再用 Pet 的 `apply_patch.py` 在其上重放 Pet 改动。Pet 脚本的唯一锚点全部匹配并通过自带 `verify()`。

## 同时保留的主要能力

### Pet Runtime

- 11 个 `com.openminis.app.pet` Kotlin 源文件
- 通用 pet ZIP 导入、spritesheet 动画、悬浮窗、拖拽/吸附/隐藏
- 宠物快捷对话、语音入口、Agent 状态联动
- `PetControlActivity` / `PetOverlayService`
- `applicationId = dev.openminispet.android`
- PRoot native-offload abstract socket 按 `BuildConfig.APPLICATION_ID` 隔离
- 系统权限页的悬浮窗权限入口

### Pi-style Agent

- `FileEditEngine` multi-edit
- `FileMutationQueue` 同文件公平串行写入
- `FileRevision` SHA-256 revision
- `ShellOutputTruncator` 大输出落盘/上下文截断
- FileEdit/FileWrite/ExecutionCoordinator/ChatViewModel 对应改造
- Pi/Web 分支自带的 JVM 单元测试文件

### Web Remote

- `RemoteAccessPrefs` / `RemoteAccessServer` / `RemoteAccessService`
- `RemoteAccessSettingsScreen`
- `assets/remote/index.html`, `app.js`, `app.css`
- Manifest 中 Web Remote foreground service
- Settings / AppNavigation 中 Web Remote 入口
- Bearer Token、4 MiB body limit、32 并发、30 s socket timeout、CSP/no-store 等边界保持原样

## 交叉冲突文件

实际需要同时容纳两条分支语义的关键文件：

- `src/android/app/build.gradle.kts`
- `src/android/app/src/main/AndroidManifest.xml`
- `MinisApp.kt`
- `AgentForegroundService.kt`
- `SettingsScreen.kt`
- `SystemPermissionsScreen.kt`
- `sandbox/NativeOffload.kt`

Pet 补丁在 Pi/Web 版本上重放成功，因此没有使用模糊整文件覆盖。

## 已执行验证

- Pet `apply_patch.py` verify：PASS
- Pet patch fixture regression test：PASS
- 11 个 Pet Kotlin 文件与 Pet 补丁包逐字节一致：PASS
- 合并结果同时包含 Pet/Web Remote/Agent 增强关键文件和关键 hooks：PASS
- Android Manifest + 8 份 strings XML：XML parse PASS
- `assets/remote/app.js`：`node --check` PASS
- 从 Pet 1.12 基线应用 `pi-web-on-pet-1.12.patch` 后与本完整合并源码逐字节一致：PASS

## 尚未完成的验证

当前执行环境没有 Android SDK，Gradle wrapper 还需要下载 Gradle 8.11.1；网络访问 `services.gradle.org` 失败，因此这里无法诚实宣称 `assembleDebug` 已成功。

真正的最终验收仍应执行：

```bash
cd src/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

配套的 GitHub Actions builder 会在 Ubuntu + JDK 17 + Android SDK 36 + NDK r28 + CMake 3.22.1 环境中自动执行这些步骤。

## 公网 Web Remote

不要直接把手机的 8765 明文 HTTP 端口映射到公网。公网访问应由 Caddy、Cloudflare Tunnel 或其他反向代理终止 HTTPS，再转发到手机 Web Remote。
