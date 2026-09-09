from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing anchor for {label}")
    if text.count(old) != 1:
        raise SystemExit(f"anchor for {label} matched {text.count(old)} times")
    return text.replace(old, new, 1)

# 1) Provider backup restore: full order-preserving union + keep-existing secrets.
rel = "src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt"
s = read(rel)
pattern = re.compile(
    r"    fun mergeBackupProviderConfig\(config: com\.openminis\.app\.data\.model\.ProviderConfig\): Pair<Int, Int> \{.*?\n    \}\n\n    fun restoreBackupThinkingRules",
    re.S,
)
replacement = '''    /**
     * Restore provider configuration without destroying local-only state.
     * Package order wins for objects carried by the backup; existing local
     * object contents win on id collision; local-only objects are appended.
     */
    fun mergeBackupProviderConfig(remote: com.openminis.app.data.model.ProviderConfig): Pair<Int, Int> {
        ensureConfigLoaded()
        return synchronized(configLock) {
            val local = _config.value
            val before = local.instances.size

            val orderedInstances = mutableListOf<ProviderInstance>()
            val placedInstances = mutableSetOf<String>()
            for (ri in remote.instances) {
                val existing = local.instances.firstOrNull { it.id == ri.id }
                orderedInstances.add(existing ?: ri)
                placedInstances.add(ri.id)
            }
            for (li in local.instances) {
                if (li.id !in placedInstances) orderedInstances.add(li)
            }

            val mergedEntries = local.modelEntries.toMutableList()
            val entryIds = mergedEntries.map { it.id }.toMutableSet()
            for (entry in remote.modelEntries) {
                if (entry.id !in entryIds) {
                    mergedEntries.add(entry)
                    entryIds.add(entry.id)
                }
            }

            val orderedGroups = mutableListOf<ModelGroup>()
            val placedGroups = mutableSetOf<String>()
            for (rg in remote.modelGroups) {
                val existing = local.modelGroups.firstOrNull { it.id == rg.id }
                orderedGroups.add(existing ?: rg)
                placedGroups.add(rg.id)
            }
            for (lg in local.modelGroups) {
                if (lg.id !in placedGroups) orderedGroups.add(lg)
            }

            val mergedAgentEntries =
                (local.agentLoopModelEntryIds + remote.agentLoopModelEntryIds).distinct()
            val mergedAgentGroups =
                (local.agentLoopGroupIds + remote.agentLoopGroupIds).distinct()

            val merged = local.copy(
                instances = orderedInstances,
                modelEntries = mergedEntries,
                modelGroups = orderedGroups,
                agentLoopModelEntryIds = mergedAgentEntries.toMutableList(),
                agentLoopGroupIds = mergedAgentGroups.toMutableList(),
            )
            saveConfig(merged)
            val after = orderedInstances.size
            android.util.Log.i(
                "ProviderRepo",
                "[Restore] provider merge: instances $before→$after " +
                    "entries=${mergedEntries.size} groups=${orderedGroups.size}",
            )
            before to after
        }
    }

    fun restoreBackupThinkingRules'''
s2, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit(f"provider merge function replacement count={n}")
s = s2

