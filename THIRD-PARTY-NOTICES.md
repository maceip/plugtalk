# Third-Party Notices

Trap is dual-licensed under **Apache-2.0 OR MIT** ([`LICENSE-APACHE`](LICENSE-APACHE), [`LICENSE-MIT`](LICENSE-MIT)). Trap links against or packages the following third-party open-source software, each governed by its own license.

## Runtime Components

| Component | Version / Artifact | Origin | License |
|---|---|---|---|
| **Cefrium** (Android port of Chromium Embedded Framework / Chromium 156) | `com.cefrium:cefrium-sdk:0.19.0` (`com.cefrium` Gradle plugin `0.19.0`) | `https://maven.cefrium.com` · upstream [Chromium Embedded Framework](https://github.com/chromiumembedded/cef) | `LGPL-3.0-or-later` (Cefrium runtime), `BSD-3-Clause` (upstream CEF & Chromium) |
| **sequoia-openpgp** | `1.22` | [sequoia-pgp/sequoia-openpgp](https://gitlab.com/sequoia-pgp/sequoia-openpgp) | `LGPL-2.0-or-later` |
| **arti-client**, **tor-rtcompat** | `0.47` | [Tor Project Arti](https://gitlab.torproject.org/tpo/core/arti) | `MIT OR Apache-2.0` |
| **rustls** (`ring` provider) | `0.23` | [rustls/rustls](https://github.com/rustls/rustls) | `Apache-2.0 OR ISC OR MIT` |
| **aes-gcm** | `0.10` | [RustCrypto/AEADs](https://github.com/RustCrypto/AEADs) | `Apache-2.0 OR MIT` |
| **rusqlite** (`bundled` SQLite) | `0.37` | [rusqlite/rusqlite](https://github.com/rusqlite/rusqlite) | `MIT` (SQLite: Public Domain) |
| **jni** | `0.21` | [jni-rs/jni-rs](https://github.com/jni-rs/jni-rs) | `MIT OR Apache-2.0` |
| **tokio**, **serde**, **serde_json**, **anyhow**, **thiserror** | Workspace pins (`Cargo.lock`) | [crates.io](https://crates.io) | `MIT OR Apache-2.0` |
| **AndroidX & Jetpack Compose** (`activity-compose`, `compose-bom`, `appcompat`, `biometric`, `browser`, `core-ktx`, `mediarouter`, `kotlinx-coroutines-android`) | `2024.10.01` BOM / `1.x` | [Android Open Source Project](https://source.android.com) / [JetBrains](https://github.com/Kotlin/kotlinx.coroutines) | `Apache-2.0` |

## LGPL Dynamic & Source Relinking Notes

1. **Browser Engine (`LGPL-3.0-or-later` / `BSD-3-Clause`)**:
   - Trap's native Rust library (`libonion_vault.so`) does **not** link against `libcef.so` (`DT_NEEDED` imports only Android Bionic system libraries: `libc.so`, `libdl.so`, `liblog.so`, and `libm.so`).
   - Cefrium packages `libcef.so` and its Chromium resource bundle into the APK at build time via `com.cefrium:cefrium-sdk:0.19.0`, and loads `libcef.so` dynamically as a replaceable shared library at runtime (`useLegacyPackaging = true`).

2. **OpenPGP Engine (`sequoia-openpgp`, `LGPL-2.0-or-later`)**:
   - `sequoia-openpgp` is compiled into `libonion_vault.so`. The complete corresponding source code of Trap (`Cargo.toml`, `Cargo.lock`, `build.rs`, `src/`, and `crates/`) is provided in this repository so any recipient can modify `sequoia-openpgp`, rebuild `libonion_vault.so` via `cargo ndk`, and repackage the APK.

## Bundled License Texts

Full verbatim license texts live in [`licenses/`](licenses/) and are copied by Gradle (`copyLegalNotices`) into `assets/licenses/` inside the APK:

| File | Applies To | SPDX Identifier |
|---|---|---|
| [`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt) | Cefrium browser engine (`libcef.so`) | `LGPL-3.0-or-later` |
| [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt) | GNU GPL v3.0 incorporated by reference in LGPL-3.0 | `GPL-3.0-or-later` |
| [`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt) | `sequoia-openpgp` | `LGPL-2.0-or-later` |

Within the running Android application, open **Menu (`☰`) → Open-Source Licences** (or **Settings → Licences**) to browse and read every bundled license file offline.

## Artwork & Design Assets

Trap's launcher icons, adaptive/monochrome layers, SecureDrop chrome vectors (`res/drawable/ic_ov_*.xml`), and Neon Graffiti artwork (`design/icons/`) were created for this repository and are covered by the repository's `Apache-2.0 OR MIT` license.
