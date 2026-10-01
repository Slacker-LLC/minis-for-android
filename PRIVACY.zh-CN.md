# 隐私政策

[English](PRIVACY.md)

**Minis for Android** · 发布者：Slacker-LLC · 应用标识 `llc.slacker.minis`
自 2026-10-02 起生效 · 适用于 1.0 及之后的版本，直至被新版本取代。

## 简述

- Minis for Android **没有自己的后端**：没有账号、无需注册，没有统计分析、广告或追踪 SDK，也没有远程崩溃上报。
  Slacker-LLC 不会收到你的对话、文件、密钥或设备数据。
- 应用保存的一切都在**设备上应用的私有存储**中。
- 内容只在你配置了某个目的地、或使用了需要联网的功能时才会离开设备：你选择的模型服务商、语音服务商、MCP 服务器、
  Agent 发起的网络请求、备份远端。第 3 节逐项列出。
- 这是一个拥有真实设备权限的 Agent。**工具读到的任何内容，都可能作为对话的一部分发送给你的模型服务商**，请据此授权。

## 1. 适用范围

Minis for Android 是面向已 Root Android 设备的开源（GPL-3.0）AI Agent Runtime，由 Slacker-LLC 在
<https://github.com/Slacker-LLC/minis-for-android> 发布。本政策只覆盖应用本身。你接入的模型服务商、语音服务商、
MCP 服务器、网站与备份服务由其他方运营，各有其隐私政策。若你自行构建、修改或再分发本应用，你自己构建版本的数据处理由你负责。

## 2. 留在设备上的数据

| 数据 | 位置 |
|---|---|
| 对话、附件、记忆、技能、设置、工作区与 Linux（Ubuntu）环境文件 | 应用私有存储（`Context.filesDir`） |
| 服务商 API Key 与登录令牌 | 应用私有存储中的 Android 加密偏好存储 |
| 崩溃与诊断日志 | 应用私有的 `logs` 目录 |

- 崩溃日志包含堆栈、系统日志末尾若干行、应用版本、Android 版本与设备型号。它们**不会被自动上传**，你可以自行查看、分享或删除。
- 应用不参与 Android 自动备份（`allowBackup="false"`）。只有你主动导出时才会生成备份压缩包，且只会去往你指定的位置。
- 敏感工具的输入与输出不会进入、或会被脱敏后再进入持久化的对话记录与检查点，详见 [SECURITY.md](SECURITY.md)。
  但这不影响它们在使用它们的那一轮被发送给模型。

## 3. 离开设备的数据，及何时发生

| 目的地 | 发送的内容 | 何时 |
|---|---|---|
| **你配置的模型服务商**，例如 OpenAI、Anthropic、Google Gemini、xAI、OpenRouter、DeepSeek、Kimi、MiniMax、小米 MiMo、阿里云百炼（DashScope）、Azure OpenAI，或你填写的任何兼容端点 | 对话、系统提示词、附件（图片、文件）、工具结果，以及请求头中的 API Key 或令牌 | 你或 Agent 每次发起请求；刷新模型列表时也会请求 |
| **服务商登录**（ChatGPT/OpenAI、Google、xAI、OpenRouter、Claude） | 与该服务商自己的登录页面完成 OAuth 交换；得到的令牌存放在设备上，且只发送给该服务商 | 登录或刷新令牌时 |
| **你启用的语音服务商**：Microsoft Azure 语音、字节跳动火山引擎、讯飞、ElevenLabs、Deepgram，或 Android 系统语音服务 | 你录入的音频或待朗读的文本 | 仅在该语音功能开启并使用时。系统语音服务由设备厂商运营 |
| **你添加的 MCP 服务器** | 工具调用、参数与结果 | Agent 调用该服务器的工具时。应用自带的 MCP 服务只监听本机回环地址，并要求 Bearer 令牌 |
| **Agent 的网络访问** | 请求的 URL 与页面请求；网页搜索的查询词发往 DuckDuckGo（`html.duckduckgo.com`）；天气查询会把 Agent 给出的坐标发往 Open-Meteo（`open-meteo.com`） | Agent 或 Linux 环境使用这些工具时 |
| **软件包镜像**（apt、pip、npm 等包管理器） | 来自 Ubuntu 环境内部的软件包请求 | 环境内命令安装或更新软件包时 |
| **GitHub** | 普通 HTTPS 请求：`api.github.com` 用于检查更新（仅在你点"检查更新"时）；`raw.githubusercontent.com` 用于刷新模型规则；从 GitHub 导入技能时访问 GitHub | 如上所述；不含账号数据，也不含对话内容 |
| **models.dev** | 对公开模型元数据的普通请求 | 后台刷新模型目录时 |
| **你配置的备份目的地**（rclone 远端，如 SMB、WebDAV、S3） | 你选择导出的备份压缩包 | 仅在你执行备份时 |
| **GitHub Issues**（"提交 GitHub Issue"） | 在你于浏览器中提交表单之前不发送任何内容。表单会预填应用版本、Android 版本与设备型号 | 你打开表单时；你在那里发布的内容是公开的 |

与任何网络请求一样，上述每一项都会向你所联系的主机暴露你的 IP 地址和常规 HTTP 元数据。发往 OpenRouter 的请求还会带有指明本应用及其仓库的归属请求头。

## 4. 设备数据与权限

应用声明了较多权限，因为它的工具是可选能力：位置、通讯录、日历、短信与通话记录、相机、麦克风、照片与媒体、全部文件访问、
蓝牙与附近 Wi-Fi、使用情况统计、通知、悬浮窗、无障碍控制、安装软件包、精确闹钟，以及 Root 或 Shizuku。使用某项权限前 Android 会询问你，
你可随时在系统设置中撤回。

权限在设备上用于完成你或 Agent 请求的操作。工具返回的内容会成为对话的一部分，而对话会发往你的模型服务商。如果你不希望模型看到某些内容，
就不要让 Agent 访问它。Root 与无障碍的边界见 [docs/SECURITY.md](docs/SECURITY.md) 与
[docs/contracts/04-SECURITY-CONTRACT.md](docs/contracts/04-SECURITY-CONTRACT.md)。

## 5. 保留与删除

Slacker-LLC 不持有你的任何数据副本，因此没有需要我们删除的内容。在设备上，你可以在应用内删除对话、记忆与技能；清除应用存储或卸载应用会移除应用的全部私有数据。
已导出的备份与备份远端上的副本由你自行删除。发送给模型或语音服务商的数据按该服务商的政策保留，请向其申请删除。

## 6. 儿童

本应用不面向儿童。

## 7. 安全

凭证不会写入仓库、诊断输出或未脱敏日志（[SECURITY.md](SECURITY.md)）。发现漏洞请通过本仓库的私密漏洞报告渠道提交，不要公开提 Issue。

## 8. 变更

本文件 `PRIVACY.md`（及本中文版）即政策本身。变更在仓库中进行，提交历史可见改了什么、何时改的；实质性变更也会记入 [CHANGELOG.md](CHANGELOG.md)。含义发生变化时，上方的生效日期会随之更新。

## 9. 联系

对本政策有疑问：请在 <https://github.com/Slacker-LLC/minis-for-android/issues> 提 Issue。请勿在其中发布密钥或个人数据。
