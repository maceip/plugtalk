//! The OpenPGP key manager.
//!
//! Everything that touches key material lives here: key generation, import,
//! export, encryption, signing, decryption, and signature verification.

use std::{
    cell::RefCell,
    io::{Read, Write},
    sync::{Arc, RwLock},
    time::UNIX_EPOCH,
};

use anyhow::{anyhow, bail, Context, Result};
use sequoia_openpgp as openpgp;
use sequoia_openpgp::{
    armor,
    cert::{Cert, CertBuilder, CipherSuite},
    crypto::{Password, SessionKey},
    packet::Packet,
    parse::{
        stream::{
            DecryptionHelper, DecryptorBuilder, MessageLayer, VerificationHelper, VerifierBuilder,
        },
        Parse,
    },
    policy::StandardPolicy,
    serialize::{
        stream::{Armorer, Encryptor2, LiteralWriter, Message, Signer},
        Marshal,
    },
    types::SymmetricAlgorithm,
    Fingerprint, KeyHandle, KeyID, PacketPile,
};

use crate::{
    master_key::{zeroize_slice, MasterKeyProvider},
    store::{
        KeyringStore, StoredCertificate, SETTING_REQUIRE_BIOMETRIC, SETTING_SELECTED_SIGNING_KEY,
    },
};

/// Structured cryptographic errors that callers (and JNI entry points) inspect
/// without string matching.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum PgpError {
    /// A matching secret key is S2K-encrypted and requires a passphrase.
    #[error("Passphrase required to unlock secret key {fingerprint}")]
    PassphraseRequired {
        /// Primary certificate fingerprint of the locked secret key.
        fingerprint: Fingerprint,
    },
    /// The supplied passphrase failed to decrypt an S2K-encrypted secret key.
    #[error("Invalid passphrase for encrypted secret key")]
    InvalidPassphrase,
}

/// Returns `true` when `err` indicates that a secret key requires a passphrase
/// or that the provided passphrase was rejected.
pub fn needs_passphrase(err: &anyhow::Error) -> bool {
    matches!(
        err.downcast_ref::<PgpError>(),
        Some(PgpError::PassphraseRequired { .. } | PgpError::InvalidPassphrase)
    )
}

/// Normalized signature verification status matching the JNI contract.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SignatureStatus {
    /// Cryptographically valid signature from a certificate in the keyring.
    Good,
    /// Invalid, corrupted, forged, unbound, or policy-rejected signature.
    Bad,
    /// Signature packet present, but its issuer key is not in the keyring.
    UnknownKey,
    /// Message carried zero signature packets.
    Unsigned,
}

impl SignatureStatus {
    /// Exact lowercase status string required by `OvNative.nativeDecrypt`.
    pub fn as_str(&self) -> &'static str {
        match self {
            SignatureStatus::Good => "good",
            SignatureStatus::Bad => "bad",
            SignatureStatus::UnknownKey => "unknown_key",
            SignatureStatus::Unsigned => "unsigned",
        }
    }
}

/// Summary metadata of a certificate stored in the keyring (`nativeListKeysJson`).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KeySummary {
    /// 40-character uppercase hex primary fingerprint.
    pub fingerprint: String,
    /// Primary User ID (empty string if certificate carries none).
    pub user_id: String,
    /// Creation timestamp in Unix epoch seconds.
    pub created_epoch_secs: i64,
    /// Whether wrapped secret key material is stored in the keyring.
    pub has_secret: bool,
    /// Whether the certificate has an alive, non-revoked signing-capable key.
    pub can_sign: bool,
    /// Whether the certificate has an alive, non-revoked encryption-capable key.
    pub can_encrypt: bool,
    /// Whether this certificate belongs to the local user (`has_secret`).
    pub is_own_key: bool,
}

/// Outcome of importing a certificate or transferable secret key.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImportedKeyInfo {
    /// Primary certificate fingerprint.
    pub fingerprint: Fingerprint,
    /// Primary User ID string (or empty if none).
    pub user_id: String,
    /// Whether wrapped secret key material was imported.
    pub has_secret: bool,
}

/// Outcome of encrypting a message with optional signing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EncryptedOutcome {
    /// ASCII-armored OpenPGP message (`-----BEGIN PGP MESSAGE-----`).
    pub ciphertext: String,
    /// `true` when the message was cryptographically signed inside the encryption layer.
    pub signed: bool,
}

/// The result of a decryption or cleartext signature verification.
#[derive(Clone, Debug)]
pub struct DecryptionResult {
    /// The decrypted (or cleartext-signed) UTF-8 message body.
    pub plaintext: String,
    /// Signatures inspected on the message.
    pub signatures: Vec<SignatureInfo>,
    /// Primary certificate fingerprint of the secret key that decrypted the session key.
    pub decrypted_with: Option<Fingerprint>,
}

impl DecryptionResult {
    /// Overall signature status of the message (`"good"|"bad"|"unknown_key"|"unsigned"`).
    pub fn signature_status(&self) -> SignatureStatus {
        if self.signatures.is_empty() {
            return SignatureStatus::Unsigned;
        }
        if self
            .signatures
            .iter()
            .any(|s| s.status == SignatureStatus::Good)
            && !self
                .signatures
                .iter()
                .any(|s| s.status == SignatureStatus::Bad)
        {
            return SignatureStatus::Good;
        }
        if self
            .signatures
            .iter()
            .any(|s| s.status == SignatureStatus::Bad)
        {
            return SignatureStatus::Bad;
        }
        if self
            .signatures
            .iter()
            .any(|s| s.status == SignatureStatus::UnknownKey)
        {
            return SignatureStatus::UnknownKey;
        }
        SignatureStatus::Good
    }

    /// Primary signature record driving UI attribution.
    pub fn primary_signature(&self) -> Option<&SignatureInfo> {
        let wanted = self.signature_status();
        self.signatures
            .iter()
            .find(|s| s.status == wanted)
            .or_else(|| self.signatures.first())
    }

