//! Build script for the `onion-vault` Android library.
//!
//! Two jobs:
//!
//! 1. **Link the prebuilt CEF runtime.**  CEF for Android is distributed as a
//!    prebuilt `libcef.so` plus a cross-compiled C++ wrapper
//!    (`libcef_dll_wrapper.a`).  Neither can be produced by Cargo, so the
//!    sysroot is located through `CEF_ANDROID_ROOT` (falling back to
//!    `CEF_PATH`) and the link directives are emitted from here.  The
//!    vendored `cef-dll-sys` build script performs the same lookup, this one
//!    additionally covers the `onion-vault` cdylib itself.
//!
//! 2. **Package the CEF resources.**  `cef.pak`, `cef_100_percent.pak`,
//!    `icudtl.dat` and `locales/en-US.pak` have to be shipped inside the APK
//!    (`assets/cef/`) and unpacked to `filesDir/cef_resources` on first boot,
//!    because Android's asset manager cannot hand Chromium a memory mapped
//!    file descriptor for them.  This script copies them into the Gradle
//!    assets directory so that `./gradlew assembleDebug` picks them up.

use std::{
    env,
    path::{Path, PathBuf},
};

fn main() {
    println!("cargo::rerun-if-changed=build.rs");
    println!("cargo::rerun-if-env-changed=CEF_ANDROID_ROOT");
    println!("cargo::rerun-if-env-changed=CEF_PATH");
    println!("cargo::rerun-if-env-changed=ONIONVAULT_CEF_CHECK_ONLY");

    let Some(root) = cef_root() else {
        println!(
            "cargo::warning=CEF_ANDROID_ROOT is not set: the CEF runtime is neither linked nor \
             packaged.  Set CEF_ANDROID_ROOT to a CEF Android distribution to build an APK."
        );
        return;
    };

    // The Chromium resources are packaged by the Cefrium SDK AAR itself.
    link_cef(&root);
}

/// Root of the pre-extracted CEF distribution.
fn cef_root() -> Option<PathBuf> {
    env::var("CEF_ANDROID_ROOT")
        .ok()
        .or_else(|| env::var("CEF_PATH").ok())
        .map(PathBuf::from)
        .filter(|p| p.is_dir())
}

/// The directory that holds `libcef.so` for the target: `lib/<abi>/` when
/// the sysroot carries several ABIs, otherwise `lib/`.
fn cef_lib_dir(root: &Path) -> PathBuf {
    let target = env::var("TARGET").unwrap_or_default();
    let abi = if target.starts_with("aarch64") {
        "arm64-v8a"
    } else if target.starts_with("x86_64") {
        "x86_64"
    } else if target.starts_with("armv7") {
        "armeabi-v7a"
    } else {
        ""
    };
    let per_abi = root.join("lib").join(abi);
    if !abi.is_empty() && per_abi.join("libcef.so").is_file() {
        per_abi
    } else {
        root.join("lib")
    }
}

/// Emit the link directives for the prebuilt CEF shared object.
fn link_cef(root: &Path) {
    let lib_dir = cef_lib_dir(root);
    let libcef = lib_dir.join("libcef.so");
    if libcef.is_file() {
        println!("cargo::rustc-link-search=native={}", lib_dir.display());
        println!("cargo::rustc-link-lib=dylib=cef");
        println!("cargo::rerun-if-changed={}", libcef.display());
    } else {
        println!(
            "cargo::warning={} not found: libcef.so will not be linked",
            libcef.display()
        );
    }

    // The C++ wrapper static library, as produced by the NDK toolchain build
    // of the CEF `libcef_dll` wrapper.
    for candidate in [
        root.join("libcef_dll_wrapper.a"),
        root.join("libcef_dll_wrapper/libcef_dll_wrapper.a"),
        root.join("build/libcef_dll_wrapper.a"),
    ] {
        if candidate.is_file() {
            let dir = candidate.parent().unwrap();
            println!("cargo::rustc-link-search=native={}", dir.display());
            println!("cargo::rustc-link-lib=static=cef_dll_wrapper");
            println!("cargo::rerun-if-changed={}", candidate.display());
            break;
        }
    }
}
