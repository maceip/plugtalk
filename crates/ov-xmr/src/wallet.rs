//! Monero key hierarchy, `(0, minor)` subaddresses, key-image derivation, and LWS output reconstruction.

use anyhow::{anyhow, bail, Result};
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use monero_wallet::{
    address::{MoneroAddress, Network, SubaddressIndex},
    ed25519::{Commitment, CompressedPoint, Point, Scalar},
    io::VarInt,
    primitives::keccak256,
    transaction::Timelock,
    ViewPair, WalletOutput,
};
use zeroize::Zeroizing;

use crate::{mnemonic::validate_canonical_spend_scalar, uri::parse_mainnet_address};

/// Derived public/view wallet keys (spend secret is never held here).
#[derive(Clone)]
pub struct DerivedWalletView {
    pub primary_address: String,
    pub view_key_bytes: Zeroizing<[u8; 32]>,
    pub view_key_hex: Zeroizing<String>,
    pub view_pair: ViewPair,
}

/// Derive `view_scalar = Hs(spend_bytes)`, `ViewPair`, and mainnet `4...` primary address.
pub fn derive_wallet_from_spend(spend_bytes: &[u8; 32]) -> Result<DerivedWalletView> {
    let spend_scalar = validate_canonical_spend_scalar(spend_bytes)?;
    let spend_dalek: curve25519_dalek::Scalar = spend_scalar.into();
    let spend_point = Point::from(&spend_dalek * ED25519_BASEPOINT_TABLE);

    let view_scalar = Scalar::hash(spend_bytes);
    let view_dalek: curve25519_dalek::Scalar = view_scalar.into();
    let view_key_bytes = Zeroizing::new(view_dalek.to_bytes());
    let view_key_hex = Zeroizing::new(hex::encode(*view_key_bytes));

    let view_pair = ViewPair::new(spend_point, Zeroizing::new(view_scalar))
        .map_err(|e| anyhow!("invalid Monero ViewPair: {e}"))?;
    let primary_address = view_pair.legacy_address(Network::Mainnet).to_string();

    Ok(DerivedWalletView {
        primary_address,
        view_key_bytes,
        view_key_hex,
        view_pair,
    })
}

/// Reconstruct [`ViewPair`] from stored `primary_address` (`4...`) and `view_key_bytes` (`32 B`)
/// without touching or unwrapping the secret spend key.
pub fn reconstruct_view_pair(primary_address: &str, view_key_bytes: &[u8]) -> Result<ViewPair> {
    let addr = parse_mainnet_address(primary_address)?;
    let arr: [u8; 32] = view_key_bytes.try_into().map_err(|_| {
        anyhow!(
            "view_key_bytes must be 32 bytes, got {}",
            view_key_bytes.len()
        )
    })?;
    let ct_opt = curve25519_dalek::Scalar::from_canonical_bytes(arr);
    let view_dalek = Option::<curve25519_dalek::Scalar>::from(ct_opt)
        .ok_or_else(|| anyhow!("stored view_key_bytes is not a canonical Ed25519 scalar"))?;
    let view_scalar = Scalar::from(view_dalek);
    let vp = ViewPair::new(addr.spend(), Zeroizing::new(view_scalar))
        .map_err(|e| anyhow!("cannot reconstruct ViewPair: {e}"))?;
    if vp.legacy_address(Network::Mainnet) != addr {
        bail!("stored view_key_bytes does not match primary_address public view key");
    }
    Ok(vp)
}

