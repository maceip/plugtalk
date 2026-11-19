//! Live Tor SOCKS5h (`ATYP_DOMAIN = 0x03`) end-to-end verification test against
//! `http://6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion` (XMR402 + BTC->XMR Swap)
//! and `http://b36xrf3dwtizfea3z6pl2i2el2vzraju4gdddben5eqpicdswuvfgoyd.onion` (PGP TOTP).
//!
//! Ignored by default during offline unit-test runs; invoke with:
//! `cargo test --test live_tor_e2e -- --ignored --nocapture`

use std::{env, future::Future, net::SocketAddr, time::Duration};

use anyhow::{anyhow, bail, Context, Result};
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_address::{MoneroAddress, Network, SubaddressIndex};
use monero_daemon_rpc::{prelude::*, HttpTransport, MoneroDaemon};
use monero_wallet::{
    ed25519::{Point, Scalar},
    interface::InterfaceError,
    ringct::RctType,
    send::{Change, SignableTransaction},
    OutputWithDecoys, Scanner, ViewPair,
};
use rand_core::{OsRng, RngCore as _};
use serde_json::{json, Value};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::timeout,
};
use trap_xmr402_swap::{
    tx_proof::{generate_tx_proof_v2, reconstruct_tx_secret_keys},
    DEFAULT_PRIZE_BANNER, VAULT_PRICE_ATOMIC,
};
use zeroize::Zeroizing;

const DEFAULT_XMR402_ONION: &str = "6e52wfxr64n2zxjhalrldumrjuqfzjh5gbnyarrkuxrvjrusf25g6yad.onion";
const PGP_TOTP_ONION: &str = "b36xrf3dwtizfea3z6pl2i2el2vzraju4gdddben5eqpicdswuvfgoyd.onion";

#[derive(Clone, Debug)]
struct Socks5hOnionTransport {
    socks_addr: SocketAddr,
    onion_host: String,
    onion_port: u16,
}

impl Socks5hOnionTransport {
    async fn request(
        &self,
        method: &str,
        path: &str,
        content_type: Option<&str>,
        extra_headers: &[(&str, &str)],
        body: &[u8],
    ) -> Result<(u16, Vec<(String, String)>, Vec<u8>)> {
        let socks_addr = self.socks_addr;
        let host = self.onion_host.clone();
        let port = self.onion_port;
        let method_s = method.to_string();
        let path_s = path.to_string();
        let ct_opt = content_type.map(str::to_string);
        let hdrs: Vec<(String, String)> = extra_headers
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        let body_vec = body.to_vec();

        timeout(Duration::from_secs(90), async move {
            let mut stream = connect_socks5h(socks_addr, &host, port).await?;
            let host_hdr = if port == 80 {
                host.clone()
            } else {
                format!("{host}:{port}")
            };
            let mut req = String::with_capacity(512);
            req.push_str(&format!("{method_s} {path_s} HTTP/1.1\r\n"));
            req.push_str(&format!("Host: {host_hdr}\r\n"));
            req.push_str("Accept: */*\r\n");
            req.push_str("Connection: close\r\n");
            if let Some(ct) = ct_opt {
                req.push_str(&format!("Content-Type: {ct}\r\n"));
                req.push_str(&format!("Content-Length: {}\r\n", body_vec.len()));
            } else if !body_vec.is_empty() {
                req.push_str(&format!("Content-Length: {}\r\n", body_vec.len()));
            }
            for (k, v) in &hdrs {
                req.push_str(&format!("{k}: {v}\r\n"));
            }
            req.push_str("\r\n");

            stream.write_all(req.as_bytes()).await?;
            if !body_vec.is_empty() {
                stream.write_all(&body_vec).await?;
            }
            stream.flush().await?;

            read_http_response(&mut stream, 32 * 1024 * 1024).await
        })
        .await
        .map_err(|_| anyhow!("Tor HTTP {method} {path} timed out"))?
    }
}

