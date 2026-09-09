from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1))


# 1. SOUL prompt hint: icon is already parsed/serialized by the Fork.
replace_once(
    "src/android/app/src/main/java/com/openminis/app/agent/SoulStore.kt",
    "SOUL.md fields (name / style / lang / body) can be edited two ways:",
    "SOUL.md fields (name / icon / style / lang / body) can be edited two ways:",
)

# 2. Credential-aware provider/group routing.
oauth = "src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt"
oauth_marker = "        /** Create the appropriate OAuthManager for a provider instance. */"
oauth_helper = '''        /**
         * [T-android-group-resolve-skip-uncredentialed] Whether ANY OAuth
         * credential is stored for [instanceId] — either a token bundle from a
         * completed login or a user-pasted manual bearer.
         *
         * Presence only: expiry validation and refresh stay on the request path.
         * This is static because provider routing must also cover provider types
         * that [forInstance] deliberately does not instantiate (notably Gemini).
         */
        fun hasStoredCredential(context: Context, instanceId: String): Boolean {
            val prefs = com.openminis.app.util.EncryptedPrefsFactory
                .safeCreate(context, "oauth_prefs")
            if (!prefs.getString("oauth_tokens_$instanceId", null).isNullOrEmpty()) return true
            return !prefs.getString("oauth_${KEY_MANUAL_BEARER}_$instanceId", null).isNullOrEmpty()
        }

'''
replace_once(oauth, oauth_marker, oauth_helper + oauth_marker)

provider = "src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt"
first_enabled = '''    /** Convenience: first enabled member of [group] in declaration order. */
    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()
'''
credential_helpers = first_enabled + '''
    /**
     * [T-android-group-resolve-skip-uncredentialed] Whether [instance] has ANY
     * usable credential — API key, an intentionally keyless compatible endpoint,
     * a manual bearer, or a stored OAuth token.
     */
    fun hasAnyCredential(instance: ProviderInstance): Boolean {
        if (usableApiKey(instance) != null) return true
        return com.openminis.app.auth.OAuthManager.hasStoredCredential(context, instance.id)
    }

    /**
     * Members of [group] that are usable now. Filtering happens before routing,
     * preserves declaration order, and skips hidden/disabled/uncredentialed rows.
     */
    fun availableMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId } ?: return@mapNotNull null
            if (entry.isHidden) return@mapNotNull null
            val instance = config.instances.find { it.id == entry.providerInstanceId }
                ?: return@mapNotNull null
            if (!instance.isEnabled) return@mapNotNull null
            if (!hasAnyCredential(instance)) return@mapNotNull null
            entry
        }
    }
'''
replace_once(provider, first_enabled, credential_helpers)
replace_once(
    provider,
    "        return firstEnabledMemberEntry(group)\n",
    "        return availableMemberEntries(group).firstOrNull()\n",
)

chat_vm = "src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
replace_once(
    chat_vm,
    "        val enabledMembers = providerRepository.enabledMemberEntries(group)\n",
    "        val enabledMembers = providerRepository.availableMemberEntries(group)\n",
)

scheduled = "src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt"
replace_once(
    scheduled,
    "                                ?.let { g -> app.providerRepository.enabledMemberEntries(g).firstOrNull()?.model?.id }\n",
    "                                // Credential-aware: unattended runs skip members that cannot authenticate.\n"
    "                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }\n",
)

correction = "src/android/app/src/main/java/com/openminis/app/speech/correction/CorrectionStrategy.kt"
replace_once(
    correction,
    "        val apiKey = repository.loadApiKey(instance.id) ?: throw CorrectionError.NoModelAvailable\n",
    "        // Keyless compatible endpoints are valid, matching the normal chat path.\n"
    "        val apiKey = repository.usableApiKey(instance) ?: throw CorrectionError.NoModelAvailable\n",
)
replace_once(
    correction,
    "        val entry = group?.let { repository.firstEnabledMemberEntry(it) }\n",
    "        val entry = group?.let { repository.availableMemberEntries(it).firstOrNull() }\n",
)

