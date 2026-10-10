# Provider balance and usage

What is **left** on an account, shown wherever the service has an endpoint for it, and readable by the agent. Everything is
phrased as what remains (balance left, share of a window left), never as what was spent. Nothing is fetched in the background: a
page that shows it asks when it is on screen (cached 5 minutes), and a request goes only to the service's own host.

## Sources

| Service | Detected by | Request | Reads |
|---|---|---|---|
| DeepSeek | host `api.deepseek.com` | `GET /user/balance` | `balance_infos[]` (`currency`, `total_balance`, `granted_balance`, `topped_up_balance`), `is_available` - <https://api-docs.deepseek.com/api/get-user-balance> |
| Moonshot | host `api.moonshot.cn` / `.ai` | `GET /v1/users/me/balance` | `data.available_balance` (CNY), `voucher_balance`, `cash_balance` - <https://platform.kimi.com/docs/api/balance.md> |
| SiliconFlow | host `api.siliconflow.cn` / `.com` | `GET /v1/user/info` | `data.totalBalance`, `balance`, `chargeBalance` (strings) - <https://docs.siliconflow.cn/cn/api-reference/userinfo/get-user-info> |
| OpenRouter | provider type | `GET /api/v1/credits` (management key), else `GET /api/v1/key` | `data.total_credits - total_usage`; or the key's `limit_remaining` - <https://openrouter.ai/docs/api/api-reference/credits/get-credits>, <https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key> |
| ChatGPT sign-in | OpenAI provider with OAuth (not a pasted bearer) | `GET https://chatgpt.com/backend-api/wham/usage` + `ChatGPT-Account-Id` | `plan_type`, `rate_limit.primary_window` / `secondary_window` (`used_percent`, `limit_window_seconds`, `reset_at`) - Codex's own client: `codex-rs/backend-client/src/client/rate_limit_resets.rs:126`, `codex-rs/codex-backend-openapi-models` |
| Claude sign-in | Anthropic provider with OAuth | `GET https://api.anthropic.com/api/oauth/usage` + `anthropic-beta: oauth-2025-04-20` | `five_hour`, `seven_day`, `seven_day_sonnet`, `seven_day_overage_included`, each `{utilization, resets_at}` - Claude Code calls this path itself (seen in its own client, 2.1.283); shape from Sub2API `backend/internal/repository/claude_usage_service.go:16,57-60` and `backend/internal/service/account_usage_service.go:256-273` |
| Kimi Code sign-in | provider type Kimi Code | `GET https://api.kimi.com/coding/v1/usages` | `usage` (weekly) and `limits[]` (`detail` / `window` / `name`; `limit`, `used` or `remaining`, `reset_at` / `reset_in`) - `kimi_cli/ui/shell/usage.py:84-157` in MoonshotAI/kimi-cli @ 9ab1286 |
| Relay, Sub2API layout | any other **https** custom base of an OpenAI/Anthropic-type provider with an API key | `GET <origin>/v1/usage` | `isValid`, `planName`, `remaining` + `unit`, `rate_limits[]` (`window`, `limit`, `used`, `reset_at`) - Sub2API `backend/internal/handler/gateway_handler.go` `usageQuotaLimited` / `usageUnrestricted`, route `routes/gateway.go:210` |
| Relay, New API / One API layout | same, when the first layout answers nothing | `GET <origin>/v1/dashboard/billing/subscription` and `.../usage` | `hard_limit_usd` (the token's whole quota; `100000000` = unlimited) and `total_usage` (hundredths); remaining = limit - usage/100. The site picks the unit (USD, CNY or tokens), so none is claimed - New API `router/dashboard.go`, `controller/billing.go` |
| Xiaomi MiMo | hosts `api.xiaomimimo.com`, `token-plan-{cn,sgp,ams}.xiaomimimo.com` | none: no balance API is documented | the page links to <https://platform.xiaomimimo.com> |

The host is matched exactly, never by substring. A relay is only ever asked on its own origin, which its chat requests already
reach with the same key; a relay that answers neither layout shows nothing (not an error); cleartext (http) relays are not asked.

## Where it shows

- Provider page: balances, subscription windows (left %, resets in), refresh row; the console link for Xiaomi.
- Model picker: a small pill beside each provider's name (the biggest balance, or the tightest window).
- Composer: a dot beside the model chip when the active provider is low (amber) or out (red); nothing while all is well.
- Agent tool `provider_quota` (local agent only; not visible to remote MCP callers): one line per provider, `refresh=true` to read
  again, `provider` to pick one. Keys never appear in it.

Levels: **empty** when the service says calls will fail, a window is spent, or every balance is zero; **low** when a balance is
under 5 (CNY) / 1 (other named currencies) or a window is at 90 % or more used. A balance in an unnamed unit (a relay's own
credits) is only ever empty, never low. A currency with nothing left does not hide another that still has money.

## Not done

- The line in the composer that alternates context usage and quota.
- Providers with no endpoint above (other than the console link for Xiaomi).

## Command Code

An API-key provider on `api.commandcode.ai` (an OpenAI-compatible provider with that base URL) is read with `GET https://api.commandcode.ai/alpha/billing/credits`
and `Authorization: Bearer <key>`: the call behind the CLI's `/usage` (found in `command-code` 1.79.2, `dist/cli.mjs`: `fetchUsageCredits`,
`projectUsageView`). The docs name no such endpoint, so this follows that source and has not been run against a real account.
`credits.monthlyCredits + purchasedCredits + freeCredits` is what is left, in dollars; `credits.windowLimits.{fiveHour,weekly}` = `{used, cap, resetAt in ms}`
is shown while `limited` is true. Organisation accounts pass an `orgId` (from `/alpha/whoami`); this app asks without one, i.e. the personal account.
The same key is exported to the sandbox as `COMMAND_CODE_API_KEY`, the variable the CLI reads.
