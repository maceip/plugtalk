use std::{env, fs, net::SocketAddr, path::PathBuf};

use anyhow::{bail, Context, Result};
use tokio::net::TcpListener;
use trap_pgp_totp::{build_router, ServiceConfig, TotpServiceState};

#[tokio::main]
async fn main() -> Result<()> {
    let bind_raw = env::var("TRAP_BIND_ADDR").unwrap_or_else(|_| "127.0.0.1:8080".to_string());
    let bind_addr: SocketAddr = bind_raw
        .parse()
        .with_context(|| format!("invalid TRAP_BIND_ADDR `{bind_raw}`"))?;

    if !bind_addr.ip().is_loopback() {
        bail!(
            "refusing to bind to non-loopback address {bind_addr}; trap-pgp-totp must only listen behind Tor on 127.0.0.1"
        );
    }

    let prize_path =
        env::var("TRAP_PRIZE_PATH").unwrap_or_else(|_| "/etc/trap-pgp-totp/prize.txt".to_string());
    let prize_text = fs::read_to_string(&prize_path)
        .with_context(|| format!("failed to read secret prize from `{prize_path}`"))?;

    let state_dir = env::var("TRAP_STATE_DIR")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(PathBuf::from);

    let state = TotpServiceState::new(ServiceConfig {
        prize_text,
        state_dir,
    })?;

    eprintln!(
        "trap-pgp-totp listening on http://{bind_addr} (server OpenPGP fingerprint: {})",
        state.server_fingerprint_hex()
    );

    let app = build_router(state);
    let listener = TcpListener::bind(bind_addr)
        .await
        .with_context(|| format!("failed to bind TCP listener on {bind_addr}"))?;

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .context("HTTP server terminated with error")?;

    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut sigterm) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            sigterm.recv().await;
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
