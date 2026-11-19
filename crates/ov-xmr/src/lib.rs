//! Pure-Rust Monero light wallet, RingCT CLSAG+Bulletproof+ signer, and
//! Tor-routed zero-JavaScript BTC -> XMR swap aggregator for Trap.

pub mod mnemonic;
pub mod qr;
pub mod store;
pub mod swap;
pub mod sync;
pub mod transport;
pub mod tx_proof;
pub mod uri;
pub mod wallet;
pub mod xmr402;

use std::{
    net::SocketAddr,
    path::Path,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, RwLock,
    },
    time::Duration,
};

use anyhow::{anyhow, bail, Result};
use monero_wallet::WalletOutput;
use ov_pgp::{zeroize_slice, MasterKeyProvider};
use ov_tor::is_valid_v3_onion_host;
use serde::{Deserialize, Serialize};
use url::Url;
use zeroize::Zeroizing;

pub use mnemonic::{decode_mnemonic, encode_mnemonic, english_words, generate_spend_key};
pub use qr::{encode_qr_matrix, encode_qr_matrix_json, QrMatrixResult, MAX_QR_PAYLOAD_BYTES};
pub use store::{
    StoredOutput, StoredSubaddress, StoredSwap, StoredTransaction, StoredTxSecret,
    StoredWalletMeta, XmrStore, DEFAULT_MONEROD_ONION_POOL, SYNC_MODE_LWS, SYNC_MODE_MONEROD_ONION,
};
pub use swap::{
    fetch_btc_xmr_quotes, fetch_btc_xmr_quotes_from_base, normalize_btc_amount_decimal,
    parse_orangefren_quotes_html, BtcSwapQuoteItem, ORANGEFREN_ONION_BASE,
};
pub use sync::{is_output_unlocked, LWS_FORBIDDEN_ERROR_MSG, UNLOCK_CONFIRMATIONS};
pub use transport::{HttpResponse, MoneroRpcTransport, TorSocksTransport};
pub use tx_proof::{
    decode_tx_secret_material, encode_tx_secret_material, format_xmr402_authorization_header,
    generate_tx_proof_v2, reconstruct_tx_secret_keys, reconstruct_tx_secret_keys_from_extra,
    verify_tx_proof_v2, OUTPROOF_V2_HEADER,
};
pub use uri::{
    format_atomic_xmr, format_monero_uri, parse_amount_decimal_or_atomic, parse_mainnet_address,
    parse_monero_uri, parse_xmr_to_atomic, MoneroUri, PICONERO_PER_XMR,
};
pub use wallet::{
    compute_key_image, derive_subaddress, derive_wallet_from_spend, reconstruct_view_pair,
};
pub use xmr402::{
    parse_xmr402_challenge, parse_xmr402_challenge_json, Xmr402ChallengeResult, Xmr402PayResult,
    XmrTxProofResult,
};

static SWAP_COUNTER: AtomicU64 = AtomicU64::new(1);

