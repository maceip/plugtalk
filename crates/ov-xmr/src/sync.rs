//! Light Monero wallet synchronization (`.onion` `monerod` + `monero-lws`) and
//! Ring-16 CLSAG + Bulletproof+ outgoing transaction builder.

use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use monero_address::MoneroAddress;
use monero_daemon_rpc::{prelude::*, MoneroDaemon};
use monero_wallet::{
    address::SubaddressIndex,
    ed25519::Scalar,
    ringct::RctType,
    send::{Change, SignableTransaction},
    transaction::{Input, Pruned, Timelock, Transaction},
    OutputWithDecoys, Scanner, ViewPair, WalletOutput,
};
use ov_pgp::{zeroize_slice, MasterKeyProvider};
use rand_core::{OsRng, RngCore as _};
use serde::Deserialize;
use zeroize::Zeroizing;

use crate::{
    mnemonic::validate_canonical_spend_scalar,
    store::{
        StoredOutput, StoredTransaction, StoredWalletMeta, XmrStore, DEFAULT_MONEROD_ONION_POOL,
        SYNC_MODE_LWS, SYNC_MODE_MONEROD_ONION,
    },
    transport::{MoneroRpcTransport, TorSocksTransport},
    tx_proof::{
        decode_tx_secret_material, encode_tx_secret_material, format_xmr402_authorization_header,
        generate_tx_proof_v2, reconstruct_tx_secret_keys, reconstruct_tx_secret_keys_from_extra,
        verify_tx_proof_v2,
    },
    uri::{parse_amount_decimal_or_atomic, parse_mainnet_address, parse_monero_uri},
    wallet::{
        compute_key_image, derive_subaddress, reconstruct_lws_wallet_output, reconstruct_view_pair,
        LwsUnspentCandidate,
    },
    xmr402::{parse_payment_challenge_json, XmrTxProofResult},
};

/// Exact required truthful message when a public `monero-lws` server returns `403 Forbidden`.
pub const LWS_FORBIDDEN_ERROR_MSG: &str = "LWS server returned 403 Forbidden (account pending admin approval); switch to .onion monerod mode or use a self-hosted LWS";

/// Blocks fetched per `contiguous_scannable_blocks` RPC request over Tor.
const BLOCK_SCAN_CHUNK: u64 = 50;

/// Maximum blocks scanned in a single `sync()` UI invocation before returning a persisted checkpoint.
const MAX_BLOCKS_PER_SYNC_PASS: u64 = 500;

/// Monero protocol RingCT unlock window (`10` confirmations: `tip >= height + 9`).
pub const UNLOCK_CONFIRMATIONS: u64 = 10;

/// Maximum inputs spent in a single outgoing RingCT transaction.
const MAX_TX_INPUTS: usize = 16;

/// Outcome of broadcasting a signed RingCT CLSAG + Bulletproof+ transaction.
#[derive(Clone, Debug)]
pub struct BroadcastOutcome {
    pub txid: String,
    pub fee_atomic: u64,
    pub amount_atomic: u64,
    pub raw_tx_bytes: usize,
}

/// Outcome of paying an `XMR402` challenge and generating its `OutProofV2`.
#[derive(Clone, Debug)]
pub struct PayXmr402Outcome {
    pub txid: String,
    pub proof: String,
    pub authorization_header: String,
    pub amount_atomic: u64,
    pub fee_atomic: u64,
    pub raw_tx_bytes: usize,
}

/// Check whether an output at `height` (with optional `timelock`) is unlocked at `daemon_tip`.
pub fn is_output_unlocked(
    height: u64,
    daemon_tip: u64,
    timelock: Timelock,
    now_epoch_secs: u64,
) -> bool {
    if height == 0 || daemon_tip < height.saturating_add(UNLOCK_CONFIRMATIONS - 1) {
        return false;
    }
    match timelock {
        Timelock::None => true,
        Timelock::Block(unlock_block) => daemon_tip >= unlock_block as u64,
        Timelock::Time(unlock_time) => now_epoch_secs >= unlock_time,
    }
}

/// Connect to a `.onion` `monerod` node, trying `custom_endpoint` if non-empty, or failing over
/// across [`DEFAULT_MONEROD_ONION_POOL`].
pub async fn connect_monerod_with_failover(
    transport: &TorSocksTransport,
    custom_endpoint: &str,
) -> Result<(MoneroDaemon<MoneroRpcTransport>, String)> {
    let trimmed = custom_endpoint.trim();
    if !trimmed.is_empty() {
        let rpc = MoneroRpcTransport::new(trimmed.to_string(), transport.clone());
        let daemon = MoneroDaemon::new(rpc)
            .await
            .map_err(|e| anyhow!("cannot connect to monerod {trimmed}: {e}"))?;
        return Ok((daemon, trimmed.to_string()));
    }

    let mut last_err = None;
    for &endpoint in &DEFAULT_MONEROD_ONION_POOL {
        let rpc = MoneroRpcTransport::new(endpoint.to_string(), transport.clone());
        match MoneroDaemon::new(rpc).await {
            Ok(daemon) => return Ok((daemon, endpoint.to_string())),
            Err(err) => {
                log::warn!("monerod failover candidate {endpoint} failed: {err}");
                last_err = Some(format!("{endpoint}: {err}"));
            }
        }
    }

    Err(anyhow!(
        "all .onion monerod nodes failed ({})",
        last_err.unwrap_or_else(|| "no endpoints".to_string())
    ))
}

