//! Persistent Monero hot-wallet engine, regtest chain bootstrapper, and atomic
//! `bitcoind` + `monerod` `BTC → XMR` swap settlement backend.

use std::{
    collections::{HashMap, HashSet},
    fs,
    future::Future,
    io::Write,
    path::{Path, PathBuf},
    pin::Pin,
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{anyhow, bail, Context, Result};
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_address::{MoneroAddress, Network, SubaddressIndex};
use monero_daemon_rpc::{prelude::*, MoneroDaemon};
use monero_wallet::{
    ed25519::{CompressedPoint, Point, Scalar},
    ringct::RctType,
    send::{Change, SignableTransaction},
    transaction::{Input, Timelock, Transaction},
    OutputWithDecoys, Scanner, ViewPair, WalletOutput,
};
use rand_core::{OsRng, RngCore as _};
use tokio::sync::Mutex;
use zeroize::Zeroizing;

use crate::rpc::{LoopbackBitcoinClient, LoopbackMoneroTransport};

pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// Minimum regtest block count ensured at startup so Ring-16 Gamma decoy selection
/// (`max_iters = 160`, `COINBASE_LOCK_WINDOW = 60`) succeeds with >99.999999999% first-try
/// probability while staying well below Trap's `MAX_BLOCKS_PER_SYNC_PASS = 500`.
const BOOTSTRAP_MIN_MONERO_HEIGHT: u64 = 300;

/// Minimum unlocked non-coinbase RingCT V2 outputs ensured on chain at startup.
const BOOTSTRAP_MIN_RINGCT_V2_OUTPUTS: usize = 24;

/// Amount per pre-split RingCT V2 hot-wallet UTXO (`1.2 XMR`).
const BOOTSTRAP_SPLIT_OUTPUT_ATOMIC: u64 = 1_200_000_000_000;

/// Blocks mined per completed `BTC → XMR` swap so client's `1.0 XMR` output immediately has 11 confirmations.
pub const SWAP_PAYOUT_CONFIRM_BLOCKS: u64 = 11;

/// Scan chunk size over loopback RPC.
const SCAN_CHUNK_BLOCKS: usize = 64;

/// Verified settlement artifacts from funding BTC + broadcasting & confirming Ring-16 XMR payout.
#[derive(Clone, Debug)]
pub struct SwapSettlement {
    pub btc_txid: String,
    pub btc_block_height: u64,
    pub xmr_payout_txid: String,
    pub xmr_payout_height: u64,
    pub xmr_fee_atomic: u64,
}

/// Abstract chain backend enabling fast deterministic unit tests alongside real loopback daemons.
pub trait ChainBackend: Send + Sync + 'static {
    fn primary_address(&self) -> MoneroAddress;
    fn vault_subaddress(&self) -> MoneroAddress;

    fn fetch_transaction(
        &self,
        txid_hex: &str,
    ) -> BoxFuture<'_, Result<(Transaction, Option<u64>, bool)>>;

    fn mine_monero_blocks(&self, count: u64) -> BoxFuture<'_, Result<u64>>;
    fn monerod_height(&self) -> BoxFuture<'_, Result<u64>>;
    fn bitcoind_blocks(&self) -> BoxFuture<'_, Result<u64>>;
    fn new_btc_deposit_address(&self, label: &str) -> BoxFuture<'_, Result<String>>;

    fn fund_btc_and_payout_xmr(
        &self,
        deposit_addr: &str,
        expected_btc: &str,
        expected_min_sats: u64,
        payout_addr: MoneroAddress,
        xmr_amount_atomic: u64,
    ) -> BoxFuture<'_, Result<SwapSettlement>>;
}

