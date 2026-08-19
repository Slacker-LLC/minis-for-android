# 分叉改动清单

相对官方 OpenMinis `1.12`（versionCode 24）。

---

## 新增文件

### 桌面宠物 `app/src/main/java/com/openminis/app/pet/`

| 文件 | 作用 |
|---|---|
| `PetOverlayService.kt` | 悬浮窗服务：手势、状态机、语音、屏幕开关联动 |
| `PetOverlayView.kt` | 宠物窗口内容：气泡 + 长按菜单 |
| `PetChatWindowView.kt` | 独立的聊天小窗 |
| `PetChatEngine.kt` | 直连模型问答、回复压短、写入会话历史 |
| `PetBehavior.kt` | 自主行为：巡游、边缘吸附、贴边隐藏 |
| `PetSpriteView.kt` | 精灵图集动画渲染 |
| `PetPackageManager.kt` | 宠物包 ZIP 导入与校验 |
| `PetModels.kt` | `pet.json` 解析与图集几何 |
| `PetPreferences.kt` | 宠物相关偏好 |
| `PetBridge.kt` | 给 App 其它部分调用的窄接口 |
| `PetControlActivity.kt` / `PetControlScreen.kt` | 宠物设置界面 |

### Web 远程控制 `app/src/main/java/com/openminis/app/remote/`

`RemoteAccessServer.kt`、`RemoteAccessService.kt`、`RemoteAccessPrefs.kt`、
`CloudflareTunnelManager.kt`，前端在 `app/src/main/assets/remote/`。

---

## 修改到的官方文件

| 文件 | 改动 |
|---|---|
| `build.gradle.kts` | `applicationId` → `dev.openminispet.android`，版本号加 `-pet.N` 后缀 |
| `AndroidManifest.xml` | 注册宠物服务/Activity、Web Remote 服务，加 `FOREGROUND_SERVICE_SPECIAL_USE` |
| `MinisApp.kt` | 进程重建后恢复宠物与 Web Remote |
| `AgentForegroundService.kt` | 把 Agent 状态推给宠物 |
| `ui/settings/SettingsScreen.kt` | 「外观」加桌面宠物入口 |
| `ui/settings/SystemPermissionsScreen.kt` | 加「显示在其他应用上层」权限行 |
| `offload/AlarmReceiver.kt` | 开机广播里恢复 Web Remote |
| `sandbox/NativeOffload.kt` | abstract socket 名带上 applicationId（见下） |
| `res/values*/strings.xml` | 应用名 → OpenMinis Pet |

---

## 值得单独说的几个修复

下面这些不是「加功能」，是踩到坑之后的修复，记下来是为了别再踩第二遍。

### 1. 与官方版共存会互相打死

**现象**：两个 App 同时装，后启动的那个在 `Application.onCreate()` 里直接崩。

**原因**：PRoot 的 native offload 用 **abstract socket**。抽象 socket 属于内核级全局命名
空间，**不随应用沙盒隔离**，两个 App 用同一个名字必然抢。

**改法**：socket 名带上 applicationId。

### 2. Cloudflare Tunnel 失败时看不到真正原因

**现象**：界面显示 `cloudflared stopped (exit 255): HandlerEntry contains 31 bytes in 2 blocks (ref 0) 0xb...`。

**原因**：那串根本不是错误信息，是 **PRoot 的 talloc 在进程退出时打印的内存分配表**。
`drainProcess` 里用 `last = 每一行` 记录「最后输出」，cloudflared 退出时 talloc 一口气吐
几十行，把真正的 `Provided Tunnel token is not valid.` 冲掉了。

**改法**：跳过 talloc / proot 噪声行，并优先保留含 error/invalid/failed 的那一行。

### 3. 重启手机后 Web Remote 再也起不来

**原因**：`RemoteAccessService.start()` 全工程**只有设置页那个开关调用**。宠物有
`PetBridge.startIfEnabled()` 负责进程重建后恢复，Web Remote 没有对应的东西。而远程管理
最需要的恰恰是「人不在手机旁边也能连上」。

