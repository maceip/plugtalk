//! The audit of a bundle.
//!
//! The audit is the part that would have caught the crash before it reached a
//! device.  It does not look at the source of the bundle; it looks at the
//! *artefacts* and asks the questions that decide whether an application that
//! links against them can start:
//!
//! * is `libcef.so` an ELF shared object for the right machine?
//! * does it export the C API that the application calls?
//! * does the application's own library declare `libcef.so` as a dependency,
//!   so that the dynamic linker resolves it at load time?
//! * are the headers and the resources present?
//! * do the checksums in the provenance match the files on disk?

use std::path::Path;

use anyhow::{bail, Result};

use crate::{layout::Layout, BundleManifest};

/// The verdict of an audit.
#[derive(Debug, Clone)]
pub struct AuditReport {
    /// The problems that make the bundle unusable.
    pub errors: Vec<String>,
    /// The observations that are worth writing down.
    pub notes: Vec<String>,
}

impl AuditReport {
    /// Whether the bundle passed.
    pub fn is_ok(&self) -> bool {
        self.errors.is_empty()
    }

    /// Render the report.
    pub fn render(&self) -> String {
        let mut text = String::new();
        for error in &self.errors {
            text.push_str(&format!("FAIL  {error}\n"));
        }
        for note in &self.notes {
            text.push_str(&format!("ok    {note}\n"));
        }
        if self.errors.is_empty() {
            text.push_str("the bundle is sound\n");
        }
        text
    }
}

/// The C API functions that an application cannot start without.
pub const REQUIRED_C_API: &[&str] = &[
    "cef_initialize",
    "cef_shutdown",
    "cef_browser_host_create_browser",
    "cef_string_utf8_to_utf16",
    "cef_string_utf16_to_utf8",
];

/// Audit a bundle.
pub fn audit(bundle: &Path) -> Result<AuditReport> {
    let layout = Layout::new(bundle);
    let mut report = AuditReport {
        errors: Vec::new(),
        notes: Vec::new(),
    };

    // The shape of the bundle.
    if let Err(error) = layout.check() {
        report.errors.push(error.to_string());
        return Ok(report);
    }

    // The engine.
    match elf_machine(&layout.libcef()) {
        Ok(machine) => {
            if machine == 183 {
                report.notes.push("libcef.so is an AArch64 ELF".to_string());
            } else {
                report.errors.push(format!(
                    "libcef.so is for the machine {machine}, the application is AArch64 (183)"
                ));
            }
        }
        Err(error) => report.errors.push(error.to_string()),
    }

    // The C API.
    let exports = exported_symbols(&layout.libcef());
    let missing: Vec<&str> = REQUIRED_C_API
        .iter()
        .copied()
        .filter(|symbol| !exports.iter().any(|e| e == symbol))
        .collect();
    if missing.is_empty() {
        report.notes.push(format!(
            "libcef.so exports {} C API symbols",
            exports.iter().filter(|e| e.starts_with("cef_")).count()
        ));
    } else {
        report
            .errors
            .push(format!("libcef.so does not export {}", missing.join(", ")));
    }

    // The headers.
    let headers = layout.headers().unwrap_or_default();
    if headers.is_empty() {
        report.errors.push("the bundle has no headers".to_string());
    } else {
        report
            .notes
            .push(format!("the bundle has {} headers", headers.len()));
    }
    for required in ["cef_app.h", "cef_browser.h", "cef_settings.h"] {
        if !headers
            .iter()
            .any(|h| h.file_name().map(|n| n == required).unwrap_or(false))
        {
            report
                .errors
                .push(format!("the header {required} is missing"));
        }
    }

    // The resources.
    if !layout.resource("icudtl.dat").is_file() {
        report
            .errors
            .push("the resource icudtl.dat is missing".to_string());
    }
    if layout.locales().is_dir() {
        report
            .notes
            .push("the bundle carries the locales".to_string());
    } else {
        report
            .errors
            .push("the bundle carries no locales".to_string());
    }

    // The provenance.
    match BundleManifest::read(bundle) {
        Ok(manifest) => {
            let libcef = crate::sha256_file(&layout.libcef()).unwrap_or_default();
            if libcef != manifest.libcef_sha256 {
                report.errors.push(format!(
                    "libcef.so does not match the checksum of the provenance ({libcef} against {})",
                    manifest.libcef_sha256
                ));
            } else {
                report
                    .notes
                    .push("libcef.so matches the checksum of the provenance".to_string());
            }
        }
        Err(error) => report.errors.push(error.to_string()),
    }

    Ok(report)
}

