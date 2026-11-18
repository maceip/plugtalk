# Bundled Third-Party License Texts

Verbatim license texts for LGPL/GPL-governed components packaged with Trap live in this directory. Gradle (`copyLegalNotices` in `android/app/build.gradle.kts`) copies `*.txt` into `assets/licenses/` inside the APK so users can read them offline via the hardware remote menu → **Licences**, and CI attaches them alongside every GitHub Release.

| File | Component | SPDX Identifier |
|---|---|---|
| `LGPL-2.1.txt` | [sequoia-openpgp](https://gitlab.com/sequoia-pgp/sequoia-openpgp) (`1.22`, compiled into `libonion_vault.so`) and LGPL-2.1-licensed Chromium subcomponents | `LGPL-2.0-or-later` / `LGPL-2.1-or-later` |
| `LGPL-3.0.txt` | LGPL-3.0-licensed subcomponents | `LGPL-3.0-or-later` |
| `GPL-3.0.txt` | GNU GPL v3.0 incorporated by reference in LGPL-3.0 | `GPL-3.0-or-later` |

The texts are verbatim copies from the [SPDX License List](https://github.com/spdx/license-list-data) with normalized LF line endings.

## Browser Engine & Source Relinking

- **Cefpack Browser Engine (`libcefpack_content.so`, `BSD-3-Clause` / `Apache-2.0 OR MIT`)**: Built directly from pinned upstream Chromium `156.0.8078.96` (`//content/public/android`) in [`https://github.com/maceip/cefpack`](https://github.com/maceip/cefpack) and packaged dynamically as `lib/{arm64-v8a,x86_64}/libcefpack_content.so` via `cefpack-android.aar` (`libonion_vault.so` has zero `DT_NEEDED` browser `.so` dependencies).
- **Sequoia OpenPGP (`libonion_vault.so`, `LGPL-2.0-or-later`)**: Full source code of Trap (`Cargo.toml`, `Cargo.lock`, `build.rs`, `src/`, and `crates/`) is published in this repository so any recipient can modify `sequoia-openpgp`, recompile `libonion_vault.so` with `cargo ndk`, and repackage the APK.