/// Query latest block height from `.onion` `monerod` when creating a fresh wallet so
/// `restore_height` snaps directly to chain tip.
pub async fn try_fetch_tip_for_new_wallet(
    transport: &TorSocksTransport,
    custom_endpoint: &str,
    sync_mode: &str,
) -> Option<u64> {
    let ep = if sync_mode == SYNC_MODE_MONEROD_ONION {
        custom_endpoint
    } else {
        ""
    };
    let (daemon, _) = connect_monerod_with_failover(transport, ep).await.ok()?;
    let tip = daemon.latest_block_number().await.ok()?;
    u64::try_from(tip).ok()
}

/// Synchronize the wallet in `store` over `transport`.
pub async fn sync_wallet(
    store: &XmrStore,
    master: &dyn MasterKeyProvider,
    transport: &TorSocksTransport,
) -> Result<()> {
    let meta = store
        .wallet_meta()?
        .ok_or_else(|| anyhow!("No Monero wallet created yet"))?;
    let view_pair = reconstruct_view_pair(&meta.primary_address, &meta.view_key_bytes)?;
    let spend_scalar = try_unwrap_spend_scalar(master, &meta.wrapped_spend_key);

    if let Some(s) = spend_scalar {
        backfill_missing_key_images(store, s)?;
    }

    if meta.sync_mode == SYNC_MODE_LWS {
        let s = spend_scalar.ok_or_else(|| {
            anyhow!(
                "Cannot unwrap spend key to verify LWS spent key images (unlock Keystore first)"
            )
        })?;
        sync_via_lws(store, &meta, &view_pair, s, transport).await
    } else {
        sync_via_monerod_onion(store, &meta, &view_pair, spend_scalar, transport).await
    }
}

