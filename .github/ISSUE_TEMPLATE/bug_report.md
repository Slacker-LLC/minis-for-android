---
name: 🐛 Bug report
about: Report a reproducible problem in Minis for Android
title: "[Bug] "
labels: ["bug", "triage"]
---

## Problem

<!-- Describe the failure clearly and concisely. -->

## Environment

| Field | Value |
|---|---|
| Android version | |
| Minis `versionName` / `versionCode` | |
| Device / ROM | |
| Root solution/status | None / Magisk / KernelSU / APatch / other |
| SELinux mode (if relevant) | |
| Provider / model (if relevant) | |
| VPN/TUN/Fake-IP (if network-related) | |

## Steps to reproduce

1.
2.
3.

## Expected behavior

## Actual behavior

## Logs / diagnostics

<!-- Sanitize logs. Remove API keys, OAuth/MCP tokens, signing material, private file contents, phone numbers, contacts, and unrelated device data. -->

```text

```

## Runtime area

- [ ] Android UI / chat
- [ ] Provider / model
- [ ] Android-native tool
- [ ] MCP
- [ ] Direct Ubuntu / rootfs / mount / privilege drop
- [ ] Network compatibility proxy / DNS / VPN / BPF
- [ ] Voice / assistant / overlay
- [ ] Build / CI / release
- [ ] Other

## Additional context

<!-- Root and networking are separate. For Root/runtime issues include the actual capability/SELinux/mount result. For network issues say whether guest direct networking fails, whether 127.0.0.1:18787 is reachable, and whether the failure is DNS, route, VPN/TUN/Fake-IP, or proxy-specific. Do not assume a proxy problem merely because the device is rooted. -->
