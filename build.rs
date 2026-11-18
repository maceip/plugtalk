//! Build script of the `onion-vault` native library (`libonion_vault.so`).
//!
//! Browser rendering and Chromium process management on Android are provided
//! by `cefpack-android.aar` (`https://github.com/maceip/cefpack`);
//! `libonion_vault.so` intentionally has **zero** linkage against
//! `libcefpack_content.so` so it remains self-contained and testable on both
//! host platforms and Android NDK targets.

fn main() {
    println!("cargo::rerun-if-changed=build.rs");

    // Enforce 16 KB ELF PT_LOAD segment alignment explicitly for Android 15+ (API 35+)
    // 16 KB page-size kernels across all NDK linker configurations.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo::rustc-cdylib-link-arg=-Wl,-z,max-page-size=16384");
    }
}
