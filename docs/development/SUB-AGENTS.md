# Sub agents

The assistant can delegate a self-contained task to a **sub agent**: a child session with its own
context and tool loop, running concurrently with the conversation that started it. The user defines
the roster in Settings › Agent › Sub Agents.

Adapted from the upstream app's 1.14 release (see `PROVENANCE.md`). This page records what differs here and why.

## Tool

`subagent` — one tool, `action` = `delegate` (default) | `status` | `steer` | `cancel`. It keeps the name of the
earlier single-shot tool (the upstream app renamed its tool to `subagent_task`; here the existing name stays so
recorded transcripts, the permission policy, the UI grouping and the prompts keep working).

- `delegate` returns at once with `status=running` and a `job_id` (`wait=false`, the default). The result
  arrives later as a **new message** in the delegating conversation, prefixed
  `[Background task finished …]`. `wait=true` blocks the call for the result; stopping the waiting turn
  stops the sub agent.
- At most **3** run at once; up to **10** more wait in a queue (`status=queued`) and start as slots free.
  Beyond that the call is refused (`queue_full`).
- `steer` queues a correction into a running child (read at its next turn). `cancel` stops it; the partial
  result is still posted back. `status` lists the conversation's jobs.
- Every lookup is scoped to the calling conversation: one chat cannot see, steer or stop another chat's runs.
- Delegation is **one level deep**: a sub agent's own session is not offered the tool (and the runtime refuses
  it there as a second line of defence).

The tool's `agent` argument is an enum rebuilt from the live roster every turn, so a name the model emits
always resolves. The roster and a one-line tool bullet are added to the system prompt as a per-turn
fragment (after skills and MCP), not as an editable prompt module.

## Roster

Each definition has a name (the wire identifier, never localized), a short description (the only text that
costs conversation tokens), optional instructions (sent only to the child), an optional **pinned model entry**
and an optional reasoning level. `SubAgentRoster.normalize` runs on every load and save: the built-in
"General Sub Agent" always exists and cannot be deleted, names are unique (case- and accent-insensitive),
fields are clamped, the count is bounded (10). Stored in the `minis_sub_agents` preferences.

Model selection for an agent that is not pinned (Auto): the delegating model passes `model_choice` —
`same_as_me` (default, the conversation's own model), `default_model` (Main slot) or `sub_model` (Light slot).
A pinned model that is gone or has no credential is refused, never silently replaced.

## How it runs

`SubAgentRuntime` (pure orchestration) talks to the app through `SubAgentPort`; `AppSubAgentPort` drives
ordinary sessions through `AgentRunner`, the loop chat and scheduled tasks use. A child therefore keeps its
own session workspace, the same tool set and the same permission gates; its source is
`ChatSessionEntity.SOURCE_SUB_AGENT`. The result callback waits for the delegating conversation to settle
before posting (a headless prompt is refused mid-turn), instead of queuing through the composer, which would
also take whatever the user has attached there.

## Relation to the other delegation mechanisms

- The older single-shot `subagent` behaviour is what the tool does with the Settings switch **off**; with it on
  (the default) the same tool name carries the roster schema above. A call shaped for the old schema (`prompt`
  instead of `task`) is still accepted. A sub agent's own session is offered no delegation tool in either mode.
- **Bot delegation** (`delegate_bot`, `docs/contracts/08-BOT-COORDINATION.md`) is a separate, persistent
  mechanism between named Bots and is untouched. Sub agents are ephemeral workers of one conversation.
- The upstream app pins a sub agent to a model *group*; this app has fixed model slots instead, so the pin is a
  model entry.

## Not implemented (yet)

The upstream app also has: resuming runs lost to a process kill (jobs here live in memory only), progress reports
and a wrap-up turn before the time budget, chat cards with live status and Stop / steer controls, and
restoring the roster from a backup. The tool block currently shows the tool's JSON reply.
