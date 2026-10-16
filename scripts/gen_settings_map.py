#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Minis for Android: settings page map, generated from the code.

Writes one standalone HTML page: the page tree (Settings home -> category -> page ->
sub-page) with, under each page, the groups and items found in its Compose source.

What comes from the code, what is kept here:
  * page titles, group headers, item titles and segmented options are read from the
    screens' `stringResource(R.string.x)` calls and resolved in values-zh/ (English as
    fallback), so a renamed string shows up on the next run;
  * which pages exist is checked against the `composable(Routes.X)` registrations in
    AppNavigation.kt: a route that is neither in PAGES nor in OUTSIDE_SETTINGS, or a
    PAGES route that is no longer registered, makes the script exit non-zero;
  * the parent/child shape of the tree (PAGES below) is written by hand, because the
    navigation callbacks are spread over lambdas that cannot be read reliably.
Only items whose title is a `stringResource` are listed; rows built in loops (provider
types, model lists, languages) and the contents of dialogs and sheets are not.

Usage (from the repository root):
  python3 scripts/gen_settings_map.py                    # writes settings-map.html
  python3 scripts/gen_settings_map.py --out some/dir/x.html
"""
import argparse
import html
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

APP = Path("src/android/app/src/main")
RES = APP / "res"
UI = APP / "java/com/openminis/app/ui"
NAV = UI / "navigation/AppNavigation.kt"
SETTINGS = UI / "settings"
CATEGORY_SCREEN = SETTINGS / "SettingsCategoryScreen.kt"

# (title, route constant, source, children). `title` is a string key or a literal wrapped in
# quotes. `source` is a file under ui/settings, or "File.kt#CATEGORY" for a branch of
# SettingsCategoryScreen. A child written as ("ref", title, route) is a link to a page that
# already sits elsewhere in the tree.
PAGES = (
    "settings_title", "SETTINGS", "SettingsScreen.kt", [
        ("settings_cat_models", None, "SettingsCategoryScreen.kt#MODELS", [
            ("settings_manage_providers", "PROVIDER_LIST", "ProviderListScreen.kt", [
                ("'添加服务商'", "ADD_PROVIDER", "AddProviderScreen.kt", []),
                ("'服务商详情'", "PROVIDER_DETAIL", "ProviderDetailScreen.kt", [
                    ("'添加自定义模型'", "ADD_CUSTOM_MODEL", "AddCustomModelScreen.kt", []),
                    ("'模型详情'", "MODEL_ENTRY_DETAIL", "ModelEntryDetailScreen.kt", []),
                ]),
                ("'语音服务详情'", "SHADOW_VOICE_DETAIL", "ShadowVoiceDetailScreen.kt", []),
            ]),
            ("model_slots_header", "MODELS", "ModelsScreen.kt", [
                ("'模型槽位详情'", "MODEL_SLOT_DETAIL", "ModelSlotDetailScreen.kt", []),
            ]),
            ("settings_agent_loop_models", "AGENT_LOOP_MODELS", "AgentModelsScreen.kt", [
                ("'添加模型'", "ADD_MODELS_TO_AGENT_LOOP", "AgentLoopAddSheets.kt", []),
            ]),
            ("settings_token_usage", "USAGE_STATS", "UsageStatsScreen.kt", []),
        ]),
        ("settings_cat_agent", None, "SettingsCategoryScreen.kt#AGENT", [
            ("settings_skills", "SKILLS", "SkillsManagementScreen.kt", [
                ("'技能详情'", "SKILL_DETAIL", None, [
                    ("'技能文件'", "SKILL_FILE", None, []),
                ]),
                ("'Minis 技能库'", "MINIS_SKILLS_BROWSER", "MinisSkillsBrowserScreen.kt", []),
            ]),
            ("settings_memory", "MEMORY", "MemoryManagementScreen.kt", [
                ("'记忆文件编辑'", "MEMORY_FILE_EDIT", None, []),
            ]),
            ("settings_mcp", "MCP", "MCPIntegrationsScreen.kt", []),
            ("settings_soul", "SOUL", "SoulSettingsScreen.kt", []),
            ("characters_title", "CHARACTERS", "CharactersScreen.kt", [
                ("'角色详情'", "CHARACTER_DETAIL", "CharacterDetailScreen.kt", []),
            ]),
            ("settings_system_prompt", "SYSTEM_PROMPT", "SystemPromptSettingsScreen.kt", [
                ("'系统提示词模块'", "SYSTEM_PROMPT_MODULES", "SystemPromptModulesScreen.kt", []),
            ]),
            ("scheduled_tasks_title", "SCHEDULED_TASKS", None, [
                ("'编辑定时任务'", "SCHEDULED_TASK_EDIT", None, []),
                ("'运行记录'", "SCHEDULED_TASK_RUNS", None, []),
            ]),
            ("sub_agents_title", "SUB_AGENTS", "SubAgentsScreen.kt", []),
        ]),
        ("settings_cat_chat", None, "ChatInputSettingsScreen.kt", [
            ("'语音通话设置'", "VOICE_CALL_SETTINGS", "VoiceCallSettingsScreen.kt", []),
        ]),
        ("appearance_title", None, "AppearanceScreen.kt", []),
        ("settings_section_files", None, "FilesHubScreen.kt", [
            ("files_mounted_title", "MOUNTED_FOLDERS", "MountedFoldersScreen.kt", [
                ("'挂载详情'", "MOUNTED_FOLDERS_DETAIL", "MountDetailScreen.kt", []),
            ]),
            ("files_session_files_title", "STORAGE", "StorageManagementScreen.kt", [
                ("'会话存储详情'", "SESSION_STORAGE_DETAIL", None, []),
            ]),
            ("'备份与恢复'", "BACKUP", None, [
                ("'备份目的地'", "BACKUP_DESTINATIONS", None, []),
                ("'备份记录详情'", "BACKUP_HISTORY_DETAIL", None, [
                    ("'浏览目的地'", "BACKUP_DESTINATION_BROWSE", None, []),
                ]),
                ("'恢复服务器'", "RESTORE_SERVERS", None, [
                    ("'浏览恢复源'", "RESTORE_BROWSE", None, []),
                ]),
            ]),
            ("ref", "files_shared_title", "FILE_BROWSER"),
            ("ref", "settings_skills", "SKILLS"),
            ("ref", "settings_memory", "MEMORY"),
        ]),
        ("settings_cat_runtime", None, "SettingsCategoryScreen.kt#RUNTIME", [
            ("settings_runtime_rootfs_title", "ROOTFS_MANAGEMENT", "RootfsManagementScreen.kt", [
                ("'镜像分类详情'", "MIRROR_CATEGORY_DETAIL", None, []),
            ]),
            ("settings_env_vars", "ENV_VARS", "EnvironmentVariablesScreen.kt", []),
        ]),
        ("settings_section_system", None, "SettingsCategoryScreen.kt#SYSTEM", [
            ("settings_tool_permissions", "PERMISSIONS", "OffloadPermissionScreen.kt", []),
            ("system_enhance_root", "ROOT", "RootAccessScreen.kt", []),
            ("system_permissions_title", "SYSTEM_PERMISSIONS", "SystemPermissionsScreen.kt", []),
            ("settings_vscreen_entry", "VIRTUAL_SCREEN_SETTINGS", "VirtualScreenSettingsScreen.kt", []),
            ("bg_section_header", "BACKGROUND", "BackgroundSettingsScreen.kt", []),
            ("system_enhance_title", "SYSTEM_ENHANCE", "SystemEnhanceScreen.kt", []),
        ]),
        ("settings_cat_data", None, "SettingsCategoryScreen.kt#DATA", [
            ("settings_section_logs", "LOGS", "LogManagementScreen.kt", [
                ("'日志详情'", "LOG_DETAIL", None, []),
            ]),
            ("settings_about_minis", "ABOUT", "AboutScreen.kt", []),
        ]),
    ],
)

# Registered in AppNavigation.kt but not part of the settings tree.
OUTSIDE_SETTINGS = {
    "ASSISTANT_HOME": "助手首页",
    "SESSION_LIST": "会话列表",
    "CHAT": "对话",
    "CHAT_FILES": "对话文件",
    "FILE_PREVIEW": "文件预览",
    "TERMINAL": "终端",
    "ONBOARDING_MODELS": "首次引导选模型",
    "SETTINGS_CATEGORY": "分类页入口（由各分类统一渲染）",
}

ROW_KINDS = {
    "SettingsRow": "row",
    "SettingsSwitchRow": "switch",
    "SettingsValueRow": "value",
    "SettingsInlineTextRow": "value",
    "SettingsChoiceRow": "value",
    "FontScaleSliderRow": "slider",
}
CALL = re.compile(r"\b(SettingsSection|SettingsRow|SettingsSwitchRow|SettingsValueRow|SettingsInlineTextRow"
                  r"|SettingsChoiceRow|FontScaleSliderRow|SettingsSegmented)\(")
STR_REF = re.compile(r"stringResource\(\s*(?:com\.openminis\.app\.)?R\.string\.([A-Za-z0-9_]+)")
KIND_LABEL = {"row": "项", "switch": "开关", "value": "取值", "slider": "滑块", "options": "选项"}


def load_strings(folder):
    out = {}
    for f in sorted((RES / folder).glob("*.xml")):
        for el in ET.parse(f).getroot():
            if el.tag == "string" and el.get("name"):
                out[el.get("name")] = "".join(el.itertext())
    return out


SCREEN_FILES = None
ROUTE_SCREENS = None
EN = load_strings("values")
ZH = load_strings("values-zh")


def text(key):
    raw = ZH.get(key) or EN.get(key)
    if raw is None:
        raise KeyError(f"string {key} not found")
    raw = raw.replace("\\'", "'").replace('\\"', '"').replace("\\n", " ")
    return re.sub(r"%(?:\d+\$)?[sd]", "…", raw).strip()


def title_of(spec):
    return spec[1:-1] if spec.startswith("'") else text(spec)


def screen_files():
    """Screen function name -> file, for every `fun XxxScreen(` under ui/."""
    found = {}
    for f in UI.rglob("*.kt"):
        for name in re.findall(r"^fun ([A-Z]\w*Screen)\(", f.read_text(encoding="utf-8"), re.M):
            found[name] = f
    return found


def route_screens():
    """Route constant -> first `XxxScreen(` called inside its composable() in AppNavigation.kt."""
    src = NAV.read_text(encoding="utf-8")
    cuts = [m.start() for m in re.finditer(r"\n {8}composable\(", src)] + [len(src)]
    out = {}
    for a, b in zip(cuts, cuts[1:]):
        block = src[a:b]
        route = re.search(r"composable\(\s*(?:route\s*=\s*)?\"?\$?\{?Routes\.([A-Z_]+)", block)
        screen = re.search(r"\b([A-Z]\w*Screen)\(", block)
        if route and screen:
            out[route.group(1)] = screen.group(1)
    return out


def screen_scope(body, screen):
    """The screen function plus the same-file helpers it calls, so a file that holds a list page and
    its detail page does not list the same items under both."""
    marks = list(re.finditer(r"^(?:private |internal )?fun (?:[\w.<>]+\.)?(\w+)\(", body, re.M))
    funcs = {}
    for i, m in enumerate(marks):
        funcs[m.group(1)] = body[m.start():marks[i + 1].start() if i + 1 < len(marks) else len(body)]
    if screen not in funcs:
        return body
    seen, queue = {screen}, [screen]
    while queue:
        for name in funcs:
            if name not in seen and re.search(r"\b" + re.escape(name) + r"\(", funcs[queue[0]]):
                seen.add(name)
                queue.append(name)
        queue.pop(0)
    return "\n".join(funcs[n] for n in funcs if n in seen)


def read_source(source, screen=None):
    """Return (text, category) for a PAGES source, or (None, None)."""
    if not source:
        return None, None
    name, _, cat = source.partition("#")
    path = SETTINGS / name
    if not path.exists():
        path = next(UI.rglob(name), None)
        if path is None:
            raise SystemExit(f"{source}: {name} not found under ui/")
    body = path.read_text(encoding="utf-8")
    if screen and not cat:
        body = screen_scope(body, screen)
    if not cat:
        return body, None
    parts = re.split(r"\n {12}(?=SettingsCategory\.[A-Z])", body)
    for part in parts:
        head = re.match(r"SettingsCategory\.([A-Z_, .a-zA-Z]+?) ->", part)
        if head and cat in re.findall(r"[A-Z]+", head.group(1)):
            return part, cat
    raise SystemExit(f"{source}: no branch for {cat}")


def outline(body):
    """Groups of (kind, title, extra) in source order: [(header|None, [items])]."""
    groups = [[None, []]]
    hits = list(CALL.finditer(body))
    for i, m in enumerate(hits):
        end = hits[i + 1].start() if i + 1 < len(hits) else len(body)
        chunk = body[m.end():min(end, m.end() + 1400)]
        name = m.group(1)
        if name == "SettingsSection":
            head = re.match(r"\s*header\s*=\s*" + STR_REF.pattern, chunk)
            groups.append([text(head.group(1)) if head else None, []])
        elif name == "SettingsSegmented":
            opts = re.search(r"options\s*=\s*listOf\((.*?)\)\s*,\s*\n\s*selectedIndex", chunk, re.S)
            keys = STR_REF.findall(opts.group(1)) if opts else []
            if keys:
                groups[-1][1].append(("options", "、".join(text(k) for k in keys), None))
        else:
            t = re.search(r"\btitle\s*=\s*" + STR_REF.pattern, chunk)
            if t:
                groups[-1][1].append((ROW_KINDS[name], text(t.group(1)), None))
    return [(h, items) for h, items in groups if items]


def registered_routes():
    src = NAV.read_text(encoding="utf-8")
    return set(re.findall(r"composable\(\s*(?:route\s*=\s*)?\"?\$?\{?Routes\.([A-Z_]+)", src))


def walk(node, depth=0):
    if node[0] == "ref":
        yield node
        return
    yield node
    for child in node[3]:
        yield from walk(child, depth + 1)


def render_outline(groups):
    rows = []
    for header, items in groups:
        li = "".join(
            f'<li><span class="k k-{k}">{KIND_LABEL[k]}</span>{html.escape(t)}</li>' for k, t, _ in items
        )
        head = f'<div class="g">{html.escape(header)}</div>' if header else ""
        rows.append(f"{head}<ul>{li}</ul>")
    return "".join(rows)


def count(groups):
    items = [i for _, g in groups for i in g]
    return len(groups), len(items), sum(1 for i in items if i[0] == "switch")


def render(node, level=0):
    if node[0] == "ref":
        _, title_key, route = node
        return (f'<div class="leaf ref"><span class="t">{html.escape(title_of(title_key))}</span>'
                f'<span class="route">→ {route}</span><span class="note">同上，入口另在此处</span></div>')
    title, route, source, children = node
    if source is None and route in ROUTE_SCREENS and ROUTE_SCREENS[route] in SCREEN_FILES:
        source = SCREEN_FILES[ROUTE_SCREENS[route]].name
    body, cat = read_source(source, ROUTE_SCREENS.get(route))
    groups = outline(body) if body else []
    label = html.escape(title_of(title))
    chip = f'<span class="route">{route}</span>' if route else '<span class="route cat">分类页</span>' if cat or source else ""
    summary = ""
    if groups:
        g, n, s = count(groups)
        summary = f'<span class="sum">{g} 组 · {n} 项' + (f" · {s} 开关" if s else "") + "</span>"
    if groups:
        detail = f'<details class="items"><summary>设置项</summary>{render_outline(groups)}</details>'
    elif source and level > 0:
        detail = '<div class="note empty">没有用 stringResource 写标题的设置项（自定义组件或动态列表）</div>'
    else:
        detail = ""
    inner = detail + "".join(render(c, level + 1) for c in children)
    head = f'<span class="t">{label}</span>{chip}{summary}'
    if not inner:
        return f'<div class="leaf">{head}</div>'
    opened = " open" if level < 2 else ""
    return f'<details class="page"{opened}><summary>{head}</summary><div class="kids">{inner}</div></details>'


STYLE = """
:root{--bg:#f6f6f8;--fg:#1c1c1e;--mut:#6e6e73;--line:#d8d8de;--card:#fff;--acc:#0a84ff;--chip:#eef0f4}
@media (prefers-color-scheme: dark){:root{--bg:#111113;--fg:#f2f2f4;--mut:#9a9aa2;--line:#2c2c31;--card:#1b1b1f;--acc:#4da3ff;--chip:#26262b}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,"PingFang SC","Noto Sans SC",sans-serif}
main{max-width:900px;margin:0 auto;padding:24px 16px 64px}
h1{font-size:22px;margin:0 0 4px}p.lead{color:var(--mut);margin:0 0 20px;font-size:13px}
.bar{display:flex;gap:8px;margin-bottom:16px}.bar button{background:var(--card);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:6px 12px;font:inherit;cursor:pointer}
details.page,.leaf{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:8px 0}
details.page>summary,.leaf{padding:9px 12px;cursor:pointer;display:flex;gap:10px;align-items:baseline;flex-wrap:wrap;list-style:none}
details.page>summary::-webkit-details-marker{display:none}
details.page>summary::before{content:"▸";color:var(--mut);width:1em}details.page[open]>summary::before{content:"▾"}
.leaf{cursor:default;padding-left:34px}.leaf.ref{opacity:.7}
.kids{padding:0 10px 10px 22px;border-left:2px solid var(--line);margin:0 0 4px 16px}
.t{font-weight:600}.route{font:12px ui-monospace,Menlo,monospace;color:var(--mut);background:var(--chip);border-radius:6px;padding:1px 6px}
.route.cat{color:var(--acc)}.sum{color:var(--mut);font-size:12px}.note{color:var(--mut);font-size:12px}.note.empty{margin:6px 0 6px 6px}
details.items{margin:6px 0 6px 6px}details.items>summary{cursor:pointer;color:var(--acc);font-size:13px}
.g{margin:10px 0 2px;font-size:12px;color:var(--mut);text-transform:none;letter-spacing:.02em}
ul{list-style:none;margin:2px 0;padding:0}li{padding:2px 0;font-size:14px}
.k{display:inline-block;min-width:3em;margin-right:8px;font-size:11px;text-align:center;border-radius:5px;padding:0 5px;background:var(--chip);color:var(--mut)}
.k-switch{color:var(--acc)}.k-options{color:#af52de}
footer{margin-top:24px;color:var(--mut);font-size:12px}
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="settings-map.html")
    args = ap.parse_args()

    global SCREEN_FILES, ROUTE_SCREENS
    SCREEN_FILES, ROUTE_SCREENS = screen_files(), route_screens()
    tree_routes = {n[1] if n[0] != "ref" else None for root in [PAGES] for n in walk(root)}
    tree_routes.discard(None)
    registered = registered_routes()
    problems = []
    for r in sorted(registered - tree_routes - set(OUTSIDE_SETTINGS) - {"FILE_BROWSER"}):
        problems.append(f"route {r} is registered in AppNavigation.kt but not in PAGES / OUTSIDE_SETTINGS")
    for r in sorted(tree_routes - registered):
        problems.append(f"PAGES lists {r}, which AppNavigation.kt no longer registers")
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1

    pages = sum(1 for n in walk(PAGES) if n[0] != "ref")
    body = render(PAGES)
    doc = f"""<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>设置页面地图</title>
<style>{STYLE}</style><main>
<h1>设置页面地图</h1>
<p class="lead">共 {pages} 个页面。由 scripts/gen_settings_map.py 从代码生成：页面是否存在对照 AppNavigation.kt，标题、分组、设置项来自各页面的 stringResource。</p>
<div class="bar"><button onclick="document.querySelectorAll('details.page').forEach(d=>d.open=true)">全部展开</button>
<button onclick="document.querySelectorAll('details.page').forEach(d=>d.open=false)">全部收起</button>
<button onclick="document.querySelectorAll('details.items').forEach(d=>d.open=true)">展开设置项</button></div>
{body}
<footer>只列出标题用 stringResource 写的设置项；按循环生成的行（服务商类型、模型列表、语言等）和弹窗/底部面板里的内容不在其中。</footer>
</main></html>"""
    Path(args.out).write_text(doc, encoding="utf-8")
    print(f"wrote {args.out}: {pages} pages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