pattern = re.compile(
    r"    fun restoreBackupProviderSecret\(secret: com\.openminis\.app\.backup\.BackupSecrets\.ProviderSecret\): Boolean \{.*?\n    \}\n\}",
    re.S,
)
replacement = '''    /**
     * Restore missing credentials only. A backup must never roll a device's
     * current API key / bearer / OAuth state back to an older secret.
     */
    fun restoreBackupProviderSecret(secret: com.openminis.app.backup.BackupSecrets.ProviderSecret): Boolean {
        fun deb64(value: String?): String? = value?.let {
            runCatching { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
        }

        val instance = instance(secret.instanceId) ?: return false
        var wrote = false

        deb64(secret.apiKey)?.let { key ->
            if (loadApiKey(instance.id).isNullOrBlank()) {
                saveApiKey(instance.id, key)
                wrote = true
            }
        }

        deb64(secret.manualOAuthToken)?.let { token ->
            val manualManager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            if (manualManager != null && manualManager.loadManualBearerToken().isNullOrBlank()) {
                manualManager.saveManualBearerToken(token)
                wrote = true
            }
        }

        val oauthManager = oauthManagerFor(instance)
        if (oauthManager != null) {
            deb64(secret.oauthToken)?.let { tokenJson ->
                if (oauthManager.exportStoredTokensJson().isNullOrBlank()) {
                    oauthManager.importStoredTokensJson(tokenJson)
                    wrote = true
                }
            }
            if (instance.providerType == ProviderType.gemini) {
                deb64(secret.oauthEmail)?.let { email ->
                    if (oauthManager.exportOAuthString("email").isNullOrBlank()) {
                        oauthManager.importOAuthString("email", email)
                        wrote = true
                    }
                }
                deb64(secret.oauthGcpProject)?.let { project ->
                    if (oauthManager.exportOAuthString("gcp_project").isNullOrBlank()) {
                        oauthManager.importOAuthString("gcp_project", project)
                        wrote = true
                    }
                }
            }
        }
        return wrote
    }
}
'''
s2, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit(f"provider secret function replacement count={n}")
write(rel, s2)

# 2) xAI pre-network fallback includes Grok 4.6 without touching custom GPT-6 entries.
rel = "src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt"
s = read(rel)
if 'val grok46 = LLMModel("grok-4.6"' not in s:
    s = replace_once(
        s,
        '        val grok45 = LLMModel("grok-4.5", "Grok 4.5", "xAI", supportsReasoning = true)\n',
        '        // [T-provider-dynamic-catalog-reconcile] Seed/fallback for pre-network first paint.\n'
        '        val grok46 = LLMModel("grok-4.6", "Grok 4.6", "xAI", supportsReasoning = true)\n'
        '        val grok45 = LLMModel("grok-4.5", "Grok 4.5", "xAI", supportsReasoning = true)\n',
        "grok46 declaration",
    )
    s = replace_once(
        s,
        '        val allXAI = listOf(\n            grok45,\n',
        '        val allXAI = listOf(\n            grok46,\n            grok45,\n',
        "grok46 catalog entry",
    )
write(rel, s)

# 3) Traditional Chinese already ships resources; expose it to Android + in-app picker.
rel = "src/android/app/src/main/res/xml/locales_config.xml"
s = read(rel)
if '<locale android:name="zh-Hant"/>' not in s:
    s = replace_once(
        s,
        '    <locale android:name="zh"/>\n',
        '    <locale android:name="zh"/>\n    <locale android:name="zh-Hant"/>\n',
        "zh-Hant locale config",
    )
write(rel, s)

rel = "src/android/app/src/main/java/com/openminis/app/ui/settings/AppearanceScreen.kt"
s = read(rel)
s = s.replace(
    'const val KEY_LANGUAGE = "app_language"             // "" = system, "en", "zh", "ja", "ko", "fr", "de", "ru"',
    'const val KEY_LANGUAGE = "app_language"             // "" = system, "en", "zh", "zh-Hant", "ja", "ko", "fr", "de", "ru"',
)
if 'LanguageOption("zh-Hant"' not in s:
    s = replace_once(
        s,
        '    LanguageOption("zh", "🇨🇳", "简体中文"),\n',
        '    LanguageOption("zh", "🇨🇳", "简体中文"),\n    LanguageOption("zh-Hant", "🇹🇼", "繁體中文"),\n',
        "zh-Hant appearance option",
    )
write(rel, s)