async fn sync_via_monerod_onion(
    store: &XmrStore,
    meta: &StoredWalletMeta,
    view_pair: &ViewPair,
    spend_scalar: Option<Scalar>,
    transport: &TorSocksTransport,
) -> Result<()> {
    let (daemon, _used_ep) = connect_monerod_with_failover(transport, &meta.sync_endpoint).await?;
    let tip_usize = daemon
        .latest_block_number()
        .await
        .map_err(|e| anyhow!("failed to query monerod latest_block_number: {e}"))?;
    let tip = u64::try_from(tip_usize).unwrap_or(0);
    let now_secs = now_epoch_secs();

    let mut restore_height = meta.restore_height;
    let mut synced_height = meta.synced_height;
    let mut birthday_pending = meta.birthday_pending;

    if birthday_pending {
        restore_height = if tip <= 10_000 {
            1.min(tip)
        } else {
            tip.saturating_sub(5)
        };
        synced_height = restore_height.saturating_sub(1);
        birthday_pending = false;
        store.update_sync_progress(restore_height, false, synced_height, tip, now_secs)?;
    }

    let scan_start = if synced_height == 0 && restore_height == 0 {
        0
    } else if synced_height < restore_height {
        restore_height
    } else {
        synced_height.saturating_add(1)
    };

    if scan_start > tip {
        store.update_sync_progress(restore_height, false, tip, tip, now_secs)?;
        store.reconcile_completed_swaps()?;
        return Ok(());
    }

    let pass_end = tip.min(scan_start.saturating_add(MAX_BLOCKS_PER_SYNC_PASS - 1));
    let mut registered_max = store.max_subaddress_minor()?.saturating_add(20);
    let mut scanner = Scanner::new(view_pair.clone());
    for minor in 1..=registered_max {
        if let Some(idx) = SubaddressIndex::new(0, minor) {
            scanner.register_subaddress(idx);
        }
    }

    let mut chunk_start = scan_start;
    while chunk_start <= pass_end {
        let chunk_end = pass_end.min(chunk_start.saturating_add(BLOCK_SCAN_CHUNK - 1));
        let range_usize =
            usize::try_from(chunk_start).unwrap_or(0)..=usize::try_from(chunk_end).unwrap_or(0);
        let scannable_blocks = daemon
            .contiguous_scannable_blocks(range_usize)
            .await
            .map_err(|e| {
                anyhow!("failed to fetch scannable blocks {chunk_start}..={chunk_end}: {e}")
            })?;

        for (offset, scannable) in scannable_blocks.into_iter().enumerate() {
            let block_height = chunk_start.saturating_add(offset as u64);
            let block_ts = scannable.block.header.timestamp;

            // Snapshot transaction hashes, fees, and key images prior to consuming `scannable`.
            let mut tx_summaries: Vec<([u8; 32], u64, Vec<[u8; 32]>)> =
                Vec::with_capacity(scannable.transactions.len() + 1);
            tx_summaries.push((scannable.block.miner_transaction().hash(), 0, Vec::new()));
            for (tx_hash, pruned_tx) in scannable
                .block
                .transactions
                .iter()
                .zip(&scannable.transactions)
            {
                let fee = pruned_tx_fee(pruned_tx);
                let kis: Vec<[u8; 32]> = pruned_tx
                    .prefix()
                    .inputs
                    .iter()
                    .filter_map(|inp| match inp {
                        Input::ToKey { key_image, .. } => Some(key_image.to_bytes()),
                        Input::Gen(_) => None,
                    })
                    .collect();
                tx_summaries.push((*tx_hash, fee, kis));
            }

            let discovered = scanner
                .scan(scannable)
                .map_err(|e| anyhow!("block scan error at height {block_height}: {e}"))?
                .ignore_additional_timelock();

            for out in &discovered {
                let minor = out.subaddress().map(|s| s.address()).unwrap_or(0);
                if minor >= 1 {
                    if let Ok(addr) = derive_subaddress(view_pair, minor) {
                        let _ = store.insert_subaddress(
                            minor,
                            &format!("Subaddress #{minor}"),
                            &addr.to_string(),
                            false,
                        );
                    }
                    if minor.saturating_add(20) > registered_max {
                        let new_max = minor.saturating_add(20);
                        for m in (registered_max + 1)..=new_max {
                            if let Some(idx) = SubaddressIndex::new(0, m) {
                                scanner.register_subaddress(idx);
                            }
                        }
                        registered_max = new_max;
                    }
                }

                let ki = spend_scalar.map(|s| compute_key_image(s, out).to_bytes());
                let txid = hex::encode(out.transaction());
                store.upsert_output(&StoredOutput {
                    txid,
                    vout: out.index_in_transaction(),
                    subaddress_minor: minor,
                    amount_atomic: out.commitment().amount,
                    height: block_height,
                    timestamp: block_ts,
                    output_blob: out.serialize(),
                    key_image: ki,
                    spent_in_txid: None,
                })?;
            }

            // Reconcile per-tx spent key images and record `xmr_transactions` rows.
            for (tx_hash, tx_fee, input_kis) in tx_summaries {
                let txid = hex::encode(tx_hash);
                let mut spent_sum = 0u64;
                for ki in &input_kis {
                    if let Some(amt) = store.mark_spent_by_key_image(ki, &txid)? {
                        spent_sum = spent_sum.saturating_add(amt);
                    }
                }

                let mut received_sum = 0u64;
                let mut received_minor = 0u32;
                for out in discovered.iter().filter(|o| o.transaction() == tx_hash) {
                    received_sum = received_sum.saturating_add(out.commitment().amount);
                    if let Some(sub) = out.subaddress() {
                        if received_minor == 0 && sub.address() > 0 {
                            received_minor = sub.address();
                        }
                    }
                }

                if spent_sum > 0 {
                    let net_sent = spent_sum
                        .saturating_sub(received_sum)
                        .saturating_sub(tx_fee);
                    store.upsert_transaction(
                        &StoredTransaction {
                            txid,
                            direction: "out".to_string(),
                            amount_atomic: net_sent,
                            fee_atomic: tx_fee,
                            height: block_height,
                            timestamp: block_ts,
                            subaddress_minor: received_minor,
                        },
                        true,
                    )?;
                } else if received_sum > 0 {
                    store.upsert_transaction(
                        &StoredTransaction {
                            txid,
                            direction: "in".to_string(),
                            amount_atomic: received_sum,
                            fee_atomic: tx_fee,
                            height: block_height,
                            timestamp: block_ts,
                            subaddress_minor: received_minor,
                        },
                        false,
                    )?;
                }
            }
        }

        synced_height = chunk_end;
        store.update_sync_progress(
            restore_height,
            birthday_pending,
            synced_height,
            tip,
            now_epoch_secs(),
        )?;
        chunk_start = chunk_end.saturating_add(1);
    }

    store.reconcile_completed_swaps()?;
    Ok(())
}

