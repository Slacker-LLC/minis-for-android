#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Minis for Android: user-visible string audit (re-runnable).

Sections:
  A  keys whose value equals the English default (per locale)
  B  keys in values/ but missing from a locale (English fallback)
  C  keys present only in a locale (orphans)
  D  plural quantity gaps (ru needs 'many', etc.)
  E  format-argument mismatches vs the default
  F  hardcoded literals in Kotlin: CJK, UI-context English, prose candidates
  G  hardcoded text in non-values XML resources

Every A/B row carries a "referenced" flag: whether R.string.<key> is used
anywhere under src/android (a string nobody references needs no translation).

Usage (from the repository root):
  python3 scripts/audit_strings.py                 # writes reports to a temp dir
  python3 scripts/audit_strings.py --out reports/  # writes them where you want
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, OrderedDict, namedtuple
from pathlib import Path

DEFAULT = "values"
LOCALES = ["values-zh", "values-zh-rTW", "values-ja", "values-ko",
           "values-de", "values-fr", "values-ru"]
RES = "src/android/app/src/main/res"
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
WS = re.compile(r"\s+")
FMT = re.compile(r"%(?:\d+\$)?[sd]")
WORD = re.compile(r"[A-Za-z]{2,}")
REF = re.compile(r"(?:R\.string\.|@string/)([A-Za-z0-9_]+)")

Entry = namedtuple("Entry", "name kind value translatable file")


def norm(s):
    return WS.sub(" ", s or "").strip()


def text_of(el):
    parts = []

    def walk(node):
        if node.text:
            parts.append(node.text)
        for child in node:
            walk(child)
            if child.tail:
                parts.append(child.tail)

    walk(el)
    return "".join(parts)


def parse_file(path):
    out = []
    try:
        root = ET.parse(str(path)).getroot()
    except ET.ParseError as exc:
        print("WARN parse error %s: %s" % (path, exc), file=sys.stderr)
        return out
    for el in list(root):
        if el.tag not in ("string", "plurals", "string-array"):
            continue
        name = el.get("name")
        if not name:
            continue
        tr = el.get("translatable")
        if el.tag == "string":
            out.append(Entry(name, "string", norm(text_of(el)), tr, str(path)))
        elif el.tag == "plurals":
            items = tuple(sorted((it.get("quantity") or "?", norm(text_of(it)))
                                 for it in el.findall("item")))
            out.append(Entry(name, "plurals", items, tr, str(path)))
        else:
            items = tuple(norm(text_of(it)) for it in el.findall("item"))
            out.append(Entry(name, "string-array", items, tr, str(path)))
    return out


def load(repo, locale):
    d = Path(repo) / RES / locale
    entries = OrderedDict()
    dupes = []
    for xml in sorted(d.glob("*.xml")):
        for e in parse_file(xml):
            if e.name in entries:
                dupes.append((locale, e.name, entries[e.name].file, e.file))
                continue
            entries[e.name] = e
    return entries, dupes


def referenced_names(repo):
    refs = Counter()
    root = Path(repo) / "src/android"
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in (".kt", ".xml", ".java"):
            continue
        if "/build/" in str(path):
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for name in REF.findall(text):
            refs[name] += 1
    return refs


UI_CTX = [re.compile(p) for p in [
    r"contentDescription\s*=\s*", r"ContentDescription\s*=\s*",
    r"[Tt]ext\(\s*", r"\btext\s*=\s*", r"[Ll]abel\s*=\s*",
    r"[Tt]itle\s*=\s*", r"[Ss]ubtitle\s*=\s*", r"[Hh]eadline\s*=\s*",
    r"[Ss]upportingText\s*=\s*", r"[Pp]laceholder\s*=\s*",
    r"[Mm]essage\s*=\s*", r"[Hh]int\s*=\s*", r"Toast\.makeText\([^)]*",
    r"[Ss]nackbar[A-Za-z]*\(\s*", r"setTitle\(\s*", r"setText\(\s*",
    r"[Bb]uttonText\s*=\s*", r"[Cc]onfirmText\s*=\s*", r"[Dd]ismissText\s*=\s*",
]]


