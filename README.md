# OpenMinis Pet

> **这是 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 官方项目的非官方分叉，只做了一点二次创作。**
> 核心的 Agent、PRoot 沙盒、模型接入等等全部是官方的功劳，本仓库只是在上面加了桌面宠物、
> 补了 Web 远程控制。遇到问题请先确认是不是本分叉引入的，**不要去打扰上游维护者**。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

基线：官方 `1.12`（versionCode 24）。上游说明保留在 [README-upstream.md](README-upstream.md)。

## 和官方版什么关系

| | 官方 OpenMinis | 本分叉 |
|---|---|---|
| applicationId | `com.openminis.app` | `dev.openminispet.android` |
| 应用名 | Minis | OpenMinis Pet |
| 能否共存 | — | **可以同时安装** |

Android 以 `applicationId` 作为安装身份，所以装了这个不会覆盖官方版。Kotlin namespace
仍然保持 `com.openminis.app`，是为了避免对整棵源码树做一次高风险的包名重命名。

## 加了什么

### 桌面宠物

一个通用的宠物运行时——不把某一只宠物硬编码进 APK，而是导入 ZIP 宠物包：

```text
my-pet.zip
├── pet.json          # id / displayName / spritesheetPath
└── spritesheet.webp  # 默认 8 列 × 9 行，单格 192×208
```

- **点一下就能聊天**：点宠物弹出输入框，直接问问题，回答显示在气泡里，同时写进 App 的会话
  历史（会话名「桌面宠物」），不会聊完就没了
- **语音**：复用 App 自己的 Voice Input / Voice Output 配置，不另起一套 API 设置
- **会自己动**：空闲时随机巡游、拖动后吸附边缘、久置贴边隐藏只露一点，点一下滑回来
- **跟着 Agent 状态走**：`running / waiting / review / failed / idle`，任务完成时招手说一句
- 悬浮窗权限并入官方的「设置 → 权限 → 系统权限」页，宠物页只做提示

宠物对话直连当前默认模型，**不跑 Agent 工具链**：能问答能总结，不能执行命令或读写文件。
真要干活还是在 App 里开正常会话。

### Web 远程控制

浏览器里管手机上的 Agent：

- 会话列表、对话、**Markdown 渲染**（代码块带语言标签和复制按钮、表格、列表、引用）
- 回复逐段增长的流式观感
- 文件浏览 / 在线编辑、Shell 执行
- 登录鉴权：PBKDF2-HMAC-SHA256（210k 轮）+ 12 小时 HttpOnly Session Cookie，
  **没设密码就拒绝启动**；默认只监听 `127.0.0.1`，要开局域网得显式打开
- Cloudflare Tunnel 管理，没有公网 IP 也能用域名访问

### Pi 风格 Agent

见 [MERGE_REPORT.md](MERGE_REPORT.md) 与 [V2_MERGE_REPORT.md](V2_MERGE_REPORT.md)。

## 装了之后

1. 「设置 → 权限 → 系统权限 → 显示在其他应用上层」授权
2. 「设置 → 外观 → 桌面宠物」导入宠物包 ZIP，启动宠物
3. 想让宠物能说话，先在设置里配好默认模型（Provider + API Key）

Web 远程控制在「设置 → Web 远程控制」，**必须先设登录密码**才能启动。

## 构建

见 [BUILD-CN.md](BUILD-CN.md)。简单说：必须在 Linux / WSL 里，需要 JDK 17 + Android SDK 36
+ NDK r28，只出 `arm64-v8a`。

```bash
git clone --recursive https://github.com/limuzi013/OpenMinis-Pet.git
```

`--recursive` 不能省——`deps/proot` 和 `deps/ish` 是 submodule，缺了它们构建不出沙盒。

## 已知限制

- **语音识别依赖设备或云端引擎**。部分国产 ROM 的系统识别不可用
  （`SpeechRecognizer.isRecognitionAvailable()` 返回 `false`），这时要在
  「设置 → 语音」给 Voice Input 组绑一个云端 ASR 模型，宠物的麦克风才能用。
- 宠物对话没有工具调用能力（见上）。
- 只构建 `arm64-v8a`，不支持 32 位设备和 x86 模拟器。
- Release 版用 debug 签名（沿用上游配置），仅供自用。

改动的完整清单见 [CHANGELOG-FORK.md](CHANGELOG-FORK.md)。

## License

跟随上游，**GPL-3.0**。分发修改后的 APK 同样受 GPL-3.0 约束，需要一并提供对应源码——
本仓库即是。

原项目版权归 OpenMinis 作者所有。