/// Derive `(spend_scalar, view_pair, primary_4_addr, vault_8_subaddr)` from 32-byte canonical spend key.
pub fn derive_hot_wallet_keys(
    spend_bytes: &[u8; 32],
) -> Result<(Scalar, ViewPair, MoneroAddress, MoneroAddress)> {
    let spend_dalek = Option::<curve25519_dalek::Scalar>::from(
        curve25519_dalek::Scalar::from_canonical_bytes(*spend_bytes),
    )
    .ok_or_else(|| anyhow!("wallet_seed.hex is not a canonical Ed25519 scalar"))?;
    if spend_dalek == curve25519_dalek::Scalar::ZERO {
        bail!("wallet_seed.hex scalar must not be zero");
    }
    let spend_scalar = Scalar::from(spend_dalek);
    let spend_pub = Point::from(&spend_dalek * ED25519_BASEPOINT_TABLE);
    let view_scalar = Scalar::hash(spend_dalek.to_bytes());
    let view_pair = ViewPair::new(spend_pub, Zeroizing::new(view_scalar))
        .map_err(|e| anyhow!("failed to construct ViewPair: {e}"))?;
    let primary_addr = view_pair.legacy_address(Network::Mainnet);
    let vault_idx =
        SubaddressIndex::new(0, 1).ok_or_else(|| anyhow!("invalid (0, 1) subaddress index"))?;
    let vault_subaddr = view_pair.subaddress(Network::Mainnet, vault_idx);
    Ok((spend_scalar, view_pair, primary_addr, vault_subaddr))
}

/// Load or atomically generate `<state_dir>/wallet_seed.hex` (`0600`).
pub fn load_or_generate_wallet_seed(state_dir: &Path) -> Result<Zeroizing<[u8; 32]>> {
    fs::create_dir_all(state_dir)
        .with_context(|| format!("failed to create state directory {}", state_dir.display()))?;
    let seed_path = state_dir.join("wallet_seed.hex");
    if seed_path.exists() {
        let raw_hex = fs::read_to_string(&seed_path)
            .with_context(|| format!("failed to read {}", seed_path.display()))?;
        let decoded = hex::decode(raw_hex.trim())
            .with_context(|| format!("invalid hex in {}", seed_path.display()))?;
        let arr: [u8; 32] = decoded
            .try_into()
            .map_err(|_| anyhow!("wallet_seed.hex must decode to 32 bytes"))?;
        // Validate canonical non-zero scalar before returning.
        let _ = derive_hot_wallet_keys(&arr)?;
        return Ok(Zeroizing::new(arr));
    }

    let fresh_scalar = Scalar::random(&mut OsRng);
    let bytes = Zeroizing::new(<[u8; 32]>::from(fresh_scalar));
    let hex_line = Zeroizing::new(format!("{}\n", hex::encode(*bytes)));

    let tmp_path = state_dir.join("wallet_seed.hex.tmp");
    {
        #[cfg(unix)]
        let mut f = {
            use std::os::unix::fs::OpenOptionsExt as _;
            fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&tmp_path)
                .with_context(|| format!("failed to open {}", tmp_path.display()))?
        };
        #[cfg(not(unix))]
        let mut f = fs::File::create(&tmp_path)?;

        f.write_all(hex_line.as_bytes())?;
        f.sync_all()?;
    }
    fs::rename(&tmp_path, &seed_path).with_context(|| {
        format!(
            "failed to rename {} -> {}",
            tmp_path.display(),
            seed_path.display()
        )
    })?;
    Ok(bytes)
}

/// Compute Monero key image `I = (s + key_offset) * Hp(P)` for a scanned [`WalletOutput`].
pub fn compute_key_image(spend_scalar: Scalar, output: &WalletOutput) -> CompressedPoint {
    let s_dalek: curve25519_dalek::Scalar = spend_scalar.into();
    let offset_dalek: curve25519_dalek::Scalar = output.key_offset().into();
    let x = Zeroizing::new(s_dalek + offset_dalek);
    let hp_p: curve25519_dalek::EdwardsPoint =
        Point::biased_hash(output.key().compress().to_bytes()).into();
    Point::from(*x * hp_p).compress()
}