impl HttpTransport for Socks5hOnionTransport {
    fn post(
        &self,
        route: &str,
        body: Vec<u8>,
        _response_size_limit: Option<usize>,
    ) -> impl Send + Future<Output = Result<Vec<u8>, InterfaceError>> {
        let this = self.clone();
        let clean = format!("/{}", route.trim_start_matches('/'));
        let ct = if clean.ends_with(".bin") {
            "application/octet-stream"
        } else {
            "application/json"
        };
        async move {
            let (status, _, resp_body) =
                this.request("POST", &clean, Some(ct), &[], &body)
                    .await
                    .map_err(|e| InterfaceError::InterfaceError(e.to_string()))?;
            if status != 200 {
                return Err(InterfaceError::InvalidInterface(format!(
                    "HTTP {status} on {clean}"
                )));
            }
            Ok(resp_body)
        }
    }
}

async fn connect_socks5h(socks_addr: SocketAddr, host: &str, port: u16) -> Result<TcpStream> {
    let mut stream = TcpStream::connect(socks_addr)
        .await
        .with_context(|| format!("failed to connect to Tor SOCKS5 proxy {socks_addr}"))?;
    let _ = stream.set_nodelay(true);
    stream.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut greet = [0u8; 2];
    stream.read_exact(&mut greet).await?;
    if greet != [0x05, 0x00] {
        bail!("SOCKS5 greeting rejected: {greet:?}");
    }

    let hbytes = host.as_bytes();
    let mut req = Vec::with_capacity(7 + hbytes.len());
    req.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, hbytes.len() as u8]);
    req.extend_from_slice(hbytes);
    req.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&req).await?;
    stream.flush().await?;

    let mut head = [0u8; 4];
    stream.read_exact(&mut head).await?;
    if head[0] != 0x05 || head[1] != 0x00 {
        bail!(
            "SOCKS5h CONNECT {host}:{port} failed with REP={:#04x}",
            head[1]
        );
    }
    match head[3] {
        0x01 => {
            let mut rest = [0u8; 6];
            stream.read_exact(&mut rest).await?;
        }
        0x04 => {
            let mut rest = [0u8; 18];
            stream.read_exact(&mut rest).await?;
        }
        0x03 => {
            let mut l = [0u8; 1];
            stream.read_exact(&mut l).await?;
            let mut rest = vec![0u8; usize::from(l[0]) + 2];
            stream.read_exact(&mut rest).await?;
        }
        other => bail!("unexpected SOCKS5 ATYP {other:#x}"),
    }
    Ok(stream)
}

async fn read_http_response<S: AsyncRead + Unpin>(
    stream: &mut S,
    max_bytes: usize,
) -> Result<(u16, Vec<(String, String)>, Vec<u8>)> {
    let mut buf = Vec::with_capacity(4096);
    let mut scratch = [0u8; 4096];
    let header_end = loop {
        if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break pos;
        }
        let n = stream.read(&mut scratch).await?;
        if n == 0 {
            bail!("EOF before HTTP response headers");
        }
        buf.extend_from_slice(&scratch[..n]);
    };

    let head_str = std::str::from_utf8(&buf[..header_end])?;
    let mut lines = head_str.split("\r\n");
    let status_line = lines.next().unwrap_or("");
    let status: u16 = status_line
        .split_whitespace()
        .nth(1)
        .ok_or_else(|| anyhow!("bad status line `{status_line}`"))?
        .parse()?;

    let mut headers = Vec::new();
    let mut content_len: Option<usize> = None;
    let mut chunked = false;
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            let ks = k.trim().to_string();
            let vs = v.trim().to_string();
            if ks.eq_ignore_ascii_case("content-length") {
                content_len = Some(vs.parse()?);
            } else if ks.eq_ignore_ascii_case("transfer-encoding")
                && vs.to_ascii_lowercase().contains("chunked")
            {
                chunked = true;
            }
            headers.push((ks, vs));
        }
    }
    buf.drain(..header_end + 4);

    if chunked {
        let mut out = Vec::new();
        loop {
            let crlf = loop {
                if let Some(p) = buf.windows(2).position(|w| w == b"\r\n") {
                    break p;
                }
                let n = stream.read(&mut scratch).await?;
                if n == 0 {
                    bail!("EOF in chunk size");
                }
                buf.extend_from_slice(&scratch[..n]);
            };
            let sz_str = std::str::from_utf8(&buf[..crlf])?
                .split(';')
                .next()
                .unwrap_or("")
                .trim();
            let sz = usize::from_str_radix(sz_str, 16)?;
            buf.drain(..crlf + 2);
            if sz == 0 {
                break;
            }
            while buf.len() < sz + 2 {
                let n = stream.read(&mut scratch).await?;
                if n == 0 {
                    bail!("EOF in chunk data");
                }
                buf.extend_from_slice(&scratch[..n]);
            }
            out.extend_from_slice(&buf[..sz]);
            buf.drain(..sz + 2);
        }
        return Ok((status, headers, out));
    }

    if let Some(cl) = content_len {
        while buf.len() < cl {
            let n = stream.read(&mut scratch).await?;
            if n == 0 {
                break;
            }
            buf.extend_from_slice(&scratch[..n]);
        }
        buf.truncate(cl);
        return Ok((status, headers, buf));
    }

    while buf.len() <= max_bytes {
        let n = stream.read(&mut scratch).await?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&scratch[..n]);
    }
    Ok((status, headers, buf))
}

