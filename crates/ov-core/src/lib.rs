//! Core coordinator and JNI entry points of Trap (`libonion_vault.so`).
//!
//! Coordinates the three pure-Rust subsystems without linking `libcef.so`:
//!   * `ov-tor`: embedded Arti Tor client, loopback SOCKS5 proxy, and per-service
//!     v3 `.onion` circuit isolation,
//!   * `ov-cef`: strict URL leak policy + zero-JS CDP DOM & text PGP classifier,
//!   * `ov-pgp`: hardware Keystore / AES-256-GCM wrapped Sequoia OpenPGP keyring.

pub mod bridge;
pub mod events;
pub mod native;
pub mod state;
pub mod surface;

use std::{
    io::Read,
    path::Path,
    sync::{atomic::AtomicBool, Arc, Mutex, OnceLock},
};

use anyhow::{anyhow, Context, Result};
use ov_cef::{OvConfig, PixelFormat};
#[cfg(target_os = "android")]
use ov_pgp::keystore::{BIOMETRIC_ALIAS, DEFAULT_ALIAS};
#[cfg(target_os = "android")]
use ov_pgp::SETTING_REQUIRE_BIOMETRIC;
use ov_pgp::{needs_passphrase, Keyring, KeyringStore};
use ov_tor::TorStatusSnapshot;
use sequoia_openpgp::{armor, parse::Parse, Cert, KeyHandle, Packet, PacketPile};

pub use bridge::OvBridge;
pub use state::EngineState;
pub use surface::SurfaceRenderer;

/// Active global engine state.
static ENGINE: OnceLock<Mutex<Option<Arc<EngineState>>>> = OnceLock::new();

/// Most recent initialization failure message (`""` when healthy).
static LAST_INIT_ERROR: OnceLock<Mutex<String>> = OnceLock::new();

/// Shared multi-threaded Tokio runtime hosting Arti and its SOCKS5 listener.
static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

fn engine_slot() -> &'static Mutex<Option<Arc<EngineState>>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

fn error_slot() -> &'static Mutex<String> {
    LAST_INIT_ERROR.get_or_init(|| Mutex::new(String::new()))
}

fn set_last_init_error(msg: impl Into<String>) {
    let mut guard = match error_slot().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    *guard = msg.into();
}

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("ov-runtime")
            .build()
            .expect("cannot create the Trap Tokio runtime")
    })
}

fn block_on_runtime<F, T>(future: F) -> T
where
    F: std::future::Future<Output = T> + Send,
    T: Send,
{
    let rt = runtime();
    if tokio::runtime::Handle::try_current().is_ok() {
        std::thread::scope(|s| {
            s.spawn(|| rt.block_on(future))
                .join()
                .expect("runtime helper thread panicked")
        })
    } else {
        rt.block_on(future)
    }
}

/// Active [`EngineState`] if initialized.
pub fn engine() -> Option<Arc<EngineState>> {
    let guard = match engine_slot().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.clone()
}

/// Handle to a running engine instance.
pub struct Engine {
    state: Arc<EngineState>,
}