#[derive(Clone)]
struct TrackedOutput {
    block_number: u64,
    is_coinbase: bool,
    subaddress_minor: u32,
    key_image: [u8; 32],
    spent: bool,
    wallet_output: WalletOutput,
}

impl TrackedOutput {
    fn is_unlocked_for_hot_spend(&self, tip_block_number: u64, now_secs: u64) -> bool {
        if self.spent || self.subaddress_minor != 0 {
            return false;
        }
        let min_confs = if self.is_coinbase { 60u64 } else { 10u64 };
        if tip_block_number < self.block_number.saturating_add(min_confs) {
            return false;
        }
        match self.wallet_output.additional_timelock() {
            Timelock::None => true,
            Timelock::Block(unlock_block) => {
                tip_block_number.saturating_add(1) >= unlock_block as u64
            }
            Timelock::Time(unlock_time) => now_secs >= unlock_time,
        }
    }
}

struct HotWalletState {
    next_scan_block: usize,
    outputs_by_id: HashMap<([u8; 32], u64), TrackedOutput>,
    id_by_key_image: HashMap<[u8; 32], ([u8; 32], u64)>,
    seen_spent_key_images: HashSet<[u8; 32]>,
}

impl HotWalletState {
    fn new() -> Self {
        Self {
            next_scan_block: 0,
            outputs_by_id: HashMap::new(),
            id_by_key_image: HashMap::new(),
            seen_spent_key_images: HashSet::new(),
        }
    }

    async fn sync_to_tip(
        &mut self,
        daemon: &MoneroDaemon<LoopbackMoneroTransport>,
        view_pair: &ViewPair,
        spend_scalar: Scalar,
    ) -> Result<usize> {
        let tip = daemon
            .latest_block_number()
            .await
            .map_err(|e| anyhow!("failed to query monerod latest_block_number: {e}"))?;
        if self.next_scan_block > tip {
            return Ok(tip);
        }

        let mut scanner = Scanner::new(view_pair.clone());
        if let Some(sub1) = SubaddressIndex::new(0, 1) {
            scanner.register_subaddress(sub1);
        }

        let mut start = self.next_scan_block;
        while start <= tip {
            let end = tip.min(start + SCAN_CHUNK_BLOCKS - 1);
            let blocks = daemon
                .contiguous_scannable_blocks(start..=end)
                .await
                .map_err(|e| anyhow!("failed to fetch monerod blocks {start}..={end}: {e}"))?;

            for (offset, scannable) in blocks.into_iter().enumerate() {
                let block_num = (start + offset) as u64;
                let miner_hash = scannable.block.miner_transaction().hash();

                let mut block_kis: Vec<[u8; 32]> = Vec::new();
                for ptx in &scannable.transactions {
                    for inp in &ptx.prefix().inputs {
                        if let Input::ToKey { key_image, .. } = inp {
                            block_kis.push(key_image.to_bytes());
                        }
                    }
                }

                let discovered = scanner
                    .scan(scannable)
                    .map_err(|e| anyhow!("hot-wallet scan error at block {block_num}: {e}"))?
                    .ignore_additional_timelock();

                for wo in discovered {
                    let id = (wo.transaction(), wo.index_in_transaction());
                    let ki = compute_key_image(spend_scalar, &wo).to_bytes();
                    let already_spent = self.seen_spent_key_images.contains(&ki);
                    let minor = wo.subaddress().map(|s| s.address()).unwrap_or(0);
                    let is_coinbase = wo.transaction() == miner_hash;

                    self.id_by_key_image.insert(ki, id);
                    self.outputs_by_id
                        .entry(id)
                        .and_modify(|existing| {
                            if already_spent {
                                existing.spent = true;
                            }
                        })
                        .or_insert(TrackedOutput {
                            block_number: block_num,
                            is_coinbase,
                            subaddress_minor: minor,
                            key_image: ki,
                            spent: already_spent,
                            wallet_output: wo,
                        });
                }

                for ki in block_kis {
                    self.mark_key_image_spent(ki);
                }
            }

            self.next_scan_block = end + 1;
            start = end + 1;
        }

        Ok(tip)
    }