# 3/4. Agent browser JS-dialog reporting + background external-scheme origin.
browser = "src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt"
replace_once(
    browser,
    '''    private val _currentURL = MutableStateFlow("")
    val currentURL: StateFlow<String> = _currentURL.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
''',
    '''    private val _currentURL = MutableStateFlow("")
    val currentURL: StateFlow<String> = _currentURL.asStateFlow()

    /** JS dialogs answered by the headless agent WebView, reported on the next tool result. */
    private val dialogQueue = InterceptedDialogQueue()

    private val _pageTitle = MutableStateFlow("")
''',
)
replace_once(
    browser,
    '''                return com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                    .handle(view.context, request.url)
''',
    '''                return com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                    .handle(
                        view.context,
                        request.url,
                        com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                            .Origin.AGENT_BACKGROUND,
                    )
''',
)
old_chrome_tail = '''            override fun onCloseWindow(window: WebView) {
                onCloseWindow?.invoke()
            }
        }
    }
'''
new_chrome_tail = '''            override fun onCloseWindow(window: WebView) {
                onCloseWindow?.invoke()
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog("alert", message.orEmpty(), null, "(dismissed)")
                result?.confirm()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?,
            ): Boolean {
                recordInterceptedDialog("confirm", message.orEmpty(), null, "false")
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                recordInterceptedDialog("prompt", message.orEmpty(), defaultValue, "null")
                result?.cancel()
                return true
            }
        }
    }

    private fun recordInterceptedDialog(
        kind: String,
        message: String,
        defaultText: String?,
        defaultResponse: String,
    ) {
        val url = _currentURL.value
        dialogQueue.record(
            kind = kind,
            message = message,
            defaultText = defaultText,
            pageURL = url.ifEmpty { null },
            defaultResponse = defaultResponse,
        )
        Log.i(TAG, "[JSDialog] intercepted $kind on $url — answered $defaultResponse")
    }

    fun drainInterceptedDialogReport(): String? = dialogQueue.drainReport()
'''
replace_once(browser, old_chrome_tail, new_chrome_tail)

pool = "src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt"
replace_once(
    pool,
    "            val result = tab.manager.execute(input)\n            // [T-android-browser-result-tab-id] Stamp the VERIFIED tab id",
    "            val result = tab.manager.execute(input)\n"
    "            val withDialogs = tab.manager.drainInterceptedDialogReport()\n"
    "                ?.let { result.copy(text = it + result.text) }\n"
    "                ?: result\n"
    "            // [T-android-browser-result-tab-id] Stamp the VERIFIED tab id",
)
replace_once(
    pool,
    "            stampTabId(result.copy(pageURL = tab.manager.currentURL.value), tab.id)\n",
    "            stampTabId(withDialogs.copy(pageURL = tab.manager.currentURL.value), tab.id)\n",
)

# 5. minis-config must write the same preference store/keys as AppearanceScreen.
cfg = "src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt"
replace_once(
    cfg,
    '''    private fun registerChat(r: ConfigRegistry, context: Context) {
        // Default app prefs — Android persists most chat preferences in
        // the default SharedPreferences for the package.
        val prefs = context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE)
''',
    '''    private fun registerChat(r: ConfigRegistry, context: Context) {
        val appearancePrefs = context.getSharedPreferences(
            com.openminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
''',
)
replace_once(
    cfg,
    '''                prefs = prefs,
                key = "return_key_behavior",
''',
    '''                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_RETURN_KEY_BEHAVIOR,
''',
)
replace_once(
    cfg,
    '''                prefs = prefs,
                key = "keep_screen_awake_during_tasks",
''',
    '''                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE,
''',
)
replace_once(
    cfg,
    '''        val appearancePrefs = context.getSharedPreferences(
            com.openminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "chat.toolPreview",
''',
    '''        r.register(
            PrefsBoolField(
                path = "chat.toolPreview",
''',
)

# 6. SOUL icon minis-config, adapted from upstream PRoot access to minisd reads.
replace_once(
    cfg,
    '''    // (name / style / lang / body) as `soul.*` config paths. The on-disk
    // YAML emoji field is intentionally NOT registered — matches the
    // iOS rollback where ✨ is locked as the identity icon.
''',
    '''    // (name / icon / style / lang / body) as `soul.*` config paths. The legacy
    // YAML emoji field remains unregistered; `icon` is the current identity surface.
''',
)

