//! Pure-Rust cryptographic address & payment URI validators for zero-JS DOM classification.
//!
//! Validates:
//! - Bitcoin mainnet Base58Check (`1...` P2PKH `0x00`, `3...` P2SH `0x05`) via double-SHA-256,
//! - Bitcoin mainnet SegWit/Taproot Bech32 & Bech32m (`bc1q...` BIP-173, `bc1p...` BIP-350),
//! - Monero mainnet addresses (`4...` primary/integrated, `8...` subaddress) & `monero:` URIs.

use monero_address::{MoneroAddress, Network};
use sha2::{Digest, Sha256};

const BASE58_ALPHABET: &[u8; 58] = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
const BECH32_CHARSET: &[u8; 32] = b"qpzry9x8gf2tvdw0s3jn54khce6mua7l";
const BECH32_CONST: u32 = 1;
const BECH32M_CONST: u32 = 0x2bc8_30a3;

/// Validate a Bitcoin mainnet address (`bc1q...`, `bc1p...`, `1...`, or `3...`)
/// and return its normalized string if checksum-valid.
pub fn validate_btc_mainnet_address(candidate: &str) -> Option<String> {
    let trimmed = candidate.trim();
    if trimmed.len() < 14 || trimmed.len() > 90 || !trimmed.is_ascii() {
        return None;
    }
    if trimmed.len() >= 4 && trimmed[..3].eq_ignore_ascii_case("bc1") {
        validate_btc_bech32(trimmed)
    } else if trimmed.starts_with('1') || trimmed.starts_with('3') {
        validate_btc_base58check(trimmed)
    } else {
        None
    }
}

/// Parse a `bitcoin:<address>?amount=...` URI, returning `(validated_address, optional_amount)`.
pub fn parse_bitcoin_uri(raw: &str) -> Option<(String, Option<String>)> {
    let trimmed = raw.trim();
    if trimmed.len() < 9 || !trimmed[..8].eq_ignore_ascii_case("bitcoin:") {
        return None;
    }
    let after = trimmed[8..].trim_start_matches('/');
    let (addr_part, query_opt) = match after.split_once('?') {
        Some((a, q)) => (a.trim(), Some(q)),
        None => (after.trim(), None),
    };
    let valid_addr = validate_btc_mainnet_address(addr_part)?;
    let amount = query_opt.and_then(|q| extract_uri_numeric_param(q, &["amount"]));
    Some((valid_addr, amount))
}

/// Validate a `monero:<address>?...` URI, returning `(normalized_uri, optional_tx_amount)`.
pub fn validate_monero_uri_str(raw: &str) -> Option<(String, Option<String>)> {
    let trimmed = raw.trim();
    if trimmed.len() < 95 || !trimmed[..7].eq_ignore_ascii_case("monero:") {
        return None;
    }
    if trimmed.chars().any(char::is_whitespace) {
        return None;
    }
    let after = trimmed[7..].trim_start_matches('/');
    let (addr_part, query_opt) = match after.split_once('?') {
        Some((a, q)) => (a.trim(), Some(q)),
        None => (after.trim(), None),
    };
    let addr = MoneroAddress::from_str(Network::Mainnet, addr_part).ok()?;
    let amount = query_opt.and_then(|q| extract_uri_numeric_param(q, &["tx_amount", "amount"]));
    let canonical = match query_opt {
        Some(q) if !q.is_empty() => format!("monero:{addr}?{q}"),
        _ => format!("monero:{addr}"),
    };
    Some((canonical, amount))
}

fn extract_uri_numeric_param(query: &str, keys: &[&str]) -> Option<String> {
    for pair in query.split('&') {
        let Some((k, v)) = pair.split_once('=') else {
            continue;
        };
        if keys.iter().any(|expected| k.eq_ignore_ascii_case(expected)) {
            let clean = clean_positive_decimal(v.trim())?;
            return Some(clean);
        }
    }
    None
}

/// Validate a positive decimal token (`"0.01"`, `"1.25"`).
pub fn clean_positive_decimal(s: &str) -> Option<String> {
    let t = s.trim();
    if t.is_empty() || t.len() > 24 {
        return None;
    }
    let (w, f) = match t.split_once('.') {
        Some((w, f)) => {
            if f.contains('.') {
                return None;
            }
            (w, f)
        }
        None => (t, ""),
    };
    if (w.is_empty() && f.is_empty())
        || !w.bytes().all(|b| b.is_ascii_digit())
        || !f.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    if !t.bytes().any(|b| b.is_ascii_digit() && b != b'0') {
        return None;
    }
    Some(t.to_string())
}

fn validate_btc_base58check(addr: &str) -> Option<String> {
    if !(25..=35).contains(&addr.len()) {
        return None;
    }
    let mut leading_zeros = 0usize;
    for &b in addr.as_bytes() {
        if b == b'1' {
            leading_zeros += 1;
        } else {
            break;
        }
    }

    let mut b256 = vec![0u8; 32];
    for &ch in addr.as_bytes() {
        let digit = BASE58_ALPHABET.iter().position(|&c| c == ch)? as u32;
        let mut carry = digit;
        for byte in b256.iter_mut().rev() {
            carry += u32::from(*byte) * 58;
            *byte = (carry & 0xff) as u8;
            carry >>= 8;
        }
        if carry != 0 {
            return None;
        }
    }

    let first_nonzero = b256.iter().position(|&b| b != 0).unwrap_or(b256.len());
    let mut decoded = Vec::with_capacity(leading_zeros + (b256.len() - first_nonzero));
    decoded.resize(leading_zeros, 0u8);
    decoded.extend_from_slice(&b256[first_nonzero..]);

    if decoded.len() != 25 {
        return None;
    }
    let version = decoded[0];
    if (addr.starts_with('1') && version != 0x00) || (addr.starts_with('3') && version != 0x05) {
        return None;
    }

    let h1 = Sha256::digest(&decoded[..21]);
    let h2 = Sha256::digest(h1);
    if h2[..4] != decoded[21..25] {
        return None;
    }
    Some(addr.to_string())
}

