//! Exact integer piconero (`10^12`) conversions and `monero:` URI parser/formatter.

use anyhow::{anyhow, bail, Result};
use monero_address::{MoneroAddress, Network};
use serde::{Deserialize, Serialize};
use url::Url;

/// Exact atomic units per 1 XMR (`10^12` piconero).
pub const PICONERO_PER_XMR: u64 = 1_000_000_000_000;

/// Maximum whole XMR representable inside `u64` atomic piconero (`18,446,744`).
const MAX_WHOLE_XMR_U64: u64 = u64::MAX / PICONERO_PER_XMR;

/// Format atomic piconero (`u64`) into an exact decimal XMR string without
/// floating-point arithmetic or precision loss.
pub fn format_atomic_xmr(atomic: u64) -> String {
    let whole = atomic / PICONERO_PER_XMR;
    let frac = atomic % PICONERO_PER_XMR;
    if frac == 0 {
        return whole.to_string();
    }
    let frac_padded = format!("{frac:012}");
    let frac_trimmed = frac_padded.trim_end_matches('0');
    format!("{whole}.{frac_trimmed}")
}

/// Parse a decimal XMR string (up to 12 fractional digits) into exact atomic
/// piconero (`u64`) without floating-point arithmetic.
pub fn parse_xmr_to_atomic(input: &str) -> Result<u64> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        bail!("XMR amount must not be empty");
    }
    if trimmed.starts_with('-') || trimmed.starts_with('+') {
        bail!("XMR amount must be an unsigned decimal value");
    }

    let (whole_str, frac_str) = match trimmed.split_once('.') {
        Some((w, f)) => {
            if f.contains('.') {
                bail!("invalid XMR decimal format: {trimmed:?}");
            }
            (w, f)
        }
        None => (trimmed, ""),
    };

    if whole_str.is_empty() && frac_str.is_empty() {
        bail!("XMR amount must contain digits");
    }
    if !whole_str.bytes().all(|b| b.is_ascii_digit())
        || !frac_str.bytes().all(|b| b.is_ascii_digit())
    {
        bail!("XMR amount contains non-numeric characters: {trimmed:?}");
    }
    if frac_str.len() > 12 {
        bail!(
            "XMR amount exceeds 12 piconero decimal places ({} fractional digits)",
            frac_str.len()
        );
    }

    let whole: u64 = if whole_str.is_empty() {
        0
    } else {
        whole_str
            .parse::<u64>()
            .map_err(|_| anyhow!("XMR whole amount overflows u64: {trimmed:?}"))?
    };

    let whole_atomic = whole
        .checked_mul(PICONERO_PER_XMR)
        .ok_or_else(|| anyhow!("XMR amount overflows u64 piconero: {trimmed:?}"))?;

    let frac_atomic: u64 = if frac_str.is_empty() {
        0
    } else {
        let mut padded = String::with_capacity(12);
        padded.push_str(frac_str);
        while padded.len() < 12 {
            padded.push('0');
        }
        padded
            .parse::<u64>()
            .map_err(|_| anyhow!("invalid XMR fractional digits: {trimmed:?}"))?
    };

    whole_atomic
        .checked_add(frac_atomic)
        .ok_or_else(|| anyhow!("XMR amount overflows u64 piconero: {trimmed:?}"))
}

/// Parse an input string that is either decimal XMR (`"0.5"`, `"1.25"`, `"2"`)
/// or raw atomic piconero (`"500000000000"`, `"500000000000piconero"`).
pub fn parse_amount_decimal_or_atomic(input: &str) -> Result<u64> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        bail!("amount must not be empty");
    }
    let lower = trimmed.to_ascii_lowercase();
    for suffix in ["piconero", "atomic"] {
        if let Some(num) = lower.strip_suffix(suffix) {
            let clean = num.trim();
            if clean.is_empty() || !clean.bytes().all(|b| b.is_ascii_digit()) {
                bail!("invalid atomic piconero amount: {trimmed:?}");
            }
            return clean
                .parse::<u64>()
                .map_err(|_| anyhow!("atomic amount overflows u64: {trimmed:?}"));
        }
    }

    if trimmed.contains('.') {
        return parse_xmr_to_atomic(trimmed);
    }

    if !trimmed.bytes().all(|b| b.is_ascii_digit()) {
        bail!("invalid XMR/atomic amount: {trimmed:?}");
    }

    let raw_int = trimmed
        .parse::<u64>()
        .map_err(|_| anyhow!("amount overflows u64: {trimmed:?}"))?;
    if raw_int > MAX_WHOLE_XMR_U64 {
        Ok(raw_int)
    } else {
        parse_xmr_to_atomic(trimmed)
    }
}

/// Validate a Monero mainnet address (`4...` primary/integrated or `8...` subaddress).
pub fn parse_mainnet_address(address: &str) -> Result<MoneroAddress> {
    let trimmed = address.trim();
    MoneroAddress::from_str(Network::Mainnet, trimmed)
        .map_err(|e| anyhow!("invalid Monero mainnet address: {e}"))
}

/// Parsed `monero:<address>?tx_amount=...&tx_description=...&recipient_name=...` payment URI.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MoneroUri {
    pub address: String,
    pub tx_amount_atomic: Option<u64>,
    pub tx_amount_xmr: Option<String>,
    pub recipient_name: Option<String>,
    pub tx_description: Option<String>,
}

