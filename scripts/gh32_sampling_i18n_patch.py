from pathlib import Path

entries = {
    "values-fr": {
        "top_p": "Top P", "top_k": "Top K", "top_k_hint": "Entier positif",
        "help": "Top P est appliqué lorsqu’il est pris en charge. Top K est appliqué par Anthropic et Gemini ; les fournisseurs non compatibles l’ignorent en toute sécurité.",
        "invalid_p": "Top P doit être compris entre 0.0 et 1.0.",
        "invalid_k": "Top K doit être un entier positif.",
    },
    "values-ja": {
        "top_p": "Top P", "top_k": "Top K", "top_k_hint": "正の整数",
        "help": "Top P は対応するプロバイダーで適用されます。Top K は Anthropic と Gemini で適用され、未対応のプロバイダーでは安全に無視されます。",
        "invalid_p": "Top P は 0.0 から 1.0 の範囲で指定してください。",
        "invalid_k": "Top K は正の整数で指定してください。",
    },
    "values-ko": {
        "top_p": "Top P", "top_k": "Top K", "top_k_hint": "양의 정수",
        "help": "Top P는 지원되는 제공자에서 적용됩니다. Top K는 Anthropic과 Gemini에서 적용되며 지원하지 않는 제공자는 안전하게 무시합니다.",
        "invalid_p": "Top P는 0.0에서 1.0 사이여야 합니다.",
        "invalid_k": "Top K는 양의 정수여야 합니다.",
    },
    "values-ru": {
        "top_p": "Top P", "top_k": "Top K", "top_k_hint": "Положительное целое число",
        "help": "Top P применяется там, где поддерживается. Top K применяется Anthropic и Gemini; неподдерживающие провайдеры безопасно его игнорируют.",
        "invalid_p": "Top P должен быть от 0.0 до 1.0.",
        "invalid_k": "Top K должен быть положительным целым числом.",
    },
    "values-zh-rTW": {
        "top_p": "Top P", "top_k": "Top K", "top_k_hint": "正整數",
        "help": "Top P 會在支援的 Provider 上生效。Top K 目前由 Anthropic 與 Gemini 套用；不支援的 Provider 會安全忽略。",
        "invalid_p": "Top P 必須介於 0.0 與 1.0 之間。",
        "invalid_k": "Top K 必須是正整數。",
    },
}

for folder, t in entries.items():
    path = Path(f"src/android/app/src/main/res/{folder}/strings_session_advanced.xml")
    text = path.read_text()
    if "session_advanced_top_p" in text:
        raise SystemExit(f"{path}: sampling strings already present")

    temp_anchor = '    <string name="session_advanced_temperature_hint">0.0–2.0</string>\n'
    if text.count(temp_anchor) != 1:
        raise SystemExit(f"{path}: temperature anchor count={text.count(temp_anchor)}")
    sampling = (
        f'    <string name="session_advanced_top_p">{t["top_p"]}</string>\n'
        '    <string name="session_advanced_top_p_hint">0.0–1.0</string>\n'
        f'    <string name="session_advanced_top_k">{t["top_k"]}</string>\n'
        f'    <string name="session_advanced_top_k_hint">{t["top_k_hint"]}</string>\n'
        f'    <string name="session_advanced_sampling_help">{t["help"]}</string>\n'
    )
    text = text.replace(temp_anchor, temp_anchor + sampling, 1)

    invalid_anchor = next(
        line + "\n" for line in text.splitlines()
        if 'name="session_advanced_invalid_temperature"' in line
    )
    invalids = (
        f'    <string name="session_advanced_invalid_top_p">{t["invalid_p"]}</string>\n'
        f'    <string name="session_advanced_invalid_top_k">{t["invalid_k"]}</string>\n'
    )
    text = text.replace(invalid_anchor, invalid_anchor + invalids, 1)
    path.write_text(text)