impl Engine {
    /// Start (or build) an isolated engine rooted at `data_dir` and `cache_dir`.
    pub fn start_with_dirs(
        data_dir: &Path,
        cache_dir: &Path,
        hidden_service_only: bool,
    ) -> Result<Arc<Self>> {
        if data_dir.as_os_str().is_empty() {
            anyhow::bail!("dataDir must not be empty");
        }
        std::fs::create_dir_all(data_dir)
            .with_context(|| format!("cannot create dataDir {}", data_dir.display()))?;

        let effective_cache_base = if cache_dir.as_os_str().is_empty() {
            data_dir.join("cache")
        } else {
            cache_dir.to_path_buf()
        };
        std::fs::create_dir_all(&effective_cache_base).with_context(|| {
            format!("cannot create cacheDir {}", effective_cache_base.display())
        })?;

        let arti_state_dir = data_dir.join("arti_state");
        let mut arti_cache_dir = effective_cache_base.join("arti_cache");
        if arti_cache_dir == arti_state_dir {
            arti_cache_dir = data_dir.join("arti_cache_separate");
        }
        restrict_dir_permissions(&arti_state_dir)?;
        restrict_dir_permissions(&arti_cache_dir)?;

        // --- OpenPGP Keyring & Master Wrapping Key ---------------------------
        let pgp_dir = data_dir.join("pgp");
        restrict_dir_permissions(&pgp_dir)?;
        let store = KeyringStore::open(&pgp_dir.join("keyring.db"))
            .context("cannot open the keyring database")?;

        #[cfg(target_os = "android")]
        let master = {
            let persisted_biometric = store
                .get_setting(SETTING_REQUIRE_BIOMETRIC)
                .ok()
                .flatten()
                .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                .unwrap_or(false);
            let vm = crate::native::java_vm()
                .context("JavaVM is not available: cannot open Android Keystore")?;
            let alias = if persisted_biometric {
                BIOMETRIC_ALIAS
            } else {
                DEFAULT_ALIAS
            };
            ov_pgp::master_key_provider(vm, alias, persisted_biometric)
                .context("cannot obtain Android Keystore master key")?
        };

        #[cfg(not(target_os = "android"))]
        let master = ov_pgp::persistent_software_master_key(&pgp_dir.join("master.key"))
            .context("cannot open or create persistent software master key")?;

        log::info!("OpenPGP master key active: {}", master.describe());
        let keyring = Arc::new(Keyring::open(store, master));

        // --- Embedded Arti Tor Client + Loopback SOCKS5 ----------------------
        let tor_config = ov_tor::TorConfig {
            state_dir: arti_state_dir,
            cache_dir: arti_cache_dir,
            allow_clearnet: !hidden_service_only,
            ..Default::default()
        };
        let tor = block_on_runtime(ov_tor::TorService::start(tor_config))
            .context("cannot start embedded Arti Tor service")?;
        log::info!("Tor SOCKS5 listener bound on {}", tor.socks_endpoint());

        {
            let tor_sub = tor.clone();
            runtime().spawn(async move {
                let mut events = tor_sub.subscribe();
                loop {
                    match events.recv().await {
                        Ok(progress) => {
                            native::call_native(
                                "onTorProgress",
                                &[
                                    native::NativeArg::Int(i32::from(progress.percent)),
                                    native::NativeArg::String(progress.stage.clone()),
                                ],
                            );
                            if progress.ready {
                                break;
                            }
                        }
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(_) => break,
                    }
                }
            });
        }

        let config = Arc::new(OvConfig {
            proxy_endpoint: tor.socks_endpoint(),
            hidden_service_only: Arc::new(AtomicBool::new(hidden_service_only)),
            block_svg: false,
        });
        let surface = Arc::new(SurfaceRenderer::new(PixelFormat::Rgba8888));
        let pending = ov_cef::pending_actions();
        let snapshots = Arc::new(ov_cef::SnapshotCollector::new());

        let state = Arc::new(EngineState {
            data_dir: data_dir.to_path_buf(),
            cache_dir: effective_cache_base,
            keyring,
            tor,
            surface,
            config,
            pending,
            snapshots,
        });

        Ok(Arc::new(Self { state }))
    }

    /// Backwards-compatible helper using `files_dir` for state and `files_dir/cache` for cache.
    pub fn start(
        files_dir: &Path,
        hidden_service_only: bool,
        require_user_authentication: bool,
    ) -> Result<Arc<Self>> {
        let engine =
            Self::start_with_dirs(files_dir, &files_dir.join("cache"), hidden_service_only)?;
        if require_user_authentication {
            let _ = engine
                .state
                .keyring
                .set_require_biometric_flag(require_user_authentication);
        }
        let mut guard = match engine_slot().lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        *guard = Some(engine.state.clone());
        Ok(engine)
    }

    /// Shared engine state.
    pub fn state(&self) -> Arc<EngineState> {
        self.state.clone()
    }

    /// Stop the Tor listener and background tasks of this engine.
    pub fn shutdown(&self) {
        self.state.tor.shutdown();
    }
}