fn find_header<'a>(headers: &'a [(String, String)], key: &str) -> Option<&'a str> {
    headers
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .map(|(_, v)| v.as_str())
}

fn extract_quoted_param(header_val: &str, param: &str) -> Option<String> {
    let needle = format!("{param}=\"");
    let start = header_val.find(&needle)? + needle.len();
    let end = start + header_val[start..].find('"')?;
    Some(header_val[start..end].to_string())
}

#[tokio::test]
#[ignore = "requires live Tor SOCKS5h proxy on TRAP_TOR_SOCKS (default 127.0.0.1:9050)"]
async fn live_tor_xmr402_swap_and_outproof_v2_prize_e2e() -> Result<()> {
    let socks_str = env::var("TRAP_TOR_SOCKS").unwrap_or_else(|_| "127.0.0.1:9050".to_string());
    let socks_addr: SocketAddr = socks_str.parse()?;
    let xmr_onion =
        env::var("TRAP_XMR402_ONION").unwrap_or_else(|_| DEFAULT_XMR402_ONION.to_string());

    // 1. Verify existing PGP TOTP hidden service (#1) remains 100% healthy (HTTP 200 OK)
    let pgp_http = Socks5hOnionTransport {
        socks_addr,
        onion_host: PGP_TOTP_ONION.to_string(),
        onion_port: 80,
    };
    let (pgp_status, _, _) = pgp_http.request("GET", "/", None, &[], &[]).await?;
    println!("[1/7] PGP TOTP hidden service {PGP_TOTP_ONION}/ -> HTTP {pgp_status}");
    assert_eq!(pgp_status, 200);

    // 2. Verify new restricted monerod hidden service port (:18081)
    let daemon_tor = Socks5hOnionTransport {
        socks_addr,
        onion_host: xmr_onion.clone(),
        onion_port: 18081,
    };
    let get_info_req = json!({"jsonrpc":"2.0","id":"0","method":"get_info"}).to_string();
    let (rpc_status, _, rpc_body) = daemon_tor
        .request(
            "POST",
            "/json_rpc",
            Some("application/json"),
            &[],
            get_info_req.as_bytes(),
        )
        .await?;
    assert_eq!(rpc_status, 200);
    let rpc_val: Value = serde_json::from_slice(&rpc_body)?;
    let is_restricted = rpc_val
        .pointer("/result/restricted")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let chain_height = rpc_val
        .pointer("/result/height")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    println!(
        "[2/7] Restricted monerod {xmr_onion}:18081/json_rpc -> HTTP 200, restricted={is_restricted}, height={chain_height}"
    );
    assert!(is_restricted, "monerod on :18081 MUST be restricted");
    assert!(chain_height >= 80, "monerod height must be >= 80");

    // 3. Hit GET /vault over Tor -> HTTP 402 + WWW-Authenticate: XMR402 ...
    let web_tor = Socks5hOnionTransport {
        socks_addr,
        onion_host: xmr_onion.clone(),
        onion_port: 80,
    };
    let (v_status, v_headers, v_body) = web_tor.request("GET", "/vault", None, &[], &[]).await?;
    assert_eq!(v_status, 402);
    let www_auth = find_header(&v_headers, "www-authenticate")
        .ok_or_else(|| anyhow!("missing WWW-Authenticate on 402 /vault"))?;
    println!("[3/7] GET http://{xmr_onion}/vault -> HTTP 402, WWW-Authenticate: {www_auth}");
    let vault_addr_str = extract_quoted_param(www_auth, "address")
        .ok_or_else(|| anyhow!("missing address in WWW-Authenticate"))?;
    let challenge_msg = extract_quoted_param(www_auth, "message")
        .ok_or_else(|| anyhow!("missing message in WWW-Authenticate"))?;
    let amount_str = extract_quoted_param(www_auth, "amount")
        .ok_or_else(|| anyhow!("missing amount in WWW-Authenticate"))?;
    assert_eq!(amount_str.parse::<u64>()?, VAULT_PRICE_ATOMIC);
    let vault_subaddr = MoneroAddress::from_str(Network::Mainnet, &vault_addr_str)?;
    let v_html = String::from_utf8(v_body)?;
    assert!(v_html.contains("name=\"xmr402-challenge\""));

    // 4. Create fresh client Monero wallet (0 XMR) & execute real BTC -> XMR swap over Tor
    let client_spend = Scalar::random(&mut OsRng);
    let client_spend_d: curve25519_dalek::Scalar = client_spend.into();
    let client_pub = Point::from(&client_spend_d * ED25519_BASEPOINT_TABLE);
    let client_view = Scalar::hash(client_spend_d.to_bytes());
    let client_vp = ViewPair::new(client_pub, Zeroizing::new(client_view))?;
    let client_sub1_idx = SubaddressIndex::new(0, 1).unwrap();
    let client_sub8 = client_vp.subaddress(Network::Mainnet, client_sub1_idx);

    let create_form = serde_urlencoded::to_string([
        ("btc_amount", "0.01"),
        ("payout_addr", &client_sub8.to_string()),
    ])?;
    let (c_status, c_headers, _) = web_tor
        .request(
            "POST",
            "/swap/order",
            Some("application/x-www-form-urlencoded"),
            &[],
            create_form.as_bytes(),
        )
        .await?;
    assert_eq!(c_status, 303);
    let order_path = find_header(&c_headers, "location")
        .ok_or_else(|| anyhow!("missing Location on POST /swap/order"))?
        .to_string();

    let (o_status, _, o_body) = web_tor.request("GET", &order_path, None, &[], &[]).await?;
    assert_eq!(o_status, 200);
    let o_html = String::from_utf8(o_body)?;
    assert!(o_html.contains("data-status=\"awaiting_btc_deposit\""));
    assert!(o_html.contains("value=\"bcrt1q"));

    let (f_status, _, _) = web_tor
        .request("POST", &format!("{order_path}/fund-btc"), None, &[], &[])
        .await?;
    assert_eq!(f_status, 303);

    let (done_status, _, done_body) = web_tor.request("GET", &order_path, None, &[], &[]).await?;
    assert_eq!(done_status, 200);
    let done_html = String::from_utf8(done_body)?;
    assert!(done_html.contains("data-status=\"completed\""));
    println!("[4/7] Completed Zero-JS BTC -> XMR swap at {order_path} for {client_sub8}");

    // 5. Sync client wallet over Tor against restricted `.onion:18081` & verify unlocked 1.0 XMR UTXO
    let daemon = MoneroDaemon::new(daemon_tor.clone())
        .await
        .map_err(|e| anyhow!("MoneroDaemon::new over Tor failed: {e}"))?;
    let tip = daemon
        .latest_block_number()
        .await
        .map_err(|e| anyhow!("latest_block_number failed: {e}"))?;
    let mut scanner = Scanner::new(client_vp.clone());
    scanner.register_subaddress(client_sub1_idx);

    let mut received_utxos = Vec::new();
    let mut start = 1usize;
    while start <= tip {
        let end = tip.min(start + 63);
        let blocks = daemon
            .contiguous_scannable_blocks(start..=end)
            .await
            .map_err(|e| anyhow!("contiguous_scannable_blocks failed: {e}"))?;
        for blk in blocks {
            let outs = scanner.scan(blk)?.ignore_additional_timelock();
            received_utxos.extend(outs);
        }
        start = end + 1;
    }
    assert_eq!(received_utxos.len(), 1);
    let utxo = received_utxos.remove(0);
    assert_eq!(utxo.commitment().amount, 1_000_000_000_000);
    println!(
        "[5/7] Scanned 1.000000000000 XMR output over Tor (tx={}) at chain tip={tip}",
        hex::encode(utxo.transaction())
    );

    // 6. Sample Ring-16 decoys over `.onion:18081`, sign & broadcast 0.25 XMR payment, compute OutProofV2
    let fee_rate = daemon
        .fee_rate(FeePriority::Normal, u64::MAX)
        .await
        .map_err(|e| anyhow!("fee_rate failed: {e}"))?;
    let owd = OutputWithDecoys::new(&mut OsRng, &daemon, 16, tip, utxo)
        .await
        .map_err(|e| anyhow!("OutputWithDecoys Ring-16 over Tor failed: {e}"))?;
    let input_pairs = vec![(owd.key(), owd.commitment().commit())];
    let mut ovk = Zeroizing::new([0u8; 32]);
    OsRng.fill_bytes(&mut *ovk);

    let signable = SignableTransaction::new(
        RctType::ClsagBulletproofPlus,
        ovk.clone(),
        vec![owd],
        vec![(vault_subaddr, VAULT_PRICE_ATOMIC)],
        Change::new(client_vp, None),
        vec![],
        fee_rate,
    )?;
    let signed_tx = signable.sign(&mut OsRng, &Zeroizing::new(client_spend))?;
    let pay_txid = hex::encode(signed_tx.hash());

    daemon
        .publish_transaction(&signed_tx)
        .await
        .map_err(|e| anyhow!("publish_transaction over Tor failed: {e}"))?;

    let (tx_key, add_keys) =
        reconstruct_tx_secret_keys(&ovk, input_pairs, &signed_tx).map_err(|e| anyhow!(e))?;
    let out_proof = generate_tx_proof_v2(
        signed_tx.hash(),
        &tx_key,
        &add_keys,
        vault_subaddr,
        &challenge_msg,
        &mut OsRng,
    );
    let auth_header = format!(r#"XMR402 txid="{pay_txid}", proof="{out_proof}""#);
    println!(
        "[6/7] Broadcast Ring-16 0.25 XMR tx {pay_txid} (0-conf mempool!) & generated OutProofV2 ({} chars)",
        out_proof.len()
    );

    // 7. Replay GET /vault with Authorization: XMR402 ... -> HTTP 200 OK + Prize, then verify replay 402!
    let (u_status, _, u_body) = web_tor
        .request(
            "GET",
            "/vault",
            None,
            &[("Authorization", &auth_header)],
            &[],
        )
        .await?;
    let u_html = String::from_utf8(u_body)?;
    assert_eq!(
        u_status, 200,
        "expected HTTP 200 on valid XMR402 OutProofV2, got {u_status}: {u_html}"
    );
    assert!(u_html.contains(DEFAULT_PRIZE_BANNER));
    assert!(u_html.contains("id=\"xmr402-prize-banner\""));
    assert!(u_html.contains("id=\"xmr402-prize-json\""));
    println!("[7/7] UNLOCKED HTTP 200 PRIZE OVER LIVE TOR: {DEFAULT_PRIZE_BANNER}");

    let (rep_status, _, _) = web_tor
        .request(
            "GET",
            "/vault",
            None,
            &[("Authorization", &auth_header)],
            &[],
        )
        .await?;
    assert_eq!(rep_status, 402, "replayed txid must return HTTP 402");
    println!("[OK] Replayed txid strictly rejected with HTTP 402!");

    Ok(())
}
