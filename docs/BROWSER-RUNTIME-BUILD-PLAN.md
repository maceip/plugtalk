# Browser Runtime Source-Build Architecture & Migration Plan

**Status:** Approved Engineering Plan (Tracked Milestone — Step 8 in [`home_run.md`](../home_run.md))  
**Date:** 2026-11-15  
**Scope:** Eliminating pre-built `com.cefrium:cefrium-sdk` Maven binaries and transitioning Trap (`dev.onionvault`) to a 100% source-built, reproducible, SLSA-attested Chromium Android runtime compiled via `siso` Remote Execution API v2 (REv2) on Limrun's build plane.

---

## 1. Executive Summary & Mandate

Trap's core value proposition—browsing `.onion` hidden services and gated clearnet origins over Arti Tor with **zero page JavaScript** while performing in-page Sequoia OpenPGP operations—depends entirely on trusting and controlling the embedded browser engine. Consuming pre-built third-party binaries from `https://maven.cefrium.com` (`com.cefrium:cefrium-sdk:0.19.0`) was necessary to recover from the initial non-booting state documented in [`INCIDENT-REPORT.md`](../INCIDENT-REPORT.md), but shipping opaque pre-compiled engine binaries is unacceptable for Trap's long-term security posture:

1. **Supply-Chain & Binary Trust:** A 291.7 MB third-party Maven AAR containing 450+ MB of native C++ machine code (`libcef.so`) and 31,398 Java-25 classes (`classes.jar`) cannot be audited for subtle proxy bypasses, telemetry beacons, or compiler backdoors without building from pinned source.
2. **Massive `//chrome/android` Bloat & Google Services Surface:** Forensic inspection (§2) proves `cefrium-sdk-0.19.0.aar` is built atop Chromium's full `//chrome/android` layer (`9,424` `org.chromium.chrome.*` classes) and declares **50 Maven dependencies** including Google Play Services (`play-services-clearcut`, `play-services-phenotype`, `play-services-gcm`, `play-services-location`, `play-services-cast`, `play-services-vision`, `play-services-identity-credentials`), Firebase (`firebase-messaging`, `firebase-datatransport`, `firebase-installations`), and MLKit GenAI (`com.google.mlkit:genai-*`). This forces Trap to strip permissions via `tools:node="remove"` in `AndroidManifest.xml` and block unsolicited background connections to `*.googleapis.com`, `*.gstatic.com`, `*.gvt1.com`, `*.pki.goog`, and `chrome.cloudflare-dns.com` inside `ov-tor`'s SOCKS5 server.
3. **Zero Need for the Desktop CEF C-API Middleman:** `libonion_vault.so` is now 100% decoupled from `libcef.so` (`0` `DT_NEEDED` dependencies on `libcef.so`, verified by `scripts/audit-apk.sh`). Trap interacts with the browser exclusively from Kotlin (`TrapApplication`, `TabSessionManager`, `CdpDomPgpController`, `OnionBackStackManager`) + browser-process Chrome DevTools Protocol (CDP) JSON. All **242** desktop CEF C-API symbols (`cef_*`) exported by `libcef.so` are dead weight.

**Architectural Decision:**
- **Primary Path (Option A — Recommended):** Build a thin, zero-patch Chromium Content Embedder AAR (`trap-chromium-content.aar` / `libtrap_chromium_content.so`) directly from pinned upstream `chromium/src` tags (`//content/public/android` + `//components/embedder_support/android` + `content::DevToolsAgentHost`), mounted via GN `root_extra_deps` with zero `//chrome` or Google Play Services code, compiled via `siso` REv2 against Limrun's Linux `x86_64` remote execution cluster (`buildplane.us-east1.limrun.net:4443`, instance `org_01m7ger979f4b98sesd3w802kv`).
- **Tactical Fallback Only (Option B):** Upstream Cefrium (`https://codeberg.org/cefrium/cef-android`, tag `v0.19.0` = `551c9dab233169c2d1b35f3a671057f85a5d9b8f`) **is** open-source (`LGPL-3.0-or-later`) on Codeberg with 44 Android patches (`patch/patches/android/*.patch`) and build scripts, so it *can* be rebuilt from source in an emergency—but it retains `//chrome/android` bloat, 50 GMS/Firebase dependencies, LGPL-3.0 copyleft obligations on SDK edits, and high rebase fragility across Chromium security updates.

---

## 2. Empirical Forensic Audit of `cefrium-sdk-0.19.0.aar` & Maven Artifacts

All figures below were measured directly from `~/.trap-toolchain/cef-android/cefrium-sdk-0.19.0.aar` and `~/.gradle/caches/modules-2/files-2.1/com.cefrium/*` (zero estimates).

### 2.1 Archive & Maven Module Structure

