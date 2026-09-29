# Model rules

The built-in provider catalog and declarative model capabilities live in `src/android/app/src/main/assets/model-rules.json`. The same bounded document may be refreshed remotely; it can only describe model metadata and picker filters. It cannot configure endpoints, credentials, headers, or executable behavior.

## Schema version 1

The top-level object contains:

- `schemaVersion`: integer; this client accepts version `1` only.
- `rules`: ordered model match rules.
- `staticModels`: ordered fallback catalogs keyed by `anthropic`, `gemini`, `openAI`, `openRouter`, `xAI`, `kimi`, and `codexOAuth`.
- `pickerFilters`: provider-list filtering policy. Version 1 supports `includePrefixes`, `excludeSuffixes`, and `excludeContains`. OpenAI also keeps its existing substring exclusions for audio/realtime/instruct/embedding model markers and `:ft-` fine-tune IDs; these were `contains` checks before extraction, so they are recorded in `excludeContains` as well as the suffix list.

A rule has an `id`, `match`, and `set` object. `match` accepts only `idExact`, `idPrefix`, `idSuffix`, and `idContains` string arrays, plus the `normalizeDots` and `stripPath` booleans. Values within one predicate are ORed; different predicates are ANDed. There is no regular-expression matching. With `stripPath`, matching uses only the part after the final `/`; with `normalizeDots`, both the model ID and predicate values treat `.` as `-`.

`set` may contain only `maxThinkingLevel`, `reasoningEffortValues`, `supportsReasoning`, `contextWindow`, `maxOutputTokens`, `inputModalities`, `outputModalities`, `rejectsTemperature`, `adaptiveThinking`, and `requiresThoughtSignature`. Numeric limits must be positive. Enum values are checked against the client enum.

## Evaluation order

Rules are walked in array order, **independently for each property**. The first matching rule that supplies a property wins; a later rule can supply a different property without overriding an earlier one. Put narrow exceptions before broader family rules. After model rules are applied, thinking-level resolution remains:

1. `supportsReasoning == false` yields `OFF`.
2. A model's declared `reasoningEffortValues` defines the selectable ceiling.
3. Otherwise use the first matching `maxThinkingLevel` rule.
4. A model ID with no applicable declaration uses `HIGH`.

GPT-5, Qwen, Hermes, and other currently known reasoning families have explicit ceiling rules so the default change affects only IDs with no known rule. The PR0 snapshot test is the regression guard for current catalog IDs.

The previous Anthropic helper parsed arbitrary numeric Claude versions and compared them to the 4.6 threshold. This schema intentionally has no range or regular-expression operator. The rule file therefore lists the currently supported/tested Claude 3.7, 4.6–4.8, and 5-series product-ID prefixes explicitly (rather than matching a loose `-5` substring that would misclassify Claude 3.5). Add a new model/version prefix to the rule data when Anthropic publishes a later family; no hidden numeric-version matcher remains in runtime code.

## Adding a model

1. Add or update its ordered fallback object under the matching `staticModels` provider key if the provider needs a built-in/failure fallback. Keep IDs and order exactly as the provider expects.
2. Add narrow capability rules before family-wide rules where metadata is not in the static object or also needs to apply to newly discovered API models. Specify an explicit ceiling when preserving the prior ceiling is required.
3. Update `pickerFilters` only when the provider's official `/models` list needs filtering; custom endpoints intentionally bypass the official OpenAI filter.
4. Add/adjust parser, snapshot, matching-order, and negative tests. Validate that the JSON stays below the size limit and that every rule is accepted by the production parser.
5. Update this document when the schema or provider maintenance guidance changes. A remote update is published through the repository's `main` asset URL; offline clients continue using cache or the bundled copy.

### Codex OAuth warning

The Codex OAuth fallback list is a callable-ID allow-list, not a speculative provider catalog. The Codex backend returns HTTP 400 for unsupported IDs, which appears in the UI as an empty reply. Keep the existing IDs and order intact; do not add models or restore previously removed IDs without a live Codex-token verification. The trailing image-only model remains after text-model enrichment because it uses a different route.

## Loading and updates

`ModelRulesProvider` is initialized from the application context. Resolution uses an in-memory document, then a validated private disk cache, then the bundled asset. The default remote URL is the raw repository asset on `main`; the provider also exposes a settings-ready URL override. Remote refresh runs on a daemon thread and does not block startup. Cache freshness is 24 hours.

Remote requests must pass `ProviderTransportPolicy.requireHttps` and use `protectedHttpsBuilder`; HTTP, HTTPS-to-HTTP redirects, and other URL schemes are rejected. Documents larger than 256 KiB are discarded, including chunked responses whose declared length is absent. Valid cache writes use a same-directory temporary file and atomic rename before the new document becomes active.

## Failure behavior

- A malformed file, unsupported schema version, unknown top-level property, invalid static-model payload, oversized response, non-HTTPS URL, failed request, or cache-write failure never replaces the active document.
- An unknown property in a rule or an unknown `maxThinkingLevel` enum discards only that rule and logs a warning; other valid rules remain available.
- A malformed bundled asset logs an error and degrades to an empty ruleset/catalog rather than crashing startup. Provider constructors retain their existing default-model fallback.
- With no valid remote/cache update, the last validated cache or bundled asset remains active. No network call is required for offline model browsing or thinking-level resolution.

## Request-body boundaries

Rules drive the listed capability metadata. They do not construct provider requests. The exact GPT-6 Astra Responses routing/body contract remains in `OpenAIProvider`: the version-1 fields cannot express that endpoint-specific wire shape, and changing it would risk request compatibility. Continue testing that contract through `Gpt6AstraTest`; do not infer other requests from Astra metadata.
