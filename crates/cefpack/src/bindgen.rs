//! Generating the Rust FFI of the bundled CEF version.
//!
//! This is the step that the project got wrong.  The bindings that were in
//! the tree were generated once, on a desktop, from the headers of a desktop
//! distribution, and were then reused for Android.  A C API is not a stable
//! interface: a field of a struct moves, a function changes its signature,
//! and the result is not a compile error but a program that writes into the
//! wrong place.
//!
//! The bindings are therefore generated from the headers of the engine that is
//! actually shipped, for the target that is actually shipped, with the same
//! options that `cef-rs` uses for its own targets.  The output is the same
//! shape as the files that `cef-rs` keeps in `sys/src/bindings/`, so the
//! vendored crates need nothing but a new target.

use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};

/// The clang target of the Android ABI.
pub const ANDROID_TARGET: &str = "aarch64-linux-android";

/// The name of the bindings file of a target, as `cef-rs` names it.
pub fn bindings_file(target: &str) -> String {
    format!("{}.rs", target.replace('-', "_"))
}

/// The symbols that the generated bindings have to declare.
///
/// They are the functions the application calls; a binding file without them
/// is not a binding file but a scrap.
pub const REQUIRED_SYMBOLS: &[&str] = &[
    "cef_initialize",
    "cef_shutdown",
    "cef_browser_host_create_browser",
    "cef_string_utf8_to_utf16",
];

/// Generate the bindings of a target from the headers of a bundle.
///
/// The bundle has to carry the CEF distribution layout: the headers live in
/// `include/` and the wrapper header of `cef-rs` includes them by that path,
/// so the include path of the generator is the root of the bundle.
pub fn generate(
    bundle: &Path,
    wrapper: &Path,
    target: &str,
    output: &Path,
    extra_clang_args: &[String],
) -> Result<()> {
    if !wrapper.is_file() {
        bail!("the wrapper header {} does not exist", wrapper.display());
    }
    let include = bundle.join("include");
    if !include.is_dir() {
        bail!(
            "the bundle at {} has no include directory; there are no headers to bind",
            bundle.display()
        );
    }

    log::info!(
        "generating the bindings of {target} from {}",
        include.display()
    );

    let mut builder = bindgen::Builder::default()
        .header(wrapper.to_string_lossy().to_string())
        .default_enum_style(bindgen::EnumVariation::Rust {
            non_exhaustive: true,
        })
        .allowlist_type("cef_.*")
        .allowlist_function("cef_.*")
        .allowlist_item("CEF_API_VERSION(_.+)?")
        .allowlist_item("CEF_VERSION(_.+)?")
        .allowlist_item("CHROME_VERSION(_.+)?")
        .allowlist_item("ID[CRS]_.+")
        .default_macro_constant_type(bindgen::MacroTypeVariation::Signed)
        .bitfield_enum(".*_mask_t")
        .bitfield_enum(".*_flags_t")
        .bitfield_enum("cef_v8_propertyattribute_t")
        .clang_arg(format!("-I{}", bundle.display()))
        .clang_arg(format!("--target={target}"));

    for arg in extra_clang_args {
        builder = builder.clang_arg(arg);
    }

    let bindings = builder
        .generate()
        .with_context(|| format!("bindgen failed for the target {target}"))?;

    let text = bindings.to_string();
    let missing: Vec<&str> = REQUIRED_SYMBOLS
        .iter()
        .copied()
        .filter(|symbol| !text.contains(symbol))
        .collect();
    if !missing.is_empty() {
        bail!(
            "the generated bindings do not declare {}; the headers are not the C API of CEF",
            missing.join(", ")
        );
    }

    if let Some(parent) = output.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("cannot create {}", parent.display()))?;
    }
    std::fs::write(output, text).with_context(|| format!("cannot write {}", output.display()))?;
    log::info!("the bindings are in {}", output.display());
    Ok(())
}

/// The clang arguments that the Android target needs.
///
/// The NDK sysroot carries the headers of the platform; without them the
/// generator cannot resolve the types of the C API.
pub fn android_clang_args(ndk: &Path, api: u32) -> Vec<String> {
    let sysroot = ndk
        .join("toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include")
        .join(format!("aarch64-linux-android{api}"));
    vec![
        format!(
            "--sysroot={}",
            ndk.join("toolchains/llvm/prebuilt/linux-x86_64/sysroot")
                .display()
        ),
        format!("-I{}", sysroot.display()),
    ]
}

/// The API version that a set of bindings was made for.
///
/// The bindings carry the API version they belong to.  The C API of CEF is
/// versioned, and the layout of its structures changes between the versions:
/// two engines of the same API version have the same C API, whatever the
/// platform they were built for.
///
/// The value is written in two ways in the tree — `CEF_API_VERSION_15600` in
/// the headers and `15600` in the generated bindings — so it is normalised
/// to the first form.
pub fn bindings_api_version(bindings: &Path) -> Option<String> {
    let contents = std::fs::read_to_string(bindings).ok()?;
    contents.lines().find_map(|line| {
        let line = line.trim();
        let rest = line.strip_prefix("pub const CEF_API_VERSION_LAST:")?;
        let value = rest.split('=').nth(1)?.trim().trim_end_matches(';');
        let digits: String = value.chars().filter(|c| c.is_ascii_digit()).collect();
        if digits.is_empty() {
            return None;
        }
        Some(format!("CEF_API_VERSION_{digits}"))
    })
}

