//! Loopback-only HTTP/1.1 transports for `monerod` (`127.0.0.1:18082`) and
//! `bitcoind` (`127.0.0.1:18443`).
//!
//! All outbound sockets strictly validate `SocketAddr::ip().is_loopback()` before connecting.

use std::{future::Future, net::SocketAddr, time::Duration};

use anyhow::{anyhow, bail, Context, Result};
use base64::{engine::general_purpose::STANDARD as BASE64_STANDARD, Engine as _};
use monero_daemon_rpc::HttpTransport;
use monero_wallet::{interface::InterfaceError, transaction::Transaction};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::timeout,
};

const DEFAULT_MAX_BODY_BYTES: usize = 100 * 1024 * 1024;
const MAX_HEADER_BYTES: usize = 64 * 1024;
const RPC_TIMEOUT: Duration = Duration::from_secs(45);

/// Parse an `http://127.0.0.1:<port>[/path]` URL into a strictly loopback `(SocketAddr, base_path)`.
pub fn parse_loopback_http_endpoint(raw: &str) -> Result<(SocketAddr, String)> {
    let trimmed = raw.trim();
    let rest = trimmed
        .strip_prefix("http://")
        .ok_or_else(|| anyhow!("loopback RPC URL must start with http:// (got `{trimmed}`)"))?;
    let (host_port, path) = match rest.find('/') {
        Some(idx) => (&rest[..idx], &rest[idx..]),
        None => (rest, ""),
    };
    let addr: SocketAddr = host_port
        .parse()
        .with_context(|| format!("invalid SocketAddr `{host_port}` in `{trimmed}`"))?;
    if !addr.ip().is_loopback() || addr.port() == 0 {
        bail!("refusing non-loopback RPC endpoint {addr}");
    }
    Ok((addr, path.trim_end_matches('/').to_string()))
}

/// Loopback HTTP/1.1 transport implementing [`monero_daemon_rpc::HttpTransport`] and
/// admin `monerod` RPC helpers (`generateblocks`, `get_transactions`, `get_info`).
#[derive(Clone, Debug)]
pub struct LoopbackMoneroTransport {
    addr: SocketAddr,
    base_path: String,
}

impl LoopbackMoneroTransport {
    pub fn new(endpoint_url: &str) -> Result<Self> {
        let (addr, base_path) = parse_loopback_http_endpoint(endpoint_url)?;
        Ok(Self { addr, base_path })
    }

    pub fn addr(&self) -> SocketAddr {
        self.addr
    }

    async fn post_raw(
        &self,
        route: &str,
        content_type: &str,
        body: &[u8],
        max_resp_bytes: usize,
    ) -> Result<(u16, Vec<u8>)> {
        let clean_route = route.trim_start_matches('/');
        let path = if self.base_path.is_empty() {
            format!("/{clean_route}")
        } else {
            format!("{}/{clean_route}", self.base_path)
        };
        http11_post_loopback(self.addr, &path, content_type, None, body, max_resp_bytes).await
    }

    /// Query `monerod` `get_info` and return `height` (number of blocks in chain).
    pub async fn get_info_height(&self) -> Result<u64> {
        let payload = json!({
            "jsonrpc": "2.0",
            "id": "0",
            "method": "get_info",
        });
        let (status, body) = self
            .post_raw(
                "json_rpc",
                "application/json",
                payload.to_string().as_bytes(),
                256 * 1024,
            )
            .await
            .context("monerod get_info HTTP request failed")?;
        if status != 200 {
            bail!("monerod get_info returned HTTP {status}");
        }
        let val: Value =
            serde_json::from_slice(&body).context("invalid JSON from monerod get_info")?;
        if let Some(err) = val.get("error") {
            bail!("monerod get_info JSON-RPC error: {err}");
        }
        let status_str = val
            .pointer("/result/status")
            .and_then(Value::as_str)
            .unwrap_or("");
        if status_str != "OK" {
            bail!("monerod get_info non-OK status: {status_str:?}");
        }
        val.pointer("/result/height")
            .and_then(Value::as_u64)
            .ok_or_else(|| anyhow!("monerod get_info missing numeric height"))
    }

