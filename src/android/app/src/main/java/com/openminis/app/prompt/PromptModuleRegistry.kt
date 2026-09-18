package com.openminis.app.prompt

/**
 * [T-system-prompt-modules] Canonical assembly order and metadata for every
 * editable module of the Android agent system prompt.
 *
 * The list order **is** the prompt order: modules render top to bottom, each one
 * separated by its [PromptModule.gapBefore]. Adding a module means adding a file
 * under `assets/prompts/` and one entry here — nothing in ChatViewModel changes.
 *
 * Dynamic prompt content stays outside this registry on purpose, because it is
 * derived per turn rather than authored: the SOUL.md identity layer
 * (`SystemPromptBuilder`), skills, MCP disclosure, memory fragments and the
 * runtime-context footer. See `AgentSystemPrompt` for the full composition.
 */
object PromptModuleRegistry {

    /** Asset directory (relative to `assets/`) holding the shipped defaults. */
    const val ASSET_DIR = "prompts"

    const val GROUP_CORE = "核心"
    const val GROUP_TOOLS = "工具与能力"
    const val GROUP_PATHS = "路径与资源"
    const val GROUP_FILES = "文件与命令"
    const val GROUP_STYLE = "对话风格"
    const val GROUP_ANDROID = "Android 能力"
    const val GROUP_ENVIRONMENT = "环境与任务"
    const val GROUP_MEMORY = "记忆"

    /**
     * Every module in prompt order. The `gapBefore` of the first module that
     * actually renders is ignored (there is nothing before it).
     *
     * `tools.memory`, `memory.notice.enabled` and `paths.inlineMedia` etc. carry
     * TIGHT gaps where the original prompt continued a bullet list or a note
     * without a blank line.
     */
    val modules: List<PromptModule> = listOf(
        PromptModule(
            id = "core.intro",
            title = "开篇：主动使用 Shell",
            description = "提示词开场白：说明设备上有完整 Linux 环境，应主动用命令行完成任务。",
            group = GROUP_CORE,
        ),
        PromptModule(
            id = "tools.available",
            title = "工具清单：shell / 文件 / 浏览器",
            description = "shell_execute、file_read、file_write、file_edit、browser_use 的用法与执行纪律（含 Google 登录限制说明）。",
            group = GROUP_TOOLS,
        ),
        PromptModule(
            id = "tools.memory",
            title = "工具清单：记忆工具条目",
            description = "memory_write / memory_get 两个工具条目。仅在当前会话开启记忆时注入，与记忆关闭说明互斥。",
            group = GROUP_TOOLS,
            gate = PromptGate.MEMORY_ON,
            gapBefore = PromptGap.TIGHT,
        ),
        PromptModule(
            id = "paths.sharedDirs",
            title = "/var/minis 共享目录",
            description = "attachments / workspace / offloads / browser / shared / memory / mounts 各目录的用途与链接方式。",
            group = GROUP_PATHS,
        ),
        PromptModule(
            id = "paths.minisUrl",
            title = "minis:// URL 方案",
            description = "minis:// 资源链接规则、百分号编码要求、与浏览器/深链的区别。",
            group = GROUP_PATHS,
        ),
        PromptModule(
            id = "paths.inlineMedia",
            title = "内联媒体与文件预览",
            description = "图片/音频/视频的内联渲染语法，以及非媒体文件的 Markdown 链接与预览规则。",
            group = GROUP_PATHS,
            gapBefore = PromptGap.TIGHT,
        ),
        PromptModule(
            id = "files.creation",
            title = "文件创建与命令规范",
            description = "file_write / file_edit 的使用边界、命令长度上限、pip/apt 安装与后台服务注意事项。",
            group = GROUP_FILES,
        ),
        PromptModule(
            id = "style.toolCall",
            title = "工具调用风格",
            description = "何时直接调用工具、何时先向用户叙述，以及缺省值的推断方式。",
            group = GROUP_STYLE,
        ),
        PromptModule(
            id = "style.tone",
            title = "语气与回复风格",
            description = "回复语言跟随用户、简洁优先的默认要求。",
            group = GROUP_STYLE,
        ),
        PromptModule(
            id = "android.debugLoop",
            title = "Android 调试闭环",
            description = "android_* 命名工具的调试流程、证据要求与 Root/Shizuku/Accessibility 能力边界。",
            group = GROUP_ANDROID,
        ),
        PromptModule(
            id = "android.tools",
            title = "Android CLI 工具清单",
            description = "/usr/local/bin 下 android-* 与 minis-* 命令的用途与调用方式。",
            group = GROUP_ANDROID,
        ),
        PromptModule(
            id = "terminal.interactive",
            title = "交互式终端入口",
            description = "minis://open_terminal 的适用场景（需要 stdin 的密码、ssh、TUI 程序）。",
            group = GROUP_FILES,
            gapBefore = PromptGap.TIGHT,
        ),
        PromptModule(
            id = "env.variables",
            title = "环境变量与设置深链",
            description = "禁止回显密钥、缺失变量的提示方式，以及 minis://settings 深链路径清单。",
            group = GROUP_ENVIRONMENT,
        ),
        PromptModule(
            id = "memory.notice.enabled",
            title = "记忆系统说明（开启）",
            description = "记忆开启时注入：写入/读取规范、GLOBAL.md 边界、禁止记忆的内容。",
            group = GROUP_MEMORY,
            gate = PromptGate.MEMORY_ON,
        ),
        PromptModule(
            id = "memory.notice.disabled",
            title = "记忆系统说明（关闭）",
            description = "记忆关闭时注入：告知模型记忆不可用，并指向 /memory 或设置入口。",
            group = GROUP_MEMORY,
            gate = PromptGate.MEMORY_OFF,
        ),
        PromptModule(
            id = "scheduled.tasks",
            title = "计划任务说明",
            description = "应用挂起时 crontab / at / nohup 不可靠，应改用系统级提醒或原生闹钟。",
            group = GROUP_ENVIRONMENT,
        ),
    )

    fun byId(id: String): PromptModule? = modules.firstOrNull { it.id == id }

    /** Ids in assembly order; also the order rendered by the Settings editor. */
    fun ids(): List<String> = modules.map { it.id }

    /** Group names in the order they first appear in [modules]. */
    val groups: List<String> = modules.map { it.group }.distinct()
}
