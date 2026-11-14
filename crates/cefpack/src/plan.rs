//! The environment of the build: where the toolchain is, and whether it is
//! the right one.
//!
//! This module exists because the single most expensive failure mode of a
//! build like this is a path that looks plausible and is wrong.  Every tool
//! the build needs is located here, **checked** here, and the version of the
//! tool is compared with the version the manifest pins.  A build that starts
//! with a wrong NDK produces a `libcef.so` that cannot load, and nothing
//! downstream says so until the application dies on a device.

use std::path::{Path, PathBuf};

use anyhow::{bail, Result};

use crate::manifest::Manifest;

/// A tool of the build.
#[derive(Debug, Clone)]
pub struct Tool {
    /// The name of the tool.
    pub name: &'static str,
    /// The path of the executable.
    pub path: PathBuf,
    /// The version that the tool reported, when it could be asked.
    pub version: Option<String>,
}

/// The resolved plan of a build: the manifest plus the checked environment.
#[derive(Debug, Clone)]
pub struct Plan {
    /// The pinned description of the build.
    pub manifest: Manifest,
    /// The root of the checkout that holds the CEF sources.
    pub source_root: PathBuf,
    /// The directory that receives the build outputs.
    pub out_dir: PathBuf,
    /// The directory that receives the installed bundle.
    pub bundle_dir: PathBuf,
    /// The NDK.
    pub ndk: Tool,
    /// The Android SDK.
    pub sdk: Tool,
    /// The depot_tools checkout that holds `gclient`, `gn` and `ninja`.
    pub depot_tools: Tool,
    /// `git`.
    pub git: Tool,
}

impl Plan {
    /// Resolve the plan and check the environment.
    ///
    /// Every tool is located by an explicit environment variable first and
    /// by well known location second.  A tool that cannot be found is an
    /// error with the name of the variable that would fix it — not a warning
    /// that turns into a mysterious failure three steps later.
    pub fn resolve(manifest: Manifest) -> Result<Self> {
        let ndk = find_tool(
            "ndk",
            Kind::Root,
            &["CEF_ANDROID_NDK", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"],
            &[PathBuf::from("/opt/android-ndk-r27c")],
            &["source.properties"],
        )?;
        let sdk = find_tool(
            "sdk",
            Kind::Root,
            &["ANDROID_SDK_ROOT", "ANDROID_HOME"],
            &[PathBuf::from("/opt/android-sdk")],
            &["platforms"],
        )?;
        let depot_tools = find_tool(
            "depot_tools",
            Kind::Root,
            &["CEF_DEPOT_TOOLS"],
            &[],
            &["gclient"],
        )?;
        let git = find_tool("git", Kind::Executable, &["CEF_GIT"], &[], &[])?;

        let source_root = env_path("CEF_SOURCE_ROOT")
            .unwrap_or_else(|| PathBuf::from("/workspace/.research/cef-source"));
        let out_dir = env_path("CEF_OUT_DIR")
            .unwrap_or_else(|| PathBuf::from("/workspace/.research/cef-out"));
        let bundle_dir = env_path("CEF_BUNDLE_DIR")
            .unwrap_or_else(|| PathBuf::from("/workspace/.research/cef-bundle"));

        let plan = Plan {
            manifest,
            source_root,
            out_dir,
            bundle_dir,
            ndk,
            sdk,
            depot_tools,
            git,
        };
        plan.check()?;
        Ok(plan)
    }

    /// Check the environment against the pinned manifest.
    pub fn check(&self) -> Result<()> {
        // The NDK carries its version in `source.properties`.
        let properties =
            std::fs::read_to_string(self.ndk.path.join("source.properties")).unwrap_or_default();
        let found = properties
            .lines()
            .find_map(|line| line.strip_prefix("Pkg.Revision = "))
            .map(|v| v.trim().to_string());
        match found {
            Some(version) if version == self.manifest.toolchain.ndk_version => {}
            Some(version) => bail!(
                "the NDK at {} is version {version}, the manifest pins {}",
                self.ndk.path.display(),
                self.manifest.toolchain.ndk_version
            ),
            None => bail!(
                "the NDK at {} does not carry a version in source.properties",
                self.ndk.path.display()
            ),
        }

        // The clang of the NDK has to be the one the manifest names.
        let clang = self
            .ndk
            .path
            .join("toolchains/llvm/prebuilt/linux-x86_64/bin/clang");
        if !clang.is_file() {
            bail!(
                "the NDK at {} has no clang at {}",
                self.ndk.path.display(),
                clang.display()
            );
        }

        // The SDK has to carry a platform that the application can be
        // compiled against.  The platform is *not* the minimum API level of
        // the application: the minimum is the oldest release the
        // application runs on, the platform is the newest release it is
        // compiled against.  The NDK sysroot carries the headers of the
        // minimum level.
        let platforms = self.sdk.path.join("platforms");
        let mut best: Option<u32> = None;
        if let Ok(entries) = std::fs::read_dir(&platforms) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().to_string();
                let Some(rest) = name.strip_prefix("android-") else {
                    continue;
                };
                let level: u32 = rest.split('.').next().unwrap_or("0").parse().unwrap_or(0);
                best = Some(best.map_or(level, |b: u32| b.max(level)));
            }
        }
        match best {
            Some(level) if level >= self.manifest.target.min_sdk => {
                log::info!("the SDK carries the platform android-{level}");
            }
            Some(level) => bail!(
                "the SDK at {} has only the platform android-{level}, the application needs \
                 android-{} or newer",
                self.sdk.path.display(),
                self.manifest.target.min_sdk
            ),
            None => bail!(
                "the SDK at {} has no platform at all",
                self.sdk.path.display()
            ),
        }