async fn sync_via_lws(
    store: &XmrStore,
    meta: &StoredWalletMeta,
    view_pair: &ViewPair,
    spend_scalar: Scalar,
    transport: &TorSocksTransport,
) -> Result<()> {
    let base = meta.sync_endpoint.trim().trim_end_matches('/');
    if base.is_empty() {
        bail!("LWS sync mode requires a configured monero-lws endpoint URL");
    }

    let view_hex = Zeroizing::new(hex::encode(&meta.view_key_bytes));

    let login_body = serde_json::json!({
        "address": meta.primary_address,
        "view_key": &*view_hex,
        "create_account": true,
        "generated_locally": true,
    });
    let login_resp = transport
        .post(
            &format!("{base}/login"),
            "application/json",
            login_body.to_string().as_bytes(),
            true,
            Some(1024 * 1024),
        )
        .await
        .context("LWS /login request failed")?;
    check_lws_status(login_resp.status, "/login")?;

    let auth_body = serde_json::json!({
        "address": meta.primary_address,
        "view_key": &*view_hex,
    });
    let info_resp = transport
        .post(
            &format!("{base}/get_address_info"),
            "application/json",
            auth_body.to_string().as_bytes(),
            true,
            Some(4 * 1024 * 1024),
        )
        .await
        .context("LWS /get_address_info request failed")?;
    check_lws_status(info_resp.status, "/get_address_info")?;

    #[derive(Default, Deserialize)]
    struct LwsSpentEntry {
        #[serde(default)]
        key_image: String,
        #[serde(default)]
        tx_id: String,
    }
    #[derive(Default, Deserialize)]
    struct LwsAddressInfo {
        #[serde(default)]
        scanned_block_height: u64,
        #[serde(default)]
        scanned_height: u64,
        #[serde(default)]
        blockchain_height: u64,
        #[serde(default)]
        start_height: u64,
        #[serde(default)]
        spent_outputs: Vec<LwsSpentEntry>,
    }

    let info: LwsAddressInfo = serde_json::from_slice(&info_resp.body)
        .context("invalid JSON from LWS /get_address_info")?;

    let unspent_req = serde_json::json!({
        "address": meta.primary_address,
        "view_key": &*view_hex,
        "amount": "0",
        "mixin": 15,
        "use_dust": true,
        "dust_threshold": "0",
    });
    let outs_resp = transport
        .post(
            &format!("{base}/get_unspent_outs"),
            "application/json",
            unspent_req.to_string().as_bytes(),
            true,
            Some(8 * 1024 * 1024),
        )
        .await
        .context("LWS /get_unspent_outs request failed")?;
    check_lws_status(outs_resp.status, "/get_unspent_outs")?;

    #[derive(Deserialize)]
    struct LwsOutJson {
        #[serde(deserialize_with = "de_u64_str_or_int")]
        amount: u64,
        #[serde(default)]
        index: u64,
        #[serde(default)]
        global_index: u64,
        tx_hash: String,
        public_key: String,
        tx_pub_key: String,
        #[serde(default)]
        height: u64,
        #[serde(default)]
        rct: String,
        #[serde(default)]
        spend_key_images: Vec<String>,
    }
    #[derive(Default, Deserialize)]
    struct LwsUnspentResp {
        #[serde(default)]
        outputs: Vec<LwsOutJson>,
    }

    let unspent_data: LwsUnspentResp = serde_json::from_slice(&outs_resp.body)
        .context("invalid JSON from LWS /get_unspent_outs")?;
    let max_minor = store.max_subaddress_minor()?;
    let now_secs = now_epoch_secs();

    for raw_out in &unspent_data.outputs {
        let Ok(tx_hash) = decode_hex32(&raw_out.tx_hash) else {
            continue;
        };
        let Ok(output_pub_key) = decode_hex32(&raw_out.public_key) else {
            continue;
        };
        let Ok(tx_pub_key) = decode_hex32(&raw_out.tx_pub_key) else {
            continue;
        };
        let cand = LwsUnspentCandidate {
            tx_hash,
            vout: raw_out.index,
            global_index: raw_out.global_index,
            amount_atomic: raw_out.amount,
            output_pub_key,
            tx_pub_key,
            rct: raw_out.rct.clone(),
        };
        let Ok((wallet_out, minor, ki)) = reconstruct_lws_wallet_output(
            view_pair,
            &meta.view_key_bytes,
            spend_scalar,
            max_minor,
            &cand,
        ) else {
            continue;
        };

        if minor >= 1 {
            if let Ok(addr) = derive_subaddress(view_pair, minor) {
                let _ = store.insert_subaddress(
                    minor,
                    &format!("Subaddress #{minor}"),
                    &addr.to_string(),
                    false,
                );
            }
        }

        let ki_hex = hex::encode(ki.to_bytes());
        let spent_txid = if raw_out
            .spend_key_images
            .iter()
            .any(|s| s.eq_ignore_ascii_case(&ki_hex))
        {
            Some("lws_spent".to_string())
        } else {
            info.spent_outputs
                .iter()
                .find(|s| s.key_image.eq_ignore_ascii_case(&ki_hex))
                .map(|s| {
                    if s.tx_id.is_empty() {
                        "lws_spent".to_string()
                    } else {
                        s.tx_id.clone()
                    }
                })
        };

        let txid = hex::encode(tx_hash);
        store.upsert_output(&StoredOutput {
            txid: txid.clone(),
            vout: raw_out.index,
            subaddress_minor: minor,
            amount_atomic: raw_out.amount,
            height: raw_out.height,
            timestamp: now_secs,
            output_blob: wallet_out.serialize(),
            key_image: Some(ki.to_bytes()),
            spent_in_txid: spent_txid.clone(),
        })?;

        if spent_txid.is_none() {
            store.upsert_transaction(
                &StoredTransaction {
                    txid,
                    direction: "in".to_string(),
                    amount_atomic: raw_out.amount,
                    fee_atomic: 0,
                    height: raw_out.height,
                    timestamp: now_secs,
                    subaddress_minor: minor,
                },
                false,
            )?;
        }
    }

    let synced = info.scanned_block_height.max(info.scanned_height);
    let tip = info.blockchain_height.max(synced);
    let restore = if meta.birthday_pending {
        info.start_height.max(tip)
    } else {
        meta.restore_height
    };
    store.update_sync_progress(restore, false, synced, tip, now_secs)?;
    store.reconcile_completed_swaps()?;
    Ok(())
}

