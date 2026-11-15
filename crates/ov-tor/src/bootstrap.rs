//! Bootstrap progress reporting.
//!
//! Arti exposes live bootstrap state via [`TorClient::bootstrap_status`] and
//! [`TorClient::bootstrap_events`].

use std::{sync::Arc, time::Duration};

use arti_client::{status::BootstrapStatus, TorClient};
use futures_util::StreamExt;
use tokio::sync::{broadcast, watch};
use tor_rtcompat::PreferredRuntime;

/// Progress of the Tor bootstrap.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct BootstrapProgress {
    /// Completion in percent, `0..=100`.
    pub percent: u8,
    /// Human-readable live stage or blockage description from Arti.
    pub stage: String,
    /// `true` once the client can carry traffic.
    pub ready: bool,
}

/// Convert an Arti [`BootstrapStatus`] into [`BootstrapProgress`].
pub fn progress_from_status(status: &BootstrapStatus) -> BootstrapProgress {
    let fraction = status.as_frac().clamp(0.0, 1.0);
    let ready = status.ready_for_traffic();
    let percent = if ready {
        100
    } else {
        (fraction * 100.0).round().clamp(0.0, 99.0) as u8
    };

    let stage = if let Some(blockage) = status.blocked() {
        format!("blocked: {}", blockage.message())
    } else if ready {
        "Connected to Tor network".to_string()
    } else {
        let rendered = status.to_string();
        if rendered.trim().is_empty() {
            "Connecting to the Tor network".to_string()
        } else {
            rendered
        }
    };

    BootstrapProgress {
        percent,
        stage,
        ready,
    }
}

/// Watch the bootstrap state of a client and publish progress changes.
pub struct BootstrapMonitor {
    client: Arc<TorClient<PreferredRuntime>>,
    events: broadcast::Sender<BootstrapProgress>,
}

impl BootstrapMonitor {
    /// Create a monitor for `client`.
    pub fn new(
        client: Arc<TorClient<PreferredRuntime>>,
        events: broadcast::Sender<BootstrapProgress>,
    ) -> Self {
        Self { client, events }
    }

    /// Current live bootstrap state read directly from Arti.
    pub fn current(&self) -> BootstrapProgress {
        let status = self.client.bootstrap_status();
        progress_from_status(&status)
    }

    /// Stream live bootstrap events until the client is ready or `shutdown` fires.
    pub async fn run_until_shutdown(self, interval: Duration, mut shutdown: watch::Receiver<bool>) {
        let mut last: Option<BootstrapProgress> = None;
        let mut event_stream = self.client.bootstrap_events();

        loop {
            let current = self.current();
            let ready = current.ready;
            if last.as_ref() != Some(&current) {
                last = Some(current.clone());
                let _ = self.events.send(current);
            }
            if ready {
                return;
            }

            tokio::select! {
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        return;
                    }
                }
                maybe_status = event_stream.next() => {
                    if let Some(status) = maybe_status {
                        let next = progress_from_status(&status);
                        let done = next.ready;
                        if last.as_ref() != Some(&next) {
                            last = Some(next.clone());
                            let _ = self.events.send(next);
                        }
                        if done {
                            return;
                        }
                    } else {
                        tokio::time::sleep(interval).await;
                    }
                }
                _ = tokio::time::sleep(interval) => {}
            }
        }
    }

    /// Backwards-compatible runner without an external shutdown receiver.
    pub async fn run(self, interval: Duration) {
        let (_tx, rx) = watch::channel(false);
        self.run_until_shutdown(interval, rx).await;
    }
}