On `https://maven.cefrium.com`, `com.cefrium:cefrium-sdk:0.19.0` resolves either as the fat archive cached at `~/.trap-toolchain/cef-android/cefrium-sdk-0.19.0.aar` (`291,719,388` bytes compressed; `700,647,065` bytes uncompressed across `4,814` ZIP entries) or via modular POM artifacts:
- `com.cefrium:cefrium-sdk-core:0.19.0` (`aar` — Java bytecode, Android resources, `.pak` assets, manifest)
- `com.cefrium:cefrium-native-codecs-arm64-v8a:0.19.0` (`aar` — `jni/arm64-v8a/libcef.so`)
- `com.cefrium:cefrium-native-codecs-x86_64:0.19.0` (`aar` — `jni/x86_64/libcef.so`)
- `com.cefrium:cefrium-native-codecs-armeabi-v7a:0.19.0` (`aar` — `jni/armeabi-v7a/libcef.so`, excluded by Trap's `abiFilters`)
- `com.cefrium:cefrium-gradle-plugin:0.19.0` (`jar` — `com.cefrium.CefriumPlugin`, registering `GenerateCefriumRTask` to synthesize `R.java` for Chromium packages and `GenerateJavalessManifestTask`)

| AAR Entry | Uncompressed Size | ZIP Storage | Empirical Notes |
|---|---:|---|---|
| `jni/arm64-v8a/libcef.so` | `211,618,456` B (201.8 MiB) | `DEFLATED` (`comp=8`) | `ELF64` `EM_AARCH64` (`183`), `SONAME=libcef.so`, `PT_LOAD` `p_align=[0x4000, 0x4000, 0x10000]` |
| `jni/x86_64/libcef.so` | `238,750,208` B (227.7 MiB) | `DEFLATED` (`comp=8`) | `ELF64` `EM_X86_64` (`62`), `SONAME=libcef.so`, `PT_LOAD` `p_align=[0x4000, 0x4000, 0x10000]` |
| `jni/armeabi-v7a/libcef.so` | `109,000,556` B (104.0 MiB) | `DEFLATED` (`comp=8`) | 32-bit ARM; filtered out by `abiFilters += listOf("arm64-v8a", "x86_64")` |
| `classes.jar` | `120,156,336` B (114.6 MiB) | `DEFLATED` (`comp=8`) | `31,398` `.class` files (`31,203` major `69` = JDK 25; `191` major `52`; `3` major `55`; `1` major `53`) |
| `assets/icudtl.dat+com.cefrium+` | `9,282,192` B (8.85 MiB) | `STORED` (`comp=0`) | ICU Unicode tables (suffixed `+com.cefrium+` to survive AAR asset merging uncompressed) |
| `assets/resources.pak+com.cefrium+` | `1,293,429` B (1.23 MiB) | `STORED` (`comp=0`) | Blink/Content Grit resources |
| `assets/stored-locales/en-US.pak+com.cefrium+` | `354,892` B (346.6 KiB) | `STORED` (`comp=0`) | English locale pack |
| `assets/chrome_200_percent.pak+com.cefrium+` | `157,166` B (153.5 KiB) | `STORED` (`comp=0`) | HiDPI UI scale pack |
| `assets/chrome_100_percent.pak+com.cefrium+` | `91,892` B (89.7 KiB) | `STORED` (`comp=0`) | 1x UI scale pack |
| `R.txt` / `res/**` | `1,254,362` B + ~4,800 XML/PNGs | `DEFLATED` (`comp=8`) | Full Chrome Android UI resources (Cast, Autofill, CVC icons, XR toolbar, tab strip, reader mode) |
| `proguard.txt` | `42,568` B | `DEFLATED` (`comp=8`) | `@UsedByReflection`, `@DoNotInline`, `@CalledByNative`, `org.jni_zero` keep rules |
| `AndroidManifest.xml` | `17,927` B | `DEFLATED` (`comp=8`) | `ZygotePreload`, `CefriumInitProvider`, 40 sandboxed + 3 privileged services |

### 2.2 Native `libcef.so` Binary & Symbol Audit

- **Embedded Chromium Version:** `Chrome/156.0.8078.80` (exact `VERSION.stamp` in source tag `v0.19.0`: `156.0.0-android-port.4474+gd81dfbe+chromium-156.0.8078.80`).
- **Dynamic Library Dependencies (`DT_NEEDED`, 10 NDK system libraries):**
  `libdl.so`, `libm.so`, `libandroid.so`, `liblog.so`, `libbinder_ndk.so`, `libjnigraphics.so`, `libaaudio.so`, `libmediandk.so`, `libEGL.so`, `libc.so`.
- **Exported Dynamic Symbols (`.dynsym` = `3,798` defined symbols on both `arm64-v8a` and `x86_64`):**
  - `242` `cef_*` symbols (the desktop CEF C API — **0 of 242** are called by Trap).
  - `109` `Java_com_cefrium_*` JNI methods (`Cefrium`, `CefriumBrowser`, `CefriumProfile`, `CefAndroidProtocolHandler`, etc.).
  - `3,445` `Java_org_jni_1zero_GEN_1JNI_*` / `Java_J_N_*` Chromium JNI registration entry points.
  - `2` standard JNI lifecycle symbols (`JNI_OnLoad`, etc.).
- **Compiled-In Feature Strings Verified in `.rodata`:** `PartitionAllocMemoryTagging` (default-enabled on ARMv9 `FEAT_MTE`, necessitating Trap's `android:memtagMode="off"` + `--disable-features=PartitionAllocMemoryTagging` mitigation in `06796bf`), `host-resolver-rules`, and `DevToolsAgentHost`.

### 2.3 Java Package & Transitive Google Dependency Bloat (`classes.jar` + POM)

Scanning all `31,398` `.class` entries in `classes.jar` exposes why `cefrium-sdk` is so large and invasive:
- `org/chromium/chrome/**`: **9,424 classes** (full Chrome browser UI, omnibox, sync, tab strip, payments, Glic/AI stubs)
- `org/chromium/components/**`: **6,675 classes**
- `org/chromium/blink/**`: **5,669 classes** (Mojo bindings)
- `org/chromium/network/**`: **2,391 classes**
- `org/chromium/media/**` + `media_session/**`: **1,668 classes**
- `org/chromium/device/**`: **1,078 classes** (Bluetooth, USB, Serial, NFC, Gamepad, Geolocation)
- `org/chromium/ui/**` (`668`), `org/chromium/base/**` (`469`), `org/chromium/content/**` (`417`), `org/chromium/viz/**` (`284`), `org/chromium/on_device_model/**` (`260`), `org/chromium/payments/**` (`208`), `com/google/ar/**` (`178`), `com/cefrium/**` (`168`), `org/chromium/content_public/**` (`165`), `com/google/cardboard/**` (`44`).

Furthermore, `cefrium-maven-deps.txt` / `cefrium-sdk-core-0.19.0.pom` pulls **50 dependencies**, including `com.google.android.gms:play-services-{clearcut,phenotype,gcm,cloud-messaging,iid,location,cast,vision,identity-credentials,auth-api-phone}`, `com.google.firebase:firebase-{messaging,datatransport,installations,common}`, and `com.google.mlkit:genai-{common,prompt,schema}`, plus `AndroidManifest.xml` `<queries>` declarations (`VIEW`, `DIAL`, `SENDTO`, `SEND`) and `CREDENTIAL_MANAGER_*` / `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions. None of these belong in a hardened Tor/OpenPGP browser.

---

## 3. Exact Browser Engine Surface Required by Trap

Inspecting [`TrapApplication.kt`](../android/app/src/main/kotlin/dev/onionvault/TrapApplication.kt), [`TabSessionManager.kt`](../android/app/src/main/kotlin/dev/onionvault/TabSessionManager.kt), [`CdpDomPgpController.kt`](../android/app/src/main/kotlin/dev/onionvault/CdpDomPgpController.kt), and [`OnionBackStackManager.kt`](../android/app/src/main/kotlin/dev/onionvault/OnionBackStackManager.kt) proves Trap uses a remarkably narrow, well-defined browser surface:

### 3.1 SOCKS5 Proxy & Zero Clearnet / DNS / WebRTC / UDP Leak
1. **Fail-Closed Startup & Hot-Swappable SOCKS5h Proxy:**
   - Must boot with `socks5://127.0.0.1:1` (fail-closed loopback blackhole) before any tab exists, then atomically hot-swap the active `NetworkContext` proxy to `socks5://127.0.0.1:<port>` once Arti binds its local SOCKS5 listener (`TrapApplication.applyProfileSocksServer`).
   - **Critical `//content` Engineering Requirement:** Unlike `//chrome/browser` (`ProxyConfigMonitor`), upstream `//content` (`ShellContentBrowserClient::ConfigureNetworkContextParams`) **ignores `--proxy-server` on the command line**. In Option A, `TrapContentBrowserClient::ConfigureNetworkContextParams` MUST populate `network::mojom::NetworkContextParams::initial_proxy_config` (`socks5://127.0.0.1:1`) AND bind `network_context_params->proxy_config_client_receiver` (`mojo::Remote<network::mojom::ProxyConfigClient>`) so `OnProxyConfigUpdated(...)` pushes `socks5://127.0.0.1:<arti_port>` directly into the `NetworkService` without a single unproxied packet.
2. **Zero OS DNS / DoH / Background Ping Leaks:**
   - `--host-resolver-rules="MAP * ~NOTFOUND , EXCLUDE 127.0.0.1"` (honored directly by `services/network/network_service.cc`, guaranteeing OS `getaddrinfo()` fails closed for every non-loopback hostname while SOCKS5h forwards `.onion` and clearnet hostnames inside the Tor circuit).
   - `--dns-prefetch-disable`, `--no-pings`, `--disable-background-networking`, Secure DNS (`DoH`) disabled (`DnsConfigOverrides` `secure_dns_mode = kOff`).
   - **WebRTC Eliminated at Compile Time:** Setting `enable_webrtc = false` in `args.gn` compiles out `//third_party/webrtc` and `RTCPeerConnection` completely—stronger than Cefrium's runtime `WEBRTC_DISABLE_NON_PROXIED_UDP` preference.

### 3.2 Hard Zero-JavaScript & Zero-WebAssembly Enforcement
- **Blink `WebPreferences` Lock (`C++`):** Override `ContentBrowserClient::OverrideWebkitPrefs(WebContents*, blink::web_pref::WebPreferences* prefs)` in C++ to unconditionally enforce:
  - `prefs->javascript_enabled = false;`
  - `prefs->javascript_can_access_clipboard = false;`
  - `prefs->allow_scripts_to_close_windows = false;`
  - `prefs->hyperlink_auditing_enabled = false;`
  - `prefs->webgl1_enabled = false; prefs->webgl2_enabled = false;`
- **V8 Process-Level Hardening:** Pass `--js-flags="--jitless --no-expose-wasm"` to renderer processes via `AppendExtraCommandLineSwitches` so V8 never allocates RWX/executable JIT pages and `WebAssembly` is non-existent even internally.
- **CDP & Network Belt-and-Suspenders:** Keep `Emulation.setScriptExecutionDisabled({"value": true})` and drop `blink::mojom::ResourceType::kScript`, `kPing`, and `kCspReport` at the `URLLoaderThrottle` layer. (Note: `TabSessionManager.kt:539` currently had to work around `CefriumBrowser.setPinchToZoomEnabled(false)` injecting JavaScript; native `WebPreferences` has no such JS hack.)

### 3.3 Browser-Process CDP `DevToolsAgentHost` Transport (With Page JS Disabled)
`CdpDomPgpController.kt` and `TabSessionManager.kt` require an in-process JSON message channel backed by `content::DevToolsAgentHost::GetOrCreateFor(WebContents*)` and `content::DevToolsAgentHostClient` (`IsTrusted() == true`, zero TCP/WebSocket port opened):
- **Events consumed:** `DOM.documentUpdated`, `Page.loadEventFired`, `Page.frameStoppedLoading`.
- **Commands executed:**
  - `Page.enable`, `DOM.enable`, `Emulation.setScriptExecutionDisabled({"value": true})`
  - `DOM.getDocument({"depth": -1, "pierce": true})` — traverses Blink's C++ user-agent `#shadow-root` (`<div id="inner-editor">`) inside `<textarea>` and `<input>` to read/verify live user text with zero JavaScript execution
  - `DOM.getOuterHTML({"nodeId": ...})`
  - `Page.getLayoutMetrics({})` (`cssVisualViewport` / `cssLayoutViewport`)
  - `DOM.getBoxModel({"backendNodeId": ...})` (viewport-normalized chip coordinates)
  - `DOM.setAttributeValue({"nodeId": ..., "name": "style", "value": ...})` (zero-JS PGP field outline)
  - `DOM.focus({"backendNodeId": ...})`, `Input.dispatchKeyEvent` (`SelectAll`, `MoveToBeginningOfDocument`, `MoveToEndOfDocumentAndModifySelection`, `DeleteBackward`), `Input.insertText({"text": ...})` (zero-JS encrypted ciphertext / public-key insertion into form controls)
  - `Page.getFrameTree` + `Page.setDocumentContent` (`@VisibleForTesting` zero-JS instrumentation fixture loader)

All 12 CDP operations execute in Chromium's C++ `InspectorDOMAgent`, `InspectorPageAgent`, `InspectorEmulationAgent`, and browser `InputHandler`—none invoke V8 or page scripts.

### 3.4 View Compositing, Interception, Ephemeral Profile, ARMv9 MTE & 16KB Pages
- **Compose Hosting & Predictive-Back Snapshots:** `ContentViewRenderView` (`SurfaceView`) + `ContentView` hosted inside `TouchGateFrameLayout` (`AndroidView`), plus `RenderWidgetHostView::CopyFromSurface` / `PixelCopy` for `OnionBackStackManager.capture`.
- **Navigation & Subresource Interception:** `content::NavigationThrottle` (`WillStartRequest`, `WillRedirectRequest`) + `content::URLLoaderThrottle` enforcing `OvNative.nativeCheckUrlPolicy` (`.onion` routing, clearnet 3-day consent gate, scheme allowlist) and popup suppression (`WebContentsDelegate::CanCreateWindow`).
- **Context Menu & Text Selection Hardening:** `WebContentsDelegateAndroid::HandleContextMenu(RenderFrameHost&, const ContextMenuParams&)` delivering structured `link_url`, `src_url`, `media_type`, and `selection_text` directly to Kotlin, plus `SelectionPopupController.setActionModeCallback` stripping `WEB_SEARCH`, `SHARE`, and `ACTION_PROCESS_TEXT` while injecting Trap's OpenPGP `Decrypt` / `Import Key` action.
- **Ephemeral In-Memory Profile (`New Identity`):** Off-the-record `BrowserContext` (`in_memory = true` `StoragePartition`, zero SQLite/disk cache writes) that can be destroyed and recreated cleanly alongside `RenderProcessHost::Cleanup` when rotating Arti Tor stream isolation tokens.
- **ARMv9 `FEAT_MTE` & Android 15/16 16KB Page Compliance:** `<application android:memtagMode="off">` with zero `<service>` manifest overrides, PartitionAlloc MTE memory tagging disabled at compile time and runtime (preventing `libc: SetHeapTaggingLevel: set_tcf_on_all_threads failed` / `SIGTRAP` `brk #0`), and `-Wl,-z,max-page-size=16384` (`PT_LOAD p_align >= 0x4000`) on `arm64-v8a` and `x86_64`.

---

## 4. Forensic Post-Mortem: Why Commit `0653953` (`crates/cefpack`) Failed

Commit `0653953dec5a4d336eb9293ca18e019312e9d6ee` (*"Replace the Cefrium dependency with a CEF bundling service for Android"*, reverted in `d43eb00`) added 42,098 lines across `crates/cefpack/` and `third_party/cef-dll-sys/` claiming to build upstream `chromiumembedded/cef` (`6028375a4532fb89d23b58635f5c0d3a82c93959`) for Android. It was never executed against a Chromium tree and fails unconditionally at five independent layers:

1. **Immediate `gn gen` Fatal Error on Read-Only & Non-Existent GN Variables (`crates/cefpack/src/gn.rs:21-40`):**
   - `cefpack` wrote `is_android=true` into `args.gn`. In Chromium's `//build/config/BUILDCONFIG.gn`, `is_android` is a computed variable derived from `target_os == "android"`; passing `is_android=true` in `args.gn` aborts `gn gen` immediately with `ERROR: Attempting to reassign read-only variable "is_android"`.
   - `cefpack` also passed `use_cef=true`, `is_client=false`, and `is_server=false`—none of which exist in upstream Chromium or `chromiumembedded/cef`.
2. **Upstream `chromiumembedded/cef/BUILD.gn` Hard Desktop `assert()` Gates:**
   - Upstream `//cef/BUILD.gn` enforces `assert(enable_print_preview)` (line 258), `assert(enable_widevine)` (line 261), and `assert(toolkit_views)` (line 272). On `target_os = "android"`, Chromium sets `toolkit_views = false` (Android uses `ui/android` `WindowAndroid`, not desktop Aura/Views) and `enable_print_preview = false`.
   - Upstream `//cef/tools/gn_args.py` (lines 77–89) recognizes only `windows`, `mac`, and `linux` (`Unknown operating system platform` otherwise).
3. **Host-Platform Header Pollution (`tools/make_config_header.py:24-26`):**
   - `cefpack` ran `tools/make_config_header.py` on Linux without `ozone_platform_x11=false`. Because `make_config_header.py` checks `sys.platform.startswith('linux')`, it emitted `#define CEF_X11 1` into `include/cef_config.h`, injecting X11 `XEvent*` / `Window` signatures into Android bindings.
4. **Zero Android Browser/Process Implementation in Upstream `chromiumembedded/cef`:**
   - Upstream `chromiumembedded/cef` has no `libcef/browser/android/`, no `OS_ANDROID` `CefWindowInfo` (`ANativeWindow` / `WindowAndroid`), and no `JNI_OnLoad` `ContentMainDelegate`. Making CEF boot on Android required Cefrium to author 44 separate Chromium patches (`patch/patches/android/*.patch`) plus an entire Java/JNI layer.
5. **Missing Chromium Java/DEX Layer & Re-Introduced `DT_NEEDED: libcef.so`:**
   - `crates/cefpack/src/build.rs:76-82` invoked `ninja -C out libcef libcef_dll_wrapper`, producing **zero** `org.chromium.base.*`, `org.chromium.content.*`, or `org.jni_zero.*` DEX classes and **zero** `:sandboxed_process*` / `:privileged_process*` Android `<service>` manifest entries. Even with a valid `.so`, Chromium aborts in `base::android::InitVM` before the first frame when its Java half is absent (`INCIDENT-REPORT.md` §2.1).
   - Worse, `0653953` re-linked `libonion_vault.so` directly to `libcef.so` (`35,925` lines of desktop C-API Rust bindings) and attempted OSR `external_message_pump = 1` against Android's `Looper`.

**Rule Going Forward:** Never attempt to build upstream `chromiumembedded/cef` for Android, and never link `libonion_vault.so` to any browser `.so`.

---

## 5. Empirical Comparison of Source-Owned Engine Options

### 5.1 Option A (Recommended Primary Path): Direct Source-Built Chromium `//content/public/android` AAR (`trap-chromium-content.aar`)

Instead of routing Kotlin -> Cefrium Java -> Cefrium JNI -> CEF C++ -> CEF C-API -> `//chrome/android` -> `//content`, Trap builds a minimal first-party Chromium Content embedder (`//trap_webview/`) directly atop upstream `chromium/src`:

```
┌────────────────────────────── Trap APK (dev.onionvault) ──────────────────────────────┐
│  Kotlin UI & Session Layer (TrapApplication, TabSessionManager, CdpDomPgpController)  │
│           │                                               │                           │
│           │ OvNative JNI (24 symbols)                     │ BrowserEngine Kotlin API  │
│           ▼                                               ▼                           │
│  libonion_vault.so (Rust, cargo-ndk)        trap-chromium-content.aar (Source-Built)  │
│   • ov-tor (Arti + SOCKS5h + ClearnetGate)   • org.chromium.{base,content_public,ui}  │
│   • ov-pgp (Sequoia + Android Keystore)      • ContentViewRenderView + ContentView    │
│   • ov-cef (Pure HTML/CDP DOM classifier)    • libtrap_chromium_content.so:           │
│   • 0 browser .so dependencies                 - TrapContentMainDelegate              │
│                                                - TrapContentBrowserClient             │
│                                                  (ProxyConfigClient Mojo + JS=false)  │
│                                                - TrapDevToolsBridge                   │
│                                                  (DevToolsAgentHostClient JSON)       │
│                                                - TrapNavigationThrottle               │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

**Why Option A Wins Every Engineering Metric:**
1. **Zero Upstream Chromium Patches (`0` `.patch` files to rebase):** Mounted into a clean upstream `chromium/src` checkout via GN's built-in `root_extra_deps = [ "//trap_webview:trap_chromium_content_aar" ]` in `args.gn`. Updating to a new Chromium security release is `git checkout <new-tag> && gclient sync` with zero patch-conflict churn.
2. **Eliminates 100% of `//chrome/android` & Google Play Services:** Depends only on `//content/public/android:content_java`, `//components/embedder_support/android:content_view_java`, `//components/embedder_support/android:view_java`, `//base:base_java`, `//ui/android:ui_java`, `//net/android:net_java`, and `//third_party/jni_zero:jni_zero_java`. Drops all `9,424` `org.chromium.chrome.*` classes and all `50` Play Services / Firebase / Clearcut / MLKit / ARCore / CredMan / PaymentRequest dependencies.
3. **Permissive BSD-3-Clause Licensing:** Upstream `//content`, `//base`, `//net`, `//components/embedder_support`, Blink, and V8 are BSD-3-Clause / MIT (with `proprietary_codecs = false`), removing Cefrium's `LGPL-3.0-or-later` / commercial dual-licensing complexity (`LICENSE-CEFRIUM.md`).
4. **Dramatically Smaller Artifact & Faster Remote Build:**
   - Java bytecode shrinks from `31,398` classes (`120.2 MB` `classes.jar`) to ~`3,500` classes (~`10 MB` `classes.jar`), removing the 23-DEX sprawl.
   - Native `.so` shrinks from `211.6 MB` (`arm64-v8a`) / `238.8 MB` (`x86_64`) to ~`95–120 MB` per ABI and compiles ~45% fewer Ninja/Siso actions than `//chrome/android`.

### 5.2 Option B (Audited Fallback Only): Rebuilding `cefrium/cef-android` from Codeberg Source

**Exact Empirical Upstream Finding:**
- Querying GitHub (`gh api search/repositories -f q=cefrium` and `users/cefrium`) returns **HTTP 404**—Cefrium is not hosted on GitHub.
- Inspecting `<scm><url>` inside `~/.gradle/caches/.../cefrium-sdk-0.19.0.pom` revealed **`https://codeberg.org/cefrium/cef-android`**, which **is** publicly hosted and auditable (`git ls-remote https://codeberg.org/cefrium/cef-android.git` verified tag `v0.19.0` -> `551c9dab233169c2d1b35f3a671057f85a5d9b8f`, up through `v0.21.0` -> `6de42c55dadc877ef8c1faf5152c840df774251c`, default branch `android-port`).
- The repository contains `BUILD-GUIDE-CEFRIUM.md`, `SCRIPTS-CEFRIUM.md`, `cefrium_sdk/BUILD.gn`, `cefrium_sdk/apply-patches.py`, `cefrium_sdk/build-all-variants.sh`, `cefrium_sdk/slim_aar.py`, core CEF patches, and **44 Android-specific Chromium patches** under `patch/patches/android/*.patch` (`multi_profile.patch`, `child_service_names.patch`, `push_lost_subscriptions.patch`, `webauthn_credman_*.patch`, etc.).
- Licensing (`LICENSE-CEFRIUM.md`): Upstream CEF/Android platform patches are BSD-3-Clause, while `cefrium_sdk/` (Java API, JNI bridge, Gradle plugin) is dual-licensed under **`LGPL-3.0-or-later`** or a paid commercial license (`cefrium@proton.me`).

**Why Option B Is Rejected as the Target Architecture:**
1. Still compiles `//chrome/android` and vendors Play Services / Firebase / MLKit / CredMan / PaymentRequest hooks into the engine.
2. Requires maintaining and auditing 44 out-of-tree patches against `//chrome` and `//content` (maintained upstream by a single developer since May 2026) on every Chromium CVE bump.
3. Any security hardening edits to `cefrium_sdk/` fall under `LGPL-3.0-or-later` copyleft installation-information requirements.
4. Retains the `com.cefrium` Gradle `R.java` synthesis plugin (`GenerateCefriumRTask`) and Java-25 `classes.jar` bloat.

| Dimension | Pre-Built `cefrium-sdk:0.19.0` (Current) | Option B: Source-Built `cefrium/cef-android` | Option A: Source-Built `trap-chromium-content.aar` (Target) |
|---|---|---|---|
| Binary Provenance | Third-party Maven binary (`maven.cefrium.com`) | First-party `siso` REv2 build + SLSA v1 | **First-party `siso` REv2 build + SLSA v1** |
| Upstream Source | Opaque AAR until audited against Codeberg | `chromium/src` + `codeberg.org/cefrium/cef-android` | **Upstream `chromium/src` release tag only** |
| Out-of-Tree Chromium Patches | 44 Android + ~90 CEF patches | 44 Android + ~90 CEF patches | **0 patches (`root_extra_deps = ["//trap_webview"]`)** |
| `org.chromium.chrome.*` / GMS / Firebase | 9,424 Chrome classes + 50 Maven deps | 9,424 Chrome classes + 50 Maven deps | **0 Chrome classes, 0 GMS/Firebase/MLKit deps** |
| Unsolicited Google Background Pings | Present (blocked at `ov-tor` SOCKS5) | Present unless patched out | **Compiled out (`//content` has no GMS/UMA/Autofill)** |
| CEF C-API Overhead (`cef_*`) | 242 unused symbols | 242 unused symbols | **0 (direct Kotlin <-> `//content` JNI)** |
| SDK License | LGPL-3.0-or-later | LGPL-3.0-or-later | **BSD-3-Clause (Chromium) + MIT/Apache-2.0 (Trap)** |

---

## 6. Siso Remote Execution v2 (`REv2`) Architecture on Limrun Buildplane

Building Chromium for two Android ABIs (`arm64-v8a` + `x86_64`) compiles ~28,000–35,000 C++/Rust/Java/Blink/V8 actions (`//content` target graph). Running locally on a single developer laptop is impractical; Chromium's native Go build system **`siso`** (`go.chromium.org/build/siso`, bundled in `depot_tools`) natively speaks the **Bazel Remote Execution API v2 (REAPI v2)** used by Limrun's build plane.

### 6.1 Empirical Verification of Siso <-> Limrun Buildplane Protocol Compatibility

Cross-referencing Chromium's upstream `siso/reapi/reapi.go`, `siso/auth/cred/credhelper.go`, `chromium/src/build/config/siso/main.star`, and Limrun's `lim` v0.57.3 setup (`bazel-setup.js`):

1. **Endpoint Format (`reapi_address` — CRITICAL `grpcs://` Catch):**
   - Bazel uses `grpcs://buildplane.us-east1.limrun.net:4443`.
   - In `siso/reapi/reapi.go` (lines 528–531):
     ```go
     endpoint := addr
     if !strings.Contains(addr, "://") {
         endpoint = "dns:///" + addr
     }
     ```
     `siso` uses TLS by default (`opt.Insecure == false`), and `grpc-go` does **not** register a `grpcs://` resolver scheme! Passing `grpcs://...` causes `siso` to fail dialing. `siso` MUST be given bare `host:port`:
     - **`reapi_address = "buildplane.us-east1.limrun.net:4443"`**
     - **`reapi_instance = "org_01m7ger979f4b98sesd3w802kv"`**
2. **Authentication (`SISO_CREDENTIAL_HELPER` <-> `lim-bazel-credentials`):**
   - For non-`*.googleapis.com` endpoints, `siso/reapi/reapi.go:536` disables Google ADC and delegates to `SISO_CREDENTIAL_HELPER`.
   - `siso/auth/cred/credhelper.go:52-133` implements the exact [EngFlow / Bazel Credential Helper Spec](https://github.com/EngFlow/credential-helper-spec/blob/main/spec.md): it invokes `<helper> get` with `{"uri": "https://buildplane.us-east1.limrun.net:4443/..."}` on `stdin` and parses RFC3339 `expires` + `headers.Authorization` (`["Bearer <token>"]`) on `stdout`.
   - Limrun's `lim-bazel-credentials` (`/Users/mac/.nvm/versions/node/v24.15.0/bin/lim-bazel-credentials`, backed by `bazelCredentials()` in `lim/dist/lib/bazel-setup.js:40-46`) mints 3600-second `Bearer` tokens with `["remotecache:*:all", "remoteexecution:*:all"]` and prints `{ headers: { Authorization: ["Bearer ..."] }, expires: token.expiresAt }`—100% wire-compatible with `SISO_CREDENTIAL_HELPER` with **zero secrets written to `.gclient`, `.sisoenv`, or `args.gn`**.
3. **Worker Platform Properties, Container & Hermetic Sysroot Requirements (`backend.star`):**
   - Upstream Chromium's default `build/config/siso/backend_config/google.star` emits Google RBE properties (`"OSFamily": "Linux"`, `"container-image": "docker://gcr.io/chops-public-images-prod/rbe/siso-chromium/linux@..."`, `"label:action_default": "1"`), which Limrun's scheduler rejects or misses.
   - As verified in `lim/dist/lib/bazel-setup.js:279-282`, Limrun's Linux `x86_64` build workers route actions via lowercase `"OSFamily": "linux"` and `"limrun-toolchains": "jdk-17-21-25-clang18-v2"`, carrying Linux `x86_64` `glibc` / `libstdc++` (`/usr/lib/gcc/x86_64-linux-gnu/13`), `/usr/bin/clang` 18, and JDKs at `/opt/java/{17,21,25}` (and **no** system `/usr/bin/python3`—see `bazel-setup.js:216`).
   - **Why Chromium's Siso Starlark Rules Match Limrun's Linux Workers:**
     - **Hermetic Python:** `chromium/src/build/config/siso/main.star:26-31` + `platform.star:13` automatically rewrite remote Python actions to `../../third_party/cpython3/linux-amd64/bin/python3` and include `third_party/cpython3/linux-amd64:cpython3` in the CAS input tree—so remote actions never call `/usr/bin/python3` on the worker!
     - **Hermetic Clang, Rust, JDK 25 & Android NDK Sysroot:** When building from a **Linux `x86_64` orchestrator** (CI runner or Linux build container), `depot_tools` fetches Linux `x86_64` `third_party/llvm-build/Release+Asserts/bin/clang++`, `third_party/rust-toolchain`, `third_party/jdk/current` (JDK 25), and `third_party/android_toolchain/ndk` (Bionic sysroot), and `siso` uploads those exact hermetic binaries + headers in the action's CAS Merkle tree (`use_sysroot = false` for Android target actions because Android links against the bundled NDK Bionic sysroot; host-tool sub-builds use `third_party/linux_chroot` Debian Bullseye/Bookworm sysroot with `use_sysroot = true`).
     - **macOS Client Caveat:** Unlike Bazel toolchains, Chromium's `fetch android` downloads *host-OS* toolchains into `third_party/llvm-build` and `third_party/jdk/current` (`darwin-arm64` Mach-O on macOS), which cannot execute on Linux `x86_64` RBE workers. Therefore, the `siso` orchestrator driving Limrun REv2 MUST run inside a **Linux `x86_64` environment** (GitHub Actions `ubuntu-26.04` / Linux VM / Docker container on macOS) so host tools (`v8_context_snapshot_generator`, `protoc`, `flatc`, `jni_zero`, `clang++`) are ELF64 `x86_64` binaries matching Limrun's Linux workers.

### 6.2 Exact `.gclient`, `backend.star`, and `.sisorc` Configuration

1. **`trap_limrun_backend.star`** (referenced via `reapi_backend_config_path` in `.gclient`):
   ```python
   # -*- bazel-starlark -*-
   load("@builtin//struct.star", "module")

   def __platform_properties(ctx):
       limrun_linux = {
           "OSFamily": "linux",
           "limrun-toolchains": "jdk-17-21-25-clang18-v2",
       }
       return {
           "default": limrun_linux,
           "large": limrun_linux,
       }

   def __configs(ctx):
       return []

   backend = module(
       "backend",
       platform_properties = __platform_properties,
       configs = __configs,
   )
   ```
2. **`.gclient` `custom_vars`:**
   ```python
   solutions = [
     {
       "name": "src",
       "url": "https://chromium.googlesource.com/chromium/src.git",
       "managed": False,
       "custom_vars": {
         "checkout_android": True,
         "checkout_pgo_profiles": False,
         "reapi_address": "buildplane.us-east1.limrun.net:4443",
         "reapi_instance": "org_01m7ger979f4b98sesd3w802kv",
         "reapi_backend_config_path": "/abs/path/to/trap_limrun_backend.star",
       },
     },
   ]
   target_os = ["android"]
   ```
3. **`build/config/siso/.sisorc` & Environment:**
   ```bash
   # Credential helper wrapper invoking `lim-bazel-credentials` (reads LIM_API_KEY from env, never logged)
   export SISO_CREDENTIAL_HELPER="/abs/path/to/scripts/chromium/limrun-siso-credhelper.sh"

   # build/config/siso/.sisorc
   ninja --reapi_compress_blob=true --reapi_enable_grpc_compression=true --reapi_keep_exec_stream
   ```

---

## 7. Step-by-Step Phased Implementation & Verification Gate

No phase advances until its empirical exit criteria pass. Pre-built `com.cefrium:cefrium-sdk:0.19.0` remains active on `main` until Phase 3 passes every CI, MTE, and live `.onion` gate.

### Phase 0: Kotlin Engine Abstraction Seam (1 Day — Zero Behavior Change)
1. Extract `dev.onionvault.engine.BrowserEngine`, `BrowserProfile`, and `BrowserSession` interfaces in `android/app/src/main/kotlin/dev/onionvault/engine/` covering the exact surface from §3:
   - `initialize(context)` / `setProfileSocksProxy(socksUrl)` / `resetEphemeralProfile()`
   - `createSession(activity): BrowserSession` (`view`, `loadUrl`, `reload`, `stopLoading`, `canGoBack`, `goBack`, `canGoForward`, `goForward`, `setZoomLevel`, `captureBitmap`, `executeDevToolsMethod`, `setDevToolsEventListener`, `reapUnusedRenderers`, `close`)
   - Typed callbacks for `RequestInterceptor`, `ContextMenuTarget`, `ActionMode.Callback`, `NavigationState`, and `PermissionDenier`.
2. Wrap existing `Cefrium` / `CefriumProfile` / `CefriumBrowser` inside `CefriumEngineAdapter` implementing `BrowserEngine`.
3. **Exit Gate:** `cargo test --workspace`, `./gradlew :app:testDebugUnitTest :app:assembleDebug`, `./scripts/audit-apk.sh`, and `limrun-device-gate` pass 100% unchanged.

### Phase 1: Verify `siso` REv2 Execution & Cache on Limrun Buildplane (1–2 Days)
1. Provision a Linux `x86_64` build environment with `depot_tools`, `lim` (`lim-bazel-credentials`), and `SISO_CREDENTIAL_HELPER` wired to `buildplane.us-east1.limrun.net:4443` (`instanceName = org_01m7ger979f4b98sesd3w802kv`).
2. **Progressive Smoke Tests (What to Test First — Never Start with Full Blink!):**
   - **Test 1A (Auth & CAS Handshake):** Run `siso version` and a 1-target C compile (`autoninja -C out/limrun_arm64 third_party/zlib:zlib`) with `use_remoteexec = true` and `use_siso = true`. Inspect `out/limrun_arm64/siso_metrics.json` and `siso_output` to verify `remote: >0`, `fallback: 0`, `err: 0`.
   - **Test 1B (Hermetic Python + `jni_zero` Codegen on Worker):** Run `autoninja -C out/limrun_arm64 base:base_jni` to prove `third_party/cpython3/linux-amd64/bin/python3` executes cleanly inside Limrun's worker sandbox without requiring `/usr/bin/python3`.
   - **Test 1C (Host x64 Tool + Target ARM64 Link + JDK 25 Javac/Turbine):** Run `autoninja -C out/limrun_arm64 base:base_java v8:v8_context_snapshot` to verify host `x86_64` snapshot generation, `turbine.jar` / `javac` JDK 25 actions, and `lld` 16KB-aligned linking.
   - **Test 1D (Remote Cache Hit Verification):** `rm -rf out/limrun_arm64 && gn gen out/limrun_arm64 && autoninja -C out/limrun_arm64 base:base_java v8:v8_context_snapshot` -> assert **>= 95% `cache_hit`** in `siso_metrics.json`.

### Phase 2: Pinned Chromium Tag, Hermetic `args.gn`, & `//trap_webview` Content Embedder (4–6 Days)
1. **Pin Exact Upstream Chromium Stable Release Tag** (e.g., `refs/tags/<major>.0.<build>.<patch>`, verified by tag commit SHA in `manifest/chromium-runtime.lock.json`).
2. **Hermetic Security-Hardened `args.gn` (`arm64` and `x64`):**
   ```gn
   target_os = "android"
   target_cpu = "arm64"                      # and "x64" for second ABI slice
   is_official_build = true
   is_debug = false
   is_component_build = false
   is_clang = true
   use_siso = true
   use_remoteexec = true
   symbol_level = 0
   blink_symbol_level = 0
   chrome_pgo_phase = 0                      # deterministic hermetic build without external profdata
   clang_use_chrome_plugins = false
   treat_warnings_as_errors = true

   # Zero WebRTC / Zero Proprietary Codec / Zero Google Telemetry & Field-Trial Surface
   enable_webrtc = false
   proprietary_codecs = false
   ffmpeg_branding = "Chromium"
   disable_fieldtrial_testing_config = true
   enable_reporting = false
   enable_vr = false
   enable_arcore = false
   enable_openxr = false
   enable_cardboard = false
   enable_remoting = false
   enable_printing = false
   safe_browsing_mode = 0
   google_api_key = ""
   google_default_client_id = ""
   google_default_client_secret = ""

   # Android SDK / 16KB Page Alignment / JNI Determinism
   default_min_sdk_version = 29
   enable_jni_multiplexing = false
   use_hashed_jni_names = false

   # Mount Trap's zero-patch Content embedder target
   root_extra_deps = [ "//trap_webview:trap_chromium_content_aar" ]
   ```
   *(Verify via `llvm-readelf -l` that `libtrap_chromium_content.so` carries `-Wl,-z,max-page-size=16384` / `PT_LOAD p_align >= 0x4000` on both `arm64-v8a` and `x86_64`, and disable `PartitionAllocMemoryTagging` in `TrapContentMainDelegate::BasicStartupComplete` so ARMv9 `FEAT_MTE` devices never call `SetHeapTaggingLevel`.)*
3. **Author `//trap_webview` (~600 LoC C++ + ~500 LoC Java/Kotlin + `AndroidManifest.xml`):**
   - `TrapContentMainDelegate` & `TrapContentBrowserClient`:
     - Enforces `--host-resolver-rules="MAP * ~NOTFOUND , EXCLUDE 127.0.0.1"`, `--dns-prefetch-disable`, `--no-pings`, `--disable-background-networking`, and renderer `--js-flags="--jitless --no-expose-wasm"`.
     - Overrides `OverrideWebkitPrefs` to lock `javascript_enabled = false`, `webgl1_enabled = false`, `webgl2_enabled = false`, `hyperlink_auditing_enabled = false`.
     - Implements `ConfigureNetworkContextParams` with `initial_proxy_config = socks5://127.0.0.1:1` and `mojo::Remote<network::mojom::ProxyConfigClient>` for atomic `updateSocksProxy("socks5://127.0.0.1:<port>")` updates and `SecureDnsMode::kOff`.
     - Registers `TrapNavigationThrottle` + `TrapURLLoaderThrottle` (dropping `kScript`, `kPing`, `kCspReport` and delegating URL policy checks to Kotlin).
   - `TrapDevToolsBridge` (`content::DevToolsAgentHostClient`):
     - Attaches in-process via `DevToolsAgentHost::GetOrCreateFor(web_contents)->AttachClient(this)` (`IsTrusted() -> true`), dispatching raw CDP JSON commands (`DOM.*`, `Page.*`, `Input.*`, `Emulation.*`) and forwarding CDP events to Kotlin.
   - `AndroidManifest.xml` & `dist_aar("trap_chromium_content_aar")`:
     - Declares only `INTERNET` and `ACCESS_NETWORK_STATE`, `ZygotePreload`, `20` `:sandboxed_process0..19` (`android:isolatedProcess="true"`) + `2` `:privileged_process0..1` services (zero `memtagMode` overrides), uncompressed `.pak` + `icudtl.dat` assets, and `lib/{arm64-v8a,x86_64}/libtrap_chromium_content.so`.

### Phase 3: Reproducible AAR, SBOM + SLSA Provenance, Drop-In Swap & Device Gates (2–3 Days)
1. **Reproducible Packaging, SBOM & SLSA v1 Attestation:**
   - Normalize AAR ZIP entry ordering, permissions, and `SOURCE_DATE_EPOCH` timestamps; publish `trap-chromium-content-<chromium_ver>-<trap_rev>.aar` to `github.com/maceip/trap` Releases (or GitHub Packages) with `SHA256SUMS.txt`, CycloneDX 1.5 / SPDX 2.3 SBOM (recording pinned `chromium/src` git commit SHA, `args.gn` SHA-256, Clang version, and Rust toolchain), and Sigstore OIDC SLSA v1 build provenance (`actions/attest-build-provenance`).
2. **Remove Cefrium Completely from Trap:**
   - Delete `maven("https://maven.cefrium.com")` from `android/settings.gradle.kts`, `id("com.cefrium")` from `android/build.gradle.kts` & `android/app/build.gradle.kts`, `com.cefrium:cefrium-sdk` dependency, and `CefriumEngineAdapter`.
   - Wire `TrapChromiumEngineAdapter` as the sole `BrowserEngine`.
3. **Update `scripts/audit-apk.sh` & Pass All Mandatory Verification Gates:**
   - Update `scripts/audit-apk.sh` to enforce:
     - Presence and `0x4000` (16KB) `PT_LOAD` alignment of `lib/{arm64-v8a,x86_64}/{libtrap_chromium_content.so,libonion_vault.so}`.
     - **Zero** `Lcom/cefrium/`, `Lcom/google/android/gms/`, `Lcom/google/firebase/`, or `Lcom/google/mlkit/` classes across all `classes*.dex` files!
     - `0` `DT_NEEDED` coupling between `libonion_vault.so` and `libtrap_chromium_content.so`, `<application android:memtagMode="off">` with `0` service overrides, and stored uncompressed `.pak` assets.
   - **Device & Cloud Runtime Gates (Law 2 Mandatory Verification):**
     1. `QEMU virt,mte=on` ARMv9 `FEAT_MTE` Android 16 emulator: boots cleanly, renders `.onion`, zero `SetHeapTaggingLevel` / `Fatal signal 5 (SIGTRAP)`.
     2. Android 15/16 `pixel16k` (16 KiB kernel page size) AVD: boots cleanly, zero ELF `dlopen` alignment errors.
     3. Live `limrun-device-gate` + `BrowserChromeInstrumentedTest`: Arti Tor bootstraps, fail-closed `127.0.0.1:1` blocks pre-Tor loads, DuckDuckGo `.onion` renders HTTP 200 with `window.WebAssembly === undefined` and `<script>` inert, CDP DOM PGP detects fields + decrypts armored blocks + inserts ciphertext via `Input.insertText` with zero JS, and clearnet 3-day glob consent gate works end-to-end.