    /// Best available signer fingerprint or issuer hex string for UI attribution.
    pub fn signer_fingerprint_hex(&self) -> String {
        let Some(sig) = self.primary_signature() else {
            return String::new();
        };
        if let Some(fp) = &sig.fingerprint {
            return fp.to_hex();
        }
        if let Some(fp) = &sig.signing_key_fingerprint {
            return fp.to_hex();
        }
        if !sig.key_id.is_wildcard() {
            return sig.key_id.to_hex();
        }
        String::new()
    }

    /// Primary verified User ID of the signer certificate, or `""` if unknown/unsigned.
    pub fn signer_user_id(&self) -> String {
        self.primary_signature()
            .and_then(|s| s.user_id.clone())
            .unwrap_or_default()
    }
}

/// Information about a single OpenPGP signature.
#[derive(Clone, Debug)]
pub struct SignatureInfo {
    /// KeyID of the signing key or subkey.
    pub key_id: KeyID,
    /// Primary certificate fingerprint of the signer, when known in the keyring.
    pub fingerprint: Option<Fingerprint>,
    /// Fingerprint of the specific signing key/subkey (or issuer subpacket).
    pub signing_key_fingerprint: Option<Fingerprint>,
    /// Primary User ID of the signer certificate, when known.
    pub user_id: Option<String>,
    /// All policy-valid User IDs on the signer certificate.
    pub user_ids: Vec<String>,
    /// Signature creation timestamp (Unix epoch seconds), when present.
    pub created_at: Option<i64>,
    /// Normalized signature verification status.
    pub status: SignatureStatus,
    /// `true` when the signature was cryptographically verified under `StandardPolicy`.
    pub verified: bool,
    /// Reason why verification failed (`None` when `verified == true`).
    pub error: Option<String>,
}

/// The OpenPGP key manager.
pub struct Keyring {
    store: KeyringStore,
    master: RwLock<Arc<dyn MasterKeyProvider>>,
    policy: StandardPolicy<'static>,
}

impl Keyring {
    /// Open a keyring protected by `master`.
    pub fn open(store: KeyringStore, master: Arc<dyn MasterKeyProvider>) -> Self {
        Self {
            store,
            master: RwLock::new(master),
            policy: StandardPolicy::new(),
        }
    }

    fn master(&self) -> Arc<dyn MasterKeyProvider> {
        match self.master.read() {
            Ok(guard) => Arc::clone(&guard),
            Err(poisoned) => Arc::clone(&poisoned.into_inner()),
        }
    }

    /// Description of the active master key backend.
    pub fn master_key_description(&self) -> String {
        self.master().describe()
    }

    /// Reference to the underlying SQLite keyring store.
    pub fn store(&self) -> &KeyringStore {
        &self.store
    }