// ============================================================================
// Exact `OvNative` JSON Contract Models (`camelCase`)
// ============================================================================

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrSubaddressItem {
    pub index: u32,
    pub label: String,
    pub address: String,
    pub received_atomic: String,
    pub received_xmr: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrTxItem {
    pub txid: String,
    pub direction: String,
    pub amount_atomic: String,
    pub amount_xmr: String,
    pub fee_atomic: String,
    pub fee_xmr: String,
    pub height: u64,
    pub timestamp: u64,
    pub subaddress_index: u32,
    pub unlocked: bool,
    #[serde(default)]
    pub has_proof: bool,
    #[serde(default)]
    pub recipient_address: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackedBtcXmrSwap {
    pub swap_id: String,
    pub provider: String,
    pub btc_amount: String,
    pub quoted_xmr: String,
    pub launch_url: String,
    pub subaddress_index: u32,
    pub subaddress: String,
    pub btc_deposit_address: String,
    pub btc_deposit_amount: String,
    pub provider_order_id: String,
    pub order_url: String,
    pub status: String,
    pub payout_txid: String,
    pub received_atomic: String,
    pub received_xmr: String,
    pub created_epoch_secs: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrStatusSnapshot {
    pub ok: bool,
    pub has_wallet: bool,
    pub wallet_label: String,
    pub primary_address: String,
    pub active_subaddress: String,
    pub active_subaddress_index: u32,
    pub balance_atomic: String,
    pub unlocked_balance_atomic: String,
    pub balance_xmr: String,
    pub unlocked_balance_xmr: String,
    pub restore_height: u64,
    pub synced_height: u64,
    pub daemon_height: u64,
    pub birthday_pending: bool,
    pub sync_endpoint: String,
    pub sync_mode: String,
    pub last_sync_epoch_secs: u64,
    pub subaddresses: Vec<XmrSubaddressItem>,
    pub recent_txs: Vec<XmrTxItem>,
    pub swaps: Vec<TrackedBtcXmrSwap>,
    pub error: String,
}

impl XmrStatusSnapshot {
    pub fn uninitialized(error: impl Into<String>) -> Self {
        Self {
            ok: false,
            has_wallet: false,
            wallet_label: String::new(),
            primary_address: String::new(),
            active_subaddress: String::new(),
            active_subaddress_index: 0,
            balance_atomic: "0".to_string(),
            unlocked_balance_atomic: "0".to_string(),
            balance_xmr: "0".to_string(),
            unlocked_balance_xmr: "0".to_string(),
            restore_height: 0,
            synced_height: 0,
            daemon_height: 0,
            birthday_pending: false,
            sync_endpoint: DEFAULT_MONEROD_ONION_POOL[0].to_string(),
            sync_mode: SYNC_MODE_MONEROD_ONION.to_string(),
            last_sync_epoch_secs: 0,
            subaddresses: Vec::new(),
            recent_txs: Vec::new(),
            swaps: Vec::new(),
            error: error.into(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrSeedResult {
    pub ok: bool,
    pub mnemonic: String,
    pub primary_address: String,
    pub active_subaddress: String,
    pub active_subaddress_index: u32,
    pub view_key_hex: String,
    pub restore_height: u64,
    pub error: String,
}

impl XmrSeedResult {
    pub fn err(msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            mnemonic: String::new(),
            primary_address: String::new(),
            active_subaddress: String::new(),
            active_subaddress_index: 0,
            view_key_hex: String::new(),
            restore_height: 0,
            error: msg.into(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrNewSubaddressResult {
    pub ok: bool,
    pub index: u32,
    pub label: String,
    pub address: String,
    pub error: String,
}

impl XmrNewSubaddressResult {
    pub fn err(msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            index: 0,
            label: String::new(),
            address: String::new(),
            error: msg.into(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrSendResult {
    pub ok: bool,
    pub txid: String,
    pub fee_atomic: String,
    pub fee_xmr: String,
    pub amount_atomic: String,
    pub amount_xmr: String,
    pub raw_tx_bytes: usize,
    pub error: String,
}

impl XmrSendResult {
    pub fn err(msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            txid: String::new(),
            fee_atomic: "0".to_string(),
            fee_xmr: "0".to_string(),
            amount_atomic: "0".to_string(),
            amount_xmr: "0".to_string(),
            raw_tx_bytes: 0,
            error: msg.into(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BtcSwapQuotesResult {
    pub ok: bool,
    pub btc_amount: String,
    pub source_onion: String,
    pub fetched_at_epoch_secs: u64,
    pub quotes: Vec<BtcSwapQuoteItem>,
    pub error: String,
}

impl BtcSwapQuotesResult {
    pub fn err(btc_amount: &str, msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            btc_amount: btc_amount.trim().to_string(),
            source_onion: ORANGEFREN_ONION_BASE.to_string(),
            fetched_at_epoch_secs: 0,
            quotes: Vec::new(),
            error: msg.into(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrSwapsActionResult {
    pub ok: bool,
    pub swap: Option<TrackedBtcXmrSwap>,
    pub swaps: Vec<TrackedBtcXmrSwap>,
    pub error: String,
}

impl XmrSwapsActionResult {
    pub fn err(swaps: Vec<TrackedBtcXmrSwap>, msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            swap: None,
            swaps,
            error: msg.into(),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SwapActionRequest {
    op: String,
    #[serde(default)]
    swap_id: String,
    #[serde(default)]
    provider: String,
    #[serde(default)]
    btc_amount: String,
    #[serde(default)]
    quoted_xmr: String,
    #[serde(default)]
    launch_url: String,
    #[serde(default)]
    btc_deposit_address: String,
    #[serde(default)]
    btc_deposit_amount: String,
    #[serde(default)]
    provider_order_id: String,
    #[serde(default)]
    order_url: String,
    #[serde(default)]
    status: String,
}

// ============================================================================
// Coordinated `XmrService`
// ============================================================================

/// High-level Monero light wallet and swap manager bound to `xmr/wallet.db`
/// and Trap's `MasterKeyProvider`.
pub struct XmrService {
    store: XmrStore,
    master: RwLock<Arc<dyn MasterKeyProvider>>,
}

impl XmrService {
    /// Open or create `wallet.db` protected by `master`.
    pub fn open(db_path: &Path, master: Arc<dyn MasterKeyProvider>) -> Result<Self> {
        let store = XmrStore::open(db_path)?;
        Ok(Self {
            store,
            master: RwLock::new(master),
        })
    }

    fn master(&self) -> Arc<dyn MasterKeyProvider> {
        match self.master.read() {
            Ok(g) => Arc::clone(&g),
            Err(p) => Arc::clone(&p.into_inner()),
        }
    }

    /// Underlying SQLite store.
    pub fn store(&self) -> &XmrStore {
        &self.store
    }

    /// Re-wrap `wrapped_spend_key` when `nativeSetRequireBiometric` rotates the Keystore key.
    pub fn rotate_master_key(&self, new_master: Arc<dyn MasterKeyProvider>) -> Result<bool> {
        let mut guard = match self.master.write() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        let rotated = self
            .store
            .rewrap_spend_key(guard.as_ref(), new_master.as_ref())?;
        *guard = new_master;
        Ok(rotated)
    }

    /// Build a live [`XmrStatusSnapshot`] from persisted SQLite state.
    pub fn status_snapshot(&self) -> XmrStatusSnapshot {
        match self.build_status_inner() {
            Ok(mut snap) => {
                snap.ok = true;
                snap
            }
            Err(err) => XmrStatusSnapshot::uninitialized(err.to_string()),
        }
    }

    fn build_status_inner(&self) -> Result<XmrStatusSnapshot> {
        let (cfg_ep, cfg_mode) = self.store.configured_endpoint_and_mode()?;
        let Some(meta) = self.store.wallet_meta()? else {
            let effective_ep = if cfg_ep.is_empty() && cfg_mode == SYNC_MODE_MONEROD_ONION {
                DEFAULT_MONEROD_ONION_POOL[0].to_string()
            } else {
                cfg_ep
            };
            return Ok(XmrStatusSnapshot {
                ok: true,
                has_wallet: false,
                wallet_label: String::new(),
                primary_address: String::new(),
                active_subaddress: String::new(),
                active_subaddress_index: 0,
                balance_atomic: "0".to_string(),
                unlocked_balance_atomic: "0".to_string(),
                balance_xmr: "0".to_string(),
                unlocked_balance_xmr: "0".to_string(),
                restore_height: 0,
                synced_height: 0,
                daemon_height: 0,
                birthday_pending: false,
                sync_endpoint: effective_ep,
                sync_mode: cfg_mode,
                last_sync_epoch_secs: 0,
                subaddresses: Vec::new(),
                recent_txs: Vec::new(),
                swaps: Vec::new(),
                error: String::new(),
            });
        };

        let subs = self.store.list_subaddresses()?;
        let outputs = self.store.list_outputs()?;
        let txs = self.store.list_recent_transactions(50)?;
        let tx_secret_recipients = self.store.list_tx_secret_recipients()?;
        let swaps = self.store.list_swaps()?;
        let now_secs = sync::now_epoch_secs();
        let effective_tip = meta.daemon_height.max(meta.synced_height);

        let mut total_balance: u64 = 0;
        let mut unlocked_balance: u64 = 0;
        for out in &outputs {
            if out.spent_in_txid.is_some() {
                continue;
            }
            total_balance = total_balance.saturating_add(out.amount_atomic);
            let timelock = if out.output_blob.is_empty() {
                monero_wallet::transaction::Timelock::None
            } else {
                WalletOutput::read(&mut out.output_blob.as_slice())
                    .map(|wo| wo.additional_timelock())
                    .unwrap_or(monero_wallet::transaction::Timelock::None)
            };
            if is_output_unlocked(out.height, effective_tip, timelock, now_secs) {
                unlocked_balance = unlocked_balance.saturating_add(out.amount_atomic);
            }
        }

        // Exact conservation of Pedersen commitments for 0-conf (`height == 0`) outgoing
        // transactions whose change output has not yet been scanned in a mined block:
        // `change_atomic = sum(inputs_spent_by_tx) - amount_atomic - fee_atomic` (locked until 10 confs).
        for t in &txs {
            if t.direction == "out"
                && t.height == 0
                && !outputs.iter().any(|o| o.txid.eq_ignore_ascii_case(&t.txid))
            {
                let spent_by_tx: u64 = outputs
                    .iter()
                    .filter(|o| {
                        o.spent_in_txid
                            .as_deref()
                            .is_some_and(|stx| stx.eq_ignore_ascii_case(&t.txid))
                    })
                    .map(|o| o.amount_atomic)
                    .fold(0u64, u64::saturating_add);
                let pending_change = spent_by_tx
                    .saturating_sub(t.amount_atomic)
                    .saturating_sub(t.fee_atomic);
                total_balance = total_balance.saturating_add(pending_change);
            }
        }

        let sub_items: Vec<XmrSubaddressItem> = subs
            .iter()
            .map(|s| {
                let rec: u64 = outputs
                    .iter()
                    .filter(|o| o.subaddress_minor == s.minor_index)
                    .map(|o| o.amount_atomic)
                    .fold(0u64, u64::saturating_add);
                XmrSubaddressItem {
                    index: s.minor_index,
                    label: s.label.clone(),
                    address: s.address.clone(),
                    received_atomic: rec.to_string(),
                    received_xmr: format_atomic_xmr(rec),
                }
            })
            .collect();

        let active_sub = sub_items
            .iter()
            .find(|s| s.index == meta.active_subaddress_index)
            .or_else(|| sub_items.last())
            .map(|s| (s.index, s.address.clone()))
            .unwrap_or((0, String::new()));

        let tx_items: Vec<XmrTxItem> = txs
            .into_iter()
            .map(|t| {
                let unlocked = t.height > 0
                    && effective_tip >= t.height.saturating_add(UNLOCK_CONFIRMATIONS - 1);
                let rcpt_opt = tx_secret_recipients.get(&t.txid.to_ascii_lowercase());
                let has_proof = rcpt_opt.is_some();
                let recipient_address = rcpt_opt.cloned().unwrap_or_default();
                XmrTxItem {
                    txid: t.txid,
                    direction: t.direction,
                    amount_atomic: t.amount_atomic.to_string(),
                    amount_xmr: format_atomic_xmr(t.amount_atomic),
                    fee_atomic: t.fee_atomic.to_string(),
                    fee_xmr: format_atomic_xmr(t.fee_atomic),
                    height: t.height,
                    timestamp: t.timestamp,
                    subaddress_index: t.subaddress_minor,
                    unlocked,
                    has_proof,
                    recipient_address,
                }
            })
            .collect();

        let swap_items: Vec<TrackedBtcXmrSwap> = swaps.into_iter().map(map_tracked_swap).collect();
        let display_ep =
            if meta.sync_endpoint.is_empty() && meta.sync_mode == SYNC_MODE_MONEROD_ONION {
                DEFAULT_MONEROD_ONION_POOL[0].to_string()
            } else {
                meta.sync_endpoint
            };

        Ok(XmrStatusSnapshot {
            ok: true,
            has_wallet: true,
            wallet_label: meta.wallet_label,
            primary_address: meta.primary_address,
            active_subaddress: active_sub.1,
            active_subaddress_index: active_sub.0,
            balance_atomic: total_balance.to_string(),
            unlocked_balance_atomic: unlocked_balance.to_string(),
            balance_xmr: format_atomic_xmr(total_balance),
            unlocked_balance_xmr: format_atomic_xmr(unlocked_balance),
            restore_height: meta.restore_height,
            synced_height: meta.synced_height,
            daemon_height: meta.daemon_height,
            birthday_pending: meta.birthday_pending,
            sync_endpoint: display_ep,
            sync_mode: meta.sync_mode,
            last_sync_epoch_secs: meta.last_sync_epoch_secs,
            subaddresses: sub_items,
            recent_txs: tx_items,
            swaps: swap_items,
            error: String::new(),
        })
    }

    /// Create a fresh 25-word Monero wallet (`nativeXmrCreateWalletJson`).
    /// If Tor is bootstrapped, queries `latest_block_number()` to snap `restore_height = tip`;
    /// otherwise sets `birthday_pending = true` and `restore_height = 0` so the first `sync()`
    /// snaps to chain tip.
    pub async fn create_wallet(
        &self,
        label: &str,
        socks_addr: Option<SocketAddr>,
        tor_ready: bool,
        hidden_service_only: bool,
    ) -> XmrSeedResult {
        let run = async {
            let clean_label = if label.trim().is_empty() {
                "Trap Light Wallet"
            } else {
                label.trim()
            };
            let spend_bytes = generate_spend_key();
            let mnemonic = encode_mnemonic(&spend_bytes)?;
            let derived = derive_wallet_from_spend(&spend_bytes)?;
            let sub1 = derive_subaddress(&derived.view_pair, 1)?.to_string();
            let wrapped = self.master().wrap(&*spend_bytes)?;

            let (cfg_ep, cfg_mode) = self.store.configured_endpoint_and_mode()?;
            let tip_opt = if tor_ready && socks_addr.is_some() {
                let transport = TorSocksTransport::new(
                    socks_addr,
                    true,
                    hidden_service_only,
                    Some("ov-xmr-rpc".to_string()),
                )
                .with_timeout(Duration::from_secs(15));
                sync::try_fetch_tip_for_new_wallet(&transport, &cfg_ep, &cfg_mode).await
            } else {
                None
            };

            let (restore_height, birthday_pending, synced_height, daemon_height) = match tip_opt {
                Some(tip) if tip <= 100 => (1.min(tip), false, 0, tip),
                Some(tip) => (tip, false, tip, tip),
                None => (0, true, 0, 0),
            };

            self.store.save_new_wallet(
                clean_label,
                &derived.primary_address,
                &wrapped,
                &*derived.view_key_bytes,
                &sub1,
                restore_height,
                birthday_pending,
                synced_height,
                daemon_height,
                &cfg_ep,
                &cfg_mode,
            )?;

            Ok::<XmrSeedResult, anyhow::Error>(XmrSeedResult {
                ok: true,
                mnemonic: mnemonic.to_string(),
                primary_address: derived.primary_address,
                active_subaddress: sub1,
                active_subaddress_index: 1,
                view_key_hex: derived.view_key_hex.to_string(),
                restore_height,
                error: String::new(),
            })
        };

        match run.await {
            Ok(res) => res,
            Err(err) => XmrSeedResult::err(err.to_string()),
        }
    }

    /// Restore a 25-word Monero wallet (`nativeXmrRestoreWalletJson`).
    pub fn restore_wallet(&self, mnemonic_phrase: &str, restore_height: i64) -> XmrSeedResult {
        let run = || -> Result<XmrSeedResult> {
            let spend_bytes = decode_mnemonic(mnemonic_phrase)?;
            let canonical_mnemonic = encode_mnemonic(&spend_bytes)?;
            let derived = derive_wallet_from_spend(&spend_bytes)?;
            let sub1 = derive_subaddress(&derived.view_pair, 1)?.to_string();
            let wrapped = self.master().wrap(&*spend_bytes)?;
            let restore_u64 = u64::try_from(restore_height.max(0)).unwrap_or(0);
            let initial_synced = restore_u64.saturating_sub(1);
            let (cfg_ep, cfg_mode) = self.store.configured_endpoint_and_mode()?;

            self.store.save_new_wallet(
                "Restored Light Wallet",
                &derived.primary_address,
                &wrapped,
                &*derived.view_key_bytes,
                &sub1,
                restore_u64,
                false,
                initial_synced,
                0,
                &cfg_ep,
                &cfg_mode,
            )?;

            Ok(XmrSeedResult {
                ok: true,
                mnemonic: canonical_mnemonic.to_string(),
                primary_address: derived.primary_address,
                active_subaddress: sub1,
                active_subaddress_index: 1,
                view_key_hex: derived.view_key_hex.to_string(),
                restore_height: restore_u64,
                error: String::new(),
            })
        };

        match run() {
            Ok(res) => res,
            Err(err) => XmrSeedResult::err(err.to_string()),
        }
    }

    /// Export the 25-word mnemonic of the stored wallet (`nativeXmrExportSeedJson`).
    pub fn export_seed(&self) -> XmrSeedResult {
        let run = || -> Result<XmrSeedResult> {
            let meta = self
                .store
                .wallet_meta()?
                .ok_or_else(|| anyhow!("No Monero wallet created yet"))?;
            let mut unwrapped = self.master().unwrap(&meta.wrapped_spend_key)?;
            let arr_res = <[u8; 32]>::try_from(unwrapped.as_slice())
                .map_err(|_| anyhow!("stored spend key has invalid length"));
            let spend_bytes = Zeroizing::new(match arr_res {
                Ok(a) => {
                    zeroize_slice(&mut unwrapped);
                    a
                }
                Err(e) => {
                    zeroize_slice(&mut unwrapped);
                    return Err(e);
                }
            });
            let mnemonic = encode_mnemonic(&spend_bytes)?;
            let subs = self.store.list_subaddresses()?;
            let active = subs
                .iter()
                .find(|s| s.minor_index == meta.active_subaddress_index)
                .or_else(|| subs.last())
                .map(|s| (s.minor_index, s.address.clone()))
                .unwrap_or((1, String::new()));

            Ok(XmrSeedResult {
                ok: true,
                mnemonic: mnemonic.to_string(),
                primary_address: meta.primary_address,
                active_subaddress: active.1,
                active_subaddress_index: active.0,
                view_key_hex: hex::encode(&meta.view_key_bytes),
                restore_height: meta.restore_height,
                error: String::new(),
            })
        };

        match run() {
            Ok(res) => res,
            Err(err) => XmrSeedResult::err(err.to_string()),
        }
    }

    /// Delete the wallet and all associated records (`nativeXmrDeleteWallet`).
    pub fn delete_wallet(&self) -> bool {
        self.store.delete_wallet().unwrap_or(false)
    }

    /// Mint a fresh `(0, minor)` `8...` subaddress (`nativeXmrNewSubaddressJson`).
    /// When `label` starts with `"site:"` (e.g. `"site:wizardswgtu2...onion"`), reuses any
    /// existing subaddress with that label idempotently (setting it active) so per-origin
    /// subaddress isolation stays deterministic across page visits.
    pub fn new_subaddress(&self, label: &str) -> XmrNewSubaddressResult {
        let run = || -> Result<XmrNewSubaddressResult> {
            let meta = self
                .store
                .wallet_meta()?
                .ok_or_else(|| anyhow!("No Monero wallet created yet"))?;
            let trimmed_label = label.trim();
            if trimmed_label.len() > 5 && trimmed_label[..5].eq_ignore_ascii_case("site:") {
                let site_suffix = trimmed_label[5..].trim();
                if !site_suffix.is_empty() {
                    let canonical_site_label = format!("site:{}", site_suffix.to_ascii_lowercase());
                    if let Some(existing) = self
                        .store
                        .find_subaddress_by_label_ci(&canonical_site_label)?
                    {
                        self.store.set_active_subaddress(existing.minor_index)?;
                        return Ok(XmrNewSubaddressResult {
                            ok: true,
                            index: existing.minor_index,
                            label: existing.label,
                            address: existing.address,
                            error: String::new(),
                        });
                    }
                    let vp = reconstruct_view_pair(&meta.primary_address, &meta.view_key_bytes)?;
                    let next_minor = self
                        .store
                        .max_subaddress_minor()?
                        .checked_add(1)
                        .ok_or_else(|| anyhow!("subaddress index overflow"))?;
                    let addr = derive_subaddress(&vp, next_minor)?.to_string();
                    self.store
                        .insert_subaddress(next_minor, &canonical_site_label, &addr, true)?;
                    return Ok(XmrNewSubaddressResult {
                        ok: true,
                        index: next_minor,
                        label: canonical_site_label,
                        address: addr,
                        error: String::new(),
                    });
                }
            }

            let vp = reconstruct_view_pair(&meta.primary_address, &meta.view_key_bytes)?;
            let next_minor = self
                .store
                .max_subaddress_minor()?
                .checked_add(1)
                .ok_or_else(|| anyhow!("subaddress index overflow"))?;
            let clean_label = if trimmed_label.is_empty() {
                format!("Subaddress #{next_minor}")
            } else {
                trimmed_label.to_string()
            };
            let addr = derive_subaddress(&vp, next_minor)?.to_string();
            self.store
                .insert_subaddress(next_minor, &clean_label, &addr, true)?;
            Ok(XmrNewSubaddressResult {
                ok: true,
                index: next_minor,
                label: clean_label,
                address: addr,
                error: String::new(),
            })
        };

        match run() {
            Ok(res) => res,
            Err(err) => XmrNewSubaddressResult::err(err.to_string()),
        }
    }

    /// Configure custom `.onion` `monerod` or `monero-lws` endpoint (`nativeXmrSetDaemonEndpointJson`).
    pub fn set_daemon_endpoint(
        &self,
        endpoint_url: &str,
        mode: &str,
        hidden_service_only: bool,
    ) -> XmrStatusSnapshot {
        let validate_and_set = || -> Result<()> {
            let norm_mode = match mode.trim().to_ascii_lowercase().as_str() {
                "" | "monerod_onion" | "monerod" | "daemon" | "onion" => SYNC_MODE_MONEROD_ONION,
                "lws" | "monero_lws" | "monero-lws" => SYNC_MODE_LWS,
                other => {
                    bail!("unsupported XMR sync mode {other:?}; expected 'monerod_onion' or 'lws'")
                }
            };

            let trimmed_ep = endpoint_url.trim();
            if trimmed_ep.is_empty() {
                if norm_mode == SYNC_MODE_LWS {
                    bail!("LWS sync mode requires a non-empty endpoint URL");
                }
                self.store.set_endpoint_and_mode("", norm_mode)?;
                return Ok(());
            }

            let parsed = Url::parse(trimmed_ep)
                .map_err(|e| anyhow!("invalid daemon endpoint URL {trimmed_ep:?}: {e}"))?;
            if !matches!(parsed.scheme(), "http" | "https") {
                bail!("daemon endpoint must use http:// or https:// scheme");
            }
            let host = parsed
                .host_str()
                .ok_or_else(|| anyhow!("daemon endpoint URL is missing host"))?
                .to_ascii_lowercase();
            if hidden_service_only && !is_valid_v3_onion_host(&host) {
                bail!(
                    "Hidden-service-only mode is active: daemon endpoint must be a v3 .onion URL"
                );
            }

            let clean_ep = parsed.as_str().trim_end_matches('/').to_string();
            self.store.set_endpoint_and_mode(&clean_ep, norm_mode)?;
            Ok(())
        };

        match validate_and_set() {
            Ok(()) => self.status_snapshot(),
            Err(err) => {
                let mut snap = self.status_snapshot();
                snap.ok = false;
                snap.error = err.to_string();
                snap
            }
        }
    }

    /// Synchronize wallet over Trap's loopback Tor SOCKS5h proxy (`nativeXmrSyncJson`).
    pub async fn sync(
        &self,
        socks_addr: Option<SocketAddr>,
        tor_ready: bool,
        hidden_service_only: bool,
    ) -> XmrStatusSnapshot {
        let transport = TorSocksTransport::new(
            socks_addr,
            tor_ready,
            hidden_service_only,
            Some("ov-xmr-rpc".to_string()),
        );
        let master = self.master();
        match sync::sync_wallet(&self.store, master.as_ref(), &transport).await {
            Ok(()) => self.status_snapshot(),
            Err(err) => {
                let mut snap = self.status_snapshot();
                snap.ok = false;
                snap.error = err.to_string();
                snap
            }
        }
    }

    /// Build, sign, and broadcast an outgoing Ring-16 CLSAG + Bulletproof+ transaction (`nativeXmrSendJson`).
    pub async fn send(
        &self,
        socks_addr: Option<SocketAddr>,
        tor_ready: bool,
        hidden_service_only: bool,
        destination_address: &str,
        amount_decimal_or_atomic: &str,
        sweep_all: bool,
    ) -> XmrSendResult {
        let transport = TorSocksTransport::new(
            socks_addr,
            tor_ready,
            hidden_service_only,
            Some("ov-xmr-rpc".to_string()),
        );
        let master = self.master();
        match sync::send_xmr(
            &self.store,
            master.as_ref(),
            &transport,
            destination_address,
            amount_decimal_or_atomic,
            sweep_all,
        )
        .await
        {
            Ok(outcome) => XmrSendResult {
                ok: true,
                txid: outcome.txid,
                fee_atomic: outcome.fee_atomic.to_string(),
                fee_xmr: format_atomic_xmr(outcome.fee_atomic),
                amount_atomic: outcome.amount_atomic.to_string(),
                amount_xmr: format_atomic_xmr(outcome.amount_atomic),
                raw_tx_bytes: outcome.raw_tx_bytes,
                error: String::new(),
            },
            Err(err) => XmrSendResult::err(err.to_string()),
        }
    }

    /// Fetch live zero-JS BTC -> XMR swap quotes from OrangeFren `.onion` over Tor (`nativeXmrFetchBtcSwapQuotesJson`).
    pub async fn fetch_btc_swap_quotes(
        &self,
        socks_addr: Option<SocketAddr>,
        tor_ready: bool,
        hidden_service_only: bool,
        btc_amount_decimal: &str,
    ) -> BtcSwapQuotesResult {
        self.fetch_btc_swap_quotes_with_base(
            socks_addr,
            tor_ready,
            hidden_service_only,
            ORANGEFREN_ONION_BASE,
            btc_amount_decimal,
        )
        .await
    }

    /// Fetch live zero-JS BTC -> XMR swap quotes from `base_url` over Tor.
    pub async fn fetch_btc_swap_quotes_with_base(
        &self,
        socks_addr: Option<SocketAddr>,
        tor_ready: bool,
        hidden_service_only: bool,
        base_url: &str,
        btc_amount_decimal: &str,
    ) -> BtcSwapQuotesResult {
        let transport = TorSocksTransport::new(
            socks_addr,
            tor_ready,
            hidden_service_only,
            Some("ov-xmr-swap".to_string()),
        );
        match fetch_btc_xmr_quotes_from_base(
            &transport,
            base_url,
            btc_amount_decimal,
            hidden_service_only,
        )
        .await
        {
            Ok((clean_btc, quotes)) => BtcSwapQuotesResult {
                ok: true,
                btc_amount: clean_btc,
                source_onion: base_url.to_string(),
                fetched_at_epoch_secs: sync::now_epoch_secs(),
                quotes,
                error: String::new(),
            },
            Err(err) => BtcSwapQuotesResult::err(btc_amount_decimal, err.to_string()),
        }
    }

    /// Manage persisted BTC -> XMR swaps (`nativeXmrSwapsActionJson`: `list`, `create`, `update_order`, `dismiss`).
    pub fn swaps_action(&self, action_json: &str) -> XmrSwapsActionResult {
        let current_swaps = || -> Vec<TrackedBtcXmrSwap> {
            self.store
                .list_swaps()
                .unwrap_or_default()
                .into_iter()
                .map(map_tracked_swap)
                .collect()
        };

        let req: SwapActionRequest = match serde_json::from_str(action_json.trim()) {
            Ok(r) => r,
            Err(err) => {
                return XmrSwapsActionResult::err(
                    current_swaps(),
                    format!("invalid swap action JSON: {err}"),
                )
            }
        };

        match req.op.trim().to_ascii_lowercase().as_str() {
            "list" => {
                let _ = self.store.reconcile_completed_swaps();
                let swaps = current_swaps();
                XmrSwapsActionResult {
                    ok: true,
                    swap: swaps.first().cloned(),
                    swaps,
                    error: String::new(),
                }
            }
            "create" => {
                let run = || -> Result<TrackedBtcXmrSwap> {
                    let provider = req.provider.trim();
                    if provider.is_empty() {
                        bail!("swap provider is required");
                    }
                    let clean_btc = normalize_btc_amount_decimal(&req.btc_amount)?;
                    let launch_url = req.launch_url.trim();
                    if launch_url.is_empty() {
                        bail!("swap launchUrl is required");
                    }

                    let sub_label = format!("Swap: {provider} ({clean_btc} BTC)");
                    let sub_res = self.new_subaddress(&sub_label);
                    if !sub_res.ok {
                        bail!("{}", sub_res.error);
                    }

                    let now_secs = sync::now_epoch_secs();
                    let seq = SWAP_COUNTER.fetch_add(1, Ordering::Relaxed);
                    let swap_id = format!("swap-{now_secs}-{}-{seq}", sub_res.index);
                    let row = StoredSwap {
                        swap_id,
                        provider: provider.to_string(),
                        btc_amount: clean_btc,
                        quoted_xmr: req.quoted_xmr.trim().to_string(),
                        launch_url: launch_url.to_string(),
                        subaddress_minor: sub_res.index,
                        subaddress: sub_res.address,
                        btc_deposit_address: String::new(),
                        btc_deposit_amount: String::new(),
                        provider_order_id: String::new(),
                        order_url: String::new(),
                        status: "awaiting_form".to_string(),
                        payout_txid: String::new(),
                        received_atomic: 0,
                        created_epoch_secs: now_secs,
                    };
                    self.store.insert_swap(&row)?;
                    Ok(map_tracked_swap(row))
                };

                match run() {
                    Ok(created) => XmrSwapsActionResult {
                        ok: true,
                        swap: Some(created),
                        swaps: current_swaps(),
                        error: String::new(),
                    },
                    Err(err) => XmrSwapsActionResult::err(current_swaps(), err.to_string()),
                }
            }
            "update_order" => match self.store.update_swap_order(
                &req.swap_id,
                &req.btc_deposit_address,
                &req.btc_deposit_amount,
                &req.provider_order_id,
                &req.order_url,
                &req.status,
            ) {
                Ok(updated) => XmrSwapsActionResult {
                    ok: true,
                    swap: Some(map_tracked_swap(updated)),
                    swaps: current_swaps(),
                    error: String::new(),
                },
                Err(err) => XmrSwapsActionResult::err(current_swaps(), err.to_string()),
            },
            "dismiss" => {
                if req.swap_id.trim().is_empty() {
                    return XmrSwapsActionResult::err(
                        current_swaps(),
                        "swapId is required for dismiss",
                    );
                }
                match self.store.dismiss_swap(&req.swap_id) {
                    Ok(_) => XmrSwapsActionResult {
                        ok: true,
                        swap: None,
                        swaps: current_swaps(),
                        error: String::new(),
                    },
                    Err(err) => XmrSwapsActionResult::err(current_swaps(), err.to_string()),
                }
            }
            other => XmrSwapsActionResult::err(
                current_swaps(),
                format!("unknown swap action op {other:?}"),
            ),
        }
    }

    /// Parse and validate an `XMR402` challenge (`nativeXmrParse402ChallengeJson`).
    pub fn parse_xmr402_challenge(
        &self,
        www_authenticate: &str,
        fallback_meta_json: &str,
        page_url: &str,
        hidden_service_only: bool,
    ) -> Xmr402ChallengeResult {
        parse_xmr402_challenge(
            www_authenticate,
            fallback_meta_json,
            page_url,
            hidden_service_only,
        )
    }

    /// Pay an `XMR402` challenge over Tor and return canonical `OutProofV2` + `Authorization` header (`nativeXmrPay402Json`).
    pub async fn pay_xmr402(
        &self,
        socks_addr: Option<SocketAddr>,
        tor_ready: bool,
        hidden_service_only: bool,
        challenge_json: &str,
    ) -> Xmr402PayResult {
        let transport = TorSocksTransport::new(
            socks_addr,
            tor_ready,
            hidden_service_only,
            Some("ov-xmr-rpc".to_string()),
        );
        let master = self.master();
        match sync::pay_xmr402_tx(
            &self.store,
            master.as_ref(),
            &transport,
            hidden_service_only,
            challenge_json,
        )
        .await
        {
            Ok(outcome) => Xmr402PayResult {
                ok: true,
                txid: outcome.txid,
                proof: outcome.proof,
                authorization_header: outcome.authorization_header,
                amount_atomic: outcome.amount_atomic.to_string(),
                amount_xmr: format_atomic_xmr(outcome.amount_atomic),
                fee_atomic: outcome.fee_atomic.to_string(),
                fee_xmr: format_atomic_xmr(outcome.fee_atomic),
                error: String::new(),
            },
            Err(err) => Xmr402PayResult::err(err.to_string()),
        }
    }

    /// Generate a canonical Monero `OutProofV2` for a stored outgoing transaction (`nativeXmrProveTxJson`).
    pub fn prove_tx(
        &self,
        txid: &str,
        recipient_address_or_empty: &str,
        message: &str,
    ) -> XmrTxProofResult {
        let master = self.master();
        match sync::prove_stored_tx(
            &self.store,
            master.as_ref(),
            txid,
            recipient_address_or_empty,
            message,
        ) {
            Ok(res) => res,
            Err(err) => XmrTxProofResult::err(txid, err.to_string()),
        }
    }

    /// Encode a payload string into a square ISO/IEC 18004 QR matrix (`nativeXmrQrMatrixJson`).
    pub fn qr_matrix(&self, payload: &str) -> QrMatrixResult {
        encode_qr_matrix(payload)
    }
}

fn map_tracked_swap(s: StoredSwap) -> TrackedBtcXmrSwap {
    TrackedBtcXmrSwap {
        swap_id: s.swap_id,
        provider: s.provider,
        btc_amount: s.btc_amount,
        quoted_xmr: s.quoted_xmr,
        launch_url: s.launch_url,
        subaddress_index: s.subaddress_minor,
        subaddress: s.subaddress,
        btc_deposit_address: s.btc_deposit_address,
        btc_deposit_amount: s.btc_deposit_amount,
        provider_order_id: s.provider_order_id,
        order_url: s.order_url,
        status: s.status,
        payout_txid: s.payout_txid,
        received_atomic: s.received_atomic.to_string(),
        received_xmr: format_atomic_xmr(s.received_atomic),
        created_epoch_secs: s.created_epoch_secs,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
    use monero_wallet::{
        address::{Network, SubaddressIndex},
        block::{Block, BlockHeader},
        ed25519::{Commitment, Point, Scalar},
        interface::{FeeRate, ScannableBlock},
        ringct::{clsag::Decoys, RctType},
        send::{Change, SignableTransaction},
        transaction::{Input, Timelock, Transaction, TransactionPrefix},
        OutputWithDecoys, Scanner,
    };
    use ov_pgp::SoftwareMasterKey;
    use rand_core::OsRng;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    fn synthetic_owd(spend: Scalar, amount: u64, ring_len: usize) -> OutputWithDecoys {
        let s_d: curve25519_dalek::Scalar = spend.into();
        let offset = Scalar::random(&mut OsRng);
        let off_d: curve25519_dalek::Scalar = offset.into();
        let real_key = Point::from(&(s_d + off_d) * ED25519_BASEPOINT_TABLE);
        let mask = Scalar::random(&mut OsRng);
        let commitment = Commitment::new(mask, amount);

        let mut ring = Vec::with_capacity(ring_len);
        let mut offsets = Vec::with_capacity(ring_len);
        let signer_idx = (ring_len / 2) as u8;
        for i in 0..ring_len {
            offsets.push(if i == 0 { 1000 } else { 15 });
            if i == signer_idx as usize {
                ring.push([real_key, commitment.commit()]);
            } else {
                let rk = Point::from(&Scalar::random(&mut OsRng).into() * ED25519_BASEPOINT_TABLE);
                let rc = Commitment::new(Scalar::random(&mut OsRng), amount).commit();
                ring.push([rk, rc]);
            }
        }
        let decoys = Decoys::new(offsets, signer_idx, ring).unwrap();
        let mut buf = Vec::new();
        buf.extend_from_slice(&real_key.compress().to_bytes());
        offset.write(&mut buf).unwrap();
        mask.write(&mut buf).unwrap();
        buf.extend_from_slice(&amount.to_le_bytes());
        decoys.write(&mut buf).unwrap();
        OutputWithDecoys::read(&mut buf.as_slice()).unwrap()
    }

    /// Spawn a local loopback SOCKS5h (`ATYP = 0x03`) mock HTTP server so `TorSocksTransport`
    /// exercises full SOCKS5h domain negotiation + HTTP/1.1 parsing with zero external network.
    async fn spawn_mock_socks5_http_server<F>(handler: F) -> SocketAddr
    where
        F: Fn(String, String, Vec<u8>) -> (u16, String, Vec<u8>) + Send + Sync + 'static,
    {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let handler = Arc::new(handler);

        tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                let h = handler.clone();
                tokio::spawn(async move {
                    let mut greet = [0u8; 2];
                    if stream.read_exact(&mut greet).await.is_err() {
                        return;
                    }
                    let mut methods = vec![0u8; usize::from(greet[1])];
                    if stream.read_exact(&mut methods).await.is_err() {
                        return;
                    }
                    // Accept METHOD_NONE (0x00)
                    let _ = stream.write_all(&[0x05, 0x00]).await;

                    let mut req_head = [0u8; 5];
                    if stream.read_exact(&mut req_head).await.is_err() {
                        return;
                    }
                    assert_eq!(req_head[0], 0x05);
                    assert_eq!(req_head[1], 0x01);
                    assert_eq!(
                        req_head[3], 0x03,
                        "SOCKS5 request MUST use ATYP=0x03 domain name"
                    );
                    let dlen = usize::from(req_head[4]);
                    let mut domain = vec![0u8; dlen];
                    stream.read_exact(&mut domain).await.unwrap();
                    let host = String::from_utf8(domain).unwrap();
                    let mut port_b = [0u8; 2];
                    stream.read_exact(&mut port_b).await.unwrap();

                    let _ = stream
                        .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80])
                        .await;

                    // Read HTTP request
                    let mut buf = Vec::new();
                    let mut tmp = [0u8; 4096];
                    let hdr_end = loop {
                        if let Some(p) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                            break p;
                        }
                        let n = stream.read(&mut tmp).await.unwrap_or(0);
                        if n == 0 {
                            return;
                        }
                        buf.extend_from_slice(&tmp[..n]);
                    };
                    let hdr_str = std::str::from_utf8(&buf[..hdr_end]).unwrap();
                    let first_line = hdr_str.lines().next().unwrap_or("");
                    let path = first_line
                        .split_whitespace()
                        .nth(1)
                        .unwrap_or("/")
                        .to_string();
                    let content_len = hdr_str
                        .lines()
                        .find_map(|l| {
                            let (k, v) = l.split_once(':')?;
                            k.trim()
                                .eq_ignore_ascii_case("content-length")
                                .then(|| v.trim().parse::<usize>().ok())
                                .flatten()
                        })
                        .unwrap_or(0);
                    let mut body = buf[hdr_end + 4..].to_vec();
                    while body.len() < content_len {
                        let n = stream.read(&mut tmp).await.unwrap_or(0);
                        if n == 0 {
                            break;
                        }
                        body.extend_from_slice(&tmp[..n]);
                    }
                    body.truncate(content_len);

                    let (status, ct, resp_body) = h(host, path, body);
                    let status_text = if status == 200 {
                        "OK"
                    } else if status == 403 {
                        "Forbidden"
                    } else {
                        "Error"
                    };
                    let resp_hdr = format!(
                        "HTTP/1.1 {status} {status_text}\r\nContent-Type: {ct}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        resp_body.len()
                    );
                    let _ = stream.write_all(resp_hdr.as_bytes()).await;
                    let _ = stream.write_all(&resp_body).await;
                    let _ = stream.shutdown().await;
                });
            }
        });
        addr
    }

    #[tokio::test]
    async fn wallet_lifecycle_swap_completion_clsag_bp_plus_and_master_key_rotation() {
        let tmp = std::env::temp_dir().join(format!("ov-xmr-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        let master1: Arc<dyn MasterKeyProvider> = Arc::new(SoftwareMasterKey::generate().unwrap());
        let svc = XmrService::open(&tmp.join("xmr").join("wallet.db"), master1).unwrap();

        // 1. Create wallet pre-bootstrap -> birthdayPending = true
        let created = svc.create_wallet("Primary Vault", None, false, true).await;
        assert!(created.ok, "create failed: {}", created.error);
        assert!(created.primary_address.starts_with('4'));
        assert!(created.active_subaddress.starts_with('8'));
        assert_eq!(created.active_subaddress_index, 1);
        assert_eq!(created.mnemonic.split_whitespace().count(), 25);

        let snap0 = svc.status_snapshot();
        assert!(snap0.has_wallet);
        assert!(snap0.birthday_pending);
        assert_eq!(snap0.balance_atomic, "0");

        // 2. Rotate MasterKeyProvider (simulating `nativeSetRequireBiometric(true)`) and verify export_seed works!
        let master2: Arc<dyn MasterKeyProvider> = Arc::new(SoftwareMasterKey::generate().unwrap());
        assert!(svc.rotate_master_key(master2).unwrap());
        let exported = svc.export_seed();
        assert!(exported.ok);
        assert_eq!(exported.mnemonic, created.mnemonic);

        // 3. Create tracked BTC->XMR swap -> mints dedicated subaddress #2 (`8...`)
        let create_swap = svc.swaps_action(
            r#"{"op":"create","provider":"WizardSwap","btcAmount":"0.01","quotedXmr":"1.1825666","launchUrl":"http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/?from=BTC&to=XMR&amount=0.01"}"#,
        );
        assert!(create_swap.ok, "swap create error: {}", create_swap.error);
        let swap = create_swap.swap.unwrap();
        assert_eq!(swap.subaddress_index, 2);
        assert!(swap.subaddress.starts_with('8'));
        assert_eq!(swap.status, "awaiting_form");

        // Update order with BTC deposit address -> transitions to `awaiting_btc_deposit`
        let updated_swap = svc.swaps_action(&format!(
            r#"{{"op":"update_order","swapId":"{}","btcDepositAddress":"bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2","btcDepositAmount":"0.01","providerOrderId":"3X314OQK","orderUrl":"http://wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion/id=3X314OQK"}}"#,
            swap.swap_id
        ));
        assert!(updated_swap.ok);
        assert_eq!(updated_swap.swap.unwrap().status, "awaiting_btc_deposit");

        // 4. Construct a real Ring-16 ClsagBulletproofPlus tx paying subaddress #2 (`swap.subaddress`),
        // scan it into `svc.store()`, and verify swap auto-completes + key-image spentness works!
        let spend_bytes = decode_mnemonic(&created.mnemonic).unwrap();
        let derived = derive_wallet_from_spend(&spend_bytes).unwrap();
        let spend_scalar = mnemonic::validate_canonical_spend_scalar(&spend_bytes).unwrap();
        let sub2_idx = SubaddressIndex::new(0, 2).unwrap();
        let sub2_addr = derived.view_pair.subaddress(Network::Mainnet, sub2_idx);
        assert_eq!(sub2_addr.to_string(), swap.subaddress);

        let sender_spend = Scalar::random(&mut OsRng);
        let in0 = synthetic_owd(sender_spend, 2_000_000_000_000, 16);
        let fee_rate = FeeRate::new(20_000, 10_000).unwrap();
        let payout_atomic = 1_182_566_600_000u64;
        let signable = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            Zeroizing::new([11u8; 32]),
            vec![in0],
            vec![(sub2_addr, payout_atomic)],
            Change::new(derived.view_pair.clone(), None),
            vec![],
            fee_rate,
        )
        .unwrap();
        let tx1 = signable
            .sign(&mut OsRng, &Zeroizing::new(sender_spend))
            .unwrap();
        let tx1_hash = hex::encode(tx1.hash());

        let miner_tx = Transaction::V1 {
            prefix: TransactionPrefix {
                additional_timelock: Timelock::Block(60),
                inputs: vec![Input::Gen(100)],
                outputs: vec![],
                extra: vec![],
            },
            signatures: vec![],
        };
        let block = Block::new(
            BlockHeader {
                hardfork_version: 16,
                hardfork_signal: 16,
                timestamp: 1_795_070_000,
                previous: [0u8; 32],
                nonce: 0,
            },
            miner_tx,
            vec![tx1.hash()],
        )
        .unwrap();
        let scannable = ScannableBlock {
            block,
            transactions: vec![Transaction::from(tx1)],
            output_index_for_first_ringct_output: Some(50_000),
        };
        let mut scanner = Scanner::new(derived.view_pair.clone());
        scanner.register_subaddress(sub2_idx);
        let found = scanner.scan(scannable).unwrap().not_additionally_locked();
        let sub2_out = found
            .iter()
            .find(|o| o.subaddress() == Some(sub2_idx))
            .unwrap();
        let ki = compute_key_image(spend_scalar, sub2_out);

        svc.store()
            .upsert_output(&StoredOutput {
                txid: tx1_hash.clone(),
                vout: sub2_out.index_in_transaction(),
                subaddress_minor: 2,
                amount_atomic: payout_atomic,
                height: 100,
                timestamp: 1_795_070_000,
                output_blob: sub2_out.serialize(),
                key_image: Some(ki.to_bytes()),
                spent_in_txid: None,
            })
            .unwrap();
        svc.store()
            .update_sync_progress(100, false, 110, 110, 1_795_070_600)
            .unwrap();
        svc.store().reconcile_completed_swaps().unwrap();

        let snap_paid = svc.status_snapshot();
        assert_eq!(snap_paid.balance_atomic, payout_atomic.to_string());
        assert_eq!(snap_paid.unlocked_balance_atomic, payout_atomic.to_string());
        assert_eq!(snap_paid.balance_xmr, "1.1825666");
        assert_eq!(snap_paid.swaps[0].status, "completed");
        assert_eq!(snap_paid.swaps[0].payout_txid, tx1_hash);
        assert_eq!(snap_paid.swaps[0].received_xmr, "1.1825666");

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[tokio::test]
    async fn socks5h_lws_403_and_quote_aggregator_integration() {
        let tmp = std::env::temp_dir().join(format!("ov-xmr-socks-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        let master: Arc<dyn MasterKeyProvider> = Arc::new(SoftwareMasterKey::generate().unwrap());
        let svc = XmrService::open(&tmp.join("xmr").join("wallet.db"), master).unwrap();

        let fixture_html = include_str!("../tests/fixtures/orangefren_btcxmr_001.html").to_string();
        let socks_addr = spawn_mock_socks5_http_server(move |host, path, _body| {
            if host == "rnwis2whetqcj4oknksnc5l24jbh33nflunifff3xtjjonnoxu3ld6id.onion"
                && path.starts_with("/results/0.01/BTC@BTC/XMR@XMR/")
            {
                (
                    200,
                    "text/html; charset=utf-8".to_string(),
                    fixture_html.as_bytes().to_vec(),
                )
            } else if path == "/login" || path == "/get_address_info" {
                (403, "application/json".to_string(), Vec::new())
            } else {
                (404, "text/plain".to_string(), b"not found".to_vec())
            }
        })
        .await;

        let created = svc.create_wallet("LWS Test", None, false, true).await;
        assert!(created.ok);

        // Fetch BTC -> XMR quotes over mock SOCKS5h using exact OrangeFren `.onion` hostname:
        let quotes_res = svc
            .fetch_btc_swap_quotes(Some(socks_addr), true, true, "0.01")
            .await;
        assert!(quotes_res.ok, "quotes error: {}", quotes_res.error);
        assert_eq!(quotes_res.quotes.len(), 5);
        assert_eq!(quotes_res.quotes[0].provider, "Pablo Cash");
        assert!(quotes_res.quotes[0].is_onion_url);

        // Switch to LWS `.onion` endpoint and verify exact HTTP 403 message:
        let set_ep = svc.set_daemon_endpoint(
            "http://xjkbsap2bvyjbhk4tkqi66qkl3lhbz3otr4k7liwjudag6dtbemrvjid.onion:8080",
            "lws",
            true,
        );
        assert!(set_ep.ok);
        assert_eq!(set_ep.sync_mode, "lws");

        let sync_res = svc.sync(Some(socks_addr), true, true).await;
        assert!(!sync_res.ok);
        assert_eq!(sync_res.error, LWS_FORBIDDEN_ERROR_MSG);

        assert!(svc.delete_wallet());
        assert!(!svc.status_snapshot().has_wallet);
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[tokio::test]
    async fn socks5h_monerod_tip_snap_and_self_hosted_lws_spentness_verification() {
        let tmp = std::env::temp_dir().join(format!("ov-xmr-lws200-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        let master: Arc<dyn MasterKeyProvider> = Arc::new(SoftwareMasterKey::generate().unwrap());
        let svc = XmrService::open(&tmp.join("xmr").join("wallet.db"), master).unwrap();

        // Pre-compute deterministic wallet + 2 real RingCT outputs (1 unspent, 1 spent via client-side key image)
        let mnemonic_str = "reruns today hookup itself thorn nirvana symptoms jukebox patio unquoted sushi long diode digit rewind hacksaw obvious soothe nightly return agile hobby algebra awesome nirvana";
        let spend_bytes = decode_mnemonic(mnemonic_str).unwrap();
        let derived = derive_wallet_from_spend(&spend_bytes).unwrap();
        let spend_scalar = mnemonic::validate_canonical_spend_scalar(&spend_bytes).unwrap();
        let sub1 = SubaddressIndex::new(0, 1).unwrap();
        let sub1_addr = derived.view_pair.subaddress(Network::Mainnet, sub1);

        // Build two real single-txkey outputs to `sub1_addr` (`r1 * B_sub + Hs(8*r1*V_sub || 0)*G`):
        let make_lws_out = |r_seed: u8, amount: u64, vout: usize, global_idx: u64, tx_tag: u8| {
            let r_scalar = Scalar::hash([r_seed; 32]);
            let r_d: curve25519_dalek::Scalar = r_scalar.into();
            let sub_spend_ed: curve25519_dalek::EdwardsPoint = sub1_addr.spend().into();
            // Subaddress (0, 1) uses `tx_pub_key = r * spend_sub`
            let tx_pub_point = Point::from(r_d * sub_spend_ed);
            let view_d: curve25519_dalek::Scalar = Option::from(
                curve25519_dalek::Scalar::from_canonical_bytes(*derived.view_key_bytes),
            )
            .unwrap();
            let tx_pub_ed: curve25519_dalek::EdwardsPoint = tx_pub_point.into();
            let ecdh = (view_d * tx_pub_ed).mul_by_cofactor().compress().to_bytes();
            let mut dbuf = ecdh.to_vec();
            monero_wallet::io::VarInt::write(&vout, &mut dbuf).unwrap();
            let shared = Scalar::hash(&dbuf);
            let shared_d: curve25519_dalek::Scalar = shared.into();
            let out_point = Point::from(sub_spend_ed + (&shared_d * ED25519_BASEPOINT_TABLE));

            let cand = wallet::LwsUnspentCandidate {
                tx_hash: [tx_tag; 32],
                vout: vout as u64,
                global_index: global_idx,
                amount_atomic: amount,
                output_pub_key: out_point.compress().to_bytes(),
                tx_pub_key: tx_pub_point.compress().to_bytes(),
                rct: String::new(),
            };
            let (_, _, ki) = wallet::reconstruct_lws_wallet_output(
                &derived.view_pair,
                &*derived.view_key_bytes,
                spend_scalar,
                1,
                &cand,
            )
            .unwrap();
            (cand, hex::encode(ki.to_bytes()))
        };

        let (cand_unspent, _ki_unspent) = make_lws_out(1, 750_000_000_000, 0, 9001, 0xaa);
        let (cand_spent, ki_spent) = make_lws_out(2, 250_000_000_000, 0, 9002, 0xbb);

        let unspent_json = serde_json::json!({
            "per_byte_fee": 20000,
            "fee_mask": 10000,
            "amount": "1000000000000",
            "outputs": [
                {
                    "amount": cand_unspent.amount_atomic.to_string(),
                    "index": cand_unspent.vout,
                    "global_index": cand_unspent.global_index,
                    "tx_hash": hex::encode(cand_unspent.tx_hash),
                    "public_key": hex::encode(cand_unspent.output_pub_key),
                    "tx_pub_key": hex::encode(cand_unspent.tx_pub_key),
                    "height": 3809500u64,
                    "rct": "",
                    "spend_key_images": ["0000000000000000000000000000000000000000000000000000000000000000"]
                },
                {
                    "amount": cand_spent.amount_atomic.to_string(),
                    "index": cand_spent.vout,
                    "global_index": cand_spent.global_index,
                    "tx_hash": hex::encode(cand_spent.tx_hash),
                    "public_key": hex::encode(cand_spent.output_pub_key),
                    "tx_pub_key": hex::encode(cand_spent.tx_pub_key),
                    "height": 3809500u64,
                    "rct": "",
                    "spend_key_images": [ki_spent]
                }
            ]
        })
        .to_string();

        let socks_addr = spawn_mock_socks5_http_server(move |_host, path, _body| match path.as_str() {
            "/json_rpc" => (
                200,
                "application/json".to_string(),
                br#"[{"jsonrpc":"2.0","id":0,"result":"1111111111111111111111111111111111111111111111111111111111111111"},{"jsonrpc":"2.0","id":1,"result":"2222222222222222222222222222222222222222222222222222222222222222"}]"#.to_vec(),
            ),
            "/get_height" => (
                200,
                "application/json".to_string(),
                br#"{"height":3809610,"status":"OK"}"#.to_vec(),
            ),
            "/login" => (
                200,
                "application/json".to_string(),
                br#"{"new_address":false,"generated_locally":true}"#.to_vec(),
            ),
            "/get_address_info" => (
                200,
                "application/json".to_string(),
                br#"{"scanned_block_height":3809609,"blockchain_height":3809609,"start_height":3809000,"spent_outputs":[]}"#.to_vec(),
            ),
            "/get_unspent_outs" => (
                200,
                "application/json".to_string(),
                unspent_json.as_bytes().to_vec(),
            ),
            _ => (404, "text/plain".to_string(), b"not found".to_vec()),
        })
        .await;

        // 1. `create_wallet` when Tor is ready snaps `restore_height` to live tip (`3809610 - 1 = 3809609`):
        let live_created = svc
            .create_wallet("Live Tip Wallet", Some(socks_addr), true, true)
            .await;
        assert!(live_created.ok, "live create error: {}", live_created.error);
        assert_eq!(live_created.restore_height, 3_809_609);
        assert!(!svc.status_snapshot().birthday_pending);

        // 2. Restore vector wallet and sync against self-hosted LWS (`200 OK`):
        let restored = svc.restore_wallet(mnemonic_str, 3_809_000);
        assert!(restored.ok);
        let _ = svc.set_daemon_endpoint(
            "http://xjkbsap2bvyjbhk4tkqi66qkl3lhbz3otr4k7liwjudag6dtbemrvjid.onion:8080",
            "lws",
            true,
        );
        let synced = svc.sync(Some(socks_addr), true, true).await;
        assert!(synced.ok, "lws 200 sync failed: {}", synced.error);
        assert_eq!(synced.synced_height, 3_809_609);
        assert_eq!(synced.daemon_height, 3_809_609);
        // Only the 0.75 XMR output is unspent; the 0.25 XMR output matched `spend_key_images`!
        assert_eq!(synced.balance_xmr, "0.75");
        assert_eq!(synced.unlocked_balance_xmr, "0.75");

        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[tokio::test]
    async fn prove_stored_tx_after_master_key_rotation_chain_switch_reset_and_site_subaddress_idempotence(
    ) {
        let tmp = std::env::temp_dir().join(format!("ov-xmr-402-e2e-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&tmp);
        let master1: Arc<dyn MasterKeyProvider> = Arc::new(SoftwareMasterKey::generate().unwrap());
        let svc = XmrService::open(&tmp.join("xmr").join("wallet.db"), master1.clone()).unwrap();

        let created = svc.create_wallet("XMR402 Vault", None, false, true).await;
        assert!(created.ok);

        // 1. Verify `"site:<host>"` subaddress idempotence:
        let site1 = svc
            .new_subaddress("site:wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion");
        let site2 = svc
            .new_subaddress("SITE:wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion");
        assert!(site1.ok && site2.ok);
        assert_eq!(site1.index, 2);
        assert_eq!(site1.index, site2.index);
        assert_eq!(site1.address, site2.address);
        assert_eq!(svc.status_snapshot().active_subaddress_index, 2);

        let other_site = svc
            .new_subaddress("site:2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion");
        assert_eq!(other_site.index, 3);
        assert_ne!(other_site.address, site1.address);

        // Re-selecting `site1` makes minor_index 2 active again without minting #4:
        let site1_again = svc
            .new_subaddress("site:wizardswgtu2ovor7r2esg3cxdpt7tv4nrugi32lldv53zmtonbz6sid.onion");
        assert_eq!(site1_again.index, 2);
        assert_eq!(svc.status_snapshot().active_subaddress_index, 2);

        // 2. Construct a real Ring-16 ClsagBulletproofPlus outgoing tx, persist `xmr_tx_secrets` +
        // `xmr_transactions`, rotate `MasterKeyProvider`, and verify `prove_tx` + `status_snapshot`!
        let spend_bytes = decode_mnemonic(&created.mnemonic).unwrap();
        let derived = derive_wallet_from_spend(&spend_bytes).unwrap();
        let spend_scalar = mnemonic::validate_canonical_spend_scalar(&spend_bytes).unwrap();

        let merchant_spend = Scalar::random(&mut OsRng);
        let merchant_d: curve25519_dalek::Scalar = merchant_spend.into();
        let merchant_vp = monero_wallet::ViewPair::new(
            Point::from(&merchant_d * ED25519_BASEPOINT_TABLE),
            Zeroizing::new(Scalar::hash(merchant_d.to_bytes())),
        )
        .unwrap();
        let merchant_sub =
            merchant_vp.subaddress(Network::Mainnet, SubaddressIndex::new(0, 1).unwrap());

        let in0 = synthetic_owd(spend_scalar, 1_000_000_000_000, 16);
        let input_pair_bytes = vec![(
            in0.key().compress().to_bytes(),
            in0.commitment().commit().compress().to_bytes(),
        )];
        let mut ovk = Zeroizing::new([0u8; 32]);
        rand_core::RngCore::fill_bytes(&mut OsRng, &mut *ovk);
        let pay_amt = 250_000_000_000u64;
        let fee_rate = FeeRate::new(20_000, 10_000).unwrap();
        let signable = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk.clone(),
            vec![in0],
            vec![(merchant_sub, pay_amt)],
            Change::new(derived.view_pair, None),
            vec![],
            fee_rate,
        )
        .unwrap();
        let fee = signable.necessary_fee();
        let signed_tx = signable
            .sign(&mut OsRng, &Zeroizing::new(spend_scalar))
            .unwrap();
        let txid_hex = hex::encode(signed_tx.hash());

        let secret_plain = encode_tx_secret_material(&ovk, &input_pair_bytes);
        let wrapped_secret = master1.wrap(&secret_plain).unwrap();
        svc.store()
            .upsert_tx_secret(
                &txid_hex,
                &merchant_sub.to_string(),
                &wrapped_secret,
                &signed_tx.prefix().extra,
            )
            .unwrap();
        svc.store()
            .upsert_transaction(
                &StoredTransaction {
                    txid: txid_hex.clone(),
                    direction: "out".to_string(),
                    amount_atomic: pay_amt,
                    fee_atomic: fee,
                    height: 0,
                    timestamp: 1_700_000_100,
                    subaddress_minor: 0,
                },
                false,
            )
            .unwrap();

        let snap_with_tx = svc.status_snapshot();
        assert_eq!(snap_with_tx.recent_txs.len(), 1);
        assert!(snap_with_tx.recent_txs[0].has_proof);
        assert_eq!(
            snap_with_tx.recent_txs[0].recipient_address,
            merchant_sub.to_string()
        );

        // Rotate `MasterKeyProvider` and confirm `xmr_tx_secrets` re-wrapped cleanly:
        let master2: Arc<dyn MasterKeyProvider> = Arc::new(SoftwareMasterKey::generate().unwrap());
        assert!(svc.rotate_master_key(master2).unwrap());

        let challenge_msg = "xmr402-citadel-1700000100-cafebabe";
        let proven = svc.prove_tx(&txid_hex, "", challenge_msg);
        assert!(proven.ok, "prove_tx failed: {}", proven.error);
        assert_eq!(proven.txid, txid_hex);
        assert_eq!(proven.recipient_address, merchant_sub.to_string());
        assert!(proven.proof.starts_with(OUTPROOF_V2_HEADER));
        assert_eq!(
            proven.authorization_header,
            format!("XMR402 txid=\"{txid_hex}\", proof=\"{}\"", proven.proof)
        );
        let verified_atomic =
            verify_tx_proof_v2(&signed_tx, merchant_sub, challenge_msg, &proven.proof).unwrap();
        assert_eq!(verified_atomic, pay_amt);

        // 3. Switching `sync_endpoint` to a different `.onion` URL clears stale outputs/txs/secrets
        // and resets `synced_height = 0, daemon_height = 0, birthday_pending = true`:
        svc.store()
            .update_sync_progress(3_809_600, false, 3_809_610, 3_809_610, 1_700_000_200)
            .unwrap();
        let custom_ep =
            "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion:18081";
        let switched = svc.set_daemon_endpoint(custom_ep, "monerod_onion", true);
        assert!(switched.ok);
        assert_eq!(switched.sync_endpoint, custom_ep);
        assert!(switched.birthday_pending);
        assert_eq!(switched.synced_height, 0);
        assert_eq!(switched.daemon_height, 0);
        assert!(switched.recent_txs.is_empty());
        // Subaddresses remain intact across chain switches!
        assert_eq!(switched.subaddresses.len(), 3);

        // Re-applying the SAME endpoint does NOT reset progress:
        svc.store()
            .update_sync_progress(1, false, 95, 95, 1_700_000_300)
            .unwrap();
        let same_ep = svc.set_daemon_endpoint(custom_ep, "monerod_onion", true);
        assert!(same_ep.ok);
        assert!(!same_ep.birthday_pending);
        assert_eq!(same_ep.synced_height, 95);
        assert_eq!(same_ep.restore_height, 1);

        // 4. Small-chain (`tip <= 10_000`) `create_wallet` snaps `restore_height = 1, synced_height = 0`
        // and `pay_xmr402` fails closed when Tor is offline or `daemonUrl` is clearnet:
        let small_chain_socks = spawn_mock_socks5_http_server(|_host, path, _body| match path.as_str() {
            "/json_rpc" => (
                200,
                "application/json".to_string(),
                br#"[{"jsonrpc":"2.0","id":0,"result":"1111111111111111111111111111111111111111111111111111111111111111"},{"jsonrpc":"2.0","id":1,"result":"2222222222222222222222222222222222222222222222222222222222222222"}]"#.to_vec(),
            ),
            "/get_height" => (
                200,
                "application/json".to_string(),
                br#"{"height":86,"status":"OK"}"#.to_vec(),
            ),
            _ => (404, "text/plain".to_string(), b"not found".to_vec()),
        })
        .await;
        let small_created = svc
            .create_wallet("Regtest Small Chain", Some(small_chain_socks), true, true)
            .await;
        assert!(small_created.ok);
        assert_eq!(small_created.restore_height, 1);
        assert_eq!(svc.status_snapshot().synced_height, 0);
        assert_eq!(svc.status_snapshot().daemon_height, 85);

        let clearnet_pay = svc
            .pay_xmr402(
                Some(small_chain_socks),
                true,
                true,
                &serde_json::json!({
                    "address": merchant_sub.to_string(),
                    "amountAtomic": "250000000000",
                    "message": "nonce",
                    "daemonUrl": "http://clearnet.example.com:18081",
                })
                .to_string(),
            )
            .await;
        assert!(!clearnet_pay.ok);
        assert!(clearnet_pay.error.contains(".onion"));

        let offline_pay = svc
            .pay_xmr402(
                None,
                false,
                true,
                &serde_json::json!({
                    "address": merchant_sub.to_string(),
                    "amountAtomic": "250000000000",
                    "message": "nonce",
                })
                .to_string(),
            )
            .await;
        assert!(!offline_pay.ok);

        let _ = std::fs::remove_dir_all(&tmp);
    }
}
