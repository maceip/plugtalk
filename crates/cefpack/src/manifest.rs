//! The pinned description of *what* is bundled and *with which* toolchain.
//!
//! Everything that decides the content of a bundle lives in one TOML file
//! (`manifest/cef-android.toml`).  The file is the single source of truth for
//! the service, it is reviewed like code, and a bundle that was installed from
//! it can be reproduced from it alone.
//!
//! The manifest never contains a path: paths belong to the machine that runs
//! the service and are discovered from the environment.  What the manifest
//! contains is *versions*, *revisions* and *checksums*, which are properties
//! of the artefact and not of the host.

use std::{collections::BTreeMap, path::Path};

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};

/// The manifest of the pinned CEF Android bundle.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Manifest {
    /// Version of the manifest format itself.
    pub schema: u32,
    /// The CEF release that is bundled.
    pub engine: Engine,
    /// Where the CEF sources come from.
    pub source: Source,
    /// The Android target of the bundle.
    pub target: Target,
    /// The toolchain that has to be used for the build.
    pub toolchain: Toolchain,
    /// The GN arguments of the Chromium build.
    pub gn: Gn,
    /// The headers of Chromium that the C API needs.
    #[serde(default)]
    pub chromium: Chromium,
    /// The resources that the engine needs at runtime.
    #[serde(default)]
    pub assets: Assets,
    /// The files that the bundle has to contain.
    #[serde(default)]
    pub contents: Contents,
}

/// The CEF release.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Engine {
    /// The full CEF version string, e.g. `156.0.30+g6028375+chromium-156.0.8078.96`.
    pub cef_version: String,
    /// The Chromium version that CEF tracks.
    pub chromium_version: String,
    /// The CEF branch that is checked out, e.g. `156`.
    pub branch: String,
}

/// Where the CEF sources come from.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Source {
    /// The git repository of CEF.
    pub repo: String,
    /// The branch that is checked out.
    pub branch: String,
    /// The commit of that branch that is built.
    ///
    /// This is the pin.  The Chromium revision that belongs to it is not
    /// written here: it lives in CEF's own `DEPS` file, which `gclient`
    /// reads and verifies.  A bundle is therefore reproducible from the CEF
    /// commit alone, and no third party archive is trusted at any point.
    pub commit: String,
}

/// The Android target.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Target {
    /// `android`.
    pub os: String,
    /// `arm64`.
    pub cpu: String,
    /// The Android ABI, `arm64-v8a`.
    pub abi: String,
    /// The minimum Android API level.
    pub min_sdk: u32,
}

/// The toolchain of the build.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Toolchain {
    /// The NDK version that the build has to use.
    pub ndk_version: String,
    /// The clang version that the NDK ships.
    pub clang_version: String,
}

/// The GN arguments of the Chromium build.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Gn {
    /// The arguments that are always passed.
    #[serde(default)]
    pub args: Vec<String>,
    /// The arguments that are only passed for a debug build.
    #[serde(default)]
    pub debug_args: Vec<String>,
}

/// The headers of Chromium that the C API of CEF needs.
///
/// The public headers of CEF include a few headers of Chromium: the list of
/// the network error codes and the list of the colour identifiers.  They are
/// fetched from the Chromium revision that CEF tracks, and their checksums
/// are pinned, so that the bindings are generated against the very headers
/// of the very engine that is shipped.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Chromium {
    /// The Chromium revision.  CEF is built on top of a Chromium release
    /// branch, so the branch head of that version is the revision.
    #[serde(rename = "ref")]
    pub revision: String,
    /// The base URL of the source browser.
    pub base_url: String,
    /// The headers that are needed.
    #[serde(default)]
    pub headers: Vec<ChromiumHeader>,
}

/// One header of Chromium.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChromiumHeader {
    /// The path of the header, relative to the root of the Chromium tree.
    pub path: String,
    /// The SHA-256 of the header.
    pub sha256: String,
}

/// The resource files that the engine reads at runtime.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Assets {
    /// The files that must be present under `Resources/`.
    #[serde(default)]
    pub required: Vec<String>,
    /// The locales that must be present under `Resources/locales/`.
    #[serde(default)]
    pub locales: Vec<String>,
}

/// The files that the bundle has to contain.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Contents {
    /// The public headers that have to be present under `include/`.
    #[serde(default)]
    pub required_headers: Vec<String>,
    /// The shared objects that have to be present under `lib/`.
    #[serde(default)]
    pub required_libraries: Vec<String>,
    /// The licence documents that have to travel with the binary.
    #[serde(default)]
    pub required_licences: Vec<String>,
}

impl Manifest {
    /// Read the manifest from a TOML file.
    pub fn read(path: &Path) -> Result<Self> {
        let text = std::fs::read_to_string(path)
            .with_context(|| format!("cannot read the manifest {}", path.display()))?;
        Self::from_toml(&text)
    }

