//! `XMR402` (`HTTP 402 Payment Required`) challenge parser, validator, and result contracts.

use std::collections::HashMap;

use anyhow::{anyhow, bail, Result};
use monero_address::{MoneroAddress, Network};
use ov_tor::is_valid_v3_onion_host;
use serde::{Deserialize, Serialize};
use url::Url;

use crate::uri::{format_atomic_xmr, parse_xmr_to_atomic};

/// Exact JSON contract returned by `nativeXmrParse402ChallengeJson`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Xmr402ChallengeResult {
    pub ok: bool,
    pub scheme: String,
    pub page_url: String,
    pub address: String,
    pub amount_atomic: String,
    pub amount_xmr: String,
    pub message: String,
    pub timestamp: u64,
    pub daemon_url: String,
    pub error: String,
}

impl Xmr402ChallengeResult {
    pub fn err(page_url: &str, msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            scheme: "XMR402".to_string(),
            page_url: page_url.trim().to_string(),
            address: String::new(),
            amount_atomic: "0".to_string(),
            amount_xmr: "0".to_string(),
            message: String::new(),
            timestamp: 0,
            daemon_url: String::new(),
            error: msg.into(),
        }
    }
}

/// Exact JSON contract returned by `nativeXmrPay402Json`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Xmr402PayResult {
    pub ok: bool,
    pub txid: String,
    pub proof: String,
    pub authorization_header: String,
    pub amount_atomic: String,
    pub amount_xmr: String,
    pub fee_atomic: String,
    pub fee_xmr: String,
    pub error: String,
}

impl Xmr402PayResult {
    pub fn err(msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            txid: String::new(),
            proof: String::new(),
            authorization_header: String::new(),
            amount_atomic: "0".to_string(),
            amount_xmr: "0".to_string(),
            fee_atomic: "0".to_string(),
            fee_xmr: "0".to_string(),
            error: msg.into(),
        }
    }
}

/// Exact JSON contract returned by `nativeXmrProveTxJson`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct XmrTxProofResult {
    pub ok: bool,
    pub txid: String,
    pub recipient_address: String,
    pub message: String,
    pub proof: String,
    pub authorization_header: String,
    pub error: String,
}

impl XmrTxProofResult {
    pub fn err(txid: &str, msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            txid: txid.trim().to_string(),
            recipient_address: String::new(),
            message: String::new(),
            proof: String::new(),
            authorization_header: String::new(),
            error: msg.into(),
        }
    }
}

/// Validated `XMR402` payment input ready for Ring-16 transaction construction.
#[derive(Clone, Debug)]
pub struct ValidatedXmr402Payment {
    pub recipient: MoneroAddress,
    pub amount_atomic: u64,
    pub message: String,
    pub daemon_url: String,
}

#[derive(Default)]
struct RawChallengeFields {
    address: Option<String>,
    amount_raw: Option<String>,
    amount_xmr_only: Option<String>,
    message: Option<String>,
    timestamp_raw: Option<String>,
    daemon_raw: Option<String>,
}