    /// Mine `count` regtest blocks on `127.0.0.1:18082` paying coinbase subsidy to `wallet_address`.
    /// Returns the updated `get_info` chain height (`block_count`).
    pub async fn generate_blocks(&self, count: u64, wallet_address: &str) -> Result<u64> {
        if count == 0 {
            return self.get_info_height().await;
        }
        let payload = json!({
            "jsonrpc": "2.0",
            "id": "0",
            "method": "generateblocks",
            "params": {
                "amount_of_blocks": count,
                "wallet_address": wallet_address,
            }
        });
        let (status, body) = self
            .post_raw(
                "json_rpc",
                "application/json",
                payload.to_string().as_bytes(),
                512 * 1024,
            )
            .await
            .context("monerod generateblocks HTTP request failed")?;
        if status != 200 {
            bail!("monerod generateblocks returned HTTP {status}");
        }
        let val: Value =
            serde_json::from_slice(&body).context("invalid JSON from monerod generateblocks")?;
        if let Some(err) = val.get("error") {
            bail!("monerod generateblocks JSON-RPC error: {err}");
        }
        let status_str = val
            .pointer("/result/status")
            .and_then(Value::as_str)
            .unwrap_or("");
        if status_str != "OK" {
            bail!("monerod generateblocks non-OK status: {status_str:?}");
        }
        self.get_info_height().await
    }

    /// Fetch full serialized [`Transaction`] (works for both 0-conf mempool and mined blocks!)
    /// from `/get_transactions` and verify its canonical `tx.hash()` matches `txid_hex`.
    pub async fn fetch_transaction(
        &self,
        txid_hex: &str,
    ) -> Result<(Transaction, Option<u64>, bool)> {
        let normalized = txid_hex.trim().to_ascii_lowercase();
        let expected_hash: [u8; 32] = hex::decode(&normalized)
            .context("txid must be valid hex")?
            .try_into()
            .map_err(|_| anyhow!("txid must be 32 bytes (64 hex chars)"))?;

        let payload = json!({
            "txs_hashes": [&normalized],
            "decode_as_json": false,
        });
        let (status, body) = self
            .post_raw(
                "get_transactions",
                "application/json",
                payload.to_string().as_bytes(),
                4 * 1024 * 1024,
            )
            .await
            .context("monerod /get_transactions failed")?;
        if status != 200 {
            bail!("monerod /get_transactions returned HTTP {status}");
        }

        #[derive(Default, Deserialize)]
        struct TxEntry {
            #[serde(default)]
            as_hex: String,
            #[serde(default)]
            in_pool: bool,
            #[serde(default)]
            block_height: Option<u64>,
        }

        #[derive(Default, Deserialize)]
        struct GetTxsResp {
            #[serde(default)]
            status: String,
            #[serde(default)]
            missed_tx: Vec<String>,
            #[serde(default)]
            txs_as_hex: Vec<String>,
            #[serde(default)]
            txs: Vec<TxEntry>,
        }

        let parsed: GetTxsResp =
            serde_json::from_slice(&body).context("invalid JSON from /get_transactions")?;
        if parsed.status != "OK" {
            bail!(
                "monerod /get_transactions non-OK status: {:?}",
                parsed.status
            );
        }
        if !parsed.missed_tx.is_empty() {
            bail!("transaction {normalized} not found in monerod mempool or blockchain");
        }

        let entry = parsed.txs.first();
        let hex_blob = parsed
            .txs_as_hex
            .first()
            .filter(|s| !s.is_empty())
            .or_else(|| entry.map(|e| &e.as_hex).filter(|s| !s.is_empty()))
            .ok_or_else(|| anyhow!("monerod /get_transactions returned empty transaction hex"))?;

        let raw_bytes =
            hex::decode(hex_blob).context("monerod returned invalid hex in txs_as_hex")?;
        let mut cursor = raw_bytes.as_slice();
        let tx = Transaction::read(&mut cursor)
            .map_err(|e| anyhow!("failed to deserialize Monero Transaction: {e}"))?;
        if !cursor.is_empty() {
            bail!(
                "trailing {} bytes after Monero Transaction deserialization",
                cursor.len()
            );
        }
        if tx.hash() != expected_hash {
            bail!(
                "fetched transaction hash {} does not match requested txid {}",
                hex::encode(tx.hash()),
                normalized
            );
        }

        let block_height = entry.and_then(|e| e.block_height);
        let in_pool = entry.is_some_and(|e| e.in_pool);
        Ok((tx, block_height, in_pool))
    }
}

impl HttpTransport for LoopbackMoneroTransport {
    fn post(
        &self,
        route: &str,
        body: Vec<u8>,
        response_size_limit: Option<usize>,
    ) -> impl Send + Future<Output = Result<Vec<u8>, InterfaceError>> {
        let this = self.clone();
        let route_owned = route.to_string();
        let is_bin = route_owned.ends_with(".bin");
        let content_type = if is_bin {
            "application/octet-stream"
        } else {
            "application/json"
        };
        let max_bytes = response_size_limit.unwrap_or(DEFAULT_MAX_BODY_BYTES);
        async move {
            let (status, resp_body) = this
                .post_raw(&route_owned, content_type, &body, max_bytes)
                .await
                .map_err(|e| InterfaceError::InterfaceError(e.to_string()))?;
            if status != 200 {
                return Err(InterfaceError::InvalidInterface(format!(
                    "monerod returned HTTP {status} for /{route_owned}"
                )));
            }
            Ok(resp_body)
        }
    }
}