    fn mark_key_image_spent(&mut self, ki: [u8; 32]) {
        self.seen_spent_key_images.insert(ki);
        if let Some(id) = self.id_by_key_image.get(&ki).copied() {
            if let Some(entry) = self.outputs_by_id.get_mut(&id) {
                entry.spent = true;
            }
        }
    }

    fn unlocked_non_coinbase_count(&self, tip_block_number: u64, now_secs: u64) -> usize {
        self.outputs_by_id
            .values()
            .filter(|o| !o.is_coinbase && o.is_unlocked_for_hot_spend(tip_block_number, now_secs))
            .count()
    }

    fn unlocked_candidates(&self, tip_block_number: u64, now_secs: u64) -> Vec<TrackedOutput> {
        let mut list: Vec<TrackedOutput> = self
            .outputs_by_id
            .values()
            .filter(|o| o.is_unlocked_for_hot_spend(tip_block_number, now_secs))
            .cloned()
            .collect();
        // Prefer non-coinbase RingCT V2 outputs first, then largest amount first.
        list.sort_by(|a, b| {
            a.is_coinbase
                .cmp(&b.is_coinbase)
                .then_with(|| {
                    b.wallet_output
                        .commitment()
                        .amount
                        .cmp(&a.wallet_output.commitment().amount)
                })
                .then_with(|| a.block_number.cmp(&b.block_number))
        });
        list
    }
}

/// Production chain backend wired to loopback `monerod` (`127.0.0.1:18082`) and `bitcoind` (`127.0.0.1:18443`).
pub struct RealChainBackend {
    monero_rpc: LoopbackMoneroTransport,
    bitcoin_rpc: LoopbackBitcoinClient,
    spend_scalar: Scalar,
    view_pair: ViewPair,
    primary_address: MoneroAddress,
    vault_subaddress: MoneroAddress,
    wallet_state: Mutex<HotWalletState>,
}

#[derive(Clone, Debug)]
pub struct RealChainConfig {
    pub state_dir: PathBuf,
    pub monerod_admin_url: String,
    pub bitcoind_rpc_url: String,
    pub bitcoind_wallet: String,
    pub bitcoind_user: String,
    pub bitcoind_pass: String,
}

impl RealChainBackend {
    /// Initialize hot-wallet keys and bootstrap both `bitcoind` (`>= 105` blocks) and `monerod`
    /// (`>= 300` blocks + `>= 24` unlocked RingCT V2 outputs).
    pub async fn connect_and_bootstrap(cfg: RealChainConfig) -> Result<Self> {
        let seed_bytes = load_or_generate_wallet_seed(&cfg.state_dir)?;
        let (spend_scalar, view_pair, primary_address, vault_subaddress) =
            derive_hot_wallet_keys(&seed_bytes)?;

        let monero_rpc = LoopbackMoneroTransport::new(&cfg.monerod_admin_url)?;
        let bitcoin_rpc = LoopbackBitcoinClient::new(
            &cfg.bitcoind_rpc_url,
            &cfg.bitcoind_wallet,
            &cfg.bitcoind_user,
            &cfg.bitcoind_pass,
        )?;

        let backend = Self {
            monero_rpc,
            bitcoin_rpc,
            spend_scalar,
            view_pair,
            primary_address,
            vault_subaddress,
            wallet_state: Mutex::new(HotWalletState::new()),
        };
        backend.bootstrap().await?;
        Ok(backend)
    }