**改法**：新增 `RemoteAccessService.startIfEnabled()`，在 `MinisApp.onCreate()` 和开机广播
里各调一次。恢复时仍然检查「开关是开的」**且**「设置了登录密码」，不会因为自动恢复就把无
密码的远程控制暴露出去。用 `runCatching` 包住——开机启动前台服务属于后台启动，部分 ROM 会
抛 `ForegroundServiceStartNotAllowedException`，不接住会连累整个 App 启动失败。

### 4. 宠物聊天框点屏幕别处关不掉

**原因**：`FLAG_WATCH_OUTSIDE_TOUCH` 只对 `NOT_FOCUSABLE` 的窗口派发 `ACTION_OUTSIDE`，
而打字必须让窗口可获焦点，两者互斥。

**改法**：聊天区拆成独立小窗——宠物本体始终不抢焦点，聊天窗单独获焦，失焦即关，返回键也关。

### 5. 熄屏后宠物还在原地跑

**原因**：没有任何屏幕状态处理。熄屏后精灵每 110ms 仍在 `invalidate()`，巡游定时器还在挪
窗口，随机性格动作照常触发——屏幕是黑的，这些一帧都不会被看到，纯耗电。

**改法**：运行时注册 `ACTION_SCREEN_ON/OFF`（manifest 静态注册收不到这两个广播），熄屏时
停掉动画、巡游和心情定时器，亮屏恢复。恢复用的是专门的 `resumeTimers()` 而不是 `reset()`
——后者会清掉贴边状态，让本来藏在边缘的宠物自己蹦出来。

### 6. Web 端 Markdown 原样显示

**原因**：消息只做 `esc()` 转义就塞进 DOM。

**改法**：新增 `assets/remote/md.js`，手写的轻量渲染器（页面在严格 CSP 下从 APK assets 里
出，CDN 一律不可达，只能自带）。两个实现期 bug 值得记：

- **代码高亮把自己的 HTML 吐成了文本**：关键字表里有 `class`，关键字替换把前面生成的
  `<span class="tok-str">` 里的 `class` 又包了一层，标签就碎了。改成先把字符串和注释藏进
  占位符再做关键字替换。
- **引用块识别不到**：先 `esc()` 再解析，`>` 早已变成 `&gt;`。顺带把 `render` 拆成「转义」
  和「解析」两层，否则引用递归会双重转义。

渲染器带 17 条断言的测试，覆盖 XSS（`<script>`、`<img onerror>`、`javascript:` 链接）。

### 7. Web 端没有流式、还闪

**原因**：每 1600ms 把整个消息列表 `innerHTML` 重建一次——既看不到增长，又会闪、丢失选中
的文本和滚动位置。

**改法**：按消息 id 增量更新，只重绘内容变化的那一条；生成中轮询 450ms、空闲 2500ms，会话
列表每 4 次才拉一次。滚动只在读者本来就在底部时才跟随，否则流式回复会一直把视图拽走。

### 8. 其它

- 宠物渲染每帧 `new Rect + new RectF` → 复用成员变量
- `frame % animation.frameCount` 在畸形宠物包下可能除零 → `coerceAtLeast(1)`
- 1536×1872 图集在 ARGB_8888 下约 11.5 MB，低密度屏或最小尺寸档按需减半采样
- Web 端文件面板硬编码 `/var/minis/workspace`，sandbox 没初始化过就整块报错 → 回退到根目录
- 打补丁脚本 `apply_patch.py` 在 Windows 上会把整份文件的 LF 改写成 CRLF（`Path.write_text`
  默认 `newline=None` → `os.linesep`），一行改动显示成全文件 diff → 统一按 LF 写回
- 同一脚本的锚点正则 `(?m)^(\s*)`，`\s` 含换行会把上一行的换行也吃进 indent 分组，导致插入
  的每一行前面都多一个空行 → 收紧为 `[^\S\n]*`

---

## 未验证 / 已知问题

诚实起见：

- **语音识别在测试机（MIUI）上不可用**。系统引擎 `isRecognitionAvailable()` 返回 `false`，
  云端引擎需要手动绑定 Voice Input 模型。代码路径正确，但没能在真机上跑通一次完整语音对话。
- 宠物的息屏优化经过编译和逻辑验证，**没有做长时间耗电对比实测**。
- Web 端 Markdown 渲染器有单元测试，但**没有在真机浏览器上逐项回归**。