/// Loopback Bitcoin Core (`bitcoind -regtest`) JSON-RPC 1.0 client with HTTP Basic Auth.
#[derive(Clone, Debug)]
pub struct LoopbackBitcoinClient {
    addr: SocketAddr,
    wallet_name: String,
    basic_auth_header: String,
}

impl LoopbackBitcoinClient {
    pub fn new(
        endpoint_url: &str,
        wallet_name: &str,
        rpc_user: &str,
        rpc_pass: &str,
    ) -> Result<Self> {
        let (addr, _) = parse_loopback_http_endpoint(endpoint_url)?;
        if wallet_name.trim().is_empty() {
            bail!("bitcoind wallet name must not be empty");
        }
        let creds = format!("{}:{rpc_pass}", rpc_user.trim());
        let basic_auth_header = format!("Basic {}", BASE64_STANDARD.encode(creds.as_bytes()));
        Ok(Self {
            addr,
            wallet_name: wallet_name.trim().to_string(),
            basic_auth_header,
        })
    }

    async fn rpc_call(&self, use_wallet: bool, method: &str, params: Value) -> Result<Value> {
        let path = if use_wallet {
            format!("/wallet/{}", self.wallet_name)
        } else {
            "/".to_string()
        };
        let payload = json!({
            "jsonrpc": "1.0",
            "id": "trap-xmr402",
            "method": method,
            "params": params,
        });
        let (status, body) = http11_post_loopback(
            self.addr,
            &path,
            "text/plain",
            Some(("Authorization", self.basic_auth_header.as_str())),
            payload.to_string().as_bytes(),
            1024 * 1024,
        )
        .await
        .with_context(|| format!("bitcoind RPC `{method}` HTTP transport failed"))?;

        let parsed: Value = serde_json::from_slice(&body).with_context(|| {
            format!("bitcoind RPC `{method}` (HTTP {status}) returned non-JSON body")
        })?;
        if let Some(err) = parsed.get("error").filter(|e| !e.is_null()) {
            bail!("bitcoind RPC `{method}` error: {err}");
        }
        if status != 200 {
            bail!("bitcoind RPC `{method}` returned HTTP {status}");
        }
        Ok(parsed.get("result").cloned().unwrap_or(Value::Null))
    }

    /// Query `getblockcount` on `bitcoind`.
    pub async fn get_block_count(&self) -> Result<u64> {
        let res = self.rpc_call(false, "getblockcount", json!([])).await?;
        res.as_u64()
            .ok_or_else(|| anyhow!("bitcoind getblockcount did not return u64"))
    }

    /// Ensure the `faucet` wallet is loaded and has `>= 105` blocks so coinbase BTC is mature.
    pub async fn ensure_faucet_ready(&self) -> Result<u64> {
        if self
            .rpc_call(true, "getwalletinfo", json!([]))
            .await
            .is_err()
            && self
                .rpc_call(false, "loadwallet", json!([&self.wallet_name]))
                .await
                .is_err()
        {
            let _ = self
                .rpc_call(
                    false,
                    "createwallet",
                    json!([&self.wallet_name, false, false, "", false, true, true]),
                )
                .await
                .context("failed to load or create bitcoind faucet wallet")?;
        }
        let blocks = self.get_block_count().await?;
        if blocks < 105 {
            let need = 105 - blocks;
            let addr = self.get_new_bech32_address("mining").await?;
            let _ = self
                .rpc_call(true, "generatetoaddress", json!([need, addr]))
                .await?;
            return self.get_block_count().await;
        }
        Ok(blocks)
    }

    /// Allocate a fresh native SegWit (`bcrt1q...`) Bech32 deposit address on `bitcoind`.
    pub async fn get_new_bech32_address(&self, label: &str) -> Result<String> {
        let res = self
            .rpc_call(true, "getnewaddress", json!([label, "bech32"]))
            .await?;
        let addr = res
            .as_str()
            .ok_or_else(|| anyhow!("bitcoind getnewaddress did not return string"))?
            .trim()
            .to_string();
        if !addr.starts_with("bcrt1q") && !addr.starts_with("bc1q") && !addr.starts_with("tb1q") {
            bail!("unexpected bitcoind bech32 address prefix `{addr}`");
        }
        Ok(addr)
    }