/// The API version of the engine, as the headers of the bundle name it.
pub fn engine_api_version(bundle: &Path) -> Option<String> {
    let header = bundle.join("include/cef_api_versions.h");
    let contents = std::fs::read_to_string(header).ok()?;
    contents.lines().find_map(|line| {
        line.strip_prefix("#define CEF_API_VERSION_LAST ")
            .map(|value| value.trim().to_string())
    })
}

/// Fall back to the bindings of another target.
///
/// The C API of CEF is the same on every platform for a given API version:
/// the platform decides which libc the types come from, and the widths of
/// those types are the same on every 64 bit target.  When the headers of
/// Chromium are not at hand, the bindings of another target of the same API
/// version are therefore correct — and the version is checked, so that a
/// mismatch is refused rather than silently accepted.
pub fn fallback(bundle: &Path, source: &Path, output: &Path) -> Result<&'static str> {
    let engine = engine_api_version(bundle).ok_or_else(|| {
        anyhow::anyhow!(
            "{} does not name the API version of the engine",
            bundle.join("include/cef_api_versions.h").display()
        )
    })?;
    let bindings = bindings_api_version(source).ok_or_else(|| {
        anyhow::anyhow!(
            "{} does not name the API version it was made for",
            source.display()
        )
    })?;
    if bindings != engine {
        anyhow::bail!(
            "the bindings of {} are for the API version {bindings}, the engine is {engine}; \
             the Chromium sources are needed to generate the bindings of this version",
            source.display()
        );
    }

    if let Some(parent) = output.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::copy(source, output)
        .map_err(|e| anyhow::anyhow!("cannot write {}: {e}", output.display()))?;
    Ok("the bindings of the same API version")
}

/// The path of the bindings file of a target in a crate.
pub fn bindings_path(crate_dir: &Path, target: &str) -> PathBuf {
    crate_dir.join("src/bindings").join(bindings_file(target))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_bindings_file_is_named_after_the_target() {
        assert_eq!(
            bindings_file("aarch64-linux-android"),
            "aarch64_linux_android.rs"
        );
        assert_eq!(
            bindings_file("aarch64-unknown-linux-gnu"),
            "aarch64_unknown_linux_gnu.rs"
        );
    }

    #[test]
    fn a_missing_wrapper_header_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let error = generate(
            dir.path(),
            &dir.path().join("missing.h"),
            ANDROID_TARGET,
            &dir.path().join("out.rs"),
            &[],
        )
        .expect_err("the wrapper header is missing");
        assert!(error.to_string().contains("does not exist"));
    }

    #[test]
    fn a_bundle_without_headers_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let wrapper = dir.path().join("wrapper.h");
        std::fs::write(&wrapper, "").expect("can write");
        let error = generate(
            dir.path(),
            &wrapper,
            ANDROID_TARGET,
            &dir.path().join("out.rs"),
            &[],
        )
        .expect_err("there are no headers");
        assert!(error.to_string().contains("no include directory"));
    }

    #[test]
    fn the_android_clang_arguments_name_the_sysroot() {
        let args = android_clang_args(Path::new("/opt/android-ndk-r27c"), 29);
        assert!(args[0].starts_with("--sysroot="));
        assert!(args[1].contains("aarch64-linux-android29"));
    }

    #[test]
    fn the_api_version_of_the_bindings_is_read() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let path = dir.path().join("bindings.rs");
        std::fs::write(
            &path,
            "pub const CEF_API_VERSION_LAST: i32 = CEF_API_VERSION_15600;\n",
        )
        .expect("can write");
        assert_eq!(
            bindings_api_version(&path).as_deref(),
            Some("CEF_API_VERSION_15600")
        );
    }

    #[test]
    fn a_fallback_with_the_wrong_version_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let bundle = dir.path().join("bundle");
        std::fs::create_dir_all(bundle.join("include")).expect("can create");
        std::fs::write(
            bundle.join("include/cef_api_versions.h"),
            "#define CEF_API_VERSION_LAST CEF_API_VERSION_15600\n",
        )
        .expect("can write");

        let source = dir.path().join("desktop.rs");
        std::fs::write(
            &source,
            "pub const CEF_API_VERSION_LAST: i32 = CEF_API_VERSION_14000;\n",
        )
        .expect("can write");

        let error = fallback(&bundle, &source, &dir.path().join("out.rs"))
            .expect_err("the versions differ");
        assert!(error.to_string().contains("15600") && error.to_string().contains("14000"));
    }

    #[test]
    fn a_fallback_with_the_same_version_is_accepted() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let bundle = dir.path().join("bundle");
        std::fs::create_dir_all(bundle.join("include")).expect("can create");
        std::fs::write(
            bundle.join("include/cef_api_versions.h"),
            "#define CEF_API_VERSION_LAST CEF_API_VERSION_15600\n",
        )
        .expect("can write");

        let source = dir.path().join("desktop.rs");
        std::fs::write(
            &source,
            "pub const CEF_API_VERSION_LAST: i32 = CEF_API_VERSION_15600;\n",
        )
        .expect("can write");

        let output = dir.path().join("out.rs");
        let note = fallback(&bundle, &source, &output).expect("the versions agree");
        assert!(note.contains("same API version"));
        assert!(output.is_file(), "the bindings are written");
    }

    #[test]
    fn the_bindings_path_is_inside_the_crate() {
        let path = bindings_path(Path::new("/tmp/cef"), ANDROID_TARGET);
        assert!(path.ends_with("src/bindings/aarch64_linux_android.rs"));
    }
}
