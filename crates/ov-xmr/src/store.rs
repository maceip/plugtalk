//! SQLite persistence for Trap's Monero light wallet and tracked BTC -> XMR swaps.
//!
//! Secret spend scalar bytes (`32 B`) are never stored in plaintext: they are
//! wrapped with [`ov_pgp::MasterKeyProvider`] (`AES-256-GCM` in Android hardware
//! Keystore on device, `0600` software key in desktop/JVM tests) before writing
//! `xmr_wallet_meta.wrapped_spend_key`.

use std::{collections::HashMap, path::Path, sync::Mutex};

use anyhow::{anyhow, bail, Context, Result};
use ov_pgp::{zeroize_slice, MasterKeyProvider};
use rusqlite::{params, Connection, OptionalExtension};

/// Default `.onion` `monerod` RPC failover pool verified live over Tor.
pub const DEFAULT_MONEROD_ONION_POOL: [&str; 4] = [
    "http://plowsoffjexmxalw73tkjmf422gq6575fc7vicuu4javzn2ynnte6tyd.onion:18089",
    "http://moneronkvv2hu2anvcc5b4qd5y7strnc2ob6khqsrtikmhocyvjpdjyd.onion:18089",
    "http://2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089",
    "http://24kkg3rbkowv3olnossmkln74fyzrkatiu624uzm2pqd7uirqiyztvqd.onion:18081",
];

/// Sync mode using direct `.onion` `monerod` RPC + local `ViewPair` block scanning.
pub const SYNC_MODE_MONEROD_ONION: &str = "monerod_onion";

/// Sync mode using a self-hosted `monero-lws` JSON REST server over Tor.
pub const SYNC_MODE_LWS: &str = "lws";

const SETTING_SYNC_ENDPOINT: &str = "sync_endpoint";
const SETTING_SYNC_MODE: &str = "sync_mode";

/// Stored wallet metadata row from `xmr_wallet_meta`.
#[derive(Clone, Debug)]
pub struct StoredWalletMeta {
    pub wallet_label: String,
    pub primary_address: String,
    pub wrapped_spend_key: Vec<u8>,
    pub view_key_bytes: Vec<u8>,
    pub active_subaddress_index: u32,
    pub restore_height: u64,
    pub birthday_pending: bool,
    pub synced_height: u64,
    pub daemon_height: u64,
    pub last_sync_epoch_secs: u64,
    pub sync_endpoint: String,
    pub sync_mode: String,
}

/// Stored `(0, minor)` subaddress row from `xmr_subaddresses`.
#[derive(Clone, Debug)]
pub struct StoredSubaddress {
    pub minor_index: u32,
    pub label: String,
    pub address: String,
}

/// Stored owned UTXO row from `xmr_outputs`.
#[derive(Clone, Debug)]
pub struct StoredOutput {
    pub txid: String,
    pub vout: u64,
    pub subaddress_minor: u32,
    pub amount_atomic: u64,
    pub height: u64,
    pub timestamp: u64,
    pub output_blob: Vec<u8>,
    pub key_image: Option<[u8; 32]>,
    pub spent_in_txid: Option<String>,
}

/// Stored wallet transaction summary from `xmr_transactions`.
#[derive(Clone, Debug)]
pub struct StoredTransaction {
    pub txid: String,
    pub direction: String,
    pub amount_atomic: u64,
    pub fee_atomic: u64,
    pub height: u64,
    pub timestamp: u64,
    pub subaddress_minor: u32,
}

/// Stored wrapped outgoing transaction proof secret row from `xmr_tx_secrets`.
#[derive(Clone, Debug)]
pub struct StoredTxSecret {
    pub txid: String,
    pub recipient_address: String,
    pub wrapped_secret_blob: Vec<u8>,
    pub tx_extra: Vec<u8>,
}

/// Stored BTC -> XMR swap tracking row from `btc_xmr_swaps`.
#[derive(Clone, Debug)]
pub struct StoredSwap {
    pub swap_id: String,
    pub provider: String,
    pub btc_amount: String,
    pub quoted_xmr: String,
    pub launch_url: String,
    pub subaddress_minor: u32,
    pub subaddress: String,
    pub btc_deposit_address: String,
    pub btc_deposit_amount: String,
    pub provider_order_id: String,
    pub order_url: String,
    pub status: String,
    pub payout_txid: String,
    pub received_atomic: u64,
    pub created_epoch_secs: u64,
}

/// Thread-safe SQLite store backing `data_dir/xmr/wallet.db`.
pub struct XmrStore {
    conn: Mutex<Connection>,
}

