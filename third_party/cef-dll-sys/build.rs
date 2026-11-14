//! Build script for the vendored, Android-patched `cef-dll-sys` crate.
//!
//! Upstream `cef-dll-sys` (https://github.com/tauri-apps/cef-rs) resolves a CEF
//! *desktop* binary distribution for the host triple, compiles the C++ wrapper
//! (`libcef_dll_wrapper`) with CMake and links `libcef`.
//!
//! OnionVault targets `aarch64-linux-android`, which upstream does not support:
//! there is no `cef_linux_aarch64` distribution for the Android ABI, the
//! wrapper has to be cross-compiled with the NDK toolchain, and `libcef.so`
//! must end up inside the APK instead of next to the host binary.
//!
//! This build script therefore keeps the upstream behaviour for desktop
//! targets and adds an Android path that
//!
//!   * consumes a pre-extracted CEF *Android* sysroot pointed at by
//!     `CEF_ANDROID_ROOT` (or `CEF_PATH`),
//!   * links the prebuilt `libcef.so` plus the cross-compiled
//!     `libcef_dll_wrapper.a` found in that sysroot, and
//!   * can be switched into "check only" mode (`ONIONVAULT_CEF_CHECK_ONLY=1`)
//!     in which no CEF distribution is required at all.  `cargo check` never
//!     links, so this keeps the type check of the whole workspace usable on a
//!     machine without the (multi gigabyte) CEF Android distribution.

use std::{
    env, fs,
    path::{Path, PathBuf},
};

