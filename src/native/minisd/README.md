# minisd

Minis Root Broker. Canonical crate after P2 sync.

Build (WSL):

```bash
cd src/native/minisd
cargo test --offline
cargo build --release --target aarch64-unknown-linux-musl
```

The Android arm64 release must be position-independent (`ET_DYN`/PIE). The
target-specific Cargo configuration supplies the static musl linker and PIE
flags required by Android 16.

Install: `/data/adb/minis/bin/minisd --watchdog`  
Socket: `/data/adb/minis/run/minisd.sock`

Do not add HTTP. Structured RPC only.