    async fn bootstrap(&self) -> Result<()> {
        let btc_blocks = self
            .bitcoin_rpc
            .ensure_faucet_ready()
            .await
            .context("failed to bootstrap bitcoind regtest faucet wallet")?;

        let primary_str = self.primary_address.to_string();
        let mut xmr_height = self
            .monero_rpc
            .get_info_height()
            .await
            .context("failed to query monerod admin RPC height")?;

        if xmr_height < BOOTSTRAP_MIN_MONERO_HEIGHT {
            let to_mine = BOOTSTRAP_MIN_MONERO_HEIGHT - xmr_height;
            eprintln!(
                "trap-xmr402-swap: mining {to_mine} initial regtest Monero blocks to {primary_str}..."
            );
            xmr_height = self
                .monero_rpc
                .generate_blocks(to_mine, &primary_str)
                .await?;
        }

        let daemon = MoneroDaemon::new(self.monero_rpc.clone())
            .await
            .map_err(|e| anyhow!("failed to initialize MoneroDaemon client: {e}"))?;

        let mut guard = self.wallet_state.lock().await;
        let mut tip = guard
            .sync_to_tip(&daemon, &self.view_pair, self.spend_scalar)
            .await?;
        let now_secs = now_unix();

        let mut ringct_v2_unlocked = guard.unlocked_non_coinbase_count(tip as u64, now_secs);
        if ringct_v2_unlocked < BOOTSTRAP_MIN_RINGCT_V2_OUTPUTS {
            eprintln!(
                "trap-xmr402-swap: found {ringct_v2_unlocked} unlocked RingCT V2 outputs (< {BOOTSTRAP_MIN_RINGCT_V2_OUTPUTS}); minting 2 batches of 12 RingCT V2 outputs..."
            );
            // Ensure we have at least 2 mature coinbase outputs (> 15 XMR each) to split.
            let mut candidates = guard.unlocked_candidates(tip as u64, now_secs);
            let min_batch_funding = BOOTSTRAP_SPLIT_OUTPUT_ATOMIC * 12 + 100_000_000_000;
            if candidates
                .iter()
                .filter(|c| c.wallet_output.commitment().amount >= min_batch_funding)
                .count()
                < 2
            {
                let _ = self.monero_rpc.generate_blocks(65, &primary_str).await?;
                tip = guard
                    .sync_to_tip(&daemon, &self.view_pair, self.spend_scalar)
                    .await?;
                candidates = guard.unlocked_candidates(tip as u64, now_unix());
            }

            let fee_rate = daemon
                .fee_rate(FeePriority::Normal, u64::MAX)
                .await
                .map_err(|e| anyhow!("failed to fetch monerod fee rate: {e}"))?;

            let mut batches_sent = 0usize;
            for cand in candidates {
                if batches_sent >= 2 {
                    break;
                }
                if cand.wallet_output.commitment().amount < min_batch_funding {
                    continue;
                }
                let owd = sample_ring16_with_retry(&daemon, tip, &cand.wallet_output).await?;
                let mut ovk = Zeroizing::new([0u8; 32]);
                OsRng.fill_bytes(&mut *ovk);
                let payments = vec![(self.primary_address, BOOTSTRAP_SPLIT_OUTPUT_ATOMIC); 12];
                let signable = SignableTransaction::new(
                    RctType::ClsagBulletproofPlus,
                    ovk,
                    vec![owd],
                    payments,
                    Change::new(self.view_pair.clone(), None),
                    vec![],
                    fee_rate,
                )
                .map_err(|e| anyhow!("failed to build bootstrap RingCT split batch: {e}"))?;
                let signed = signable
                    .sign(&mut OsRng, &Zeroizing::new(self.spend_scalar))
                    .map_err(|e| anyhow!("failed to sign bootstrap RingCT split batch: {e}"))?;
                daemon
                    .publish_transaction(&signed)
                    .await
                    .map_err(|e| anyhow!("failed to publish bootstrap RingCT split batch: {e}"))?;
                guard.mark_key_image_spent(cand.key_image);
                batches_sent += 1;
            }

            if batches_sent == 0 {
                bail!("failed to fund any bootstrap RingCT V2 split batches");
            }

            xmr_height = self.monero_rpc.generate_blocks(12, &primary_str).await?;
            tip = guard
                .sync_to_tip(&daemon, &self.view_pair, self.spend_scalar)
                .await?;
            ringct_v2_unlocked = guard.unlocked_non_coinbase_count(tip as u64, now_unix());
        }

        eprintln!(
            "trap-xmr402-swap: chain bootstrap complete (monerod height={xmr_height}, unlocked RingCT V2 outputs={ringct_v2_unlocked}, bitcoind blocks={btc_blocks}, vault={})",
            self.vault_subaddress
        );
        Ok(())
    }