    /// Send `amount_btc` (`"0.01"`) from `"faucet"` to `deposit_addr`, mine `1` block to a fresh
    /// `"faucet"` address, and cryptographically verify `getreceivedbyaddress(deposit_addr, 1) >= amount_btc`.
    pub async fn fund_and_confirm_deposit(
        &self,
        deposit_addr: &str,
        amount_btc: &str,
        expected_min_sats: u64,
    ) -> Result<(String, u64)> {
        let txid_val = self
            .rpc_call(true, "sendtoaddress", json!([deposit_addr, amount_btc]))
            .await?;
        let btc_txid = txid_val
            .as_str()
            .ok_or_else(|| anyhow!("bitcoind sendtoaddress did not return txid string"))?
            .trim()
            .to_ascii_lowercase();
        if btc_txid.len() != 64 || !btc_txid.chars().all(|c| c.is_ascii_hexdigit()) {
            bail!("bitcoind sendtoaddress returned malformed txid `{btc_txid}`");
        }

        let mining_addr = self.get_new_bech32_address("mining").await?;
        let _ = self
            .rpc_call(true, "generatetoaddress", json!([1, mining_addr]))
            .await?;

        let recv_val = self
            .rpc_call(true, "getreceivedbyaddress", json!([deposit_addr, 1]))
            .await?;
        let received_sats = json_btc_value_to_sats(&recv_val)?;
        if received_sats < expected_min_sats {
            bail!(
                "bitcoind getreceivedbyaddress({deposit_addr}, 1) returned {received_sats} sats (< {expected_min_sats} sats)"
            );
        }

        let height = self.get_block_count().await?;
        Ok((btc_txid, height))
    }
}

/// Convert a Bitcoin Core JSON decimal BTC value (`0.01` or `"0.01000000"`) into integer satoshis.
pub fn json_btc_value_to_sats(val: &Value) -> Result<u64> {
    if let Some(s) = val.as_str() {
        return parse_decimal_btc_to_sats(s);
    }
    if let Some(n) = val.as_number() {
        return parse_decimal_btc_to_sats(&n.to_string());
    }
    bail!("expected BTC amount string or number, got {val}")
}

/// Exact integer decimal parser (`"0.01"` -> `1_000_000` sats) avoiding float drift.
pub fn parse_decimal_btc_to_sats(raw: &str) -> Result<u64> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.starts_with('-') {
        bail!("invalid BTC amount `{raw}`");
    }
    let (whole_str, frac_str) = match trimmed.split_once('.') {
        Some((w, f)) => (w, f),
        None => (trimmed, ""),
    };
    if !whole_str.chars().all(|c| c.is_ascii_digit())
        || !frac_str.chars().all(|c| c.is_ascii_digit())
    {
        bail!("non-digit in BTC amount `{raw}`");
    }
    let whole: u64 = if whole_str.is_empty() {
        0
    } else {
        whole_str.parse()?
    };
    let frac_trimmed = if frac_str.len() > 8 {
        &frac_str[..8]
    } else {
        frac_str
    };
    let mut padded = String::with_capacity(8);
    padded.push_str(frac_trimmed);
    while padded.len() < 8 {
        padded.push('0');
    }
    let frac: u64 = padded.parse()?;
    whole
        .checked_mul(100_000_000)
        .and_then(|w| w.checked_add(frac))
        .ok_or_else(|| anyhow!("BTC satoshi overflow on `{raw}`"))
}

async fn http11_post_loopback(
    addr: SocketAddr,
    path: &str,
    content_type: &str,
    extra_header: Option<(&str, &str)>,
    body: &[u8],
    max_body_bytes: usize,
) -> Result<(u16, Vec<u8>)> {
    if !addr.ip().is_loopback() {
        bail!("refusing non-loopback HTTP connection to {addr}");
    }
    timeout(RPC_TIMEOUT, async move {
        let mut stream = TcpStream::connect(addr)
            .await
            .with_context(|| format!("failed to connect to loopback daemon {addr}"))?;
        let _ = stream.set_nodelay(true);

        let mut req = String::with_capacity(256);
        req.push_str(&format!("POST {path} HTTP/1.1\r\n"));
        req.push_str(&format!("Host: {addr}\r\n"));
        req.push_str(&format!("Content-Type: {content_type}\r\n"));
        req.push_str(&format!("Content-Length: {}\r\n", body.len()));
        req.push_str("Accept: */*\r\n");
        req.push_str("Connection: close\r\n");
        if let Some((hk, hv)) = extra_header {
            req.push_str(&format!("{hk}: {hv}\r\n"));
        }
        req.push_str("\r\n");

        stream.write_all(req.as_bytes()).await?;
        stream.write_all(body).await?;
        stream.flush().await?;

        read_http11_response(&mut stream, max_body_bytes).await
    })
    .await
    .map_err(|_| anyhow!("loopback HTTP POST {addr}{path} timed out after {RPC_TIMEOUT:?}"))?
}

