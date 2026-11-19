use std::{env, fs, net::SocketAddr, path::PathBuf, sync::Arc};

use anyhow::{bail, Context, Result};
use tokio::net::TcpListener;
use trap_xmr402_swap::{
    build_router,
    wallet_engine::{RealChainBackend, RealChainConfig},
    ServiceConfig, Xmr402SwapState, DEFAULT_PRIZE_BANNER,
};

#[tokio::main]
async fn main() -> Result<()> {
    let bind_raw = env::var("TRAP_BIND_ADDR").unwrap_or_else(|_| "127.0.0.1:8081".to_string());
    let bind_addr: SocketAddr = bind_raw
        .parse()
        .with_context(|| format!("invalid TRAP_BIND_ADDR `{bind_raw}`"))?;

    if !bind_addr.ip().is_loopback() {
        bail!(
            "refusing to bind to non-loopback address {bind_addr}; trap-xmr402-swap must only listen behind Tor on 127.0.0.1"
        );
    }

    let onion_host = match env::var("TRAP_ONION_HOST") {
        Ok(val) if !val.trim().is_empty() => val.trim().to_string(),
        _ => {
            let host_file = env::var("TRAP_ONION_HOSTNAME_FILE")
                .unwrap_or_else(|_| "/var/lib/tor/trap_xmr402_swap/hostname".to_string());
            fs::read_to_string(&host_file)
                .with_context(|| format!("failed to read Tor onion hostname from `{host_file}`"))?
                .trim()
                .to_string()
        }
    };

    let prize_banner = match env::var("TRAP_PRIZE_PATH") {
        Ok(p) if !p.trim().is_empty() => fs::read_to_string(&p)
            .with_context(|| format!("failed to read prize banner from `{p}`"))?
            .trim()
            .to_string(),
        _ => env::var("TRAP_PRIZE_BANNER")
            .ok()
            .filter(|v| !v.trim().is_empty())
            .unwrap_or_else(|| DEFAULT_PRIZE_BANNER.to_string()),
    };

    let state_dir = PathBuf::from(
        env::var("TRAP_STATE_DIR").unwrap_or_else(|_| "/var/lib/trap-xmr402-swap".to_string()),
    );

    let monerod_admin_url =
        env::var("TRAP_MONEROD_ADMIN_URL").unwrap_or_else(|_| "http://127.0.0.1:18082".to_string());
    let bitcoind_rpc_url =
        env::var("TRAP_BITCOIND_RPC_URL").unwrap_or_else(|_| "http://127.0.0.1:18443".to_string());
    let bitcoind_wallet = env::var("TRAP_BITCOIND_WALLET").unwrap_or_else(|_| "faucet".to_string());
    let bitcoind_user = env::var("TRAP_BITCOIND_USER").unwrap_or_else(|_| "trapbtc".to_string());
    let bitcoind_pass = match env::var("TRAP_BITCOIND_PASS") {
        Ok(p) if !p.trim().is_empty() => p.trim().to_string(),
        _ => {
            let pass_file = env::var("TRAP_BITCOIND_PASS_FILE")
                .unwrap_or_else(|_| "/etc/trap-xmr402-swap/btc_rpc_pass".to_string());
            fs::read_to_string(&pass_file)
                .with_context(|| {
                    format!("failed to read bitcoind RPC password from `{pass_file}`")
                })?
                .trim()
                .to_string()
        }
    };

    let chain = Arc::new(
        RealChainBackend::connect_and_bootstrap(RealChainConfig {
            state_dir: state_dir.clone(),
            monerod_admin_url,
            bitcoind_rpc_url,
            bitcoind_wallet,
            bitcoind_user,
            bitcoind_pass,
        })
        .await?,
    );

    let state = Xmr402SwapState::new(
        ServiceConfig {
            onion_host,
            prize_banner,
            state_dir: Some(state_dir),
        },
        chain,
    )?;

    eprintln!(
        "trap-xmr402-swap listening on http://{bind_addr} (onion: http://{}/vault, daemon: {}, vault: {})",
        state.onion_host(),
        state.daemon_url(),
        state.vault_address_string()
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
