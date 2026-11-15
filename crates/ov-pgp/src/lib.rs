//! Hardware backed OpenPGP key manager for Trap.
//!
//! The crate provides a Sequoia (RFC 4880 / RFC 9580) based keyring that
//!
//!   * stores certificates in a SQLite database inside the application
//!     sandbox,
//!   * never writes transferable secret keys to disk in the clear: they are
//!     wrapped with an AES-256-GCM master key that lives in the Android
//!     hardware Keystore (or a persisted 0600 software master key on non-Android
//!     test/desktop hosts),
//!   * performs all cryptographic operations in the browser process.

pub mod crypto;
pub mod keystore;
pub mod master_key;
pub mod store;

pub use crypto::{
    needs_passphrase, DecryptionResult, EncryptedOutcome, ImportedKeyInfo, KeySummary, Keyring,
    PgpError, SignatureInfo, SignatureStatus,
};
pub use master_key::{zeroize_slice, MasterKeyProvider, SoftwareMasterKey};
pub use store::{
    KeyringStore, StoredCertificate, SETTING_REQUIRE_BIOMETRIC, SETTING_SELECTED_SIGNING_KEY,
};

use std::sync::Arc;

use anyhow::{Context, Result};
use sequoia_openpgp::Fingerprint;

/// Parse a fingerprint in its hexadecimal representation (with or without spaces).
pub fn parse_fingerprint(text: &str) -> Result<Fingerprint> {
    let trimmed = text.trim();
    let compact: String = trimmed.chars().filter(|c| !c.is_whitespace()).collect();
    let bytes = hex::decode(&compact)
        .with_context(|| format!("{trimmed} is not a hexadecimal fingerprint"))?;
    if bytes.len() != 20 && bytes.len() != 32 {
        anyhow::bail!("{trimmed} is not a valid OpenPGP v4/v6 fingerprint");
    }
    Ok(Fingerprint::from_bytes(&bytes))
}

/// Primary User ID of an armored certificate.
pub fn user_id_of(armored: &str) -> Option<String> {
    use sequoia_openpgp::parse::Parse;
    use sequoia_openpgp::policy::StandardPolicy;

    let cert = sequoia_openpgp::Cert::from_bytes(armored.as_bytes()).ok()?;
    let policy = StandardPolicy::new();
    if let Ok(valid) = cert.with_policy(&policy, None) {
        if let Ok(primary) = valid.primary_userid() {
            return Some(String::from_utf8_lossy(primary.userid().value()).into_owned());
        }
    }
    let user_id = cert.userids().next()?;
    Some(String::from_utf8_lossy(user_id.userid().value()).into_owned())
}

/// Build the master key provider on Android using `AndroidKeyStore`.
#[cfg(target_os = "android")]
pub fn master_key_provider(
    vm: jni::JavaVM,
    alias: &str,
    require_user_auth: bool,
) -> Result<Arc<dyn MasterKeyProvider>> {
    let provider = keystore::AndroidKeystoreMasterKey::open(vm, alias, require_user_auth)?;
    Ok(Arc::new(provider))
}

/// Build an ephemeral software master key provider on non-Android platforms.
#[cfg(not(target_os = "android"))]
pub fn master_key_provider(
    _alias: &str,
    _require_user_auth: bool,
) -> Result<Arc<dyn MasterKeyProvider>> {
    log::warn!(
        "the Android Keystore is not available on this platform, using an ephemeral software master key"
    );
    Ok(Arc::new(SoftwareMasterKey::generate()?))
}

/// Open or create a persistent software master key provider stored at `key_file`
/// on non-Android platforms so secret keys survive restarts across sessions.
#[cfg(not(target_os = "android"))]
pub fn persistent_software_master_key(
    key_file: &std::path::Path,
) -> Result<Arc<dyn MasterKeyProvider>> {
    Ok(Arc::new(SoftwareMasterKey::open_or_create(key_file)?))
}