/// Parse and validate an `XMR402` challenge from `WWW-Authenticate` and/or
/// `<meta name="xmr402-challenge" content="...">` fallback JSON.
pub fn parse_xmr402_challenge(
    www_authenticate: &str,
    fallback_meta_json: &str,
    page_url: &str,
    hidden_service_only: bool,
) -> Xmr402ChallengeResult {
    let run = || -> Result<Xmr402ChallengeResult> {
        let hdr_trimmed = www_authenticate.trim();
        let meta_trimmed = fallback_meta_json.trim();
        if hdr_trimmed.is_empty() && meta_trimmed.is_empty() {
            bail!("missing XMR402 WWW-Authenticate header and fallback meta challenge");
        }

        let hdr_res = if !hdr_trimmed.is_empty() {
            Some(parse_www_authenticate_fields(hdr_trimmed))
        } else {
            None
        };
        let meta_res = if !meta_trimmed.is_empty() {
            Some(parse_meta_json_fields(meta_trimmed))
        } else {
            None
        };

        let merged = match (hdr_res, meta_res) {
            (Some(Ok(mut h)), Some(Ok(m))) => {
                if h.address.is_none() {
                    h.address = m.address;
                }
                if h.amount_raw.is_none() {
                    h.amount_raw = m.amount_raw;
                }
                if h.amount_xmr_only.is_none() {
                    h.amount_xmr_only = m.amount_xmr_only;
                }
                if h.message.is_none() {
                    h.message = m.message;
                }
                if h.timestamp_raw.is_none() {
                    h.timestamp_raw = m.timestamp_raw;
                }
                if h.daemon_raw.is_none() {
                    h.daemon_raw = m.daemon_raw;
                }
                h
            }
            (Some(Ok(h)), _) => h,
            (_, Some(Ok(m))) => m,
            (Some(Err(e)), None) => return Err(e),
            (None, Some(Err(e))) => return Err(e),
            (Some(Err(eh)), Some(Err(em))) => {
                bail!("invalid XMR402 header ({eh}) and meta JSON ({em})")
            }
            (None, None) => unreachable!(),
        };

        let validated = validate_raw_fields(merged, hidden_service_only)?;
        Ok(Xmr402ChallengeResult {
            ok: true,
            scheme: "XMR402".to_string(),
            page_url: page_url.trim().to_string(),
            address: validated.0.to_string(),
            amount_atomic: validated.1.to_string(),
            amount_xmr: format_atomic_xmr(validated.1),
            message: validated.2,
            timestamp: validated.3,
            daemon_url: validated.4,
            error: String::new(),
        })
    };

    match run() {
        Ok(res) => res,
        Err(err) => Xmr402ChallengeResult::err(page_url, err.to_string()),
    }
}

/// Serialize `parse_xmr402_challenge` directly into the `OvNative` JSON contract.
pub fn parse_xmr402_challenge_json(
    www_authenticate: &str,
    fallback_meta_json: &str,
    page_url: &str,
    hidden_service_only: bool,
) -> String {
    let res = parse_xmr402_challenge(
        www_authenticate,
        fallback_meta_json,
        page_url,
        hidden_service_only,
    );
    serde_json::to_string(&res).unwrap_or_else(|_| {
        r#"{"ok":false,"scheme":"XMR402","pageUrl":"","address":"","amountAtomic":"0","amountXmr":"0","message":"","timestamp":0,"daemonUrl":"","error":"serialization error"}"#.to_string()
    })
}

/// Parse and validate `challenge_json` passed to `pay_xmr402`.
pub fn parse_payment_challenge_json(
    challenge_json: &str,
    hidden_service_only: bool,
) -> Result<ValidatedXmr402Payment> {
    let trimmed = challenge_json.trim();
    if trimmed.is_empty() {
        bail!("XMR402 challenge JSON must not be empty");
    }
    let raw = parse_meta_json_fields(trimmed)?;
    let (recipient, amount_atomic, message, _timestamp, daemon_url) =
        validate_raw_fields(raw, hidden_service_only)?;
    Ok(ValidatedXmr402Payment {
        recipient,
        amount_atomic,
        message,
        daemon_url,
    })
}

/// Validate an optional `daemonUrl` (`""` allowed; if non-empty, must be `http://` or `https://`
/// and must be a v3 `.onion` host when `hidden_service_only == true`).
pub fn validate_daemon_url_opt(raw_daemon: &str, hidden_service_only: bool) -> Result<String> {
    let trimmed = raw_daemon.trim();
    if trimmed.is_empty() {
        return Ok(String::new());
    }
    let parsed =
        Url::parse(trimmed).map_err(|e| anyhow!("invalid XMR402 daemon URL {trimmed:?}: {e}"))?;
    if !matches!(parsed.scheme(), "http" | "https") {
        bail!("XMR402 daemon URL must use http:// or https:// scheme");
    }
    let host = parsed
        .host_str()
        .ok_or_else(|| anyhow!("XMR402 daemon URL is missing host"))?
        .to_ascii_lowercase();
    if hidden_service_only && !is_valid_v3_onion_host(&host) {
        bail!("Hidden-service-only mode is active: XMR402 daemonUrl must be a v3 .onion URL");
    }
    Ok(parsed.as_str().trim_end_matches('/').to_string())
}