/// Construct, sign (Ring-16 `ClsagBulletproofPlus`), and publish an outgoing XMR transaction
/// over Tor.
pub async fn send_xmr(
    store: &XmrStore,
    master: &dyn MasterKeyProvider,
    transport: &TorSocksTransport,
    destination_or_uri: &str,
    amount_decimal_or_atomic: &str,
    sweep_all: bool,
) -> Result<BroadcastOutcome> {
    let trimmed_dest = destination_or_uri.trim();
    let (dest_addr, uri_amount_opt) =
        if trimmed_dest.len() >= 8 && trimmed_dest[..7].eq_ignore_ascii_case("monero:") {
            let parsed = parse_monero_uri(trimmed_dest)?;
            (
                parse_mainnet_address(&parsed.address)?,
                parsed.tx_amount_atomic,
            )
        } else {
            (parse_mainnet_address(trimmed_dest)?, None)
        };

    let target_amount = if sweep_all {
        0
    } else if !amount_decimal_or_atomic.trim().is_empty() {
        let amt = parse_amount_decimal_or_atomic(amount_decimal_or_atomic)?;
        if amt == 0 {
            bail!("Send amount must be greater than zero");
        }
        amt
    } else if let Some(uri_amt) = uri_amount_opt {
        if uri_amt == 0 {
            bail!("Send amount in monero: URI must be greater than zero");
        }
        uri_amt
    } else {
        bail!("Send amount is required unless Sweep All is enabled");
    };

    let (outcome, _) = build_sign_and_publish_tx(
        store,
        master,
        transport,
        dest_addr,
        target_amount,
        sweep_all,
        None,
        None,
    )
    .await?;
    Ok(outcome)
}

/// Pay an `XMR402` challenge over Tor, syncing against `daemonUrl` if needed, signing a Ring-16
/// `ClsagBulletproofPlus` transaction, computing canonical `OutProofV2`, broadcasting, and
/// persisting `xmr_tx_secrets`.
pub async fn pay_xmr402_tx(
    store: &XmrStore,
    master: &dyn MasterKeyProvider,
    transport: &TorSocksTransport,
    hidden_service_only: bool,
    challenge_json: &str,
) -> Result<PayXmr402Outcome> {
    let payment = parse_payment_challenge_json(challenge_json, hidden_service_only)?;
    let meta0 = store
        .wallet_meta()?
        .ok_or_else(|| anyhow!("No Monero wallet created yet"))?;

    if !payment.daemon_url.is_empty() {
        let endpoint_changed = meta0.sync_endpoint.trim().trim_end_matches('/')
            != payment.daemon_url.trim().trim_end_matches('/');
        if endpoint_changed {
            store.set_endpoint_and_mode(&payment.daemon_url, SYNC_MODE_MONEROD_ONION)?;
            sync_wallet_until_caught_up(store, master, transport, 8).await?;
        } else if meta0.birthday_pending
            || meta0.synced_height == 0
            || unlocked_balance_snapshot(store, &meta0)? <= payment.amount_atomic
        {
            let _ = sync_wallet_until_caught_up(store, master, transport, 8).await;
        }
    } else if meta0.birthday_pending
        || unlocked_balance_snapshot(store, &meta0)? <= payment.amount_atomic
    {
        let _ = sync_wallet_until_caught_up(store, master, transport, 4).await;
    }

    let explicit_daemon = (!payment.daemon_url.is_empty()).then_some(payment.daemon_url.as_str());
    let (outcome, proof_opt) = build_sign_and_publish_tx(
        store,
        master,
        transport,
        payment.recipient,
        payment.amount_atomic,
        false,
        Some(&payment.message),
        explicit_daemon,
    )
    .await?;
    let proof = proof_opt.ok_or_else(|| anyhow!("missing generated OutProofV2"))?;
    let authorization_header = format_xmr402_authorization_header(&outcome.txid, &proof);

    Ok(PayXmr402Outcome {
        txid: outcome.txid,
        proof,
        authorization_header,
        amount_atomic: outcome.amount_atomic,
        fee_atomic: outcome.fee_atomic,
        raw_tx_bytes: outcome.raw_tx_bytes,
    })
}