    /// Parse the manifest from a TOML document.
    pub fn from_toml(text: &str) -> Result<Self> {
        let manifest: Manifest =
            toml::from_str(text).context("the manifest is not a valid manifest document")?;
        manifest.validate()?;
        Ok(manifest)
    }

    /// Render the manifest as TOML.
    pub fn to_toml(&self) -> Result<String> {
        toml::to_string_pretty(self).context("cannot render the manifest")
    }

    /// Check that the manifest describes a bundle that can be built.
    pub fn validate(&self) -> Result<()> {
        if self.schema != 1 {
            bail!(
                "unsupported manifest schema {} (this service speaks schema 1)",
                self.schema
            );
        }
        if self.engine.cef_version.is_empty() {
            bail!("the manifest names no CEF version");
        }
        if self.engine.chromium_version.is_empty() {
            bail!("the manifest names no Chromium version");
        }
        if self.target.os != "android" {
            bail!(
                "this service builds CEF for Android, the manifest asks for {}",
                self.target.os
            );
        }
        if self.target.abi.is_empty() {
            bail!("the manifest names no Android ABI");
        }
        if self.toolchain.ndk_version.is_empty() {
            bail!("the manifest names no NDK version");
        }
        if !self.chromium.revision.is_empty() {
            for header in &self.chromium.headers {
                if header.sha256.len() != 64 {
                    bail!("the Chromium header {} has no usable SHA-256", header.path);
                }
                if self.chromium.base_url.is_empty() {
                    bail!("the manifest names Chromium headers but no source for them");
                }
            }
        }
        if self.source.commit.len() != 40
            || !self.source.commit.chars().all(|c| c.is_ascii_hexdigit())
        {
            bail!(
                "the CEF commit must be a full 40 character git SHA, got {:?}",
                self.source.commit
            );
        }
        Ok(())
    }

    /// The GN arguments of a build of the given kind.
    pub fn gn_args(&self, debug: bool) -> Vec<String> {
        let mut args = self.gn.args.clone();
        if debug {
            args.extend(self.gn.debug_args.iter().cloned());
        }
        args
    }

    /// The map form of the GN arguments, for `gn gen`.
    pub fn gn_args_map(&self, debug: bool) -> BTreeMap<String, String> {
        self.gn_args(debug)
            .iter()
            .filter_map(|arg| {
                arg.split_once('=')
                    .map(|(k, v)| (k.to_string(), v.to_string()))
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minimal() -> &'static str {
        r#"
schema = 1

[engine]
cef_version = "156.0.30+g6028375+chromium-156.0.8078.96"
chromium_version = "156.0.8078.96"
branch = "156"

[source]
repo = "https://github.com/chromiumembedded/cef.git"
branch = "156"
commit = "6028375f8b5c1a4a2b8a5c9e7d3f1a2b4c6d8e0f"

[target]
os = "android"
cpu = "arm64"
abi = "arm64-v8a"
min_sdk = 29

[toolchain]
ndk_version = "27.2.12479018"
clang_version = "18"

[gn]
args = ["is_debug=false"]
debug_args = ["is_debug=true"]
"#
    }

    #[test]
    fn parses_a_complete_manifest() {
        let manifest = Manifest::from_toml(minimal()).expect("valid manifest");
        assert_eq!(manifest.schema, 1);
        assert_eq!(manifest.target.abi, "arm64-v8a");
        assert_eq!(manifest.toolchain.ndk_version, "27.2.12479018");
    }

    #[test]
    fn rejects_a_manifest_for_another_platform() {
        let text = minimal().replace(r#"os = "android""#, r#"os = "linux""#);
        let error = Manifest::from_toml(&text).expect_err("must refuse a desktop target");
        assert!(error
            .to_string()
            .contains("this service builds CEF for Android"));
    }

    #[test]
    fn rejects_a_manifest_without_a_pinned_commit() {
        let text = minimal().replace("6028375f8b5c1a4a2b8a5c9e7d3f1a2b4c6d8e0f", "6028375");
        let error = Manifest::from_toml(&text).expect_err("must refuse an unpinned revision");
        assert!(error.to_string().contains("full 40 character git SHA"));
    }

    #[test]
    fn rejects_an_unknown_schema() {
        let text = minimal().replace("schema = 1", "schema = 99");
        let error = Manifest::from_toml(&text).expect_err("must refuse a future schema");
        assert!(error.to_string().contains("unsupported manifest schema"));
    }

    #[test]
    fn the_debug_arguments_are_only_used_for_a_debug_build() {
        let manifest = Manifest::from_toml(minimal()).expect("valid manifest");
        assert!(!manifest
            .gn_args(false)
            .contains(&"is_debug=true".to_string()));
        assert!(manifest
            .gn_args(true)
            .contains(&"is_debug=true".to_string()));
    }

    #[test]
    fn the_gn_argument_map_keeps_the_last_value_of_a_key() {
        let manifest = Manifest::from_toml(minimal()).expect("valid manifest");
        let map = manifest.gn_args_map(false);
        assert_eq!(map.get("is_debug").map(String::as_str), Some("false"));
    }
}
