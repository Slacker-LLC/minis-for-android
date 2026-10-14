# Minis for Android 新增问题审计报告 — 2026-10-04

> 本轮独立报告。只记录此前综合报告之外的新发现，不追加、不重写原综合报告。固定版本审计快照，不代替现役合同。

## 范围与证据限制

- 本地审阅基线：`main` / `d64e7c06d8159be90737dd9ba03dd9bd5855f339`，证据来自当前工作树。
- 上游参考：[OpenMinis 固定提交](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)。使用 `/tmp/openminis-review-20261004` 的只读源码，没有执行上游代码。
- 本轮重点：配置写入、确认与审计撤销，以及定时任务的保存、后台回执和闹钟身份。
- 不重复统计原报告的 40 项，也不处理注释、重复基础设施和 Lint 清理。
- 本轮只新增本报告；产品源码、测试、合同及原综合报告均未修改。原综合报告的保全 SHA-256：`9a909370f95462d951897c81e1f38809699d7ff9a8f1f89e14f8b8f28f0319ca`。
- 没有运行 Android 构建、JVM 测试或设备端到端测试。下文的端到端验收是修复后的建议，并非已经通过的检查。

共 **6 项新增发现**。N01–N04 有明确输入或操作顺序，源码控制流可以确定错误结果；N05 已计算出实际标识碰撞，但未验证设备闹钟；N06 是源码确认的审批归属竞争风险，未做 UI 时序复现。N04 还包含未压力复现的并发丢更新风险。