    /// Policy applied to certificates and messages.
    pub fn policy(&self) -> &StandardPolicy<'static> {
        &self.policy
    }

    /// Read the persisted `require_biometric` setting (`false` by default).
    pub fn require_biometric(&self) -> Result<bool> {
        Ok(self
            .store
            .get_setting(SETTING_REQUIRE_BIOMETRIC)?
            .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
            .unwrap_or(false))
    }

    /// Persist `require_biometric` without changing the master key provider
    /// (used on non-Android platforms with `SoftwareMasterKey`).
    pub fn set_require_biometric_flag(&self, required: bool) -> Result<()> {
        self.store
            .set_setting(SETTING_REQUIRE_BIOMETRIC, if required { "1" } else { "0" })
    }

    /// Atomically re-wrap all stored secret keys under `new_master`, update the
    /// persisted `require_biometric` flag, and swap the active master key.
    pub fn rotate_master_key(
        &self,
        new_master: Arc<dyn MasterKeyProvider>,
        require_biometric: bool,
    ) -> Result<usize> {
        let mut guard = match self.master.write() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        let migrated = self
            .store
            .rewrap_all_secrets(guard.as_ref(), new_master.as_ref())?;
        self.store.set_setting(
            SETTING_REQUIRE_BIOMETRIC,
            if require_biometric { "1" } else { "0" },
        )?;
        *guard = new_master;
        Ok(migrated)
    }

    /// Currently selected outgoing signing key fingerprint, if configured and still present.
    pub fn selected_signing_key(&self) -> Result<Option<Fingerprint>> {
        let Some(hex_str) = self.store.get_setting(SETTING_SELECTED_SIGNING_KEY)? else {
            return Ok(None);
        };
        if hex_str.trim().is_empty() {
            return Ok(None);
        }
        let Ok(fp) = crate::parse_fingerprint(&hex_str) else {
            return Ok(None);
        };
        if self.store.has_secret(&fp)? {
            Ok(Some(fp))
        } else {
            Ok(None)
        }
    }

    /// Persist the preferred outgoing signing key (`None` disables automatic signing selection).
    pub fn set_selected_signing_key(&self, fingerprint: Option<&Fingerprint>) -> Result<()> {
        match fingerprint {
            Some(fp) => {
                if !self.store.has_secret(fp)? {
                    bail!("certificate {fp} has no secret key in the keyring");
                }
                self.store
                    .set_setting(SETTING_SELECTED_SIGNING_KEY, &fp.to_hex())
            }
            None => {
                let _ = self.store.remove_setting(SETTING_SELECTED_SIGNING_KEY)?;
                Ok(())
            }
        }
    }

    /// All certificates in the keyring.
    pub fn certificates(&self) -> Result<Vec<StoredCertificate>> {
        self.store.certificates()
    }

    /// Rich summaries of all certificates in the keyring (`nativeListKeysJson`).
    pub fn list_key_summaries(&self) -> Result<Vec<KeySummary>> {
        let stored_list = self.store.certificates()?;
        let mut summaries = Vec::with_capacity(stored_list.len());
        for stored in stored_list {
            let cert = parse_cert(stored.armored.as_bytes())?;
            let user_id = primary_user_id_of_cert(&cert, &self.policy).unwrap_or_default();
            let cert_created = cert
                .primary_key()
                .key()
                .creation_time()
                .duration_since(UNIX_EPOCH)
                .ok()
                .map(|d| d.as_secs() as i64)
                .filter(|&secs| secs > 0)
                .unwrap_or(stored.created_at);

            let can_sign = cert
                .keys()
                .with_policy(&self.policy, None)
                .supported()
                .alive()
                .revoked(false)
                .for_signing()
                .next()
                .is_some();

            let can_encrypt = cert
                .keys()
                .with_policy(&self.policy, None)
                .supported()
                .alive()
                .revoked(false)
                .for_transport_encryption()
                .next()
                .is_some();

            summaries.push(KeySummary {
                fingerprint: stored.fingerprint.to_hex(),
                user_id,
                created_epoch_secs: cert_created,
                has_secret: stored.has_secret,
                can_sign,
                can_encrypt,
                is_own_key: stored.has_secret,
            });
        }
        Ok(summaries)
    }

    /// Generate a new Ed25519/Cv25519 key pair without an S2K passphrase
    /// (still wrapped at rest by the Keystore / AES-256-GCM master key).
    pub fn generate_keypair(&self, userid: &str) -> Result<Fingerprint> {
        self.generate_keypair_with_passphrase(userid, None)
    }

    /// Generate a new Ed25519/Cv25519 key pair for `userid`, optionally
    /// protecting its secret key packets with `passphrase` (S2K) before
    /// wrapping the TSK with the master wrapping key.
    pub fn generate_keypair_with_passphrase(
        &self,
        userid: &str,
        passphrase: Option<&[u8]>,
    ) -> Result<Fingerprint> {
        let trimmed_uid = userid.trim();
        if trimmed_uid.is_empty() {
            bail!("User ID must not be empty");
        }

        let mut builder =
            CertBuilder::general_purpose(CipherSuite::default(), Some(trimmed_uid));
        if let Some(pw) = non_empty_passphrase(passphrase) {
            builder = builder.set_password(Some(Password::from(pw)));
        }

        let (cert, _revocation) = builder
            .generate()
            .map_err(|e| anyhow!("cannot generate a key pair: {e}"))?;

        let fingerprint = cert.fingerprint();
        let mut tsk = serialize_transferable_secret_key(&cert)?
            .ok_or_else(|| anyhow!("the generated key has no secret material"))?;
        let wrap_res = self.master().wrap(&tsk);
        zeroize_slice(&mut tsk);
        let wrapped = wrap_res?;

        let armored = armor_public_key(&cert)?;
        self.store
            .put_certificate_and_secret(&fingerprint, &armored, Some(&wrapped))?;

        if self.selected_signing_key()?.is_none() {
            let _ = self
                .store
                .set_setting(SETTING_SELECTED_SIGNING_KEY, &fingerprint.to_hex());
        }

        log::info!("generated a new key pair {fingerprint} for {trimmed_uid:?}");
        Ok(fingerprint)
    }

    /// Import a certificate or transferable secret key without passphrase.
    pub fn import_cert(&self, data: &[u8]) -> Result<Fingerprint> {
        Ok(self.import_cert_with_passphrase(data, None)?.fingerprint)
    }

    /// Import a certificate or transferable secret key (armored or binary).
    ///
    /// - If `data` contains S2K-encrypted secret keys and `passphrase` is
    ///   non-empty, the passphrase is verified immediately and the unlocked
    ///   secret key is wrapped with the master key (`InvalidPassphrase` if wrong).
    /// - If `data` contains S2K-encrypted secret keys and `passphrase` is
    ///   empty/`None`, the S2K-encrypted TSK is wrapped with the master key so
    ///   later decryption/signing prompts for the passphrase (`needsPassphrase`).
    pub fn import_cert_with_passphrase(
        &self,
        data: &[u8],
        passphrase: Option<&[u8]>,
    ) -> Result<ImportedKeyInfo> {
        let cert = parse_cert(data)?;
        let fingerprint = cert.fingerprint();
        let user_id = primary_user_id_of_cert(&cert, &self.policy).unwrap_or_default();
        let armored = armor_public_key(&cert)?;

        if cert_has_secret(&cert) {
            let cert_for_tsk = if cert_has_encrypted_secret(&cert) {
                if let Some(pw) = non_empty_passphrase(passphrase) {
                    decrypt_cert_secrets(cert, pw)?
                } else {
                    cert
                }
            } else {
                cert
            };

            let mut tsk = serialize_transferable_secret_key(&cert_for_tsk)?
                .ok_or_else(|| anyhow!("imported secret certificate lost secret material"))?;
            let wrap_res = self.master().wrap(&tsk);
            zeroize_slice(&mut tsk);
            let wrapped = wrap_res?;

            self.store
                .put_certificate_and_secret(&fingerprint, &armored, Some(&wrapped))?;

            if self.selected_signing_key()?.is_none() {
                let _ = self
                    .store
                    .set_setting(SETTING_SELECTED_SIGNING_KEY, &fingerprint.to_hex());
            }

            log::info!("imported certificate {fingerprint} including its secret key");
            Ok(ImportedKeyInfo {
                fingerprint,
                user_id,
                has_secret: true,
            })
        } else {
            let existing_has_secret = self.store.has_secret(&fingerprint)?;
            self.store.put_certificate(&fingerprint, &armored)?;
            log::info!("imported public certificate {fingerprint}");
            Ok(ImportedKeyInfo {
                fingerprint,
                user_id,
                has_secret: existing_has_secret,
            })
        }
    }

    /// Permanently unlock an S2K-encrypted secret key in the keyring so its
    /// secret material is protected solely by the hardware/master wrapping key.
    pub fn unlock_secret_key(&self, fingerprint: &Fingerprint, passphrase: &[u8]) -> Result<()> {
        let master = self.master();
        let wrapped = self
            .store
            .secret(fingerprint)?
            .ok_or_else(|| anyhow!("no secret key {fingerprint} in the keyring"))?;
        let mut tsk_bytes = master
            .unwrap(&wrapped)
            .context("cannot unwrap secret key")?;
        let parse_res = parse_cert(&tsk_bytes);
        zeroize_slice(&mut tsk_bytes);
        let cert = parse_res?;

        let unlocked_cert = if cert_has_encrypted_secret(&cert) {
            let Some(pw) = non_empty_passphrase(Some(passphrase)) else {
                return Err(PgpError::PassphraseRequired {
                    fingerprint: fingerprint.clone(),
                }
                .into());
            };
            decrypt_cert_secrets(cert, pw)?
        } else {
            cert
        };

        let mut new_tsk = serialize_transferable_secret_key(&unlocked_cert)?
            .ok_or_else(|| anyhow!("unlocked key {fingerprint} has no secret material"))?;
        let new_wrapped_res = master.wrap(&new_tsk);
        zeroize_slice(&mut new_tsk);
        let new_wrapped = new_wrapped_res?;
        self.store.put_secret(fingerprint, &new_wrapped)
    }

    /// Returns `true` when `fingerprint` has a stored secret key whose packets
    /// are still S2K-encrypted (requiring a passphrase).
    pub fn is_secret_key_locked(&self, fingerprint: &Fingerprint) -> Result<bool> {
        let Some(wrapped) = self.store.secret(fingerprint)? else {
            return Ok(false);
        };
        let mut tsk = self.master().unwrap(&wrapped)?;
        let cert_res = parse_cert(&tsk);
        zeroize_slice(&mut tsk);
        let cert = cert_res?;
        Ok(cert_has_encrypted_secret(&cert))
    }

    /// Export the ASCII-armored public key of `fingerprint`.
    pub fn export_armored_public_key(&self, fingerprint: &Fingerprint) -> Result<String> {
        self.store
            .certificate(fingerprint)?
            .ok_or_else(|| anyhow!("no certificate {fingerprint} in the keyring"))
    }

    /// Export the public key for `fingerprint_or_empty` (when empty, exports
    /// the selected signing key, or newest own key, or newest stored cert).
    pub fn export_public_key_by_query(&self, fingerprint_or_empty: &str) -> Result<String> {
        let trimmed = fingerprint_or_empty.trim();
        if !trimmed.is_empty() {
            let fp = crate::parse_fingerprint(trimmed)?;
            return self.export_armored_public_key(&fp);
        }

        if let Some(selected) = self.selected_signing_key()? {
            if let Some(armored) = self.store.certificate(&selected)? {
                return Ok(armored);
            }
        }

        let certs = self.store.certificates()?;
        if let Some(own) = certs.iter().find(|c| c.has_secret) {
            return Ok(own.armored.clone());
        }
        if let Some(first) = certs.first() {
            return Ok(first.armored.clone());
        }
        bail!("keyring contains no certificates to export")
    }

    /// Remove the secret key of `fingerprint`, keeping the public part.
    pub fn delete_secret_key(&self, fingerprint: &Fingerprint) -> Result<bool> {
        self.store.remove_secret(fingerprint)
    }

    /// Remove the certificate and any associated wrapped secret key.
    pub fn delete_certificate(&self, fingerprint: &Fingerprint) -> Result<bool> {
        self.store.remove_certificate(fingerprint)
    }

    /// Encrypt `plaintext` for `recipients` and optionally sign it.
    pub fn encrypt_armored(
        &self,
        plaintext: &str,
        recipients: &[Fingerprint],
        sign_with: Option<&Fingerprint>,
    ) -> Result<String> {
        Ok(self
            .encrypt_with_options(plaintext, recipients, sign_with, None, sign_with.is_some())?
            .ciphertext)
    }

    /// Encrypt `plaintext` for `recipients`.
    ///
    /// - When `sign_with` is `Some(fp)`, signs with `fp` (unlocking with
    ///   `passphrase` if S2K-encrypted; errors if missing/locked).
    /// - When `sign_with` is `None` and `auto_sign_if_available` is `true`,
    ///   automatically signs with the selected or newest usable local signing
    ///   key if one can be unlocked without failing encryption when none exists.
    pub fn encrypt_with_options(
        &self,
        plaintext: &str,
        recipients: &[Fingerprint],
        sign_with: Option<&Fingerprint>,
        passphrase: Option<&[u8]>,
        auto_sign_if_available: bool,
    ) -> Result<EncryptedOutcome> {
        if recipients.is_empty() {
            bail!("no recipient given");
        }

        let certs = recipients
            .iter()
            .map(|fingerprint| self.certificate_for(fingerprint))
            .collect::<Result<Vec<_>>>()?;

        let mut recipient_keys = Vec::new();
        for (fingerprint, cert) in recipients.iter().zip(certs.iter()) {
            let keys = cert
                .keys()
                .with_policy(&self.policy, None)
                .supported()
                .alive()
                .revoked(false)
                .for_transport_encryption()
                .map(|key| key.key())
                .collect::<Vec<_>>();
            if keys.is_empty() {
                bail!("the certificate {fingerprint} has no usable encryption subkey");
            }
            recipient_keys.extend(keys);
        }

        let signer = if let Some(explicit_fp) = sign_with {
            Some(self.signing_keypair(explicit_fp, passphrase)?)
        } else if auto_sign_if_available {
            self.auto_signing_keypair(passphrase)?
        } else {
            None
        };
        let signed = signer.is_some();

        let mut sink = Vec::new();
        {
            let message = Message::new(&mut sink);
            let message = Armorer::new(message)
                .kind(armor::Kind::Message)
                .build()
                .map_err(|e| anyhow!("cannot armourize the message: {e}"))?;
            let message = Encryptor2::for_recipients(message, recipient_keys)
                .build()
                .map_err(|e| anyhow!("cannot encrypt the message: {e}"))?;
            let message = match signer {
                Some(keypair) => Signer::new(message, keypair)
                    .build()
                    .map_err(|e| anyhow!("cannot sign the message: {e}"))?,
                None => message,
            };
            let mut literal = LiteralWriter::new(message)
                .format(openpgp::types::DataFormat::Text)
                .build()
                .map_err(|e| anyhow!("cannot create the literal writer: {e}"))?;
            literal
                .write_all(plaintext.as_bytes())
                .map_err(|e| anyhow!("cannot write the plaintext: {e}"))?;
            literal
                .finalize()
                .map_err(|e| anyhow!("cannot finalise the message: {e}"))?;
        }

        let ciphertext = String::from_utf8(sink)
            .map_err(|e| anyhow!("the armored message is not valid UTF-8: {e}"))?;
        Ok(EncryptedOutcome { ciphertext, signed })
    }

    /// Decrypt an ASCII-armored OpenPGP message without an S2K passphrase.
    pub fn decrypt_armored(&self, ciphertext: &str) -> Result<DecryptionResult> {
        self.decrypt_armored_with_passphrase(ciphertext, None)
    }

    /// Decrypt an ASCII-armored OpenPGP message (or verify a cleartext-signed
    /// `-----BEGIN PGP SIGNED MESSAGE-----` block), using `passphrase` on-the-fly
    /// if a matching secret key is S2K-encrypted.
    pub fn decrypt_armored_with_passphrase(
        &self,
        ciphertext: &str,
        passphrase: Option<&[u8]>,
    ) -> Result<DecryptionResult> {
        let trimmed = ciphertext.trim();
        if trimmed.starts_with("-----BEGIN PGP SIGNED MESSAGE-----") {
            return self.verify_armored(trimmed);
        }

        let payload = dearmor_if_needed(trimmed.as_bytes())?;
        let master = self.master();
        let passphrase_outcome: RefCell<Option<PgpError>> = RefCell::new(None);
        let helper = KeyringHelper {
            store: &self.store,
            master: master.as_ref(),
            policy: &self.policy,
            passphrase: non_empty_passphrase(passphrase),
            passphrase_outcome: &passphrase_outcome,
            signatures: Vec::new(),
            decrypted_with: None,
        };

        let builder = DecryptorBuilder::from_bytes(&payload)
            .map_err(|e| anyhow!("cannot read the OpenPGP message: {e}"))?;
        let mut decryptor = match builder.with_policy(&self.policy, None, helper) {
            Ok(dec) => dec,
            Err(err) => {
                if let Some(pgp_err) = passphrase_outcome.into_inner() {
                    return Err(pgp_err.into());
                }
                return Err(anyhow!("cannot decrypt the OpenPGP message: {err}"));
            }
        };

        let mut plaintext = Vec::new();
        decryptor
            .read_to_end(&mut plaintext)
            .map_err(|e| anyhow!("cannot read the decrypted message: {e}"))?;
        let helper = decryptor.into_helper();

        Ok(DecryptionResult {
            plaintext: String::from_utf8(plaintext)
                .map_err(|e| anyhow!("the decrypted message is not valid UTF-8: {e}"))?,
            signatures: helper.signatures,
            decrypted_with: helper.decrypted_with,
        })
    }

    /// Verify a cleartext-signed or inline-signed OpenPGP message and extract
    /// its signed UTF-8 message body alongside full signer attribution.
    pub fn verify_armored(&self, signed_message: &str) -> Result<DecryptionResult> {
        let master = self.master();
        let passphrase_outcome: RefCell<Option<PgpError>> = RefCell::new(None);
        let helper = KeyringHelper {
            store: &self.store,
            master: master.as_ref(),
            policy: &self.policy,
            passphrase: None,
            passphrase_outcome: &passphrase_outcome,
            signatures: Vec::new(),
            decrypted_with: None,
        };

        let mut verifier = VerifierBuilder::from_bytes(signed_message.as_bytes())
            .map_err(|e| anyhow!("cannot parse signed OpenPGP message: {e}"))?
            .with_policy(&self.policy, None, helper)
            .map_err(|e| anyhow!("cannot verify signed OpenPGP message: {e}"))?;

        let mut body = Vec::new();
        verifier
            .read_to_end(&mut body)
            .map_err(|e| anyhow!("cannot read signed OpenPGP body: {e}"))?;
        let helper = verifier.into_helper();

        Ok(DecryptionResult {
            plaintext: String::from_utf8(body)
                .map_err(|e| anyhow!("the signed message body is not valid UTF-8: {e}"))?,
            signatures: helper.signatures,
            decrypted_with: None,
        })
    }

    /// Extract a deterministic cryptographic fingerprint or KeyID hint from an
    /// armored OpenPGP public key, private key, encrypted message, or signed
    /// message, resolving subkey KeyIDs to primary certificate fingerprints
    /// whenever the certificate is known in this keyring.
    pub fn fingerprint_hint_of_armored(&self, armored: &str) -> Option<String> {
        let trimmed = armored.trim();
        if trimmed.is_empty() {
            return None;
        }

        if let Ok(cert) = parse_cert(trimmed.as_bytes()) {
            return Some(cert.fingerprint().to_hex());
        }

        if trimmed.starts_with("-----BEGIN PGP SIGNED MESSAGE-----") {
            if let Ok(verified) = self.verify_armored(trimmed) {
                let hex = verified.signer_fingerprint_hex();
                if !hex.is_empty() {
                    return Some(hex);
                }
            }
        }

        let binary = dearmor_if_needed(trimmed.as_bytes()).ok()?;
        let pile = PacketPile::from_bytes(&binary).ok()?;
        for packet in pile.descendants() {
            match packet {
                Packet::PKESK(pkesk) => {
                    let recipient = pkesk.recipient();
                    if !recipient.is_wildcard() {
                        if let Some(fp) =
                            self.resolve_primary_fingerprint(&KeyHandle::KeyID(recipient.clone()))
                        {
                            return Some(fp.to_hex());
                        }
                        return Some(recipient.to_hex());
                    }
                }
                Packet::OnePassSig(ops) => {
                    let issuer = ops.issuer();
                    if !issuer.is_wildcard() {
                        if let Some(fp) =
                            self.resolve_primary_fingerprint(&KeyHandle::KeyID(issuer.clone()))
                        {
                            return Some(fp.to_hex());
                        }
                        return Some(issuer.to_hex());
                    }
                }
                Packet::Signature(sig) => {
                    for handle in sig.get_issuers() {
                        if let Some(fp) = self.resolve_primary_fingerprint(&handle) {
                            return Some(fp.to_hex());
                        }
                        match handle {
                            KeyHandle::Fingerprint(fp) => return Some(fp.to_hex()),
                            KeyHandle::KeyID(kid) if !kid.is_wildcard() => {
                                return Some(kid.to_hex())
                            }
                            _ => {}
                        }
                    }
                }
                _ => {}
            }
        }
        None
    }

    fn resolve_primary_fingerprint(&self, handle: &KeyHandle) -> Option<Fingerprint> {
        let stored = self.store.certificates().ok()?;
        for entry in stored {
            let Ok(cert) = parse_cert(entry.armored.as_bytes()) else {
                continue;
            };
            if cert
                .keys()
                .any(|ka| ka.key().key_handle().aliases(handle))
            {
                return Some(cert.fingerprint());
            }
        }
        None
    }

    fn certificate_for(&self, fingerprint: &Fingerprint) -> Result<Cert> {
        let armored = self
            .store
            .certificate(fingerprint)?
            .ok_or_else(|| anyhow!("no certificate {fingerprint} in the keyring"))?;
        parse_cert(armored.as_bytes())
    }

    fn auto_signing_keypair(
        &self,
        passphrase: Option<&[u8]>,
    ) -> Result<Option<openpgp::crypto::KeyPair>> {
        let mut candidates = Vec::new();
        if let Some(selected) = self.selected_signing_key()? {
            candidates.push(selected);
        }
        for stored in self.store.certificates()? {
            if stored.has_secret && !candidates.contains(&stored.fingerprint) {
                candidates.push(stored.fingerprint);
            }
        }

        for fp in candidates {
            match self.signing_keypair(&fp, passphrase) {
                Ok(kp) => return Ok(Some(kp)),
                Err(err) => {
                    if needs_passphrase(&err) {
                        log::debug!("skipping locked auto-signing key {fp}: {err}");
                        continue;
                    }
                    log::debug!("skipping non-signing own key {fp}: {err}");
                }
            }
        }
        Ok(None)
    }

    fn signing_keypair(
        &self,
        fingerprint: &Fingerprint,
        passphrase: Option<&[u8]>,
    ) -> Result<openpgp::crypto::KeyPair> {
        let wrapped = self
            .store
            .secret(fingerprint)?
            .ok_or_else(|| anyhow!("no secret key {fingerprint} in the keyring"))?;
        let mut tsk = self
            .master()
            .unwrap(&wrapped)
            .context("cannot unwrap the secret key")?;
        let cert_res = parse_cert(&tsk);
        zeroize_slice(&mut tsk);
        let cert = cert_res?;

        let candidates = cert
            .keys()
            .with_policy(&self.policy, None)
            .supported()
            .alive()
            .revoked(false)
            .for_signing()
            .collect::<Vec<_>>();

        let mut saw_locked = false;
        let mut saw_bad_pw = false;

        for candidate in candidates {
            let secret = match candidate.key().clone().parts_into_secret() {
                Ok(secret) => secret,
                Err(_) => continue,
            };
            let unlocked = if secret.has_unencrypted_secret() {
                secret
            } else if let Some(pw) = non_empty_passphrase(passphrase) {
                match secret.decrypt_secret(&Password::from(pw)) {
                    Ok(dec) => dec,
                    Err(_) => {
                        saw_bad_pw = true;
                        continue;
                    }
                }
            } else {
                saw_locked = true;
                continue;
            };

            if let Ok(keypair) = unlocked.into_keypair() {
                return Ok(keypair);
            }
        }

        if saw_bad_pw {
            return Err(PgpError::InvalidPassphrase.into());
        }
        if saw_locked {
            return Err(PgpError::PassphraseRequired {
                fingerprint: fingerprint.clone(),
            }
            .into());
        }
        bail!("the key {fingerprint} has no usable signing key")
    }
}