async fn read_http11_response<S: AsyncRead + Unpin>(
    stream: &mut S,
    max_body_bytes: usize,
) -> Result<(u16, Vec<u8>)> {
    let mut buf = Vec::with_capacity(4096);
    let mut scratch = [0u8; 4096];

    let header_end = loop {
        if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break pos;
        }
        if buf.len() > MAX_HEADER_BYTES {
            bail!("HTTP response header exceeded {MAX_HEADER_BYTES} bytes");
        }
        let n = stream.read(&mut scratch).await?;
        if n == 0 {
            bail!("unexpected EOF before HTTP response headers completed");
        }
        buf.extend_from_slice(&scratch[..n]);
    };

    let header_text =
        std::str::from_utf8(&buf[..header_end]).context("non-UTF8 HTTP response headers")?;
    let mut lines = header_text.split("\r\n");
    let status_line = lines.next().ok_or_else(|| anyhow!("empty HTTP response"))?;
    let status = status_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| anyhow!("malformed HTTP status line `{status_line}`"))?
        .parse::<u16>()
        .with_context(|| format!("invalid HTTP status code in `{status_line}`"))?;

    let mut content_length: Option<usize> = None;
    let mut chunked = false;
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            if k.eq_ignore_ascii_case("content-length") {
                content_length = Some(v.trim().parse::<usize>()?);
            } else if k.eq_ignore_ascii_case("transfer-encoding")
                && v.to_ascii_lowercase().contains("chunked")
            {
                chunked = true;
            }
        }
    }

    buf.drain(..header_end + 4);

    if chunked {
        let decoded = read_chunked_body(stream, &mut buf, max_body_bytes).await?;
        return Ok((status, decoded));
    }

    if let Some(cl) = content_length {
        if cl > max_body_bytes {
            bail!("HTTP Content-Length {cl} exceeds limit {max_body_bytes}");
        }
        while buf.len() < cl {
            let n = stream.read(&mut scratch).await?;
            if n == 0 {
                bail!(
                    "unexpected EOF reading HTTP body ({}/{} bytes)",
                    buf.len(),
                    cl
                );
            }
            buf.extend_from_slice(&scratch[..n]);
        }
        buf.truncate(cl);
        return Ok((status, buf));
    }

    while buf.len() <= max_body_bytes {
        let n = stream.read(&mut scratch).await?;
        if n == 0 {
            return Ok((status, buf));
        }
        buf.extend_from_slice(&scratch[..n]);
    }
    bail!("unbounded HTTP response exceeded {max_body_bytes} bytes")
}

async fn read_chunked_body<S: AsyncRead + Unpin>(
    stream: &mut S,
    buf: &mut Vec<u8>,
    max_body_bytes: usize,
) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut scratch = [0u8; 4096];

    loop {
        let line_end = loop {
            if let Some(pos) = buf.windows(2).position(|w| w == b"\r\n") {
                break pos;
            }
            if buf.len() > 4096 {
                bail!("malformed HTTP chunk size line");
            }
            let n = stream.read(&mut scratch).await?;
            if n == 0 {
                bail!("unexpected EOF reading HTTP chunk size");
            }
            buf.extend_from_slice(&scratch[..n]);
        };

        let size_str = std::str::from_utf8(&buf[..line_end])?
            .split(';')
            .next()
            .unwrap_or("")
            .trim();
        let chunk_size = usize::from_str_radix(size_str, 16)
            .with_context(|| format!("invalid hex chunk size `{size_str}`"))?;
        buf.drain(..line_end + 2);

        if chunk_size == 0 {
            break;
        }
        if out.len().saturating_add(chunk_size) > max_body_bytes {
            bail!("chunked HTTP body exceeded {max_body_bytes} bytes");
        }

        let needed = chunk_size + 2;
        while buf.len() < needed {
            let n = stream.read(&mut scratch).await?;
            if n == 0 {
                bail!("unexpected EOF inside HTTP chunk payload");
            }
            buf.extend_from_slice(&scratch[..n]);
        }
        if &buf[chunk_size..chunk_size + 2] != b"\r\n" {
            bail!("missing CRLF after HTTP chunk payload");
        }
        out.extend_from_slice(&buf[..chunk_size]);
        buf.drain(..needed);
    }

    Ok(out)
}
