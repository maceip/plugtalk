# Incident report — Trap (onion-vault) Android browser (Historical)

**Filed:** 2026-11-14  
**Status:** Resolved — remediated via decoupled `libonion_vault.so` JNI, source-built `maceip/cefpack` Chromium `//content/public/android` AAR (`cefpack-android.aar`), `scripts/audit-apk.sh`, `scripts/emulator-gate.sh`, and `scripts/limrun-gate.mjs`.

---

## 1. Historical Summary (2026-11-14)

An initial prototype of the Trap Android browser shipped unexecuted C-API browser bindings and reported them as working without launching the APK on an emulator or device:

1. **Missing Chromium Java/DEX Layer & Unloaded Native Library:** The initial prototype packaged a raw native browser `.so` with zero `Lorg/chromium/` or `Lorg/jni_zero/` Java classes in `classes*.dex` and never called `System.loadLibrary("onion_vault")`.
2. **Unreachable Render-Process DOM Handler:** The prototype attempted to run DOM OpenPGP scanning inside a desktop-style render-process C-API callback rather than browser-process Chrome DevTools Protocol (`DOM.*` / `Input.*`) over `content::DevToolsAgentHost`.
3. **Missing Runtime & Packaging Gates:** Early CI only ran host `cargo test` and JVM unit tests without auditing APK ELF/DEX contents or booting the APK on Android.

## 2. Remediations Enforced Today

1. **Zero Browser `.so` Coupling in Rust (`libonion_vault.so`):** `libonion_vault.so` exports strictly the 24 `Java_dev_onionvault_OvNative_*` JNI entry points (Arti Tor, `ClearnetAllowlist`, Sequoia OpenPGP + Android Keystore, and pure-Rust CDP DOM JSON classification) with `0` `DT_NEEDED` browser library dependencies.
2. **Source-Built `maceip/cefpack` Content Embedder (`cefpack-android.aar`):** Browser rendering runs on [`https://github.com/maceip/cefpack`](https://github.com/maceip/cefpack) (`dev.cefpack.android.CefpackEngine` & `CefpackSession`, `libcefpack_content.so` for `arm64-v8a` + `x86_64`, SHA-256 verified by Gradle) with C++ `javascript_enabled = false`, live `ProxyConfigClient` SOCKS5 Tor routing, and in-process `DevToolsAgentHost` JSON CDP.
3. **Fail-Closed Binary, Emulator, and Cloud Device Gates:** `scripts/audit-apk.sh`, `scripts/emulator-gate.sh`, and `scripts/limrun-gate.mjs` block every release unless the packaged APK passes 16KB ELF64 alignment, DEX/JNI/manifest audits, cold boot, Arti Tor bootstrap, and live `.onion` HTTP 200 rendering.
