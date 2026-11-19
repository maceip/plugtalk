//! Zero-JavaScript `XMR402` (`HTTP 402 Payment Required` + canonical Monero `OutProofV2`)
//! Vault and real loopback `BTC → XMR` Swap Desk Tor v3 hidden service for Trap.

pub mod rpc;
pub mod tx_proof;
pub mod wallet_engine;

use std::{
    collections::{HashMap, HashSet, VecDeque},
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{bail, Context, Result};
use axum::{
    extract::{DefaultBodyLimit, Form, Path as AxumPath, State},
    http::{header, HeaderMap, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use hmac::{Hmac, Mac};
use monero_address::{MoneroAddress, Network};
use rand_core::{OsRng, RngCore as _};
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::Sha256;
use subtle::ConstantTimeEq as _;

use crate::{tx_proof::verify_tx_proof_v2, wallet_engine::ChainBackend};

/// Required vault payment in atomic XMR (`0.25 XMR = 250_000_000_000` piconero).
pub const VAULT_PRICE_ATOMIC: u64 = 250_000_000_000;

/// Fixed swap quote BTC input (`0.01 BTC`).
pub const SWAP_BTC_AMOUNT: &str = "0.01";

/// Fixed swap quote BTC input in satoshis (`1_000_000` sats).
pub const SWAP_MIN_BTC_SATS: u64 = 1_000_000;

/// Fixed swap quote XMR payout (`1.0 XMR = 1_000_000_000_000` piconero).
pub const SWAP_XMR_PAYOUT_ATOMIC: u64 = 1_000_000_000_000;

/// Active `XMR402` challenge lifetime (20 minutes).
pub const CHALLENGE_TTL_SECS: u64 = 20 * 60;

/// Maximum active `XMR402` challenges retained in the ring buffer.
pub const MAX_CHALLENGE_RING: usize = 1024;

/// Maximum swap orders retained in memory.
const MAX_SWAP_ORDERS: usize = 2048;

/// Default prize banner unlocked upon verified `XMR402` payment.
pub const DEFAULT_PRIZE_BANNER: &str =
    "PRIZE: OBSIDIAN-MONERO-CITADEL :: ZERO-JS XMR402 + BTC-SWAP CHAMPION [SEAL-402XMR]";

/// Strict zero-JavaScript Content-Security-Policy applied to every response.
pub const STRICT_CSP: &str = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'";

type HmacSha256 = Hmac<Sha256>;

/// Clock abstraction enabling deterministic challenge-expiry tests.
pub trait Clock: Send + Sync + 'static {
    fn now_unix(&self) -> u64;
}

/// Production OS wall clock.
#[derive(Clone, Copy, Debug, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now_unix(&self) -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    }
}

#[derive(Clone, Debug)]
pub struct ServiceConfig {
    pub onion_host: String,
    pub prize_banner: String,
    pub state_dir: Option<PathBuf>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct ChallengeEntry {
    timestamp: u64,
    message: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SwapOrderRecord {
    pub id: String,
    pub created_unix: u64,
    pub btc_amount: String,
    pub xmr_amount_atomic: u64,
    pub deposit_address: String,
    pub payout_address: String,
    pub status: String,
    pub btc_txid: Option<String>,
    pub xmr_payout_txid: Option<String>,
    pub xmr_payout_height: Option<u64>,
}

struct MutableRecords {
    challenges: VecDeque<ChallengeEntry>,
    redeemed_txids: HashSet<String>,
    orders: HashMap<String, SwapOrderRecord>,
    order_fifo: VecDeque<String>,
    order_locks: HashMap<String, Arc<tokio::sync::Mutex<()>>>,
}

pub struct Xmr402SwapState {
    chain: Arc<dyn ChainBackend>,
    clock: Arc<dyn Clock>,
    onion_host: String,
    daemon_url: String,
    prize_banner: String,
    hmac_key: [u8; 32],
    state_dir: Option<PathBuf>,
    records: Mutex<MutableRecords>,
}

impl Xmr402SwapState {
    pub fn new(config: ServiceConfig, chain: Arc<dyn ChainBackend>) -> Result<Arc<Self>> {
        Self::with_clock(config, chain, Arc::new(SystemClock))
    }

    pub fn with_clock(
        config: ServiceConfig,
        chain: Arc<dyn ChainBackend>,
        clock: Arc<dyn Clock>,
    ) -> Result<Arc<Self>> {
        let onion_host = config
            .onion_host
            .trim()
            .trim_start_matches("http://")
            .trim_end_matches('/')
            .to_ascii_lowercase();
        if onion_host.is_empty() || !onion_host.ends_with(".onion") {
            bail!("invalid onion_host `{onion_host}`; expected `<56-char>.onion`");
        }
        let prize_banner = config.prize_banner.trim().to_string();
        if prize_banner.is_empty() {
            bail!("prize_banner must not be empty");
        }
        let daemon_url = format!("http://{onion_host}:18081");

        let hmac_key = load_or_generate_hmac_key(config.state_dir.as_deref())?;
        let redeemed_txids = load_persisted_redeemed_txids(config.state_dir.as_deref())?;
        let (orders, order_fifo) = load_persisted_orders(config.state_dir.as_deref())?;
        let mut order_locks = HashMap::new();
        for id in orders.keys() {
            order_locks.insert(id.clone(), Arc::new(tokio::sync::Mutex::new(())));
        }

        Ok(Arc::new(Self {
            chain,
            clock,
            onion_host,
            daemon_url,
            prize_banner,
            hmac_key,
            state_dir: config.state_dir,
            records: Mutex::new(MutableRecords {
                challenges: VecDeque::with_capacity(MAX_CHALLENGE_RING),
                redeemed_txids,
                orders,
                order_fifo,
                order_locks,
            }),
        }))
    }

    pub fn onion_host(&self) -> &str {
        &self.onion_host
    }

    pub fn daemon_url(&self) -> &str {
        &self.daemon_url
    }

    pub fn vault_address_string(&self) -> String {
        self.chain.vault_subaddress().to_string()
    }

    fn issue_challenge(&self) -> (u64, String) {
        let now = self.clock.now_unix();
        let message = compute_challenge_message(&self.hmac_key, now);
        if let Ok(mut guard) = self.records.lock() {
            while let Some(front) = guard.challenges.front() {
                if now > front.timestamp.saturating_add(CHALLENGE_TTL_SECS) {
                    guard.challenges.pop_front();
                } else {
                    break;
                }
            }
            if guard
                .challenges
                .back()
                .is_none_or(|last| last.message != message)
            {
                if guard.challenges.len() >= MAX_CHALLENGE_RING {
                    guard.challenges.pop_front();
                }
                guard.challenges.push_back(ChallengeEntry {
                    timestamp: now,
                    message: message.clone(),
                });
            }
        }
        (now, message)
    }

    fn snapshot_active_challenges_newest_first(&self, now: u64) -> Vec<String> {
        let Ok(mut guard) = self.records.lock() else {
            return Vec::new();
        };
        while let Some(front) = guard.challenges.front() {
            if now > front.timestamp.saturating_add(CHALLENGE_TTL_SECS) {
                guard.challenges.pop_front();
            } else {
                break;
            }
        }
        guard
            .challenges
            .iter()
            .rev()
            .filter(|c| {
                c.timestamp <= now.saturating_add(60)
                    && now <= c.timestamp.saturating_add(CHALLENGE_TTL_SECS)
            })
            .map(|c| c.message.clone())
            .collect()
    }

    fn is_txid_redeemed(&self, txid: &str) -> bool {
        self.records
            .lock()
            .map(|g| g.redeemed_txids.contains(txid))
            .unwrap_or(false)
    }

    fn try_mark_txid_redeemed(&self, txid: &str) -> bool {
        let Ok(mut guard) = self.records.lock() else {
            return false;
        };
        if !guard.redeemed_txids.insert(txid.to_string()) {
            return false;
        }
        if let Some(ref dir) = self.state_dir {
            let list: Vec<&String> = guard.redeemed_txids.iter().collect();
            let _ = write_json_atomic(&dir.join("redeemed_txids.json"), &list);
        }
        true
    }

    fn insert_order(&self, record: SwapOrderRecord) {
        if let Ok(mut guard) = self.records.lock() {
            while guard.order_fifo.len() >= MAX_SWAP_ORDERS {
                if let Some(oldest) = guard.order_fifo.pop_front() {
                    guard.orders.remove(&oldest);
                    guard.order_locks.remove(&oldest);
                }
            }
            let id = record.id.clone();
            guard.order_fifo.push_back(id.clone());
            guard
                .order_locks
                .insert(id.clone(), Arc::new(tokio::sync::Mutex::new(())));
            guard.orders.insert(id, record);
            if let Some(ref dir) = self.state_dir {
                let all: Vec<&SwapOrderRecord> = guard.orders.values().collect();
                let _ = write_json_atomic(&dir.join("swap_orders.json"), &all);
            }
        }
    }

    fn get_order(&self, id: &str) -> Option<SwapOrderRecord> {
        self.records
            .lock()
            .ok()
            .and_then(|g| g.orders.get(id).cloned())
    }

    fn get_order_lock(&self, id: &str) -> Option<Arc<tokio::sync::Mutex<()>>> {
        self.records
            .lock()
            .ok()
            .and_then(|g| g.order_locks.get(id).cloned())
    }

    fn complete_order(
        &self,
        id: &str,
        btc_txid: String,
        xmr_payout_txid: String,
        xmr_payout_height: u64,
    ) -> Option<SwapOrderRecord> {
        let mut guard = self.records.lock().ok()?;
        let updated = {
            let entry = guard.orders.get_mut(id)?;
            entry.status = "completed".to_string();
            entry.btc_txid = Some(btc_txid);
            entry.xmr_payout_txid = Some(xmr_payout_txid);
            entry.xmr_payout_height = Some(xmr_payout_height);
            entry.clone()
        };
        if let Some(ref dir) = self.state_dir {
            let all: Vec<&SwapOrderRecord> = guard.orders.values().collect();
            let _ = write_json_atomic(&dir.join("swap_orders.json"), &all);
        }
        Some(updated)
    }
}

/// Build the Axum router serving all zero-JS `XMR402` Vault and `BTC → XMR` Swap Desk routes.
pub fn build_router(state: Arc<Xmr402SwapState>) -> Router {
    Router::new()
        .route("/", get(handle_vault))
        .route("/vault", get(handle_vault))
        .route("/swap", get(handle_swap_form))
        .route("/swap/order", post(handle_create_swap_order))
        .route("/swap/order/:id", get(handle_get_swap_order))
        .route("/swap/order/:id/fund-btc", post(handle_fund_swap_order_btc))
        .route("/healthz", get(handle_healthz))
        .layer(DefaultBodyLimit::max(64 * 1024))
        .with_state(state)
}

/// Compute `xmr402-citadel-<unix_ts>-<hmac16>` challenge nonce.
pub fn compute_challenge_message(hmac_key: &[u8; 32], unix_ts: u64) -> String {
    let tag = compute_challenge_tag(hmac_key, unix_ts);
    format!("xmr402-citadel-{unix_ts}-{}", hex::encode(tag))
}

fn compute_challenge_tag(hmac_key: &[u8; 32], unix_ts: u64) -> [u8; 8] {
    let mut mac = HmacSha256::new_from_slice(hmac_key).expect("HMAC-SHA256 accepts 256-bit keys");
    mac.update(b"TRAP-XMR402-CITADEL-V2\0");
    mac.update(format!("xmr402-citadel-{unix_ts}").as_bytes());
    let digest = mac.finalize().into_bytes();
    let mut out = [0u8; 8];
    out.copy_from_slice(&digest[..8]);
    out
}

/// Verify `xmr402-citadel-<unix_ts>-<hmac16>` HMAC tag and 20-minute freshness window.
pub fn verify_challenge_hmac_and_expiry(
    hmac_key: &[u8; 32],
    message: &str,
    now_unix: u64,
) -> Result<u64, &'static str> {
    let rest = message
        .trim()
        .strip_prefix("xmr402-citadel-")
        .ok_or("invalid XMR402 challenge prefix")?;
    let (ts_part, tag_hex) = rest
        .split_once('-')
        .ok_or("malformed XMR402 challenge format")?;
    let ts: u64 = ts_part
        .parse()
        .map_err(|_| "invalid XMR402 challenge timestamp")?;
    if tag_hex.len() != 16 {
        return Err("invalid XMR402 challenge HMAC length");
    }
    let decoded = hex::decode(tag_hex).map_err(|_| "invalid XMR402 challenge HMAC hex")?;
    let tag_bytes: [u8; 8] = decoded.try_into().map_err(|_| "bad HMAC slice")?;
    let expected = compute_challenge_tag(hmac_key, ts);
    if !bool::from(tag_bytes.ct_eq(&expected)) {
        return Err("invalid XMR402 challenge HMAC signature");
    }
    if ts > now_unix.saturating_add(60) {
        return Err("XMR402 challenge timestamp is in the future");
    }
    if now_unix > ts.saturating_add(CHALLENGE_TTL_SECS) {
        return Err("XMR402 challenge expired (>20 minutes)");
    }
    Ok(ts)
}

/// Parsed `Authorization: XMR402 txid="...", proof="OutProofV2..."[, message="..."]` credentials.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ParsedXmr402Auth {
    pub txid: String,
    pub proof: String,
    pub message: Option<String>,
}

/// Parse `Authorization: XMR402 ...` header according to `XMR402` v2.0.
pub fn parse_xmr402_authorization(raw_header: &str) -> Result<ParsedXmr402Auth, &'static str> {
    let trimmed = raw_header.trim();
    let mut parts = trimmed.splitn(2, char::is_whitespace);
    let scheme = parts.next().unwrap_or("");
    if !scheme.eq_ignore_ascii_case("XMR402") && !scheme.eq_ignore_ascii_case("x402") {
        return Err("unsupported Authorization scheme (expected XMR402)");
    }
    let params_str = parts
        .next()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or("missing XMR402 Authorization parameters")?;

    let mut txid: Option<String> = None;
    let mut proof: Option<String> = None;
    let mut message: Option<String> = None;

    for (k, v) in parse_auth_kv_pairs(params_str) {
        match k.to_ascii_lowercase().as_str() {
            "txid" | "tx_hash" | "txhash" => txid = Some(v),
            "proof" | "tx_proof" | "signature" => proof = Some(v),
            "message" | "nonce" if !v.is_empty() => {
                message = Some(v);
            }
            _ => {}
        }
    }

    let txid_norm = txid
        .ok_or("missing txid in XMR402 Authorization header")?
        .trim()
        .to_ascii_lowercase();
    if txid_norm.len() != 64 || !txid_norm.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err("txid must be 64 hexadecimal characters");
    }

    let proof_str = proof
        .ok_or("missing proof in XMR402 Authorization header")?
        .trim()
        .to_string();
    if !proof_str.starts_with("OutProofV2") {
        return Err("proof must start with OutProofV2");
    }

    Ok(ParsedXmr402Auth {
        txid: txid_norm,
        proof: proof_str,
        message,
    })
}