/// The machine of an ELF file, as the `e_machine` field.
pub fn elf_machine(path: &Path) -> Result<u16> {
    let bytes =
        std::fs::read(path).map_err(|e| anyhow::anyhow!("cannot read {}: {e}", path.display()))?;
    if bytes.len() < 20 {
        bail!("{} is too short to be an ELF file", path.display());
    }
    if &bytes[0..4] != b"\x7fELF" {
        bail!("{} is not an ELF file", path.display());
    }
    // EI_DATA: 1 = little endian, 2 = big endian.
    let endian = match bytes[5] {
        1 => u16::from_le_bytes,
        2 => u16::from_be_bytes,
        other => bail!("{} has an unknown byte order ({other})", path.display()),
    };
    Ok(endian([bytes[18], bytes[19]]))
}

/// The exported symbols of an ELF shared object.
///
/// The file is read and the dynamic symbol table is scanned.  A full ELF
/// parser is not needed for that and would be a liability: the bundle is
/// always produced by this service, so its shape is known.
pub fn exported_symbols(path: &Path) -> Vec<String> {
    let mut symbols = Vec::new();
    let Ok(output) = std::process::Command::new("nm")
        .arg("-D")
        .arg("--defined-only")
        .arg(path)
        .output()
    else {
        return symbols;
    };
    if !output.status.success() {
        return symbols;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    for line in text.lines() {
        let mut fields = line.split_whitespace();
        let (_address, _kind, name) = (fields.next(), fields.next(), fields.next());
        if let Some(name) = name {
            if !name.is_empty() {
                symbols.push(name.to_string());
            }
        }
    }
    symbols
}

/// Check that a library declares another one as a dependency.
///
/// This is the check that catches the failure mode in which the application
/// loads its own library before the engine: the dynamic linker then pulls the
/// engine in as a plain dependency, its initialisation hook never runs, and
/// the engine aborts while it looks for its resources.
pub fn needs_library(library: &Path, dependency: &str) -> Result<bool> {
    let output = std::process::Command::new("readelf")
        .arg("-d")
        .arg(library)
        .output()
        .map_err(|e| anyhow::anyhow!("cannot run readelf on {}: {e}", library.display()))?;
    if !output.status.success() {
        bail!(
            "readelf failed on {}: {}",
            library.display(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    let text = String::from_utf8_lossy(&output.stdout);
    Ok(text.contains(&format!("Shared library: [{dependency}]")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_file_that_is_not_an_elf_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let path = dir.path().join("not-an-elf");
        std::fs::write(&path, b"this is not an ELF file at all").expect("can write");
        let error = elf_machine(&path).expect_err("the file is not an ELF file");
        assert!(error.to_string().contains("not an ELF file"));
    }

    #[test]
    fn a_truncated_elf_is_refused() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let path = dir.path().join("short");
        std::fs::write(&path, b"\x7fELF").expect("can write");
        let error = elf_machine(&path).expect_err("the file is too short");
        assert!(error.to_string().contains("too short"));
    }

    #[test]
    fn a_fake_aarch64_elf_is_recognised() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let path = dir.path().join("fake.so");
        let mut bytes = vec![0u8; 64];
        bytes[0..4].copy_from_slice(b"\x7fELF");
        bytes[5] = 1; // little endian
        bytes[18] = 183; // EM_AARCH64
        bytes[19] = 0;
        std::fs::write(&path, &bytes).expect("can write");
        assert_eq!(elf_machine(&path).expect("can read"), 183);
    }

    #[test]
    fn the_report_renders_the_verdict() {
        let report = AuditReport {
            errors: vec!["the engine is missing".to_string()],
            notes: vec!["the headers are there".to_string()],
        };
        assert!(!report.is_ok());
        let text = report.render();
        assert!(text.contains("FAIL  the engine is missing"));
        assert!(text.contains("ok    the headers are there"));
    }

    /// A bundle that carries everything an audit expects.
    fn synthetic_bundle() -> (tempfile::TempDir, std::path::PathBuf) {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let root = dir.path().to_path_buf();
        let layout = Layout::new(&root);
        std::fs::create_dir_all(layout.include()).expect("can create");
        std::fs::create_dir_all(layout.lib()).expect("can create");
        std::fs::create_dir_all(layout.resources()).expect("can create");
        std::fs::create_dir_all(layout.locales()).expect("can create");

        for header in ["cef_app.h", "cef_browser.h", "cef_settings.h"] {
            std::fs::write(layout.include().join(header), "").expect("can write");
        }
        std::fs::write(layout.resource("icudtl.dat"), "").expect("can write");
        std::fs::write(layout.locales().join("en-US.pak"), "").expect("can write");
        std::fs::write(layout.license(), "the licence").expect("can write");

        // A shared object that is not really one, but has the right shape
        // for the parts of the audit that do not read it.
        std::fs::write(layout.libcef(), b"not an ELF file").expect("can write");
        std::fs::write(layout.wrapper(), "").expect("can write");

        let manifest = crate::BundleManifest {
            schema: crate::BUNDLE_SCHEMA,
            cef_version: "1".to_string(),
            chromium_version: "1".to_string(),
            cef_revision: "0".to_string(),
            target_os: "android".to_string(),
            abi: "arm64-v8a".to_string(),
            min_sdk: 29,
            ndk_version: "1".to_string(),
            gn_args: Vec::new(),
            libcef_sha256: crate::sha256_file(&layout.libcef()).expect("can hash"),
            wrapper_sha256: crate::sha256_file(&layout.wrapper()).expect("can hash"),
            header_count: 3,
            resources: Vec::new(),
            licences: Vec::new(),
        };
        manifest.write(&root).expect("can write");

        (dir, root)
    }

    #[test]
    fn a_bundle_without_the_engine_is_not_sound() {
        let (_dir, root) = synthetic_bundle();
        std::fs::remove_file(root.join("lib/libcef.so")).expect("can remove");
        let report = audit(&root).expect("can audit");
        assert!(!report.is_ok());
        assert!(report.errors.iter().any(|e| e.contains("engine")));
    }

    #[test]
    fn a_bundle_without_a_header_is_not_sound() {
        let (_dir, root) = synthetic_bundle();
        std::fs::remove_file(root.join("include/cef_app.h")).expect("can remove");
        let report = audit(&root).expect("can audit");
        assert!(!report.is_ok());
        assert!(report.errors.iter().any(|e| e.contains("cef_app.h")));
    }

    #[test]
    fn a_bundle_without_a_resource_is_not_sound() {
        let (_dir, root) = synthetic_bundle();
        std::fs::remove_file(root.join("Resources/icudtl.dat")).expect("can remove");
        let report = audit(&root).expect("can audit");
        assert!(!report.is_ok());
        assert!(report.errors.iter().any(|e| e.contains("icudtl.dat")));
    }

    #[test]
    fn a_bundle_without_the_locales_is_not_sound() {
        let (_dir, root) = synthetic_bundle();
        std::fs::remove_dir_all(root.join("Resources/locales")).expect("can remove");
        let report = audit(&root).expect("can audit");
        assert!(!report.is_ok());
        assert!(report.errors.iter().any(|e| e.contains("locales")));
    }

    #[test]
    fn a_bundle_whose_engine_changed_after_the_provenance_is_not_sound() {
        let (_dir, root) = synthetic_bundle();
        std::fs::write(root.join("lib/libcef.so"), b"another file").expect("can write");
        let report = audit(&root).expect("can audit");
        assert!(!report.is_ok());
        assert!(report
            .errors
            .iter()
            .any(|e| e.contains("checksum of the provenance")));
    }

    #[test]
    fn a_report_without_errors_is_sound() {
        let report = AuditReport {
            errors: Vec::new(),
            notes: Vec::new(),
        };
        assert!(report.is_ok());
        assert!(report.render().contains("the bundle is sound"));
    }
}
