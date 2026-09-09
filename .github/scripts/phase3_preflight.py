#!/usr/bin/env python3
from pathlib import Path

worker = Path(__file__).with_name("phase3_direct_runtime_cleanup.py")
text = worker.read_text(encoding="utf-8")

# Production migration, guards, and active docs/contracts are already committed.
# The one-off worker must now do exactly one thing: run the full acceptance
# suite against the final tree with the phase scripts removed in its worktree.
full = '''    phase_payload_and_network_proxy()
    phase_remove_minisd()
    phase_ci()
    phase_guards()
    phase_docs()
    acceptance_and_cleanup()
'''
resumed = '''    phase_guards()
    phase_docs()
    acceptance_and_cleanup()
'''
acceptance = '''    acceptance_and_cleanup()
'''
if full in text:
    text = text.replace(full, acceptance, 1)
elif resumed in text:
    text = text.replace(resumed, acceptance, 1)
elif acceptance not in text:
    raise SystemExit("phase3 main is not in a recognized migration state")

# Workflow deletion is connector-owned because GITHUB_TOKEN lacks workflow
# write permission. Do not make acceptance depend on editing workflow YAML.
workflow_line = '        ".github/workflows/tmp-direct-runtime-inventory.yml",\n'
if workflow_line in text:
    text = text.replace(workflow_line, "", 1)

# Contents-API edits can leave the Android wrapper without an executable bit in
# the branch tree. Acceptance invokes it directly from src/android, so restore
# only the checkout-local mode; the helper itself is deleted after acceptance.
gradlew = Path("src/android/gradlew")
if not gradlew.is_file():
    raise SystemExit("Android gradlew is missing")
gradlew.chmod(gradlew.stat().st_mode | 0o111)

worker.write_text(text, encoding="utf-8", newline="\n")
print("phase3 worker ready for acceptance only")