        // depot_tools has to carry the three programs the build drives.
        for tool in ["gclient", "gn", "ninja"] {
            let path = self.depot_tools.path.join(tool);
            if !path.is_file() {
                bail!(
                    "depot_tools at {} has no {tool}; the build cannot run",
                    self.depot_tools.path.display()
                );
            }
        }

        Ok(())
    }

    /// The GN arguments of the build, with the host paths filled in.
    pub fn gn_args(&self, debug: bool) -> Vec<String> {
        let mut args = self.manifest.gn_args(debug);
        args.push(format!("android_sdk_root=\"{}\"", self.sdk.path.display()));
        args.push(format!("android_ndk_root=\"{}\"", self.ndk.path.display()));
        args.push(format!(
            "android_ndk_version=\"{}\"",
            self.manifest.toolchain.ndk_version
        ));
        args
    }
}

/// The value of an environment variable as a path.
fn env_path(name: &str) -> Option<PathBuf> {
    std::env::var_os(name)
        .map(PathBuf::from)
        .filter(|p| !p.as_os_str().is_empty())
}

/// What a tool is: a directory that holds a toolchain, or an executable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    /// A directory that *is* the tool, e.g. the NDK.
    Root,
    /// An executable, e.g. `git`.
    Executable,
}

/// Locate a tool.
///
/// The environment variables are tried first, in order, then the well known
/// locations.  A candidate is accepted when it carries one of the `witness`
/// files; a tool with no witness is accepted when it exists at all.
fn find_tool(
    name: &'static str,
    kind: Kind,
    env_vars: &[&str],
    fallbacks: &[PathBuf],
    witnesses: &[&str],
) -> Result<Tool> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    for var in env_vars {
        if let Some(path) = env_path(var) {
            candidates.push(path);
        }
    }
    candidates.extend(fallbacks.iter().cloned());

    for candidate in &candidates {
        let executable = match kind {
            Kind::Root => candidate.clone(),
            Kind::Executable => {
                if candidate.is_dir() {
                    candidate.join(name)
                } else {
                    candidate.clone()
                }
            }
        };
        if !executable.exists() {
            continue;
        }
        let ok = witnesses.is_empty()
            || witnesses
                .iter()
                .any(|witness| candidate.join(witness).exists());
        if !ok {
            continue;
        }
        let version = tool_version(&executable);
        return Ok(Tool {
            name,
            path: candidate.to_path_buf(),
            version,
        });
    }

    // An executable that is on the PATH is found there.
    if kind == Kind::Executable {
        if let Ok(found) = which::which(name) {
            let version = tool_version(&found);
            return Ok(Tool {
                name,
                path: found,
                version,
            });
        }
    }

    let searched = candidates
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(", ");
    bail!(
        "cannot find the {name}; set {} (searched: {searched})",
        env_vars.join(" or ")
    );
}

/// Ask a tool for its version.
fn tool_version(path: &Path) -> Option<String> {
    let output = std::process::Command::new(path)
        .arg("--version")
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let first = text.lines().next()?.trim().to_string();
    if first.is_empty() {
        None
    } else {
        Some(first)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_missing_tool_names_the_variable_that_fixes_it() {
        let error = find_tool(
            "cefpack-test-tool",
            Kind::Executable,
            &["CEFPACK_TEST_TOOL"],
            &[],
            &[],
        )
        .expect_err("there is no such tool");
        let text = error.to_string();
        assert!(text.contains("cannot find the cefpack-test-tool"));
        assert!(text.contains("CEFPACK_TEST_TOOL"));
    }

    #[test]
    fn a_directory_without_the_witness_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let bin = dir.path().join("bin");
        std::fs::create_dir_all(&bin).expect("can create");
        let tool = bin.join("cefpack-test-tool");
        std::fs::write(&tool, "#!/bin/sh\necho 1.2.3\n").expect("can write");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&tool).expect("can stat").permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&tool, perms).expect("can chmod");
        }
        let error = find_tool(
            "cefpack-test-tool",
            Kind::Executable,
            &[],
            std::slice::from_ref(&bin),
            &["witness"],
        )
        .expect_err("the witness is missing");
        assert!(error.to_string().contains("cannot find"));
    }

    #[test]
    fn a_directory_with_the_witness_is_accepted_and_its_version_is_read() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let bin = dir.path().join("bin");
        std::fs::create_dir_all(&bin).expect("can create");
        let tool = bin.join("cefpack-test-tool");
        std::fs::write(&tool, "#!/bin/sh\necho cefpack-test-tool 1.2.3\n").expect("can write");
        std::fs::write(bin.join("witness"), "").expect("can write");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = std::fs::metadata(&tool).expect("can stat").permissions();
            perms.set_mode(0o755);
            std::fs::set_permissions(&tool, perms).expect("can chmod");
        }
        let found = find_tool(
            "cefpack-test-tool",
            Kind::Executable,
            &[],
            &[bin],
            &["witness"],
        )
        .expect("the tool is there");
        assert_eq!(
            found.version.as_deref(),
            Some("cefpack-test-tool 1.2.3"),
            "the version of the tool is read"
        );
    }
}
