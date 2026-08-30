# Documentation

English is the primary language for current project documentation. Translations are secondary and must not define behavior that differs from the English source.

## Current documentation

| Document | Purpose |
|---|---|
| [`../README.md`](../README.md) | project overview and architecture |
| [`../BUILDING.md`](../BUILDING.md) | single canonical build and release guide |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | current engineering state and active risk areas |
| [`EXECUTION-ENVIRONMENT.md`](EXECUTION-ENVIRONMENT.md) | rooted-device Ubuntu/minisd execution model |
| [`SECURITY.md`](SECURITY.md) | security boundaries and hardening rules |
| [`BUILD-CLEANUP-AUDIT.md`](BUILD-CLEANUP-AUDIT.md) | Issue #53 build-path classification and allowlist decisions |
| [`VOICE.md`](VOICE.md) | speech recognition, voice provider, TTS, and pet/chat voice paths |
| [`../PROVENANCE.md`](../PROVENANCE.md) | source lineage and legal attribution |
| [`../THIRD_PARTY_LICENSES.md`](../THIRD_PARTY_LICENSES.md) | third-party license inventory |

Chinese translations: [`../README.zh-CN.md`](../README.zh-CN.md), [`../BUILDING.zh-CN.md`](../BUILDING.zh-CN.md).

## Specifications

- [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md)
- [`specs/debug-server-api.md`](specs/debug-server-api.md)

## Historical archive

Historical implementation notes that remain useful are isolated under [`archive/`](archive/) and are non-authoritative. They exist to explain old repository references, not to define current behavior.

## Documentation authority

```text
source code and tests
  > current architecture/security documents
  > README and changelog
  > archived historical material
```

Current documents describe Minis for Android directly. Source lineage is handled separately in provenance/legal documentation.