    async fn send_hot_wallet_xmr(
        &self,
        payout_addr: MoneroAddress,
        amount_atomic: u64,
    ) -> Result<(String, u64, u64)> {
        let daemon = MoneroDaemon::new(self.monero_rpc.clone())
            .await
            .map_err(|e| anyhow!("failed to connect MoneroDaemon for swap payout: {e}"))?;
        let fee_rate = daemon
            .fee_rate(FeePriority::Normal, u64::MAX)
            .await
            .map_err(|e| anyhow!("failed to fetch monerod fee rate: {e}"))?;
        let primary_str = self.primary_address.to_string();

        let mut guard = self.wallet_state.lock().await;
        let mut tip = guard
            .sync_to_tip(&daemon, &self.view_pair, self.spend_scalar)
            .await?;

        let fee_buffer = 50_000_000_000u64; // 0.05 XMR generous upper bound for selection
        let mut available = guard.unlocked_candidates(tip as u64, now_unix());
        let total_avail: u64 = available
            .iter()
            .take(16)
            .map(|c| c.wallet_output.commitment().amount)
            .sum();

        if total_avail < amount_atomic.saturating_add(fee_buffer) {
            let _ = self.monero_rpc.generate_blocks(65, &primary_str).await?;
            tip = guard
                .sync_to_tip(&daemon, &self.view_pair, self.spend_scalar)
                .await?;
            available = guard.unlocked_candidates(tip as u64, now_unix());
        }

        let mut chosen = Vec::new();
        let mut acc = 0u64;
        for cand in available {
            if chosen.len() >= 16 {
                break;
            }
            acc = acc.saturating_add(cand.wallet_output.commitment().amount);
            chosen.push(cand);
            if acc >= amount_atomic.saturating_add(fee_buffer) {
                break;
            }
        }
        if chosen.is_empty() || acc <= amount_atomic {
            bail!(
                "hot wallet has insufficient unlocked XMR ({acc} atomic available for {amount_atomic} atomic payout)"
            );
        }

        let mut inputs_with_decoys = Vec::with_capacity(chosen.len());
        for cand in &chosen {
            let owd = sample_ring16_with_retry(&daemon, tip, &cand.wallet_output).await?;
            inputs_with_decoys.push(owd);
        }

        let mut ovk = Zeroizing::new([0u8; 32]);
        OsRng.fill_bytes(&mut *ovk);
        let signable = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk,
            inputs_with_decoys,
            vec![(payout_addr, amount_atomic)],
            Change::new(self.view_pair.clone(), None),
            vec![],
            fee_rate,
        )
        .map_err(|e| anyhow!("failed to construct swap payout transaction: {e}"))?;
        let final_fee = signable.necessary_fee();

        let signed_tx = signable
            .sign(&mut OsRng, &Zeroizing::new(self.spend_scalar))
            .map_err(|e| anyhow!("failed to sign Ring-16 swap payout transaction: {e}"))?;
        let txid_hex = hex::encode(signed_tx.hash());

        daemon
            .publish_transaction(&signed_tx)
            .await
            .map_err(|e| anyhow!("monerod rejected swap payout transaction: {e}"))?;