impl XmrStore {
    /// Open or initialize `wallet.db` at `db_path`.
    pub fn open(db_path: &Path) -> Result<Self> {
        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("cannot create XMR directory {}", parent.display()))?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(parent, std::fs::Permissions::from_mode(0o700));
            }
        }

        let conn = Connection::open(db_path)
            .with_context(|| format!("cannot open XMR database {}", db_path.display()))?;
        #[cfg(unix)]
        if db_path.exists() {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(db_path, std::fs::Permissions::from_mode(0o600));
        }

        conn.execute_batch(
            r#"
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;
            PRAGMA foreign_keys = ON;

            CREATE TABLE IF NOT EXISTS xmr_settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS xmr_wallet_meta (
                id                      INTEGER PRIMARY KEY CHECK (id = 1),
                wallet_label            TEXT NOT NULL DEFAULT 'Trap Light Wallet',
                primary_address         TEXT NOT NULL,
                wrapped_spend_key       BLOB NOT NULL,
                view_key_bytes          BLOB NOT NULL,
                active_subaddress_index INTEGER NOT NULL DEFAULT 1,
                restore_height          INTEGER NOT NULL DEFAULT 0,
                birthday_pending        INTEGER NOT NULL DEFAULT 0,
                synced_height           INTEGER NOT NULL DEFAULT 0,
                daemon_height           INTEGER NOT NULL DEFAULT 0,
                last_sync_epoch_secs    INTEGER NOT NULL DEFAULT 0,
                sync_endpoint           TEXT NOT NULL DEFAULT '',
                sync_mode               TEXT NOT NULL DEFAULT 'monerod_onion'
            );

            CREATE TABLE IF NOT EXISTS xmr_subaddresses (
                minor_index INTEGER PRIMARY KEY,
                label       TEXT NOT NULL,
                address     TEXT NOT NULL UNIQUE
            );

            CREATE TABLE IF NOT EXISTS xmr_outputs (
                txid             TEXT NOT NULL,
                vout             INTEGER NOT NULL,
                subaddress_minor INTEGER NOT NULL,
                amount_atomic    TEXT NOT NULL,
                height           INTEGER NOT NULL,
                timestamp        INTEGER NOT NULL,
                output_blob      BLOB NOT NULL,
                key_image        BLOB UNIQUE,
                spent_in_txid    TEXT,
                PRIMARY KEY (txid, vout)
            );

            CREATE TABLE IF NOT EXISTS xmr_transactions (
                txid             TEXT PRIMARY KEY,
                direction        TEXT NOT NULL,
                amount_atomic    TEXT NOT NULL,
                fee_atomic       TEXT NOT NULL,
                height           INTEGER NOT NULL,
                timestamp        INTEGER NOT NULL,
                subaddress_minor INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS xmr_tx_secrets (
                txid                TEXT PRIMARY KEY,
                recipient_address   TEXT NOT NULL,
                wrapped_secret_blob BLOB NOT NULL,
                tx_extra            BLOB NOT NULL
            );

            CREATE TABLE IF NOT EXISTS btc_xmr_swaps (
                swap_id             TEXT PRIMARY KEY,
                provider            TEXT NOT NULL,
                btc_amount          TEXT NOT NULL,
                quoted_xmr          TEXT NOT NULL,
                launch_url          TEXT NOT NULL,
                subaddress_minor    INTEGER NOT NULL,
                subaddress          TEXT NOT NULL,
                btc_deposit_address TEXT NOT NULL DEFAULT '',
                btc_deposit_amount  TEXT NOT NULL DEFAULT '',
                provider_order_id   TEXT NOT NULL DEFAULT '',
                order_url           TEXT NOT NULL DEFAULT '',
                status              TEXT NOT NULL,
                payout_txid         TEXT NOT NULL DEFAULT '',
                received_atomic     TEXT NOT NULL DEFAULT '0',
                created_epoch_secs  INTEGER NOT NULL
            );
            "#,
        )
        .context("cannot initialize XMR SQLite schema")?;

        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Connection> {
        match self.conn.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    /// Read configured `(sync_endpoint, sync_mode)` even before a wallet exists.
    pub fn configured_endpoint_and_mode(&self) -> Result<(String, String)> {
        let conn = self.lock();
        if let Some((ep, mode)) = conn
            .query_row(
                "SELECT sync_endpoint, sync_mode FROM xmr_wallet_meta WHERE id = 1",
                [],
                |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
            )
            .optional()?
        {
            return Ok((ep, mode));
        }

        let ep = conn
            .query_row(
                "SELECT value FROM xmr_settings WHERE key = ?1",
                params![SETTING_SYNC_ENDPOINT],
                |r| r.get::<_, String>(0),
            )
            .optional()?
            .unwrap_or_default();
        let mode = conn
            .query_row(
                "SELECT value FROM xmr_settings WHERE key = ?1",
                params![SETTING_SYNC_MODE],
                |r| r.get::<_, String>(0),
            )
            .optional()?
            .unwrap_or_else(|| SYNC_MODE_MONEROD_ONION.to_string());
        Ok((ep, mode))
    }

    /// Persist custom `sync_endpoint` and `sync_mode`.
    /// When `sync_endpoint` changes to a different URL than before on an existing wallet,
    /// clears cached `xmr_outputs`, `xmr_transactions`, and `xmr_tx_secrets` from the previous
    /// endpoint and resets `restore_height = 0, synced_height = 0, daemon_height = 0, birthday_pending = true`
    /// so chain switches (e.g. mainnet `.onion` <-> custom/regtest `.onion`) rescan cleanly without contamination.
    pub fn set_endpoint_and_mode(&self, endpoint: &str, mode: &str) -> Result<()> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        let existing_ep: Option<String> = tx
            .query_row(
                "SELECT sync_endpoint FROM xmr_wallet_meta WHERE id = 1",
                [],
                |r| r.get(0),
            )
            .optional()?;

        tx.execute(
            "INSERT INTO xmr_settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![SETTING_SYNC_ENDPOINT, endpoint],
        )?;
        tx.execute(
            "INSERT INTO xmr_settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![SETTING_SYNC_MODE, mode],
        )?;

        let endpoint_changed = existing_ep.as_deref().is_some_and(|old| {
            old.trim().trim_end_matches('/') != endpoint.trim().trim_end_matches('/')
        });
        if endpoint_changed {
            tx.execute("DELETE FROM xmr_outputs", [])?;
            tx.execute("DELETE FROM xmr_transactions", [])?;
            tx.execute("DELETE FROM xmr_tx_secrets", [])?;
            tx.execute(
                "UPDATE xmr_wallet_meta
                 SET sync_endpoint = ?1, sync_mode = ?2,
                     restore_height = 0, birthday_pending = 1,
                     synced_height = 0, daemon_height = 0, last_sync_epoch_secs = 0
                 WHERE id = 1",
                params![endpoint, mode],
            )?;
        } else {
            tx.execute(
                "UPDATE xmr_wallet_meta SET sync_endpoint = ?1, sync_mode = ?2 WHERE id = 1",
                params![endpoint, mode],
            )?;
        }
        tx.commit()?;
        Ok(())
    }

    /// Replace wallet state atomically with a newly created or restored wallet.
    #[allow(clippy::too_many_arguments)]
    pub fn save_new_wallet(
        &self,
        wallet_label: &str,
        primary_address: &str,
        wrapped_spend_key: &[u8],
        view_key_bytes: &[u8],
        initial_subaddress: &str,
        restore_height: u64,
        birthday_pending: bool,
        synced_height: u64,
        daemon_height: u64,
        sync_endpoint: &str,
        sync_mode: &str,
    ) -> Result<()> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        tx.execute("DELETE FROM btc_xmr_swaps", [])?;
        tx.execute("DELETE FROM xmr_tx_secrets", [])?;
        tx.execute("DELETE FROM xmr_transactions", [])?;
        tx.execute("DELETE FROM xmr_outputs", [])?;
        tx.execute("DELETE FROM xmr_subaddresses", [])?;
        tx.execute("DELETE FROM xmr_wallet_meta", [])?;

        tx.execute(
            "INSERT INTO xmr_wallet_meta (
                id, wallet_label, primary_address, wrapped_spend_key, view_key_bytes,
                active_subaddress_index, restore_height, birthday_pending,
                synced_height, daemon_height, last_sync_epoch_secs, sync_endpoint, sync_mode
            ) VALUES (1, ?1, ?2, ?3, ?4, 1, ?5, ?6, ?7, ?8, 0, ?9, ?10)",
            params![
                wallet_label,
                primary_address,
                wrapped_spend_key,
                view_key_bytes,
                u64_to_sql(restore_height),
                i64::from(birthday_pending),
                u64_to_sql(synced_height),
                u64_to_sql(daemon_height),
                sync_endpoint,
                sync_mode,
            ],
        )?;

        tx.execute(
            "INSERT INTO xmr_subaddresses (minor_index, label, address) VALUES (1, ?1, ?2)",
            params![wallet_label, initial_subaddress],
        )?;

        tx.commit()?;
        Ok(())
    }

    /// Delete the stored wallet and all derived outputs, transactions, subaddresses, and swaps.
    pub fn delete_wallet(&self) -> Result<bool> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        let deleted = tx.execute("DELETE FROM xmr_wallet_meta", [])?;
        tx.execute("DELETE FROM xmr_subaddresses", [])?;
        tx.execute("DELETE FROM xmr_outputs", [])?;
        tx.execute("DELETE FROM xmr_transactions", [])?;
        tx.execute("DELETE FROM xmr_tx_secrets", [])?;
        tx.execute("DELETE FROM btc_xmr_swaps", [])?;
        tx.commit()?;
        Ok(deleted > 0)
    }

    /// Re-wrap `wrapped_spend_key` and all `xmr_tx_secrets.wrapped_secret_blob` rows
    /// under `new_master` when Keystore policy rotates.
    pub fn rewrap_spend_key(
        &self,
        old_master: &dyn MasterKeyProvider,
        new_master: &dyn MasterKeyProvider,
    ) -> Result<bool> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        let existing: Option<Vec<u8>> = tx
            .query_row(
                "SELECT wrapped_spend_key FROM xmr_wallet_meta WHERE id = 1",
                [],
                |r| r.get(0),
            )
            .optional()?;
        let Some(blob) = existing else {
            return Ok(false);
        };
        let mut plain = old_master.unwrap(&blob)?;
        let rewrapped = new_master.wrap(&plain);
        zeroize_slice(&mut plain);
        let new_blob = rewrapped?;
        tx.execute(
            "UPDATE xmr_wallet_meta SET wrapped_spend_key = ?1 WHERE id = 1",
            params![new_blob],
        )?;

        let secret_rows: Vec<(String, Vec<u8>)> = {
            let mut stmt = tx.prepare("SELECT txid, wrapped_secret_blob FROM xmr_tx_secrets")?;
            let mapped = stmt.query_map([], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?))
            })?;
            mapped.collect::<std::result::Result<Vec<_>, _>>()?
        };
        for (txid, old_sec_blob) in secret_rows {
            let mut sec_plain = old_master.unwrap(&old_sec_blob)?;
            let sec_rewrapped = new_master.wrap(&sec_plain);
            zeroize_slice(&mut sec_plain);
            let new_sec_blob = sec_rewrapped?;
            tx.execute(
                "UPDATE xmr_tx_secrets SET wrapped_secret_blob = ?1 WHERE txid = ?2",
                params![new_sec_blob, txid],
            )?;
        }

        tx.commit()?;
        Ok(true)
    }

    /// Fetch `xmr_wallet_meta` if a wallet exists.
    pub fn wallet_meta(&self) -> Result<Option<StoredWalletMeta>> {
        let conn = self.lock();
        conn.query_row(
            "SELECT wallet_label, primary_address, wrapped_spend_key, view_key_bytes,
                    active_subaddress_index, restore_height, birthday_pending,
                    synced_height, daemon_height, last_sync_epoch_secs, sync_endpoint, sync_mode
             FROM xmr_wallet_meta WHERE id = 1",
            [],
            |r| {
                Ok(StoredWalletMeta {
                    wallet_label: r.get(0)?,
                    primary_address: r.get(1)?,
                    wrapped_spend_key: r.get(2)?,
                    view_key_bytes: r.get(3)?,
                    active_subaddress_index: sql_to_u32(r.get(4)?),
                    restore_height: sql_to_u64(r.get(5)?),
                    birthday_pending: r.get::<_, i64>(6)? != 0,
                    synced_height: sql_to_u64(r.get(7)?),
                    daemon_height: sql_to_u64(r.get(8)?),
                    last_sync_epoch_secs: sql_to_u64(r.get(9)?),
                    sync_endpoint: r.get(10)?,
                    sync_mode: r.get(11)?,
                })
            },
        )
        .optional()
        .map_err(Into::into)
    }

    /// Update sync progress fields on `xmr_wallet_meta`.
    pub fn update_sync_progress(
        &self,
        restore_height: u64,
        birthday_pending: bool,
        synced_height: u64,
        daemon_height: u64,
        last_sync_epoch_secs: u64,
    ) -> Result<()> {
        let conn = self.lock();
        conn.execute(
            "UPDATE xmr_wallet_meta
             SET restore_height = ?1, birthday_pending = ?2, synced_height = ?3,
                 daemon_height = ?4, last_sync_epoch_secs = ?5
             WHERE id = 1",
            params![
                u64_to_sql(restore_height),
                i64::from(birthday_pending),
                u64_to_sql(synced_height),
                u64_to_sql(daemon_height),
                u64_to_sql(last_sync_epoch_secs),
            ],
        )?;
        Ok(())
    }

    /// Highest allocated subaddress minor index (`>= 1`).
    pub fn max_subaddress_minor(&self) -> Result<u32> {
        let conn = self.lock();
        let max_opt: Option<i64> =
            conn.query_row("SELECT MAX(minor_index) FROM xmr_subaddresses", [], |r| {
                r.get(0)
            })?;
        Ok(max_opt.map(sql_to_u32).unwrap_or(1).max(1))
    }

    /// Insert a `(0, minor)` subaddress and optionally mark it active.
    pub fn insert_subaddress(
        &self,
        minor_index: u32,
        label: &str,
        address: &str,
        set_active: bool,
    ) -> Result<()> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;
        tx.execute(
            "INSERT INTO xmr_subaddresses (minor_index, label, address)
             VALUES (?1, ?2, ?3)
             ON CONFLICT(minor_index) DO NOTHING",
            params![i64::from(minor_index), label, address],
        )?;
        if set_active {
            tx.execute(
                "UPDATE xmr_wallet_meta SET active_subaddress_index = ?1 WHERE id = 1",
                params![i64::from(minor_index)],
            )?;
        }
        tx.commit()?;
        Ok(())
    }

    /// List all stored `(0, minor)` subaddresses ordered by `minor_index ASC`.
    pub fn list_subaddresses(&self) -> Result<Vec<StoredSubaddress>> {
        let conn = self.lock();
        let mut stmt = conn.prepare(
            "SELECT minor_index, label, address FROM xmr_subaddresses ORDER BY minor_index ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok(StoredSubaddress {
                minor_index: sql_to_u32(r.get(0)?),
                label: r.get(1)?,
                address: r.get(2)?,
            })
        })?;
        rows.collect::<std::result::Result<Vec<_>, _>>()
            .map_err(Into::into)
    }

    /// Find an existing `(0, minor)` subaddress matching `label` case-insensitively.
    pub fn find_subaddress_by_label_ci(&self, label: &str) -> Result<Option<StoredSubaddress>> {
        let conn = self.lock();
        conn.query_row(
            "SELECT minor_index, label, address FROM xmr_subaddresses
             WHERE lower(label) = lower(?1)
             ORDER BY minor_index ASC LIMIT 1",
            params![label],
            |r| {
                Ok(StoredSubaddress {
                    minor_index: sql_to_u32(r.get(0)?),
                    label: r.get(1)?,
                    address: r.get(2)?,
                })
            },
        )
        .optional()
        .map_err(Into::into)
    }

    /// Mark an existing `minor_index` as the active subaddress in `xmr_wallet_meta`.
    pub fn set_active_subaddress(&self, minor_index: u32) -> Result<()> {
        let conn = self.lock();
        conn.execute(
            "UPDATE xmr_wallet_meta SET active_subaddress_index = ?1 WHERE id = 1",
            params![i64::from(minor_index)],
        )?;
        Ok(())
    }

    /// Upsert a scanned or LWS-imported [`StoredOutput`].
    pub fn upsert_output(&self, out: &StoredOutput) -> Result<()> {
        let conn = self.lock();
        let ki_slice: Option<&[u8]> = out.key_image.as_ref().map(|k| k.as_slice());
        conn.execute(
            "INSERT INTO xmr_outputs (
                txid, vout, subaddress_minor, amount_atomic, height, timestamp,
                output_blob, key_image, spent_in_txid
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
            ON CONFLICT(txid, vout) DO UPDATE SET
                height = excluded.height,
                timestamp = excluded.timestamp,
                output_blob = CASE WHEN length(excluded.output_blob) > 0 THEN excluded.output_blob ELSE xmr_outputs.output_blob END,
                key_image = COALESCE(excluded.key_image, xmr_outputs.key_image),
                spent_in_txid = COALESCE(excluded.spent_in_txid, xmr_outputs.spent_in_txid)",
            params![
                out.txid,
                u64_to_sql(out.vout),
                i64::from(out.subaddress_minor),
                out.amount_atomic.to_string(),
                u64_to_sql(out.height),
                u64_to_sql(out.timestamp),
                out.output_blob,
                ki_slice,
                out.spent_in_txid,
            ],
        )?;
        Ok(())
    }

    /// Populate `key_image` for an output that was previously scanned while Keystore was locked.
    pub fn set_output_key_image(&self, txid: &str, vout: u64, key_image: &[u8; 32]) -> Result<()> {
        let conn = self.lock();
        conn.execute(
            "UPDATE xmr_outputs SET key_image = ?1 WHERE txid = ?2 AND vout = ?3",
            params![key_image.as_slice(), txid, u64_to_sql(vout)],
        )?;
        Ok(())
    }

    /// Mark an owned output spent if `key_image` matches any row in `xmr_outputs`.
    /// Returns `Some(amount_atomic)` of the spent output when matched.
    pub fn mark_spent_by_key_image(
        &self,
        key_image: &[u8; 32],
        spending_txid: &str,
    ) -> Result<Option<u64>> {
        let conn = self.lock();
        let existing: Option<String> = conn
            .query_row(
                "SELECT amount_atomic FROM xmr_outputs WHERE key_image = ?1",
                params![key_image.as_slice()],
                |r| r.get(0),
            )
            .optional()?;
        let Some(amt_str) = existing else {
            return Ok(None);
        };
        conn.execute(
            "UPDATE xmr_outputs SET spent_in_txid = ?1 WHERE key_image = ?2",
            params![spending_txid, key_image.as_slice()],
        )?;
        Ok(Some(amt_str.parse::<u64>().unwrap_or(0)))
    }

    /// List all stored outputs.
    pub fn list_outputs(&self) -> Result<Vec<StoredOutput>> {
        let conn = self.lock();
        let mut stmt = conn.prepare(
            "SELECT txid, vout, subaddress_minor, amount_atomic, height, timestamp,
                    output_blob, key_image, spent_in_txid
             FROM xmr_outputs
             ORDER BY height ASC, vout ASC",
        )?;
        let rows = stmt.query_map([], |r| {
            let amt_str: String = r.get(3)?;
            let ki_blob: Option<Vec<u8>> = r.get(7)?;
            let ki_arr = ki_blob.and_then(|b| <[u8; 32]>::try_from(b.as_slice()).ok());
            Ok(StoredOutput {
                txid: r.get(0)?,
                vout: sql_to_u64(r.get(1)?),
                subaddress_minor: sql_to_u32(r.get(2)?),
                amount_atomic: amt_str.parse::<u64>().unwrap_or(0),
                height: sql_to_u64(r.get(4)?),
                timestamp: sql_to_u64(r.get(5)?),
                output_blob: r.get(6)?,
                key_image: ki_arr,
                spent_in_txid: r.get(8)?,
            })
        })?;
        rows.collect::<std::result::Result<Vec<_>, _>>()
            .map_err(Into::into)
    }

    /// Upsert a wallet transaction summary into `xmr_transactions`.
    /// When confirming a previously broadcast outgoing transaction (`height == 0` -> `height > 0`),
    /// preserves the exact locally recorded outgoing `amount_atomic` if `preserve_existing_out_amount` is set.
    pub fn upsert_transaction(
        &self,
        tx: &StoredTransaction,
        preserve_existing_out_amount: bool,
    ) -> Result<()> {
        let conn = self.lock();
        if preserve_existing_out_amount && tx.direction == "out" {
            conn.execute(
                "INSERT INTO xmr_transactions (
                    txid, direction, amount_atomic, fee_atomic, height, timestamp, subaddress_minor
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                ON CONFLICT(txid) DO UPDATE SET
                    direction = 'out',
                    amount_atomic = CASE
                        WHEN xmr_transactions.direction = 'out' AND xmr_transactions.amount_atomic != '0'
                        THEN xmr_transactions.amount_atomic
                        ELSE excluded.amount_atomic
                    END,
                    fee_atomic = excluded.fee_atomic,
                    height = excluded.height,
                    timestamp = excluded.timestamp",
                params![
                    tx.txid,
                    tx.direction,
                    tx.amount_atomic.to_string(),
                    tx.fee_atomic.to_string(),
                    u64_to_sql(tx.height),
                    u64_to_sql(tx.timestamp),
                    i64::from(tx.subaddress_minor),
                ],
            )?;
        } else {
            conn.execute(
                "INSERT INTO xmr_transactions (
                    txid, direction, amount_atomic, fee_atomic, height, timestamp, subaddress_minor
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                ON CONFLICT(txid) DO UPDATE SET
                    direction = excluded.direction,
                    amount_atomic = excluded.amount_atomic,
                    fee_atomic = excluded.fee_atomic,
                    height = excluded.height,
                    timestamp = excluded.timestamp,
                    subaddress_minor = excluded.subaddress_minor",
                params![
                    tx.txid,
                    tx.direction,
                    tx.amount_atomic.to_string(),
                    tx.fee_atomic.to_string(),
                    u64_to_sql(tx.height),
                    u64_to_sql(tx.timestamp),
                    i64::from(tx.subaddress_minor),
                ],
            )?;
        }
        Ok(())
    }

    /// List most recent transactions (`height = 0` mempool first, then newest height/timestamp).
    pub fn list_recent_transactions(&self, limit: usize) -> Result<Vec<StoredTransaction>> {
        let conn = self.lock();
        let mut stmt = conn.prepare(
            "SELECT txid, direction, amount_atomic, fee_atomic, height, timestamp, subaddress_minor
             FROM xmr_transactions
             ORDER BY CASE WHEN height = 0 THEN 1 ELSE 0 END DESC, height DESC, timestamp DESC
             LIMIT ?1",
        )?;
        let rows = stmt.query_map(params![limit as i64], |r| {
            let amt: String = r.get(2)?;
            let fee: String = r.get(3)?;
            Ok(StoredTransaction {
                txid: r.get(0)?,
                direction: r.get(1)?,
                amount_atomic: amt.parse::<u64>().unwrap_or(0),
                fee_atomic: fee.parse::<u64>().unwrap_or(0),
                height: sql_to_u64(r.get(4)?),
                timestamp: sql_to_u64(r.get(5)?),
                subaddress_minor: sql_to_u32(r.get(6)?),
            })
        })?;
        rows.collect::<std::result::Result<Vec<_>, _>>()
            .map_err(Into::into)
    }

    /// Persist wrapped outgoing proof material (`ovk` + spent input `(key, commitment)` pairs + `tx.extra`).
    pub fn upsert_tx_secret(
        &self,
        txid: &str,
        recipient_address: &str,
        wrapped_secret_blob: &[u8],
        tx_extra: &[u8],
    ) -> Result<()> {
        let clean_txid = txid.trim().to_ascii_lowercase();
        let conn = self.lock();
        conn.execute(
            "INSERT INTO xmr_tx_secrets (txid, recipient_address, wrapped_secret_blob, tx_extra)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(txid) DO UPDATE SET
                 recipient_address = excluded.recipient_address,
                 wrapped_secret_blob = excluded.wrapped_secret_blob,
                 tx_extra = excluded.tx_extra",
            params![clean_txid, recipient_address, wrapped_secret_blob, tx_extra],
        )?;
        Ok(())
    }

    /// Load wrapped outgoing proof secret row by `txid` (`64-hex`).
    pub fn get_tx_secret(&self, txid: &str) -> Result<Option<StoredTxSecret>> {
        let clean_txid = txid.trim().to_ascii_lowercase();
        let conn = self.lock();
        conn.query_row(
            "SELECT txid, recipient_address, wrapped_secret_blob, tx_extra
             FROM xmr_tx_secrets
             WHERE lower(txid) = ?1",
            params![clean_txid],
            |r| {
                Ok(StoredTxSecret {
                    txid: r.get(0)?,
                    recipient_address: r.get(1)?,
                    wrapped_secret_blob: r.get(2)?,
                    tx_extra: r.get(3)?,
                })
            },
        )
        .optional()
        .map_err(Into::into)
    }

    /// Read `(txid -> recipient_address)` map from `xmr_tx_secrets` without touching `MasterKeyProvider`.
    pub fn list_tx_secret_recipients(&self) -> Result<HashMap<String, String>> {
        let conn = self.lock();
        let mut stmt = conn.prepare("SELECT txid, recipient_address FROM xmr_tx_secrets")?;
        let rows = stmt.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        let mut map = HashMap::new();
        for item in rows {
            let (txid, rcpt) = item?;
            map.insert(txid, rcpt);
        }
        Ok(map)
    }

    /// Insert a newly tracked BTC -> XMR swap.
    pub fn insert_swap(&self, swap: &StoredSwap) -> Result<()> {
        let conn = self.lock();
        conn.execute(
            "INSERT INTO btc_xmr_swaps (
                swap_id, provider, btc_amount, quoted_xmr, launch_url,
                subaddress_minor, subaddress, btc_deposit_address, btc_deposit_amount,
                provider_order_id, order_url, status, payout_txid, received_atomic, created_epoch_secs
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
            params![
                swap.swap_id,
                swap.provider,
                swap.btc_amount,
                swap.quoted_xmr,
                swap.launch_url,
                i64::from(swap.subaddress_minor),
                swap.subaddress,
                swap.btc_deposit_address,
                swap.btc_deposit_amount,
                swap.provider_order_id,
                swap.order_url,
                swap.status,
                swap.payout_txid,
                swap.received_atomic.to_string(),
                u64_to_sql(swap.created_epoch_secs),
            ],
        )?;
        Ok(())
    }

    /// Update deposit address / order metadata on a tracked swap (if `swap_id` is empty, targets
    /// the most recent open swap).
    pub fn update_swap_order(
        &self,
        swap_id: &str,
        btc_deposit_address: &str,
        btc_deposit_amount: &str,
        provider_order_id: &str,
        order_url: &str,
        status: &str,
    ) -> Result<StoredSwap> {
        let mut conn = self.lock();
        let tx = conn.transaction()?;

        let target_id: String = if !swap_id.trim().is_empty() {
            swap_id.trim().to_string()
        } else {
            tx.query_row(
                "SELECT swap_id FROM btc_xmr_swaps
                 WHERE status NOT IN ('completed', 'dismissed')
                 ORDER BY created_epoch_secs DESC, rowid DESC LIMIT 1",
                [],
                |r| r.get(0),
            )
            .optional()?
            .ok_or_else(|| anyhow!("no active BTC->XMR swap found to update"))?
        };

        let current: Option<StoredSwap> = query_swap_by_id(&tx, &target_id)?;
        let Some(cur) = current else {
            bail!("swap {target_id:?} not found");
        };

        let next_addr = if btc_deposit_address.trim().is_empty() {
            cur.btc_deposit_address.clone()
        } else {
            btc_deposit_address.trim().to_string()
        };
        let next_amt = if btc_deposit_amount.trim().is_empty() {
            cur.btc_deposit_amount.clone()
        } else {
            btc_deposit_amount.trim().to_string()
        };
        let next_order_id = if provider_order_id.trim().is_empty() {
            cur.provider_order_id.clone()
        } else {
            provider_order_id.trim().to_string()
        };
        let next_order_url = if order_url.trim().is_empty() {
            cur.order_url.clone()
        } else {
            order_url.trim().to_string()
        };
        let next_status = if cur.status == "completed" {
            "completed".to_string()
        } else if !status.trim().is_empty() {
            status.trim().to_string()
        } else if !next_addr.is_empty() {
            "awaiting_btc_deposit".to_string()
        } else {
            cur.status.clone()
        };

        tx.execute(
            "UPDATE btc_xmr_swaps
             SET btc_deposit_address = ?1,
                 btc_deposit_amount = ?2,
                 provider_order_id = ?3,
                 order_url = ?4,
                 status = ?5
             WHERE swap_id = ?6",
            params![
                next_addr,
                next_amt,
                next_order_id,
                next_order_url,
                next_status,
                target_id,
            ],
        )?;

        let updated = query_swap_by_id(&tx, &target_id)?
            .ok_or_else(|| anyhow!("updated swap disappeared"))?;
        tx.commit()?;
        Ok(updated)
    }

    /// Delete/dismiss a swap by `swap_id`.
    pub fn dismiss_swap(&self, swap_id: &str) -> Result<bool> {
        let conn = self.lock();
        let deleted = conn.execute(
            "DELETE FROM btc_xmr_swaps WHERE swap_id = ?1",
            params![swap_id.trim()],
        )?;
        Ok(deleted > 0)
    }

    /// List all non-dismissed swaps ordered newest first.
    pub fn list_swaps(&self) -> Result<Vec<StoredSwap>> {
        let conn = self.lock();
        let mut stmt = conn.prepare(
            "SELECT swap_id, provider, btc_amount, quoted_xmr, launch_url,
                    subaddress_minor, subaddress, btc_deposit_address, btc_deposit_amount,
                    provider_order_id, order_url, status, payout_txid, received_atomic, created_epoch_secs
             FROM btc_xmr_swaps
             WHERE status != 'dismissed'
             ORDER BY created_epoch_secs DESC, rowid DESC",
        )?;
        let rows = stmt.query_map([], map_swap_row)?;
        rows.collect::<std::result::Result<Vec<_>, _>>()
            .map_err(Into::into)
    }

    /// Automatically transition any tracked `btc_xmr_swaps` row whose dedicated `subaddress_minor`
    /// has received on-chain funds in `xmr_outputs` to `status = "completed"`.
    pub fn reconcile_completed_swaps(&self) -> Result<usize> {
        let outputs = self.list_outputs()?;
        let swaps = self.list_swaps()?;
        let conn = self.lock();
        let mut completed_count = 0usize;

        for swap in swaps {
            if swap.subaddress_minor == 0 {
                continue;
            }
            let mut total_received = 0u64;
            let mut latest_txid = String::new();
            let mut latest_height = 0u64;
            for out in outputs
                .iter()
                .filter(|o| o.subaddress_minor == swap.subaddress_minor)
            {
                total_received = total_received.saturating_add(out.amount_atomic);
                if latest_txid.is_empty() || out.height >= latest_height {
                    latest_height = out.height;
                    latest_txid = out.txid.clone();
                }
            }
            if total_received > 0
                && (swap.status != "completed"
                    || swap.received_atomic != total_received
                    || swap.payout_txid != latest_txid)
            {
                conn.execute(
                    "UPDATE btc_xmr_swaps
                     SET status = 'completed', payout_txid = ?1, received_atomic = ?2
                     WHERE swap_id = ?3",
                    params![latest_txid, total_received.to_string(), swap.swap_id],
                )?;
                completed_count += 1;
            }
        }
        Ok(completed_count)
    }
}

