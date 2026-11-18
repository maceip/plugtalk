<p align="center">
  <img src="design/icons/out/play-store-512.png" width="240" alt="Trap launcher icon" />
</p>

# Trap

[![CI & Release](https://github.com/maceip/trap/actions/workflows/ci-release.yml/badge.svg)](https://github.com/maceip/trap/actions/workflows/ci-release.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0%20OR%20MIT-blue.svg)](LICENSE-APACHE)

**Trap** is an Android Tor & OpenPGP browser (`dev.onionvault`, `minSdk 29`, `targetSdk 37`) that routes every `.onion` and user-approved clearnet connection exclusively through an embedded **Arti** Tor client, gates clearnet origins behind a **3-day globbing Clearnet Consent Interstitial** (`*.tor.taxi`), runs a first-party source-built **Cefpack** Chromium `156.0.8078.96` (`//content/public/android`) runtime from [`maceip/cefpack`](https://github.com/maceip/cefpack) with **JavaScript completely disabled**, and performs **OpenPGP** key management, encryption, signing, and decryption natively in Rust (**Sequoia OpenPGP `1.22`** + **Android Keystore AES-256-GCM**).

## Downloads & Attestations

Every push to `main` runs Rust workspace checks, multi-ABI (`arm64-v8a` + `x86_64`) APK packaging/ARMv9-MTE/16KB-ELF/DEX/certificate audits (`scripts/audit-apk.sh`), the live Android 15 emulator runtime gate (`scripts/emulator-gate.sh`), and the fail-closed **Limrun cloud Android 15 device gate** (`limrun-device-gate`: `scripts/limrun-gate.sh` / `scripts/limrun-gate.mjs`) before publishing an attested [Latest GitHub Release](https://github.com/maceip/trap/releases/latest):

| Artifact | Direct Download | Description |
|---|---|---|
| **Android Debug APK** | [`app-debug.apk`](https://github.com/maceip/trap/releases/latest/download/app-debug.apk) | Universal debug APK (`arm64-v8a` + `x86_64`) bundling Cefpack (`libcefpack_content.so`) + `libonion_vault.so` (pinned v2 debug cert for `adb install -r` upgrades) |
| **CycloneDX 1.5 SBOM** | [`app-debug.sbom.cdx.json`](https://github.com/maceip/trap/releases/latest/download/app-debug.sbom.cdx.json) | Complete Cargo + Cefpack + Maven/Android (`debugRuntimeClasspath`) + native `.so` SHA-256 inventory |
| **SPDX 2.3 SBOM** | [`app-debug.sbom.spdx.json`](https://github.com/maceip/trap/releases/latest/download/app-debug.sbom.spdx.json) | Sigstore-attested SPDX 2.3 JSON inventory for `app-debug.apk` |
| **SHA-256 Checksums** | [`SHA256SUMS.txt`](https://github.com/maceip/trap/releases/latest/download/SHA256SUMS.txt) | SHA-256 digests of all published release artifacts |
| **Limrun Cloud Gate Captures** | [`limrun-launch.png`](https://github.com/maceip/trap/releases/latest/download/limrun-launch.png) · [`limrun-onion.png`](https://github.com/maceip/trap/releases/latest/download/limrun-onion.png) · [`limrun-prize.png`](https://github.com/maceip/trap/releases/latest/download/limrun-prize.png) · [`limrun-summary.json`](https://github.com/maceip/trap/releases/latest/download/limrun-summary.json) | Live Limrun cloud Android 15 screenshots (including 3-round OpenPGP TOTP `.onion` prize unlock) and telemetry captured during `scripts/limrun-gate.mjs` |
| **Emulator Gate Capture** | [`emulator-launch.png`](https://github.com/maceip/trap/releases/latest/download/emulator-launch.png) | Live Android 15 runtime screenshot captured during `scripts/emulator-gate.sh` |
| **Bundled License Texts** | [`LGPL-3.0.txt`](https://github.com/maceip/trap/releases/latest/download/LGPL-3.0.txt) · [`GPL-3.0.txt`](https://github.com/maceip/trap/releases/latest/download/GPL-3.0.txt) · [`LGPL-2.1.txt`](https://github.com/maceip/trap/releases/latest/download/LGPL-2.1.txt) | Verbatim third-party license texts also embedded offline inside `assets/licenses/` |

> **Limrun Cloud Android 15 Gate (`LIM_API_KEY`)**: `limrun-device-gate` runs `node scripts/test-limrun-gate.mjs` on every push and then provisions a fresh Limrun cloud Android 15 phone (`@limrun/api@0.73.2` + `lim@0.57.3`) via repository secret `LIM_API_KEY` (from `https://console.limrun.com/settings`) to verify cold launch, isolated child processes, Arti SOCKS5 bootstrap, and live DuckDuckGo v3 `.onion` navigation (`limrun-launch.png`, `limrun-onion.png`, `limrun-summary.json`). If `LIM_API_KEY` is unset or any check fails, `limrun-device-gate` fails closed and skips `release`, keeping the previously verified release as `latest`.

Verify the downloaded APK checksum, SLSA build provenance attestation, and SPDX 2.3 SBOM attestation with the [GitHub CLI](https://cli.github.com/):

```sh
curl -fLO https://github.com/maceip/trap/releases/latest/download/app-debug.apk
curl -fLO https://github.com/maceip/trap/releases/latest/download/SHA256SUMS.txt
sha256sum -c SHA256SUMS.txt --ignore-missing

# Verify Sigstore build provenance & SPDX 2.3 SBOM attestations
gh attestation verify app-debug.apk --repo maceip/trap
gh attestation verify app-debug.apk --repo maceip/trap --predicate-type https://spdx.dev/Document/v2.3
```

## Repository Layout

```text
trap/
├── VERSION                     Monotonic release version manifest (`MAJOR.MINOR.PATCH`)
├── Cargo.toml                  Workspace manifest + Android `cdylib` (`libonion_vault.so`)
├── build.rs                    Self-contained Cargo build script (zero browser `.so` link coupling)
├── src/lib.rs                  24 `#[no_mangle]` JNI entry points (`Java_dev_onionvault_OvNative_*`)
├── crates/
│   ├── ov-tor/                 Embedded Arti 0.47 Tor client, loopback SOCKS5 proxy, 16-char .onion isolation & 3-day globbing `ClearnetAllowlist`
│   ├── ov-cef/                 Pure-Rust Chrome DevTools Protocol (CDP) DOM PGP classifier & URL/clearnet consent policy
│   ├── ov-pgp/                 Sequoia 1.22 OpenPGP engine, SQLite keyring, Android Keystore AES-256-GCM wrapper
│   └── ov-core/                Unified JNI/JSON coordinator bridging Tor, clearnet rules, OpenPGP, and CDP DOM inspection
├── docs/
│   └── BROWSER-RUNTIME-BUILD-PLAN.md  Source-built Chromium Android (`maceip/cefpack` `//content/public/android`) architecture
├── design/icons/               Reproducible icon & Neon Graffiti sprite pipelines (`trap_icon.py`, `neon/slice_neon.py`)
├── licenses/                   Bundled LGPL-3.0, GPL-3.0, and LGPL-2.1 texts copied into `assets/licenses/` by Gradle (`copyLegalNotices`)
├── scripts/
│   ├── bump-version.py         Monotonic `VERSION`, `Cargo.toml`, `Cargo.lock`, and Gradle `versionCode` bumper
│   ├── audit-apk.sh            Pre-release APK ARMv9 `memtagMode="off"`, 16KB ELF `PT_LOAD`, DEX, `.pak` & v2 cert gate
│   ├── generate-sbom.py        Real Cargo + Cefpack + Gradle + APK binary CycloneDX 1.5 & SPDX 2.3 SBOM generator
│   ├── emulator-gate.sh        Automated Android emulator launch, Cefpack + `ov_tor` SOCKS5 & crash/linkage gate
│   ├── limrun-gate.sh          Fail-closed Limrun CLI/SDK bootstrap & cloud Android 15 runtime + TOTP prize gate wrapper
│   ├── limrun-gate.mjs         Limrun WebSocket/ADB/UI driver (`syncApp`, Arti SOCKS5, `.onion` smoke & 3-round OpenPGP TOTP prize gate)
│   ├── run-totp-prize-gate.sh  Local ADB/USB/wireless 3-round OpenPGP TOTP `.onion` prize gate runner + screenshot extractor
│   └── test-limrun-gate.mjs    Self-contained local WebSocket mock server E2E test suite for `limrun-gate.mjs`
└── android/                    Gradle Android application (`cefpack-android.aar` from `maceip/cefpack`, Jetpack Compose, pinned `app/debug.keystore`)
    └── app/src/main/kotlin/dev/onionvault/
        ├── TrapApplication.kt          Process-isolated `CefpackEngine` initialization & fail-closed ephemeral profile
        ├── MainActivity.kt             Compose host binding Arti, Sequoia/BiometricPrompt, Cefpack & preferences
        ├── TabSessionManager.kt        Multi-tab `CefpackSession` lifecycle, URL policy gate & hardened ActionMode
        ├── CdpDomPgpController.kt      Zero-JS in-process CDP DOM scanner & verified field mutator
        ├── OnionBackStackManager.kt    12-entry LRU bitmap + DOM snapshot stack for predictive back navigation
        ├── CrashReporter.kt            Java & native crash persistence surfaced via `OvCrashScreen`
        ├── OvNative.kt / OvBridge.kt   Typed Kotlin JNI bindings & JSON parsers for `libonion_vault.so`
        └── ui/                         `TrapTheme`, `BrowserScreen`, `StartPage`, hardware pods, PGP/Tor/Settings/Tabs sheets
```

## Architecture & Security Properties

| Security Property | Enforcement Mechanism |
|---|---|
| **100% Zero JavaScript** | `CefpackContentBrowserClient::OverrideWebPreferences` locks `javascript_enabled = false` in C++, `CefpackEngine` passes `--js-flags=--jitless,--no-expose-wasm`, every `CefpackSession` enforces CDP `Emulation.setScriptExecutionDisabled({"value": true})`, and `CefpackURLLoaderThrottle` + `TabSessionManager` cancel script subresource requests at the network layer. Neither websites nor Trap ever evaluate JavaScript. |
| **Fail-Closed Tor Routing (Zero Unproxied Leaks)** | `CefpackEngine` boots an ephemeral in-memory `CefpackBrowserContext` (`IsOffTheRecord() == true`, `http_cache_enabled = false`) pinned to a blackhole loopback proxy (`socks5://127.0.0.1:1`), `--host-resolver-rules="MAP * ~NOTFOUND , EXCLUDE 127.0.0.1"`, `DisableQuic()`, `InsecureDnsMode::kDisabled`, `SecureDnsMode::kOff`, `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`, `--disable-features=PartitionAllocMemoryTagging,AutofillServerCommunication,OptimizationHints,Translate,MediaRouter,WebRtcHideLocalIpsWithMdns,HttpsUpgrades,HttpsFirstBalancedMode,HttpsFirstBalancedModeAutoEnable`, `--dns-prefetch-disable`, `--disable-background-networking`, and `--no-pings` until Arti binds `127.0.0.1:<port>`, then live-switches all `NetworkContext`s via `CefpackEngine.setProxyServer("socks5://127.0.0.1:<port>")`. |
| **Clearnet Consent Interstitial & `.onion` Stream Isolation** | Every v3 `.onion` destination connects immediately over an isolated Tor circuit derived from its 16-character base32 `.onion` apex (`iso-<apex16>`). Clearnet destinations are gated on **every** navigation and subresource SOCKS5 stream by `ClearnetAllowlist` (`crates/ov-tor/src/clearnet.rs`): unrecognized clearnet hosts hold at the SOCKS5 gate and present `ClearnetConsentSheet` (`Allow for 3 days` `259,200s` persisted to `clearnet_allowlist.json` with root-inclusive `*` globbing such as `*.tor.taxi`, `Allow once` session-only, or `Block`), while unsolicited background Google endpoints (`*.googleapis.com`, `*.gstatic.com`, `*.gvt1.com`, `*.pki.goog`, `chrome.cloudflare-dns.com`) are silently refused (`REP_NOT_ALLOWED`). Enabling **Hidden Services (.onion) Only** rejects all clearnet traffic outright. |
| **Android Child-Process & GPU Isolation** | Chromium spawns isolated `:sandboxed_process0..39` renderer (`android:isolatedProcess="true"`) and `:privileged_process0..4` GPU services declared in `cefpack-android.aar`. `CefpackEngine.initProcess(this)` configures `PROCESS_BROWSER` vs `PROCESS_CHILD`, while `TrapApplication.isBrowserProcess()` restricts `CrashReporter`, `libonion_vault.so`, Arti, and Keystore access strictly to the primary browser process. |
| **ARMv9 `FEAT_MTE` & 16KB Page-Size Hardening** | `<application android:memtagMode="off">` prevents Zygote from locking Bionic heap tagging on ARMv9 devices (including GrapheneOS seccomp-locked `PR_SET_TAGGED_ADDR_CTRL`), while `CefpackMainDelegate` and `TrapApplication` disable `PartitionAllocMemoryTagging` across all processes. `scripts/audit-apk.sh` enforces `memtagMode="off"`, `16 KB` ELF `PT_LOAD` alignment (`p_align >= 0x4000`) across every `.so` in the APK, zero GMS/Firebase/MLKit/legacy classes, and the deterministic v2 debug signing certificate (`android/app/debug.keystore`). |
| **Hardware-Backed OpenPGP Secret Keys** | Transferable secret keys are encrypted with a 256-bit AES-GCM master wrapping key inside `AndroidKeyStore` (`onionvault.master.wrap.v1`, or `onionvault.master.wrap.biometric.v1` gated by `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) before storage in SQLite (`android:allowBackup="false"`). Toggling biometric protection atomically rewraps stored secret keys. |
| **Native-Only Plaintext Display & Hardened Selection** | Decrypted PGP messages render exclusively in native Compose (`PlaintextSheet`), preventing CSS/DOM exfiltration side channels; plaintext is written into a page control only on explicit **Insert into page** confirmation. `TabSessionManager` wraps Chromium's `ActionMode.Callback` via `CefpackSession.setTextSelectionActionModeCallback` to strip external `WEB_SEARCH`, `SHARE`, and `ACTION_PROCESS_TEXT` intents while offering in-app PGP Encrypt/Decrypt/Import actions. |

## Zero-JavaScript CDP DOM OpenPGP Engine

Because JavaScript execution is disabled across the engine, Trap inspects and mutates page forms through Cefpack's in-process Chrome DevTools Protocol bridge (`CefpackSession.executeDevToolsMethod` in `CdpDomPgpController.kt` backed by `content::DevToolsAgentHost` and `crates/ov-cef/src/cdp.rs`):

1. **Shadow-Piercing Inspection**: `DOM.getDocument({"depth": -1, "pierce": true})` walks Blink's C++ `InspectorDOMAgent` tree—including Blink's user-agent `#inner-editor` shadow roots inside `<textarea>` and `<input>` controls where typed text lives—without running page scripts.
2. **Rust Classification & Viewport Anchoring**: `OvNative.nativeClassifyCdpDom` classifies editable fields, ASCII-armored `PGP MESSAGE` blocks, and `PGP PUBLIC KEY BLOCK` nodes, while `DOM.getBoxModel`, `DOM.getNodeForLocation`, and `Page.getLayoutMetrics` resolve focused controls for Trap's docked OpenPGP actions (`Encrypt Field`, `Insert My PubKey`, `Decrypt Block`, `Import Key`).
3. **Read-Back Verified Mutation**: When writing encrypted ciphertext or an exported public key into a form control, `CdpDomPgpController` focuses the node (`DOM.focus`), clears existing content via `Input.dispatchKeyEvent` (`SelectAll` / selection + `Backspace`), inserts the payload via `Input.insertText`, and reads back the live Blink DOM value (`FieldMutationOutcome.verified`) before reporting success in the UI banner.

## Source-Built Cefpack Browser Runtime

Trap loads its Chromium `156.0.8078.96` `//content/public/android` runtime (`cefpack-android.aar`, `arm64-v8a` + `x86_64`) directly from [`https://github.com/maceip/cefpack`](https://github.com/maceip/cefpack) Releases with mandatory SHA-256 verification in `android/app/build.gradle.kts` (`:app:resolveCefpackAar`, overridable locally via `-PcefpackAarPath` / `CEFPACK_AAR_PATH`). Architectural details are documented in [`docs/BROWSER-RUNTIME-BUILD-PLAN.md`](docs/BROWSER-RUNTIME-BUILD-PLAN.md).

## Building & Testing

### Prerequisites

- **Rust 1.82+** with targets `aarch64-linux-android` and `x86_64-linux-android`, plus `cargo-ndk`
- **JDK 25**
- **Android SDK** (`compileSdk 37`, `targetSdk 37`, `minSdk 29`) & **Android NDK `27.2.12479018`**

### Commands

```sh
# 1. Rust workspace formatting, lint, and unit/integration tests (host)
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# 2. Cross-compile Rust JNI library (libonion_vault.so) for Android ABIs
cargo ndk -t arm64-v8a -t x86_64 --platform 29 -o android/app/src/main/jniLibs build --release

# 3. Assemble debug APK (resolves & SHA-256 verifies cefpack-android.aar + packages libonion_vault.so + assets/licenses/)
./android/gradlew --project-dir android :app:assembleDebug

# 4. Run JVM / Robolectric Compose UI, screenshot, JNI parity, and crash-guard tests
./android/gradlew --project-dir android :app:testDebugUnitTest

# 5. Run on-device / emulator instrumentation tests & 3-round OpenPGP TOTP .onion prize gates
./android/gradlew --project-dir android :app:assembleDebug :app:assembleDebugAndroidTest
./scripts/run-totp-prize-gate.sh dist/totp-prize/totp-prize-won.png
./scripts/emulator-gate.sh android/app/build/outputs/apk/debug/app-debug.apk
node scripts/test-limrun-gate.mjs
LIM_API_KEY="$LIM_API_KEY" ./scripts/limrun-gate.sh android/app/build/outputs/apk/debug/app-debug.apk dist/limrun
```

## License

Trap is dual-licensed under **Apache-2.0 OR MIT** ([`LICENSE-APACHE`](LICENSE-APACHE), [`LICENSE-MIT`](LICENSE-MIT)). Third-party runtime components and bundled LGPL/GPL texts are documented in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and [`licenses/`](licenses/).