/// Helper feeding Sequoia with keyring certificates and wrapped secret keys.
struct KeyringHelper<'a> {
    store: &'a KeyringStore,
    master: &'a dyn MasterKeyProvider,
    policy: &'a StandardPolicy<'static>,
    passphrase: Option<&'a [u8]>,
    passphrase_outcome: &'a RefCell<Option<PgpError>>,
    signatures: Vec<SignatureInfo>,
    decrypted_with: Option<Fingerprint>,
}

impl KeyringHelper<'_> {
    fn secret_certificates(&self) -> Result<Vec<(Fingerprint, Cert)>> {
        let mut out = Vec::new();
        for stored in self.store.certificates()? {
            let Some(wrapped) = self.store.secret(&stored.fingerprint)? else {
                continue;
            };
            let mut tsk = self
                .master
                .unwrap(&wrapped)
                .with_context(|| format!("cannot unwrap the secret key {}", stored.fingerprint))?;
            let cert_res = parse_cert(&tsk);
            zeroize_slice(&mut tsk);
            let cert = cert_res?;
            out.push((stored.fingerprint, cert));
        }
        Ok(out)
    }

    fn resolve_cert_for_issuers(&self, handles: &[KeyHandle]) -> Option<(Fingerprint, Vec<String>)> {
        let stored = self.store.certificates().ok()?;
        for entry in stored {
            let Ok(cert) = parse_cert(entry.armored.as_bytes()) else {
                continue;
            };
            let matched = cert.keys().any(|ka| {
                let kh = ka.key().key_handle();
                handles.iter().any(|wanted| kh.aliases(wanted))
            });
            if matched {
                let uids = user_ids_of_cert(&cert, self.policy);
                return Some((cert.fingerprint(), uids));
            }
        }
        None
    }
}

