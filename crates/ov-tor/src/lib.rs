//! Embedded Tor transport for Trap.
//!
//! The crate owns a complete Arti client inside the application process:
//!
//!   * bootstraps against the Tor network using the application sandbox for
//!     state and cache,
//!   * exposes a loopback SOCKS5 listener on `127.0.0.1:<ephemeral>`,
//!   * places every v3 `.onion` service on its own isolated circuit group.

pub mod bootstrap;
pub mod config;
pub mod isolation;
pub mod socks5;

use std::{net::SocketAddr, sync::Arc, time::Duration};

use anyhow::Context;
use arti_client::TorClient;
use tokio::{
    sync::{broadcast, watch},
    task::JoinHandle,
};
use tor_rtcompat::PreferredRuntime;

pub use bootstrap::{BootstrapMonitor, BootstrapProgress};
pub use config::TorConfig;
pub use isolation::{apex_domain, extract_host, is_valid_v3_onion_host, IsolationRegistry};
pub use socks5::{Socks5Decision, Socks5Error, Socks5Request, Socks5Server};

/// Number of buffered bootstrap events.
const EVENT_BUFFER: usize = 32;

/// Complete telemetry snapshot serialized by `OvNative.nativeTorStatusJson`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TorStatusSnapshot {
    /// `true` once Arti reports `ready_for_traffic()`.
    pub bootstrapped: bool,
    /// Bootstrap completion percentage (`0..=100`).
    pub bootstrap_percent: u8,
    /// Live human-readable Arti bootstrap stage or blockage summary.
    pub bootstrap_summary: String,
    /// Loopback SOCKS5 `host:port` endpoint (`127.0.0.1:<port>`).
    pub socks_addr: String,
    /// `true` when clearnet connections are refused at the SOCKS5 listener.
    pub hidden_service_only: bool,
    /// Circuit isolation token for `current_url` (`"iso-<16char_apex>"` or `""`).
    pub isolation_token: String,
    /// Number of currently allocated isolated `.onion` circuit groups.
    pub isolated_group_count: usize,
}

/// Install the `ring` cryptography provider of `rustls` (idempotent).
pub fn install_crypto_provider() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        if rustls::crypto::CryptoProvider::get_default().is_none() {
            if let Err(err) = rustls::crypto::ring::default_provider().install_default() {
                log::error!("cannot install the rustls cryptography provider: {err:?}");
            } else {
                log::info!("the rustls cryptography provider is installed");
            }
        }
    });
}

/// The embedded Tor service.
pub struct TorService {
    client: Arc<TorClient<PreferredRuntime>>,
    isolation: Arc<IsolationRegistry>,
    socks: Arc<Socks5Server>,
    events: broadcast::Sender<BootstrapProgress>,
    shutdown_tx: watch::Sender<bool>,
    bootstrap_task: JoinHandle<()>,
    monitor_task: JoinHandle<()>,
    config: TorConfig,
}

impl TorService {
    /// Bootstrap the client in the background and start the loopback SOCKS5 listener.
    pub async fn start(config: TorConfig) -> anyhow::Result<Arc<Self>> {
        install_crypto_provider();
        config.validate()?;

        let tor_config = config.build_tor_config()?;
        let runtime =
            tor_rtcompat::PreferredRuntime::current().context("cannot obtain the Tor runtime")?;
        let client = TorClient::with_runtime(runtime)
            .config(tor_config)
            .bootstrap_behavior(arti_client::BootstrapBehavior::Manual)
            .create_unbootstrapped_async()
            .await
            .context("cannot create the Arti Tor client")?;

        let (events, _) = broadcast::channel(EVENT_BUFFER);
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let monitor = BootstrapMonitor::new(client.clone(), events.clone());

        let bootstrap_task = {
            let client = client.clone();
            let mut stop_rx = shutdown_rx.clone();
            tokio::spawn(async move {
                tokio::select! {
                    _ = stop_rx.changed() => {}
                    res = client.bootstrap() => {
                        match res {
                            Ok(()) => log::info!("Tor client bootstrapped"),
                            Err(err) => log::error!("Tor bootstrap failed: {err}"),
                        }
                    }
                }
            })
        };

        let monitor_task = tokio::spawn(async move {
            monitor
                .run_until_shutdown(Duration::from_millis(500), shutdown_rx)
                .await;
        });

        let isolation = Arc::new(IsolationRegistry::new(
            client.clone(),
            config.isolate_per_apex,
        ));

        let socks = Socks5Server::start(
            config.socks_bind,
            isolation.clone(),
            config.allow_clearnet,
            config.connect_timeout,
        )
        .await?;

        log::info!("SOCKS5 listener bound to {}", socks.local_addr());

        Ok(Arc::new(Self {
            client,
            isolation,
            socks,
            events,
            shutdown_tx,
            bootstrap_task,
            monitor_task,
            config,
        }))
    }