/// Generate a canonical `OutProofV2` + `Authorization: XMR402 ...` header for a previously sent
/// outgoing transaction stored in `xmr_tx_secrets`.
pub fn prove_stored_tx(
    store: &XmrStore,
    master: &dyn MasterKeyProvider,
    txid: &str,
    recipient_address_or_empty: &str,
    message: &str,
) -> Result<XmrTxProofResult> {
    let clean_txid = txid.trim().to_ascii_lowercase();
    let txid_bytes = decode_hex32(&clean_txid)
        .map_err(|_| anyhow!("txid must be a 64-character hexadecimal transaction hash"))?;
    let secret_row = store
        .get_tx_secret(&clean_txid)?
        .ok_or_else(|| anyhow!("No outgoing proof secret stored for transaction {clean_txid}"))?;

    let rcpt_str = if !recipient_address_or_empty.trim().is_empty() {
        recipient_address_or_empty.trim()
    } else {
        secret_row.recipient_address.as_str()
    };
    let recipient = parse_mainnet_address(rcpt_str)?;

    let mut plain = master.unwrap(&secret_row.wrapped_secret_blob)?;
    let decoded = decode_tx_secret_material(&plain);
    zeroize_slice(&mut plain);
    let (ovk, input_pairs) = decoded?;

    let (tx_key, additional_keys) =
        reconstruct_tx_secret_keys_from_extra(&ovk, input_pairs, &secret_row.tx_extra)?;
    let proof = generate_tx_proof_v2(
        txid_bytes,
        &tx_key,
        &additional_keys,
        recipient,
        message,
        &mut OsRng,
    );
    let authorization_header = format_xmr402_authorization_header(&clean_txid, &proof);

    Ok(XmrTxProofResult {
        ok: true,
        txid: clean_txid,
        recipient_address: recipient.to_string(),
        message: message.to_string(),
        proof,
        authorization_header,
        error: String::new(),
    })
}

async fn sync_wallet_until_caught_up(
    store: &XmrStore,
    master: &dyn MasterKeyProvider,
    transport: &TorSocksTransport,
    max_passes: usize,
) -> Result<()> {
    for _ in 0..max_passes.max(1) {
        sync_wallet(store, master, transport).await?;
        let Some(meta) = store.wallet_meta()? else {
            break;
        };
        if !meta.birthday_pending && meta.synced_height >= meta.daemon_height {
            break;
        }
    }
    Ok(())
}

fn unlocked_balance_snapshot(store: &XmrStore, meta: &StoredWalletMeta) -> Result<u64> {
    let tip = meta.daemon_height.max(meta.synced_height);
    let now_secs = now_epoch_secs();
    let mut unlocked = 0u64;
    for row in store.list_outputs()? {
        if row.spent_in_txid.is_some() || row.output_blob.is_empty() {
            continue;
        }
        let Ok(wo) = WalletOutput::read(&mut row.output_blob.as_slice()) else {
            continue;
        };
        if is_output_unlocked(row.height, tip, wo.additional_timelock(), now_secs) {
            unlocked = unlocked.saturating_add(row.amount_atomic);
        }
    }
    Ok(unlocked)
}