/// Derive a deterministic `(0, minor)` `8...` subaddress (`minor >= 1`).
pub fn derive_subaddress(view_pair: &ViewPair, minor: u32) -> Result<MoneroAddress> {
    let idx = SubaddressIndex::new(0, minor)
        .ok_or_else(|| anyhow!("subaddress minor index must be >= 1 (got {minor})"))?;
    Ok(view_pair.subaddress(Network::Mainnet, idx))
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

/// Raw output entry returned by `monero-lws` `/get_unspent_outs`.
#[derive(Clone, Debug)]
pub struct LwsUnspentCandidate {
    pub tx_hash: [u8; 32],
    pub vout: u64,
    pub global_index: u64,
    pub amount_atomic: u64,
    pub output_pub_key: [u8; 32],
    pub tx_pub_key: [u8; 32],
    pub rct: String,
}

/// Reconstruct a [`WalletOutput`], subaddress minor index, and real [`CompressedPoint`] key image
/// from a `monero-lws` `/get_unspent_outs` candidate so client-side spentness and signing work
/// identically to full block scanning.
pub fn reconstruct_lws_wallet_output(
    view_pair: &ViewPair,
    view_key_bytes: &[u8],
    spend_scalar: Scalar,
    max_subaddress_minor: u32,
    cand: &LwsUnspentCandidate,
) -> Result<(WalletOutput, u32, CompressedPoint)> {
    let out_cp = CompressedPoint::from(cand.output_pub_key);
    let out_point = out_cp
        .decompress()
        .ok_or_else(|| anyhow!("invalid output public key point from LWS"))?;
    let tx_cp = CompressedPoint::from(cand.tx_pub_key);
    let tx_point = tx_cp
        .decompress()
        .ok_or_else(|| anyhow!("invalid tx_pub_key point from LWS"))?;

    let view_arr: [u8; 32] = view_key_bytes
        .try_into()
        .map_err(|_| anyhow!("view_key_bytes must be 32 bytes"))?;
    let view_dalek = Zeroizing::new(
        Option::<curve25519_dalek::Scalar>::from(curve25519_dalek::Scalar::from_canonical_bytes(
            view_arr,
        ))
        .ok_or_else(|| anyhow!("non-canonical view_key_bytes"))?,
    );
    let tx_ed: curve25519_dalek::EdwardsPoint = tx_point.into();
    let ecdh_raw = *view_dalek * tx_ed;
    let ecdh_cofactor = Zeroizing::new(ecdh_raw.mul_by_cofactor().compress().to_bytes());

    let mut derivation_buf = Zeroizing::new(Vec::with_capacity(40));
    derivation_buf.extend_from_slice(&*ecdh_cofactor);
    let vout_usize = usize::try_from(cand.vout).map_err(|_| anyhow!("vout exceeds usize"))?;
    VarInt::write(&vout_usize, &mut *derivation_buf)
        .map_err(|e| anyhow!("VarInt encode failed: {e}"))?;
    let shared_key = Scalar::hash(&*derivation_buf);
    let shared_dalek: curve25519_dalek::Scalar = shared_key.into();

    let out_ed: curve25519_dalek::EdwardsPoint = out_point.into();
    let candidate_sub_spend = out_ed - (&shared_dalek * ED25519_BASEPOINT_TABLE);
    let candidate_sub_bytes = candidate_sub_spend.compress().to_bytes();

    let mut matched_sub: Option<SubaddressIndex> = None;
    let mut found = false;
    if candidate_sub_bytes == view_pair.spend().compress().to_bytes() {
        found = true;
    } else {
        for minor in 1..=max_subaddress_minor.saturating_add(20) {
            if let Some(sub_idx) = SubaddressIndex::new(0, minor) {
                let sub_addr = view_pair.subaddress(Network::Mainnet, sub_idx);
                if sub_addr.spend().compress().to_bytes() == candidate_sub_bytes {
                    matched_sub = Some(sub_idx);
                    found = true;
                    break;
                }
            }
        }
    }
    if !found {
        bail!("LWS output does not match primary address or lookahead subaddresses");
    }

    let mut key_offset_dalek = shared_dalek;
    if let Some(sub_idx) = matched_sub {
        let sub_deriv_scalar = Scalar::hash(Zeroizing::new(
            [
                b"SubAddr\0".as_slice(),
                &view_arr,
                &sub_idx.account().to_le_bytes(),
                &sub_idx.address().to_le_bytes(),
            ]
            .concat(),
        ));
        let sub_deriv: curve25519_dalek::Scalar = sub_deriv_scalar.into();
        key_offset_dalek += sub_deriv;
    }
    let key_offset = Scalar::from(key_offset_dalek);

    let spend_dalek: curve25519_dalek::Scalar = spend_scalar.into();
    let x = Zeroizing::new(spend_dalek + key_offset_dalek);
    if Point::from(&*x * ED25519_BASEPOINT_TABLE).compress() != out_cp {
        bail!("LWS output derived secret scalar does not match output public key");
    }

    let mask = derive_lws_commitment_mask(&cand.rct, shared_key)?;
    let commitment = Commitment::new(mask, cand.amount_atomic);

    // Serialize into `WalletOutput::read` format:
    // AbsoluteId (tx_hash 32B + vout u64 LE)
    // RelativeId (global_index u64 LE)
    // OutputData (key 32B + key_offset 32B + commitment (mask 32B + amount u64 LE))
    // Metadata (Timelock::None 1B(0) + subaddress flag + payment_id flag 0 + arbitrary_data 0)
    let mut raw = Vec::with_capacity(148);
    raw.extend_from_slice(&cand.tx_hash);
    raw.extend_from_slice(&cand.vout.to_le_bytes());
    raw.extend_from_slice(&cand.global_index.to_le_bytes());
    raw.extend_from_slice(&out_cp.to_bytes());
    key_offset.write(&mut raw)?;
    commitment.write(&mut raw)?;
    Timelock::None.write(&mut raw)?;
    match matched_sub {
        Some(sub) => {
            raw.push(1);
            raw.extend_from_slice(&sub.account().to_le_bytes());
            raw.extend_from_slice(&sub.address().to_le_bytes());
        }
        None => raw.push(0),
    }
    raw.push(0); // payment_id: None
    VarInt::write(&0usize, &mut raw)?; // arbitrary_data: empty

    let wallet_out = WalletOutput::read(&mut raw.as_slice())
        .map_err(|e| anyhow!("cannot build WalletOutput from LWS candidate: {e}"))?;
    let ki = compute_key_image(spend_scalar, &wallet_out);
    let minor = matched_sub.map(|s| s.address()).unwrap_or(0);
    Ok((wallet_out, minor, ki))
}

fn derive_lws_commitment_mask(rct: &str, shared_key: Scalar) -> Result<Scalar> {
    let trimmed = rct.trim();
    if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("coinbase") {
        // Coinbase / transparent RingCT uses mask = 1
        return Ok(Scalar::ONE);
    }
    // LWS `rct` format: 64-hex commitment || 64-hex mask || 64-hex amount (or "compact" 16-hex)
    if trimmed.len() >= 128 {
        let mask_hex = &trimmed[64..128];
        if mask_hex.chars().all(|c| c == '0') {
            // Compact RingCT (`rctType >= 2`): mask = Hs("commitment_mask" || shared_key)
            let mut buf = Zeroizing::new(b"commitment_mask".to_vec());
            let sk_dalek: curve25519_dalek::Scalar = shared_key.into();
            buf.extend_from_slice(&sk_dalek.to_bytes());
            return Ok(Scalar::hash(&*buf));
        }
        let mask_bytes: [u8; 32] = hex::decode(mask_hex)
            .map_err(|e| anyhow!("invalid LWS rct mask hex: {e}"))?
            .try_into()
            .map_err(|_| anyhow!("LWS rct mask not 32 bytes"))?;
        let mask_shared = Scalar::hash(Zeroizing::new({
            let d: curve25519_dalek::Scalar = shared_key.into();
            d.to_bytes()
        }));
        let mask_shared_d: curve25519_dalek::Scalar = mask_shared.into();
        let mask_d = curve25519_dalek::Scalar::from_bytes_mod_order(mask_bytes) - mask_shared_d;
        let _ = keccak256([]); // keep import used cleanly
        return Ok(Scalar::from(mask_d));
    }
    let mut buf = Zeroizing::new(b"commitment_mask".to_vec());
    let sk_dalek: curve25519_dalek::Scalar = shared_key.into();
    buf.extend_from_slice(&sk_dalek.to_bytes());
    Ok(Scalar::hash(&*buf))
}