fn validate_raw_fields(
    raw: RawChallengeFields,
    hidden_service_only: bool,
) -> Result<(MoneroAddress, u64, String, u64, String)> {
    let addr_str = raw
        .address
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("XMR402 challenge is missing address"))?;
    let addr = MoneroAddress::from_str(Network::Mainnet, addr_str)
        .map_err(|e| anyhow!("invalid Monero mainnet address in XMR402 challenge: {e}"))?;

    let amount_atomic = if let Some(amt_str) = raw
        .amount_raw
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        parse_challenge_amount_atomic(amt_str)?
    } else if let Some(xmr_str) = raw
        .amount_xmr_only
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        parse_xmr_to_atomic(xmr_str)?
    } else {
        bail!("XMR402 challenge is missing amount");
    };
    if amount_atomic == 0 {
        bail!("XMR402 challenge amount must be greater than zero");
    }

    let message = raw
        .message
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| anyhow!("XMR402 challenge is missing non-empty message"))?
        .to_string();

    let timestamp = match raw
        .timestamp_raw
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(ts_str) => ts_str
            .parse::<u64>()
            .map_err(|_| anyhow!("invalid XMR402 timestamp {ts_str:?}"))?,
        None => 0,
    };

    let daemon_url = match raw.daemon_raw.as_deref() {
        Some(d) => validate_daemon_url_opt(d, hidden_service_only)?,
        None => String::new(),
    };

    Ok((addr, amount_atomic, message, timestamp, daemon_url))
}

fn parse_challenge_amount_atomic(raw: &str) -> Result<u64> {
    let trimmed = raw.trim();
    if trimmed.contains('.') {
        return parse_xmr_to_atomic(trimmed);
    }
    if trimmed.is_empty() || !trimmed.bytes().all(|b| b.is_ascii_digit()) {
        bail!("invalid XMR402 atomic amount {trimmed:?}");
    }
    trimmed
        .parse::<u64>()
        .map_err(|_| anyhow!("XMR402 atomic amount overflows u64: {trimmed:?}"))
}

fn parse_www_authenticate_fields(header: &str) -> Result<RawChallengeFields> {
    let mut s = header.trim();
    if s.len() >= 17 && s[..17].eq_ignore_ascii_case("www-authenticate:") {
        s = s[17..].trim();
    }

    let params_slice = find_xmr402_scheme_tail(s)
        .ok_or_else(|| anyhow!("WWW-Authenticate does not specify XMR402 / x402 scheme"))?;
    let map = parse_auth_params(params_slice)?;
    Ok(map_to_raw_fields(&map))
}

fn find_xmr402_scheme_tail(s: &str) -> Option<&str> {
    let trimmed = s.trim();
    for scheme in ["xmr402", "x402"] {
        if trimmed.len() > scheme.len()
            && trimmed[..scheme.len()].eq_ignore_ascii_case(scheme)
            && trimmed.as_bytes()[scheme.len()].is_ascii_whitespace()
        {
            return Some(trimmed[scheme.len()..].trim());
        }
    }
    // Also handle multi-challenge WWW-Authenticate headers (e.g. `Bearer ..., XMR402 address=...`)
    let lower = trimmed.to_ascii_lowercase();
    for needle in [", xmr402 ", ",xmr402 ", ", x402 ", ",x402 "] {
        if let Some(pos) = lower.find(needle) {
            let after = &trimmed[pos + needle.len()..];
            return Some(after.trim());
        }
    }
    None
}