    /// Loopback `127.0.0.1:<port>` address of the SOCKS5 listener.
    pub fn socks_endpoint(&self) -> SocketAddr {
        self.socks.local_addr()
    }

    /// Change whether clearnet destinations are permitted (`false` = hidden-service-only).
    pub fn set_allow_clearnet(&self, allow: bool) {
        self.socks.set_allow_clearnet(allow);
    }

    /// Enable or disable strict hidden-service-only mode at runtime.
    pub fn set_hidden_service_only(&self, enabled: bool) {
        self.socks.set_allow_clearnet(!enabled);
    }

    /// `true` when clearnet traffic is blocked at the SOCKS5 listener.
    pub fn hidden_service_only(&self) -> bool {
        !self.socks.allow_clearnet()
    }

    /// `socks5://127.0.0.1:<port>` URL for Chromium's proxy configuration.
    pub fn proxy_url(&self) -> String {
        format!("socks5://{}", self.socks_endpoint())
    }

    /// Subscribe to live bootstrap progress updates.
    pub fn subscribe(&self) -> broadcast::Receiver<BootstrapProgress> {
        self.events.subscribe()
    }

    /// Current live bootstrap progress.
    pub fn bootstrap_progress(&self) -> BootstrapProgress {
        BootstrapMonitor::new(self.client.clone(), self.events.clone()).current()
    }

    /// Snapshot of all live Tor, SOCKS5, and circuit-isolation telemetry for `current_url`.
    pub fn status_snapshot(&self, current_url: &str) -> TorStatusSnapshot {
        let progress = self.bootstrap_progress();
        TorStatusSnapshot {
            bootstrapped: progress.ready,
            bootstrap_percent: progress.percent,
            bootstrap_summary: progress.stage,
            socks_addr: self.socks_endpoint().to_string(),
            hidden_service_only: self.hidden_service_only(),
            isolation_token: self.isolation.isolation_token_for_url(current_url),
            isolated_group_count: self.isolation.group_count_sync(),
        }
    }

    /// Evict circuit isolation group(s) (`""` resets all `.onion` circuits).
    pub fn reset_isolation(&self, target_url_or_empty: &str) -> usize {
        self.isolation.reset_isolation(target_url_or_empty)
    }

    /// `true` when Arti reports `ready_for_traffic()`.
    pub fn is_ready(&self) -> bool {
        self.client.bootstrap_status().ready_for_traffic()
    }

    /// Main unisolated Arti client.
    pub fn client(&self) -> Arc<TorClient<PreferredRuntime>> {
        self.client.clone()
    }

    /// Circuit isolation registry.
    pub fn isolation(&self) -> Arc<IsolationRegistry> {
        self.isolation.clone()
    }

    /// Configuration this service was started with.
    pub fn config(&self) -> &TorConfig {
        &self.config
    }

    /// Stop the SOCKS5 listener, clear isolated circuit handles, and cancel background tasks.
    pub fn shutdown(&self) {
        let _ = self.shutdown_tx.send(true);
        self.socks.shutdown();
        self.bootstrap_task.abort();
        self.monitor_task.abort();
        let _ = self.isolation.reset_isolation("");
    }
}

impl Drop for TorService {
    fn drop(&mut self) {
        let _ = self.shutdown_tx.send(true);
        self.socks.shutdown();
        self.bootstrap_task.abort();
        self.monitor_task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn the_listener_binds_to_loopback_only() {
        install_crypto_provider();
        let config = TorConfig {
            state_dir: std::env::temp_dir().join("ov-tor-test-state"),
            cache_dir: std::env::temp_dir().join("ov-tor-test-cache"),
            ..TorConfig::default()
        };
        config.validate().unwrap();
        assert!(config.socks_bind.ip().is_loopback());
    }

    #[test]
    fn a_public_bind_address_is_rejected() {
        let config = TorConfig {
            socks_bind: "0.0.0.0:9050".parse().unwrap(),
            ..TorConfig::default()
        };
        assert!(config.validate().is_err());
    }
}