fn parse_auth_kv_pairs(input: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let bytes = input.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b',') {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        let key_start = i;
        while i < bytes.len()
            && bytes[i] != b'='
            && bytes[i] != b','
            && !bytes[i].is_ascii_whitespace()
        {
            i += 1;
        }
        let key = input[key_start..i].trim().to_string();
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= bytes.len() || bytes[i] != b'=' {
            while i < bytes.len() && bytes[i] != b',' {
                i += 1;
            }
            continue;
        }
        i += 1; // skip '='
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let val = if i < bytes.len() && bytes[i] == b'"' {
            i += 1;
            let mut v = String::new();
            while i < bytes.len() {
                if bytes[i] == b'\\' && i + 1 < bytes.len() {
                    i += 1;
                    v.push(bytes[i] as char);
                    i += 1;
                } else if bytes[i] == b'"' {
                    i += 1;
                    break;
                } else {
                    v.push(bytes[i] as char);
                    i += 1;
                }
            }
            v
        } else {
            let val_start = i;
            while i < bytes.len() && bytes[i] != b',' && !bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            input[val_start..i].trim().to_string()
        };
        if !key.is_empty() {
            out.push((key, val));
        }
    }
    out
}

async fn handle_vault(State(state): State<Arc<Xmr402SwapState>>, headers: HeaderMap) -> Response {
    let auth_opt = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|s| !s.is_empty());

    let Some(raw_auth) = auth_opt else {
        return build_402_challenge_response(&state, None);
    };

    match verify_and_redeem_xmr402(&state, raw_auth).await {
        Ok(unlocked_resp) => unlocked_resp,
        Err(reason) => build_402_challenge_response(&state, Some(&reason)),
    }
}

