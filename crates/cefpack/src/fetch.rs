//! Fetching the CEF sources.
//!
//! Nothing is downloaded as a *package*.  The service checks out the CEF
//! repository at the commit that the manifest pins and lets `gclient` pull
//! the Chromium sources and their dependencies at the revisions that CEF's own
//! `DEPS` file names.  Every revision in that file is a git SHA, so the whole
//! tree is pinned by the CEF commit alone and nothing is taken on trust from
//! a mirror.

use std::{
    path::{Path, PathBuf},
    process::Command,
};

use anyhow::{bail, Context, Result};

use crate::plan::Plan;

/// The checkout of the CEF sources.
#[derive(Debug, Clone)]
pub struct SourceTree {
    /// The directory that holds the checkout.
    pub root: PathBuf,
    /// The directory that holds the CEF sources themselves.
    pub cef_dir: PathBuf,
    /// The commit that is checked out.
    pub commit: String,
}

impl SourceTree {
    /// The directory of the Chromium checkout, which is the parent of the
    /// CEF directory.
    pub fn chromium_dir(&self) -> &Path {
        self.cef_dir.parent().unwrap_or(&self.root)
    }
}

/// Fetch the sources described by the plan.
///
/// The step is idempotent: a checkout that already carries the pinned commit
/// is left alone, so an interrupted build can be resumed.
pub fn fetch(plan: &Plan) -> Result<SourceTree> {
    let repo = &plan.manifest.source.repo;
    let commit = &plan.manifest.source.commit;
    let cef_dir = plan.source_root.join("cef");

    if cef_dir.join(".git").exists() {
        let current = head_commit(&cef_dir)?;
        if current == *commit {
            log::info!("the CEF checkout is already at {commit}");
            return Ok(SourceTree {
                root: plan.source_root.clone(),
                cef_dir,
                commit: current,
            });
        }
        log::info!("the CEF checkout is at {current}, fetching {commit}");
        git(&cef_dir, &["fetch", "--depth", "1", "origin", commit])?;
        git(&cef_dir, &["checkout", "--detach", commit])?;
    } else {
        log::info!("cloning {repo} at {commit}");
        std::fs::create_dir_all(&plan.source_root)
            .with_context(|| format!("cannot create {}", plan.source_root.display()))?;
        git(
            &plan.source_root,
            &["clone", "--filter=blob:none", "--no-checkout", repo, "cef"],
        )?;
        git(&cef_dir, &["fetch", "--depth", "1", "origin", commit])?;
        git(&cef_dir, &["checkout", "--detach", commit])?;
    }

    let current = head_commit(&cef_dir)?;
    if current != *commit {
        bail!(
            "the checkout is at {current} but the manifest pins {commit}; refusing to build \
             something that is not the pinned revision"
        );
    }

    Ok(SourceTree {
        root: plan.source_root.clone(),
        cef_dir,
        commit: current,
    })
}

/// Run `gclient sync` for the checkout.
///
/// `gclient` reads the `DEPS` file of the CEF checkout and fetches Chromium
/// and every dependency at the revision that the file names.
pub fn sync(plan: &Plan, tree: &SourceTree) -> Result<()> {
    let gclient = plan.depot_tools.path.join("gclient");
    let cef_dir = &tree.cef_dir;

    log::info!("running gclient sync in {}", cef_dir.display());

    // The CEF checkout is a client of the Chromium checkout: the parent
    // directory holds the Chromium sources and the `cef` directory holds the
    // CEF layer.
    let status = Command::new(&gclient)
        .current_dir(cef_dir)
        .args([
            "sync",
            "--no-history",
            "--shallow",
            "--with_branch_heads",
            "--force",
            "--reset",
        ])
        .env("DEPOT_TOOLS_UPDATE", "0")
        .env("PATH", path_with_depot_tools(plan))
        .status()
        .with_context(|| format!("cannot run {}", gclient.display()))?;
    if !status.success() {
        bail!("gclient sync failed with {status}");
    }
    Ok(())
}

