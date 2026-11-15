//! Build script of the `onion-vault` native library (`libonion_vault.so`).
//!
//! Browser rendering and Chromium process management on Android are provided
//! by the `com.cefrium:cefrium-sdk` AAR; `libonion_vault.so` intentionally has
//! **zero** linkage against `libcef.so` so it remains self-contained and
//! testable on both host platforms and Android NDK targets.

fn main() {
    println!("cargo::rerun-if-changed=build.rs");
}
