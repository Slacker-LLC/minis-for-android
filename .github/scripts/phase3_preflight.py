#!/usr/bin/env python3
from pathlib import Path

worker = Path(__file__).with_name("phase3_direct_runtime_cleanup.py")
text = worker.read_text(encoding="utf-8")

# The first successful worker pass already pushed the rootfs/network payload
# migration and physical minisd deletion. CI was then written through the
# GitHub connector because GITHUB_TOKEN cannot push workflow-file changes.
# Resume only from guards/docs/acceptance to keep the phased work idempotent.
old = '''    phase_payload_and_network_proxy()
    phase_remove_minisd()
    phase_ci()
    phase_guards()
    phase_docs()
    acceptance_and_cleanup()
'''
new = '''    phase_guards()
    phase_docs()
    acceptance_and_cleanup()
'''
if old not in text:
    raise SystemExit("cannot patch phase3 resume point")
text = text.replace(old, new, 1)

# The Actions token cannot delete workflow files. Leave the one-off workflow
# for the connector to delete after the tested code/docs cleanup commit lands.
workflow_cleanup = '        ".github/workflows/tmp-direct-runtime-inventory.yml",\n'
if workflow_cleanup not in text:
    raise SystemExit("cannot defer temporary workflow deletion")
text = text.replace(workflow_cleanup, "", 1)

# The worker embeds generated check_build_cleanup.py inside a raw triple-single
# quoted literal. Replace the one nested triple-quoted regex by line identity,
# not by its escape-heavy original spelling.
lines = text.splitlines()
regex_hits = 0
for index, line in enumerate(lines):
    if "LEGACY_IOS_RE = re.compile" in line:
        indent = line[: len(line) - len(line.lstrip())]
        lines[index] = indent + 'LEGACY_IOS_RE = re.compile(r"(?i)(?:^|[^A-Za-z0-9_.-])src[\\\\/]ios(?:[^A-Za-z0-9_.-]|$)")'
        regex_hits += 1
if regex_hits != 1:
    raise SystemExit(f"expected one LEGACY_IOS_RE line, found {regex_hits}")
text = "\n".join(lines) + "\n"

old = 'SELF_EXCLUDED_SCRIPTS = {"scripts/check_build_cleanup.py", "scripts/test_build_cleanup_guard.py"}'
new = '''SELF_EXCLUDED_SCRIPTS = {
            "scripts/check_build_cleanup.py",
            "scripts/test_build_cleanup_guard.py",
            "scripts/check-runtime-package-boundary.sh",
            "scripts/verify-runtime-payload.sh",
            "scripts/verify-root-network-proxy.sh",
        }'''
if old not in text:
    raise SystemExit("cannot patch generated build-cleanup exclusion set")
text = text.replace(old, new, 1)

old = '''    for line in grep.stdout.splitlines():
        path = line.split(":", 1)[0]
        if path.startswith(("src/android/", "scripts/", ".github/workflows/")):
            forbidden.append(line)
'''
new = '''    negative_guard_paths = {
        "scripts/check_build_cleanup.py",
        "scripts/test_build_cleanup_guard.py",
        "scripts/check-runtime-package-boundary.sh",
        "scripts/verify-runtime-payload.sh",
        "scripts/verify-root-network-proxy.sh",
    }
    for line in grep.stdout.splitlines():
        path = line.split(":", 1)[0]
        if path.startswith("src/android/") or path.startswith(".github/workflows/"):
            forbidden.append(line)
        elif path.startswith("scripts/") and path not in negative_guard_paths:
            forbidden.append(line)
'''
if old not in text:
    raise SystemExit("cannot patch final minisd grep classification")
text = text.replace(old, new, 1)

old = '''        ".github/scripts/phase3_direct_runtime_cleanup.py",
    ]:
'''
new = '''        ".github/scripts/phase3_direct_runtime_cleanup.py",
        ".github/scripts/phase3_preflight.py",
    ]:
'''
if old not in text:
    raise SystemExit("cannot add preflight to final scaffolding cleanup")
text = text.replace(old, new, 1)

old = '''    run("git", "diff", "--check")
    run("python3", "scripts/test_build_cleanup_guard.py")
'''
new = '''    run("git", "diff", "--check")
    run("bash", "-n", "scripts/build-ubuntu-rootfs.sh", "scripts/build-root-network-proxy-android.sh", "scripts/build-runtime-payload.sh", "scripts/test-build-ubuntu-rootfs-verification.sh", "scripts/test-runtime-payload-verification.sh", "scripts/verify-runtime-payload.sh", "scripts/verify-root-network-proxy.sh", "scripts/verify-android-release.sh", "scripts/check-runtime-package-boundary.sh")
    run("python3", "scripts/test_build_cleanup_guard.py")
'''
if old not in text:
    raise SystemExit("cannot add shell syntax gate")
text = text.replace(old, new, 1)

worker.write_text(text, encoding="utf-8", newline="\n")
print("phase3 worker resumed after CI handoff")