fn parse_auth_params(input: &str) -> Result<HashMap<String, String>> {
    let mut out = HashMap::new();
    let chars: Vec<char> = input.chars().collect();
    let mut i = 0usize;

    while i < chars.len() {
        while i < chars.len() && (chars[i].is_whitespace() || chars[i] == ',' || chars[i] == ';') {
            i += 1;
        }
        if i >= chars.len() {
            break;
        }

        let key_start = i;
        while i < chars.len()
            && (chars[i].is_ascii_alphanumeric() || chars[i] == '_' || chars[i] == '-')
        {
            i += 1;
        }
        if i == key_start {
            bail!("malformed parameter name in XMR402 WWW-Authenticate header");
        }
        let key: String = chars[key_start..i]
            .iter()
            .collect::<String>()
            .to_ascii_lowercase();

        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        if i >= chars.len() || chars[i] != '=' {
            // Stop if another auth scheme token follows without `=`
            break;
        }
        i += 1; // consume '='
        while i < chars.len() && chars[i].is_whitespace() {
            i += 1;
        }
        if i >= chars.len() {
            out.insert(key, String::new());
            break;
        }

        let val = if chars[i] == '"' || chars[i] == '\'' {
            let quote = chars[i];
            i += 1;
            let mut buf = String::new();
            let mut closed = false;
            while i < chars.len() {
                let c = chars[i];
                i += 1;
                if c == '\\' && i < chars.len() {
                    buf.push(chars[i]);
                    i += 1;
                } else if c == quote {
                    closed = true;
                    break;
                } else {
                    buf.push(c);
                }
            }
            if !closed {
                bail!("unterminated quoted parameter {key:?} in XMR402 header");
            }
            buf
        } else {
            let val_start = i;
            while i < chars.len() && !chars[i].is_whitespace() && chars[i] != ',' && chars[i] != ';'
            {
                i += 1;
            }
            chars[val_start..i].iter().collect()
        };

        out.insert(key, val);
    }

    Ok(out)
}

fn parse_meta_json_fields(raw: &str) -> Result<RawChallengeFields> {
    let trimmed = raw.trim();
    if !trimmed.starts_with('{') {
        return parse_www_authenticate_fields(trimmed);
    }

    let val: serde_json::Value =
        serde_json::from_str(trimmed).map_err(|e| anyhow!("invalid XMR402 JSON: {e}"))?;
    let obj = val
        .as_object()
        .ok_or_else(|| anyhow!("XMR402 challenge JSON must be a JSON object"))?;

    let mut map = HashMap::new();
    for (k, v) in obj {
        let s = match v {
            serde_json::Value::String(s) => Some(s.clone()),
            serde_json::Value::Number(n) => Some(n.to_string()),
            _ => None,
        };
        if let Some(sv) = s {
            map.insert(k.to_ascii_lowercase(), sv);
        }
    }
    Ok(map_to_raw_fields(&map))
}