/// Fetch the headers of Chromium that the C API of CEF needs.
///
/// The public headers of CEF include a few headers of Chromium.  They are
/// fetched from the Chromium revision that CEF tracks — the branch head of the
/// Chromium release — and every one of them is checked against the checksum
/// that the manifest pins.  They are written into the bundle below its root,
/// so that the include path of the generator resolves both the headers of
/// CEF (`include/...`) and the headers of Chromium (`net/...`).
pub fn chromium_headers(bundle: &Path, manifest: &crate::manifest::Manifest) -> Result<()> {
    if manifest.chromium.revision.is_empty() || manifest.chromium.headers.is_empty() {
        return Ok(());
    }
    let base = manifest.chromium.base_url.trim_end_matches('/');
    for header in &manifest.chromium.headers {
        let url = format!("{base}/{}/{}", manifest.chromium.revision, header.path);
        log::info!("fetching {url}");
        let response = ureq::get(&url)
            .call()
            .with_context(|| format!("cannot fetch {url}"))?;
        let mut body = Vec::new();
        std::io::copy(&mut response.into_reader(), &mut body)
            .with_context(|| format!("cannot read {url}"))?;

        verify_header(&header.path, &header.sha256, &body)?;

        let target = bundle.join(&header.path);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("cannot create {}", parent.display()))?;
        }
        std::fs::write(&target, &body)
            .with_context(|| format!("cannot write {}", target.display()))?;
        log::info!("{} is in {}", header.path, target.display());
    }
    Ok(())
}

/// Check a header against the checksum that the manifest pins.
///
/// The headers of Chromium end up in the bindings of the engine, and the
/// bindings end up in a binary that runs on the device of a user.  A header
/// that is not the one of the pinned revision is a silent mismatch, so the
/// checksum is verified before the header is used.
pub fn verify_header(path: &str, expected: &str, body: &[u8]) -> Result<()> {
    let digest = crate::sha256_bytes(body);
    if digest != expected {
        bail!("the header {path} has the checksum {digest}, the manifest pins {expected}");
    }
    Ok(())
}

/// The commit that a checkout carries.
pub fn head_commit(dir: &Path) -> Result<String> {
    let output = Command::new("git")
        .current_dir(dir)
        .args(["rev-parse", "HEAD"])
        .output()
        .with_context(|| format!("cannot ask git about {}", dir.display()))?;
    if !output.status.success() {
        bail!(
            "git rev-parse failed in {}: {}",
            dir.display(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

/// Run a git command in a directory.
fn git(dir: &Path, args: &[&str]) -> Result<()> {
    let output = Command::new("git")
        .current_dir(dir)
        .args(args)
        .output()
        .with_context(|| format!("cannot run git in {}", dir.display()))?;
    if !output.status.success() {
        bail!(
            "git {} failed in {}: {}",
            args.join(" "),
            dir.display(),
            String::from_utf8_lossy(&output.stderr).trim()
        );
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

    #[test]
    fn the_head_commit_of_a_repository_is_read() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let ok = Command::new("git")
            .current_dir(dir.path())
            .args(["init", "--quiet", "--initial-branch=main"])
            .status()
            .expect("git runs");
        assert!(ok.success());
        std::fs::write(dir.path().join("a"), "x").expect("can write");
        let ok = Command::new("git")
            .current_dir(dir.path())
            .args(["add", "a"])
            .status()
            .expect("git runs");
        assert!(ok.success());
        let ok = Command::new("git")
            .current_dir(dir.path())
            .args([
                "-c",
                "user.email=cefpack@example.invalid",
                "-c",
                "user.name=cefpack",
                "commit",
                "--quiet",
                "-m",
                "x",
            ])
            .status()
            .expect("git runs");
        assert!(ok.success());
        let commit = head_commit(dir.path()).expect("the commit is read");
        assert_eq!(commit.len(), 40, "a commit is a full SHA");
    }

    #[test]
    fn a_header_with_the_wrong_checksum_is_refused() {
        let error = verify_header(
            "net/base/net_error_list.h",
            "0000000000000000000000000000000000000000000000000000000000000000",
            b"the header",
        )
        .expect_err("the checksum is wrong");
        assert!(error.to_string().contains("checksum"));
    }

    #[test]
    fn a_header_with_the_right_checksum_is_accepted() {
        verify_header(
            "net/base/net_error_list.h",
            &crate::sha256_bytes(b"the header"),
            b"the header",
        )
        .expect("the checksum is right");
    }

    #[test]
    fn the_head_commit_of_a_directory_without_a_repository_fails() {
        let dir = tempfile::tempdir().expect("a temporary directory");
        let error = head_commit(dir.path()).expect_err("there is no repository");
        assert!(error.to_string().contains("git rev-parse failed"));
    }
}