async fn verify_and_redeem_xmr402(
    state: &Arc<Xmr402SwapState>,
    raw_auth: &str,
) -> Result<Response, String> {
    let parsed = parse_xmr402_authorization(raw_auth).map_err(|e| e.to_string())?;
    if state.is_txid_redeemed(&parsed.txid) {
        return Err(format!("replayed XMR402 txid {} rejected", parsed.txid));
    }

    let (tx, existing_height, _in_pool) = state
        .chain
        .fetch_transaction(&parsed.txid)
        .await
        .map_err(|e| format!("failed to fetch transaction {}: {e}", parsed.txid))?;

    let now = state.clock.now_unix();
    let vault_subaddr = state.chain.vault_subaddress();

    let (matched_message, verified_amount) = if let Some(ref explicit_msg) = parsed.message {
        verify_challenge_hmac_and_expiry(&state.hmac_key, explicit_msg, now)
            .map_err(|e| e.to_string())?;
        let amt = verify_tx_proof_v2(&tx, vault_subaddr, explicit_msg, &parsed.proof)
            .map_err(|e| format!("OutProofV2 verification failed: {e}"))?;
        (explicit_msg.clone(), amt)
    } else {
        let candidates = state.snapshot_active_challenges_newest_first(now);
        if candidates.is_empty() {
            return Err("no active unexpired XMR402 challenge nonces".to_string());
        }
        let mut found: Option<(String, u64)> = None;
        let mut last_err = "OutProofV2 did not match any active challenge nonce";
        for cand_msg in candidates {
            match verify_tx_proof_v2(&tx, vault_subaddr, &cand_msg, &parsed.proof) {
                Ok(amt) => {
                    found = Some((cand_msg, amt));
                    break;
                }
                Err(e) => {
                    last_err = e;
                }
            }
        }
        found.ok_or_else(|| format!("OutProofV2 verification failed: {last_err}"))?
    };

    if verified_amount < VAULT_PRICE_ATOMIC {
        return Err(format!(
            "insufficient XMR402 payment: verified {} atomic (< required {} atomic)",
            verified_amount, VAULT_PRICE_ATOMIC
        ));
    }

    if !state.try_mark_txid_redeemed(&parsed.txid) {
        return Err(format!("replayed XMR402 txid {} rejected", parsed.txid));
    }

    // Mine 1 block on admin RPC so client wallet also sees immediate on-chain confirmation.
    let confirmed_height = match existing_height {
        Some(h) if h > 0 => {
            let _ = state.chain.mine_monero_blocks(1).await;
            h
        }
        _ => state
            .chain
            .mine_monero_blocks(1)
            .await
            .unwrap_or(0)
            .saturating_sub(1),
    };

    let proof_prefix_len = parsed.proof.len().min(26);
    let proof_prefix = format!("{}...", &parsed.proof[..proof_prefix_len]);
    let vault_str = vault_subaddr.to_string();

    let prize_json_obj = json!({
        "ok": true,
        "prizeBanner": state.prize_banner,
        "txid": parsed.txid,
        "verifiedAmountAtomic": verified_amount,
        "requiredAmountAtomic": VAULT_PRICE_ATOMIC,
        "message": matched_message,
        "vaultAddress": vault_str,
        "proofPrefix": proof_prefix,
        "confirmedAtHeight": confirmed_height,
        "onionHost": state.onion_host,
    });
    let prize_json_pretty =
        serde_json::to_string_pretty(&prize_json_obj).unwrap_or_else(|_| "{}".to_string());

    let html = format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Obsidian Monero Citadel — Unlocked</title>
<style>{BASE_CSS}</style>
</head>
<body>
<main class="card">
  <div class="header-row">
    <span class="badge ok">HTTP 200 UNLOCKED</span>
    <span class="meta">OutProofV2 Verified</span>
  </div>
  <h1 id="xmr402-prize-banner">{banner}</h1>
  <p class="lead">Stateless Pedersen commitment &amp; Schnorr DLEQ <code>OutProofV2</code> verified for <strong>{xmr_fmt} XMR</strong>.</p>
  <section class="panel">
    <div class="kv"><span class="k">Redeemed TxID</span><code id="xmr402-redeemed-txid">{txid}</code></div>
    <div class="kv"><span class="k">Challenge Nonce</span><code id="xmr402-redeemed-message">{msg}</code></div>
    <div class="kv"><span class="k">Vault Subaddress</span><code>{vault}</code></div>
  </section>
  <h2>Cryptographic Settlement Receipt</h2>
  <pre id="xmr402-prize-json">{json_escaped}</pre>
  <p class="nav-row"><a href="/swap">Open Zero-JS BTC → XMR Swap Desk</a></p>
</main>
</body>
</html>"#,
        banner = escape_html(&state.prize_banner),
        xmr_fmt = format_atomic_xmr(verified_amount),
        txid = escape_html(&parsed.txid),
        msg = escape_html(&matched_message),
        vault = escape_html(&vault_str),
        json_escaped = escape_html(&prize_json_pretty),
    );

    Ok(html_response(StatusCode::OK, html, None))
}

fn build_402_challenge_response(
    state: &Arc<Xmr402SwapState>,
    auth_error: Option<&str>,
) -> Response {
    let (unix_ts, message) = state.issue_challenge();
    let vault_addr = state.vault_address_string();
    let daemon_url = state.daemon_url();
    let amount_str = VAULT_PRICE_ATOMIC.to_string();

    let www_auth = format!(
        r#"XMR402 address="{vault_addr}", amount="{amount_str}", message="{message}", timestamp="{unix_ts}", daemon="{daemon_url}""#
    );

    let meta_json = json!({
        "address": vault_addr,
        "amountAtomic": amount_str,
        "message": message,
        "timestamp": unix_ts,
        "daemonUrl": daemon_url,
    })
    .to_string();
    let meta_attr_escaped = meta_json.replace('&', "&amp;").replace('\'', "&#39;");

    let err_block = match auth_error {
        Some(err) => format!(
            r#"<p id="xmr402-auth-error" class="alert" role="alert">Authorization rejected: {}</p>"#,
            escape_html(err)
        ),
        None => String::new(),
    };

    let html = format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="xmr402-challenge" content='{meta_attr_escaped}'>
<title>HTTP 402 Payment Required — Obsidian Monero Citadel</title>
<style>{BASE_CSS}</style>
</head>
<body>
<main class="card">
  <div class="header-row">
    <span class="badge warn">XMR402 GATE</span>
    <span class="meta">Zero-JS Tor v3 Vault</span>
  </div>
  <h1 id="xmr402-status-heading">HTTP 402 PAYMENT REQUIRED — 0.25 XMR</h1>
  {err_block}
  <p class="lead">Pay <strong>0.250000000000 XMR</strong> (<code>250000000000</code> atomic) to the vault subaddress below and replay this request with <code>Authorization: XMR402 txid="&lt;hex&gt;", proof="OutProofV2..."</code>.</p>
  <section class="panel">
    <div class="kv">
      <span class="k">Vault Monero Subaddress (8...)</span>
      <code id="xmr402-vault-address">{vault}</code>
    </div>
    <div class="kv">
      <span class="k">Required Amount</span>
      <code id="xmr402-amount-atomic">250000000000</code>
    </div>
    <div class="kv">
      <span class="k">Challenge Nonce (HMAC, 20m TTL)</span>
      <code id="xmr402-challenge-message">{msg}</code>
    </div>
    <div class="kv">
      <span class="k">Restricted Regtest Monero Daemon (.onion)</span>
      <code id="xmr402-daemon-url">{daemon}</code>
    </div>
  </section>
  <div class="cta-box">
    <p>Need XMR on this hidden-service chain?</p>
    <a id="goto-swap-desk" class="btn-link" href="/swap">Fund Wallet via Zero-JS BTC → XMR Swap Desk</a>
  </div>
</main>
</body>
</html>"#,
        vault = escape_html(&vault_addr),
        msg = escape_html(&message),
        daemon = escape_html(daemon_url),
    );

    html_response(StatusCode::PAYMENT_REQUIRED, html, Some(&www_auth))
}