/// `true` when the build must not require a CEF distribution.
fn check_only() -> bool {
    env::var("ONIONVAULT_CEF_CHECK_ONLY")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

/// `true` when the target triple is an Android ABI.
fn is_android(target: &str) -> bool {
    target.contains("android")
}

/// Root of the pre-extracted CEF distribution for the current build.
fn cef_root() -> Option<PathBuf> {
    env::var("CEF_ANDROID_ROOT")
        .ok()
        .or_else(|| env::var("CEF_PATH").ok())
        .map(PathBuf::from)
        .filter(|p| p.exists())
}

fn main() -> anyhow::Result<()> {
    println!("cargo::rerun-if-changed=build.rs");
    println!("cargo::rerun-if-env-changed=CEF_ANDROID_ROOT");
    println!("cargo::rerun-if-env-changed=CEF_PATH");
    println!("cargo::rerun-if-env-changed=ONIONVAULT_CEF_CHECK_ONLY");
    println!("cargo::rerun-if-env-changed=FLATPAK");
    println!("cargo::rerun-if-env-changed=NIX_CEF_BINARY");

    let target = env::var("TARGET")?;
    let out_dir = PathBuf::from(env::var("OUT_DIR")?);

    if is_android(&target) {
        return build_android(&target, &out_dir);
    }

    if check_only() {
        // Desktop check-only mode: no distribution, no wrapper, no link step.
        return Ok(());
    }

    desktop_build(&target, &out_dir)
}

/// Android build: link against the bundle of the engine.
///
/// The bundle is produced by the `cefpack` service of the workspace, which
/// builds CEF from its own sources with the Android NDK.  It has the shape of
/// a CEF binary distribution:
///
/// ```text
/// <bundle>/include/                  the public headers of that version
/// <bundle>/lib/libcef.so             the engine
/// <bundle>/lib/libcef_dll_wrapper.a  the C API shim
/// <bundle>/Resources/                the resource files
/// ```
///
/// Two things are checked here, because both of them fail silently
/// otherwise:
///
/// * the bindings that are used have to be the ones of the **version that is
///   shipped**.  The bindings of another version have the same names and
///   another layout of the structures, and the result is not a compile error
///   but a program that writes into the wrong place.
/// * the library of the application has to declare the engine as a
///   dependency, so that the dynamic linker finds it at load time.
fn build_android(target: &str, out_dir: &Path) -> anyhow::Result<()> {
    let Some(root) = cef_root() else {
        if check_only() {
            println!(
                "cargo::warning=ONIONVAULT_CEF_CHECK_ONLY=1: skipping the CEF link step; \
                 set CEF_ANDROID_ROOT to a bundle built by `cefpack build` to build a binary"
            );
            return Ok(());
        }
        anyhow::bail!(
            "no bundle of the engine found.\n\
             Set CEF_ANDROID_ROOT to a bundle built by `cefpack build`, e.g.\n    \
             export CEF_ANDROID_ROOT=/workspace/.research/cef-bundle\n\
             The bundle has to carry include/, lib/libcef.so and \
             lib/libcef_dll_wrapper.a."
        );
    };

    let lib_dir = root.join("lib");
    let libcef = lib_dir.join("libcef.so");
    if !libcef.exists() {
        if check_only() {
            println!(
                "cargo::warning=ONIONVAULT_CEF_CHECK_ONLY=1: {} does not exist; the link step is \
                 skipped",
                libcef.display()
            );
            check_api_version(&root)?;
            return Ok(());
        }
        anyhow::bail!("{} does not exist", libcef.display());
    }
    println!("cargo::rerun-if-changed={}", libcef.display());
    println!("cargo::rustc-link-search=native={}", lib_dir.display());
    println!("cargo::rustc-link-lib=dylib=cef");

    // The C API shim: the C functions of CEF are implemented on top of the
    // C++ API by this static archive.  A distribution that carries the shim
    // inside the shared object does not need it.
    let wrapper = lib_dir.join("libcef_dll_wrapper.a");
    if wrapper.exists() {
        println!("cargo::rerun-if-changed={}", wrapper.display());
        println!("cargo::rustc-link-lib=static=cef_dll_wrapper");
    } else {
        println!(
            "cargo::warning={} does not exist: the shared object is expected to carry the C \
             API itself",
            wrapper.display()
        );
    }

    // The bindings have to be the ones of the version that is shipped.
    check_api_version(&root)?;

    println!("cargo::metadata=CEF_ANDROID_ROOT={}", root.display());
    let _ = out_dir;
    let _ = target;
    Ok(())
}

/// The API version of the engine, as the headers of the bundle name it.
fn bundle_api_version(root: &Path) -> Option<String> {
    let header = root.join("include/cef_api_versions.h");
    let contents = std::fs::read_to_string(header).ok()?;
    contents.lines().find_map(|line| {
        line.strip_prefix("#define CEF_API_VERSION_LAST ")
            .map(|value| value.trim().to_string())
    })
}

/// Check that the bindings are the ones of the shipped version.
///
/// The generated bindings carry the API version they were made for.  The
/// headers of the bundle carry the API version of the engine.  When the two
/// differ, the structures of the C API have another layout and every call
/// into the engine reads the wrong place — a failure that no compiler and no
/// test notices.
fn check_api_version(root: &Path) -> anyhow::Result<()> {
    let Some(expected) = bundle_api_version(root) else {
        anyhow::bail!(
            "{} does not name the API version of the engine; the bundle is incomplete",
            root.join("include/cef_api_versions.h").display()
        );
    };

    let bindings = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("src/bindings")
        .join("aarch64_linux_android.rs");
    if !bindings.exists() {
        anyhow::bail!(
            "{} does not exist; run `cefpack bindgen <bundle>` to generate the bindings of \
             the engine",
            bindings.display()
        );
    }
    println!("cargo::rerun-if-changed={}", bindings.display());

    let contents = std::fs::read_to_string(&bindings)?;
    // The value is written as `CEF_API_VERSION_15600` in the headers and as
    // `15600` in the generated bindings; it is normalised to the first form.
    let found = contents.lines().find_map(|line| {
        let line = line.trim();
        let rest = line.strip_prefix("pub const CEF_API_VERSION_LAST:")?;
        let value = rest.split('=').nth(1)?.trim().trim_end_matches(';');
        let digits: String = value.chars().filter(|c| c.is_ascii_digit()).collect();
        if digits.is_empty() {
            return None;
        }
        Some(format!("CEF_API_VERSION_{digits}"))
    });
    match found {
        Some(found) if found == expected => {
            println!(
                "cargo::warning=the bindings and the engine agree on the API version {expected}"
            );
            Ok(())
        }
        Some(found) => anyhow::bail!(
            "the bindings are for the API version {found}, the engine is {expected}; run \
             `cefpack bindgen <bundle>` to regenerate them"
        ),
        None => anyhow::bail!(
            "{} does not name the API version it was made for",
            bindings.display()
        ),
    }
}

/// Desktop build: unchanged upstream behaviour (download + CMake + Ninja).
fn desktop_build(target: &str, out_dir: &Path) -> anyhow::Result<()> {
    use download_cef::OsAndArch;

    let os_arch = OsAndArch::try_from(target)?;
    let package_version = env::var("CARGO_PKG_VERSION")?;
    let cef_version = download_cef::default_version(&package_version);

    let check_archive = |path: &Path| -> anyhow::Result<()> {
        download_cef::check_archive_json(&package_version, &path.to_string_lossy())?;
        Ok(())
    };

    let resolve_cef_dir = |location: &Path| -> anyhow::Result<PathBuf> {
        let cef_dir = location.join(os_arch.to_string());
        if !fs::exists(&cef_dir)? {
            use download_cef::CefIndex;
            let download_url = download_cef::default_download_url();
            let index = CefIndex::download_from(&download_url)?;
            let platform = index.platform(target)?;
            let version = platform.version(&cef_version)?;
            let archive = version.download_archive_from(&download_url, location, false)?;
            let extracted_dir = download_cef::extract_target_archive(target, &archive, location, false)?;
            let extracted_dir_canonical = fs::canonicalize(&extracted_dir)?;
            let cef_dir_canonical = fs::canonicalize(&cef_dir)?;
            if extracted_dir_canonical != cef_dir_canonical {
                anyhow::bail!(
                    "extracted dir {extracted_dir_canonical:?} does not match cef_dir {cef_dir_canonical:?}"
                );
            }
            version.write_archive_json(extracted_dir)?;
        }
        Ok(cef_dir)
    };

    let cef_dir = if let Ok(cef_path) = env::var("CEF_PATH") {
        let configured_path = PathBuf::from(cef_path);
        if fs::exists(&configured_path)? {
            let versioned = configured_path.join(&cef_version);
            if fs::exists(&versioned)? {
                resolve_cef_dir(&versioned)?
            } else {
                match check_archive(&configured_path) {
                    Ok(()) => configured_path,
                    Err(_) => resolve_cef_dir(&versioned)?,
                }
            }
        } else {
            resolve_cef_dir(&configured_path)?
        }
    } else {
        resolve_cef_dir(out_dir)?
    };

    let cef_dir_str = cef_dir.to_string_lossy().into_owned();
    println!("cargo::rerun-if-changed={cef_dir_str}");
    println!("cargo::metadata=CEF_DIR={cef_dir_str}");
    println!("cargo::rustc-link-search=native={cef_dir_str}");

    let api_version = cef_api_version_last(&cef_dir)?;
    println!("cargo::metadata=CEF_API_VERSION={api_version}");

    let mut cef_dll_wrapper = cmake::Config::new(&cef_dir);
    cef_dll_wrapper
        .generator("Ninja")
        .profile("RelWithDebInfo")
        .build_target("libcef_dll_wrapper")
        .define("CEF_COMPILER_DEFINES", format!("CEF_API_VERSION={api_version}"));

    let project_arch = match os_arch.arch {
        "aarch64" => "arm64",
        arch => arch,
    };
    let sandbox = if cfg!(feature = "sandbox") { "ON" } else { "OFF" };

    match os_arch.os {
        "linux" | "windows" => {
            copy_cef_runtime_files(&cef_dir, out_dir)?;
            if os_arch.os == "windows" {
                for lib in [
                    "comctl32", "delayimp", "mincore", "powrprof", "propsys", "runtimeobject",
                    "setupapi", "shcore", "shell32", "shlwapi", "user32", "version", "wbemuuid",
                    "winmm",
                ] {
                    println!("cargo::rustc-link-lib=dylib={lib}");
                }
                cef_dll_wrapper
                    .define("CMAKE_MSVC_RUNTIME_LIBRARY", "MultiThreaded")
                    .define("CMAKE_OBJECT_PATH_MAX", "500")
                    .define("PROJECT_ARCH", project_arch)
                    .define("USE_SANDBOX", sandbox);
            }
            let build_dir = cef_dll_wrapper.build().to_string_lossy().into_owned();
            println!("cargo::rustc-link-search=native={build_dir}/build/libcef_dll_wrapper");
            println!("cargo::rustc-link-lib=static=libcef_dll_wrapper");
            if os_arch.os == "linux" {
                println!("cargo::rustc-link-lib=dylib=cef");
            } else {
                println!("cargo::rustc-link-lib=dylib=libcef");
            }
        }
        "macos" => {
            println!("cargo::rustc-link-lib=framework=AppKit");
            let build_dir = cef_dll_wrapper
                .no_default_flags(true)
                .define("PROJECT_ARCH", project_arch)
                .define("USE_SANDBOX", sandbox)
                .build()
                .to_string_lossy()
                .into_owned();
            println!("cargo::rustc-link-search=native={build_dir}/build/libcef_dll_wrapper");
            println!("cargo::rustc-link-lib=static=cef_dll_wrapper");
        }
        os => anyhow::bail!("unknown target {os}"),
    }

    Ok(())
}

/// `CEF_API_VERSION_LAST` from the distribution's `include/cef_api_versions.h`.
fn cef_api_version_last(cef_dir: &Path) -> anyhow::Result<u32> {
    let header = cef_dir.join("include").join("cef_api_versions.h");
    let contents = fs::read_to_string(&header)?;
    contents
        .lines()
        .find_map(|line| {
            line.strip_prefix("#define CEF_API_VERSION_LAST ")?
                .trim()
                .strip_prefix("CEF_API_VERSION_")?
                .parse()
                .ok()
        })
        .ok_or_else(|| anyhow::anyhow!("no CEF_API_VERSION_LAST in {}", header.display()))
}

fn copy_directory(src: &Path, dest: &Path) -> Result<(), std::io::Error> {
    fs::create_dir_all(dest)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_file() {
            let target = dest.join(entry.file_name());
            if target.is_file() {
                fs::remove_file(&target)?;
            }
            fs::copy(path, target)?;
        }
    }
    Ok(())
}

fn copy_cef_runtime_files(cef_dir: &Path, target_dir: &Path) -> Result<(), std::io::Error> {
    copy_directory(cef_dir, target_dir)?;
    copy_directory(&cef_dir.join("locales"), &target_dir.join("locales"))
}