        for cand in &chosen {
            guard.mark_key_image_spent(cand.key_image);
        }

        // Mine 11 blocks so the 1.0 XMR output has 11 confirmations (100% unlocked for immediate XMR402 spend!).
        let new_chain_height = self
            .monero_rpc
            .generate_blocks(SWAP_PAYOUT_CONFIRM_BLOCKS, &primary_str)
            .await?;
        let _ = guard
            .sync_to_tip(&daemon, &self.view_pair, self.spend_scalar)
            .await?;
        drop(guard);

        let payout_height = match self.monero_rpc.fetch_transaction(&txid_hex).await {
            Ok((_, Some(h), _)) => h,
            _ => new_chain_height.saturating_sub(SWAP_PAYOUT_CONFIRM_BLOCKS),
        };

        Ok((txid_hex, payout_height, final_fee))
    }
}

async fn sample_ring16_with_retry(
    daemon: &MoneroDaemon<LoopbackMoneroTransport>,
    tip: usize,
    wo: &WalletOutput,
) -> Result<OutputWithDecoys> {
    let mut last_err = None;
    for _ in 0..5 {
        match OutputWithDecoys::new(&mut OsRng, daemon, 16, tip, wo.clone()).await {
            Ok(owd) => return Ok(owd),
            Err(e) => last_err = Some(e),
        }
    }
    Err(anyhow!(
        "failed to sample Ring-16 decoys from local monerod: {}",
        last_err.map(|e| e.to_string()).unwrap_or_default()
    ))
}

impl ChainBackend for RealChainBackend {
    fn primary_address(&self) -> MoneroAddress {
        self.primary_address
    }

    fn vault_subaddress(&self) -> MoneroAddress {
        self.vault_subaddress
    }

    fn fetch_transaction(
        &self,
        txid_hex: &str,
    ) -> BoxFuture<'_, Result<(Transaction, Option<u64>, bool)>> {
        let txid_owned = txid_hex.to_string();
        Box::pin(async move { self.monero_rpc.fetch_transaction(&txid_owned).await })
    }

    fn mine_monero_blocks(&self, count: u64) -> BoxFuture<'_, Result<u64>> {
        let primary_str = self.primary_address.to_string();
        Box::pin(async move { self.monero_rpc.generate_blocks(count, &primary_str).await })
    }

    fn monerod_height(&self) -> BoxFuture<'_, Result<u64>> {
        Box::pin(async move { self.monero_rpc.get_info_height().await })
    }

    fn bitcoind_blocks(&self) -> BoxFuture<'_, Result<u64>> {
        Box::pin(async move { self.bitcoin_rpc.get_block_count().await })
    }

    fn new_btc_deposit_address(&self, label: &str) -> BoxFuture<'_, Result<String>> {
        let label_owned = label.to_string();
        Box::pin(async move { self.bitcoin_rpc.get_new_bech32_address(&label_owned).await })
    }

    fn fund_btc_and_payout_xmr(
        &self,
        deposit_addr: &str,
        expected_btc: &str,
        expected_min_sats: u64,
        payout_addr: MoneroAddress,
        xmr_amount_atomic: u64,
    ) -> BoxFuture<'_, Result<SwapSettlement>> {
        let dep = deposit_addr.to_string();
        let btc_amt = expected_btc.to_string();
        Box::pin(async move {
            let (btc_txid, btc_block_height) = self
                .bitcoin_rpc
                .fund_and_confirm_deposit(&dep, &btc_amt, expected_min_sats)
                .await?;
            let (xmr_payout_txid, xmr_payout_height, xmr_fee_atomic) = self
                .send_hot_wallet_xmr(payout_addr, xmr_amount_atomic)
                .await?;
            Ok(SwapSettlement {
                btc_txid,
                btc_block_height,
                xmr_payout_txid,
                xmr_payout_height,
                xmr_fee_atomic,
            })
        })
    }
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
