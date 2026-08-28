from pathlib import Path
import re
import subprocess

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    (ROOT / path).write_text(text, encoding='utf-8')

def sub_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    return text.replace(old, new, 1)

# Restore files whose PR changes were exclusively the Top P / Top K sampling experiment.
restore = [
    'src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt',
    'src/android/app/src/main/java/com/openminis/app/provider/LLMProvider.kt',
    'src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt',
    'src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiModelsApi.kt',
    'src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt',
    'src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt',
]
subprocess.run(['git', 'checkout', 'origin/master', '--', *restore], check=True)

policy = ROOT / 'src/android/app/src/main/java/com/openminis/app/provider/SamplingPolicy.kt'
if policy.exists():
    policy.unlink()

# SessionOverrides: remove persistence/parsing for Top P / Top K.
p = 'src/android/app/src/main/java/com/openminis/app/data/model/SessionOverrides.kt'
s = read(p)
for old in [
    '    val topP: Double? = null,\n',
    '    val topK: Int? = null,\n',
    '            topP == null &&\n',
    '            topK == null &&\n',
    '        topP?.let { json.put(KEY_TOP_P, it) }\n',
    '        topK?.let { json.put(KEY_TOP_K, it) }\n',
    '        private const val KEY_TOP_P = "topP"\n',
    '        private const val KEY_TOP_K = "topK"\n',
    '            val topP = json.optDouble(KEY_TOP_P, Double.NaN)\n                .takeIf { it.isFinite() && it in 0.0..1.0 }\n',
    '            val topK = json.optInt(KEY_TOP_K, -1).takeIf { it > 0 }\n',
    '                topP = topP,\n',
    '                topK = topK,\n',
]:
    s = sub_once(s, old, '', f'SessionOverrides {old.strip()}')
write(p, s)

# Agent loop: go back to the normal provider entry; keep Temperature and all other session overrides.
p = 'src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt'
s = read(p)
s = sub_once(s, '                    currentProvider.streamMessageWithSampling(\n', '                    currentProvider.streamMessage(\n', 'ChatViewModel sampling entry')
s = sub_once(s, '                        topP = sessionOverrides.topP,\n', '', 'ChatViewModel topP')
s = sub_once(s, '                        topK = sessionOverrides.topK,\n', '', 'ChatViewModel topK')
write(p, s)

# Advanced settings UI: remove Top P / Top K controls and validation.
p = 'src/android/app/src/main/java/com/openminis/app/ui/chat/SessionAdvancedSettingsSheet.kt'
s = read(p)
for old in [
    '    var customTopP by remember(sessionId) { mutableStateOf(false) }\n    var topPText by remember(sessionId) { mutableStateOf("") }\n',
    '    var customTopK by remember(sessionId) { mutableStateOf(false) }\n    var topKText by remember(sessionId) { mutableStateOf("") }\n',
    '    val invalidTopPMessage = stringResource(R.string.session_advanced_invalid_top_p)\n',
    '    val invalidTopKMessage = stringResource(R.string.session_advanced_invalid_top_k)\n',
    '        customTopP = overrides.topP != null\n        topPText = overrides.topP?.toString().orEmpty()\n',
    '        customTopK = overrides.topK != null\n        topKText = overrides.topK?.toString().orEmpty()\n',
    '            topP = topP,\n',
    '            topK = topK,\n',
]:
    s = sub_once(s, old, '', f'Advanced UI {old.splitlines()[0].strip()}')

pattern = re.compile(r'\n        val topP = if \(customTopP\) \{.*?\n        val topK = if \(customTopK\) \{.*?\n        \} else \{\n            null\n        \}\n', re.S)
s, n = pattern.subn('\n', s, count=1)
if n != 1:
    raise SystemExit(f'Advanced UI validation blocks: expected 1, got {n}')

start = '                    SettingsSwitchRow(\n                        title = stringResource(R.string.session_advanced_top_p),'
end = '                    SettingsSwitchRow(\n                        title = stringResource(R.string.session_advanced_max_tokens),'
i = s.find(start)
j = s.find(end, i + 1)
if i < 0 or j < 0:
    raise SystemExit('Advanced UI Top P/K block anchors not found')
s = s[:i] + s[j:]
s = sub_once(
    s,
    '                    footer = stringResource(R.string.session_advanced_sampling_help),\n',
    '                    footer = stringResource(R.string.session_advanced_settings_inherit_help),\n',
    'Advanced UI model footer',
)
write(p, s)

# Remove Top P/K strings from every locale file.
string_names = {
    'session_advanced_top_p', 'session_advanced_top_p_hint',
    'session_advanced_top_k', 'session_advanced_top_k_hint',
    'session_advanced_sampling_help',
    'session_advanced_invalid_top_p', 'session_advanced_invalid_top_k',
}
for fp in ROOT.glob('src/android/app/src/main/res/values*/strings_session_advanced.xml'):
    lines = fp.read_text(encoding='utf-8').splitlines(keepends=True)
    out = []
    removed = set()
    for line in lines:
        hit = next((name for name in string_names if f'name="{name}"' in line), None)
        if hit:
            removed.add(hit)
            continue
        out.append(line)
    if removed != string_names:
        raise SystemExit(f'{fp}: removed {sorted(removed)}, expected all sampling strings')
    fp.write_text(''.join(out), encoding='utf-8')

# Unit tests: remove sampling-only test data/cases while keeping sparse JSON coverage.
p = 'src/android/app/src/test/java/com/openminis/app/data/model/SessionOverridesTest.kt'
s = read(p)
s = sub_once(s, '            topP = 0.8,\n            topK = 32,\n', '', 'test roundtrip sampling')
pattern = re.compile(r'\n    @Test\n    fun `sampling boundaries are accepted and invalid neighbors inherit`\(\) \{.*?\n    \}\n', re.S)
s, n = pattern.subn('\n', s, count=1)
if n != 1:
    raise SystemExit(f'sampling boundary test: expected 1, got {n}')
s = sub_once(s, '                "topP":-0.1,\n                "topK":0,\n', '', 'test malformed sampling json')
s = sub_once(s, '        assertNull(decoded.topP)\n        assertNull(decoded.topK)\n', '', 'test malformed sampling assertions')
write(p, s)

# Ensure none of the GH#32 Top P/K plumbing remains in the files we own.
checks = [
    'src/android/app/src/main/java/com/openminis/app/data/model/SessionOverrides.kt',
    'src/android/app/src/main/java/com/openminis/app/ui/chat/SessionAdvancedSettingsSheet.kt',
    'src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt',
    'src/android/app/src/main/java/com/openminis/app/provider/LLMProvider.kt',
    'src/android/app/src/test/java/com/openminis/app/data/model/SessionOverridesTest.kt',
]
for path in checks:
    text = read(path)
    for token in ('sessionOverrides.topP', 'sessionOverrides.topK', 'streamMessageWithSampling', 'streamMessageSamplingClamped', 'session_advanced_top_p', 'session_advanced_top_k'):
        if token in text:
            raise SystemExit(f'{path}: leftover {token}')

subprocess.run(['git', 'diff', '--check'], check=True)