impl VerificationHelper for KeyringHelper<'_> {
    fn get_certs(&mut self, keyids: &[KeyHandle]) -> Result<Vec<Cert>> {
        let mut certs = Vec::new();
        for stored in self.store.certificates()? {
            let Ok(cert) = parse_cert(stored.armored.as_bytes()) else {
                continue;
            };
            // Match against ALL keys/subkeys on the certificate so even an
            // expired/unbound subkey yields `BadKey`/`UnboundKey` (`"bad"`)
            // rather than `MissingKey` (`"unknown_key"`).
            let matches = cert.keys().any(|key| {
                let handle = key.key().key_handle();
                keyids.iter().any(|wanted| handle.aliases(wanted))
            });
            if matches {
                certs.push(cert);
            }
        }
        Ok(certs)
    }

    fn check(&mut self, structure: openpgp::parse::stream::MessageStructure) -> Result<()> {
        for layer in structure {
            let MessageLayer::SignatureGroup { results } = layer else {
                continue;
            };
            for result in results {
                match result {
                    Ok(good) => {
                        let primary_fp = good.ka.cert().fingerprint();
                        let signing_fp = good.ka.key().fingerprint();
                        let key_id = good.ka.key().keyid();
                        let user_ids = user_ids_of_cert(good.ka.cert(), self.policy);
                        let user_id = user_ids.first().cloned();
                        let created_at = good
                            .sig
                            .signature_creation_time()
                            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                            .map(|d| d.as_secs() as i64);

                        self.signatures.push(SignatureInfo {
                            key_id,
                            fingerprint: Some(primary_fp),
                            signing_key_fingerprint: Some(signing_fp),
                            user_id,
                            user_ids,
                            created_at,
                            status: SignatureStatus::Good,
                            verified: true,
                            error: None,
                        });
                    }
                    Err(err) => {
                        use openpgp::parse::stream::VerificationError::*;

                        let (sig, status, known_cert) = match &err {
                            MissingKey { sig } => (sig, SignatureStatus::UnknownKey, None),
                            MalformedSignature { sig, .. } => (sig, SignatureStatus::Bad, None),
                            UnboundKey { sig, cert, .. } => {
                                (sig, SignatureStatus::Bad, Some(*cert))
                            }
                            BadKey { sig, ka, .. } => {
                                (sig, SignatureStatus::Bad, Some(ka.cert()))
                            }
                            BadSignature { sig, ka, .. } => {
                                (sig, SignatureStatus::Bad, Some(ka.cert()))
                            }
                        };

                        let issuers = sig.get_issuers();
                        let key_id = signature_key_id(sig);
                        let issuer_fp = sig.issuer_fingerprints().next().cloned();
                        let created_at = sig
                            .signature_creation_time()
                            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                            .map(|d| d.as_secs() as i64);

                        let (primary_fp, user_ids) = if let Some(cert) = known_cert {
                            (
                                Some(cert.fingerprint()),
                                user_ids_of_cert(cert, self.policy),
                            )
                        } else if status == SignatureStatus::Bad {
                            match self.resolve_cert_for_issuers(&issuers) {
                                Some((fp, uids)) => (Some(fp), uids),
                                None => (None, Vec::new()),
                            }
                        } else {
                            (None, Vec::new())
                        };

                        let user_id = user_ids.first().cloned();

                        self.signatures.push(SignatureInfo {
                            key_id,
                            fingerprint: primary_fp,
                            signing_key_fingerprint: issuer_fp,
                            user_id,
                            user_ids,
                            created_at,
                            status,
                            verified: false,
                            error: Some(err.to_string()),
                        });
                    }
                }
            }
        }
        Ok(())
    }
}

