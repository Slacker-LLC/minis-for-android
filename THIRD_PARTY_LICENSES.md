# Third-Party Licenses

OpenMinis bundles, links, or depends on the following third-party components. Versions reflect the current source tree; license types were verified against each project's repository (GitHub license metadata / LICENSE files).

## 本分支的许可证说明

上游 OpenMinis 采用 GPL-3.0，其中一个原因是 iOS 侧的 iSH 为 GPL-3.0。**本分支移除了
iOS 相关代码与 iSH，但这不改变许可证义务**：本仓库是 OpenMinis 的派生作品，因此整体
继续按 **GPL-3.0** 分发。分发由本仓库构建的 APK 时，同样需要提供对应源码。

## Native C/C++ dependencies (`deps/`)

> P2 重构后 PRoot/Alpine 依赖已移除;下表中 proot/talloc/Alpine 条目为历史记录,
> 当前不再构建或随 APK 分发。当前原生依赖为 Rust 的 minisd(见下)。

| Component | Version / Source | License | Notes |
|---|---|---|---|
| ~~[proot](https://github.com/OpenMinis/proot) (fork)~~ | pinned git submodule `deps/proot` | **GPL-2.0** | P2 已移除(历史) |
| ~~Termux PRoot ELF loaders~~ | proot `5.1.107-70` | **GPL-2.0** | P2 已移除(历史) |
| ~~[talloc](https://talloc.samba.org) (Samba)~~ | 2.4.2 | **LGPL-3.0-or-later** | P2 已移除(历史) |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | vendored (`jieba_jni`) | **MIT** | Chinese word segmentation (header-only + dictionaries) |
| ~~Alpine Linux minirootfs~~ | 3.21.3 | Aggregate | P2 已移除(历史) |

## Rust native dependencies (`src/native/minisd/`)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| minisd(本项目源码) | 同仓 `src/native/minisd/` | **GPL-3.0**(随项目) | Root Broker + Ubuntu chroot 运行时;静态 musl 交叉编译 |
| [serde](https://github.com/serde-rs/serde) / serde_json | 1.x(见 Cargo.lock) | MIT / Apache-2.0 | JSON 协议序列化 |
| [libc](https://github.com/rust-lang/libc) | 0.2(见 Cargo.lock) | MIT / Apache-2.0 | 裸 syscall 绑定(unshare/mount/getsockopt 等) |
| Ubuntu 24.04 base rootfs | `24.04.3`(noble),打包脚本 SHA-256 校验 | Aggregate of package licenses | 生成构建产物,打包进 APK assets


## Android — Gradle dependencies

| Library | Version | License |
|---|---|---|
| AndroidX / Jetpack (Compose BOM 2025.09.00, core-ktx, lifecycle, activity, navigation, Room, DataStore, security-crypto, browser, webkit, exifinterface) | see `app/build.gradle.kts` | **Apache-2.0** (Google / AOSP) |
| OkHttp + okhttp-sse | 4.12.0 | **Apache-2.0** |
| kotlinx-serialization-json | 1.7.3 | **Apache-2.0** |
| kotlinx-coroutines-android | 1.9.0 | **Apache-2.0** |
| Coil (coil-compose) | 2.7.0 | **Apache-2.0** |
| multiplatform-markdown-renderer (+ m3) — mikepenz | 0.33.0 | **Apache-2.0** |
| Reorderable (sh.calvin.reorderable) | 2.4.0 | **Apache-2.0** |
| ACRA (acra-core) | 5.12.0 | **Apache-2.0** |
| Shizuku API + provider (dev.rikka.shizuku) | 13.1.5 | **MIT** |
| [RealTimeCutVADLibraryForAndroid](https://github.com/helloooideeeeea/RealTimeCutVADLibraryForAndroid) | 1.0.5 | **MIT** |

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**).


## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | Android `assets/jieba/` | **MIT** (cppjieba distribution) |

## Removed / historical

- **swift-markdown-ui** (MIT) — formerly vendored under `deps/swift-markdown-ui`; no longer referenced by the Xcode project or imported by any source file, and is not part of the open-source tree.
