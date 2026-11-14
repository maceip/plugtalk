//! The staging of a bundle for the Gradle build, and the audit of the APK
//! that comes out of it.
//!
//! This is the Android counterpart of `cef-rs`'s `bundle`: on a desktop the
//! runtime files are copied next to the executable, on Android they go into
//! the APK — the engine into `jniLibs`, the resources into `assets`, the
//! licences into `assets/licenses`.  The resources have to be *stored* and not
//! compressed, because the engine maps them out of the APK.
//!
//! The audit of the built APK is the last gate before a release.  It answers
//! the questions that the crash of this project could not be answered from:
//! is the engine really in the APK, are the resources really uncompressed,
//! and does the application really depend on the engine?

use std::{
    fs::File,
    path::{Path, PathBuf},
};

use anyhow::{bail, Context, Result};

use crate::{audit::AuditReport, layout::Layout};

/// The staging tree of a bundle.
#[derive(Debug, Clone)]
pub struct Staging {
    root: PathBuf,
}

impl Staging {
    /// The staging of a bundle in a directory.
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Staging { root: root.into() }
    }

    /// The root of the staging tree.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// The directory with the native libraries.
    pub fn jni_libs(&self) -> PathBuf {
        self.root.join("jniLibs")
    }

    /// The directory with the assets.
    pub fn assets(&self) -> PathBuf {
        self.root.join("assets")
    }

    /// The directory with the licences.
    pub fn licences(&self) -> PathBuf {
        self.assets().join("licenses")
    }
}

/// Stage a bundle for the Gradle build.
///
/// The result is a directory that the Gradle build copies into the APK.
pub fn pack(bundle: &Path, staging: &Path, abi: &str) -> Result<Staging> {
    let layout = Layout::new(bundle);
    layout.check()?;

    let staging = Staging::new(staging);
    let lib_dir = staging.jni_libs().join(abi);
    let asset_dir = staging.assets().join("cef");

    std::fs::create_dir_all(&lib_dir)
        .with_context(|| format!("cannot create {}", lib_dir.display()))?;
    std::fs::create_dir_all(&asset_dir)
        .with_context(|| format!("cannot create {}", asset_dir.display()))?;
    std::fs::create_dir_all(staging.licences())
        .with_context(|| format!("cannot create {}", staging.licences().display()))?;

    // The engine goes into the native libraries of the ABI.
    std::fs::copy(layout.libcef(), lib_dir.join("libcef.so"))?;

    // The resources go into the assets of the engine.
    if layout.resources().is_dir() {
        for entry in std::fs::read_dir(layout.resources())? {
            let entry = entry?;
            let path = entry.path();
            if path.is_file() {
                std::fs::copy(&path, asset_dir.join(entry.file_name()))?;
            } else if path.is_dir() {
                let target = asset_dir.join(entry.file_name());
                std::fs::create_dir_all(&target)?;
                for locale in std::fs::read_dir(&path)? {
                    let locale = locale?;
                    if locale.path().is_file() {
                        std::fs::copy(locale.path(), target.join(locale.file_name()))?;
                    }
                }
            }
        }
    }

    // The licences travel with the binary.
    for path in [layout.license(), layout.notice()] {
        if path.is_file() {
            std::fs::copy(&path, staging.licences().join(path.file_name().unwrap()))?;
        }
    }

    // The C API shim is not shipped: it is linked into the application.
    // It is staged anyway, because the Rust build has to find it.
    let wrapper_dir = staging.root().join("lib");
    std::fs::create_dir_all(&wrapper_dir)?;
    std::fs::copy(layout.wrapper(), wrapper_dir.join("libcef_dll_wrapper.a"))?;

    Ok(staging)
}

