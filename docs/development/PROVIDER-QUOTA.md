# Provider balance and usage

The provider page shows what is left on the account when the service has an endpoint for it. Nothing is fetched in
the background: the page asks when it is opened (cached 5 minutes, refresh button), and a request goes only to the
service's own host - the host is matched exactly, so a look-alike base URL never receives the key.

| Service | Detected by | Request | Reads |
|---|---|---|---|
| DeepSeek | host `api.deepseek.com` | `GET /user/balance` | `balance_infos[]` (`currency`, `total_balance`, `granted_balance`, `topped_up_balance`), `is_available` - <https://api-docs.deepseek.com/api/get-user-balance> |
| Moonshot | host `api.moonshot.cn` / `.ai` | `GET /v1/users/me/balance` | `data.available_balance` (CNY), `voucher_balance`, `cash_balance` - <https://platform.kimi.com/docs/api/balance.md> |
| SiliconFlow | host `api.siliconflow.cn` / `.com` | `GET /v1/user/info` | `data.totalBalance`, `balance`, `chargeBalance` (strings) - <https://docs.siliconflow.cn/cn/api-reference/userinfo/get-user-info> |
| OpenRouter | provider type | `GET /api/v1/credits` (management key), else `GET /api/v1/key` | `data.total_credits - total_usage`; or the key's `limit_remaining` - <https://openrouter.ai/docs/api/api-reference/credits/get-credits>, <https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key> |
| ChatGPT sign-in | OpenAI provider with OAuth (not a pasted bearer) | `GET https://chatgpt.com/backend-api/wham/usage` with the access token and `ChatGPT-Account-Id` | `plan_type`, `rate_limit.primary_window` / `secondary_window` (`used_percent`, `limit_window_seconds`, `reset_at`) - Codex's own client, `codex-rs/backend-client/src/client/rate_limit_resets.rs:126` and `codex-rs/codex-backend-openapi-models` |

Levels: **empty** when the service says calls will fail, a usage window is spent, or every balance is zero; **low** when a
balance is under 5 (CNY) / 1 (other currencies) or a window is at 90 % or more. A currency with nothing left does not hide
another that still has money.

Not done: relay services (New API / Sub2API) and Claude subscriptions have no documented endpoint checked here; pills in
the model picker, a warning dot beside the model name in a chat, and a line in the composer are not built.
