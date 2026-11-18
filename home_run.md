# Home run: from "crash on launch" to the verified Tor + OpenPGP browser

Written 2026-11-14; updated 2026-11-17 following cutover to first-party source-built [`maceip/cefpack`](https://github.com/maceip/cefpack). Every milestone ends in empirical verification on an Android emulator and real/cloud ARM64/x86_64 devices.

---

## 1. Architecture of Record

```text
┌──────────────────────── Kotlin / Compose ────────────────────────┐
│ TrapApplication: Chromium process setup, CefpackEngine.initialize│
│ MainActivity / MetaTools chrome, themes, predictive back, PiP    │
│ CefpackSession: JS off, proxy = Tor SOCKS5, DevTools DOM.* JSON  │
│ CdpDomPgpController: zero-JS PGP field detection & mutation      │
└──────────────┬───────────────────────────────────┬───────────────┘
               │ JNI (24 OvNative symbols)         │ DevTools JSON
┌──────────────▼──────────────┐       ┌────────────▼───────────────┐
│ libonion_vault.so (Rust)     │       │ Cefpack (maceip/cefpack)   │
│  ov-tor  Arti + SOCKS5,      │       │  //content/public/android  │
│          ClearnetAllowlist   │       │  libcefpack_content.so     │
│  ov-pgp  Sequoia, Keystore   │       │  0 GMS / 0 Chrome UI bloat │
│  ov-cef  CDP DOM classifier  │       └────────────────────────────┘
└──────────────────────────────┘
```

## 2. Non-Negotiable Release Gates

No release is published unless all gates pass in `.github/workflows/ci-release.yml`:

1. `rust-tests`: `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`, and `cargo llvm-cov`.
2. `android-build`: Multi-ABI `cargo ndk` (`arm64-v8a` + `x86_64`), `:app:testDebugUnitTest`, `:app:assembleDebug` (resolving & SHA-256 verifying `cefpack-android.aar` from `maceip/cefpack`), `scripts/audit-apk.sh`, and CycloneDX 1.5 + SPDX 2.3 SBOM generation (`scripts/generate-sbom.py`).
3. `emulator-gate`: Boots Android 15 (`API 35`) `x86_64` AVD, runs `scripts/emulator-gate.sh`, verifies `Cefpack` readiness + `ov_tor` SOCKS5 bind + 12s live-PID stability + zero crashes/linkage errors, and captures `emulator-launch.png`.
4. `limrun-device-gate`: Runs `node scripts/test-limrun-gate.mjs` (5/5 protocol scenarios) and `scripts/limrun-gate.sh` on a fresh Limrun cloud Android 15 phone, verifying Arti Tor bootstrap, `iso-duckduckgogg42xj` circuit isolation, live DuckDuckGo `.onion` `OnLoadEnd: status 200`, and clean instance teardown.
5. `release`: Sigstore SLSA build provenance + SPDX 2.3 SBOM attestations and tagged GitHub Release.

## 3. Completed Milestones

- [x] Decoupled `libonion_vault.so` (`0` browser `.so` `DT_NEEDED` dependencies; 24 `Java_dev_onionvault_OvNative_*` JNI exports)
- [x] Mandatory APK audit (`scripts/audit-apk.sh`), Android 15 emulator gate (`scripts/emulator-gate.sh`), and Limrun cloud Android 15 `.onion` smoke gate (`scripts/limrun-gate.mjs`)
- [x] Zero-JavaScript browser-process CDP DOM OpenPGP inspection, field encryption/public-key insertion (`Input.insertText`), and native-only `PlaintextSheet` decryption with Sequoia signature verification
- [x] 3-day globbing `ClearnetAllowlist` consent gate (`*.tor.taxi`) + per-service `.onion` Tor circuit isolation (`iso-<apex16>`)
- [x] ARMv9 `FEAT_MTE` (`android:memtagMode="off"` + `PartitionAllocMemoryTagging` disabled) and 16 KB ELF64 `PT_LOAD` page alignment across all native libraries
- [x] First-party source-built Chromium `156.0.8078.96` `//content/public/android` runtime (`cefpack-android.aar` from `https://github.com/maceip/cefpack`) replacing all third-party pre-built Maven browser SDKs
