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
- [ ] documentation provenance guard when active docs change

## Security and compatibility

- [ ] No API key, OAuth token, MCP token, signing material, or private fixture is committed
- [ ] New tools use the canonical Tool Registry / permission/runtime gates
- [ ] Root operations do not introduce raw model-controlled `su` execution
- [ ] Side-effecting operations preserve approval/checkpoint/recovery semantics
- [ ] Path, IPC, output, and timeout limits remain fail-closed
- [ ] Network changes do not allow credential downgrade from HTTPS to public cleartext HTTP
- [ ] Release builds do not fall back to debug signing
- [ ] Current English documentation is updated when behavior changes
- [ ] Source/third-party attribution remains intact

## Provenance

If this change imports or adapts code from another project, include the source PR/commit/release and describe any adaptation required by the current Minis for Android architecture.

See [CONTRIBUTING.md](../CONTRIBUTING.md) and [PROVENANCE.md](../PROVENANCE.md).
