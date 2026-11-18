# Third-Party Notices

Trap is dual-licensed under **Apache-2.0 OR MIT** ([`LICENSE-APACHE`](LICENSE-APACHE), [`LICENSE-MIT`](LICENSE-MIT)). Trap links against or packages the following third-party open-source software, each governed by its own license.

## Runtime Components

| Component | Version / Artifact | Origin | License |
|---|---|---|---|
| **Cefpack** (source-built Android Chromium `//content/public/android` embedder, Chromium `156.0.8078.96`) | `cefpack-android.aar` (`v0.2.1`, SHA-256 verified by `:app:resolveCefpackAar`) | [maceip/cefpack](https://github.com/maceip/cefpack) · upstream [Chromium](https://chromium.googlesource.com/chromium/src.git) (`2ad9b7f5a86846e0b264090723a6a78964bb84c4`) | `BSD-3-Clause` (Chromium `//content`, Blink, V8, `cefpack_webview`), `Apache-2.0 OR MIT` (`maceip/cefpack` tooling), plus Chromium third-party notices |
| **sequoia-openpgp** | `1.22` | [sequoia-pgp/sequoia-openpgp](https://gitlab.com/sequoia-pgp/sequoia-openpgp) | `LGPL-2.0-or-later` |
| **arti-client**, **tor-rtcompat** | `0.47` | [Tor Project Arti](https://gitlab.torproject.org/tpo/core/arti) | `MIT OR Apache-2.0` |
| **rustls** (`ring` provider) | `0.23` | [rustls/rustls](https://github.com/rustls/rustls) | `Apache-2.0 OR ISC OR MIT` |
| **aes-gcm** | `0.10` | [RustCrypto/AEADs](https://github.com/RustCrypto/AEADs) | `Apache-2.0 OR MIT` |
| **rusqlite** (`bundled` SQLite) | `0.37` | [rusqlite/rusqlite](https://github.com/rusqlite/rusqlite) | `MIT` (SQLite: Public Domain) |
| **jni** | `0.21` | [jni-rs/jni-rs](https://github.com/jni-rs/jni-rs) | `MIT OR Apache-2.0` |
| **tokio**, **serde**, **serde_json**, **anyhow**, **thiserror** | Workspace pins (`Cargo.lock`) | [crates.io](https://crates.io) | `MIT OR Apache-2.0` |
| **AndroidX & Jetpack Compose** (`activity-compose`, `compose-bom`, `appcompat`, `biometric`, `browser`, `core-ktx`, `mediarouter`, `kotlinx-coroutines-android`) | `2024.10.01` BOM / `1.x` | [Android Open Source Project](https://source.android.com) / [JetBrains](https://github.com/Kotlin/kotlinx.coroutines) | `Apache-2.0` |

## Dynamic Shared Library & Source Relinking Notes

1. **Browser Engine (`BSD-3-Clause` / `Apache-2.0 OR MIT`)**:
   - Trap's native Rust library (`libonion_vault.so`) does **not** link against `libcefpack_content.so` (`DT_NEEDED` imports only Android Bionic system libraries: `libc.so`, `libdl.so`, `liblog.so`, and `libm.so`).
   - Gradle's `:app:resolveCefpackAar` task downloads `cefpack-android.aar` directly from [`maceip/cefpack` Releases](https://github.com/maceip/cefpack/releases), verifies its pinned SHA-256 digest, and packages `libcefpack_content.so` (`arm64-v8a` + `x86_64`) and uncompressed `cefpack_resources.pak` + `icudtl.dat` into the APK (`useLegacyPackaging = true`). Complete build recipes and embedder sources live in [`https://github.com/maceip/cefpack`](https://github.com/maceip/cefpack).

2. **OpenPGP Engine (`sequoia-openpgp`, `LGPL-2.0-or-later`)**:
   - `sequoia-openpgp` is compiled into `libonion_vault.so`. The complete corresponding source code of Trap (`Cargo.toml`, `Cargo.lock`, `build.rs`, `src/`, and `crates/`) is provided in this repository so any recipient can modify `sequoia-openpgp`, rebuild `libonion_vault.so` via `cargo ndk`, and repackage the APK.

## Bundled License Texts

Full verbatim license texts live in [`licenses/`](licenses/) and are copied by Gradle (`copyLegalNotices`) into `assets/licenses/` inside the APK:

| File | Applies To | SPDX Identifier |
|---|---|---|
| [`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt) | `sequoia-openpgp` & LGPL-2.1-licensed Chromium subcomponents | `LGPL-2.0-or-later` / `LGPL-2.1-or-later` |
| [`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt) | LGPL-3.0-licensed subcomponents | `LGPL-3.0-or-later` |
| [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt) | GNU GPL v3.0 incorporated by reference in LGPL-3.0 | `GPL-3.0-or-later` |

Within the running Android application, open the hardware remote menu → **Licences** to browse and read every bundled license file offline.

## Artwork & Design Assets

Trap's launcher icons, adaptive/monochrome layers, SecureDrop chrome vectors (`res/drawable/ic_ov_*.xml`), and Neon Graffiti artwork (`design/icons/`) were created for this repository and are covered by the repository's `Apache-2.0 OR MIT` license.
