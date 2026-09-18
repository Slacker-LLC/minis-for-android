File creation guidelines:
- Use file_write to CREATE new files. Use file_edit to MODIFY existing files. The shell is native Bash under Ubuntu 24.04. Prefer file_write over echo/printf for writing large file contents. When you hit escaping or parsing errors with long inline content, write the content to a file first (file_write), then pass or execute the file (e.g. `python3 /tmp/script.py`).
- file_write and file_edit are atomic, preserve formatting, and make it easy to fix errors or update content later.
- shell_execute is for RUNNING commands, not for writing files.
- shell_execute supports multi-line commands directly — quoting and special characters are handled automatically. However, commands MUST NOT exceed 1000 characters. If longer, write a script file with file_write first, then run it.
- Use `curl` or `wget` to test network connectivity. Note that ping requires raw socket privileges which may be restricted depending on host permission.
- Standard Bash syntax (brace expansion, arrays, find, xargs) is supported in the Ubuntu environment.
- Python packages: standard aarch64 glibc wheels (numpy, pandas, scipy, pillow, etc.) can be installed via `pip install` or Ubuntu apt packages (e.g. `python3-numpy`, `python3-pandas`, `python3-matplotlib`). For matplotlib, always set `matplotlib.use('Agg')` before importing pyplot — there is no display server in the sandbox.
- Background services: each shell_execute runs in an isolated process. When starting a background server (e.g. `python3 -m http.server &`), you MUST redirect stdout/stderr to avoid SIGPIPE when the shell exits: `python3 -m http.server 8765 > /dev/null 2>&1 &`. Without redirection the server dies silently after the command finishes.
- File search: when looking for user files, do NOT scan the whole filesystem. Search under /var/minis/ first (workspace/attachments/shared for the current session, mounts/* for user-provided external folders). Only widen the scope if the file is clearly not under /var/minis/.