fn restrict_dir_permissions(path: &Path) -> Result<()> {
    std::fs::create_dir_all(path)
        .with_context(|| format!("cannot create directory {}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))
            .with_context(|| format!("cannot set 0700 permissions on {}", path.display()))?;
    }
    Ok(())
}

// ============================================================================
// Pure-Rust Core Operations Backing the 19 `OvNative` Entry Points
// ============================================================================

/// 1. Initialize (or update) the global Trap native engine (`nativeInit`).
pub fn init_engine(data_dir: &Path, cache_dir: &Path, hidden_service_only: bool) -> bool {
    if let Some(existing) = engine() {
        if existing.data_dir == data_dir {
            existing.set_hidden_service_only(hidden_service_only);
            set_last_init_error("");
            return true;
        }
        existing.tor.shutdown();
    }

    match Engine::start_with_dirs(data_dir, cache_dir, hidden_service_only) {
        Ok(started) => {
            let mut guard = match engine_slot().lock() {
                Ok(g) => g,
                Err(p) => p.into_inner(),
            };
            *guard = Some(started.state.clone());
            set_last_init_error("");
            log::info!("the engine is ready");
            true
        }
        Err(err) => {
            let msg = format!("{err:#}");
            log::error!("cannot start Trap native engine: {msg}");
            set_last_init_error(msg);
            false
        }
    }
}

/// 2. Last initialization error (`nativeLastInitError`), or `""` when healthy.
pub fn last_init_error() -> String {
    match error_slot().lock() {
        Ok(guard) => guard.clone(),
        Err(poisoned) => poisoned.into_inner().clone(),
    }
}

/// 3. Active SOCKS5 proxy URL (`"socks5://127.0.0.1:<port>"`), or `""` before init.
pub fn socks_proxy() -> String {
    engine().map(|s| s.tor.proxy_url()).unwrap_or_default()
}

/// 4. Live Tor status & isolation snapshot JSON (`nativeTorStatusJson`).
pub fn tor_status_json(current_url: &str) -> String {
    let snapshot = match engine() {
        Some(state) => state.tor.status_snapshot(current_url),
        None => TorStatusSnapshot {
            bootstrapped: false,
            bootstrap_percent: 0,
            bootstrap_summary: last_init_error_or("Engine not initialized"),
            socks_addr: String::new(),
            hidden_service_only: true,
            isolation_token: String::new(),
            isolated_group_count: 0,
        },
    };
    serde_json::to_string(&snapshot).unwrap_or_else(|_| {
        r#"{"bootstrapped":false,"bootstrapPercent":0,"bootstrapSummary":"serialization error","socksAddr":"","hiddenServiceOnly":true,"isolationToken":"","isolatedGroupCount":0}"#.to_string()
    })
}

fn last_init_error_or(default_msg: &str) -> String {
    let err = last_init_error();
    if err.is_empty() {
        default_msg.to_string()
    } else {
        err
    }
}

/// 5. Toggle hidden-service-only enforcement (`nativeSetHiddenServiceOnly`).
pub fn set_hidden_service_only(enabled: bool) {
    if let Some(state) = engine() {
        state.set_hidden_service_only(enabled);
    }
}

/// 6. Reset Tor circuit isolation (`nativeResetTorIsolation`).
pub fn reset_tor_isolation(target_url_or_empty: &str) -> i32 {
    match engine() {
        Some(state) => state.tor.reset_isolation(target_url_or_empty) as i32,
        None => 0,
    }
}

/// 7. Evaluate URL leak policy (`nativeCheckUrlPolicy`).
pub fn check_url_policy_json(url: &str, hidden_service_only: bool) -> String {
    let decision = ov_cef::check_url_policy(url, hidden_service_only);
    serde_json::to_string(&decision).unwrap_or_else(|_| {
        r#"{"allowed":false,"reason":"URL policy serialization failed","isOnion":false,"normalizedUrl":""}"#.to_string()
    })
}

/// 8. Classify CDP `DOM.getDocument({"depth":-1,"pierce":true})` JSON (`nativeClassifyCdpDom`).
pub fn classify_cdp_dom_json(cdp_dom_json: &str) -> String {
    let active = engine();
    match ov_cef::classify_cdp_dom_with_hints(cdp_dom_json, |kind, payload| {
        if kind == "pgp_textarea" || payload.trim().is_empty() {
            return (None, None);
        }
        let hint = active
            .as_ref()
            .and_then(|s| s.keyring.fingerprint_hint_of_armored(payload))
            .or_else(|| standalone_fingerprint_hint(payload));
        (hint, None)
    }) {
        Ok(nodes) => serde_json::to_string(&nodes).unwrap_or_else(|_| "[]".to_string()),
        Err(err) => {
            log::warn!("cannot parse CDP DOM JSON: {err}");
            "[]".to_string()
        }
    }
}

/// 9. Classify arbitrary selected/clipboard text (`nativeClassifyText`).
pub fn classify_text_json(text: &str) -> String {
    let active = engine();
    let classified = ov_cef::classify_text_with_hints(text, |_kind, armored| {
        let hint = active
            .as_ref()
            .and_then(|s| s.keyring.fingerprint_hint_of_armored(armored))
            .or_else(|| standalone_fingerprint_hint(armored));
        (hint, None)
    });
    serde_json::to_string(&classified).unwrap_or_else(|_| {
        r#"{"kind":"plain","actionLabel":"","armored":"","fingerprintHint":""}"#.to_string()
    })
}

/// 10. List stored OpenPGP certificates (`nativeListKeysJson`).
pub fn list_keys_json() -> String {
    let Some(state) = engine() else {
        return "[]".to_string();
    };
    match state.keyring.list_key_summaries() {
        Ok(keys) => serde_json::to_string(&keys).unwrap_or_else(|_| "[]".to_string()),
        Err(err) => {
            log::error!("cannot list OpenPGP keys: {err:#}");
            "[]".to_string()
        }
    }
}

/// 11. Generate a new Ed25519/Cv25519 key pair (`nativeGenerateKey`).
pub fn generate_key_json(user_id: &str, passphrase: &str) -> String {
    #[derive(serde::Serialize)]
    #[serde(rename_all = "camelCase")]
    struct KeyGenResponse {
        ok: bool,
        fingerprint: String,
        public_key_armored: String,
        error: String,
    }

    let resp = match engine() {
        None => KeyGenResponse {
            ok: false,
            fingerprint: String::new(),
            public_key_armored: String::new(),
            error: "Engine is not initialized".to_string(),
        },
        Some(state) => {
            let pw = (!passphrase.is_empty()).then_some(passphrase.as_bytes());
            match state.keyring.generate_keypair_with_passphrase(user_id, pw) {
                Ok(fp) => match state.keyring.export_armored_public_key(&fp) {
                    Ok(armored) => KeyGenResponse {
                        ok: true,
                        fingerprint: fp.to_hex(),
                        public_key_armored: armored,
                        error: String::new(),
                    },
                    Err(err) => KeyGenResponse {
                        ok: false,
                        fingerprint: fp.to_hex(),
                        public_key_armored: String::new(),
                        error: err.to_string(),
                    },
                },
                Err(err) => KeyGenResponse {
                    ok: false,
                    fingerprint: String::new(),
                    public_key_armored: String::new(),
                    error: err.to_string(),
                },
            }
        }
    };

    serde_json::to_string(&resp).unwrap_or_else(|_| {
        r#"{"ok":false,"fingerprint":"","publicKeyArmored":"","error":"serialization error"}"#
            .to_string()
    })
}

/// 12. Import an armored public or secret OpenPGP key (`nativeImportKey`).
pub fn import_key_json(armored: &str, passphrase: &str) -> String {
    #[derive(serde::Serialize)]
    #[serde(rename_all = "camelCase")]
    struct KeyImportResponse {
        ok: bool,
        fingerprint: String,
        user_id: String,
        has_secret: bool,
        error: String,
    }

    let resp = match engine() {
        None => KeyImportResponse {
            ok: false,
            fingerprint: String::new(),
            user_id: String::new(),
            has_secret: false,
            error: "Engine is not initialized".to_string(),
        },
        Some(state) => {
            let pw = (!passphrase.is_empty()).then_some(passphrase.as_bytes());
            match state
                .keyring
                .import_cert_with_passphrase(armored.as_bytes(), pw)
            {
                Ok(info) => KeyImportResponse {
                    ok: true,
                    fingerprint: info.fingerprint.to_hex(),
                    user_id: info.user_id,
                    has_secret: info.has_secret,
                    error: String::new(),
                },
                Err(err) => KeyImportResponse {
                    ok: false,
                    fingerprint: String::new(),
                    user_id: String::new(),
                    has_secret: false,
                    error: err.to_string(),
                },
            }
        }
    };

    serde_json::to_string(&resp).unwrap_or_else(|_| {
        r#"{"ok":false,"fingerprint":"","userId":"","hasSecret":false,"error":"serialization error"}"#.to_string()
    })
}

/// 13. Export an ASCII-armored public key (`nativeExportPublicKey`).
pub fn export_public_key(fingerprint_or_empty: &str) -> String {
    let Some(state) = engine() else {
        return String::new();
    };
    state
        .keyring
        .export_public_key_by_query(fingerprint_or_empty)
        .unwrap_or_default()
}

/// 14. Delete a certificate and its wrapped secret key (`nativeDeleteKey`).
pub fn delete_key(fingerprint: &str) -> bool {
    let Some(state) = engine() else {
        return false;
    };
    let Ok(fp) = ov_pgp::parse_fingerprint(fingerprint) else {
        return false;
    };
    state.keyring.delete_certificate(&fp).unwrap_or(false)
}

/// 15. Encrypt (and optionally sign) plaintext for recipient(s) (`nativeEncrypt`).
pub fn encrypt_json(
    plaintext: &str,
    recipient_fingerprint: &str,
    signer_fingerprint_or_empty: &str,
    passphrase: &str,
) -> String {
    #[derive(serde::Serialize)]
    #[serde(rename_all = "camelCase")]
    struct EncryptResponse {
        ok: bool,
        ciphertext: String,
        signed: bool,
        error: String,
    }

    let run = || -> Result<ov_pgp::EncryptedOutcome> {
        let state = engine().ok_or_else(|| anyhow!("Engine is not initialized"))?;
        let recipients: Vec<sequoia_openpgp::Fingerprint> = recipient_fingerprint
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(ov_pgp::parse_fingerprint)
            .collect::<Result<Vec<_>>>()?;

        if recipients.is_empty() {
            anyhow::bail!("Recipient fingerprint is required");
        }

        let explicit_signer = if signer_fingerprint_or_empty.trim().is_empty() {
            None
        } else {
            Some(ov_pgp::parse_fingerprint(signer_fingerprint_or_empty)?)
        };
        let pw = (!passphrase.is_empty()).then_some(passphrase.as_bytes());

        // Never auto-sign when `signer_fingerprint_or_empty` is empty: the user
        // explicitly selected Unsigned, so secret keys / Android Keystore must
        // not be unwrapped or attached.
        state.keyring.encrypt_with_options(
            plaintext,
            &recipients,
            explicit_signer.as_ref(),
            pw,
            false,
        )
    };

    let resp = match run() {
        Ok(outcome) => EncryptResponse {
            ok: true,
            ciphertext: outcome.ciphertext,
            signed: outcome.signed,
            error: String::new(),
        },
        Err(err) => EncryptResponse {
            ok: false,
            ciphertext: String::new(),
            signed: false,
            error: err.to_string(),
        },
    };

    serde_json::to_string(&resp).unwrap_or_else(|_| {
        r#"{"ok":false,"ciphertext":"","signed":false,"error":"serialization error"}"#.to_string()
    })
}

/// 16. Decrypt (or verify cleartext-signed) armored OpenPGP message (`nativeDecrypt`).
pub fn decrypt_json(armored_ciphertext: &str, passphrase: &str) -> String {
    #[derive(serde::Serialize)]
    #[serde(rename_all = "camelCase")]
    struct DecryptResponseJson {
        ok: bool,
        plaintext: String,
        signature_status: String,
        signer_fingerprint: String,
        signer_user_id: String,
        needs_passphrase: bool,
        error: String,
    }

    let resp = match engine() {
        None => DecryptResponseJson {
            ok: false,
            plaintext: String::new(),
            signature_status: "unsigned".to_string(),
            signer_fingerprint: String::new(),
            signer_user_id: String::new(),
            needs_passphrase: false,
            error: "Engine is not initialized".to_string(),
        },
        Some(state) => {
            let pw = (!passphrase.is_empty()).then_some(passphrase.as_bytes());
            match state
                .keyring
                .decrypt_armored_with_passphrase(armored_ciphertext, pw)
            {
                Ok(res) => DecryptResponseJson {
                    ok: true,
                    plaintext: res.plaintext.clone(),
                    signature_status: res.signature_status().as_str().to_string(),
                    signer_fingerprint: res.signer_fingerprint_hex(),
                    signer_user_id: res.signer_user_id(),
                    needs_passphrase: false,
                    error: String::new(),
                },
                Err(err) => {
                    let requires_pw = needs_passphrase(&err);
                    DecryptResponseJson {
                        ok: false,
                        plaintext: String::new(),
                        signature_status: "unsigned".to_string(),
                        signer_fingerprint: String::new(),
                        signer_user_id: String::new(),
                        needs_passphrase: requires_pw,
                        error: err.to_string(),
                    }
                }
            }
        }
    };

    serde_json::to_string(&resp).unwrap_or_else(|_| {
        r#"{"ok":false,"plaintext":"","signatureStatus":"unsigned","signerFingerprint":"","signerUserId":"","needsPassphrase":false,"error":"serialization error"}"#.to_string()
    })
}

/// 17. Enable/disable hardware Keystore user-authentication requirement (`nativeSetRequireBiometric`).
pub fn set_require_biometric(required: bool) -> bool {
    let Some(state) = engine() else {
        return false;
    };

    #[cfg(target_os = "android")]
    {
        let Some(vm) = crate::native::java_vm() else {
            log::error!("cannot toggle biometric Keystore policy: JavaVM unavailable");
            return false;
        };
        let target_alias = if required {
            BIOMETRIC_ALIAS
        } else {
            DEFAULT_ALIAS
        };
        let new_master = match ov_pgp::master_key_provider(vm, target_alias, required) {
            Ok(provider) => provider,
            Err(err) => {
                log::warn!(
                    "Android Keystore refused biometric={required} ({target_alias}): {err:#}"
                );
                return false;
            }
        };
        if let Err(err) = state.keyring.rotate_master_key(new_master, required) {
            log::error!("failed to re-wrap secret keys for biometric={required}: {err:#}");
            return false;
        }
        true
    }

    #[cfg(not(target_os = "android"))]
    {
        match state.keyring.set_require_biometric_flag(required) {
            Ok(()) => true,
            Err(err) => {
                log::error!("cannot persist require_biometric flag: {err:#}");
                false
            }
        }
    }
}

/// 18. Read current hardware Keystore user-authentication setting (`nativeGetRequireBiometric`).
pub fn get_require_biometric() -> bool {
    engine()
        .and_then(|s| s.keyring.require_biometric().ok())
        .unwrap_or(false)
}

/// 19. Stop the embedded Tor client and clear global engine state (`nativeShutdown`).
pub fn shutdown_engine() {
    let removed = {
        let mut guard = match engine_slot().lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        guard.take()
    };
    if let Some(state) = removed {
        state.tor.shutdown();
    }
}

/// Stateless OpenPGP packet fingerprint/KeyID extractor used when `classify_cdp_dom_json`
/// or `classify_text_json` is invoked before `nativeInit` or for foreign keys.
fn standalone_fingerprint_hint(armored: &str) -> Option<String> {
    let trimmed = armored.trim();
    if trimmed.is_empty() {
        return None;
    }
    if let Ok(cert) = Cert::from_bytes(trimmed.as_bytes()) {
        return Some(cert.fingerprint().to_hex());
    }
    let mut reader =
        armor::Reader::from_bytes(trimmed.as_bytes(), armor::ReaderMode::Tolerant(None));
    let mut binary = Vec::new();
    if reader.read_to_end(&mut binary).is_err() {
        return None;
    }
    if let Ok(cert) = Cert::from_bytes(&binary) {
        return Some(cert.fingerprint().to_hex());
    }
    let pile = PacketPile::from_bytes(&binary).ok()?;
    for packet in pile.descendants() {
        match packet {
            Packet::PKESK(pkesk) if !pkesk.recipient().is_wildcard() => {
                return Some(pkesk.recipient().to_hex());
            }
            Packet::OnePassSig(ops) if !ops.issuer().is_wildcard() => {
                return Some(ops.issuer().to_hex());
            }
            Packet::Signature(sig) => {
                for handle in sig.get_issuers() {
                    match handle {
                        KeyHandle::Fingerprint(fp) => return Some(fp.to_hex()),
                        KeyHandle::KeyID(kid) if !kid.is_wildcard() => return Some(kid.to_hex()),
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }
    None
}

// ============================================================================
// Android JNI Entry Points (`Java_dev_onionvault_OvNative_*`)
// ============================================================================

#[cfg(target_os = "android")]
#[allow(non_snake_case)]
pub mod jni_api {
    use super::*;
    use jni::objects::{JObject, JString};
    use jni::sys::{jboolean, jint, jstring};
    use jni::JNIEnv;
    use std::path::PathBuf;

    fn init_android_logger_once() {
        use std::sync::Once;
        static LOG_ONCE: Once = Once::new();
        LOG_ONCE.call_once(|| {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("OnionVault"),
            );
        });
    }

    fn read_jstring(env: &mut JNIEnv, input: jstring) -> String {
        if input.is_null() {
            return String::new();
        }
        // SAFETY: `input` is a valid non-null local reference in the caller's JNI frame;
        // we borrow it without taking ownership so ART releases the caller's local ref normally.
        let jstr = unsafe { JString::from_raw(input) };
        let out = env
            .get_string(&jstr)
            .ok()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| {
                let _ = env.exception_clear();
                String::new()
            });
        let _ = jstr.into_raw();
        out
    }

    fn into_jstring(env: JNIEnv, text: &str) -> jstring {
        let cleaned = if text.contains('\0') {
            std::borrow::Cow::Owned(text.replace('\0', ""))
        } else {
            std::borrow::Cow::Borrowed(text)
        };
        match env.new_string(cleaned.as_ref()) {
            Ok(s) => s.into_raw(),
            Err(_) => {
                let _ = env.exception_clear();
                env.new_string("")
                    .map(|s| s.into_raw())
                    .unwrap_or(std::ptr::null_mut())
            }
        }
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeInit(
        mut env: JNIEnv,
        _this: JObject,
        data_dir: jstring,
        cache_dir: jstring,
        hidden_service_only: jboolean,
    ) -> jboolean {
        init_android_logger_once();
        if let Ok(vm) = env.get_java_vm() {
            native::set_java_vm(vm);
        }
        native::cache_bridge_class(&mut env);

        let data_path = PathBuf::from(read_jstring(&mut env, data_dir));
        let cache_path = PathBuf::from(read_jstring(&mut env, cache_dir));
        u8::from(init_engine(
            &data_path,
            &cache_path,
            hidden_service_only != 0,
        ))
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeLastInitError(
        env: JNIEnv,
        _this: JObject,
    ) -> jstring {
        let msg = last_init_error();
        into_jstring(env, &msg)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeSocksProxy(
        env: JNIEnv,
        _this: JObject,
    ) -> jstring {
        let proxy = socks_proxy();
        into_jstring(env, &proxy)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeTorStatusJson(
        mut env: JNIEnv,
        _this: JObject,
        current_url: jstring,
    ) -> jstring {
        let url = read_jstring(&mut env, current_url);
        let json = tor_status_json(&url);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeSetHiddenServiceOnly(
        _env: JNIEnv,
        _this: JObject,
        enabled: jboolean,
    ) {
        set_hidden_service_only(enabled != 0);
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeResetTorIsolation(
        mut env: JNIEnv,
        _this: JObject,
        target_url_or_empty: jstring,
    ) -> jint {
        let target = read_jstring(&mut env, target_url_or_empty);
        reset_tor_isolation(&target)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeCheckUrlPolicy(
        mut env: JNIEnv,
        _this: JObject,
        url: jstring,
        hidden_service_only: jboolean,
    ) -> jstring {
        let raw_url = read_jstring(&mut env, url);
        let json = check_url_policy_json(&raw_url, hidden_service_only != 0);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeClassifyCdpDom(
        mut env: JNIEnv,
        _this: JObject,
        cdp_dom_json: jstring,
    ) -> jstring {
        let raw_dom = read_jstring(&mut env, cdp_dom_json);
        let json = classify_cdp_dom_json(&raw_dom);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeClassifyText(
        mut env: JNIEnv,
        _this: JObject,
        text: jstring,
    ) -> jstring {
        let raw_text = read_jstring(&mut env, text);
        let json = classify_text_json(&raw_text);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeListKeysJson(
        env: JNIEnv,
        _this: JObject,
    ) -> jstring {
        let json = list_keys_json();
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeGenerateKey(
        mut env: JNIEnv,
        _this: JObject,
        user_id: jstring,
        passphrase: jstring,
    ) -> jstring {
        let uid = read_jstring(&mut env, user_id);
        let pw = read_jstring(&mut env, passphrase);
        let json = generate_key_json(&uid, &pw);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeImportKey(
        mut env: JNIEnv,
        _this: JObject,
        armored: jstring,
        passphrase: jstring,
    ) -> jstring {
        let raw_armored = read_jstring(&mut env, armored);
        let pw = read_jstring(&mut env, passphrase);
        let json = import_key_json(&raw_armored, &pw);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeExportPublicKey(
        mut env: JNIEnv,
        _this: JObject,
        fingerprint_or_empty: jstring,
    ) -> jstring {
        let fp = read_jstring(&mut env, fingerprint_or_empty);
        let armored = export_public_key(&fp);
        into_jstring(env, &armored)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeDeleteKey(
        mut env: JNIEnv,
        _this: JObject,
        fingerprint: jstring,
    ) -> jboolean {
        let fp = read_jstring(&mut env, fingerprint);
        u8::from(delete_key(&fp))
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeEncrypt(
        mut env: JNIEnv,
        _this: JObject,
        plaintext: jstring,
        recipient_fingerprint: jstring,
        signer_fingerprint_or_empty: jstring,
        passphrase: jstring,
    ) -> jstring {
        let pt = read_jstring(&mut env, plaintext);
        let rcpt = read_jstring(&mut env, recipient_fingerprint);
        let signer = read_jstring(&mut env, signer_fingerprint_or_empty);
        let pw = read_jstring(&mut env, passphrase);
        let json = encrypt_json(&pt, &rcpt, &signer, &pw);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeDecrypt(
        mut env: JNIEnv,
        _this: JObject,
        armored_ciphertext: jstring,
        passphrase: jstring,
    ) -> jstring {
        let ct = read_jstring(&mut env, armored_ciphertext);
        let pw = read_jstring(&mut env, passphrase);
        let json = decrypt_json(&ct, &pw);
        into_jstring(env, &json)
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeSetRequireBiometric(
        _env: JNIEnv,
        _this: JObject,
        required: jboolean,
    ) -> jboolean {
        u8::from(set_require_biometric(required != 0))
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeGetRequireBiometric(
        _env: JNIEnv,
        _this: JObject,
    ) -> jboolean {
        u8::from(get_require_biometric())
    }

    pub extern "system" fn Java_dev_onionvault_OvNative_nativeShutdown(
        _env: JNIEnv,
        _this: JObject,
    ) {
        shutdown_engine();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_ONION: &str =
        "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/inbox";

    #[test]
    fn full_engine_json_contracts_round_trip_end_to_end() {
        let base = std::env::temp_dir().join(format!("ov-core-e2e-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        let data_dir = base.join("files");
        let cache_dir = base.join("cache");

        // Pre-init policy & uninitialized status checks:
        let policy: serde_json::Value =
            serde_json::from_str(&check_url_policy_json(SAMPLE_ONION, true)).unwrap();
        assert_eq!(policy["allowed"], true);
        assert_eq!(policy["isOnion"], true);
        assert_eq!(policy["normalizedUrl"], SAMPLE_ONION);

        let blocked_policy: serde_json::Value =
            serde_json::from_str(&check_url_policy_json("https://example.com", true)).unwrap();
        assert_eq!(blocked_policy["allowed"], false);

        // Initialize engine:
        assert!(init_engine(&data_dir, &cache_dir, true));
        assert_eq!(last_init_error(), "");
        assert!(socks_proxy().starts_with("socks5://127.0.0.1:"));

        // Live Tor status JSON schema check:
        let tor_status: serde_json::Value =
            serde_json::from_str(&tor_status_json(SAMPLE_ONION)).unwrap();
        assert!(tor_status["bootstrapped"].is_boolean());
        assert!(tor_status["bootstrapPercent"].is_u64());
        assert!(tor_status["bootstrapSummary"].is_string());
        assert!(tor_status["socksAddr"]
            .as_str()
            .unwrap()
            .starts_with("127.0.0.1:"));
        assert_eq!(tor_status["hiddenServiceOnly"], true);
        assert_eq!(tor_status["isolationToken"], "iso-2gzyxa5ihm7nsggf");
        assert_eq!(tor_status["isolatedGroupCount"], 0);

        set_hidden_service_only(false);
        let tor_status_clearnet: serde_json::Value =
            serde_json::from_str(&tor_status_json("https://example.com")).unwrap();
        assert_eq!(tor_status_clearnet["hiddenServiceOnly"], false);
        assert_eq!(tor_status_clearnet["isolationToken"], "");
        set_hidden_service_only(true);

        // Key generation (unlocked + passphrase-locked) & listing:
        let alice_gen: serde_json::Value =
            serde_json::from_str(&generate_key_json("Alice <alice@onion.invalid>", "")).unwrap();
        assert_eq!(alice_gen["ok"], true);
        let alice_fp = alice_gen["fingerprint"].as_str().unwrap().to_string();
        let alice_pub = alice_gen["publicKeyArmored"].as_str().unwrap().to_string();
        assert_eq!(alice_fp.len(), 40);
        assert!(alice_pub.starts_with("-----BEGIN PGP PUBLIC KEY BLOCK-----"));

        let bob_gen: serde_json::Value = serde_json::from_str(&generate_key_json(
            "Bob <bob@onion.invalid>",
            "bob-secret-pw",
        ))
        .unwrap();
        assert_eq!(bob_gen["ok"], true);
        let bob_fp = bob_gen["fingerprint"].as_str().unwrap().to_string();

        let keys: serde_json::Value = serde_json::from_str(&list_keys_json()).unwrap();
        let arr = keys.as_array().unwrap();
        assert_eq!(arr.len(), 2);
        assert_eq!(arr[0]["hasSecret"], true);
        assert_eq!(arr[0]["canSign"], true);
        assert_eq!(arr[0]["canEncrypt"], true);
        assert_eq!(arr[0]["isOwnKey"], true);

        // Unsigned encryption (`signer_fingerprint_or_empty = ""`) must strictly
        // produce an unsigned ciphertext without touching unlocked `alice_fp`:
        let enc_unsigned: serde_json::Value =
            serde_json::from_str(&encrypt_json("Anonymous drop", &bob_fp, "", "")).unwrap();
        assert_eq!(enc_unsigned["ok"], true);
        assert_eq!(enc_unsigned["signed"], false);
        let ct_unsigned = enc_unsigned["ciphertext"].as_str().unwrap().to_string();
        let dec_unsigned: serde_json::Value =
            serde_json::from_str(&decrypt_json(&ct_unsigned, "bob-secret-pw")).unwrap();
        assert_eq!(dec_unsigned["ok"], true);
        assert_eq!(dec_unsigned["plaintext"], "Anonymous drop");
        assert_eq!(dec_unsigned["signatureStatus"], "unsigned");
        assert_eq!(dec_unsigned["signerFingerprint"], "");
        assert_eq!(dec_unsigned["signerUserId"], "");

        // Encrypt from Alice -> Bob (signed) and decrypt with passphrase negotiation:
        let enc: serde_json::Value =
            serde_json::from_str(&encrypt_json("Meet at midnight", &bob_fp, &alice_fp, ""))
                .unwrap();
        assert_eq!(enc["ok"], true);
        assert_eq!(enc["signed"], true);
        let ct = enc["ciphertext"].as_str().unwrap().to_string();

        let dec_locked: serde_json::Value = serde_json::from_str(&decrypt_json(&ct, "")).unwrap();
        assert_eq!(dec_locked["ok"], false);
        assert_eq!(dec_locked["needsPassphrase"], true);

        let dec_ok: serde_json::Value =
            serde_json::from_str(&decrypt_json(&ct, "bob-secret-pw")).unwrap();
        assert_eq!(dec_ok["ok"], true);
        assert_eq!(dec_ok["plaintext"], "Meet at midnight");
        assert_eq!(dec_ok["signatureStatus"], "good");
        assert_eq!(dec_ok["signerFingerprint"], alice_fp);
        assert_eq!(dec_ok["signerUserId"], "Alice <alice@onion.invalid>");
        assert_eq!(dec_ok["needsPassphrase"], false);

        // CDP DOM & text classification populate cryptographic `fingerprintHint`:
        let text_cls: serde_json::Value = serde_json::from_str(&classify_text_json(&ct)).unwrap();
        assert_eq!(text_cls["kind"], "pgp_message");
        assert_eq!(text_cls["actionLabel"], "Decrypt Block");
        assert_eq!(text_cls["fingerprintHint"], bob_fp);

        let cdp_doc = serde_json::json!({
            "root": {
                "nodeId": 1,
                "backendNodeId": 1,
                "nodeType": 9,
                "nodeName": "#document",
                "children": [{
                    "nodeId": 5,
                    "backendNodeId": 50,
                    "nodeType": 1,
                    "nodeName": "PRE",
                    "localName": "pre",
                    "children": [{
                        "nodeId": 6,
                        "nodeType": 3,
                        "nodeName": "#text",
                        "nodeValue": alice_pub
                    }]
                }]
            }
        });
        let dom_cls: serde_json::Value =
            serde_json::from_str(&classify_cdp_dom_json(&cdp_doc.to_string())).unwrap();
        let dom_arr = dom_cls.as_array().unwrap();
        assert_eq!(dom_arr.len(), 1);
        assert_eq!(dom_arr[0]["kind"], "pgp_public_key");
        assert_eq!(dom_arr[0]["fingerprintHint"], alice_fp);

        // Biometric toggle, export, delete, reset isolation, and shutdown:
        assert!(!get_require_biometric());
        assert!(set_require_biometric(true));
        assert!(get_require_biometric());
        assert!(!export_public_key("").is_empty());
        assert!(delete_key(&bob_fp));
        assert!(!delete_key(&bob_fp));
        assert_eq!(reset_tor_isolation(""), 0);

        shutdown_engine();
        assert_eq!(socks_proxy(), "");
        let _ = std::fs::remove_dir_all(&base);
    }
}
