#!/usr/bin/env python3
from pathlib import Path

worker = Path(__file__).with_name("phase3_direct_runtime_cleanup.py")
text = worker.read_text(encoding="utf-8")

# Phase 3A-3E production work is already on the remote branch:
# - rootfs-only payload + single-purpose Root network proxy
# - physical native minisd removal
# - canonical CI migration (written through the GitHub connector)
# Resume only guards/docs/acceptance so the one-off worker is idempotent.
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
    raise SystemExit("phase3 resume point no longer matches worker")
text = text.replace(old, new, 1)

# GITHUB_TOKEN may update normal repository content but GitHub rejects workflow
# file changes without workflows permission. The connector will delete the
# temporary workflow only after the worker has finished all tests.
workflow_line = '        ".github/workflows/tmp-direct-runtime-inventory.yml",\n'
if workflow_line not in text:
    raise SystemExit("temporary workflow cleanup entry no longer matches worker")
text = text.replace(workflow_line, "", 1)

worker.write_text(text, encoding="utf-8", newline="\n")
print("phase3 worker resumed at guards/docs/acceptance")
