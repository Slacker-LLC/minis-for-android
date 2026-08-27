# Documentation

English is the primary language for current project documentation. Translations are secondary and must not define behavior that differs from the English source.

## Start here

| Document | Purpose |
|---|---|
| [`../README.md`](../README.md) | project overview and architecture |
| [`../BUILDING.md`](../BUILDING.md) | authoritative build and release guide |
| [`../UPSTREAM.md`](../UPSTREAM.md) | OpenMinis provenance and synchronization policy |
| [`../CHANGELOG.md`](../CHANGELOG.md) | current project change history |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | current engineering state and active risk areas |
| [`EXECUTION-ENVIRONMENT.md`](EXECUTION-ENVIRONMENT.md) | rooted-device Ubuntu/minisd execution model |
| [`SECURITY.md`](SECURITY.md) | security boundaries and hardening rules |
| [`VOICE.md`](VOICE.md) | speech recognition, voice provider, TTS, and pet/chat voice paths |

Chinese translations:

- [`../README.zh-CN.md`](../README.zh-CN.md)
- [`../BUILDING.zh-CN.md`](../BUILDING.zh-CN.md)

## Specifications

| Document | Purpose |
|---|---|
| [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md) | `minis://` URL scheme |
| [`specs/debug-server-api.md`](specs/debug-server-api.md) | DebugServer API reference; runtime discovery/source remain authoritative |

## Archived material

`docs/archive/` contains historical design material that may describe removed implementations, experiments, other platforms, or one-off device evaluations.

Archived documents are intentionally non-authoritative and may retain their original language.

Do not use archived PRoot, Alpine, Web Remote, Cloudflare Tunnel, or iOS-specific design notes as the implementation contract for the current Android project.

## Documentation authority

When sources disagree:

```text
source code and tests
  > current architecture/security documents
  > README and changelog
  > archived material
```

Current documentation should describe the project as **Minis for Android**, an independent Android project built on upstream OpenMinis. It should not describe this repository as an upstream release mirror or as the old PRoot/Web Remote experiment.
