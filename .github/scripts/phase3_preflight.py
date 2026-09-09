#!/usr/bin/env python3
from pathlib import Path

worker = Path(__file__).with_name("phase3_direct_runtime_cleanup.py")
text = worker.read_text(encoding="utf-8")

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
print("phase3 worker acceptance hardened")
