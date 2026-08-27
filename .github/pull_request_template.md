## Summary

<!-- What changed and why? -->

## Scope

- [ ] Android app/runtime
- [ ] Provider/model behavior
- [ ] Android-native tools
- [ ] MCP client/server
- [ ] `minisd` / Ubuntu execution runtime
- [ ] Voice / assistant / overlay
- [ ] Build / CI / release engineering
- [ ] Documentation only

## Verification

<!-- List the exact commands or manual checks that were actually run. -->

- [ ] Relevant Android unit tests
- [ ] `:app:lintDebug` when Android source/resources changed
- [ ] `:app:lintRelease` when release behavior changed
- [ ] `:app:assembleDebug` when production source/assets changed
- [ ] release build/signing checks when release configuration changed
- [ ] Rust fmt / Clippy / tests when `src/native/minisd/` changed
- [ ] rootfs verification tests when rootfs scripts changed

## Security and compatibility

- [ ] No API key, OAuth token, MCP token, signing material, or private fixture is committed
- [ ] New tools use the canonical Tool Registry / permission/runtime gates
- [ ] Root operations do not introduce raw model-controlled `su` execution
- [ ] Side-effecting operations preserve approval/checkpoint/recovery semantics
- [ ] Path, IPC, output, and timeout limits remain fail-closed
- [ ] Network changes do not allow credential downgrade from HTTPS to public cleartext HTTP
- [ ] Release builds do not fall back to debug signing
- [ ] Current English documentation is updated when behavior changes
- [ ] Upstream/third-party attribution remains intact

## Upstream

If this ports a change from OpenMinis, include the upstream PR/commit and describe any adaptation required by the local `minisd` + Ubuntu architecture.

See [CONTRIBUTING.md](../CONTRIBUTING.md) and [UPSTREAM.md](../UPSTREAM.md).
