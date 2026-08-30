# minisd

Minis Root Broker. Canonical crate after P2 sync.

Build (WSL):

```bash
cd src/native/minisd
cargo test --offline
cargo build --release --target aarch64-unknown-linux-musl
```

The Android arm64 binary is built by `src/android/app/build.gradle.kts`, not
by copying this host/musl artifact. That task uses the pinned Android NDK and
the `aarch64-linux-android` target, then verifies an Android `ET_DYN`/PIE ELF
with 16 KB `LOAD` alignment before packaging it as
`lib/arm64-v8a/libminisd.so`.

At runtime Android resolves the executable from
`ApplicationInfo.nativeLibraryDir`. The broker uses app-UID-scoped abstract
Unix sockets and receives its policy in memory; it does not install a binary,
socket, pidfile, or policy file under `/data/adb/minis`.

Do not add HTTP. Structured RPC only.
