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

<!-- List exact commands or manual checks that actually ran. -->

- [ ] Relevant Android unit tests
- [ ] Android lint/build checks when Android source/resources changed
- [ ] Rust fmt / Clippy / tests when `src/native/minisd/` changed
- [ ] rootfs verification tests when rootfs scripts changed
- [ ] documentation provenance guard when active docs change

## Security and compatibility

- [ ] No secrets, tokens, signing material, or private fixtures are committed
- [ ] New tools use canonical permission/runtime gates
- [ ] Root operations do not introduce raw model-controlled root execution
- [ ] Side-effecting operations preserve approval/checkpoint/recovery semantics
- [ ] Current English documentation is updated when behavior changes
- [ ] Source provenance and third-party attribution remain intact

## Provenance

If the change imports or adapts code from another project, identify the source commit/PR/release, preserve applicable notices, and explain any adaptation required by the current Minis for Android architecture.

See [CONTRIBUTING.md](../CONTRIBUTING.md) and [PROVENANCE.md](../PROVENANCE.md).