# 4) Main DB downgrade guard adapted to Fork schema v20.
guard = '''package com.openminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.logging.AppLogger

/**
 * Refuses to let Room open a minis.db written by a newer build when this
 * build has no verified downgrade path. The probe is read-only, so user data
 * remains untouched and reinstalling/upgrading the newer APK recovers it.
 */
object DatabaseVersionGuard {
    private const val TAG = "DbVersionGuard"
    const val CODE_DB_VERSION = 20
    private const val DB_NAME = "minis.db"

    fun readOnDiskVersion(context: Context): Int? {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            AppLogger.warning(TAG, "could not probe db version: ${it.javaClass.simpleName}")
            null
        }
    }

    enum class Decision { PROCEED, SHOW_NEWER_DB_GUIDANCE }

    fun evaluate(context: Context): Decision {
        val onDisk = readOnDiskVersion(context) ?: return Decision.PROCEED
        if (onDisk <= CODE_DB_VERSION) return Decision.PROCEED
        AppLogger.warning(TAG, "database is from a newer build: onDisk=$onDisk code=$CODE_DB_VERSION")
        return Decision.SHOW_NEWER_DB_GUIDANCE
    }
}
'''
write("src/android/app/src/main/java/com/openminis/app/data/db/DatabaseVersionGuard.kt", guard)

rel = "src/android/app/src/main/java/com/openminis/app/MinisApp.kt"
s = read(rel)
if 'import com.openminis.app.data.db.DatabaseVersionGuard' not in s:
    s = replace_once(
        s,
        'import com.openminis.app.data.db.AppDatabase\n',
        'import com.openminis.app.data.db.AppDatabase\nimport com.openminis.app.data.db.DatabaseVersionGuard\n',
        "MinisApp guard import",
    )
if 'var dbVersionDecision:' not in s:
    s = replace_once(
        s,
        '    var subsystemsInitialized: Boolean = false\n        private set\n',
        '    var subsystemsInitialized: Boolean = false\n        private set\n\n'
        '    @Volatile\n'
        '    var dbVersionDecision: DatabaseVersionGuard.Decision = DatabaseVersionGuard.Decision.PROCEED\n'
        '        private set\n',
        "MinisApp guard state",
    )
anchor = '''        try {
        // Repositories that read canonical guest files must have a configured
        // broker client before their constructors run.
        UbuntuRuntime.init(this)
        database = AppDatabase.getInstance(this)
'''
if 'dbVersionDecision = DatabaseVersionGuard.evaluate(this)' not in s:
    replacement = '''        dbVersionDecision = DatabaseVersionGuard.evaluate(this)

        try {
        if (dbVersionDecision == DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE) {
            error(
                "on-disk chat schema is newer than this build " +
                    "(code=${DatabaseVersionGuard.CODE_DB_VERSION}); refusing to open it; database left untouched"
            )
        }
        // Repositories that read canonical guest files must have a configured
        // broker client before their constructors run.
        UbuntuRuntime.init(this)
        database = AppDatabase.getInstance(this)
'''
    s = replace_once(s, anchor, replacement, "MinisApp guard evaluation")
write(rel, s)

guidance = '''package com.openminis.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.db.DatabaseVersionGuard

@Composable
fun NewerDatabaseGuidanceScreen(onExit: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.newer_db_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.newer_db_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.newer_db_versions, DatabaseVersionGuard.CODE_DB_VERSION),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.newer_db_exit))
                    }
                }
            }
        }
    }
}
'''
write("src/android/app/src/main/java/com/openminis/app/ui/NewerDatabaseGuidanceScreen.kt", guidance)

rel = "src/android/app/src/main/java/com/openminis/app/MainActivity.kt"
s = read(rel)
if 'import com.openminis.app.ui.NewerDatabaseGuidanceScreen' not in s:
    marker = 'import com.openminis.app.ui.navigation.safeNavigate\n'
    if marker in s:
        s = replace_once(s, marker, marker + 'import com.openminis.app.ui.NewerDatabaseGuidanceScreen\n', "MainActivity guidance import")
    else:
        # Some Fork revisions do not import safeNavigate at this point; put the UI import next to AppNavigation.
        marker = 'import com.openminis.app.ui.navigation.AppNavigation\n'
        s = replace_once(s, marker, marker + 'import com.openminis.app.ui.NewerDatabaseGuidanceScreen\n', "MainActivity guidance import fallback")
