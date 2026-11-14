//! Driving the Chromium build of CEF for Android, and collecting the result.
//!
//! The build is the ordinary Chromium build: `gn` configures it, `ninja`
//! compiles it, the NDK is the toolchain.  CEF contributes the `use_cef`
//! argument, its patches and its two libraries, `libcef` (the C++ API) and
//! `libcef_dll` (the C API shim that `cef-rs` links against).
//!
//! The collection step is the part that turns a build output into a
//! *distribution*: the headers come from the CEF checkout, the resources
//! from the build output, the libraries from the build output.  Nothing is
//! downloaded, because everything is built.

use std::{
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{bail, Context, Result};

use crate::{layout::Layout, plan::Plan, BundleManifest, BUNDLE_SCHEMA};

/// The result of a build.
#[derive(Debug, Clone)]
pub struct BuildOutput {
    /// The directory that holds the build output.
    pub out_dir: PathBuf,
    /// The bundle that was assembled.
    pub bundle: PathBuf,
}

/// Configure and compile the bundle.
///
/// `debug` builds the debug configuration, which is what a development
/// machine wants; the release configuration is what a shipped application
/// links against.
pub fn build(plan: &Plan, debug: bool) -> Result<BuildOutput> {
    let cef_dir = plan.source_root.join("cef");
    if !cef_dir.join("libcef").is_dir() {
        bail!(
            "{} does not look like a CEF checkout; run `cefpack fetch` first",
            cef_dir.display()
        );
    }

    apply_patches(plan, &cef_dir)?;

    let args = build_args(plan, debug);
    log::info!("gn gen with {args}");

    let gn = plan.depot_tools.path.join("gn");
    let status = Command::new(&gn)
        .current_dir(plan.source_root.join("src"))
        .args([
            "gen",
            plan.out_dir.to_string_lossy().as_ref(),
            &format!("--args={args}"),
        ])
        .env("PATH", path_with_depot_tools(plan))
        .status()
        .with_context(|| format!("cannot run {}", gn.display()))?;
    if !status.success() {
        bail!("gn gen failed with {status}");
    }

    // `libcef` is the engine and `libcef_dll_wrapper` is the C API shim.
    // Both are needed: the application links the shim, the shim calls the
    // engine.
    let ninja = plan.depot_tools.path.join("ninja");
    let status = Command::new(&ninja)
        .current_dir(plan.out_dir.clone())
        .args([
            "-C",
            &plan.out_dir.to_string_lossy(),
            "libcef",
            "libcef_dll_wrapper",
        ])
        .env("PATH", path_with_depot_tools(plan))
        .status()
        .with_context(|| format!("cannot run {}", ninja.display()))?;
    if !status.success() {
        bail!("ninja failed with {status}");
    }

    let bundle = collect(plan, debug)?;
    Ok(BuildOutput {
        out_dir: plan.out_dir.clone(),
        bundle,
    })
}

/// Apply the patches of CEF to Chromium.
///
/// CEF keeps its patches in `cef/patch/patches` and applies them with
/// `cef/tools/patch.py`.  The step is skipped when the patch has already been
/// applied, so it can be run again.
fn apply_patches(plan: &Plan, cef_dir: &Path) -> Result<()> {
    let patcher = cef_dir.join("tools/patch.py");
    if !patcher.is_file() {
        bail!(
            "{} has no patcher; the checkout is not a CEF checkout",
            cef_dir.display()
        );
    }
    let status = Command::new("python3")
        .current_dir(cef_dir)
        .arg(&patcher)
        .env("PATH", path_with_depot_tools(plan))
        .status()
        .with_context(|| format!("cannot run {}", patcher.display()))?;
    if !status.success() {
        bail!("the patcher failed with {status}");
    }
    Ok(())
}

/// Generate the C API headers of a CEF checkout.
///
/// CEF checks in the C++ headers but **generates** the C API headers: the
/// `include/capi/cef_*_capi.h` files are written by `tools/version_manager.py`
/// from the C++ headers.  The Rust bindings are generated from the C API
/// headers, so this step has to run before them.
///
/// The tool ends with a formatting pass that needs the clang of the Chromium
/// toolchain.  That pass is a convenience, not a part of the output, so the
/// verdict of this function is the *content* of `include/capi/` and not the
/// exit code of the tool.
pub fn generate_capi_headers(cef_dir: &Path) -> Result<usize> {
    let tool = cef_dir.join("tools/version_manager.py");
    if !tool.is_file() {
        bail!(
            "{} has no version manager; the checkout is not a CEF checkout",
            cef_dir.display()
        );
    }

    let output = Command::new("python3")
        .current_dir(cef_dir)
        .arg(&tool)
        .arg(cef_dir)
        .output()
        .with_context(|| format!("cannot run {}", tool.display()))?;

    let capi = cef_dir.join("include/capi");
    let generated = std::fs::read_dir(&capi)
        .with_context(|| format!("cannot read {}", capi.display()))?
        .flatten()
        .filter(|entry| entry.path().is_file())
        .count();

    if generated < 10 {
        bail!(
            "the C API headers were not generated ({} files in {}); the tool said: {}",
            generated,
            capi.display(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    log::info!("{} C API headers are in {}", generated, capi.display());
    Ok(generated)
}

/// The headers that CEF generates during the build.
#[derive(Debug, Clone)]
pub struct GeneratedHeaders {
    /// The C API headers in `include/capi/`.
    pub capi: usize,
    /// The headers that the build generates, by name.
    pub generated: Vec<String>,
}

/// Generate every header that a CEF checkout does not carry.
///
/// CEF checks in the C++ headers but generates four more:
///
/// * `include/capi/cef_*_capi.h` — the C API, written by the translator,
/// * `include/cef_config.h` — the configuration of the build,
/// * `include/cef_version.h` — the version of the engine,
/// * `include/cef_api_versions.h` — the versions of the C API.
///
/// All four are written by tools that live in the CEF checkout and that can
/// run without a complete build of Chromium.  That matters: the Rust
/// bindings are generated from these headers, and regenerating them after a
/// change of the engine must not take hours.
pub fn generate_headers(
    cef_dir: &Path,
    args: &[String],
    include: &Path,
) -> Result<GeneratedHeaders> {
    std::fs::create_dir_all(include)
        .with_context(|| format!("cannot create {}", include.display()))?;

    // The C API headers.  They are written into the checkout, so this has
    // to happen before the checkout is copied.
    let capi = generate_capi_headers(cef_dir)?;

    // The arguments of the build, written where the generators can read
    // them.  The configuration header is derived from them, so the header
    // and the build always agree.  They live in a work directory that does
    // not belong to the bundle.
    let work = include.parent().unwrap_or(include).join(".work");
    std::fs::create_dir_all(&work).with_context(|| format!("cannot create {}", work.display()))?;
    let args_file = work.join("args.gn");
    std::fs::write(&args_file, args.join("\n"))
        .with_context(|| format!("cannot write {}", args_file.display()))?;

    let mut generated = Vec::new();

    // The configuration header.
    let config = include.join("cef_config.h");
    run_python(
        cef_dir,
        &[
            "tools/make_config_header.py",
            &config.to_string_lossy(),
            &args_file.to_string_lossy(),
        ],
    )?;
    generated.push("cef_config.h".to_string());

    // The version header.
    let version = include.join("cef_version.h");
    run_python(
        cef_dir,
        &["tools/make_version_header.py", &version.to_string_lossy()],
    )?;
    generated.push("cef_version.h".to_string());

    // The versions of the C API.
    let api_versions = include.join("cef_api_versions.h");
    let api_inc = include.join("cef_api_versions.inc");
    run_python(
        cef_dir,
        &[
            "tools/make_api_versions_header.py",
            &api_versions.to_string_lossy(),
            &api_inc.to_string_lossy(),
            "cef_api_versions.json",
            "cef_api_untracked.json",
        ],
    )?;
    generated.push("cef_api_versions.h".to_string());

    let _ = std::fs::remove_dir_all(&work);

    Ok(GeneratedHeaders { capi, generated })
}

/// Generate the identifiers of the colours and of the commands.
///
/// The build derives them from the headers of Chromium: the list of the
/// colours of the interface and the list of the commands of the browser.
/// The headers of Chromium have to be in the bundle already; they are
/// fetched by the caller.
pub fn generate_id_headers(cef_dir: &Path, bundle: &Path) -> Result<Vec<String>> {
    let include = bundle.join("include");
    let chromium = bundle;
    let mut generated = Vec::new();

    // The identifiers of the colours.
    let color_ids = include.join("cef_color_ids.h");
    let color_input = chromium.join("ui/color/color_id.h");
    if color_input.is_file() {
        run_python(
            cef_dir,
            &[
                "tools/make_colorids_header.py",
                &color_ids.to_string_lossy(),
                &color_input.to_string_lossy(),
                &chromium
                    .join("components/color/color_id.h")
                    .to_string_lossy(),
                &chromium
                    .join("chrome/browser/ui/color/chrome_color_id.h")
                    .to_string_lossy(),
            ],
        )?;
        generated.push("cef_color_ids.h".to_string());
    }

    // The identifiers of the commands.
    let command_ids = include.join("cef_command_ids.h");
    let command_input = chromium.join("chrome/app/chrome_command_ids.h");
    if command_input.is_file() {
        let inc = include.join("cef_command_ids.inc");
        run_python(
            cef_dir,
            &[
                "tools/make_pack_header.py",
                &command_ids.to_string_lossy(),
                &inc.to_string_lossy(),
                &command_input.to_string_lossy(),
            ],
        )?;
        generated.push("cef_command_ids.h".to_string());
    }

    Ok(generated)
}

/// Run a Python tool of the CEF checkout.
fn run_python(cef_dir: &Path, args: &[&str]) -> Result<()> {
    let status = Command::new("python3")
        .current_dir(cef_dir)
        .args(args)
        .status()
        .with_context(|| format!("cannot run python3 {}", args[0]))?;
    if !status.success() {
        bail!("{} failed with {status}", args[0]);
    }
    Ok(())
}

/// Assemble the headers of a CEF checkout into a bundle.
///
/// The headers are what the Rust bindings are generated from.  Assembling
/// them on their own makes it possible to regenerate the bindings without a
/// complete build of Chromium, which takes hours.
pub fn headers(cef_dir: &Path, bundle: &Path, args: &[String]) -> Result<PathBuf> {
    let layout = Layout::new(bundle);

    // The C++ headers of the checkout, with the generated C API headers
    // that the translator has just written into it.
    generate_capi_headers(cef_dir)?;
    std::fs::create_dir_all(layout.include())?;
    copy_tree(&cef_dir.join("include"), &layout.include())?;

    // The three headers that the build generates.
    let generated = generate_headers(cef_dir, args, &layout.include())?;
    log::info!(
        "{} C API headers and {} generated headers are in {}",
        generated.capi,
        generated.generated.len(),
        layout.include().display()
    );
    Ok(layout.root().to_path_buf())
}

/// The GN arguments of the build, as one string.
fn build_args(plan: &Plan, debug: bool) -> String {
    let mut args = plan.gn_args(debug);
    args.push(format!(
        "cef_path=\"{}\"",
        plan.source_root.join("cef").display()
    ));
    args.join(" ")
}

/// Collect the build output into a bundle.
fn collect(plan: &Plan, debug: bool) -> Result<PathBuf> {
    let layout = Layout::new(&plan.bundle_dir);
    std::fs::create_dir_all(layout.root())
        .with_context(|| format!("cannot create {}", layout.root().display()))?;
    std::fs::create_dir_all(layout.include())?;
    std::fs::create_dir_all(layout.lib())?;
    std::fs::create_dir_all(layout.resources())?;

    // The headers come from the CEF checkout: they are the public API of the
    // version that was built.  The directory is copied whole, because the
    // C API headers live in `include/capi/` and the wrapper header of
    // `cef-rs` includes them by that path.
    copy_tree(&plan.source_root.join("cef/include"), &layout.include())?;

    // The libraries come from the build output.
    copy_file(&plan.out_dir.join("libcef.so"), &layout.libcef())?;
    for name in ["libcef_dll_wrapper.a", "libcef_dll_wrapper.a.a"] {
        let source = plan.out_dir.join(name);
        if source.is_file() {
            copy_file(&source, &layout.wrapper())?;
            break;
        }
    }
    if !layout.wrapper().is_file() {
        bail!(
            "the build produced no libcef_dll_wrapper.a in {}",
            plan.out_dir.display()
        );
    }

    // The resources come from the build output.
    for name in [
        "icudtl.dat",
        "resources.pak",
        "chrome_100_percent.pak",
        "chrome_200_percent.pak",
        "cef.pak",
        "cef_100_percent.pak",
        "cef_200_percent.pak",
    ] {
        let source = plan.out_dir.join(name);
        if source.is_file() {
            copy_file(&source, &layout.resource(name))?;
        }
    }
    let locales = plan.out_dir.join("locales");
    if locales.is_dir() {
        copy_tree(&locales, &layout.locales())?;
    }

    // The licences come from the CEF checkout.
    copy_file(&plan.source_root.join("cef/LICENSE.txt"), &layout.license())?;

    // The provenance.
    let manifest = BundleManifest {
        schema: BUNDLE_SCHEMA,
        cef_version: plan.manifest.engine.cef_version.clone(),
        chromium_version: plan.manifest.engine.chromium_version.clone(),
        cef_revision: plan.manifest.source.commit.clone(),
        target_os: plan.manifest.target.os.clone(),
        abi: plan.manifest.target.abi.clone(),
        min_sdk: plan.manifest.target.min_sdk,
        ndk_version: plan.manifest.toolchain.ndk_version.clone(),
        gn_args: plan.gn_args(debug),
        libcef_sha256: crate::sha256_file(&layout.libcef())?,
        wrapper_sha256: crate::sha256_file(&layout.wrapper())?,
        header_count: layout.headers()?.len(),
        resources: resource_names(&layout),
        licences: licence_names(&layout),
    };
    manifest.write(layout.root())?;

    log::info!("the bundle is in {}", layout.root().display());
    Ok(layout.root().to_path_buf())
}

/// The names of the resources of a bundle.
fn resource_names(layout: &Layout) -> Vec<String> {
    let mut names = Vec::new();
    if let Ok(entries) = std::fs::read_dir(layout.resources()) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() {
                names.push(
                    path.file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .to_string(),
                );
            } else if path.is_dir() {
                names.push(format!(
                    "locales/{}",
                    path.file_name().unwrap_or_default().to_string_lossy()
                ));
            }
        }
    }
    names.sort();
    names
}

/// The names of the licence documents of a bundle.
fn licence_names(layout: &Layout) -> Vec<String> {
    let mut names = Vec::new();
    for path in [layout.license(), layout.notice()] {
        if path.is_file() {
            names.push(
                path.file_name()
                    .unwrap_or_default()
                    .to_string_lossy()
                    .to_string(),
            );
        }
    }
    names
}

/// Copy a file, creating the directory of the destination.
fn copy_file(source: &Path, destination: &Path) -> Result<()> {
    if !source.is_file() {
        bail!("{} does not exist", source.display());
    }
    if let Some(parent) = destination.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::copy(source, destination).with_context(|| {
        format!(
            "cannot copy {} to {}",
            source.display(),
            destination.display()
        )
    })?;
    Ok(())
}

/// Copy a directory tree.
fn copy_tree(source: &Path, destination: &Path) -> Result<()> {
    if !source.is_dir() {
        bail!("{} does not exist", source.display());
    }
    for entry in walkdir::WalkDir::new(source)
        .into_iter()
        .filter_map(Result::ok)
    {
        let path = entry.path();
        let relative = path
            .strip_prefix(source)
            .with_context(|| format!("{} is not below {}", path.display(), source.display()))?;
        let target = destination.join(relative);
        if path.is_dir() {
            std::fs::create_dir_all(&target)?;
        } else {
            copy_file(path, &target)?;
        }
    }
    Ok(())
}

/// The PATH of the build, with depot_tools in front of it.
fn path_with_depot_tools(plan: &Plan) -> String {
    let depot = plan.depot_tools.path.display().to_string();
    match std::env::var("PATH") {
        Ok(current) if !current.is_empty() => format!("{depot}:{current}"),
        _ => depot,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::Layout;

    #[test]
    fn copying_a_missing_file_fails_with_its_name() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let error = copy_file(&dir.path().join("missing"), &dir.path().join("target"))
            .expect_err("the source is missing");
        assert!(error.to_string().contains("missing"));
    }

    #[test]
    fn a_tree_is_copied_with_its_structure() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let source = dir.path().join("source");
        std::fs::create_dir_all(source.join("inner")).expect("can create");
        std::fs::write(source.join("inner/file"), "body").expect("can write");
        let destination = dir.path().join("destination");
        copy_tree(&source, &destination).expect("can copy");
        assert_eq!(
            std::fs::read_to_string(destination.join("inner/file")).expect("can read"),
            "body"
        );
    }

    #[test]
    fn the_resource_and_licence_names_are_listed() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let layout = Layout::new(dir.path());
        std::fs::create_dir_all(layout.resources()).expect("can create");
        std::fs::write(layout.resource("icudtl.dat"), "").expect("can write");
        std::fs::write(layout.license(), "").expect("can write");
        assert_eq!(resource_names(&layout), vec!["icudtl.dat".to_string()]);
        assert_eq!(licence_names(&layout), vec!["LICENSE.txt".to_string()]);
    }
}
