# Third-Party Licenses

OpenMinis bundles, links, or depends on the following third-party components. Versions reflect the current source tree; license types were verified against each project's repository (GitHub license metadata / LICENSE files).

## 本分支的许可证说明

上游 OpenMinis 采用 GPL-3.0，其中一个原因是 iOS 侧的 iSH 为 GPL-3.0。**本分支移除了
iOS 相关代码与 iSH，但这不改变许可证义务**：本仓库是 OpenMinis 的派生作品，因此整体
继续按 **GPL-3.0** 分发。分发由本仓库构建的 APK 时，同样需要提供对应源码。

## Native C/C++ dependencies (`deps/`)

| Component | Version / Source | License | Notes |
|---|---|---|---|
| [proot](https://github.com/OpenMinis/proot) (fork) | git submodule `deps/proot` | **GPL-2.0** | Linux sandbox on Android (`libproot.so`, `proot-aarch64`) |
| [talloc](https://talloc.samba.org) (Samba) | vendored at `deps/talloc` | **LGPL-3.0-or-later** | Memory allocator required by proot |
| [cppjieba](https://github.com/yanyiwu/cppjieba) | vendored (`jieba_jni`) | **MIT** | Chinese word segmentation (header-only + dictionaries) |
| Alpine Linux minirootfs | downloaded at build time by `deps/prepare_alpine_rootfs.sh` | Aggregate of package licenses (musl **MIT**, BusyBox **GPL-2.0**, etc.) | Not stored in this repo; bundled into app builds as the default rootfs |

## Web Remote 前端（`assets/remote/`）

本分支为 Web 远程控制页面引入，均以单文件 UMD 形式随 APK 分发：

| 组件 | 版本 | License | 用途 |
|---|---|---|---|
| [marked](https://github.com/markedjs/marked) | 15.0.12 | **MIT** | Markdown 解析（`marked.js`，许可证全文见 `assets/remote/LICENSE-marked.md`） |
| [DOMPurify](https://github.com/cure53/DOMPurify) | 3.4.14 | **MPL-2.0 OR Apache-2.0** | 渲染前净化模型输出，防 XSS（`purify.js`，许可证全文见 `assets/remote/LICENSE-dompurify`） |
| [@deepseek-ai/dsh-client-ui-theme](https://www.npmjs.com/package/@deepseek-ai/dsh-client-ui-theme) | 0.0.1-rc.1 | **BSD-3-Clause** | 设计 token 与滚动条样式（`ds-tokens.css` / `ds-scrollbar.css`，取自公开 npm 包，许可证全文见 `assets/remote/LICENSE-deepseek-theme`）。BSD-3 第三条：不得以原作者名义为本项目背书——本项目与 DeepSeek 无任何关联。 |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | 0.1.0-rc.8 | **MIT** | 本地官方 rc8 源码是 Web Remote 的 source-adapted 信息架构、会话事件和交互细节参照；当前 `index.html` / `app.css` / `app.js` 为本项目原生实现，未捆绑 Harness 的 React/Cordis 前端 bundle。许可证全文见 `assets/remote/LICENSE-deepseek-harness-MIT`。本项目与 DeepSeek 无任何关联。 |

页面在严格 CSP 下从 APK assets 提供，无法访问任何 CDN，因此两者随包分发而非外链。

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

Test-only dependencies: JUnit 4.13.2 (**EPL-1.0**), MockWebServer 4.12.0 (**Apache-2.0**), kotlinx-coroutines-test 1.9.0 (**Apache-2.0**), org.json 20231013 (**Public Domain / JSON License**).

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android `app/src/main/assets/katex/` | **MIT** |
| jieba dictionaries | iOS bundle / Android `assets/jieba/` | **MIT** (cppjieba distribution) |

## Removed / historical

- **swift-markdown-ui** (MIT) — formerly vendored under `deps/swift-markdown-ui`; no longer referenced by the Xcode project or imported by any source file, and is not part of the open-source tree.
