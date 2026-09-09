#!/usr/bin/env python3
from pathlib import Path

worker = Path(__file__).with_name("phase3_direct_runtime_cleanup.py")
text = worker.read_text(encoding="utf-8")

# Phase 3A-3E production work is already on the remote branch. Resume only
# guards/docs/acceptance. Earlier runs may already have committed this patched
# worker, so treat both states as valid.
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
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("phase3 resume point is neither original nor resumed state")

# GITHUB_TOKEN cannot update workflow files. Earlier runs may already have
# committed the worker without this cleanup entry; either state is acceptable.
workflow_line = '        ".github/workflows/tmp-direct-runtime-inventory.yml",\n'
if workflow_line in text:
    text = text.replace(workflow_line, "", 1)

worker.write_text(text, encoding="utf-8", newline="\n")
print("phase3 worker ready at guards/docs/acceptance")
