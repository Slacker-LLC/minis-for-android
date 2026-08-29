# minisd

Minis Root Broker. Canonical crate after P2 sync.

Build (WSL):

```bash
cargo test --offline
cargo build --release --target aarch64-unknown-linux-musl
```

Install: `/data/adb/minis/bin/minisd --watchdog --mount-ns-pid <app-pid>`

Socket: `/data/adb/minis/run/minisd.sock`

Persistent mode requires the watchdog to join the Android App mount namespace
before it starts the broker. App-private workspace/session/global directories
are then visible with their real local backing instead of the global
`tmpfs_data` overlay. The guest home is mounted separately at `/home/minis`;
the default command working directory remains `/workspace`.

Do not add HTTP. Structured RPC only.