/// Parse and cryptographically validate a `monero:` URI.
pub fn parse_monero_uri(raw_uri: &str) -> Result<MoneroUri> {
    let trimmed = raw_uri.trim();
    if trimmed.len() < 8 || !trimmed[..7].eq_ignore_ascii_case("monero:") {
        bail!("URI does not start with monero: scheme");
    }
    let after_scheme = trimmed[7..].trim_start_matches('/');
    let (addr_part, query_part) = match after_scheme.split_once('?') {
        Some((a, q)) => (a.trim(), Some(q)),
        None => (after_scheme.trim(), None),
    };

    let validated_addr = parse_mainnet_address(addr_part)?;
    let mut tx_amount_atomic = None;
    let mut tx_amount_xmr = None;
    let mut recipient_name = None;
    let mut tx_description = None;

    if let Some(q) = query_part {
        let helper = Url::parse(&format!("http://monero.invalid/?{q}"))
            .map_err(|e| anyhow!("invalid monero: URI query string: {e}"))?;
        for (key, value) in helper.query_pairs() {
            match key.as_ref() {
                "tx_amount" | "amount" => {
                    let v = value.trim();
                    if !v.is_empty() {
                        let atomic = parse_xmr_to_atomic(v)?;
                        tx_amount_xmr = Some(format_atomic_xmr(atomic));
                        tx_amount_atomic = Some(atomic);
                    }
                }
                "recipient_name" => {
                    if !value.is_empty() {
                        recipient_name = Some(value.into_owned());
                    }
                }
                "tx_description" | "message" => {
                    if !value.is_empty() {
                        tx_description = Some(value.into_owned());
                    }
                }
                _ => {}
            }
        }
    }

    Ok(MoneroUri {
        address: validated_addr.to_string(),
        tx_amount_atomic,
        tx_amount_xmr,
        recipient_name,
        tx_description,
    })
}

/// Format a [`MoneroUri`] into a canonical `monero:<address>?...` URI string.
pub fn format_monero_uri(req: &MoneroUri) -> Result<String> {
    let validated = parse_mainnet_address(&req.address)?;
    let mut helper = Url::parse("http://monero.invalid/").expect("static URL");
    let mut has_params = false;
    {
        let mut qp = helper.query_pairs_mut();
        if let Some(atomic) = req.tx_amount_atomic {
            qp.append_pair("tx_amount", &format_atomic_xmr(atomic));
            has_params = true;
        } else if let Some(ref xmr) = req.tx_amount_xmr {
            let atomic = parse_xmr_to_atomic(xmr)?;
            qp.append_pair("tx_amount", &format_atomic_xmr(atomic));
            has_params = true;
        }
        if let Some(ref name) = req.recipient_name {
            if !name.is_empty() {
                qp.append_pair("recipient_name", name);
                has_params = true;
            }
        }
        if let Some(ref desc) = req.tx_description {
            if !desc.is_empty() {
                qp.append_pair("tx_description", desc);
                has_params = true;
            }
        }
    }

    if has_params {
        let query = helper.query().unwrap_or("");
        Ok(format!("monero:{validated}?{query}"))
    } else {
        Ok(format!("monero:{validated}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_SUBADDR: &str = "82ffYh7xRhFBvBHEHGV8TNRBoYucBFNoyYnwrJJjnMGpHKPWosZeB4BDr7pBG4DCux1WKepdAV6rRTimi1LqWMacF88hvQm";

    #[test]
    fn exact_atomic_xmr_roundtrips_without_float_drift() {
        let cases = [
            (0u64, "0"),
            (1u64, "0.000000000001"),
            (31_000_000u64, "0.000031"),
            (500_000_000_000u64, "0.5"),
            (1_000_000_000_000u64, "1"),
            (1_239_077_910_000u64, "1.23907791"),
            (u64::MAX, "18446744.073709551615"),
        ];
        for (atomic, decimal) in cases {
            assert_eq!(format_atomic_xmr(atomic), decimal);
            assert_eq!(parse_xmr_to_atomic(decimal).unwrap(), atomic);
        }
        assert_eq!(parse_xmr_to_atomic(".25").unwrap(), 250_000_000_000);
        assert_eq!(parse_xmr_to_atomic("2.").unwrap(), 2_000_000_000_000);
        assert!(parse_xmr_to_atomic("0.0000000000001").is_err());
        assert!(parse_xmr_to_atomic("-0.5").is_err());
        assert!(parse_xmr_to_atomic("18446745").is_err());

        assert_eq!(
            parse_amount_decimal_or_atomic("0.5").unwrap(),
            500_000_000_000
        );
        assert_eq!(
            parse_amount_decimal_or_atomic("500000000000").unwrap(),
            500_000_000_000
        );
        assert_eq!(
            parse_amount_decimal_or_atomic("12345atomic").unwrap(),
            12345
        );
    }

    #[test]
    fn monero_uri_parses_and_formats_cleanly() {
        let raw = format!(
            "monero:{SAMPLE_SUBADDR}?tx_amount=1.2500&recipient_name=Alice%20Shop&tx_description=Order%20%2342"
        );
        let parsed = parse_monero_uri(&raw).unwrap();
        assert_eq!(parsed.address, SAMPLE_SUBADDR);
        assert_eq!(parsed.tx_amount_atomic, Some(1_250_000_000_000));
        assert_eq!(parsed.tx_amount_xmr.as_deref(), Some("1.25"));
        assert_eq!(parsed.recipient_name.as_deref(), Some("Alice Shop"));
        assert_eq!(parsed.tx_description.as_deref(), Some("Order #42"));

        let formatted = format_monero_uri(&parsed).unwrap();
        let reparsed = parse_monero_uri(&formatted).unwrap();
        assert_eq!(reparsed, parsed);

        assert!(parse_monero_uri("monero:not_a_valid_address?tx_amount=1").is_err());
    }
}
