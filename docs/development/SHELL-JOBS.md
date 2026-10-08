# Background shell jobs and parallel tool calls

Two separate things make a turn with several commands or lookups faster. Both follow the shape of Codex's
unified exec (independent command sessions) and tool scheduler (read-only calls share a lock, everything else
is exclusive).

## Background shell jobs

`shell_execute` runs in **one persistent bash per session**, one command at a time: `cd`/`export` carry over,
and a timeout kills that shell together with its state. For long or parallel work the agent sets
`background=true`:

- The command runs as a **job**: its own guest shell process (same chroot, App UID, workspace and
  environment), independent of the persistent shell and of every other job. The call returns at once with a
  `job_id` and whatever the job printed in its first moments.
- A job does not see the persistent shell's `cd`/`export` state. Its stdin is a named pipe in the session's `/tmp`:
  the agent writes to it with `job_input {job_id, input, eof}` (`eof=true` closes it, which is how a program that
  reads until end-of-file is finished). The wrapper falls back to no stdin if the pipe cannot be created, so a job
  never fails to start because of it.
- The agent reads jobs with the **existing** `job_output` / `job_list` / `job_kill` tools. They are backed by
  `JobRegistry` (so the `agent.jobs.*` RPC sees them too). `job_output` takes an `offset` and
  answers `[next_offset: N]` plus the failure reason in its status trailer, so a long job is read
  incrementally; `wait=true` blocks until the job ends (up to `timeout_ms`).
- Output is a bounded tail (the newest 200,000 characters). Positions count from the first character, so an
  offset stays valid after the head is dropped; a reader that fell behind is told how much it missed.
- Limits: 6 running jobs per session, 16 in total (a refusal that says so, not a silent queue); a deadline per
  job (default 2 hours, at most 24 hours).
- Lifetime: a job ends with its session and on a runtime-wide stop (rootfs or mount changes). A user Stop of
  the *turn* leaves jobs running, that is what a background job is for. Jobs live in memory: when the app
  process dies they die with it.
- A scheduled read-only run may not start a job.

Code: `runtime/ShellJobs.kt` (process and lifetime), `runtime/JobStdin.kt` (the input pipe), `ExecutionCoordinator.newJobProcess` (the guest shell),
`tools/runtime/ShellBackground.kt` (the tool reply), `tools/JobRegistry.kt` / `JobTools.kt` (state and tools).

## Parallel tool calls

Within one model turn, calls to **read-only** tools run at the same time; any other call waits for every call
before it and then runs alone. `ToolRound.run` prepares each call in order (gates, argument repair, loop
detector, preflight), starts read-only ones without waiting, and folds the outcomes in under a lock; results go
back to the model in **call order** whatever order they finish in. A call that is refused or blocked never holds
anything up.

`tools/runtime/ToolConcurrency.kt` is a whitelist: file reads and searches, web fetch/search, and device
lookups. Writes, edits, the shell, the browser, UI automation, questions to the user and any tool nobody
classified are exclusive and keep their old one-at-a-time, in-order behaviour. The shell is deliberately not in
the list: its persistent bash makes the order of commands meaningful, and starting a guest shell per call costs
more than the commands it would save. For parallel shell work use `background=true`.