def scope_of(rel):
    if "/src/test/" in rel or "/androidTest/" in rel:
        return "test"
    if "/third_party/" in rel:
        return "third_party"
    return "main"


def looks_prose(lit):
    if len(lit) < 4 or " " not in lit:
        return False
    for b in ["://", "/", "\\", "=", "(", ")", "{", "}", "#", "&", "?", "%", "<", ">", "@", "|", "$", "'"]:
        if b in lit:
            return False
    if re.match(r"^[A-Z][A-Z0-9_]*$", lit):
        return False
    return len(WORD.findall(lit)) >= 2


def scan_kotlin(repo):
    lit_re = re.compile(r'"(?:\\.|[^"\\])*"')
    cjk_rows, ui_rows, cand_rows = [], [], []
    for path in sorted((Path(repo) / "src/android").rglob("*.kt")):
        ps = str(path)
        if "/build/" in ps:
            continue
        rel = str(path.relative_to(repo))
        scope = scope_of(rel)
        for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for m in lit_re.finditer(line):
                lit = m.group(0)[1:-1]
                if len(lit) < 2:
                    continue
                ctx = line[:m.start()]
                ui = "ui" if any(p.search(ctx) for p in UI_CTX) else ""
                if CJK.search(lit):
                    cjk_rows.append((rel, lineno, lit, scope, ui, line.strip()[:110]))
                elif ui:
                    ui_rows.append((rel, lineno, lit, scope, line.strip()[:110]))
                elif looks_prose(lit):
                    cand_rows.append((rel, lineno, lit, scope, line.strip()[:110]))
    return cjk_rows, ui_rows, cand_rows