| 编号 | 优先级 | 问题 | 证据层级 |
|---|---|---|---|
| [N01](#n01) | P1 | 拒绝、超时和逐行拒绝的凭据变更把明文写入审计库，并允许 CLI 读回 | 静态明确 |
| [N02](#n02) | P1 | 同一批次追加或删除同一列表时，后项覆盖前项 | 静态明确 |
| [N03](#n03) | P2 | 集合删除的撤销硬编码模型参数，思考规则恢复失败，模型恢复丢字段 | 静态明确 |
| [N04](#n04) | P1 | 定时任务保存旧草稿会抹掉新回执；部分写入还绕过锁 | 静态明确，并发风险另列 |
| [N05](#n05) | P2 | 闹钟用 UUID 哈希作唯一标识，碰撞任务共享 PendingIntent | 源码、碰撞样本、平台文档 |
| [N06](#n06) | P1 | 确认按钮不携带请求 ID，旧界面事件可能批准下一项配置 | 静态竞争风险 |

<a id="n01"></a>
## N01 — 凭据变更在拒绝和超时路径进入明文审计

**触发方式：** 对已有 Provider 的 `providers.<id>.apiKey` 写入一个新的字面量密钥，然后取消确认、等待 120 秒超时，或在批次确认中关闭该行。`providers.append` 中包含字面量 `apiKey` 时同样受影响。

链路已经提供脱敏副本，但只在成功写入路径使用：

- [ProvidersCollection.kt:360](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ProvidersCollection.kt:360) 的 API Key 字段允许写入，读取明确抛出 `PermissionDenied`。
- [ConfigBridge.kt:505](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:505) 创建 `auditNewValue`，确认界面显示隐藏值；集合添加也在 `:360` 创建脱敏副本。
- 超时 `:554`、整体拒绝 `:576`、逐行拒绝 `:609` 却均记录 `r.newValue.jsonString()`，没有使用该脱敏副本。批准的字段写入路径 `:665` 才使用 `auditNewValue`，集合添加在 `:634` 使用该副本。
- [ConfigAuditLog.kt:55](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/audit/ConfigAuditLog.kt:55) 把 old/new 原样写入 App 私有的 `minis-config-audit.db`。
- [ConfigBridge.kt:844](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:844) 的审计返回值直接暴露 `oldValueJSON` / `newValueJSON`；[ConfigOffloadHandler.kt:311](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt:311) 的 `audit-list` / `audit-get` 可读回这些内容，前提是 minis-config 总开关开启。

**影响：** 用户没有批准的新密钥仍被长期保存到普通审计库，还可通过本来禁止读密钥的配置 CLI 读回。这是配置审计的独立旁路，与原报告 F32 的工具 checkpoint / 日志副本不是同一个持久化入口。没有证据表明发生了远程窃取；`$$ENV` 输入通常只留下引用名称，不能把它等同于实际密钥明文。

**上游对照：** 固定上游 Android 的相同拒绝、超时分支也使用 `r.newValue`，属于双方共有缺陷，不能照搬上游视为解决。

**修复方向：** 统一所有结果分支的审计值选择，凭据只允许使用脱敏副本；审计读取增加防护，并考虑处理已落盘的历史敏感记录。

**端到端验收：** 用无效 canary 密钥分别执行批准、取消、超时、逐行拒绝和 Provider 添加；搜索审计数据库、CLI 输出及审计 UI，均不得出现 canary。保存匿名化的检查结果，不能把真实密钥写入验收工件。

<a id="n02"></a>
## N02 — 同批次列表操作丢失前面的修改

**最小触发：** 当前 `defaults.agentLoopEntries = [A]`，B、C 都是已有合法模型条目。在一次 `set-batch` 中依次追加 B、C，全部批准。预期 `[A,B,C]`，实际最终为 `[A,C]`。

```json
[
  {"path":"defaults.agentLoopEntries.append","value_json":"\"B\""},
  {"path":"defaults.agentLoopEntries.append","value_json":"\"C\""}
]
```

示例 A/B/C 是占位符，实际执行需要替换为存在的条目 ID。

证据：

- [ConfigOffloadHandler.kt:282](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ConfigOffloadHandler.kt:282) 接受最多 50 项的 `set-batch`，未禁止重复目标路径。
- [ConfigBridge.kt:314](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:314) 在执行任何修改前解析全部操作；每项 `:421` 独立读取同一份当前列表。
- `:450` 预计算整个替换值，所以两项分别准备 `[A,B]` 与 `[A,C]`；批准后 `:657` 逐项调用 `field.write(r.newValue)`，后一份整表覆盖前一份。
- [ConfigBuiltins.kt:695](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:695) 确实注册此字段，writer `:733` 整体替换条目列表。[ModelSlotsCollection.kt:46](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelSlotsCollection.kt:46) 的 `slots.<slot>.entries` 也是实际可调用的数组字段。

同批次两次 `.remove` 或先追加后删除也会基于同一旧快照计算，可能把先前删除的条目带回。确认对话框等待期间其他入口修改列表，还会产生跨请求的旧快照覆盖；本文最小例子不依赖并发。

**影响：** 返回成功和多条审计记录，但最终状态没有包含所有批准的修改。问题在执行语义，不是要求批次必须具备事务原子性。

**上游对照：** 固定上游 Android 同样先读取所有 oldValue 并计算替换数组，缺陷共有。

**修复方向：** 让同目标操作按顺序作用于工作状态，或在执行时基于最新状态应用追加/删除；如果暂不支持重复目标，应在确认前明确拒绝整批，不能成功后静默丢项。

**端到端验收：** 在真实配置 CLI 中执行两次追加、两次删除、追加再删除，并包含拒绝中间行；核对最终配置与每行审计。保存请求、响应及配置前后快照。

<a id="n03"></a>
## N03 — 集合删除的撤销只拼模型字段

**触发方式：** 通过 minis-config 删除一个自定义思考规则，再从审计 UI 或 `audit-revert` 撤销。删除已生效，但撤销必然缺少思考规则添加所需参数。自定义模型删除后的撤销则可能返回成功，却丢掉原有配置。

证据：

- [ConfigBridge.kt:646](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:646) 删除前调用 `snapshotCollectionChild` 保存字段快照，`auditRevert` 接受已应用的集合删除。
- [ConfigBridge.kt:923](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:923) 对所有集合都只映射 `providerInstanceId → instance_id`、`modelId → model_id`、`displayName → display_name`，随后调用该集合 `.append`。
- [ThinkingRulesCollection.kt:64](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ThinkingRulesCollection.kt:64) 的快照字段是 `label`、`scope`、`wireFormat`、`provider`；其 `add()` 在 `:144` 要求 `provider`，之后还要求 `label` / `wire_format`。上述通用恢复映射产出空对象，首先报缺少 `provider`。
- [ModelsCollection.kt:94](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:94) 的添加支持 `context_window` / `max_output_tokens` 等配置；恢复映射没有带回它们，也没有恢复已有 override。新的 ModelEntry 因此使用缺省字段。
- [ConfigAuditScreen.kt:277](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ConfigAuditScreen.kt:277) 对全部 APPLIED 条目显示撤销按钮，`:172` 确实调用这条恢复链。

**影响：** 思考规则无法通过承诺的审计撤销恢复；模型可能只恢复名称和 Provider 归属，用户限制、能力或模型参数丢失。集合恢复生成新 ID、旧会话引用不自动重绑已在代码中明确说明，本项不把这个已知行为重新算作额外缺陷。

**上游对照：** 固定上游 Android 也只映射这三个模型字段，缺陷共有。

**修复方向：** 使用各集合实际的添加输入与完整恢复语义，不能把模型映射当作通用恢复实现；无法恢复的条目应提前明确标记，而不是统一提供撤销按钮。

**端到端验收：** 为自定义思考规则设置非默认 scope/wire format，为自定义模型设置窗口、输出上限和 override；删除后分别从 UI、CLI 撤销，对比完整对象。保存前后快照和恢复响应，覆盖 Provider 已被删除等失败路径。

<a id="n04"></a>
## N04 — 定时任务的旧草稿与后台写入互相覆盖

### 不依赖线程竞争的旧草稿覆盖

**操作顺序：** 打开某例程的编辑页，保持页面打开；该例程后台完成一次执行，写入新运行记录；此后只改名称或提示词并保存。新回执被编辑页携带的旧字段覆盖。

- [ScheduledTaskEditScreen.kt:147](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTaskEditScreen.kt:147) 的 `LaunchedEffect(taskId)` 初次加载 `lastFiredAt`、`lastResult*` 和 `runHistory`，没有随例程记录变化重载这些编辑态字段。
- [ScheduledAgentRunner.kt:168](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:168) 在后台 IO scope 完成执行，并在 `:179` 调用 `markFired`。
- 编辑页 `currentTask()` 和 [buildTask:1048](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTaskEditScreen.kt:1048) 又把旧 `lastFiredAt` / `lastResult*` / `runHistory` 带回完整 ScheduledTask。
- [ScheduledTaskManager.kt:65](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:65) 虽读取最新 existing 做权限校验，却在 `:78` 原样 `store.upsert(task)`，不保留当前的回执字段。

### 并发路径还没有共享同一把锁

- Manager 的 create/update/delete 使用进程内 `MUTATION_LOCK`，但 [setEnabled:84](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:84)、`rescheduleNext:138`、`markFired:149` 均在锁外执行读→复制→写。
- [ScheduledTaskStore.kt:39](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskStore.kt:39) 的 upsert/delete 又整表读写 JSON，没有统一锁和原子行变换。
- 用户开关/删除在 [ScheduledTasksViewModel.kt:140](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTasksViewModel.kt:140) 的 IO 协程执行；闹钟 receiver 在 Default 协程执行，后台回执在 IO 协程执行，是真实的多个写入方。

**影响：** 旧草稿覆盖明确丢失新的历史和结果。并发情况下还可能出现关闭后被旧记录重新设为 enabled、删除后被回执重新插入、两个不同例程的整表更新互相丢弃。后面这些是可推导的竞争结果，尚无设备压力复现；例如重新插入数据行不等于一定重新注册了闹钟，不能混为一谈。

**上游对照：** 固定上游的 ScheduledTaskStore 已用进程共享 LOCK 串行化 upsert/delete，并提供原子 `update(taskId, transform)`；Manager.markFired 使用该 update。其 Manager.update 还通过 `mergeBookkeeping` 保留 incoming 为空时的历史。本地没有这些处理。上游只在 incoming 历史为空时合并，不能据此声称非空旧草稿覆盖也已完全解决。

**修复方向：** 编辑输入与后台维护字段分开合并；所有写入共享一个持久化更新边界，回执只更新仍存在的当前条目，避免把执行开始或编辑开始时的旧对象整行写回。复用现有数据层与锁即可，不需要新建调度框架。

**端到端验收：** 编辑期间执行完成后保存，历史必须保留；关闭或删除例程同时让后台执行结束，不能恢复用户已撤销的状态；并行更新两个例程，均不得丢失。保存完整操作时间线、最终持久化列表和闹钟状态。

<a id="n05"></a>
## N05 — 哈希碰撞让两个例程共用闹钟

[ScheduledTaskManager.kt:197](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:197) 使用固定 action、固定 receiver，task ID 只放在 extra；requestCode 为 `taskId.hashCode() and 0x7FFFFFFF`，flags 为 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`。

Android 明确规定 PendingIntent 的匹配不比较 extras；相同 requestCode、action/data/component 等标识会复用 token，UPDATE_CURRENT 会更新 extras。[Android 官方 PendingIntent 文档](https://developer.android.com/reference/android/app/PendingIntent)

本轮在宿主用确定性样本生成器计算得到以下两个合法版本 4 UUID，requestCode 都为 **1698959550**：

```text
a5656eec-ecca-4d7b-9c6c-ed12ef8a469c
73d88780-7d3c-4fd9-a65c-dce62bd9e0d4
```

**触发与影响：** 如果这两个 ID 同时用于例程，两个 PendingIntent 会共享身份，后一次注册会改变 extra 中的目标任务并替换同身份闹钟；取消其中一个也会取消共用的 token。正常随机生成 ID 的小规模任务集合碰撞概率低，不能把本次人工生成样本说成用户设备已经发生过的错触发。

**上游对照：** 固定上游使用相同哈希方案，缺陷共有。

**修复方向：** 把完整 task ID 纳入 Intent 的 data 等参与身份比较的属性，使不同任务即使 requestCode 相同也获得不同 token。按完整 ID 生成、查找和取消，避免新增一套哈希分配器。

**可重复计算工件：** 下列脚本只用标准库，不访问项目/设备，不注册闹钟；在第 36,275 个样本找到上述碰撞。其结果仅验证标识计算，不替代 Android E2E。

```python
import hashlib
import uuid

seen = {}
for i in range(300_000):
    digest = hashlib.sha256(f"minis-audit-{i}".encode()).digest()
    task_id = str(uuid.UUID(bytes=digest[:16], version=4))
    code = 0
    for char in task_id:
        code = (31 * code + ord(char)) & 0xffffffff
    code &= 0x7fffffff
    if code in seen:
        print(seen[code], task_id, code, i + 1)
        break
    seen[code] = task_id
```

**端到端验收：** 用这两个固定 ID 安排不同时间的两个例程，核对 PendingIntent、实际收到的 task ID，以及取消其中一个后的另一个闹钟；保存系统闹钟快照和回调日志。

<a id="n06"></a>
## N06 — 确认事件没有绑定正在展示的请求

**这是静态竞争风险，尚未复现 UI 时序。** 当前审批接口没有携带确认框正在展示的 request ID，执行方也没有检查行 ID 与请求的归属。

- [ConfigConfirmDialog.kt:118](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ConfigConfirmDialog.kt:118) 的确认回调只传 `workingItems.toList()`，没有传 `change.id`；取消同样不传 ID。
- [ConfigConfirmationGate.kt:118](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/confirm/ConfigConfirmationGate.kt:118) 在收到点击时读取此刻的 `_pending.value`，然后把传入 items 当作这个请求的 Approved 结果。
- Gate 在 `:145` 超时或完成时直接推进下一项；UI 通过 StateFlow 和 Compose 重组更新显示，存在状态已推进、旧 UI 回调仍可被投递的窗口。
- [ConfigBridge.kt:599](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:599) 只按下标把 outcome.items 配给 resolved，不检查 path 或行 ID；拿到旧框 A 的勾选状态也可能写入请求 B 的值。

**候选触发序列：** A 与 B 排队，用户正在看 A；A 在超时边界推进到 B，但 A 的旧点击回调仍被投递。Gate 选择 B，Bridge 按下标执行 B，而用户刚才看到的是 A。两个请求都只有一行时，不需要复杂行数条件即可构成错误归属。

**影响：** 单次审批缺少可验证的请求归属，可能把用户对旧修改的同意用于另一项修改。不是说正常每次点击都会串请求，也没有把它标为真机已复现的授权绕过。

**上游对照：** 固定上游的 UI、Gate 接口和按下标应用方式相同，风险共有。

**修复方向：** approve/reject 携带展示的 request ID，Gate 在锁内验证仍对应当前请求；Bridge 验证行归属，不按位置接受其他请求的确认结果。旧请求事件必须被拒绝，不能自动套用到下一项。

**端到端验收：** 同时排队两个不同配置变更，在超时换框附近重复点击并记录 request ID、展示内容与写入结果；旧事件不能改变下一项。保存屏幕录制和匿名化事件时间线，包含旧请求取消事件。

## 未纳入正式发现的候选与排除结果

- 早期观察到 Gate.resolve 没有从 backlog 移除非队首的超时项，曾在进度消息中提到“过期队列”。进一步核对后，各请求从入队起使用相同 120 秒超时，普通 FIFO 序列下队首通常先超时并推进队列。本轮没有建立非队首先超时的实际生产触发条件，因此不把“僵尸队列”算作已确认问题。
- 定时编辑器的 Run now 表面上没有独立 FULL 确认，但 `onPermissionTierChange` 会在选中 FULL 前先弹确认，只有用户确认后才修改 tier；本轮未确认这条 UI 路径存在权限升级绕过。
- MCP Server 的慢请求、取消与 token 撤销时序仍需专门验证，本轮不基于通用风险推测追加漏洞。

## 本轮验证记录

- 已读取配置 CLI、Bridge、审计存储、集合实现、确认 UI/Gate，以及定时编辑器、Manager、Store、Receiver 和 Runner 的相关调用链。
- 已在固定上游源码中复核对应实现，明确区分双方共有缺陷与本地未保留的持久化处理。
- 已执行 N05 的有界、确定性 UUID 哈希计算，获得真实碰撞样本；未注册设备闹钟。
- 报告交付前复核本地源码链接、N01–N06 锚点与原综合报告保全哈希，并执行项目文档来源检查。文档检查通过不代表 Android 行为已通过验证。
