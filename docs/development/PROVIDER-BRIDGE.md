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

## More services and more agents

- **Known services** are exported under the variable their own tools read (the names the models.dev catalogue lists, which OpenCode
  loads providers by): `ZHIPU_API_KEY`, `DASHSCOPE_API_KEY`, `ARK_API_KEY`, `XIAOMI_API_KEY`, `STEPFUN_API_KEY`, `SILICONFLOW_API_KEY`,
  `MINIMAX_API_KEY`, `GROQ_API_KEY`, `CEREBRAS_API_KEY`, `TOGETHER_API_KEY`, `FIREWORKS_API_KEY`, `MISTRAL_API_KEY`... (the table is
  `ProviderBridge.SERVICE_VARIABLES`, matched on the exact host or a subdomain). A Kimi Code API key goes to `KIMI_API_KEY` +
  `KIMI_BASE_URL` (Kimi CLI).
- **Providers with their own base URL** (relays, the services in "Add provider") are also written as custom endpoints, one entry per
  provider with the models the app lists for it. Each provider's key is exported as `MINIS_KEY_<NAME>`; the files only name that
  variable, they never hold a key:
  - pi: `~/.pi/agent/models.json`, `providers.minis-<name>` (`baseUrl`, `api`, `apiKey: "$MINIS_KEY_<NAME>"`, `models`).
  - Command Code: `~/.commandcode/providers.json`, `provider.minis-<name>` (`baseURL`, `api`, `apiKey`, `models`).
  - OpenCode: the `OPENCODE_CONFIG_CONTENT` variable (inline config, merged by OpenCode), `provider.minis-<name>`, `apiKey: "{env:MINIS_KEY_<NAME>}"`.
  Entries are told apart by the `minis-` id prefix; anything else in those files is kept, a file that is not valid JSON is never
  rewritten, and switching one-tap authorize off removes our entries (and the file if nothing else was in it).
  Sources: pi `docs/models.md`, commandcode.ai/docs/byok, opencode.ai/docs/providers and /docs/cli.

## Not done

- Codex's `config.toml` (custom model providers), Claude Code's settings file and Aider/Crush configs are not written; those tools
  get the plain variables above only.
- Providers whose base URL is plain http (other than a loopback server) are never exported.
- The user terminal's login shells read the file only when started; a running terminal needs `source ~/.profile`.