async fn handle_swap_form(State(state): State<Arc<Xmr402SwapState>>) -> Response {
    let html = render_swap_form_html(&state, None);
    html_response(StatusCode::OK, html, None)
}

fn render_swap_form_html(state: &Xmr402SwapState, error_msg: Option<&str>) -> String {
    let err_block = match error_msg {
        Some(msg) => format!(
            r#"<p id="swap-error" class="alert" role="alert">{}</p>"#,
            escape_html(msg)
        ),
        None => String::new(),
    };
    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Zero-JS BTC → XMR Swap Desk</title>
<style>{BASE_CSS}</style>
</head>
<body data-swap="btc-to-xmr">
<main class="card">
  <div class="header-row">
    <span class="badge ok">ZERO-JS SWAP DESK</span>
    <span class="meta">0.01 BTC → 1.0 XMR</span>
  </div>
  <h1>Zero-JS BTC → XMR Swap Desk</h1>
  {err_block}
  <p class="lead">Instant atomic regtest settlement: deposit <strong>0.01 BTC</strong> to receive a Ring-16 <code>ClsagBulletproofPlus</code> payout of <strong>1.000000000000 XMR</strong> on <code id="swap-daemon-url">{daemon}</code>.</p>
  <form method="POST" action="/swap/order" class="swap-form">
    <div class="field">
      <label for="btc_amount">BTC Deposit Amount (Fixed Quote: 0.01 BTC → 1.000000000000 XMR)</label>
      <input id="btc_amount" name="btc_amount" type="text" value="0.01" readonly>
    </div>
    <div class="field" data-net="xmr">
      <label for="payout_addr">Recipient Monero (XMR) Subaddress</label>
      <input id="payout_addr" name="payout_addr" type="text" placeholder="Paste Monero 8... subaddress" required>
    </div>
    <button id="create-swap-btn" type="submit">Create BTC → XMR Swap Order</button>
  </form>
  <p class="nav-row"><a href="/vault">Back to XMR402 Vault (/vault)</a></p>
</main>
</body>
</html>"#,
        daemon = escape_html(state.daemon_url()),
    )
}

#[derive(Deserialize)]
struct CreateSwapForm {
    #[serde(default)]
    btc_amount: String,
    payout_addr: String,
}

async fn handle_create_swap_order(
    State(state): State<Arc<Xmr402SwapState>>,
    Form(form): Form<CreateSwapForm>,
) -> Response {
    let btc_trimmed = form.btc_amount.trim();
    if !btc_trimmed.is_empty() && btc_trimmed != SWAP_BTC_AMOUNT {
        let html = render_swap_form_html(
            &state,
            Some("Invalid BTC quote amount; fixed desk quote is 0.01 BTC."),
        );
        return html_response(StatusCode::BAD_REQUEST, html, None);
    }

    let payout_trimmed = form.payout_addr.trim();
    if MoneroAddress::from_str(Network::Mainnet, payout_trimmed).is_err() {
        let html = render_swap_form_html(
            &state,
            Some("Invalid Monero recipient address; paste a checksum-valid 8... subaddress or 4... address."),
        );
        return html_response(StatusCode::BAD_REQUEST, html, None);
    }

    let mut id_bytes = [0u8; 16];
    OsRng.fill_bytes(&mut id_bytes);
    let order_id = hex::encode(id_bytes);

    let deposit_address = match state
        .chain
        .new_btc_deposit_address(&format!("swap-{order_id}"))
        .await
    {
        Ok(addr) => addr,
        Err(err) => {
            let html = render_swap_form_html(
                &state,
                Some(&format!(
                    "Failed to allocate BTC Bech32 deposit address: {err}"
                )),
            );
            return html_response(StatusCode::BAD_GATEWAY, html, None);
        }
    };

    let record = SwapOrderRecord {
        id: order_id.clone(),
        created_unix: state.clock.now_unix(),
        btc_amount: SWAP_BTC_AMOUNT.to_string(),
        xmr_amount_atomic: SWAP_XMR_PAYOUT_ATOMIC,
        deposit_address,
        payout_address: payout_trimmed.to_string(),
        status: "awaiting_btc_deposit".to_string(),
        btc_txid: None,
        xmr_payout_txid: None,
        xmr_payout_height: None,
    };
    state.insert_order(record);

    redirect_303(&format!("/swap/order/{order_id}"))
}

async fn handle_get_swap_order(
    State(state): State<Arc<Xmr402SwapState>>,
    AxumPath(id): AxumPath<String>,
) -> Response {
    let clean_id = id.trim();
    let Some(order) = state.get_order(clean_id) else {
        let html = render_swap_form_html(&state, Some("Swap order not found or expired."));
        return html_response(StatusCode::NOT_FOUND, html, None);
    };
    let html = render_swap_order_html(&state, &order, None);
    html_response(StatusCode::OK, html, None)
}

async fn handle_fund_swap_order_btc(
    State(state): State<Arc<Xmr402SwapState>>,
    AxumPath(id): AxumPath<String>,
) -> Response {
    let clean_id = id.trim().to_string();
    let Some(lock) = state.get_order_lock(&clean_id) else {
        let html = render_swap_form_html(&state, Some("Swap order not found or expired."));
        return html_response(StatusCode::NOT_FOUND, html, None);
    };

    let _order_guard = lock.lock().await;
    let Some(order) = state.get_order(&clean_id) else {
        let html = render_swap_form_html(&state, Some("Swap order not found or expired."));
        return html_response(StatusCode::NOT_FOUND, html, None);
    };

    if order.status == "completed" {
        return redirect_303(&format!("/swap/order/{clean_id}"));
    }

    let payout_addr = match MoneroAddress::from_str(Network::Mainnet, &order.payout_address) {
        Ok(a) => a,
        Err(e) => {
            let html = render_swap_order_html(
                &state,
                &order,
                Some(&format!("Invalid payout address: {e}")),
            );
            return html_response(StatusCode::BAD_REQUEST, html, None);
        }
    };

    match state
        .chain
        .fund_btc_and_payout_xmr(
            &order.deposit_address,
            &order.btc_amount,
            SWAP_MIN_BTC_SATS,
            payout_addr,
            order.xmr_amount_atomic,
        )
        .await
    {
        Ok(settlement) => {
            let _ = state.complete_order(
                &clean_id,
                settlement.btc_txid,
                settlement.xmr_payout_txid,
                settlement.xmr_payout_height,
            );
            redirect_303(&format!("/swap/order/{clean_id}"))
        }
        Err(err) => {
            let html = render_swap_order_html(
                &state,
                &order,
                Some(&format!("Swap settlement failed: {err}")),
            );
            html_response(StatusCode::BAD_GATEWAY, html, None)
        }
    }
}

