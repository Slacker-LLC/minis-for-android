# 构建入口与清理边界

最初的 Issue #53 审计已结束。本文只保留仍有用途的入口/守卫索引，操作步骤统一见[中文构建指南](../BUILDING.zh-CN.md)。

| 产物或检查 | 当前入口 |
|---|---|
| Android APK/AAB | `src/android/gradlew` |
| Ubuntu rootfs | `scripts/build-ubuntu-rootfs.sh` |
| rootfs-only payload | `scripts/build-runtime-payload.sh` |
| 独立网络 helper | `scripts/build-root-network-proxy-android.sh` |
| rclone Android AAR | `deps/build_rclone_android.sh` |
| Windows Debug 包装脚本 | `scripts/build-android-debug.ps1` |
| CI | `.github/workflows/ci.yml` |

runtime payload 只含 Ubuntu rootfs 及 manifest；HTTP/CONNECT helper 是单独验证和打包的 native 产物，不因当前特权部署而成为 Root/chroot 的固有组件。

旧 broker 源码、构建步骤、Android client、socket 与二进制，以及一次性迁移辅助脚本已退出生产路径。历史五份 PR patch 副本和已被合同替代的七步计划已从文档树删除，需要追溯时查 Git 历史，不再将归档代码当构建输入。

## 回归守卫

- `scripts/check_build_cleanup.py` / `scripts/test_build_cleanup_guard.py`：防止旧构建与 runtime 身份回归。
- `scripts/check-runtime-package-boundary.sh`：现役 runtime 包与有限旧包白名单。
- `scripts/test-build-ubuntu-rootfs-verification.sh` / `scripts/test-runtime-payload-verification.sh`：rootfs/payload 验证否定用例。
- `scripts/verify-runtime-payload.sh` / `scripts/verify-root-network-proxy.sh`：产物验证。
- `scripts/verify-android-16k.sh` / `scripts/verify-android-bundle.sh`：APK/AAB 与 16 KiB 兼容检查。

守卫通过不等于 Root 真机通过；设备证据见[实测报告](REAL-DEVICE-TEST-REPORT.md)。
