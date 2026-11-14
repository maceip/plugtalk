//! The command line of the bundling service.
//!
//! ```text
//! cefpack plan                 resolve the plan and check the host
//! cefpack fetch                check out the pinned CEF sources
//! cefpack build                configure and compile CEF for Android
//! cefpack audit [BUNDLE]       check a bundle
//! cefpack bindgen [BUNDLE]     generate the Rust FFI of the bundled version
//! cefpack headers [CEF_DIR]     generate the C API headers and assemble them
//! cefpack pack [BUNDLE]        stage the bundle for the Gradle build
//! cefpack apk <APK>            check a built APK
//! cefpack manifest             print the pinned manifest
//! ```

use std::{path::PathBuf, process::ExitCode};

use anyhow::{bail, Context, Result};
use cefpack::{apk, audit, bindgen, build, fetch, layout::Layout, manifest::Manifest, plan::Plan};

fn main() -> ExitCode {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    match run(&arguments) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("cefpack: {error:#}");
            ExitCode::FAILURE
        }
    }
}

/// Run the service.
fn run(arguments: &[String]) -> Result<()> {
    let command = arguments.first().map(String::as_str).unwrap_or("help");
    let rest = &arguments[arguments.len().min(1)..];

    match command {
        "plan" => command_plan(),
        "fetch" => command_fetch(),
        "build" => command_build(),
        "headers" => {
            let cef_dir = argument(rest, 0)
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("/workspace/.research/cef-source/cef"));
            let bundle = argument(rest, 1)
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("/workspace/.research/cef-bundle"));
            let plan_args = manifest()?.gn_args(false);
            let root = build::headers(&cef_dir, &bundle, &plan_args)?;
            println!("the headers are in {}", root.display());
            Ok(())
        }
        "audit" => {
            let bundle = argument(rest, 0)
                .map(PathBuf::from)
                .unwrap_or_else(default_bundle);
            command_audit(&bundle)
        }
        "bindgen" => {
            let bundle = argument(rest, 0)
                .map(PathBuf::from)
                .unwrap_or_else(default_bundle);
            command_bindgen(&bundle)
        }
        "pack" => {
            let bundle = argument(rest, 0)
                .map(PathBuf::from)
                .unwrap_or_else(default_bundle);
            let staging = argument(rest, 1)
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("/workspace/.research/cef-staging"));
            command_pack(&bundle, &staging)
        }
        "apk" => {
            let apk_path = argument(rest, 0)
                .map(PathBuf::from)
                .context("the APK to audit is missing")?;
            command_apk(&apk_path)
        }
        "manifest" => command_manifest(),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        other => {
            print_help();
            bail!("unknown command {other:?}");
        }
    }
}

/// The argument at an index.
fn argument(arguments: &[String], index: usize) -> Option<String> {
    arguments.get(index).cloned()
}

/// The default bundle directory.
fn default_bundle() -> PathBuf {
    PathBuf::from("/workspace/.research/cef-bundle")
}

/// The pinned manifest.
fn manifest() -> Result<Manifest> {
    Manifest::read(&cefpack::manifest_path())
}

/// Resolve the plan and check the host.
fn command_plan() -> Result<()> {
    let manifest = manifest()?;
    println!("CEF      {}", manifest.engine.cef_version);
    println!("Chromium {}", manifest.engine.chromium_version);
    println!("commit   {}", manifest.source.commit);
    println!(
        "target   android/{}/api{}",
        manifest.target.abi, manifest.target.min_sdk
    );
    println!("NDK      {}", manifest.toolchain.ndk_version);

    match Plan::resolve(manifest) {
        Ok(plan) => {
            println!();
            println!("NDK      {}", plan.ndk.path.display());
            println!("SDK      {}", plan.sdk.path.display());
            println!("depot    {}", plan.depot_tools.path.display());
            println!("sources  {}", plan.source_root.display());
            println!("output   {}", plan.out_dir.display());
            println!("bundle   {}", plan.bundle_dir.display());
            println!();
            println!("the host has everything the build needs");
            Ok(())
        }
        Err(error) => {
            println!();
            println!("the host is not ready: {error:#}");
            Ok(())
        }
    }
}

/// Check out the pinned CEF sources.
fn command_fetch() -> Result<()> {
    let plan = Plan::resolve(manifest()?)?;
    let tree = fetch::fetch(&plan)?;
    println!("the sources are at {}", tree.cef_dir.display());
    fetch::sync(&plan, &tree)?;
    println!("the dependencies are in sync");
    Ok(())
}

/// Configure and compile CEF for Android.
fn command_build() -> Result<()> {
    let debug = std::env::var("CEF_DEBUG").is_ok();
    let plan = Plan::resolve(manifest()?)?;
    let output = build::build(&plan, debug)?;
    println!("the bundle is in {}", output.bundle.display());
    Ok(())
}