fn map_to_raw_fields(map: &HashMap<String, String>) -> RawChallengeFields {
    let get_any = |keys: &[&str]| -> Option<String> {
        for &k in keys {
            if let Some(v) = map.get(k) {
                if !v.trim().is_empty() {
                    return Some(v.clone());
                }
            }
        }
        None
    };

    RawChallengeFields {
        address: get_any(&["address", "recipient", "pay_to", "payto"]),
        amount_raw: get_any(&["amountatomic", "amount_atomic", "amount"]),
        amount_xmr_only: get_any(&["amountxmr", "amount_xmr"]),
        message: get_any(&["message", "nonce", "challenge"]),
        timestamp_raw: get_any(&["timestamp", "ts"]),
        daemon_raw: get_any(&["daemonurl", "daemon_url", "daemon", "rpc"]),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VALID_SUBADDR: &str = "82ffYh7xRhFBvBHEHGV8TNRBoYucBFNoyYnwrJJjnMGpHKPWosZeB4BDr7pBG4DCux1WKepdAV6rRTimi1LqWMacF88hvQm";
    const SAMPLE_ONION_DAEMON: &str =
        "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion:18081";

    #[test]
    fn parses_canonical_xmr402_header_unquoted_x402_and_meta_fallback_json() {
        // 1. Canonical quoted XMR402 v2.0 header with `.onion` daemon
        let hdr = format!(
            r#"XMR402 address="{VALID_SUBADDR}", amount="250000000000", message="xmr402-citadel-1700000000-ab12cd34", timestamp="1700000000", daemon="{SAMPLE_ONION_DAEMON}/""#
        );
        let parsed = parse_xmr402_challenge(
            &hdr,
            "",
            "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/vault",
            true,
        );
        assert!(parsed.ok, "unexpected error: {}", parsed.error);
        assert_eq!(parsed.scheme, "XMR402");
        assert_eq!(parsed.address, VALID_SUBADDR);
        assert_eq!(parsed.amount_atomic, "250000000000");
        assert_eq!(parsed.amount_xmr, "0.25");
        assert_eq!(parsed.message, "xmr402-citadel-1700000000-ab12cd34");
        assert_eq!(parsed.timestamp, 1_700_000_000);
        assert_eq!(parsed.daemon_url, SAMPLE_ONION_DAEMON);

        // 2. Case-insensitive `x402` scheme with unquoted params + `<meta>` supplying `daemonUrl`
        let unquoted =
            format!("x402 address={VALID_SUBADDR}, amount=50000000000, message=nonce_99");
        let meta = format!(r#"{{"daemonUrl":"{SAMPLE_ONION_DAEMON}","timestamp":1700000999}}"#);
        let merged = parse_xmr402_challenge(&unquoted, &meta, "http://test.onion/gate", true);
        assert!(merged.ok, "unexpected error: {}", merged.error);
        assert_eq!(merged.amount_atomic, "50000000000");
        assert_eq!(merged.amount_xmr, "0.05");
        assert_eq!(merged.message, "nonce_99");
        assert_eq!(merged.timestamp, 1_700_000_999);
        assert_eq!(merged.daemon_url, SAMPLE_ONION_DAEMON);

        // 3. Fallback `<meta name="xmr402-challenge">` JSON alone when header is empty
        let meta_only = serde_json::json!({
            "address": VALID_SUBADDR,
            "amountAtomic": "250000000000",
            "message": "meta-nonce-only",
            "timestamp": 1_700_001_234u64,
            "daemonUrl": SAMPLE_ONION_DAEMON,
        })
        .to_string();
        let from_meta = parse_xmr402_challenge("", &meta_only, "http://test.onion/vault", true);
        assert!(from_meta.ok, "meta error: {}", from_meta.error);
        assert_eq!(from_meta.amount_xmr, "0.25");
        assert_eq!(from_meta.message, "meta-nonce-only");

        // 4. Reject non-.onion daemonUrl when hidden_service_only == true
        let clearnet_meta = serde_json::json!({
            "address": VALID_SUBADDR,
            "amountAtomic": "250000000000",
            "message": "nonce",
            "daemonUrl": "http://node.moneroworld.com:18089",
        })
        .to_string();
        let rejected = parse_xmr402_challenge("", &clearnet_meta, "http://test.onion/vault", true);
        assert!(!rejected.ok);
        assert!(rejected.error.contains(".onion"));

        // Allow clearnet daemonUrl when hidden_service_only == false
        let allowed =
            parse_xmr402_challenge("", &clearnet_meta, "https://example.com/vault", false);
        assert!(allowed.ok);

        // 5. Reject zero amount, empty message, or invalid Monero address
        let zero_amt = format!(r#"XMR402 address="{VALID_SUBADDR}", amount="0", message="n""#);
        assert!(!parse_xmr402_challenge(&zero_amt, "", "", true).ok);

        let empty_msg =
            format!(r#"XMR402 address="{VALID_SUBADDR}", amount="1000", message="   ""#);
        assert!(!parse_xmr402_challenge(&empty_msg, "", "", true).ok);

        let bad_addr = r#"XMR402 address="44invalidaddress", amount="1000", message="nonce""#;
        assert!(!parse_xmr402_challenge(bad_addr, "", "", true).ok);
    }
}
