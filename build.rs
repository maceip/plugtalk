//! Build script of the `onion-vault` Android library.
//!
//! The engine of the browser is CEF, built from its own sources with the
//! Android NDK by the `cefpack` service of the workspace.  The service
//! produces a bundle with the shape of a CEF binary distribution:
//!
//! ```text
//! <bundle>/
//!   include/                  the public headers of the built version
//!   lib/libcef.so             the engine
//!   lib/libcef_dll_wrapper.a  the C API shim
//!   Resources/                the resource files of the engine
//!   LICENSE.txt                the licence of the engine
//! ```
//!
//! The root of the bundle comes from `CEF_ANDROID_ROOT`.  This script emits
//! the link directives for the two libraries and copies the resource files
//! into the assets of the application, where the engine maps them out of the
//! APK.

use std::{
    env, fs,
    path::{Path, PathBuf},
};

/// The resources that have to end up in the assets of the APK.
///
/// The engine reads them out of the APK and maps them, therefore they must
/// not be compressed there.
const CEF_RESOURCES: &[&str] = &[
    "icudtl.dat",
    "resources.pak",
    "chrome_100_percent.pak",
    "chrome_200_percent.pak",
    "cef.pak",
    "cef_100_percent.pak",
    "cef_200_percent.pak",
];

fn main() {
    println!("cargo::rerun-if-changed=build.rs");
    println!("cargo::rerun-if-env-changed=CEF_ANDROID_ROOT");
    println!("cargo::rerun-if-env-changed=CEF_PATH");
    println!("cargo::rerun-if-env-changed=ONIONVAULT_CEF_CHECK_ONLY");

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let assets_dir = manifest_dir.join("android/app/src/main/assets/cef");

    let Some(root) = cef_root() else {
        if check_only() {
            println!(
                "cargo::warning=ONIONVAULT_CEF_CHECK_ONLY=1: the engine is neither linked nor \
                 packaged"
            );
        } else {
            println!(
                "cargo::warning=CEF_ANDROID_ROOT is not set: the engine is neither linked nor \
                 packaged.  Set CEF_ANDROID_ROOT to a bundle built by `cefpack build`."
            );
        }
        return;
    };

    link_cef(&root);
    package_resources(&root, &assets_dir);
}

/// `true` when the build must not require a bundle.
fn check_only() -> bool {
    env::var("ONIONVAULT_CEF_CHECK_ONLY")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

/// Root of the bundle of the engine.
fn cef_root() -> Option<PathBuf> {
    env::var("CEF_ANDROID_ROOT")
        .ok()
        .or_else(|| env::var("CEF_PATH").ok())
        .map(PathBuf::from)
        .filter(|p| p.is_dir())
}

/// Emit the link directives for the engine and the C API shim.
fn link_cef(root: &Path) {
    let lib_dir = root.join("lib");
    let libcef = lib_dir.join("libcef.so");
    if libcef.is_file() {
        println!("cargo::rustc-link-search=native={}", lib_dir.display());
        println!("cargo::rustc-link-lib=dylib=cef");
        println!("cargo::rerun-if-changed={}", libcef.display());
    } else {
        println!(
            "cargo::warning={} not found: the engine will not be linked",
            libcef.display()
        );
    }

    // The C API shim: the C functions of CEF are implemented on top of the
    // C++ API by this static archive.  A distribution that carries the shim
    // inside the shared object does not need it.
    let wrapper = lib_dir.join("libcef_dll_wrapper.a");
    if wrapper.is_file() {
        println!("cargo::rustc-link-lib=static=cef_dll_wrapper");
        println!("cargo::rerun-if-changed={}", wrapper.display());
    } else {
        println!(
            "cargo::warning={} not found: the shared object is expected to carry the C API \
             itself",
            wrapper.display()
        );
    }
}

/// Copy the resource files of the engine into the assets of the application.
fn package_resources(root: &Path, assets_dir: &Path) {
    if let Err(err) = fs::create_dir_all(assets_dir) {
        println!(
            "cargo::warning=cannot create {}: {err}",
            assets_dir.display()
        );
        return;
    }

    let mut copied = 0usize;
    for name in CEF_RESOURCES {
        let src = root.join("Resources").join(name);
        if src.is_file() {
            if let Err(err) = fs::copy(&src, assets_dir.join(name)) {
                println!("cargo::warning=cannot copy {}: {err}", src.display());
            } else {
                copied += 1;
            }
        }
    }

    // The locales live in their own directory.
    let locales_src = root.join("Resources/locales");
    let locales_dst = assets_dir.join("locales");
    if locales_src.is_dir() {
        if let Err(err) = fs::create_dir_all(&locales_dst) {
            println!(
                "cargo::warning=cannot create {}: {err}",
                locales_dst.display()
            );
        } else if let Ok(entries) = fs::read_dir(&locales_src) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() {
                    let _ = fs::copy(&path, locales_dst.join(entry.file_name()));
                    copied += 1;
                }
            }
        }
    }

    if copied == 0 {
        println!(
            "cargo::warning=no resource file of the engine was found below {}: the browser will \
             not be able to render anything",
            root.join("Resources").display()
        );
    } else {
        println!("cargo::warning=packaged {copied} resource file(s) of the engine");
    }
}
