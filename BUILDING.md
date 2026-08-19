# Building OpenMinis Pet (Android)

> 本分支只构建 Android，iOS 相关内容（`src/ios/`、iSH、FFmpeg、LAME）已移除。
> 中文构建步骤见 [BUILD-CN.md](BUILD-CN.md)。

Minis ships a full Linux sandbox inside the app, so a first build is not just
"open the project and press Run": the native dependencies (PRoot) and the Alpine rootfs are **built from source by the
scripts in `deps/`**, not committed as binaries. Budget ~30–60 minutes for the
first build; afterwards the artifacts are cached on disk and normal builds are
fast.

Read the section for your platform end to end before starting — the steps are
ordered by dependency, and skipping one produces confusing link errors later.

---

## Common setup

Clone with submodules — the PRoot fork is a submodule, and a clone
without them will fail at the native build step:

```sh
git clone --recurse-submodules https://github.com/OpenMinis/OpenMinis.git
cd OpenMinis

# Already cloned without --recurse-submodules?
git submodule update --init --recursive
```

| Submodule | Repository | Used by |
|---|---|---|
| `deps/proot` | [OpenMinis/proot](https://github.com/OpenMinis/proot) | Android sandbox |

### Build-time customization

Some values are injected at build time and are **not** in this repository.
Copy the templates before building:

```sh
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Leaving the values empty is fine — **the app compiles and runs**. A value is
only required by the feature that uses it, and that feature fails loudly at
runtime when it is missing. API-key based sign-in works without any
customization.

### `ANTHROPIC_OAUTH_IDENTIFIER_PROMPT`

Only relevant if you want to **sign in with Claude OAuth credentials** rather
than an Anthropic API key.

When a request is authenticated with OAuth, Anthropic's endpoint expects the
system prompt to begin with the identifying line that Claude Code itself
sends; without it the request is rejected. The build injects that line from
this value, so OAuth sign-in fails at runtime while it is empty.

We do not ship a value. Supply your own if you need this path — other
open-source projects that talk to the same endpoint declare the same
identifier, for example
[claude-relay-service](https://github.com/Wei-Shaw/claude-relay-service),
which you can consult for the exact wording.

Everything else — Anthropic API keys, and every other provider — works
without setting this.

---

## Android

### Requirements

| Tool | Version / notes |
|---|---|
| JDK | **17** (`sourceCompatibility`/`targetCompatibility` are 17) |
| Android SDK | **compileSdk 36**, targetSdk 35, **minSdk 26** |
| Android NDK | **r28+** — set `$ANDROID_NDK_HOME`, or install via Android Studio |
| CMake | 3.22.1 (install through the SDK Manager) |
| Shell tools | `curl`, `tar`, `make`, `awk`, `sed` |

Gradle itself comes from the wrapper (Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0) —
do not install it separately.

Only `arm64-v8a` is built (`abiFilters`), so use an arm64 device or emulator
image.

### 1. Build the native dependencies

```sh
./deps/build_proot.sh              # → assets/proot-aarch64, jniLibs/arm64-v8a/*.so
./scripts/prepare_android_sandbox.sh   # → assets/alpine-minirootfs.tar.gz
```

- **`build_proot.sh`** cross-compiles a static `libtalloc` and the
  `deps/proot` fork with the NDK, then installs into the app's `assets/` and
  `jniLibs/arm64-v8a/`: the proot binary itself plus **`libproot-loader.so`
  and `libproot-loader32.so`**. The script verifies all of them at the end and
  fails the build if any is missing.

  The loaders are required, not optional. Android 10+ enforces W^X for
  `untrusted_app`: nothing labelled `app_data_file` — which includes the whole
  extracted Alpine rootfs under the app's `files/` dir — can ever be executed.
  Only `nativeLibraryDir`, populated from `lib/**/*.so` in the APK, is
  executable, so the loader lives there and maps guest binaries itself instead
  of relying on the kernel's `execve`. (proot can normally extract an embedded
  loader at runtime, but on Android it writes it into the app's temp dir and
  hits the same W^X wall.) That is also why they carry a `.so` suffix despite
  being executables rather than shared objects — only `*.so` gets extracted.

  Artifacts are **not** byte-identical across NDK releases; the loader's code
  differs between toolchain generations. Functionally equivalent — don't expect
  checksums to match someone else's build.
- **`prepare_android_sandbox.sh`** downloads the Alpine aarch64 minirootfs into
  `assets/`.

Both write into `src/android/app/src/main/`, and their outputs are gitignored —
they are build artifacts, so rerun the scripts rather than committing them.

The small JNI libraries in `src/main/cpp/` (`pty_bridge`, the crash handler,
`jieba_jni`) are built by CMake as part of the normal Gradle build; no separate
step is needed.

### 2. Build the app

```sh
cd src/android
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/
./gradlew :app:installDebug           # install onto a connected device
```

Release builds are configured with the debug signing config, so no keystore is
required to produce one locally.

### Tests

```sh
./gradlew :app:testDebugUnitTest        # JVM unit tests
./gradlew :app:connectedAndroidTest     # instrumented; needs a device/emulator
```

---

## Troubleshooting

**`deps/proot` is empty** — the submodules were not initialised:
`git submodule update --init --recursive`.

**Android: `Android NDK not found`** — set `ANDROID_NDK_HOME` to your NDK r28+
installation, e.g.
`export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/28.0.12433566`.

**Android: app starts but the shell does not** — the sandbox assets are
missing. Rerun `./deps/build_proot.sh` and
`./scripts/prepare_android_sandbox.sh`, then rebuild.

**Android: every command returns `[Shell not running] (exit code: -1)`** —
the proot ELF loaders are missing from the APK. Check that
`src/android/app/src/main/jniLibs/arm64-v8a/` contains `libproot-loader.so`
(and `libproot-loader32.so`); if not, rerun `./deps/build_proot.sh`, which
now verifies them, and rebuild. Confirm with
`unzip -l app-debug.apk | grep libproot` — all three `.so` files must be
present.

This failure is deliberately hard to spot from the outside: proot still
launches and logs its `native_offload` initialisation, so the sandbox looks
healthy and any "does it start?" check passes. Only the first
`execve("/bin/sh")` fails, with `Permission denied` in logcat under the
`PRootStderr` tag. When touching this area, verify by **running a command and
asserting exit code 0** — starting the sandbox is not enough.

**A feature throws about a missing configuration value** — that value comes
from the customization file; see [Build-time customization](#build-time-customization).

---

## Licensing note

Upstream Minis is **GPLv3**; one reason is that it links iSH (GPLv3). This fork
drops iOS and iSH, but that does not change the obligation — it is a derivative
work of OpenMinis and stays **GPLv3**. It still links PRoot (GPLv2). Preserve the
vendored `LICENSE` files. See
[LICENSE](LICENSE) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
