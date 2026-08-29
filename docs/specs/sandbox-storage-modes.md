# Sandbox storage modes

Status: design only. The urgent issue #50 fix keeps local persistent storage as
the only supported mode. The selector described here belongs on
`feature/sandbox-storage-modes` and must not delay the persistence repair.

## Branch and delivery model

| Branch | Contract |
|---|---|
| `master` | Existing observable `tmpfs_data` behavior before issue #50 is fixed |
| `fix/issue-50-persistent-storage` | Local persistent storage only; no mode selector |
| `feature/sandbox-storage-modes` | Future local/memory selector, based on the persistence fix |

The volatile behavior on `master` is a namespace bug, not the implementation
of memory mode. A future memory mode must use an explicit, owned tmpfs and must
never depend on accidentally seeing Android's App Data Isolation overlay.

## User-facing modes

| Setting | Persistent (recommended) | Memory |
|---|---|---|
| Backing store | App-private local filesystem | Explicit runtime tmpfs |
| Survives sandbox/App/device restart | Yes | No guarantee |
| Applies immediately | No; restart App | No; restart App |
| Local data when switching away | Retained | Volatile data is not copied automatically |
| Intended use | Normal use | Disposable sessions, testing, sensitive scratch work |

Changing modes must never delete the inactive persistent dataset. Switching
back to persistent mode exposes the same local files that existed before the
switch. The UI must warn that files created only in memory mode can disappear
and offer an explicit export/copy action separately from the mode switch.

## Data covered by the selection

The selected backing applies as one consistent runtime layout:

- per-session workspaces, attachments, offloads, and browser files;
- global memory, skills, and shared files;
- the Linux user home at `/home/minis`, including `.config`, Git/GitHub CLI
  credentials, `.netrc`, `.gitconfig`, `.bashrc`, and `.profile`.

The Ubuntu rootfs, `minisd` binary, policy, PID files, and other privileged
runtime state remain under `/data/adb/minis`; they are not user workspace data
and are not switched to tmpfs by this setting.

An API key supplied only with `export` remains process-scoped in both modes.
Persistence of the home directory does not turn a process environment variable
into stored configuration, and the Settings UI must explain that distinction.

## Startup and transition contract

1. Persist an enum such as `PERSISTENT` or `MEMORY` in DataStore.
2. Show a restart-required state after the user changes it.
3. On the next App start, stop the old keeper and broker before selecting the
   new backing store.
4. Materialize every source directory before the keeper calls `unshare`.
5. Start `minisd` in the App mount namespace and pass one complete, typed layout
   to `ubuntu.start`.
6. Verify the broker namespace and filesystem type before exposing file tools.
7. Fail closed on a mixed layout; never let Android file tools and the guest
   resolve the same logical path to different backing files.

Persistent mode must reject tmpfs-backed sources. Memory mode must positively
verify tmpfs-backed sources. The check is symmetric so neither mode can silently
degrade into the other.

## Acceptance matrix

| Scenario | Expected result |
|---|---|
| Persistent: create `/workspace/probe`, restart App | File remains |
| Persistent: log in with `gh`, restart App | `gh auth status` remains authenticated |
| Persistent: add an alias to `~/.bashrc`, start a new shell | Alias is available |
| Memory: create `/workspace/probe`, recreate the volatile sandbox | File is gone |
| Switch persistent → memory → persistent | Original persistent files return unchanged |
| Any mode: compare App resolver, `mountinfo`, and `statfs` | All views agree on the selected backing |
| Mode change without restart | Existing sandbox continues unchanged and UI shows restart required |