/// Check a bundle.
fn command_audit(bundle: &std::path::Path) -> Result<()> {
    let report = audit::audit(bundle)?;
    print!("{}", report.render());
    if !report.is_ok() {
        bail!("the bundle at {} is not sound", bundle.display());
    }
    Ok(())
}

/// Generate the Rust FFI of the bundled version.
fn command_bindgen(bundle: &std::path::Path) -> Result<()> {
    let manifest = manifest()?;

    // The public headers of CEF include a few headers of Chromium.  They are
    // fetched from the pinned revision and verified, so that the bindings
    // are generated against the headers of the engine that is shipped.
    fetch::chromium_headers(bundle, &manifest)?;

    // The identifiers of the colours and of the commands are derived from
    // the headers of Chromium, which have just been fetched.
    let cef_source = std::env::var("CEF_SOURCE_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("/workspace/.research/cef-source/cef"));
    if cef_source.join("tools/make_colorids_header.py").is_file() {
        let generated = build::generate_id_headers(&cef_source, bundle)?;
        println!("the generated headers: {}", generated.join(", "));
    }
    // The wrapper header and the generated bindings belong to the vendored
    // `cef-dll-sys` crate.
    let vendored = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../third_party/cef-dll-sys");
    let wrapper = vendored.join("wrapper.h");
    let output = vendored
        .join("src/bindings")
        .join(bindgen::bindings_file(bindgen::ANDROID_TARGET));
    let ndk = std::env::var("ANDROID_NDK_HOME").unwrap_or_else(|_| {
        std::env::var("ANDROID_NDK_ROOT").unwrap_or_else(|_| "/opt/android-ndk-r27c".to_string())
    });
    let args = bindgen::android_clang_args(std::path::Path::new(&ndk), manifest.target.min_sdk);
    match bindgen::generate(bundle, &wrapper, bindgen::ANDROID_TARGET, &output, &args) {
        Ok(()) => {
            println!("the bindings are in {}", output.display());
            Ok(())
        }
        Err(error) => {
            // The generator needs the headers of Chromium, which are the
            // generated ones of a build.  When they are not at hand, the
            // bindings of another target of the same API version are
            // correct — the version is checked, so a mismatch is refused.
            let desktop = vendored
                .join("src/bindings")
                .join("aarch64_unknown_linux_gnu.rs");
            println!("the generator could not run: {error:#}");
            match bindgen::fallback(bundle, &desktop, &output) {
                Ok(note) => {
                    println!("{note} were used instead: {}", output.display());
                    Ok(())
                }
                Err(fallback_error) => {
                    eprintln!("cefpack: {fallback_error:#}");
                    Err(error)
                }
            }
        }
    }
}

/// Stage a bundle for the Gradle build.
fn command_pack(bundle: &std::path::Path, staging: &std::path::Path) -> Result<()> {
    let manifest = manifest()?;
    let staged = apk::pack(bundle, staging, &manifest.target.abi)?;
    println!("the bundle is staged in {}", staged.root().display());
    Ok(())
}

/// Check a built APK.
fn command_apk(apk_path: &std::path::Path) -> Result<()> {
    let manifest = manifest()?;
    let report = apk::audit_apk(apk_path, &manifest.target.abi, "libonion_vault.so")?;
    print!("{}", report.render());
    if !report.is_ok() {
        bail!("the APK {} is not sound", apk_path.display());
    }
    Ok(())
}

/// Print the pinned manifest.
fn command_manifest() -> Result<()> {
    let manifest = manifest()?;
    print!("{}", manifest.to_toml()?);
    Ok(())
}

/// Print the help.
fn print_help() {
    println!(
        r#"cefpack — the CEF bundling service for Android

  cefpack plan                 resolve the plan and check the host
  cefpack fetch                check out the pinned CEF sources
  cefpack build                configure and compile CEF for Android
  cefpack audit [BUNDLE]       check a bundle
  cefpack bindgen [BUNDLE]     generate the Rust FFI of the bundled version
  cefpack pack [BUNDLE]        stage the bundle for the Gradle build
  cefpack apk <APK>            check a built APK
  cefpack manifest             print the pinned manifest
"#
    );
}

/// Keep the layout module in the binary, it carries the paths.
#[allow(dead_code)]
fn _layout_is_used() -> Layout {
    Layout::new("/")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_unknown_command_is_refused() {
        let error = run(&["frobnicate".to_string()]).expect_err("the command does not exist");
        assert!(error.to_string().contains("unknown command"));
    }

    #[test]
    fn the_help_is_printed_without_a_command() {
        run(&[]).expect("the help is printed");
    }

    #[test]
    fn an_argument_is_read_by_its_index() {
        let arguments = vec!["first".to_string(), "second".to_string()];
        assert_eq!(argument(&arguments, 0).as_deref(), Some("first"));
        assert_eq!(argument(&arguments, 1).as_deref(), Some("second"));
        assert_eq!(argument(&arguments, 2), None);
    }
}