#[allow(clippy::too_many_arguments)]
async fn build_sign_and_publish_tx(
    store: &XmrStore,
    master: &dyn MasterKeyProvider,
    transport: &TorSocksTransport,
    dest_addr: MoneroAddress,
    target_amount: u64,
    sweep_all: bool,
    proof_message_opt: Option<&str>,
    explicit_daemon_url: Option<&str>,
) -> Result<(BroadcastOutcome, Option<String>)> {
    let meta = store
        .wallet_meta()?
        .ok_or_else(|| anyhow!("No Monero wallet created yet"))?;
    let view_pair = reconstruct_view_pair(&meta.primary_address, &meta.view_key_bytes)?;
    let spend_scalar = unwrap_spend_scalar_strict(master, &meta.wrapped_spend_key)?;

    // Connect to `.onion` `monerod` for Ring-16 decoy sampling, fee estimation, and broadcast.
    let daemon_ep = if let Some(explicit) = explicit_daemon_url {
        explicit
    } else if meta.sync_mode == SYNC_MODE_MONEROD_ONION {
        meta.sync_endpoint.as_str()
    } else {
        ""
    };
    let (daemon, _) = connect_monerod_with_failover(transport, daemon_ep).await?;
    let tip_usize = daemon
        .latest_block_number()
        .await
        .map_err(|e| anyhow!("failed to fetch monerod tip: {e}"))?;
    let tip = u64::try_from(tip_usize).unwrap_or(0);
    let fee_rate = daemon
        .fee_rate(FeePriority::Normal, u64::MAX)
        .await
        .map_err(|e| anyhow!("failed to fetch monerod fee estimate: {e}"))?;

    let now_secs = now_epoch_secs();
    let mut spendable: Vec<(StoredOutput, WalletOutput)> = Vec::new();
    for row in store.list_outputs()? {
        if row.spent_in_txid.is_some() || row.output_blob.is_empty() {
            continue;
        }
        let Ok(wo) = WalletOutput::read(&mut row.output_blob.as_slice()) else {
            continue;
        };
        if is_output_unlocked(row.height, tip, wo.additional_timelock(), now_secs) {
            spendable.push((row, wo));
        }
    }
    if spendable.is_empty() {
        bail!("No unlocked XMR outputs available (outputs require 10 confirmations)");
    }

    // Sort largest-first to minimize Ring-16 input count and fee.
    spendable.sort_by_key(|a| std::cmp::Reverse(a.0.amount_atomic));

    let selected: Vec<(StoredOutput, WalletOutput)> = if sweep_all {
        spendable.into_iter().take(MAX_TX_INPUTS).collect()
    } else {
        let mut chosen = Vec::new();
        let mut acc = 0u64;
        // Conservative initial fee estimate (~2000 B base + 1300 B per input).
        for item in spendable {
            if chosen.len() >= MAX_TX_INPUTS {
                break;
            }
            acc = acc.saturating_add(item.0.amount_atomic);
            chosen.push(item);
            let approx_weight = 2000u64 + (chosen.len() as u64) * 1300;
            let approx_fee =
                u64::try_from(fee_rate.calculate_fee_from_weight(approx_weight)).unwrap_or(0);
            if acc >= target_amount.saturating_add(approx_fee) {
                break;
            }
        }
        chosen
    };

    let total_in: u64 = selected.iter().map(|(r, _)| r.amount_atomic).sum();
    if !sweep_all && total_in <= target_amount {
        bail!(
            "Insufficient unlocked XMR balance ({} atomic available, {} atomic requested + fee)",
            total_in,
            target_amount
        );
    }

    let mut inputs_with_decoys = Vec::with_capacity(selected.len());
    for (_, wo) in selected {
        let owd = OutputWithDecoys::new(&mut OsRng, &daemon, 16, tip_usize, wo)
            .await
            .map_err(|e| anyhow!("failed to sample Ring-16 decoys over Tor: {e}"))?;
        inputs_with_decoys.push(owd);
    }

    let input_points: Vec<_> = inputs_with_decoys
        .iter()
        .map(|inp| (inp.key(), inp.commitment().commit()))
        .collect();
    let input_pair_bytes: Vec<([u8; 32], [u8; 32])> = input_points
        .iter()
        .map(|(k, c)| (k.compress().to_bytes(), c.compress().to_bytes()))
        .collect();

    let mut ovk = Zeroizing::new([0u8; 32]);
    OsRng.fill_bytes(&mut *ovk);
    let ovk_for_secret = ovk.clone();

    let (signable, final_amount, final_fee) = if sweep_all {
        let probe = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk.clone(),
            inputs_with_decoys.clone(),
            vec![(dest_addr, 0)],
            Change::new(view_pair.clone(), None),
            vec![],
            fee_rate,
        )
        .map_err(|e| anyhow!("cannot build sweep transaction: {e}"))?;
        let fee = probe.necessary_fee();
        if total_in <= fee {
            bail!(
                "Unlocked XMR balance ({total_in} atomic) cannot cover network fee ({fee} atomic)"
            );
        }
        let sweep_amt = total_in - fee;
        let real = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk,
            inputs_with_decoys,
            vec![(dest_addr, sweep_amt)],
            Change::new(view_pair, None),
            vec![],
            fee_rate,
        )
        .map_err(|e| anyhow!("cannot finalize sweep transaction: {e}"))?;
        (real, sweep_amt, fee)
    } else {
        let real = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk,
            inputs_with_decoys,
            vec![(dest_addr, target_amount)],
            Change::new(view_pair, None),
            vec![],
            fee_rate,
        )
        .map_err(|e| anyhow!("cannot build transaction: {e}"))?;
        let fee = real.necessary_fee();
        (real, target_amount, fee)
    };

    let signed_tx = signable
        .sign(&mut OsRng, &Zeroizing::new(spend_scalar))
        .map_err(|e| anyhow!("RingCT CLSAG+Bulletproof+ signing failed: {e}"))?;

    let generated_proof = if let Some(msg) = proof_message_opt {
        let (tx_key, add_keys) =
            reconstruct_tx_secret_keys(&ovk_for_secret, input_points, &signed_tx)?;
        let proof = generate_tx_proof_v2(
            signed_tx.hash(),
            &tx_key,
            &add_keys,
            dest_addr,
            msg,
            &mut OsRng,
        );
        let verified_amt = verify_tx_proof_v2(&signed_tx, dest_addr, msg, &proof)?;
        if verified_amt < final_amount {
            bail!(
                "OutProofV2 self-verification mismatch: verified {verified_amt} < expected {final_amount}"
            );
        }
        Some(proof)
    } else {
        None
    };

    let raw_bytes = signed_tx.serialize();
    let txid = hex::encode(signed_tx.hash());

    if explicit_daemon_url.is_none()
        && meta.sync_mode == SYNC_MODE_LWS
        && !meta.sync_endpoint.trim().is_empty()
    {
        let base = meta.sync_endpoint.trim().trim_end_matches('/');
        let view_hex = Zeroizing::new(hex::encode(&meta.view_key_bytes));
        let body = serde_json::json!({
            "address": meta.primary_address,
            "view_key": &*view_hex,
            "tx": hex::encode(&raw_bytes),
        });
        let resp = transport
            .post(
                &format!("{base}/submit_raw_tx"),
                "application/json",
                body.to_string().as_bytes(),
                true,
                Some(256 * 1024),
            )
            .await
            .context("LWS /submit_raw_tx failed")?;
        check_lws_status(resp.status, "/submit_raw_tx")?;
    } else {
        daemon
            .publish_transaction(&signed_tx)
            .await
            .map_err(|e| anyhow!("monerod rejected transaction: {e}"))?;
    }

    let secret_plain = encode_tx_secret_material(&ovk_for_secret, &input_pair_bytes);
    let wrapped_secret = master.wrap(&secret_plain)?;
    store.upsert_tx_secret(
        &txid,
        &dest_addr.to_string(),
        &wrapped_secret,
        &signed_tx.prefix().extra,
    )?;

    for inp in &signed_tx.prefix().inputs {
        if let Input::ToKey { key_image, .. } = inp {
            let _ = store.mark_spent_by_key_image(&key_image.to_bytes(), &txid);
        }
    }
    store.upsert_transaction(
        &StoredTransaction {
            txid: txid.clone(),
            direction: "out".to_string(),
            amount_atomic: final_amount,
            fee_atomic: final_fee,
            height: 0,
            timestamp: now_secs,
            subaddress_minor: 0,
        },
        false,
    )?;

    Ok((
        BroadcastOutcome {
            txid,
            fee_atomic: final_fee,
            amount_atomic: final_amount,
            raw_tx_bytes: raw_bytes.len(),
        },
        generated_proof,
    ))
}

