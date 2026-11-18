# Browser Runtime Source-Build Architecture (`maceip/cefpack`)

**Status:** Active Production Architecture  
**Date:** 2026-11-17  
**Scope:** Source-built, SHA-256-pinned Chromium `156.0.8078.96` (`2ad9b7f5a86846e0b264090723a6a78964bb84c4`) Android `//content/public/android` runtime (`cefpack-android.aar`) built in [`https://github.com/maceip/cefpack`](https://github.com/maceip/cefpack) and consumed directly by Trap (`dev.onionvault`).

---

## 1. Executive Summary

Trap's core security guarantees—browsing `.onion` hidden services and gated clearnet origins over Arti Tor with **zero page JavaScript** while performing in-page Sequoia OpenPGP operations—require a first-party, auditable browser engine built directly from pinned upstream `chromium/src` sources rather than third-party pre-built Maven SDKs:

1. **Zero `//chrome/android` & Zero Google Play Services Surface:** `cefpack-android.aar` compiles only `//content/public/android`, `//components/embedder_support/android`, `//base`, `//net`, `//ui/android`, Blink, and V8. All `org.chromium.chrome.*` classes and all Google Play Services (`com.google.android.gms.*`), Firebase (`com.google.firebase.*`), MLKit (`com.google.mlkit.*`), and WebOTP SMS classes are excluded and rejected at build time by both `maceip/cefpack/embedder/assemble_aar.py` and Trap's `scripts/audit-apk.sh`.
2. **Zero Desktop CEF C-API Middleman:** `libonion_vault.so` has `0` `DT_NEEDED` browser library dependencies (verified by `scripts/audit-apk.sh`). Trap drives `CefpackEngine` and `CefpackSession` (`dev.cefpack.android.*`) directly from Kotlin plus an in-process trusted `content::DevToolsAgentHost` JSON bridge (`CefpackDevToolsBridge`).
3. **Reproducible GitHub Release Resolution with Mandatory SHA-256 Verification:** `android/app/build.gradle.kts` (`:app:resolveCefpackAar`) downloads `cefpack-android.aar` directly from `https://github.com/maceip/cefpack/releases/download/<tag>/cefpack-android.aar`, verifies its pinned 64-hex SHA-256 digest before staging into `android/app/build/cefpack/<tag>/cefpack-android.aar` (with `-PcefpackAarPath` / `CEFPACK_AAR_PATH` override for local iteration), and records exact digest provenance into Trap's CycloneDX 1.5 and SPDX 2.3 SBOMs (`scripts/generate-sbom.py`).

---

## 2. Architecture Overview

```text
┌────────────────────────────── Trap APK (dev.onionvault) ──────────────────────────────┐
│  Kotlin UI & Session Layer (TrapApplication, TabSessionManager, CdpDomPgpController)  │
│           │                                               │                           │
│           │ OvNative JNI (24 symbols)                     │ dev.cefpack.android.*     │
│           ▼                                               ▼                           │
│  libonion_vault.so (Rust, cargo-ndk)        cefpack-android.aar (maceip/cefpack)      │
│   • ov-tor (Arti + SOCKS5h + ClearnetGate)   • org.chromium.{base,content_public,ui}  │
│   • ov-pgp (Sequoia + Android Keystore)      • ContentViewRenderView + ContentView    │
│   • ov-cef (Pure HTML/CDP DOM classifier)    • libcefpack_content.so (arm64 + x64):   │
│   • 0 browser .so dependencies                 - CefpackMainDelegate                  │
│                                                - CefpackContentBrowserClient          │
│                                                  (ProxyConfigClient Mojo + JS=false)  │
│                                                - CefpackDevToolsBridge                │
│                                                  (DevToolsAgentHostClient JSON)       │
│                                                - CefpackNavigationThrottle            │
│                                                - CefpackURLLoaderThrottle             │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Hardened Engine Properties Verified in `maceip/cefpack` & `audit-apk.sh`

### 3.1 SOCKS5 Proxy & Zero Clearnet / DNS / QUIC / WebRTC Leak
1. **Fail-Closed Startup & Live Dynamic SOCKS5 Switching:**
   - `CefpackEngine` boots with `socks5://127.0.0.1:1` (fail-closed loopback blackhole) before any tab exists.
   - `CefpackContentBrowserClient::ConfigureNetworkContextParams` populates `initial_proxy_config` (`socks5://127.0.0.1:1`), disables QUIC (`DisableQuic()`), disables insecure and DoH DNS (`InsecureDnsMode::kDisabled`, `SecureDnsMode::kOff`), and binds `mojo::RemoteSet<network::mojom::ProxyConfigClient>` so `CefpackEngine.setProxyServer("socks5://127.0.0.1:<arti_port>")` atomically updates all active and future `NetworkContext`s once Arti bootstraps.
2. **Command-Line & Network Hardening:**
   - `--host-resolver-rules="MAP * ~NOTFOUND , EXCLUDE 127.0.0.1"` guarantees OS `getaddrinfo()` fails closed for every non-loopback hostname while SOCKS5 forwards `.onion` and approved clearnet hostnames inside Tor circuits.
   - `--dns-prefetch-disable`, `--no-pings`, `--disable-background-networking`, `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`, and `enable_webrtc = false` in `args.gn`.
   - `--disable-features=PartitionAllocMemoryTagging,AutofillServerCommunication,OptimizationHints,Translate,MediaRouter,WebRtcHideLocalIpsWithMdns,HttpsUpgrades,HttpsFirstBalancedMode,HttpsFirstBalancedModeAutoEnable`.

### 3.2 Hard Zero-JavaScript & Zero-WebAssembly Enforcement
- `CefpackContentBrowserClient::OverrideWebPreferences` locks `javascript_enabled = false`, `javascript_can_access_clipboard = false`, `allow_scripts_to_close_windows = false`, `hyperlink_auditing_enabled = false`, `webgl1_enabled = false`, and `webgl2_enabled = false` in C++.
- Renderer processes receive `--js-flags=--jitless,--no-expose-wasm`.
- `CefpackURLLoaderThrottle` drops script, ping, and CSP-report subresources at the network layer, and `TabSessionManager` sends CDP `Emulation.setScriptExecutionDisabled({"value": true})`.

### 3.3 In-Process Trusted CDP JSON Bridge (`CefpackDevToolsBridge`)
- Attaches in-process via `content::DevToolsAgentHost::GetOrCreateFor(web_contents)` (`IsTrusted() == true`, zero TCP/WebSocket port opened).
- Executes `DOM.enable`, `DOM.getDocument`, `DOM.getOuterHTML`, `DOM.querySelector`, `DOM.getBoxModel`, `DOM.getNodeForLocation`, `DOM.focus`, `DOM.setAttributeValue`, `Input.dispatchKeyEvent`, `Input.insertText`, `Page.enable`, `Page.getLayoutMetrics`, `Page.getNavigationHistory`, `Page.navigateToHistoryEntry`, and `Page.setDocumentContent`, delivering raw JSON results to `CefpackSession.executeDevToolsMethod` without invoking V8 or page JavaScript.

### 3.4 Multi-ABI Packaging, Delegating `R` Classes, ARMv9 MTE & 16KB Pages
- `embedder/assemble_aar.py` in `maceip/cefpack` packages:
  - `jni/arm64-v8a/libcefpack_content.so` and `jni/x86_64/libcefpack_content.so` linked with `-Wl,-z,max-page-size=16384` (`PT_LOAD p_align >= 0x4000`, `JNI_OnLoad` exported), with `v8_use_external_startup_data = false` and `enable_jni_multiplexing = false` so `org.jni_zero.GEN_JNI` and assets never collide across ABIs.
  - Non-final delegating `org.chromium.**.R` classes forwarding resource IDs at `<clinit>` time to `dev.cefpack.android.R.<type>.<name>` generated by AGP from `R.txt`.
  - Uncompressed (`ZIP_STORED`) `assets/cefpack_resources.pak` and `assets/icudtl.dat` preserved via `androidResources { noCompress += listOf("pak", "dat", "bin") }`.
  - `SandboxedProcessService0..39` (`android:isolatedProcess="true"`) and `PrivilegedProcessService0..4` (`android:isolatedProcess="false"`) with zero `useAppZygote` or `memtagMode` overrides, inheriting `<application android:memtagMode="off">` from Trap's host manifest.

---

## 4. Mandatory CI & Release Verification Gates

Every build must pass all five automated gates before release:
1. **Rust Workspace Gate (`rust-tests`)**: `cargo fmt`, `cargo clippy -D warnings`, `cargo test --workspace`, and `cargo llvm-cov`.
2. **APK Binary/DEX/ELF/Manifest Audit (`scripts/audit-apk.sh`)**:
   - Zero banned legacy engine, GMS, Firebase, or MLKit classes/strings across `android/**/*.gradle.kts`, `AndroidManifest.xml`, and `classes*.dex`.
   - Presence and `16 KB` (`0x4000`) `PT_LOAD` alignment of every `.so` in `lib/{arm64-v8a,x86_64}/` (`libcefpack_content.so`, `libonion_vault.so`, etc.).
   - `<application android:memtagMode="off">` with zero overrides plus `SandboxedProcessService0` & `PrivilegedProcessService0`.
   - Uncompressed (`Stored`) `assets/cefpack_resources.pak` and `assets/icudtl.dat`.
   - `0` browser `.so` `DT_NEEDED` entries on `libonion_vault.so`, `JNI_OnLoad` exported on `libcefpack_content.so`, and all `24/24` `Java_dev_onionvault_OvNative_*` JNI symbols exported.
3. **SBOM & Attestation (`scripts/generate-sbom.py`)**: Real SHA-256 digests for `app-debug.apk`, `cefpack-android.aar`, `libcefpack_content.so`, `libonion_vault.so`, Cargo crates, and Maven runtime artifacts in CycloneDX 1.5 and SPDX 2.3.
4. **Android 15 Emulator Gate (`scripts/emulator-gate.sh`)**: Cold launch, `Cefpack` readiness, `ov_tor` loopback SOCKS5 bind, 12s live-PID hold, zero fatal exceptions/signals/MTE faults/linkage errors, and live screenshot capture.
5. **Limrun Cloud Android 15 Gate (`scripts/limrun-gate.mjs` + `scripts/test-limrun-gate.mjs`)**: Cloud Android 15 provisioning, cold launch, Arti Tor bootstrap, live DuckDuckGo `.onion` HTTP 200 `OnLoadEnd` & `iso-duckduckgogg42xj` circuit isolation, DropBox/tombstone audit, and guaranteed instance cleanup.
