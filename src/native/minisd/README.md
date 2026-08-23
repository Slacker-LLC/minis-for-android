# minisd

Minis Root Broker. Canonical crate after P2 sync.

Build (WSL):

```bash
cargo test --offline
cargo build --release --target aarch64-unknown-linux-musl
```

Install: `/data/adb/minis/bin/minisd --watchdog`  
Socket: `/data/adb/minis/run/minisd.sock`

Do not add HTTP. Structured RPC only.
