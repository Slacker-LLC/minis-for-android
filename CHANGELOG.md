# Changelog

This changelog tracks **Minis for Android** as an independently maintained Android project. Legal source lineage is documented separately in [PROVENANCE.md](PROVENANCE.md).

## Unreleased

### Application identity: llc.slacker.eta — 2026-09-18

- `applicationId` moves from `llc.slacker.minis` to `llc.slacker.eta` so this fork
  installs next to the upstream Minis package instead of replacing it. The Kotlin
  namespace stays `com.openminis.app`; only the build identity changes.
- The documents-provider authority and the ownership-migration target now derive from
  `BuildConfig.APPLICATION_ID` instead of repeating the literal, so they cannot drift again.
- Data does not migrate: a fresh install under the new id starts with its own private
  data, and the previous package keeps its data untouched. Deep links (`minis://`) are
  unchanged because they are scheme-based.
- Current-state docs, debug tooling and the documentation-provenance guard follow the
  new id; the device reports under `docs/` are left as historical records that describe
  the old package.

### Custom system prompt box + editable prompt modules — 2026-09-18

- Settings → System prompt is now a single free-text box for the device owner's own system prompt (Codex-style custom instructions). Whatever is saved there is injected at the top of the assembled agent prompt with an explicit precedence header, so it outranks the personality (SOUL.md), response style, presets, bot instructions and session Souls. An empty box injects nothing.
- Stored at `<filesDir>/system_prompt/custom.md`; editable from the app, from `minis://settings/system-prompt`, and by the agent through the config registry (`prompt.custom`, confirmation sheet and audit/revert unchanged).
- The static system prompt is no longer a Kotlin literal inside `ChatViewModel.buildSystemPrompt()`. The shipped wording is one file per section under `src/android/app/src/main/assets/prompts/<id>.md`; `com.openminis.app.prompt` owns the registry (assembly order, separators, runtime gates), the override store, the custom-prompt store and the composer. ChatViewModel only contributes the per-turn runtime fragments.
- The shipped sections keep an advanced editor (Settings → System prompt → Built-in prompt modules): every module shows its id, gate (ALWAYS / memory-on / memory-off), override state and an enable switch. Editing writes an app-private override; Reset to default deletes it so the section tracks app updates again. `minis-config` reads and writes the same store (`prompt.modules`, `prompt.<id>.text`, `prompt.<id>.enabled`).
- Defaults stay byte-identical: with no overrides and an empty custom box the assembled prompt equals the pre-extraction prompt for both memory states, pinned by checked-in fixtures in `SystemPromptComposerTest`. `SystemPromptRemnantGuardTest` scans every module default file and asserts ChatViewModel no longer inlines prompt wording.
- Unchanged by design: the SOUL.md identity layer (Settings → Soul), per-bot instructions, per-session Soul overrides and the per-turn runtime fragments (skills, MCP disclosure, memory, runtime context).
- Known limitation: the custom prompt and module overrides are app-private files and are not yet part of the backup/restore categories.

### Direct Ubuntu runtime finalization — 2026-09-10

- Finalized the production Linux path as Android App-owned orchestration + Ubuntu 24.04 direct chroot.
- Removed the obsolete privileged broker source/build/runtime path, socket protocol, Android client package, payload fields, and packaged broker binary.
- Kept raw Root scripts and generic Root RPC internal to trusted runtime infrastructure; the local Agent has the upstream-compatible structured `root.shell` capability, while MCP remains denied.
- Guest commands run as the real Android App UID/GID after `setpriv` clears supplementary groups and Linux capabilities.
- Moved active guest user data to App-owned backing derived from `Context.filesDir`; `/data/adb/minis/rootfs` remains Root-owned replaceable runtime state.
- Added one-time migration from historical Root-owned user-data trees into App-owned storage.
- Added the standalone loopback HTTP/CONNECT network compatibility helper and deterministic HTTP absolute-form / CONNECT forwarding tests.
- Clarified that the network proxy is independent from the Root/chroot architecture: proxying itself does not require Root; privileged identity is only a deployment choice for Android UID/VPN/BPF egress compatibility.
- Strengthened runtime/package/build cleanup guards so removed broker identities cannot return to production source/build/package paths.
- Kept Release signing fail-closed and verified Debug/Release packaging, native 16 KiB alignment, rootfs payload boundaries, rclone, lint, unit tests, and bundle-generated APKs in canonical CI.
- PR #235 merged the Direct Ubuntu migration into `main` on 2026-09-10. This branch remains useful as the migration/reference branch and may contain documentation follow-up commits after that merge.

### Security and tool boundary

- `root.shell` is a bounded structured local-Agent capability (`tool` + `args`); it remains hidden from MCP and does not expose raw commands, host files, or generic RPC.
- Android capability/tool descriptions now report Direct Ubuntu rather than the removed runtime architecture.
- Production residue guards distinguish real obsolete runtime identities from unrelated identifiers such as `MinisDocumentsProvider`.

## Historical architecture stages

Earlier project history includes two superseded Linux-runtime stages:

1. Alpine/PRoot userspace inherited or evaluated during early development.
2. A privileged Rust broker + Ubuntu chroot stage used during the first Root-runtime migration.

Both are historical only. Old Issue/PR documents and Git history may retain those names to explain past decisions; they are not current runtime contracts.

## Current architecture summary

```text
Android App
  → ExecutionCoordinator / App-owned shell lifecycle
  → UbuntuKernel / DirectRootRunner
  → su → setsid → unshare -m → bind mounts → chroot
  → setpriv(real App UID/GID, clear groups/caps)
  → Ubuntu 24.04 bash
```

Network compatibility is separate:

```text
Ubuntu guest (App UID)
  → optional/current loopback HTTP/CONNECT compatibility path
  → Android outbound network
```

The current implementation may run that helper with privileged identity where required by Android networking policy; this does not make the proxy protocol itself part of Root/chroot.

## Upstream / source lineage

For legal source lineage, see [PROVENANCE.md](PROVENANCE.md). Historical upstream relationships do not define current product identity or a sync policy.
