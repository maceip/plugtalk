//! The layout of a bundle.
//!
//! The layout is the layout of a CEF binary distribution.  That is the point:
//! the consumers of a CEF distribution — the `cef-rs` build script, the
//! Gradle packaging, the licence task — then need no special case for
//! Android, and a bundle built here can be compared with a desktop one file
//! by file.

use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};

/// The canonical layout of a bundle.
#[derive(Debug, Clone)]
pub struct Layout {
    root: PathBuf,
}

impl Layout {
    /// The layout of a bundle in a directory.
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Layout { root: root.into() }
    }

    /// The root of the bundle.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// The directory with the public headers.
    pub fn include(&self) -> PathBuf {
        self.root.join("include")
    }

    /// The directory with the libraries.
    pub fn lib(&self) -> PathBuf {
        self.root.join("lib")
    }

    /// The engine.
    pub fn libcef(&self) -> PathBuf {
        self.lib().join("libcef.so")
    }

    /// The C API shim that `cef-rs` links against.
    pub fn wrapper(&self) -> PathBuf {
        self.lib().join("libcef_dll_wrapper.a")
    }

    /// The directory with the runtime resources.
    pub fn resources(&self) -> PathBuf {
        self.root.join("Resources")
    }

    /// A resource file.
    pub fn resource(&self, name: &str) -> PathBuf {
        self.resources().join(name)
    }

    /// The directory with the locales.
    pub fn locales(&self) -> PathBuf {
        self.resources().join("locales")
    }

    /// The licence of the engine.
    pub fn license(&self) -> PathBuf {
        self.root.join("LICENSE.txt")
    }

    /// The notice of the engine.
    pub fn notice(&self) -> PathBuf {
        self.root.join("NOTICE")
    }

    /// Check that the bundle has the shape a consumer expects.
    pub fn check(&self) -> Result<()> {
        for (what, path) in [
            ("the headers", self.include()),
            ("the libraries", self.lib()),
            ("the resources", self.resources()),
        ] {
            if !path.is_dir() {
                bail!("the bundle has no {what} directory ({})", path.display());
            }
        }
        for (what, path) in [
            ("the engine", self.libcef()),
            ("the C API shim", self.wrapper()),
            ("the licence of the engine", self.license()),
        ] {
            if !path.is_file() {
                bail!("the bundle has no {what} ({})", path.display());
            }
        }
        Ok(())
    }

    /// The headers of the bundle, sorted.
    pub fn headers(&self) -> Result<Vec<PathBuf>> {
        let mut headers = Vec::new();
        collect(&self.include(), &mut headers)?;
        headers.sort();
        Ok(headers)
    }
}

/// Collect the files below a directory.
fn collect(dir: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    if !dir.is_dir() {
        return Ok(());
    }
    for entry in std::fs::read_dir(dir).with_context(|| format!("cannot read {}", dir.display()))? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            collect(&path, out)?;
        } else {
            out.push(path);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bundle_with(files: &[(&str, &str)]) -> (tempfile::TempDir, Layout) {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let layout = Layout::new(dir.path());
        std::fs::create_dir_all(layout.include()).expect("can create");
        std::fs::create_dir_all(layout.lib()).expect("can create");
        std::fs::create_dir_all(layout.resources()).expect("can create");
        for (name, body) in files {
            let path = layout.root().join(name);
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent).expect("can create");
            }
            std::fs::write(path, body).expect("can write");
        }
        (dir, layout)
    }

    #[test]
    fn a_complete_bundle_passes_the_check() {
        let (_dir, layout) = bundle_with(&[
            ("include/cef_app.h", ""),
            ("lib/libcef.so", ""),
            ("lib/libcef_dll_wrapper.a", ""),
            ("Resources/icudtl.dat", ""),
            ("LICENSE.txt", ""),
        ]);
        layout.check().expect("the bundle is complete");
    }

    #[test]
    fn a_bundle_without_the_engine_is_refused() {
        let (_dir, layout) = bundle_with(&[("include/cef_app.h", "")]);
        let error = layout.check().expect_err("the engine is missing");
        assert!(
            error.to_string().contains("no the engine") || error.to_string().contains("no engine")
        );
    }

    #[test]
    fn a_bundle_without_the_licence_is_refused() {
        let (_dir, layout) = bundle_with(&[
            ("include/cef_app.h", ""),
            ("lib/libcef.so", ""),
            ("lib/libcef_dll_wrapper.a", ""),
        ]);
        let error = layout.check().expect_err("the licence is missing");
        assert!(error.to_string().contains("licence"));
    }

    #[test]
    fn the_headers_are_listed_in_a_stable_order() {
        let (_dir, layout) = bundle_with(&[
            ("include/cef_app.h", ""),
            ("include/internal/capi.h", ""),
            ("include/cef_browser.h", ""),
        ]);
        let headers = layout.headers().expect("can list");
        let names: Vec<_> = headers
            .iter()
            .map(|p| {
                p.strip_prefix(layout.include())
                    .unwrap()
                    .to_string_lossy()
                    .to_string()
            })
            .collect();
        assert_eq!(
            names,
            vec!["cef_app.h", "cef_browser.h", "internal/capi.h"],
            "the headers are listed in the order of their paths"
        );
    }
}
