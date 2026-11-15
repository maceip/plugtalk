<p align="center">
  <img src="design/icons/out/play-store-512.png" width="240" alt="Trap launcher icon" />
</p>

# Trap

[![CI & Release](https://github.com/maceip/trap/actions/workflows/ci-release.yml/badge.svg)](https://github.com/maceip/trap/actions/workflows/ci-release.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0%20OR%20MIT-blue.svg)](LICENSE-APACHE)

**Trap** is an Android hidden-service browser (`dev.onionvault`, `minSdk 29`, `targetSdk 35`) that routes traffic exclusively through an embedded **Arti** Tor client, runs Chromium (**Cefrium `0.19.0`**) with **JavaScript completely disabled**, and performs **OpenPGP** key management, encryption, signing, and decryption natively in Rust (**Sequoia OpenPGP `1.22`** + **Android Keystore AES-256-GCM**) with zero content scripts and zero WebAssembly.

## Downloads & Attestations

Every push to `main` runs Rust workspace checks, multi-ABI (`arm64-v8a` + `x86_64`) APK packaging/ARMv9-MTE/16KB-ELF/DEX/certificate audits (`scripts/audit-apk.sh`), the live Android 15 emulator runtime gate (`scripts/emulator-gate.sh`), and the fail-closed **Limrun cloud Android 15 device gate** (`limrun-device-gate`: `scripts/limrun-gate.sh` / `scripts/limrun-gate.mjs`) before publishing an attested [Latest GitHub Release](https://github.com/maceip/trap/releases/latest):

| Artifact | Direct Download | Description |
|---|---|---|
| **Android Debug APK** | [`app-debug.apk`](https://github.com/maceip/trap/releases/latest/download/app-debug.apk) | Universal debug APK (`arm64-v8a` + `x86_64`) bundling Cefrium `0.19.0` + `libonion_vault.so` (pinned v2 debug cert for `adb install -r` upgrades) |
| **CycloneDX 1.5 SBOM** | [`app-debug.sbom.cdx.json`](https://github.com/maceip/trap/releases/latest/download/app-debug.sbom.cdx.json) | Complete Cargo + Maven/Android (`debugRuntimeClasspath`) + native `.so` SHA-256 inventory |
| **SPDX 2.3 SBOM** | [`app-debug.sbom.spdx.json`](https://github.com/maceip/trap/releases/latest/download/app-debug.sbom.spdx.json) | Sigstore-attested SPDX 2.3 JSON inventory for `app-debug.apk` |
| **SHA-256 Checksums** | [`SHA256SUMS.txt`](https://github.com/maceip/trap/releases/latest/download/SHA256SUMS.txt) | SHA-256 digests of all published release artifacts |
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
├── build.rs                    Self-contained Cargo build script (zero `libcef.so` link coupling)
├── src/lib.rs                  19 `#[no_mangle]` JNI entry points (`Java_dev_onionvault_OvNative_*`)
├── crates/
│   ├── ov-tor/                 Embedded Arti 0.47 Tor client, loopback SOCKS5 proxy, 16-char .onion isolation
│   ├── ov-cef/                 Pure-Rust Chrome DevTools Protocol (CDP) DOM PGP classifier & leak policy switches
│   ├── ov-pgp/                 Sequoia 1.22 OpenPGP engine, SQLite keyring, Android Keystore AES-256-GCM wrapper
│   └── ov-core/                Unified JNI/JSON coordinator bridging Tor, OpenPGP, and CDP DOM inspection
├── design/icons/               Reproducible icon & Neon Graffiti sprite pipelines (`trap_icon.py`, `neon/slice_neon.py`)
├── licenses/                   Bundled LGPL-3.0, GPL-3.0, and LGPL-2.1 texts copied into `assets/licenses/` by Gradle (`copyLegalNotices`)
├── scripts/
│   ├── bump-version.py         Monotonic `VERSION`, `Cargo.toml`, `Cargo.lock`, and Gradle `versionCode` bumper
│   ├── audit-apk.sh            Pre-release APK ARMv9 `memtagMode="off"`, 16KB ELF `PT_LOAD`, DEX, `.pak` & v2 cert gate
│   ├── generate-sbom.py        Real Cargo + Gradle + APK binary CycloneDX 1.5 & SPDX 2.3 SBOM generator
│   ├── emulator-gate.sh        Automated Android emulator launch, Cefrium + `ov_tor` SOCKS5 & crash/linkage gate
│   ├── limrun-gate.sh          Fail-closed Limrun CLI/SDK bootstrap & cloud Android 15 runtime gate wrapper
│   ├── limrun-gate.mjs         Limrun WebSocket/ADB/UI driver (`syncApp`, `launchApp`, Arti SOCKS5 & `.onion` smoke gate)
│   └── test-limrun-gate.mjs    Self-contained local WebSocket mock server E2E test suite for `limrun-gate.mjs`
└── android/                    Gradle Android application (`com.cefrium:cefrium-sdk:0.19.0`, Jetpack Compose, pinned `app/debug.keystore`)
    └── app/src/main/kotlin/dev/onionvault/
        ├── TrapApplication.kt          Process-isolated Cefrium initialization & fail-closed incognito profile
        ├── MainActivity.kt             Compose host binding Arti, Sequoia/BiometricPrompt, Cefrium & preferences
        ├── TabSessionManager.kt        Multi-tab CefriumBrowser lifecycle, URL policy gate & hardened ActionMode
        ├── CdpDomPgpController.kt      Zero-JS in-process CDP DOM scanner, highlighter & verified field mutator
        ├── OnionBackStackManager.kt    12-entry LRU bitmap + DOM snapshot stack for predictive back navigation
        ├── CrashReporter.kt            Java & native crash persistence surfaced via `OvCrashScreen`
        ├── OvNative.kt / OvBridge.kt   Typed Kotlin JNI bindings & JSON parsers for `libonion_vault.so`
        └── ui/                         `TrapTheme`, `BrowserScreen`, `StartPage`, PGP/Tor/Settings/Tabs sheets
```

## Architecture & Security Properties

| Security Property | Enforcement Mechanism |
|---|---|
| **100% Zero JavaScript** | Every `CefriumBrowser` tab enforces `setJavaScriptEnabled(false)` **and** Chrome DevTools Protocol `Emulation.setScriptExecutionDisabled({"value": true})`. `TabSessionManager` additionally cancels `TYPE_SCRIPT` subresource requests at the network layer. Neither websites nor Trap ever evaluate JavaScript. |
| **Fail-Closed Tor Routing (Zero Clearnet Leaks)** | `TrapApplication` boots an ephemeral in-memory `CefriumProfile.incognito()` pinned to a blackhole loopback proxy (`socks5://127.0.0.1:1`), `--host-resolver-rules="MAP * ~NOTFOUND , EXCLUDE 127.0.0.1"`, `--disable-webrtc` (`WEBRTC_DISABLE_NON_PROXIED_UDP`), `--disable-quic`, `--disable-features=NetworkPrediction,DnsOverHttps,AsyncDns`, and `--no-pings` until Arti binds `127.0.0.1:<port>`. Non-`about:blank` navigations wait fail-closed until Arti's SOCKS5 listener is live. |
| **Dual-Layer `.onion` Policy & Stream Isolation** | When **Hidden Service Only** (default `true`) is enabled, both `TabSessionManager` (`OvNative.nativeCheckUrlPolicy`) and Arti's Rust SOCKS5 proxy reject every non-v3-`.onion` host, `TYPE_PING`, and `TYPE_CSP_REPORT`. Each v3 `.onion` destination receives an isolated Tor circuit derived from its 16-character base32 `.onion` apex (`iso-<apex16>`), resettable on demand via **New Identity / Reset Tor Isolation**. |
| **Android Child-Process & GPU Isolation** | Chromium spawns isolated `:sandboxed_process*` renderer and `:privileged_process*` GPU (`Cefrium.setGpuProcessEnabled(true)`) services from the APK rather than running GPU work inside the browser process. `TrapApplication.isBrowserProcess()` restricts `CrashReporter`, `libonion_vault.so`, Arti, and Keystore access strictly to the primary browser process. |
| **ARMv9 `FEAT_MTE` & 16KB Page-Size Hardening** | `<application android:memtagMode="off">` prevents Zygote from locking Bionic heap tagging on ARMv9 devices (including GrapheneOS seccomp-locked `PR_SET_TAGGED_ADDR_CTRL`), while `TrapApplication.appendDisabledFeature(cmd, "PartitionAllocMemoryTagging")` disables Chromium's `PartitionAlloc` MTE reconfiguration across all processes. `scripts/audit-apk.sh` enforces `memtagMode="off"`, `16 KB` ELF `PT_LOAD` alignment (`p_align >= 0x4000`), and the deterministic v2 debug signing certificate (`android/app/debug.keystore`). |
| **Hardware-Backed OpenPGP Secret Keys** | Transferable secret keys are encrypted with a 256-bit AES-GCM master wrapping key inside `AndroidKeyStore` (`onionvault.master.wrap.v1`, or `onionvault.master.wrap.biometric.v1` gated by `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) before storage in SQLite (`android:allowBackup="false"`). Toggling biometric protection atomically rewraps stored secret keys. |
| **Native-Only Plaintext Display & Hardened Selection** | Decrypted PGP messages render exclusively in native Compose (`PlaintextSheet`), preventing CSS/DOM exfiltration side channels; plaintext is written into a page control only on explicit **Insert into page** confirmation. `TabSessionManager` wraps Chromium's `ActionMode.Callback` to strip external `WEB_SEARCH`, `SHARE`, and `ACTION_PROCESS_TEXT` intents while offering in-app PGP Encrypt/Decrypt/Import actions. |

## Zero-JavaScript CDP DOM OpenPGP Engine

Because JavaScript execution is disabled across the engine, Trap inspects and mutates page forms through Cefrium's in-process Chrome DevTools Protocol bridge (`CefriumBrowser.executeDevToolsMethod` in `CdpDomPgpController.kt` backed by `crates/ov-cef/src/cdp.rs`):

1. **Shadow-Piercing Inspection**: `DOM.getDocument({"depth": -1, "pierce": true})` walks Blink's C++ `InspectorDOMAgent` tree—including Blink's user-agent `#inner-editor` shadow roots inside `<textarea>` and `<input>` controls where typed text lives—without running page scripts.
2. **Rust Classification & Viewport Anchoring**: `OvNative.nativeClassifyCdpDom` classifies editable fields, ASCII-armored `PGP MESSAGE` blocks, and `PGP PUBLIC KEY BLOCK` nodes, while `DOM.getBoxModel` + `Page.getLayoutMetrics` compute normalized viewport coordinates for Compose action pills (`Encrypt Field`, `Insert My PubKey`, `Decrypt Block`, `Import Key`) and `DOM.setAttributeValue` applies visual CSS outline highlights.
3. **Read-Back Verified Mutation**: When writing encrypted ciphertext or an exported public key into a form control, `CdpDomPgpController` focuses the node (`DOM.focus`), clears existing content via `Input.dispatchKeyEvent` (`SelectAll` / selection + `Backspace`), inserts the payload via `Input.insertText`, and reads back the live Blink DOM value (`FieldMutationOutcome.verified`) before reporting success in the UI banner.

## Browser Chrome & Unified `TrapTheme`

Trap's Jetpack Compose UI (`android/app/src/main/kotlin/dev/onionvault/ui/`) persists user preferences via `TrapPreferences` (`SharedPreferences`) and supports two complete theme families across **Dark** and **Light** modes:

- **`SecureDrop`** (default): Warm obsidian/amber/teal workbench palette (`#0F0C09`, `#1A1510`, `#E8A33D` amber `Go`, `#2EC4B0` teal, `#3ECF6A` green) paired with crisp `2dp` vector iconography (`res/drawable/ic_ov_*.xml`) and a high-contrast warm-paper Light mode.
- **`NeonGraffiti`**: Palette and artwork from the **TRAP Neon Graffiti Browser Board** (`#15181F`, `#1E222C`, `#00E4C1` teal, `#67EEE1` mint, `#FD96C3` pink, `#E8933E` amber), rendering untinted RGBA neon sprites in Dark mode (preserving alpha glow halos) and crisp vectors in Light mode.

Key chrome surfaces:
- **Top Identity Header**: `40dp` Trap onion mark (Start Page shortcut) · Page Title + `.onion` host (`<= 2:1` label density) · tappable `TOR` status pill (opens **Tor Security & Circuit** sheet) · `40dp` **PGP Keyring** button.
- **Start Page** (`about:blank`): Live Zero-JS / Traffic Policy / Arti bootstrap posture card, one-tap verified v3 `.onion` launch card (**DuckDuckGo `.onion`**: `https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/`), and quick buttons for **PGP Keyring** and **Settings**.
- **Bottom Control Dock & Transport Footer**: Responsive `44dp` touch targets with `8dp` spacing, unified `[Shield · .onion Input · Clear ✕ | Go]` capsule (`0dp` internal seam), `Back` (`PredictiveBackHandler` + `OnionBackStackManager` cached `Bitmap` preview + **Reload Live over Tor** banner), `Forward`, spinning `Reload`, `Tabs` badge (`tabs.size`), `Menu` (`☰`), and real-time transport footer (`Tor ready` / `Tor bootstrapping X%` + active `iso-<apex16>` circuit token).
- **Modal Sheets**: Multi-Tab switcher (`TabsSheet`), vertical-row **Tor Security & Circuit** sheet (`SecurityCircuitSheet`), **Settings** sheet (`SettingsSheet`), **PGP Keyring** (`KeyringSheet`: list, generate with custom User ID + optional passphrase, import, copy armored public key, set default own key, delete), **Recipient & Signer Picker** (`EncryptPickerSheet`, including anonymous unsigned mode), **Unlock Secret Key** (`DecryptPassphraseSheet`), native **Decrypted PGP Message** (`PlaintextSheet` with 4-state Sequoia signature verification), long-press **Context Menu** & **Selection PGP** sheets, scrollable **Open-Source Licences** (`LegalNoticesDialog`), and `OvCrashScreen`.

## Building & Testing

### Prerequisites

- **Rust 1.82+** with targets `aarch64-linux-android` and `x86_64-linux-android`, plus `cargo-ndk`
- **JDK 25** (`kotlin { jvmToolchain(25) }`)
- **Android SDK** (`compileSdk 37`, `targetSdk 35`, `minSdk 29`) & **Android NDK `27.2.12479018`**

### Commands

```sh
# 1. Rust workspace formatting, lint, and unit/integration tests (host)
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# 2. Cross-compile Rust JNI library (libonion_vault.so) for Android ABIs
cargo ndk -t arm64-v8a -t x86_64 --platform 29 -o android/app/src/main/jniLibs build --release

# 3. Assemble debug APK (packages Cefrium 0.19.0 + libonion_vault.so + assets/licenses/)
./android/gradlew --project-dir android :app:assembleDebug

# 4. Run JVM / Robolectric Compose UI, screenshot, JNI parity, and crash-guard tests
./android/gradlew --project-dir android :app:testDebugUnitTest

# 5. Run on-device / emulator instrumentation tests & runtime stability gates
./android/gradlew --project-dir android :app:connectedDebugAndroidTest
./scripts/emulator-gate.sh android/app/build/outputs/apk/debug/app-debug.apk
node scripts/test-limrun-gate.mjs
LIM_API_KEY="$LIM_API_KEY" ./scripts/limrun-gate.sh android/app/build/outputs/apk/debug/app-debug.apk dist/limrun
```

## Empirical Verification Status

Every item below reflects commands executed and verified against this codebase:

| Subsystem / Gate | Verification Method | Result |
|---|---|---|
| **Rust Formatting & Lint** (`ov-core`, `ov-cef`, `ov-pgp`, `ov-tor`) | `cargo fmt --all -- --check` & `cargo clippy --workspace --all-targets -- -D warnings` | **PASS** (`0` warnings) |
| **Rust Crypto, Tor SOCKS5/Isolation & CDP DOM Suite** | `cargo test --workspace` | **PASS** (`62` passed, `0` failed, `2` live-Tor `#[ignore]`) |
| **Android Native NDK Build, ARMv9 MTE & 16KB ELF Audit** (`arm64-v8a`, `x86_64`) | `./scripts/audit-apk.sh app-debug.apk` + QEMU `virt,mte=on` 4-process `FEAT_MTE` harness | **PASS** (5/5 APK audit checks: `memtagMode="off"`, `PT_LOAD >= 0x4000`, 19/19 JNI exports, zero `libcef.so` `DT_NEEDED`, pinned v2 cert; `fail=0` across `browser`, `renderer`, `gpu-process`, `zygote` under ARMv9 TBI/locked-TBI/Async/Sync MTE) |
| **Compose Previews, 13 Visual Captures & Crash-Guard Unit Tests** | `./android/gradlew --project-dir android :app:testDebugUnitTest` (`OnionVaultPreviewsTest`, `ScreenshotTest`, `CrashGuardTest`, `OvNativeTest`) | **PASS** (`38` passed, `0` failed; 13 PNG screenshots rendered across `SecureDrop` & `NeonGraffiti` Dark/Light) |
| **On-Device Instrumentation E2E (`connectedDebugAndroidTest`)** | `BrowserChromeInstrumentedTest` on `arm64-v8a` (`PAGESIZE=16384` `pixel16k_hvf` & API 35) | **PASS** (`2` passed, `0` failed: Chrome settings/security/tabs/Keystore keyring E2E + live `CefriumBrowser` zero-JS CDP DOM PGP `Insert My PubKey`, `Encrypt Field` UA shadow read-back verification, and `Decrypt Block` -> `PlaintextSheet` signature verification) |
| **Emulator Runtime Gate & Isolated GPU / `.onion` Circuit Verification** | `./scripts/emulator-gate.sh app-debug.apk` across `pixel16k_hvf` (`16 KB`), `xtool-api36` (`4 KB`), and `codex_cosmo_api35` (`4 KB`) | **PASS** (Cold launch + `adb install -r` upgrade, isolated `:privileged_process0` GPU + `:sandboxed_process0` renderer, embedded `ov_tor` SOCKS5 ready, zero MTE/GPU/native crashes) |
| **Limrun Cloud Android Gate Protocol Self-Test** | `node scripts/test-limrun-gate.mjs` (`@limrun/api@0.73.2`, `lim@0.57.3`) | **PASS** (`4` passed, `0` failed: happy-path `.onion` smoke gate, `SIGTRAP`/MTE crash rejection, clearnet leak rejection, and missing `LIM_API_KEY` fail-closed guard) |

## License

Trap is dual-licensed under **Apache-2.0 OR MIT** ([`LICENSE-APACHE`](LICENSE-APACHE), [`LICENSE-MIT`](LICENSE-MIT)). Third-party runtime components and bundled LGPL/GPL texts are documented in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and [`licenses/`](licenses/).