impl DecryptionHelper for KeyringHelper<'_> {
    fn decrypt<D>(
        &mut self,
        pkesks: &[openpgp::packet::PKESK],
        _skesks: &[openpgp::packet::SKESK],
        sym_algo: Option<SymmetricAlgorithm>,
        mut decrypt: D,
    ) -> Result<Option<Fingerprint>>
    where
        D: FnMut(SymmetricAlgorithm, &SessionKey) -> bool,
    {
        let secrets = self.secret_certificates()?;
        let mut saw_locked: Option<Fingerprint> = None;
        let mut saw_bad_pw = false;

        for pkesk in pkesks {
            let recipient = pkesk.recipient();
            for (fingerprint, cert) in &secrets {
                for key_amalg in cert.keys().secret() {
                    let secret_key = key_amalg.key();
                    if !recipient.is_wildcard() && secret_key.keyid() != *recipient {
                        continue;
                    }

                    let unlocked = if secret_key.has_unencrypted_secret() {
                        secret_key.clone()
                    } else if let Some(pw) = self.passphrase {
                        match secret_key.clone().decrypt_secret(&Password::from(pw)) {
                            Ok(dec) => dec,
                            Err(_) => {
                                saw_bad_pw = true;
                                continue;
                            }
                        }
                    } else {
                        if saw_locked.is_none() {
                            saw_locked = Some(fingerprint.clone());
                        }
                        continue;
                    };

                    let mut keypair = match unlocked.into_keypair() {
                        Ok(kp) => kp,
                        Err(err) => {
                            log::warn!("cannot build decryption keypair for {fingerprint}: {err:#}");
                            continue;
                        }
                    };

                    let Some((algo, session_key)) = pkesk.decrypt(&mut keypair, sym_algo) else {
                        continue;
                    };
                    let algo = sym_algo.unwrap_or(algo);
                    if decrypt(algo, &session_key) {
                        self.decrypted_with = Some(fingerprint.clone());
                        return Ok(Some(fingerprint.clone()));
                    }
                }
            }
        }

        if saw_bad_pw {
            *self.passphrase_outcome.borrow_mut() = Some(PgpError::InvalidPassphrase);
            return Err(PgpError::InvalidPassphrase.into());
        }
        if let Some(fp) = saw_locked {
            let err = PgpError::PassphraseRequired { fingerprint: fp };
            *self.passphrase_outcome.borrow_mut() = Some(err.clone());
            return Err(err.into());
        }

        Ok(None)
    }
}

