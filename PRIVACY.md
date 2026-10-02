# Privacy Policy

[简体中文](PRIVACY.zh-CN.md)

**Minis for Android** · publisher: Slacker-LLC · application id `llc.slacker.minis`
Effective 2026-10-02 · applies to version 1.0 and later until replaced.

## In short

- Minis for Android has **no backend of its own**: no account, no sign-up, no analytics, no advertising or
  tracking SDK, no remote crash reporting. Slacker-LLC does not receive your conversations, files, keys or
  device data.
- Everything the app keeps lives in its **private storage on your device**.
- Content leaves the device only when you set up a destination or use a feature that needs one: the model
  provider you chose, a voice provider, an MCP server, a web request the agent makes, a backup remote.
  Section 3 lists them all.
- The app is an agent with real device access. **Anything a tool reads can be sent to your model provider** as
  part of the conversation. Grant access accordingly.

## 1. Scope

Minis for Android is an open-source (GPL-3.0) AI agent runtime for rooted Android devices, published by
Slacker-LLC at <https://github.com/Slacker-LLC/minis-for-android>. This policy covers the application. The
model providers, voice providers, MCP servers, websites and backup services you connect to are run by other
parties and have their own policies. If you build, modify or redistribute the app, you are responsible for the
data practices of your own build.

## 2. What stays on your device

| Data | Where |
|---|---|
| Conversations, attachments, memory, skills, settings, workspace and Linux (Ubuntu) environment files | The app's private storage (`Context.filesDir`) |
| Provider API keys and sign-in tokens | Android encrypted preferences in the app's private storage |
| Crash and diagnostic logs | The app's private `logs` folder |

- Crash logs hold a stack trace, the tail of the system log, the app version, the Android version and the
  device model. They are **never uploaded automatically**. You can read, share or delete them yourself.
- The app opts out of Android Auto Backup (`allowBackup="false"`). Backup archives exist only when you export
  one, and go only where you send them.
- Sensitive tool inputs and outputs are kept out of or redacted from stored transcripts and checkpoints as
  described in [SECURITY.md](SECURITY.md). That does not prevent them from being sent to the model during the
  turn that uses them.

## 3. What leaves your device, and when

| Destination | What is sent | When |
|---|---|---|
| **Model providers you configure** — for example OpenAI, Anthropic, Google Gemini, xAI, OpenRouter, DeepSeek, Kimi, MiniMax, Xiaomi MiMo, Alibaba DashScope, Azure OpenAI, or any compatible endpoint you enter | The conversation, system prompt, attachments (images, files), tool results, and your API key or token in the request header | Every request you or an agent run makes; also when the model list is refreshed |
| **Provider sign-in** (ChatGPT/OpenAI, Google, xAI, OpenRouter, Claude) | The OAuth exchange with that provider's own sign-in pages; the resulting token is stored on the device and sent only to that provider | When you sign in or the token is refreshed |
| **Voice providers you enable** — Microsoft Azure Speech, ByteDance Volcano Engine, iFlytek, ElevenLabs, Deepgram, or the Android system speech services | Audio you record or text to be spoken | Only while that voice feature is on and in use. Android's own speech services are run by your device vendor |
| **MCP servers you add** | Tool calls, their arguments and results | When the agent calls a tool of that server. The app's own MCP server listens on the loopback interface and requires a bearer token |
| **Web access by the agent** | Requested URLs and page requests; web-search queries go to DuckDuckGo (`html.duckduckgo.com`); weather lookups send the coordinates the agent supplies to Open-Meteo (`open-meteo.com`) | When the agent or the Linux environment uses those tools |
| **Package mirrors** (Linux package managers such as apt, pip, npm) | Package requests from inside the Ubuntu environment | When a command in the environment installs or updates packages |
| **GitHub** | A plain HTTPS request: `api.github.com` for the update check (only when you tap *Check for updates*); `raw.githubusercontent.com` for the model-rules refresh; GitHub hosts when you import a skill from GitHub | As stated. No account data and no conversation content |
| **models.dev** | A plain request for public model metadata | In the background to keep the model catalog current |
| **Backup destinations you configure** (rclone remotes such as SMB, WebDAV, S3) | The backup archive you choose to export | Only when you run a backup |
| **GitHub Issues** (*Submit GitHub issue*) | Nothing until you submit the form in your browser. The form is pre-filled with the app version, Android version and device model | When you open the form; what you post there is public |

Like any web request, each of these reveals your IP address and ordinary HTTP metadata to the host you contact.
Requests to OpenRouter also carry attribution headers that name this application and its repository.

## 4. Device data and permissions

The app declares many permissions because its tools are optional abilities: location, contacts, calendar,
SMS and call log, camera, microphone, photos and media, all-files access, Bluetooth and nearby Wi-Fi, usage
statistics, notifications, drawing over other apps, accessibility control, package installation, exact alarms,
and Root or Shizuku access. Android asks you before a permission is used, and you can withdraw it in system
settings at any time.

A permission is used on the device to carry out the action you or the agent asked for. What a tool returns
becomes part of the conversation, and the conversation goes to your model provider. If you do not want a model
to see something, do not give the agent access to it. The Root and accessibility boundaries are described in
[docs/SECURITY.md](docs/SECURITY.md) and [docs/contracts/04-SECURITY-CONTRACT.md](docs/contracts/04-SECURITY-CONTRACT.md).

## 5. Retention and deletion

Slacker-LLC holds no copy of your data, so there is nothing to ask us to delete. On the device you can delete
conversations, memory and skills in the app; clearing the app's storage or uninstalling it removes all of the
app's private data. Exported backups and copies on a backup remote are yours to delete. Data sent to a model
or voice provider is kept according to that provider's policy; ask the provider to delete it.

## 6. Children

The app is not directed at children.

## 7. Security

Credentials are never written to the repository, to diagnostic output or to unredacted logs
([SECURITY.md](SECURITY.md)). Report a vulnerability through this repository's private vulnerability reporting,
not through a public issue.

## 8. Changes

This file, `PRIVACY.md`, is the policy. Changes are made in the repository, so its history shows what changed
and when; material changes are also noted in [CHANGELOG.md](CHANGELOG.md). The effective date above moves when
the meaning changes.

## 9. Contact

Questions about this policy: open an issue at <https://github.com/Slacker-LLC/minis-for-android/issues>.
Do not post secrets or personal data there.