fn validate_btc_bech32(addr: &str) -> Option<String> {
    let has_lower = addr.bytes().any(|b| b.is_ascii_lowercase());
    let has_upper = addr.bytes().any(|b| b.is_ascii_uppercase());
    if has_lower && has_upper {
        return None;
    }
    let lower = addr.to_ascii_lowercase();
    let data_part = lower.strip_prefix("bc1")?;
    if data_part.len() < 6 {
        return None;
    }

    let mut values = Vec::with_capacity(data_part.len());
    for b in data_part.bytes() {
        let v = BECH32_CHARSET.iter().position(|&c| c == b)? as u8;
        values.push(v);
    }

    // `hrp_expand("bc")` = [b'b' >> 5, b'c' >> 5, 0, b'b' & 31, b'c' & 31] = [3, 3, 0, 2, 3]
    let mut poly_input = Vec::with_capacity(5 + values.len());
    poly_input.extend_from_slice(&[3, 3, 0, 2, 3]);
    poly_input.extend_from_slice(&values);
    let polymod = bech32_polymod(&poly_input);

    let witness_ver = values[0];
    if witness_ver > 16 {
        return None;
    }
    let expected_const = if witness_ver == 0 {
        BECH32_CONST
    } else {
        BECH32M_CONST
    };
    if polymod != expected_const {
        return None;
    }

    let program = convert_bits_5_to_8(&values[1..values.len() - 6])?;
    if !(2..=40).contains(&program.len()) {
        return None;
    }
    if witness_ver == 0 && program.len() != 20 && program.len() != 32 {
        return None;
    }
    if witness_ver == 1 && program.len() != 32 {
        return None;
    }

    Some(lower)
}

fn bech32_polymod(values: &[u8]) -> u32 {
    const GEN: [u32; 5] = [
        0x3b6a_57b2,
        0x2650_8e6d,
        0x1ea1_19fa,
        0x3d42_33dd,
        0x2a14_62b3,
    ];
    let mut chk: u32 = 1;
    for &v in values {
        let top = chk >> 25;
        chk = ((chk & 0x01ff_ffff) << 5) ^ u32::from(v);
        for (i, &g) in GEN.iter().enumerate() {
            if ((top >> i) & 1) != 0 {
                chk ^= g;
            }
        }
    }
    chk
}

fn convert_bits_5_to_8(data: &[u8]) -> Option<Vec<u8>> {
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    let mut out = Vec::with_capacity((data.len() * 5) / 8);
    for &value in data {
        if value >= 32 {
            return None;
        }
        acc = (acc << 5) | u32::from(value);
        bits += 5;
        while bits >= 8 {
            bits -= 8;
            out.push(((acc >> bits) & 0xff) as u8);
        }
    }
    if bits >= 5 || ((acc << (8 - bits)) & 0xff) != 0 {
        return None;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_bech32_bech32m_and_base58check_btc_addresses() {
        // Real Bech32 v0 P2WPKH (WizardSwap order + BIP-173 vector)
        assert_eq!(
            validate_btc_mainnet_address("bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2").as_deref(),
            Some("bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q2")
        );
        assert_eq!(
            validate_btc_mainnet_address("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4").as_deref(),
            Some("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        );
        // Mixed-case Bech32 must be rejected
        assert!(
            validate_btc_mainnet_address("bc1Qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4").is_none()
        );
        // Tampered Bech32 checksum must be rejected
        assert!(
            validate_btc_mainnet_address("bc1qwxcndrucjwn7f4u2vrq2lx0hv543sjz57e77q3").is_none()
        );

        // Real Bech32m v1 Taproot P2TR (BIP-350 test vector)
        assert_eq!(
            validate_btc_mainnet_address(
                "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
            )
            .as_deref(),
            Some("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0")
        );
        // Bech32m v1 using old Bech32 checksum must fail
        assert!(validate_btc_mainnet_address(
            "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd"
        )
        .is_none());

        // Real Base58Check P2SH (`3...` from HiddenSwap live order) & P2PKH (`1...` genesis)
        assert_eq!(
            validate_btc_mainnet_address("3AQ2gsD1FG5vhFiGGzTZd7N57WwDJj4SSi").as_deref(),
            Some("3AQ2gsD1FG5vhFiGGzTZd7N57WwDJj4SSi")
        );
        assert_eq!(
            validate_btc_mainnet_address("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa").as_deref(),
            Some("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
        );
        // Tampered Base58Check checksum must fail
        assert!(validate_btc_mainnet_address("3AQ2gsD1FG5vhFiGGzTZd7N57WwDJj4SSj").is_none());
    }
}