if 'showing newer-database guidance screen' not in s:
    anchor = '''        val minisApp = application as? MinisApp

        if (minisApp == null || !minisApp.subsystemsInitialized) {
'''
    replacement = '''        val minisApp = application as? MinisApp

        if (minisApp != null &&
            minisApp.dbVersionDecision ==
            com.openminis.app.data.db.DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE
        ) {
            android.util.Log.w("MainActivity", "showing newer-database guidance screen")
            setContent { NewerDatabaseGuidanceScreen(onExit = { finishAndRemoveTask() }) }
            return
        }

        if (minisApp == null || !minisApp.subsystemsInitialized) {
'''
    s = replace_once(s, anchor, replacement, "MainActivity newer DB guidance")
write(rel, s)

# Resource strings. Add only when absent; non-translated locales fall back to values/.
def add_strings(rel, entries):
    s = read(rel)
    if 'name="newer_db_title"' in s:
        return
    block = ''.join(f'    <string name="{name}">{value}</string>\n' for name, value in entries)
    s = replace_once(s, '</resources>', '\n' + block + '</resources>', rel)
    write(rel, s)

add_strings(
    "src/android/app/src/main/res/values/strings.xml",
    [
        ("newer_db_title", "Your data is from a newer version"),
        ("newer_db_body", "This copy of Minis is older than the one that created your data, so it cannot open it safely. Nothing has been deleted. Reinstall the newer version of Minis and your conversations will be available again."),
        ("newer_db_versions", "This version supports data format %1$d"),
        ("newer_db_exit", "Close"),
    ],
)
add_strings(
    "src/android/app/src/main/res/values-zh/strings.xml",
    [
        ("newer_db_title", "数据来自更新的版本"),
        ("newer_db_body", "当前安装的 Minis 比创建这些数据的版本更旧，因此无法安全打开数据库。数据没有被删除。重新安装较新的 Minis 版本后，会恢复原有会话。"),
        ("newer_db_versions", "当前版本支持的数据格式：%1$d"),
        ("newer_db_exit", "关闭"),
    ],
)
add_strings(
    "src/android/app/src/main/res/values-zh-rTW/strings.xml",
    [
        ("newer_db_title", "資料來自較新的版本"),
        ("newer_db_body", "目前安裝的 Minis 比建立這些資料的版本更舊，因此無法安全開啟資料庫。資料沒有被刪除。重新安裝較新的 Minis 版本後，原有對話會恢復。"),
        ("newer_db_versions", "目前版本支援的資料格式：%1$d"),
        ("newer_db_exit", "關閉"),
    ],
)

# Structural assertions: fail before commit if any intended behavior is missing.
checks = {
    "provider merge entries": ("src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt", "mergedEntries"),
    "provider merge groups": ("src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt", "orderedGroups"),
    "provider keep key": ("src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt", "loadApiKey(instance.id).isNullOrBlank()"),
    "grok46": ("src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt", 'grok-4.6'),
    "zh hant": ("src/android/app/src/main/res/xml/locales_config.xml", 'zh-Hant'),
    "db20": ("src/android/app/src/main/java/com/openminis/app/data/db/DatabaseVersionGuard.kt", "CODE_DB_VERSION = 20"),
    "db guidance": ("src/android/app/src/main/java/com/openminis/app/MainActivity.kt", "showing newer-database guidance screen"),
    "fork runtime retained": ("src/android/app/src/main/java/com/openminis/app/MinisApp.kt", "UbuntuRuntime.init(this)"),
}
for label, (rel, needle) in checks.items():
    if needle not in read(rel):
        raise SystemExit(f"validation failed: {label}")

print("upstream audit gap patch applied and validated")