fn render_swap_order_html(
    state: &Xmr402SwapState,
    order: &SwapOrderRecord,
    error_msg: Option<&str>,
) -> String {
    let is_completed = order.status == "completed";
    let (badge_cls, status_label) = if is_completed {
        (
            "ok",
            "Completed — 1.000000000000 XMR Sent & Unlocked (11 Confirmations)",
        )
    } else {
        ("warn", "Awaiting 0.01 BTC Deposit")
    };

    let err_block = match error_msg {
        Some(msg) => format!(
            r#"<p id="swap-order-error" class="alert" role="alert">{}</p>"#,
            escape_html(msg)
        ),
        None => String::new(),
    };

    let action_section = if is_completed {
        format!(
            r#"<section class="panel">
    <div class="kv">
      <span class="k">Confirmed Bitcoin Deposit TxID</span>
      <code id="btc-txid">{btc_txid}</code>
    </div>
    <div class="kv">
      <span class="k">Ring-16 Monero Payout TxID (1.0 XMR)</span>
      <code id="xmr-payout-txid">{xmr_txid}</code>
    </div>
    <div class="kv">
      <span class="k">Monero Payout Block Height</span>
      <code id="xmr-payout-height">{xmr_height}</code>
    </div>
  </section>
  <div class="cta-box">
    <a id="return-to-vault" class="btn-link" href="/vault">Return to XMR402 Vault (/vault)</a>
  </div>"#,
            btc_txid = escape_html(order.btc_txid.as_deref().unwrap_or("")),
            xmr_txid = escape_html(order.xmr_payout_txid.as_deref().unwrap_or("")),
            xmr_height = order.xmr_payout_height.unwrap_or(0),
        )
    } else {
        format!(
            r#"<form method="POST" action="/swap/order/{id}/fund-btc" class="swap-form">
    <button id="fund-btc-btn" type="submit">Pay 0.01 BTC from External Regtest Wallet</button>
  </form>"#,
            id = escape_html(&order.id),
        )
    };

    format!(
        r#"<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Swap Order {id} — BTC → XMR</title>
<style>{BASE_CSS}</style>
</head>
<body data-swap="btc-to-xmr">
<main class="card">
  <div class="header-row">
    <span id="swap-status" class="badge {badge_cls}" data-status="{status_attr}">{status_label}</span>
    <span class="meta">Order #{short_id}</span>
  </div>
  <h1>BTC → XMR Swap Order</h1>
  {err_block}
  <section class="panel" id="deposit-box">
    <div class="kv">
      <span class="k">Required Deposit</span>
      <span><span id="amount_a">{btc_amt}</span> BTC → <strong>1.000000000000 XMR</strong></span>
    </div>
    <div class="field">
      <label for="deposit_address">BTC Deposit Address (Regtest Bech32)</label>
      <input id="deposit_address" name="deposit_address" type="text" readonly value="{dep_addr}">
    </div>
    <div class="kv">
      <span class="k">Recipient XMR Target</span>
      <code id="xmr-payout-target">{payout_addr}</code>
    </div>
    <div class="kv">
      <span class="k">Monero Daemon (.onion:18081)</span>
      <code id="xmr-daemon-url">{daemon}</code>
    </div>
  </section>
  {action_section}
</main>
</body>
</html>"#,
        id = escape_html(&order.id),
        short_id = escape_html(&order.id[..order.id.len().min(12)]),
        badge_cls = badge_cls,
        status_attr = escape_html(&order.status),
        status_label = escape_html(status_label),
        btc_amt = escape_html(&order.btc_amount),
        dep_addr = escape_html(&order.deposit_address),
        payout_addr = escape_html(&order.payout_address),
        daemon = escape_html(state.daemon_url()),
        action_section = action_section,
    )
}

async fn handle_healthz(State(state): State<Arc<Xmr402SwapState>>) -> Response {
    let xmr_res = state.chain.monerod_height().await;
    let btc_res = state.chain.bitcoind_blocks().await;

    match (xmr_res, btc_res) {
        (Ok(monerod_height), Ok(bitcoind_blocks)) => {
            let body = json!({
                "ok": true,
                "monerodHeight": monerod_height,
                "bitcoindBlocks": bitcoind_blocks,
                "onionHost": state.onion_host(),
                "daemonUrl": state.daemon_url(),
                "vaultAddress": state.vault_address_string(),
            });
            json_response(StatusCode::OK, body)
        }
        (xmr_err, btc_err) => {
            let err_str = format!(
                "monerod={:?}, bitcoind={:?}",
                xmr_err.err().map(|e| e.to_string()),
                btc_err.err().map(|e| e.to_string())
            );
            let body = json!({
                "ok": false,
                "error": err_str,
                "onionHost": state.onion_host(),
            });
            json_response(StatusCode::SERVICE_UNAVAILABLE, body)
        }
    }
}

fn html_response(status: StatusCode, html: String, www_authenticate: Option<&str>) -> Response {
    let mut resp = (status, html).into_response();
    let headers = resp.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/html; charset=utf-8"),
    );
    apply_security_headers(headers);
    if let Some(auth_val) = www_authenticate {
        if let Ok(hv) = HeaderValue::from_str(auth_val) {
            headers.insert(header::WWW_AUTHENTICATE, hv);
        }
    }
    resp
}

fn json_response(status: StatusCode, value: serde_json::Value) -> Response {
    let mut resp = (status, Json(value)).into_response();
    apply_security_headers(resp.headers_mut());
    resp
}

fn redirect_303(location: &str) -> Response {
    let mut resp = StatusCode::SEE_OTHER.into_response();
    let headers = resp.headers_mut();
    if let Ok(hv) = HeaderValue::from_str(location) {
        headers.insert(header::LOCATION, hv);
    }
    apply_security_headers(headers);
    resp
}

fn apply_security_headers(headers: &mut HeaderMap) {
    headers.insert(
        header::CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(STRICT_CSP),
    );
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
}

fn format_atomic_xmr(atomic: u64) -> String {
    let whole = atomic / 1_000_000_000_000;
    let frac = atomic % 1_000_000_000_000;
    format!("{whole}.{frac:012}")
}

fn escape_html(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for ch in s.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            _ => out.push(ch),
        }
    }
    out
}

fn load_or_generate_hmac_key(state_dir: Option<&Path>) -> Result<[u8; 32]> {
    let Some(dir) = state_dir else {
        let mut fresh = [0u8; 32];
        OsRng.fill_bytes(&mut fresh);
        return Ok(fresh);
    };
    fs::create_dir_all(dir)?;
    let path = dir.join("challenge_hmac.hex");
    if path.exists() {
        let raw = fs::read_to_string(&path)?;
        let decoded = hex::decode(raw.trim()).context("invalid hex in challenge_hmac.hex")?;
        if let Ok(arr) = <[u8; 32]>::try_from(decoded.as_slice()) {
            return Ok(arr);
        }
    }
    let mut fresh = [0u8; 32];
    OsRng.fill_bytes(&mut fresh);
    let tmp = dir.join("challenge_hmac.hex.tmp");
    {
        #[cfg(unix)]
        let mut f = {
            use std::os::unix::fs::OpenOptionsExt as _;
            fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&tmp)?
        };
        #[cfg(not(unix))]
        let mut f = fs::File::create(&tmp)?;
        f.write_all(format!("{}\n", hex::encode(fresh)).as_bytes())?;
        f.sync_all()?;
    }
    fs::rename(&tmp, &path)?;
    Ok(fresh)
}

fn load_persisted_redeemed_txids(state_dir: Option<&Path>) -> Result<HashSet<String>> {
    let Some(dir) = state_dir else {
        return Ok(HashSet::new());
    };
    let path = dir.join("redeemed_txids.json");
    if !path.exists() {
        return Ok(HashSet::new());
    }
    let raw = fs::read_to_string(&path)?;
    let list: Vec<String> = serde_json::from_str(&raw).unwrap_or_default();
    Ok(list.into_iter().collect())
}

fn load_persisted_orders(
    state_dir: Option<&Path>,
) -> Result<(HashMap<String, SwapOrderRecord>, VecDeque<String>)> {
    let Some(dir) = state_dir else {
        return Ok((HashMap::new(), VecDeque::new()));
    };
    let path = dir.join("swap_orders.json");
    if !path.exists() {
        return Ok((HashMap::new(), VecDeque::new()));
    }
    let raw = fs::read_to_string(&path)?;
    let mut list: Vec<SwapOrderRecord> = serde_json::from_str(&raw).unwrap_or_default();
    list.sort_by_key(|o| o.created_unix);
    let mut map = HashMap::new();
    let mut fifo = VecDeque::new();
    for ord in list {
        fifo.push_back(ord.id.clone());
        map.insert(ord.id.clone(), ord);
    }
    Ok((map, fifo))
}

