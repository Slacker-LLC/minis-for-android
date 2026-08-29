# Documentation

English is the primary language for current project documentation. Translations are secondary and must not define behavior that differs from the English source.

## Start here

| Document | Purpose |
|---|---|
| [`../README.md`](../README.md) | project overview and architecture |
| [`../BUILDING.md`](../BUILDING.md) | single canonical build and release guide |
| [`../UPSTREAM.md`](../UPSTREAM.md) | OpenMinis provenance and synchronization policy |
| [`../CHANGELOG.md`](../CHANGELOG.md) | current project change history |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | current engineering state and active risk areas |
| [`EXECUTION-ENVIRONMENT.md`](EXECUTION-ENVIRONMENT.md) | rooted-device Ubuntu/minisd execution model |
| [`SECURITY.md`](SECURITY.md) | security boundaries and hardening rules |
| [`BUILD-CLEANUP-AUDIT.md`](BUILD-CLEANUP-AUDIT.md) | Issue #53 build-path classification and allowlist decisions |
| [`VOICE.md`](VOICE.md) | speech recognition, voice provider, TTS, and pet/chat voice paths |

Chinese translations:

- [`../README.zh-CN.md`](../README.zh-CN.md)
- [`../BUILDING.zh-CN.md`](../BUILDING.zh-CN.md)

## Specifications

| Document | Purpose |
|---|---|
| [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md) | `minis://` URL scheme |
| [`specs/debug-server-api.md`](specs/debug-server-api.md) | DebugServer API reference; runtime discovery/source remain authoritative |

## Documentation authority

When sources disagree:

```text
source code and tests
  > current architecture/security documents
  > README and changelog
```

Historical implementation notes remain available in Git history rather than in the current documentation tree. Current documents should describe the project as **Minis for Android**, an independent Android project built on upstream OpenMinis, not as an upstream release mirror or as an earlier PRoot/Web Remote experiment.
