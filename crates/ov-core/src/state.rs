//! Shared runtime state of the Trap native engine.

use std::{
    path::PathBuf,
    sync::{atomic::Ordering, Arc},
};

use ov_cef::{OvConfig, SnapshotCollector};
use ov_pgp::Keyring;
use ov_tor::TorService;
use ov_xmr::XmrService;

use crate::surface::SurfaceRenderer;

/// Shared engine state coordinated across JNI entry points and background tasks.
pub struct EngineState {
    /// Root application private files directory.
    pub data_dir: PathBuf,
    /// Root application cache directory.
    pub cache_dir: PathBuf,
    /// Hardware-wrapped Sequoia OpenPGP key manager.
    pub keyring: Arc<Keyring>,
    /// Hardware-wrapped pure-Rust Monero light wallet & Tor swap manager.
    pub xmr: Arc<XmrService>,
    /// Embedded Arti Tor service + loopback SOCKS5 listener + isolation registry.
    pub tor: Arc<TorService>,
    /// Viewport tracker.
    pub surface: Arc<SurfaceRenderer>,
    /// URL/leak policy configuration.
    pub config: Arc<OvConfig>,
    /// Pending DOM actions map.
    pub pending: ov_cef::PendingActions,
    /// Bounded snapshot collector.
    pub snapshots: Arc<SnapshotCollector>,
}

impl EngineState {
    /// Fingerprints of certificates in the keyring that possess a wrapped secret key.
    pub fn own_fingerprints(&self) -> Vec<sequoia_openpgp::Fingerprint> {
        match self.keyring.certificates() {
            Ok(certificates) => certificates
                .into_iter()
                .filter(|certificate| certificate.has_secret)
                .map(|certificate| certificate.fingerprint)
                .collect(),
            Err(err) => {
                log::error!("cannot read the keyring: {err:#}");
                Vec::new()
            }
        }
    }

    /// Update `hidden_service_only` across both the SOCKS5 listener and URL policy.
    pub fn set_hidden_service_only(&self, enabled: bool) {
        self.tor.set_hidden_service_only(enabled);
        self.config
            .hidden_service_only
            .store(enabled, Ordering::Release);
    }

    /// Current `hidden_service_only` enforcement state.
    pub fn hidden_service_only(&self) -> bool {
        self.tor.hidden_service_only()
    }
}