fn write_json_atomic<T: Serialize>(path: &Path, val: &T) -> Result<()> {
    let tmp = path.with_extension("json.tmp");
    let data = serde_json::to_vec(val)?;
    {
        #[cfg(unix)]
        let mut f = {
            use std::os::unix::fs::OpenOptionsExt as _;
            fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&tmp)?
        };
        #[cfg(not(unix))]
        let mut f = fs::File::create(&tmp)?;
        f.write_all(&data)?;
        f.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    Ok(())
}

const BASE_CSS: &str = r#"
:root {
  color-scheme: dark;
  --bg: #0b0d10;
  --surface: #13171d;
  --panel: #191f28;
  --border: #2a3342;
  --text: #e7ecf3;
  --muted: #98a4b5;
  --accent: #ff7a2f;
  --ok: #2ecc71;
  --warn: #f39c12;
  --danger: #e74c3c;
}
* { box-sizing: border-box; }
body {
  margin: 0;
  padding: 20px 14px;
  background: var(--bg);
  color: var(--text);
  font-family: ui-sans-serif, system-ui, -apple-system, sans-serif;
  line-height: 1.5;
}
.card {
  max-width: 680px;
  margin: 0 auto;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 22px;
}
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}
.badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.badge.ok { background: rgba(46, 204, 113, 0.16); color: var(--ok); border: 1px solid rgba(46, 204, 113, 0.4); }
.badge.warn { background: rgba(243, 156, 18, 0.16); color: var(--warn); border: 1px solid rgba(243, 156, 18, 0.4); }
.meta { font-size: 12px; color: var(--muted); font-family: ui-monospace, monospace; }
h1 { font-size: 20px; margin: 0 0 12px 0; line-height: 1.3; }
h2 { font-size: 15px; margin: 18px 0 8px 0; color: var(--muted); }
.lead { color: var(--muted); margin: 0 0 16px 0; font-size: 14px; }
.panel {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 14px;
  margin-bottom: 16px;
}
.kv { margin-bottom: 10px; }
.kv:last-child { margin-bottom: 0; }
.kv .k { display: block; font-size: 12px; color: var(--muted); margin-bottom: 3px; }
code, pre {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}
code {
  display: inline-block;
  background: #0e1217;
  padding: 4px 7px;
  border-radius: 4px;
  border: 1px solid var(--border);
}
pre {
  background: #0e1217;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid var(--border);
  white-space: pre-wrap;
  overflow-x: auto;
}
.field { margin-bottom: 14px; }
label { display: block; font-size: 13px; font-weight: 600; margin-bottom: 6px; }
input[type="text"] {
  width: 100%;
  padding: 10px 12px;
  background: #0e1217;
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 8px;
  font-family: ui-monospace, monospace;
  font-size: 13px;
}
button, .btn-link {
  display: inline-block;
  width: 100%;
  text-align: center;
  padding: 12px 16px;
  background: var(--accent);
  color: #0b0d10;
  font-weight: 700;
  font-size: 14px;
  border: none;
  border-radius: 8px;
  text-decoration: none;
  cursor: pointer;
}
.cta-box { margin-top: 14px; }
.cta-box p { margin: 0 0 8px 0; font-size: 13px; color: var(--muted); }
.alert {
  background: rgba(231, 76, 60, 0.15);
  border: 1px solid rgba(231, 76, 60, 0.45);
  color: #ffb4ab;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 14px;
}
.nav-row { margin-top: 14px; font-size: 13px; }
.nav-row a { color: var(--accent); }
"#;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    use axum::{
        body::to_bytes,
        http::{Request, StatusCode},
    };
    use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
    use monero_address::SubaddressIndex;
    use monero_wallet::{
        ed25519::{Commitment, Point, Scalar},
        interface::FeeRate,
        ringct::{clsag::Decoys, RctType},
        send::{Change, SignableTransaction},
        transaction::Transaction,
        OutputWithDecoys, ViewPair,
    };
    use tower::ServiceExt as _;
    use zeroize::Zeroizing;

    use crate::{
        tx_proof::{generate_tx_proof_v2, reconstruct_tx_secret_keys, verify_tx_proof_v2},
        wallet_engine::{BoxFuture, SwapSettlement},
    };

    struct ManualClock {
        now: AtomicU64,
    }

    impl ManualClock {
        fn new(initial: u64) -> Arc<Self> {
            Arc::new(Self {
                now: AtomicU64::new(initial),
            })
        }
        fn advance(&self, secs: u64) {
            self.now.fetch_add(secs, Ordering::SeqCst);
        }
    }

    impl Clock for ManualClock {
        fn now_unix(&self) -> u64 {
            self.now.load(Ordering::SeqCst)
        }
    }

    struct InMemoryChain {
        primary_addr: MoneroAddress,
        vault_subaddr: MoneroAddress,
        txs: Mutex<HashMap<String, Transaction>>,
        xmr_height: AtomicU64,
        btc_blocks: AtomicU64,
        addr_counter: AtomicU64,
    }

    impl InMemoryChain {
        fn new() -> Arc<Self> {
            let mut rng = OsRng;
            let (_, vp) = make_wallet(&mut rng);
            let primary_addr = vp.legacy_address(Network::Mainnet);
            let vault_subaddr =
                vp.subaddress(Network::Mainnet, SubaddressIndex::new(0, 1).unwrap());
            Arc::new(Self {
                primary_addr,
                vault_subaddr,
                txs: Mutex::new(HashMap::new()),
                xmr_height: AtomicU64::new(312),
                btc_blocks: AtomicU64::new(105),
                addr_counter: AtomicU64::new(1),
            })
        }

        fn insert_mempool_tx(&self, tx: Transaction) -> String {
            let txid = hex::encode(tx.hash());
            self.txs.lock().unwrap().insert(txid.clone(), tx);
            txid
        }
    }

    impl ChainBackend for InMemoryChain {
        fn primary_address(&self) -> MoneroAddress {
            self.primary_addr
        }
        fn vault_subaddress(&self) -> MoneroAddress {
            self.vault_subaddr
        }
        fn fetch_transaction(
            &self,
            txid_hex: &str,
        ) -> BoxFuture<'_, Result<(Transaction, Option<u64>, bool)>> {
            let key = txid_hex.to_ascii_lowercase();
            let res = self
                .txs
                .lock()
                .unwrap()
                .get(&key)
                .cloned()
                .map(|tx| (tx, None, true))
                .ok_or_else(|| anyhow::anyhow!("tx not found"));
            Box::pin(async move { res })
        }
        fn mine_monero_blocks(&self, count: u64) -> BoxFuture<'_, Result<u64>> {
            let h = self.xmr_height.fetch_add(count, Ordering::SeqCst) + count;
            Box::pin(async move { Ok(h) })
        }
        fn monerod_height(&self) -> BoxFuture<'_, Result<u64>> {
            let h = self.xmr_height.load(Ordering::SeqCst);
            Box::pin(async move { Ok(h) })
        }
        fn bitcoind_blocks(&self) -> BoxFuture<'_, Result<u64>> {
            let b = self.btc_blocks.load(Ordering::SeqCst);
            Box::pin(async move { Ok(b) })
        }
        fn new_btc_deposit_address(&self, _label: &str) -> BoxFuture<'_, Result<String>> {
            let n = self.addr_counter.fetch_add(1, Ordering::SeqCst);
            let addr = format!("bcrt1qswapdeposit{:026x}", n);
            Box::pin(async move { Ok(addr) })
        }
        fn fund_btc_and_payout_xmr(
            &self,
            _deposit_addr: &str,
            _expected_btc: &str,
            _expected_min_sats: u64,
            payout_addr: MoneroAddress,
            xmr_amount_atomic: u64,
        ) -> BoxFuture<'_, Result<SwapSettlement>> {
            let mut rng = OsRng;
            let (tx, _, _) = sign_real_ring16_tx(&mut rng, payout_addr, xmr_amount_atomic);
            let xmr_txid = self.insert_mempool_tx(tx);
            let btc_h = self.btc_blocks.fetch_add(1, Ordering::SeqCst) + 1;
            let xmr_h = self.xmr_height.fetch_add(11, Ordering::SeqCst) + 1;
            let settlement = SwapSettlement {
                btc_txid: "ab".repeat(32),
                btc_block_height: btc_h,
                xmr_payout_txid: xmr_txid,
                xmr_payout_height: xmr_h,
                xmr_fee_atomic: 380_000_000,
            };
            Box::pin(async move { Ok(settlement) })
        }
    }

    fn make_wallet(rng: &mut OsRng) -> (Scalar, ViewPair) {
        let spend = Scalar::random(rng);
        let spend_d: curve25519_dalek::Scalar = spend.into();
        let spend_pub = Point::from(&spend_d * ED25519_BASEPOINT_TABLE);
        let view = Scalar::hash(spend_d.to_bytes());
        let vp = ViewPair::new(spend_pub, Zeroizing::new(view)).unwrap();
        (spend, vp)
    }

    fn make_synthetic_ring16_input(
        rng: &mut OsRng,
        sender_spend: Scalar,
        amount: u64,
    ) -> OutputWithDecoys {
        let key_offset = Scalar::random(rng);
        let s_d: curve25519_dalek::Scalar = sender_spend.into();
        let off_d: curve25519_dalek::Scalar = key_offset.into();
        let out_priv = s_d + off_d;
        let out_key = Point::from(&out_priv * ED25519_BASEPOINT_TABLE);
        let mask = Scalar::random(rng);
        let commitment = Commitment::new(mask, amount);

        let mut ring = Vec::with_capacity(16);
        let offsets = vec![100u64, 5, 3, 8, 2, 9, 4, 6, 11, 7, 1, 12, 3, 4, 9, 2];
        for idx in 0..16 {
            if idx == 7 {
                ring.push([out_key, commitment.commit()]);
            } else {
                let rk: curve25519_dalek::Scalar = Scalar::random(rng).into();
                let rc: curve25519_dalek::Scalar = Scalar::random(rng).into();
                ring.push([
                    Point::from(&rk * ED25519_BASEPOINT_TABLE),
                    Point::from(&rc * ED25519_BASEPOINT_TABLE),
                ]);
            }
        }
        let decoys = Decoys::new(offsets, 7, ring).unwrap();

        let mut raw = Vec::new();
        out_key.compress().write(&mut raw).unwrap();
        key_offset.write(&mut raw).unwrap();
        commitment.write(&mut raw).unwrap();
        decoys.write(&mut raw).unwrap();
        OutputWithDecoys::read(&mut raw.as_slice()).unwrap()
    }

    fn sign_real_ring16_tx(
        rng: &mut OsRng,
        recipient: MoneroAddress,
        amount_atomic: u64,
    ) -> (Transaction, Zeroizing<Scalar>, Vec<Zeroizing<Scalar>>) {
        let (sender_spend, sender_vp) = make_wallet(rng);
        let input = make_synthetic_ring16_input(rng, sender_spend, amount_atomic + 500_000_000_000);
        let input_pairs = vec![(input.key(), input.commitment().commit())];
        let mut ovk = Zeroizing::new([0u8; 32]);
        rng.fill_bytes(&mut *ovk);
        let fee_rate = FeeRate::new(20_000, 10_000).unwrap();
        let signable = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk.clone(),
            vec![input],
            vec![(recipient, amount_atomic)],
            Change::new(sender_vp, None),
            vec![],
            fee_rate,
        )
        .unwrap();
        let tx = signable.sign(rng, &Zeroizing::new(sender_spend)).unwrap();
        let (r, add) = reconstruct_tx_secret_keys(&ovk, input_pairs, &tx).unwrap();
        (tx, r, add)
    }

    #[test]
    fn outproof_v2_roundtrip_subaddress_and_tamper_rejection() {
        let mut rng = OsRng;
        let (_, merchant_vp) = make_wallet(&mut rng);
        let sub8 = merchant_vp.subaddress(Network::Mainnet, SubaddressIndex::new(0, 1).unwrap());
        let std4 = merchant_vp.legacy_address(Network::Mainnet);

        let (tx, r, add) = sign_real_ring16_tx(&mut rng, sub8, VAULT_PRICE_ATOMIC);
        let nonce = "xmr402-citadel-1700000000-0123456789abcdef";
        let proof = generate_tx_proof_v2(tx.hash(), &r, &add, sub8, nonce, &mut rng);

        let verified = verify_tx_proof_v2(&tx, sub8, nonce, &proof).unwrap();
        assert_eq!(verified, VAULT_PRICE_ATOMIC);
        assert!(verify_tx_proof_v2(&tx, sub8, "tampered-nonce", &proof).is_err());
        assert!(verify_tx_proof_v2(&tx, std4, nonce, &proof).is_err());
    }

    #[tokio::test]
    async fn full_vault_402_unlock_replay_expiry_and_swap_e2e() {
        let clock = ManualClock::new(1_760_000_000);
        let chain = InMemoryChain::new();
        let onion = "6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion";

        let state = Xmr402SwapState::with_clock(
            ServiceConfig {
                onion_host: onion.to_string(),
                prize_banner: DEFAULT_PRIZE_BANNER.to_string(),
                state_dir: None,
            },
            chain.clone(),
            clock.clone(),
        )
        .unwrap();
        let app = build_router(state.clone());

        // 1. GET /vault without Authorization -> 402 + WWW-Authenticate + zero-JS meta challenge
        let resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/vault")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::PAYMENT_REQUIRED);
        assert_eq!(
            resp.headers().get(header::CONTENT_SECURITY_POLICY).unwrap(),
            STRICT_CSP
        );
        let www_auth = resp
            .headers()
            .get(header::WWW_AUTHENTICATE)
            .unwrap()
            .to_str()
            .unwrap()
            .to_string();
        assert!(www_auth.starts_with("XMR402 "));
        assert!(www_auth.contains(&format!("daemon=\"http://{onion}:18081\"")));

        let body_str = String::from_utf8(
            to_bytes(resp.into_body(), 1024 * 1024)
                .await
                .unwrap()
                .to_vec(),
        )
        .unwrap();
        assert!(body_str.contains("HTTP 402 PAYMENT REQUIRED — 0.25 XMR"));
        assert!(body_str.contains("name=\"xmr402-challenge\""));
        assert!(body_str.contains("id=\"goto-swap-desk\""));
        assert!(!body_str.to_ascii_lowercase().contains("<script"));

        // Extract issued challenge message
        let msg_marker = "message=\"";
        let start = www_auth.find(msg_marker).unwrap() + msg_marker.len();
        let end = start + www_auth[start..].find('"').unwrap();
        let challenge_msg = www_auth[start..end].to_string();

        // 2. Underpaid transaction (0.10 XMR < 0.25 XMR) must be rejected with 402
        let mut rng = OsRng;
        let vault_subaddr = chain.vault_subaddress();
        let (under_tx, under_r, under_add) =
            sign_real_ring16_tx(&mut rng, vault_subaddr, 100_000_000_000);
        let under_txid = chain.insert_mempool_tx(under_tx.clone());
        let under_proof = generate_tx_proof_v2(
            under_tx.hash(),
            &under_r,
            &under_add,
            vault_subaddr,
            &challenge_msg,
            &mut rng,
        );
        let under_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/vault")
                    .header(
                        header::AUTHORIZATION,
                        format!(r#"XMR402 txid="{under_txid}", proof="{under_proof}""#),
                    )
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(under_resp.status(), StatusCode::PAYMENT_REQUIRED);

        // 3. Valid 0.25 XMR payment WITHOUT explicit `message=` param (matched via ring buffer!) -> 200 OK
        let (valid_tx, valid_r, valid_add) =
            sign_real_ring16_tx(&mut rng, vault_subaddr, VAULT_PRICE_ATOMIC);
        let valid_txid = chain.insert_mempool_tx(valid_tx.clone());
        let valid_proof = generate_tx_proof_v2(
            valid_tx.hash(),
            &valid_r,
            &valid_add,
            vault_subaddr,
            &challenge_msg,
            &mut rng,
        );

        let ok_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/vault")
                    .header(
                        header::AUTHORIZATION,
                        format!(r#"XMR402 txid="{valid_txid}", proof="{valid_proof}""#),
                    )
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(ok_resp.status(), StatusCode::OK);
        let ok_html = String::from_utf8(
            to_bytes(ok_resp.into_body(), 1024 * 1024)
                .await
                .unwrap()
                .to_vec(),
        )
        .unwrap();
        assert!(ok_html.contains(DEFAULT_PRIZE_BANNER));
        assert!(ok_html.contains("id=\"xmr402-prize-banner\""));
        assert!(ok_html.contains("id=\"xmr402-prize-json\""));

        // 4. Replaying the exact same txid MUST fail with 402
        let replay_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/vault")
                    .header(
                        header::AUTHORIZATION,
                        format!(r#"XMR402 txid="{valid_txid}", proof="{valid_proof}""#),
                    )
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(replay_resp.status(), StatusCode::PAYMENT_REQUIRED);

        // 5. Advance clock past 20m TTL -> old challenge nonce expires even on a brand-new tx!
        clock.advance(CHALLENGE_TTL_SECS + 5);
        let (late_tx, late_r, late_add) =
            sign_real_ring16_tx(&mut rng, vault_subaddr, VAULT_PRICE_ATOMIC);
        let late_txid = chain.insert_mempool_tx(late_tx.clone());
        let late_proof = generate_tx_proof_v2(
            late_tx.hash(),
            &late_r,
            &late_add,
            vault_subaddr,
            &challenge_msg,
            &mut rng,
        );
        let expired_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/vault")
                    .header(
                        header::AUTHORIZATION,
                        format!(
                            r#"XMR402 txid="{late_txid}", proof="{late_proof}", message="{challenge_msg}""#
                        ),
                    )
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(expired_resp.status(), StatusCode::PAYMENT_REQUIRED);

        // 6. Full Zero-JS BTC -> XMR Swap flow (/swap -> POST /swap/order -> GET /swap/order/:id -> POST /fund-btc)
        let (_, client_vp) = make_wallet(&mut rng);
        let client_sub8 = client_vp
            .subaddress(Network::Mainnet, SubaddressIndex::new(0, 1).unwrap())
            .to_string();

        let swap_page = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/swap")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(swap_page.status(), StatusCode::OK);

        let form_body = serde_urlencoded::to_string([
            ("btc_amount", "0.01"),
            ("payout_addr", client_sub8.as_str()),
        ])
        .unwrap();
        let create_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/swap/order")
                    .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                    .body(axum::body::Body::from(form_body))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(create_resp.status(), StatusCode::SEE_OTHER);
        let order_loc = create_resp
            .headers()
            .get(header::LOCATION)
            .unwrap()
            .to_str()
            .unwrap()
            .to_string();
        assert!(order_loc.starts_with("/swap/order/"));

        let awaiting_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(&order_loc)
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(awaiting_resp.status(), StatusCode::OK);
        let awaiting_html = String::from_utf8(
            to_bytes(awaiting_resp.into_body(), 1024 * 1024)
                .await
                .unwrap()
                .to_vec(),
        )
        .unwrap();
        assert!(awaiting_html.contains("data-status=\"awaiting_btc_deposit\""));
        assert!(awaiting_html.contains("id=\"deposit_address\""));
        assert!(awaiting_html.contains("id=\"amount_a\">0.01<"));
        assert!(awaiting_html.contains("id=\"fund-btc-btn\""));

        let fund_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri(format!("{order_loc}/fund-btc"))
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(fund_resp.status(), StatusCode::SEE_OTHER);

        let done_resp = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(&order_loc)
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        let done_html = String::from_utf8(
            to_bytes(done_resp.into_body(), 1024 * 1024)
                .await
                .unwrap()
                .to_vec(),
        )
        .unwrap();
        assert!(done_html.contains("data-status=\"completed\""));
        assert!(done_html.contains("id=\"return-to-vault\""));
        assert!(done_html.contains("id=\"xmr-payout-txid\""));

        // 7. GET /healthz
        let hz_resp = app
            .oneshot(
                Request::builder()
                    .uri("/healthz")
                    .body(axum::body::Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(hz_resp.status(), StatusCode::OK);
    }

    #[test]
    fn loopback_endpoint_and_btc_decimal_parsers_enforce_invariants() {
        use crate::rpc::{parse_decimal_btc_to_sats, parse_loopback_http_endpoint};

        assert!(parse_loopback_http_endpoint("http://127.0.0.1:18082").is_ok());
        assert!(parse_loopback_http_endpoint("http://[::1]:18443/wallet/faucet").is_ok());
        assert!(parse_loopback_http_endpoint("http://8.8.8.8:18082").is_err());
        assert!(parse_loopback_http_endpoint("http://0.0.0.0:18082").is_err());
        assert!(parse_loopback_http_endpoint("https://127.0.0.1:18082").is_err());

        assert_eq!(parse_decimal_btc_to_sats("0.01").unwrap(), 1_000_000);
        assert_eq!(parse_decimal_btc_to_sats("0.01000000").unwrap(), 1_000_000);
        assert_eq!(parse_decimal_btc_to_sats("1").unwrap(), 100_000_000);
        assert!(parse_decimal_btc_to_sats("-0.01").is_err());
        assert!(parse_decimal_btc_to_sats("abc").is_err());
    }

    #[test]
    fn authorization_parser_and_challenge_ring_buffer_bounds() {
        let txid = "ab".repeat(32);
        let proof = format!("OutProofV2{}", "1".repeat(132));
        let parsed = parse_xmr402_authorization(&format!(
            r#"XMR402 txid="{txid}", proof="{proof}", message="xmr402-citadel-100-deadbeefcafebabe""#
        ))
        .unwrap();
        assert_eq!(parsed.txid, txid);
        assert_eq!(parsed.proof, proof);
        assert_eq!(
            parsed.message.as_deref(),
            Some("xmr402-citadel-100-deadbeefcafebabe")
        );

        let parsed2 =
            parse_xmr402_authorization(&format!("xmr402 txid={txid} proof={proof}")).unwrap();
        assert_eq!(parsed2.txid, txid);
        assert!(parsed2.message.is_none());

        assert!(parse_xmr402_authorization("Bearer token").is_err());
        assert!(parse_xmr402_authorization(&format!("XMR402 txid=short, proof={proof}")).is_err());

        let clock = ManualClock::new(2_000_000_000);
        let chain = InMemoryChain::new();
        let state = Xmr402SwapState::with_clock(
            ServiceConfig {
                onion_host: "6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion"
                    .to_string(),
                prize_banner: DEFAULT_PRIZE_BANNER.to_string(),
                state_dir: None,
            },
            chain,
            clock.clone(),
        )
        .unwrap();

        for _ in 0..(MAX_CHALLENGE_RING + 50) {
            clock.advance(1);
            let _ = state.issue_challenge();
        }
        let snap = state.snapshot_active_challenges_newest_first(clock.now_unix());
        assert!(snap.len() <= MAX_CHALLENGE_RING);
    }

    #[test]
    fn wallet_seed_persistence_creates_0600_and_reloads_deterministically() {
        use crate::wallet_engine::{derive_hot_wallet_keys, load_or_generate_wallet_seed};

        let tmp_dir = std::env::temp_dir().join(format!(
            "trap-xmr402-seed-test-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let s1 = load_or_generate_wallet_seed(&tmp_dir).unwrap();
        let s2 = load_or_generate_wallet_seed(&tmp_dir).unwrap();
        assert_eq!(*s1, *s2);

        let (_, _, p1, v1) = derive_hot_wallet_keys(&s1).unwrap();
        assert!(p1.to_string().starts_with('4'));
        assert!(v1.to_string().starts_with('8'));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt as _;
            let mode = fs::metadata(tmp_dir.join("wallet_seed.hex"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(mode, 0o600);
        }
        let _ = fs::remove_dir_all(&tmp_dir);
    }
}