def scan_xml_res(repo):
    rows = []
    res = Path(repo) / RES
    pat = re.compile(r'android:(label|title|summary|text|hint|name)="([^"@][^"]*)"')
    for path in sorted(res.rglob("*.xml")):
        if path.parent.name.startswith("values"):
            continue
        rel = str(path.relative_to(repo))
        for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for m in pat.finditer(line):
                rows.append((rel, lineno, m.group(1), m.group(2)))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=str(Path(__file__).resolve().parents[1]))
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    repo = Path(a.repo).resolve()
    if a.out is None:
        import tempfile
        out = Path(tempfile.mkdtemp(prefix="minis-i18n-audit-"))
    else:
        out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    print("reports: %s" % out)

    refs = referenced_names(repo)

    def is_ref(name):
        return "yes" if refs.get(name) else "no"

    default, dupes = load(repo, DEFAULT)
    loc = OrderedDict()
    for l in LOCALES:
        loc[l], dd = load(repo, l)
        dupes += dd

    same, missing, orphan, plural_gap, fmt_bad = [], [], [], [], []
    for l, table in loc.items():
        for name, e in table.items():
            d = default.get(name)
            if d is None:
                orphan.append((l, name, e.kind, str(e.value), is_ref(name)))
                continue
            if e.kind != d.kind:
                fmt_bad.append((l, name, "kind", str(d.value), str(e.value)))
                continue
            if e.kind == "string":
                if e.value == d.value:
                    same.append((l, name, e.value, is_ref(name),
                                 "nontranslatable" if e.translatable == "false" else "",
                                 Path(e.file).name))
                if sorted(FMT.findall(d.value)) != sorted(FMT.findall(e.value)):
                    fmt_bad.append((l, name, "fmt", d.value, e.value))
            elif e.kind == "plurals":
                dq = dict(d.value)
                eq = dict(e.value)
                for q, v in dq.items():
                    if q not in eq:
                        plural_gap.append((l, name, q, v, is_ref(name)))
                for q, v in eq.items():
                    if q in dq and sorted(FMT.findall(dq[q])) != sorted(FMT.findall(v)):
                        fmt_bad.append((l, name + "/" + q, "fmt", dq[q], v))
    for name, d in default.items():
        for l, table in loc.items():
            if name not in table:
                missing.append((l, name, d.kind, str(d.value), is_ref(name), Path(d.file).name))

    def w(fname, header, rows):
        p = out / fname
        with p.open("w", encoding="utf-8") as fh:
            fh.write("\t".join(header) + "\n")
            for r in rows:
                fh.write("\t".join(str(x).replace("\t", " ").replace("\n", " ") for x in r) + "\n")
        return p

    k = scan_kotlin(repo)
    xml_rows = scan_xml_res(repo)
    w("A_same_as_english.tsv", ["locale", "key", "value", "referenced", "flag", "file"], same)
    w("B_missing_in_locale.tsv", ["locale", "key", "kind", "default_value", "referenced", "default_file"], missing)
    w("C_orphan_keys.tsv", ["locale", "key", "kind", "value", "referenced"], orphan)
    w("D_plural_gap.tsv", ["locale", "key", "quantity", "default_value", "referenced"], plural_gap)
    w("E_format_mismatch.tsv", ["locale", "key", "kind", "default", "locale_value"], fmt_bad)
    w("F1_kotlin_cjk.tsv", ["file", "line", "literal", "scope", "ui_ctx", "code"], k[0])
    w("F2_kotlin_ui_english.tsv", ["file", "line", "literal", "scope", "code"], k[1])
    w("F3_kotlin_prose_candidates.tsv", ["file", "line", "literal", "scope", "code"], k[2])
    w("G_xml_hardcoded.tsv", ["file", "line", "attr", "value"], xml_rows)

    lines = ["default keys: %d" % len(default)]
    for l in LOCALES:
        rows_same = [r for r in same if r[0] == l]
        lines.append("%-14s keys=%-5d same_as_en=%-5d(used=%-4d) missing=%-5d(used=%-4d) orphan=%-3d plural_gap=%-3d"
                     % (l, len(loc[l]),
                        len(rows_same), sum(1 for r in rows_same if r[3] == "yes"),
                        sum(1 for r in missing if r[0] == l),
                        sum(1 for r in missing if r[0] == l and r[4] == "yes"),
                        sum(1 for r in orphan if r[0] == l),
                        sum(1 for r in plural_gap if r[0] == l)))
    lines.append("")
    lines.append("missing by (locale, default_file) [used only]:")
    cnt = Counter()
    used = Counter()
    for r in missing:
        cnt[(r[0], r[5])] += 1
        if r[4] == "yes":
            used[(r[0], r[5])] += 1
    for (l, f), n in sorted(cnt.items()):
        lines.append("  %-14s %-32s %4d  (used %d)" % (l, f, n, used[(l, f)]))
    lines.append("")
    lines.append("same-as-en by (locale, file) [used only]:")
    cnt2 = Counter()
    used2 = Counter()
    for r in same:
        cnt2[(r[0], r[5])] += 1
        if r[3] == "yes":
            used2[(r[0], r[5])] += 1
    for (l, f), n in sorted(cnt2.items()):
        lines.append("  %-14s %-32s %4d  (used %d)" % (l, f, n, used2[(l, f)]))
    lines.append("")
    lines.append("kotlin: cjk=%d ui_english=%d prose_candidates=%d xml_hardcoded=%d dupes=%d"
                 % (len(k[0]), len(k[1]), len(k[2]), len(xml_rows), len(dupes)))
    lines.append("kotlin rows by scope:")
    for (scope, n) in sorted(Counter([r[3] for r in k[0]] + [r[3] for r in k[1]]).items()):
        lines.append("  %s: %d" % (scope, n))
    lines.append("kotlin CJK by (scope, ui_ctx):")
    for key, n in sorted(Counter([(r[3], r[4] or "no-ui") for r in k[0]]).items()):
        lines.append("  %s: %d" % (str(key), n))
    lines.append("kotlin CJK top dirs (main, ui_ctx=ui):")
    for d, n in Counter(["/".join(r[0].split("/")[:8]) for r in k[0] if r[3] == "main" and r[4] == "ui"]).most_common(20):
        lines.append("  %-70s %d" % (d, n))
    lines.append("kotlin UI-EN top dirs (main):")
    for d, n in Counter(["/".join(r[0].split("/")[:8]) for r in k[1] if r[3] == "main"]).most_common(20):
        lines.append("  %-70s %d" % (d, n))
    summary = "\n".join(lines) + "\n"
    (out / "summary.txt").write_text(summary, encoding="utf-8")
    sys.stdout.write("\n" + summary)


if __name__ == "__main__":
    main()
