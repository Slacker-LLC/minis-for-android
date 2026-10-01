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

## Runs across restarts, cards, time budget, progress, backup

- **Persistence and resume.** The last 30 delegations are mirrored to app-private preferences (results and
  briefs bounded). A run that was queued or running when the app was killed loads as `interrupted`;
  `action=resume` (or the card's Resume button) restarts it — in the child it already has, with a notice that
  live state (browser tabs, shell processes) is gone, or with its original brief if it never got a child.
  Resume is scoped to the calling conversation and refused for anything that is not interrupted.
- **Cards.** A `subagent` tool block that started a run opens a card: agent, model, job id, live status and
  elapsed time (from the registry, falling back to what the call returned), the task, the result once it ends,
  Stop / Steer while it runs, Resume when interrupted, and Open session to watch the child. The controls use the
  same tool path as the model, so they can only act on this conversation's own runs.
- **Time budget.** When `max_minutes` runs out the child is stopped and given a 90 s grace, tools off, to answer
  with what it has; that message becomes the result (`status=timeout`), falling back to the partial output.
- **Progress reports.** `progress_report` = `none` (default) | `frequent` (~15 s) | `moderate` (~60 s), background
  runs only: while it runs, the current tool and the tail of the latest message are posted into the delegating
  conversation whenever they changed. Each report costs the delegating model a turn, which the tool description says.
- **Backup.** The user's custom agents are written to `data/sub_agents.jsonl` inside the Providers category (like
  custom thinking rules, so the category set is unchanged) and merged on restore: a new id is added unless its
  name clashes with a local agent, a known id is replaced only by a newer copy, the built-in is never touched.
  A record from the upstream app restores too; its model-group pin is dropped (such an agent restores as Auto).

## Known limits

Jobs are mirrored for the last 30 only; a callback is held until the delegating conversation has settled (up to ~10
minutes per attempt, 20 attempts) and is dropped if that conversation is deleted. A real end-to-end run against a live
model has not been done on a device yet: the runtime is covered against a fake port, the card and Settings page were
checked on an emulator with seeded data.
