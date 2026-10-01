# Changelog

This changelog tracks **Minis for Android** as an independently maintained Android project. Legal source lineage is documented separately in [PROVENANCE.md](PROVENANCE.md).

## Unreleased

## 1.0 — 2026-10-02

First official release (`versionName` 1.0, `versionCode` 1000099). Everything below this heading up to the end of
the file is the history that led here.

Builds before 1.0 (`1.01-beta.*`) are not offered 1.0 by the in-app updater, because the updater reads `1.01` as
newer than `1.0`. Install the 1.0 APK over them by hand: its `versionCode` is higher (1000099 against 40 or less), so it upgrades in place
and keeps your data.

### Sub agents, Compact Above and reply usage — 2026-10-02

- **Sub agents.** The `subagent` tool can hand work to named sub agents that run as their own child sessions:
  up to three at once with ten more queued, a card per delegation in the tool detail view, wrap-up on timeout,
  optional progress reports, resume after an interrupted run or a restart, and a roster the model sees each
  turn. Settings → Sub agents defines your own agents (name, description, instructions, an optional pinned model) and the master switch; they
  are part of backup and restore. With the switch off the original single-shot `subagent` behaviour is unchanged.
  Child sessions never receive the delegation tool, and delegation has its own permission (`agent.subagent`).
  See [docs/development/SUB-AGENTS.md](docs/development/SUB-AGENTS.md), which also records what has not been
  exercised with a live model on a device yet.
- **Compact Above.** Long-press one of your messages to compact everything above it into a summary.
- **Reply token usage.** The long-press menu of a reply shows its context, input, output and cache tokens and the
  time it finished; the counts are stored with the message.

### Model discovery and image models — 2026-10-01

- With a ChatGPT sign-in, the model list is read live from the Codex catalog instead of a fixed list, so new
  models appear without an app update. Manual bearer keys keep the static list.
- Model refresh also runs when the app returns to the foreground and when the model picker opens (at most every
  30 minutes after a failure); the daily stamp is only written once every provider instance refreshed.
  Gemini model lists are paged, and Gemini sign-in is handled as OAuth.
- GPT Image 2.5 (`gpt-image-2.5-flare`, `gpt-image-2.5-sunburst`) is available through the ChatGPT sign-in as an
  image-only route; GPT Image 2 is unchanged. The Codex client version is 0.159.0.

### Version management — 2026-10-02

- `main` is the current version and its betas, finals and patches are tagged there (`vX.Y[.Z][-beta.N]`; betas
  are GitHub prereleases). Only a version that has become history gets its own branch, one per version, named
  `release/vX.Y` (so `release/v1.0` appears once the project has moved past 1.0); fixes land on `main` first and
  are cherry-picked down. The branch is not called plain `vX.Y` because the tag is, and a branch and a tag with
  one name make git refuse to push or check it out. CI also runs on `release/**` pushes. See
  [docs/development/RELEASING.md](docs/development/RELEASING.md); `scripts/release-check.sh` is the read-only
  pre-flight before tagging.
- `versionName` is set in one place (`appVersionName` in `app/build.gradle.kts`) and `versionCode` is derived
  from it, so they cannot drift and the code orders like the version (`X.Y-dev < X.Y-beta.N < X.Y < X.Y.Z`).
  A malformed `versionName` fails the build. `VersionSchemeTest` pins the scheme and its agreement with the
  updater.
- The updater understands the stages: a beta is offered the next beta and then the final, and a stable build is
  no longer offered prereleases. (Before, a prerelease tag read as the same version as its final and would have
  been offered to everyone.)

### Repository links, privacy policy and open-source notices — 2026-10-02

- The update checker read releases from an old fork (`limuzi013/OpenMinis-Pet`) and several links named
  `limuzi013/minis-for-android`. All of them now come from one `AppLinks` object and point at
  `Slacker-LLC/minis-for-android`: update check and manual-download link, About → GitHub repository, the bug
  report form, the crash-frequency "Submit GitHub issue" row and the OpenRouter attribution headers. The
  remote importer's User-Agent no longer carries the old fork name.
- Added [PRIVACY.md](PRIVACY.md) and [PRIVACY.zh-CN.md](PRIVACY.zh-CN.md), written from what the app actually
  does: no backend, no analytics, crash logs stay on the device, every outbound destination listed.
  Settings → Privacy Policy opens it (the Chinese file for a Chinese UI) instead of a third party's page.
- About gains *Open-source licenses* ([THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)) and *License
  (GPL-3.0)* rows. The inventory now also lists libxposed, JetBrains annotations, the vendored Backdrop source,
  JetBrains Mono, the models.dev catalog snapshot, and where to get source for the Ubuntu packages.

### Canonical application identity and repository — 2026-09-20

- The integrated product uses the canonical `llc.slacker.minis` application identity in Gradle,
  Xposed targets, accessibility-control protocol addresses, debug tooling, tests and current docs.
- The active repository and mainline are `Slacker-LLC/minis-for-android` / `main`; Eta capability
  work is integrated into this independent product rather than presented as a separate fork line.
- Kotlin sources remain under `com.openminis.app`; this identity change does not rename the source
  namespace.

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
