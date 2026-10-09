# Provider keys for the coding agents in the sandbox

pi, Claude Code and Codex installed in the Ubuntu sandbox would each need their own login. The one-tap authorize switch (Settings >
System & permissions) now also hands them the **API-key providers added in the app**, so they work without logging in again.

## What is shared

For each service, the first enabled provider that has an API key:

| App provider | Variable | Read by |
|---|---|---|
| Anthropic | `ANTHROPIC_API_KEY` (+ `ANTHROPIC_BASE_URL` for a relay, as its origin) | pi, Claude Code |
| OpenAI (official endpoint) | `OPENAI_API_KEY` | pi, Codex |
| OpenAI-compatible relay (https) | `OPENAI_API_KEY` + `OPENAI_BASE_URL`, only when there is no official OpenAI provider | Codex |
| Gemini | `GEMINI_API_KEY` | pi |
| OpenRouter | `OPENROUTER_API_KEY` | pi |
| xAI | `XAI_API_KEY` | pi |
| DeepSeek (host `api.deepseek.com`) | `DEEPSEEK_API_KEY` | pi |
| Moonshot (host `api.moonshot.cn` / `.ai`) | `MOONSHOT_API_KEY` | pi |

The variable names come from pi's own list (`packages/coding-agent/docs/providers.md`, lines 33-65 of the 1.1.0 source) and each
tool's documented API-key variables. A relay's base URL always travels with its key, so the two belong together.

Not shared, on purpose: providers signed in with OAuth (ChatGPT, Claude, Kimi Code...). Their tokens are rotated by whoever
refreshes them, so two programs holding one would invalidate each other. Cleartext (http) relays are never exported.

## How

- `~/.minis/agent-env.sh` (mode 600) holds the `export` lines; a managed block in `~/.profile` and `~/.bashrc` sources it, so the
  Terminal and the agent's terminals (login shells) have the variables. Text outside the block is never touched.
- The agent's own shells (`shell_execute`, background jobs) get the same variables through the environment injection; a variable the
  user set by hand in Settings > Environment variables wins.
- The files and the injected values follow the providers: a provider added, disabled or re-keyed rewrites them (debounced).
- Privacy Mode masks these values in anything the model reads, like the user's own environment variables.
- Switching one-tap authorize off removes the file and the managed blocks.

## Not done

- Providers with no standard variable (other relays, custom hosts) are not exported, nor written into pi's `models.json`,
  Claude Code's settings or Codex's `config.toml`.
- The user terminal's login shells read the file only when started; a running terminal needs `source ~/.profile`.