fn check_lws_status(status: u16, route: &str) -> Result<()> {
    if status == 403 {
        bail!("{LWS_FORBIDDEN_ERROR_MSG}");
    }
    if status != 200 {
        bail!("LWS server returned HTTP {status} for {route}");
    }
    Ok(())
}

fn try_unwrap_spend_scalar(master: &dyn MasterKeyProvider, wrapped: &[u8]) -> Option<Scalar> {
    unwrap_spend_scalar_strict(master, wrapped).ok()
}

pub fn unwrap_spend_scalar_strict(
    master: &dyn MasterKeyProvider,
    wrapped: &[u8],
) -> Result<Scalar> {
    let mut plain = master.unwrap(wrapped)?;
    let res = if let Ok(arr) = <[u8; 32]>::try_from(plain.as_slice()) {
        let z = Zeroizing::new(arr);
        validate_canonical_spend_scalar(&z)
    } else {
        Err(anyhow!(
            "unwrapped spend key has invalid length {}",
            plain.len()
        ))
    };
    ov_pgp::zeroize_slice(&mut plain);
    res
}

fn backfill_missing_key_images(store: &XmrStore, spend_scalar: Scalar) -> Result<()> {
    for row in store.list_outputs()? {
        if row.key_image.is_none() && !row.output_blob.is_empty() {
            if let Ok(wo) = WalletOutput::read(&mut row.output_blob.as_slice()) {
                let ki = compute_key_image(spend_scalar, &wo);
                let _ = store.set_output_key_image(&row.txid, row.vout, &ki.to_bytes());
            }
        }
    }
    Ok(())
}

fn pruned_tx_fee(tx: &Transaction<Pruned>) -> u64 {
    match tx {
        Transaction::V2 {
            proofs: Some(p), ..
        } => p.base.fee,
        _ => 0,
    }
}

fn decode_hex32(s: &str) -> Result<[u8; 32]> {
    hex::decode(s.trim())?
        .try_into()
        .map_err(|_| anyhow!("expected 32-byte hex"))
}

fn de_u64_str_or_int<'de, D>(deserializer: D) -> std::result::Result<u64, D::Error>
where
    D: serde::Deserializer<'de>,
{
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum StrOrInt {
        Str(String),
        Int(u64),
    }
    match StrOrInt::deserialize(deserializer)? {
        StrOrInt::Str(s) => s.trim().parse::<u64>().map_err(serde::de::Error::custom),
        StrOrInt::Int(v) => Ok(v),
    }
}

pub fn now_epoch_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