/// Audit a built APK.
///
/// The checks are the ones that decide whether the application can start.
pub fn audit_apk(apk: &Path, abi: &str, app_library: &str) -> Result<AuditReport> {
    let mut report = AuditReport {
        errors: Vec::new(),
        notes: Vec::new(),
    };

    let file = File::open(apk).with_context(|| format!("cannot open the APK {}", apk.display()))?;
    let mut archive = zip::ZipArchive::new(file)
        .with_context(|| format!("cannot read the APK {}", apk.display()))?;

    let names: Vec<String> = archive.file_names().map(|n| n.to_string()).collect();

    // The engine has to be in the APK.
    let engine = format!("lib/{abi}/libcef.so");
    if names.contains(&engine) {
        report
            .notes
            .push(format!("the engine is in the APK ({engine})"));
    } else {
        report.errors.push(format!("the APK has no {engine}"));
    }

    // The application library has to be in the APK.
    let app = format!("lib/{abi}/{app_library}");
    if names.contains(&app) {
        report
            .notes
            .push(format!("the application library is in the APK ({app})"));
    } else {
        report.errors.push(format!("the APK has no {app}"));
    }

    // The resources have to be there and they have to be stored.
    let resources: Vec<&String> = names
        .iter()
        .filter(|n| n.starts_with("assets/cef/") && !n.ends_with('/'))
        .collect();
    if resources.is_empty() {
        report
            .errors
            .push("the APK carries no resources of the engine".to_string());
    } else {
        report.notes.push(format!(
            "the APK carries {} resource files",
            resources.len()
        ));
    }
    for name in &resources {
        let entry = archive
            .by_name(name)
            .with_context(|| format!("cannot read {name} from the APK"))?;
        if entry.compression() != zip::CompressionMethod::Stored {
            report.errors.push(format!(
                "the resource {name} is compressed; the engine maps it out of the APK and \
                 needs it stored"
            ));
        }
    }

    // The licences have to travel with the binary.
    let licences: Vec<&String> = names
        .iter()
        .filter(|n| n.starts_with("assets/licenses/") && !n.ends_with('/'))
        .collect();
    if licences.is_empty() {
        report
            .errors
            .push("the APK carries no licence document".to_string());
    } else {
        report.notes.push(format!(
            "the APK carries {} licence documents",
            licences.len()
        ));
    }

    Ok(report)
}

/// Check that an APK is a valid zip file.
pub fn is_zip(path: &Path) -> bool {
    File::open(path)
        .ok()
        .and_then(|file| zip::ZipArchive::new(file).ok())
        .is_some()
}

/// The names of the entries of an archive.
pub fn entries(path: &Path) -> Result<Vec<String>> {
    let file = File::open(path).with_context(|| format!("cannot open {}", path.display()))?;
    let archive =
        zip::ZipArchive::new(file).with_context(|| format!("cannot read {}", path.display()))?;
    Ok(archive.file_names().map(|n| n.to_string()).collect())
}

/// Refuse a path that is not a file.
pub fn require_file(path: &Path) -> Result<()> {
    if !path.is_file() {
        bail!("{} does not exist", path.display());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn make_apk(path: &Path, entries: &[(&str, zip::CompressionMethod)]) {
        let file = File::create(path).expect("can create");
        let mut zip = zip::ZipWriter::new(file);
        for (name, compression) in entries {
            zip.start_file(
                *name,
                zip::write::SimpleFileOptions::default().compression_method(*compression),
            )
            .expect("can add");
            zip.write_all(b"payload").expect("can write");
        }
        zip.finish().expect("can finish");
    }

    #[test]
    fn an_apk_without_the_engine_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let apk = dir.path().join("app.apk");
        make_apk(
            &apk,
            &[(
                "lib/arm64-v8a/libonion_vault.so",
                zip::CompressionMethod::Stored,
            )],
        );
        let report = audit_apk(&apk, "arm64-v8a", "libonion_vault.so").expect("can audit");
        assert!(!report.is_ok());
        assert!(report
            .errors
            .iter()
            .any(|e| e.contains("no lib/arm64-v8a/libcef.so")));
    }

    #[test]
    fn a_compressed_resource_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let apk = dir.path().join("app.apk");
        make_apk(
            &apk,
            &[
                ("lib/arm64-v8a/libcef.so", zip::CompressionMethod::Stored),
                (
                    "lib/arm64-v8a/libonion_vault.so",
                    zip::CompressionMethod::Stored,
                ),
                ("assets/cef/icudtl.dat", zip::CompressionMethod::Deflated),
                (
                    "assets/licenses/LICENSE.txt",
                    zip::CompressionMethod::Stored,
                ),
            ],
        );
        let report = audit_apk(&apk, "arm64-v8a", "libonion_vault.so").expect("can audit");
        assert!(!report.is_ok());
        assert!(report
            .errors
            .iter()
            .any(|e| e.contains("icudtl.dat") && e.contains("compressed")));
    }

    #[test]
    fn a_complete_apk_passes() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let apk = dir.path().join("app.apk");
        make_apk(
            &apk,
            &[
                ("lib/arm64-v8a/libcef.so", zip::CompressionMethod::Stored),
                (
                    "lib/arm64-v8a/libonion_vault.so",
                    zip::CompressionMethod::Stored,
                ),
                ("assets/cef/icudtl.dat", zip::CompressionMethod::Stored),
                (
                    "assets/cef/locales/en-US.pak",
                    zip::CompressionMethod::Stored,
                ),
                (
                    "assets/licenses/LICENSE.txt",
                    zip::CompressionMethod::Stored,
                ),
            ],
        );
        let report = audit_apk(&apk, "arm64-v8a", "libonion_vault.so").expect("can audit");
        assert!(report.is_ok(), "{}", report.render());
    }
}