fn query_swap_by_id(conn: &Connection, swap_id: &str) -> Result<Option<StoredSwap>> {
    conn.query_row(
        "SELECT swap_id, provider, btc_amount, quoted_xmr, launch_url,
                subaddress_minor, subaddress, btc_deposit_address, btc_deposit_amount,
                provider_order_id, order_url, status, payout_txid, received_atomic, created_epoch_secs
         FROM btc_xmr_swaps WHERE swap_id = ?1",
        params![swap_id],
        map_swap_row,
    )
    .optional()
    .map_err(Into::into)
}

fn map_swap_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<StoredSwap> {
    let rec_str: String = r.get(13)?;
    Ok(StoredSwap {
        swap_id: r.get(0)?,
        provider: r.get(1)?,
        btc_amount: r.get(2)?,
        quoted_xmr: r.get(3)?,
        launch_url: r.get(4)?,
        subaddress_minor: sql_to_u32(r.get(5)?),
        subaddress: r.get(6)?,
        btc_deposit_address: r.get(7)?,
        btc_deposit_amount: r.get(8)?,
        provider_order_id: r.get(9)?,
        order_url: r.get(10)?,
        status: r.get(11)?,
        payout_txid: r.get(12)?,
        received_atomic: rec_str.parse::<u64>().unwrap_or(0),
        created_epoch_secs: sql_to_u64(r.get(14)?),
    })
}

fn u64_to_sql(v: u64) -> i64 {
    i64::try_from(v).unwrap_or(i64::MAX)
}

fn sql_to_u64(v: i64) -> u64 {
    u64::try_from(v.max(0)).unwrap_or(0)
}

fn sql_to_u32(v: i64) -> u32 {
    u32::try_from(v.max(0)).unwrap_or(0)
}