fn non_empty_passphrase(passphrase: Option<&[u8]>) -> Option<&[u8]> {
    passphrase.filter(|pw| !pw.is_empty())
}

fn cert_has_secret(cert: &Cert) -> bool {
    cert.keys().secret().next().is_some()
}

fn cert_has_encrypted_secret(cert: &Cert) -> bool {
    cert.keys()
        .secret()
        .any(|ka| !ka.key().has_unencrypted_secret())
}

fn decrypt_cert_secrets(cert: Cert, passphrase: &[u8]) -> Result<Cert> {
    let pw = Password::from(passphrase);
    let mut unlocked_packets = Vec::new();
    for packet in cert.into_tsk().into_packets() {
        match packet {
            Packet::SecretKey(sk) => {
                let dec = if sk.has_unencrypted_secret() {
                    sk
                } else {
                    sk.decrypt_secret(&pw)
                        .map_err(|_| PgpError::InvalidPassphrase)?
                };
                unlocked_packets.push(Packet::SecretKey(dec));
            }
            Packet::SecretSubkey(ssk) => {
                let dec = if ssk.has_unencrypted_secret() {
                    ssk
                } else {
                    ssk.decrypt_secret(&pw)
                        .map_err(|_| PgpError::InvalidPassphrase)?
                };
                unlocked_packets.push(Packet::SecretSubkey(dec));
            }
            other => unlocked_packets.push(other),
        }
    }
    Cert::from_packets(unlocked_packets.into_iter())
        .map_err(|e| anyhow!("cannot reassemble unlocked certificate: {e}"))
}

