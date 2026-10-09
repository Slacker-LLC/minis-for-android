# The agent's real terminals

`shell_execute` runs on pipes: no screen, no cursor, `TERM=dumb`. A program that needs a terminal (a coding agent such
as pi, Claude Code or Codex; an editor; a REPL) refuses to start there. The `terminal` tool gives the agent what a
person has: a pty with a screen it can read and a keyboard it can press. Plain commands stay on `shell_execute`
(cheaper, with an exit code); a one-shot task for another agent is better run non-interactively
(`pi -p`, `claude -p`, `codex exec`) through `shell_execute background=true`.

## Shape

- One tool, `terminal`, with `action`: `open`, `send`, `read`, `list`, `close`. One schema keeps every request small.
- `open` starts the same pty-backed login shell the Terminal page uses (`TerminalSession`), bound to the chat's workspace,
  and optionally runs `command` at once. Its output feeds a headless `TerminalEmulator`; the agent reads the *rendered*
  screen (`screenText()`), not escape codes.
- `send` types `text` (a newline is Enter; with bracketed paste on, a multi-line text is one paste and only its trailing
  newline presses Enter) and presses `keys` (`Enter`, `Escape`, `Ctrl-C`, `Up`, `Alt-b`, `F5`... see `AgentKeyEncoder`).
- Every `send`/`read` waits, then returns the screen. It stops at the first of: the screen was quiet for 500 ms,
  `wait_for` matched, the program reported **done / blocked / error** through OSC 7501, the process exited, or `wait_ms`
  (default 2 s, at most 30 s) ran out. A program that reports its status (pi does) therefore needs no polling.
- A `read` of an unchanged screen answers `[screen unchanged]` instead of repeating it.
- Privacy Mode masks the user's environment-variable values on the screen text, as it does for shell output.

## Limits and lifetime

- 3 terminals per chat, 6 in total; a refusal says so. Sizes are clamped (40-200 columns, 10-60 rows).
- A terminal ends with its chat session, on a runtime stop, on `close`, or after an hour unused (checked when another is
  opened).

## Permissions

The local agent may use it. A remote MCP caller needs the confirmation gate, like `linux.shell`. A scheduled read-only run
may not use it (it is code execution).

## Not done

- The Terminal page does not yet show the agent's terminals; the user cannot take one over from there.
- The user's environment variables are not exported into these terminals (they are for `shell_execute`).
