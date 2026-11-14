//! `cefpack` — the CEF bundling service for Android.
//!
//! CEF publishes binary distributions for Windows, macOS and Linux.  There is
//! **no Android distribution**, because building CEF for Android needs the
//! Android NDK and the Chromium build system rather than a tarball.  This
//! service produces the missing package: it takes the CEF sources, drives
//! Chromium's GN/Ninja build with the NDK, and assembles a distribution that
//! has the same shape as the desktop ones, so that the rest of the toolchain
//! (the Rust bindings, the Gradle packaging, the licence delivery) does not
//! have to know that Android is special.
//!
//! # What the service does
//!
//! | step | command | what it produces |
//! |---|---|---|
//! | resolve | `cefpack plan` | the pinned build plan and a check of the host |
//! | fetch | `cefpack fetch` | the CEF sources and the reference distribution |
//! | build | `cefpack build` | `libcef.so`, `libcef_dll_wrapper.a`, headers, resources |
//! | install | `cefpack install` | the canonical bundle with a provenance file |
//! | audit | `cefpack audit` | a verdict on the bundle, or the reason it is broken |
//! | bindgen | `cefpack bindgen` | the Rust FFI for the bundled CEF version |
//! | pack | `cefpack pack` | the staging tree for the Gradle build |
//! | apk | `cefpack apk` | a verdict on a built APK |
//!
//! # The shape of a bundle
//!
//! ```text
//! <bundle>/
//!   include/                  the public CEF headers
//!   lib/libcef.so             the engine
//!   lib/libcef_dll_wrapper.a  the C API shim
//!   Resources/                icudtl.dat, the .pak files, the locales
//!   LICENSE.txt  NOTICE        the licence documents of the engine
//!   bundle.json               provenance: versions, checksums, build arguments
//! ```
//!
//! The layout is deliberately the layout of a CEF binary distribution, so that
//! the consumers — `cef-rs`' build script, the Gradle packaging, the licence
//! task — need no special case for Android.

pub mod apk;
pub mod audit;
pub mod bindgen;
pub mod build;
pub mod fetch;
pub mod gn;
pub mod layout;
pub mod manifest;
pub mod plan;

use std::path::{Path, PathBuf};

use anyhow::Result;

pub use manifest::Manifest;

/// The schema version of `bundle.json`.
pub const BUNDLE_SCHEMA: u32 = 1;

/// The name of the provenance file inside a bundle.
pub const BUNDLE_MANIFEST: &str = "bundle.json";

/// The provenance of an installed bundle.
///
/// It is written next to the bundle and answers the question "what exactly is
/// in this directory, and how was it built?" without a network connection.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct BundleManifest {
    /// The version of this format.
    pub schema: u32,
    /// The CEF version.
    pub cef_version: String,
    /// The Chromium version.
    pub chromium_version: String,
    /// The CEF revision that was built.
    pub cef_revision: String,
    /// The Android target.
    pub target_os: String,
    /// The Android ABI.
    pub abi: String,
    /// The minimum Android API level.
    pub min_sdk: u32,
    /// The NDK that built the bundle.
    pub ndk_version: String,
    /// The GN arguments of the build.
    pub gn_args: Vec<String>,
    /// The SHA-256 of `lib/libcef.so`.
    pub libcef_sha256: String,
    /// The SHA-256 of `lib/libcef_dll_wrapper.a`.
    pub wrapper_sha256: String,
    /// The number of headers in `include/`.
    pub header_count: usize,
    /// The resources that are present.
    pub resources: Vec<String>,
    /// The licences that travel with the bundle.
    pub licences: Vec<String>,
}

impl BundleManifest {
    /// Read the provenance of a bundle.
    pub fn read(bundle: &Path) -> Result<Self> {
        let path = bundle.join(BUNDLE_MANIFEST);
        let text = std::fs::read_to_string(&path)
            .map_err(|e| anyhow::anyhow!("cannot read {}: {e}", path.display()))?;
        let manifest: BundleManifest = serde_json::from_str(&text)
            .map_err(|e| anyhow::anyhow!("cannot parse {}: {e}", path.display()))?;
        Ok(manifest)
    }

    /// Write the provenance of a bundle.
    pub fn write(&self, bundle: &Path) -> Result<()> {
        let path = bundle.join(BUNDLE_MANIFEST);
        let text = serde_json::to_string_pretty(self)?;
        std::fs::write(&path, text)
            .map_err(|e| anyhow::anyhow!("cannot write {}: {e}", path.display()))?;
        Ok(())
    }
}

/// The SHA-256 of a file, as lower case hexadecimal.
pub fn sha256_file(path: &Path) -> Result<String> {
    use sha2::{Digest, Sha256};

    let mut file = std::fs::File::open(path)
        .map_err(|e| anyhow::anyhow!("cannot open {}: {e}", path.display()))?;
    let mut hasher = Sha256::new();
    std::io::copy(&mut file, &mut hasher)
        .map_err(|e| anyhow::anyhow!("cannot read {}: {e}", path.display()))?;
    Ok(hasher
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect())
}

/// The SHA-256 of a byte slice, as lower case hexadecimal.
pub fn sha256_bytes(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};

    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// The directory of this crate, which holds the pinned manifest.
pub fn manifest_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("manifest/cef-android.toml")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_checksum_of_the_empty_input_is_the_known_one() {
        assert_eq!(
            sha256_bytes(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn the_checksum_of_a_file_matches_the_checksum_of_its_bytes() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let path = dir.path().join("payload");
        std::fs::write(&path, b"trap").expect("can write");
        assert_eq!(sha256_file(&path).expect("can hash"), sha256_bytes(b"trap"));
    }
}