soul_marker = "    private fun registerSoul(r: ConfigRegistry, context: Context) {"
soul_helper = '''    private fun resolveSoulIconImage(raw: String): String {
        val icon = com.openminis.app.agent.SoulIcon
        val bytes: ByteArray = when (val src = icon.classifySource(raw)) {
            is com.openminis.app.agent.SoulIcon.Source.Unsupported ->
                throw ConfigError.InvalidValue(src.reason)
            is com.openminis.app.agent.SoulIcon.Source.Bytes -> src.data
            is com.openminis.app.agent.SoulIcon.Source.LinuxPath -> {
                val root = icon.ALLOWED_LINUX_ROOTS.firstOrNull {
                    src.path == it || src.path.startsWith("$it/")
                } ?: throw ConfigError.InvalidValue(
                    "path must be inside one of ${icon.ALLOWED_LINUX_ROOTS.joinToString(", ")}",
                )
                val normalized = runCatching {
                    java.nio.file.Paths.get(src.path).normalize().toString()
                }.getOrElse {
                    throw ConfigError.InvalidValue("invalid path: ${src.path}")
                }
                if (normalized != src.path) {
                    throw ConfigError.InvalidValue("path must not contain traversal segments")
                }
                val sessionScoped = root == "/var/minis/attachments" ||
                    root == "/var/minis/workspace" || root == "/var/minis/offloads"
                val sid = if (sessionScoped) {
                    ChatViewModelStore.activeSessionId
                        ?: throw ConfigError.InvalidValue(
                            "no active session — open a chat first, or pass the image inline as a data URI",
                        )
                } else ""
                runCatching {
                    com.openminis.app.runtime.minisd.WorkspaceFileClient
                        .readAllBlocking(sid, src.path)
                }.getOrElse {
                    throw ConfigError.InvalidValue("could not read ${src.path}: ${it.message}")
                }
            }
        }

        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: throw ConfigError.InvalidValue(
            "that data isn't a decodable image (png / jpeg / webp / gif are supported; svg is not)",
        )
        return when (val result = icon.encode(bitmap)) {
            is com.openminis.app.agent.SoulIcon.EncodeResult.Success -> result.dataUri
            is com.openminis.app.agent.SoulIcon.EncodeResult.Failure -> when (result.reason) {
                com.openminis.app.agent.SoulIcon.Rejection.TOO_LARGE ->
                    throw ConfigError.InvalidValue("that image is too large to store inline")
                com.openminis.app.agent.SoulIcon.Rejection.UNREADABLE ->
                    throw ConfigError.InvalidValue("that image could not be processed")
            }
        }
    }

'''
replace_once(cfg, soul_marker, soul_helper + soul_marker)

style_marker = '''        r.register(
            ClosureField(
                path = "soul.style",
'''
icon_registration = '''        r.register(
            ClosureField(
                path = "soul.icon",
                displayName = "Soul icon",
                description = "Identity icon/avatar. Accepts one emoji, base64/data URI image, minis:// resource, or a path under /var/minis/{attachments,workspace,offloads,shared,memory,skills}. Images are normalized and stored inline; remote URLs are not supported.",
                valueSchema = ConfigSchema.Str(),
                risk = ConfigRisk.SENSITIVE,
                revertable = false,
                reader = {
                    val raw = loadCurrent().metadata.icon
                    ConfigValue.Str(if (com.openminis.app.agent.SoulIcon.isDataUri(raw)) "<image>" else raw)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val trimmed = raw.trim()
                    val cur = loadCurrent()
                    val next = when {
                        trimmed.isEmpty() -> ""
                        com.openminis.app.agent.SoulIcon.graphemeClusters(trimmed).size == 1 &&
                            com.openminis.app.agent.SoulIcon.isEmojiGlyph(trimmed) -> trimmed
                        else -> resolveSoulIconImage(trimmed)
                    }
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(icon = next)))
                },
            )
        )

'''
replace_once(cfg, style_marker, icon_registration + style_marker)

print("upstream behavior patch applied")