fn user_ids_of_cert(cert: &Cert, policy: &StandardPolicy<'_>) -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(valid) = cert.with_policy(policy, None) {
        if let Ok(primary) = valid.primary_userid() {
            let text = String::from_utf8_lossy(primary.userid().value()).into_owned();
            if !text.is_empty() {
                out.push(text);
            }
        }
        for uid in valid.userids() {
            let text = String::from_utf8_lossy(uid.userid().value()).into_owned();
            if !text.is_empty() && !out.contains(&text) {
                out.push(text);
            }
        }
    }
    if out.is_empty() {
        for uid in cert.userids() {
            let text = String::from_utf8_lossy(uid.userid().value()).into_owned();
            if !text.is_empty() && !out.contains(&text) {
                out.push(text);
            }
        }
    }
    out
}

fn primary_user_id_of_cert(cert: &Cert, policy: &StandardPolicy<'_>) -> Option<String> {
    user_ids_of_cert(cert, policy).into_iter().next()
}

fn signature_key_id(signature: &openpgp::packet::Signature) -> KeyID {
    signature
        .get_issuers()
        .first()
        .map(key_id_of)
        .unwrap_or_else(KeyID::wildcard)
}

fn key_id_of(handle: &KeyHandle) -> KeyID {
    match handle {
        KeyHandle::KeyID(key_id) => key_id.clone(),
        KeyHandle::Fingerprint(fingerprint) => {
            let bytes = fingerprint.as_bytes();
            let start = bytes.len().saturating_sub(8);
            KeyID::from_bytes(&bytes[start..])
        }
    }
}

/// Parse a certificate from armored or binary input.
fn parse_cert(data: &[u8]) -> Result<Cert> {
    match Cert::from_bytes(data) {
        Ok(cert) => Ok(cert),
        Err(first) => {
            let binary = dearmor_if_needed(data)?;
            if binary == data {
                bail!("cannot read the certificate: {first}");
            }
            Cert::from_bytes(&binary).map_err(|e| anyhow!("cannot read the certificate: {e}"))
        }
    }
}

/// Serialize the transferable secret key of `cert`.
fn serialize_transferable_secret_key(cert: &Cert) -> Result<Option<Vec<u8>>> {
    if !cert_has_secret(cert) {
        return Ok(None);
    }

    let mut buffer = Vec::new();
    let tsk = cert.clone().into_tsk();
    tsk.serialize(&mut buffer)
        .map_err(|e| anyhow!("cannot serialize the secret key: {e}"))?;
    Ok(Some(buffer))
}

/// ASCII armor the public part of `cert`.
fn armor_public_key(cert: &Cert) -> Result<String> {
    let mut public = Vec::new();
    cert.serialize(&mut public)
        .map_err(|e| anyhow!("cannot serialize the certificate: {e}"))?;

    let mut sink = Vec::new();
    {
        let mut writer = armor::Writer::new(&mut sink, armor::Kind::PublicKey)
            .map_err(|e| anyhow!("cannot create the armor writer: {e}"))?;
        writer
            .write_all(&public)
            .map_err(|e| anyhow!("cannot write the certificate: {e}"))?;
        writer
            .finalize()
            .map_err(|e| anyhow!("cannot finalize the armor: {e}"))?;
    }
    String::from_utf8(sink).map_err(|e| anyhow!("the armored certificate is not valid UTF-8: {e}"))
}

/// Strip the ASCII armor of `data`, if it carries any.
fn dearmor_if_needed(data: &[u8]) -> Result<Vec<u8>> {
    let is_armored = std::str::from_utf8(data)
        .map(|text| text.contains("-----BEGIN PGP"))
        .unwrap_or(false);
    if !is_armored {
        return Ok(data.to_vec());
    }

    let mut reader = armor::Reader::from_bytes(data, armor::ReaderMode::Tolerant(None));
    let mut payload = Vec::new();
    reader
        .read_to_end(&mut payload)
        .map_err(|e| anyhow!("cannot decode the armored payload: {e}"))?;
    Ok(payload)
}
