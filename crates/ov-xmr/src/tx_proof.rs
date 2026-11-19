//! Canonical Monero `OutProofV2` (`wallet2::get_tx_proof` / `check_tx_proof`) and
//! deterministic `monero-wallet` ephemeral transaction secret key reconstruction.

use anyhow::{anyhow, bail, Result};
use curve25519_dalek::{
    constants::ED25519_BASEPOINT_TABLE,
    edwards::{CompressedEdwardsY, EdwardsPoint},
};
use monero_address::MoneroAddress;
use monero_wallet::{
    ed25519::{Commitment, CompressedPoint, Point, Scalar},
    extra::Extra,
    io::VarInt,
    primitives::keccak256,
    ringct::{EncryptedAmount, RctProofs},
    send::TransactionKeys,
    transaction::Transaction,
};
use rand_core::{CryptoRng, RngCore};
use zeroize::Zeroizing;

/// Domain separator hashed into every `OutProofV2` Schnorr DLEQ challenge (`wallet2.cpp` `HASH_KEY_TXPROOF_V2`).
pub const TXPROOF_V2_DOMAIN: &[u8] = b"TXPROOF_V2";

/// Canonical ASCII header prefix of Monero v2 outgoing payment proofs.
pub const OUTPROOF_V2_HEADER: &str = "OutProofV2";

/// Monero block-Base58 encoded length of a 32-byte compressed Ed25519 point (`4 * 11 = 44`).
pub const BASE58_PK_LEN: usize = 44;

/// Monero block-Base58 encoded length of a 64-byte `(c, s)` Schnorr signature (`8 * 11 = 88`).
pub const BASE58_SIG_LEN: usize = 88;

/// Monero block-Base58 encoded length of one `(D_i, sig_i)` proof entry (`44 + 88 = 132`).
pub const BASE58_ENTRY_LEN: usize = BASE58_PK_LEN + BASE58_SIG_LEN;

const SECRET_BLOB_VERSION_V1: u8 = 1;

/// Unwrapped outgoing transaction secret material (`(outgoing_view_key, spent_input_points)`).
pub type DecodedTxSecretMaterial = (Zeroizing<[u8; 32]>, Vec<(Point, Point)>);

/// Reconstructed ephemeral transaction secret scalars (`(tx_key, additional_tx_keys)`).
pub type ReconstructedTxKeys = (Zeroizing<Scalar>, Vec<Zeroizing<Scalar>>);

/// Format an `XMR402` v2.0 `Authorization` HTTP header value.
pub fn format_xmr402_authorization_header(txid_hex: &str, proof: &str) -> String {
    format!("XMR402 txid=\"{txid_hex}\", proof=\"{proof}\"")
}

/// Encode `(outgoing_view_key, spent_input_key_commitment_pairs)` into a compact
/// binary buffer suitable for wrapping with [`ov_pgp::MasterKeyProvider`].
pub fn encode_tx_secret_material(
    outgoing_view_key: &[u8; 32],
    input_key_commitment_pairs: &[([u8; 32], [u8; 32])],
) -> Zeroizing<Vec<u8>> {
    let count = u32::try_from(input_key_commitment_pairs.len()).unwrap_or(u32::MAX);
    let mut buf = Zeroizing::new(Vec::with_capacity(
        1 + 32 + 4 + input_key_commitment_pairs.len() * 64,
    ));
    buf.push(SECRET_BLOB_VERSION_V1);
    buf.extend_from_slice(outgoing_view_key);
    buf.extend_from_slice(&count.to_le_bytes());
    for (key_bytes, commitment_bytes) in input_key_commitment_pairs {
        buf.extend_from_slice(key_bytes);
        buf.extend_from_slice(commitment_bytes);
    }
    buf
}

/// Decode `(outgoing_view_key, Vec<(Point, Point)>)` from an unwrapped secret blob.
pub fn decode_tx_secret_material(raw: &[u8]) -> Result<DecodedTxSecretMaterial> {
    if raw.len() < 1 + 32 + 4 {
        bail!("tx secret material is truncated ({} bytes)", raw.len());
    }
    if raw[0] != SECRET_BLOB_VERSION_V1 {
        bail!("unsupported tx secret material version {}", raw[0]);
    }
    let mut ovk = Zeroizing::new([0u8; 32]);
    ovk.copy_from_slice(&raw[1..33]);
    let count = u32::from_le_bytes([raw[33], raw[34], raw[35], raw[36]]) as usize;
    if count == 0 || count > 256 {
        bail!("invalid tx secret input count {count}");
    }
    let expected_len = 1 + 32 + 4 + count * 64;
    if raw.len() != expected_len {
        bail!(
            "tx secret material length mismatch (expected {expected_len}, got {})",
            raw.len()
        );
    }
    let mut pairs = Vec::with_capacity(count);
    for idx in 0..count {
        let base = 37 + idx * 64;
        let mut key_arr = [0u8; 32];
        let mut commit_arr = [0u8; 32];
        key_arr.copy_from_slice(&raw[base..base + 32]);
        commit_arr.copy_from_slice(&raw[base + 32..base + 64]);
        let key_pt = CompressedPoint::from(key_arr)
            .decompress()
            .ok_or_else(|| anyhow!("invalid input key point in stored tx secret"))?;
        let commit_pt = CompressedPoint::from(commit_arr)
            .decompress()
            .ok_or_else(|| anyhow!("invalid input commitment point in stored tx secret"))?;
        pairs.push((key_pt, commit_pt));
    }
    Ok((ovk, pairs))
}

/// Reconstruct `(tx_key, additional_tx_keys)` from `outgoing_view_key`, spent input
/// `(key, commitment)` pairs, and raw `tx.prefix().extra` bytes.
pub fn reconstruct_tx_secret_keys_from_extra(
    outgoing_view_key: &Zeroizing<[u8; 32]>,
    input_keys_and_commitments: Vec<(Point, Point)>,
    tx_extra: &[u8],
) -> Result<ReconstructedTxKeys> {
    let extra =
        Extra::read(&mut &tx_extra[..]).map_err(|e| anyhow!("failed to parse tx.extra: {e}"))?;
    let (_, additional_pks) = extra
        .keys()
        .ok_or_else(|| anyhow!("missing tx pubkey in tx.extra"))?;
    let additional_len = additional_pks.as_ref().map_or(0, Vec::len);

    let mut key_iter = TransactionKeys::new(outgoing_view_key, input_keys_and_commitments);
    let tx_key = key_iter
        .next()
        .ok_or_else(|| anyhow!("TransactionKeys iterator exhausted on primary tx_key"))?;
    let mut additional_keys = Vec::with_capacity(additional_len);
    for _ in 0..additional_len {
        additional_keys.push(
            key_iter
                .next()
                .ok_or_else(|| anyhow!("TransactionKeys iterator exhausted on additional_keys"))?,
        );
    }
    Ok((tx_key, additional_keys))
}

/// Reconstruct `(tx_key, additional_tx_keys)` for any `Transaction::V2` created by
/// `monero-wallet = "0.2"` from its `outgoing_view_key`, spent `inputs`
/// (`(output.key(), output.commitment().commit())`), and `tx.extra`.
pub fn reconstruct_tx_secret_keys(
    outgoing_view_key: &Zeroizing<[u8; 32]>,
    input_keys_and_commitments: Vec<(Point, Point)>,
    tx: &Transaction,
) -> Result<ReconstructedTxKeys> {
    reconstruct_tx_secret_keys_from_extra(
        outgoing_view_key,
        input_keys_and_commitments,
        &tx.prefix().extra,
    )
}

fn generate_single_tx_proof_v2(
    prefix_hash: &[u8; 32],
    r: curve25519_dalek::Scalar,
    a_point: EdwardsPoint,
    b_point_opt: Option<EdwardsPoint>,
    rng: &mut (impl RngCore + CryptoRng),
) -> ([u8; 32], [u8; 64]) {
    // D = r * A (un-cofactored, matching wallet2.cpp `hwdev.scalarmultKey(aP, A, r)`)
    let d_bytes = (r * a_point).compress().to_bytes();
    // R_temp = r * B (if subaddress) else r * G (matching wallet2.cpp lines 12949-12974)
    let r_pub_bytes = match b_point_opt {
        Some(b_pt) => (r * b_pt).compress().to_bytes(),
        None => (&r * ED25519_BASEPOINT_TABLE).compress().to_bytes(),
    };

    let k_d: curve25519_dalek::Scalar = Scalar::random(rng).into();
    let k = Zeroizing::new(k_d);
    let x_bytes = match b_point_opt {
        Some(b_pt) => (*k * b_pt).compress().to_bytes(),
        None => (&*k * ED25519_BASEPOINT_TABLE).compress().to_bytes(),
    };
    let y_bytes = (*k * a_point).compress().to_bytes();
    let sep = keccak256(TXPROOF_V2_DOMAIN);
    let a_bytes = a_point.compress().to_bytes();
    let b_bytes = b_point_opt
        .map(|b| b.compress().to_bytes())
        .unwrap_or([0u8; 32]);

    // s_comm_2: prefix_hash || D || X || Y || sep || R || A || B (8 * 32 = 256 bytes)
    let mut buf = [0u8; 256];
    buf[0..32].copy_from_slice(prefix_hash);
    buf[32..64].copy_from_slice(&d_bytes);
    buf[64..96].copy_from_slice(&x_bytes);
    buf[96..128].copy_from_slice(&y_bytes);
    buf[128..160].copy_from_slice(&sep);
    buf[160..192].copy_from_slice(&r_pub_bytes);
    buf[192..224].copy_from_slice(&a_bytes);
    buf[224..256].copy_from_slice(&b_bytes);

    let c = curve25519_dalek::Scalar::from_bytes_mod_order(keccak256(buf));
    let s = *k - c * r;

    let mut sig = [0u8; 64];
    sig[0..32].copy_from_slice(&c.to_bytes());
    sig[32..64].copy_from_slice(&s.to_bytes());
    (d_bytes, sig)
}

/// Generate canonical Monero `OutProofV2` matching `wallet2::get_tx_proof`.
pub fn generate_tx_proof_v2(
    txid: [u8; 32],
    tx_key: &Scalar,
    additional_tx_keys: &[Zeroizing<Scalar>],
    recipient: MoneroAddress,
    message: &str,
    rng: &mut (impl RngCore + CryptoRng),
) -> String {
    let mut prefix_data = Vec::with_capacity(32 + message.len());
    prefix_data.extend_from_slice(&txid);
    prefix_data.extend_from_slice(message.as_bytes());
    let prefix_hash = keccak256(&prefix_data);

    let a_point: EdwardsPoint = recipient.view().into();
    let b_point_opt: Option<EdwardsPoint> =
        recipient.is_subaddress().then(|| recipient.spend().into());

    let num_sigs = 1 + additional_tx_keys.len();
    let mut out = String::with_capacity(OUTPROOF_V2_HEADER.len() + num_sigs * BASE58_ENTRY_LEN);
    out.push_str(OUTPROOF_V2_HEADER);

    let r0_d: curve25519_dalek::Scalar = (*tx_key).into();
    let r0 = Zeroizing::new(r0_d);
    let (d0, sig0) = generate_single_tx_proof_v2(&prefix_hash, *r0, a_point, b_point_opt, rng);
    out.push_str(&monero_base58::encode(&d0));
    out.push_str(&monero_base58::encode(&sig0));

    for add_k in additional_tx_keys {
        let r_i_d: curve25519_dalek::Scalar = (**add_k).into();
        let r_i = Zeroizing::new(r_i_d);
        let (di, sigi) = generate_single_tx_proof_v2(&prefix_hash, *r_i, a_point, b_point_opt, rng);
        out.push_str(&monero_base58::encode(&di));
        out.push_str(&monero_base58::encode(&sigi));
    }
    out
}

fn check_single_tx_proof_v2(
    prefix_hash: &[u8; 32],
    r_pub: EdwardsPoint,
    a_point: EdwardsPoint,
    b_point_opt: Option<EdwardsPoint>,
    d_point: EdwardsPoint,
    c: curve25519_dalek::Scalar,
    s: curve25519_dalek::Scalar,
) -> bool {
    let x_point = match b_point_opt {
        Some(b_pt) => c * r_pub + s * b_pt,
        None => c * r_pub + (&s * ED25519_BASEPOINT_TABLE),
    };
    let y_point = c * d_point + s * a_point;

    let sep = keccak256(TXPROOF_V2_DOMAIN);
    let mut buf = [0u8; 256];
    buf[0..32].copy_from_slice(prefix_hash);
    buf[32..64].copy_from_slice(&d_point.compress().to_bytes());
    buf[64..96].copy_from_slice(&x_point.compress().to_bytes());
    buf[96..128].copy_from_slice(&y_point.compress().to_bytes());
    buf[128..160].copy_from_slice(&sep);
    buf[160..192].copy_from_slice(&r_pub.compress().to_bytes());
    buf[192..224].copy_from_slice(&a_point.compress().to_bytes());
    buf[224..256].copy_from_slice(
        &b_point_opt
            .map(|b| b.compress().to_bytes())
            .unwrap_or([0u8; 32]),
    );

    let c2 = curve25519_dalek::Scalar::from_bytes_mod_order(keccak256(buf));
    c2 == c
}

/// Statelessly verify a canonical Monero `OutProofV2` against a `Transaction::V2`, returning the
/// verified atomic XMR amount received by `recipient` (matching `wallet2::check_tx_proof`).
pub fn verify_tx_proof_v2(
    tx: &Transaction,
    recipient: MoneroAddress,
    message: &str,
    proof: &str,
) -> Result<u64> {
    if !proof.starts_with(OUTPROOF_V2_HEADER) {
        bail!("missing OutProofV2 header");
    }
    let body = &proof[OUTPROOF_V2_HEADER.len()..];
    if body.is_empty() || !body.len().is_multiple_of(BASE58_ENTRY_LEN) {
        bail!("invalid OutProofV2 length");
    }
    let num_sigs = body.len() / BASE58_ENTRY_LEN;

    let extra = Extra::read(&mut tx.prefix().extra.as_slice())
        .map_err(|e| anyhow!("invalid tx.extra: {e}"))?;
    let (primary_pks, additional_pks_opt) = extra
        .keys()
        .ok_or_else(|| anyhow!("missing tx pubkey in tx.extra"))?;
    let additional_pks = additional_pks_opt.unwrap_or_default();
    if 1 + additional_pks.len() != num_sigs {
        bail!("signature count mismatch with additional tx pubkeys");
    }

    let mut shared_secrets = Vec::with_capacity(num_sigs);
    let mut sigs = Vec::with_capacity(num_sigs);
    for i in 0..num_sigs {
        let entry = &body[i * BASE58_ENTRY_LEN..(i + 1) * BASE58_ENTRY_LEN];
        let d_raw: [u8; 32] = monero_base58::decode(&entry[..BASE58_PK_LEN])
            .ok_or_else(|| anyhow!("base58 pk decode failed"))?
            .try_into()
            .map_err(|_| anyhow!("decoded pk not 32 bytes"))?;
        let sig_raw: [u8; 64] = monero_base58::decode(&entry[BASE58_PK_LEN..])
            .ok_or_else(|| anyhow!("base58 sig decode failed"))?
            .try_into()
            .map_err(|_| anyhow!("decoded sig not 64 bytes"))?;

        let d_point = CompressedEdwardsY(d_raw)
            .decompress()
            .ok_or_else(|| anyhow!("invalid shared secret point D"))?;
        let mut c_bytes = [0u8; 32];
        let mut s_bytes = [0u8; 32];
        c_bytes.copy_from_slice(&sig_raw[0..32]);
        s_bytes.copy_from_slice(&sig_raw[32..64]);
        let c = Option::<curve25519_dalek::Scalar>::from(
            curve25519_dalek::Scalar::from_canonical_bytes(c_bytes),
        )
        .ok_or_else(|| anyhow!("non-canonical sig.c"))?;
        let s = Option::<curve25519_dalek::Scalar>::from(
            curve25519_dalek::Scalar::from_canonical_bytes(s_bytes),
        )
        .ok_or_else(|| anyhow!("non-canonical sig.r"))?;

        shared_secrets.push(d_point);
        sigs.push((c, s));
    }

    let txid = tx.hash();
    let mut prefix_data = Vec::with_capacity(32 + message.len());
    prefix_data.extend_from_slice(&txid);
    prefix_data.extend_from_slice(message.as_bytes());
    let prefix_hash = keccak256(&prefix_data);

    let a_point: EdwardsPoint = recipient.view().into();
    let b_point: EdwardsPoint = recipient.spend().into();
    let b_point_opt = recipient.is_subaddress().then_some(b_point);

    let mut good_sig = vec![false; num_sigs];
    for pk in &primary_pks {
        let pk_ed: EdwardsPoint = (*pk).into();
        if pk_ed.compress().to_bytes() == [0u8; 32] {
            continue;
        }
        if check_single_tx_proof_v2(
            &prefix_hash,
            pk_ed,
            a_point,
            b_point_opt,
            shared_secrets[0],
            sigs[0].0,
            sigs[0].1,
        ) {
            good_sig[0] = true;
            break;
        }
    }
    for i in 0..additional_pks.len() {
        let pk_ed: EdwardsPoint = additional_pks[i].into();
        good_sig[i + 1] = check_single_tx_proof_v2(
            &prefix_hash,
            pk_ed,
            a_point,
            b_point_opt,
            shared_secrets[i + 1],
            sigs[i + 1].0,
            sigs[i + 1].1,
        );
    }

    if !good_sig.iter().any(|&ok| ok) {
        bail!("no valid Schnorr DLEQ signature in OutProofV2");
    }

    // Multiply verified D_i by cofactor 8 (`crypto::generate_key_derivation(D_i, 1)`)
    let main_deriv: Option<[u8; 32]> =
        good_sig[0].then(|| shared_secrets[0].mul_by_cofactor().compress().to_bytes());
    let add_derivs: Vec<Option<[u8; 32]>> = (1..num_sigs)
        .map(|i| good_sig[i].then(|| shared_secrets[i].mul_by_cofactor().compress().to_bytes()))
        .collect();

    let Transaction::V2 {
        prefix,
        proofs: Some(RctProofs { base, .. }),
    } = tx
    else {
        bail!("expected RingCT Transaction::V2");
    };

    let mut received: u64 = 0;
    for (n, out) in prefix.outputs.iter().enumerate() {
        let mut matched_shared_key: Option<Scalar> = None;

        let try_deriv = |deriv_bytes: &[u8; 32]| -> Option<Scalar> {
            let mut buf = Vec::with_capacity(40);
            buf.extend_from_slice(deriv_bytes);
            VarInt::write(&n, &mut buf).ok()?;
            if let Some(vt) = out.view_tag {
                let expected_vt = keccak256([b"view_tag".as_slice(), &buf].concat())[0];
                if vt != expected_vt {
                    return None;
                }
            }
            let sk = Scalar::hash(&buf);
            let sk_d: curve25519_dalek::Scalar = sk.into();
            let derived_out = (&sk_d * ED25519_BASEPOINT_TABLE) + b_point;
            (derived_out.compress().to_bytes() == out.key.to_bytes()).then_some(sk)
        };

        if let Some(ref d0) = main_deriv {
            matched_shared_key = try_deriv(d0);
        }
        if matched_shared_key.is_none() {
            if let Some(Some(ref dn)) = add_derivs.get(n) {
                matched_shared_key = try_deriv(dn);
            }
        }

        if let Some(sk) = matched_shared_key {
            let sk_bytes: [u8; 32] = <[u8; 32]>::from(sk);
            let (mask, amount) = match base
                .encrypted_amounts
                .get(n)
                .ok_or_else(|| anyhow!("missing ecdhInfo"))?
            {
                EncryptedAmount::Compact { amount: enc_amt } => {
                    let mask = Scalar::hash([b"commitment_mask".as_slice(), &sk_bytes].concat());
                    let amt_mask = keccak256([b"amount".as_slice(), &sk_bytes].concat());
                    let mut mask8 = [0u8; 8];
                    mask8.copy_from_slice(&amt_mask[..8]);
                    let amt = u64::from_le_bytes(*enc_amt) ^ u64::from_le_bytes(mask8);
                    (mask, amt)
                }
                EncryptedAmount::Original { mask, amount } => {
                    let mask_sec: curve25519_dalek::Scalar = Scalar::hash(sk_bytes).into();
                    let amt_sec: curve25519_dalek::Scalar =
                        Scalar::hash(mask_sec.to_bytes()).into();
                    let dec_mask = curve25519_dalek::Scalar::from_bytes_mod_order(*mask) - mask_sec;
                    let dec_amt = curve25519_dalek::Scalar::from_bytes_mod_order(*amount) - amt_sec;
                    let mut amt_le = [0u8; 8];
                    amt_le.copy_from_slice(&dec_amt.to_bytes()[..8]);
                    let amt = u64::from_le_bytes(amt_le);
                    (Scalar::from(dec_mask), amt)
                }
            };
            // Verify Pedersen commitment `C_n == mask * G + amount * H`
            let expected_c = Commitment::new(mask, amount).commit().compress();
            if expected_c
                != *base
                    .commitments
                    .get(n)
                    .ok_or_else(|| anyhow!("missing outPk"))?
            {
                bail!("Pedersen commitment mismatch on matched output");
            }
            received = received
                .checked_add(amount)
                .ok_or_else(|| anyhow!("amount overflow"))?;
        }
    }

    if received == 0 {
        bail!("zero funds received by address in tx");
    }
    Ok(received)
}

#[cfg(test)]
mod tests {
    use super::*;
    use monero_address::{Network, SubaddressIndex};
    use monero_wallet::{
        interface::FeeRate,
        ringct::{clsag::Decoys, RctType},
        send::{Change, SignableTransaction},
        OutputWithDecoys, ViewPair,
    };
    use rand_core::OsRng;

    fn make_wallet(rng: &mut OsRng) -> (Scalar, ViewPair) {
        let spend = Scalar::random(rng);
        let spend_d: curve25519_dalek::Scalar = spend.into();
        let spend_pub = Point::from(&spend_d * ED25519_BASEPOINT_TABLE);
        let view = Scalar::hash(spend_d.to_bytes());
        let vp = ViewPair::new(spend_pub, Zeroizing::new(view)).unwrap();
        (spend, vp)
    }

    fn make_synthetic_input(
        rng: &mut OsRng,
        sender_spend: Scalar,
        amount: u64,
    ) -> OutputWithDecoys {
        let key_offset = Scalar::random(rng);
        let s_d: curve25519_dalek::Scalar = sender_spend.into();
        let off_d: curve25519_dalek::Scalar = key_offset.into();
        let out_priv = s_d + off_d;
        let out_key = Point::from(&out_priv * ED25519_BASEPOINT_TABLE);
        let mask = Scalar::random(rng);
        let commitment = Commitment::new(mask, amount);

        // Ring-16 decoys with real spend at index 7
        let mut ring = Vec::with_capacity(16);
        let offsets = vec![100u64, 5, 3, 8, 2, 9, 4, 6, 11, 7, 1, 12, 3, 4, 9, 2];
        for idx in 0..16 {
            if idx == 7 {
                ring.push([out_key, commitment.commit()]);
            } else {
                let rk: curve25519_dalek::Scalar = Scalar::random(rng).into();
                let rc: curve25519_dalek::Scalar = Scalar::random(rng).into();
                ring.push([
                    Point::from(&rk * ED25519_BASEPOINT_TABLE),
                    Point::from(&rc * ED25519_BASEPOINT_TABLE),
                ]);
            }
        }
        let decoys = Decoys::new(offsets, 7, ring).unwrap();

        let mut raw = Vec::new();
        out_key.compress().write(&mut raw).unwrap();
        key_offset.write(&mut raw).unwrap();
        commitment.write(&mut raw).unwrap();
        decoys.write(&mut raw).unwrap();
        OutputWithDecoys::read(&mut raw.as_slice()).unwrap()
    }

    #[test]
    fn e2e_outproof_v2_standard_4_and_subaddress_8_and_multi_output_and_secret_blob() {
        let mut rng = OsRng;
        let (sender_spend, sender_vp) = make_wallet(&mut rng);
        let (_, merchant_vp) = make_wallet(&mut rng);
        let (_, third_vp) = make_wallet(&mut rng);

        let standard_4_addr = merchant_vp.legacy_address(Network::Mainnet);
        assert!(standard_4_addr.to_string().starts_with('4'));
        assert!(!standard_4_addr.is_subaddress());

        let subaddr_8 =
            merchant_vp.subaddress(Network::Mainnet, SubaddressIndex::new(0, 1).unwrap());
        assert!(subaddr_8.to_string().starts_with('8'));
        assert!(subaddr_8.is_subaddress());

        let other_4_addr = third_vp.legacy_address(Network::Mainnet);
        let fee_rate = FeeRate::new(20_000, 10_000).unwrap();

        // === CASE 1: Standard `4...` recipient (2 outputs: payment + Change::new) ===
        let input1 = make_synthetic_input(&mut rng, sender_spend, 1_000_000_000_000);
        let input1_bytes = vec![(
            input1.key().compress().to_bytes(),
            input1.commitment().commit().compress().to_bytes(),
        )];
        let mut ovk1 = Zeroizing::new([0u8; 32]);
        rng.fill_bytes(&mut *ovk1);

        let amount1 = 123_456_789_000u64;
        let signable1 = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk1.clone(),
            vec![input1],
            vec![(standard_4_addr, amount1)],
            Change::new(sender_vp.clone(), None),
            vec![],
            fee_rate,
        )
        .unwrap();
        let tx1 = signable1
            .sign(&mut rng, &Zeroizing::new(sender_spend))
            .unwrap();

        // Verify binary secret material round-trip + `reconstruct_tx_secret_keys_from_extra`
        let encoded_blob = encode_tx_secret_material(&ovk1, &input1_bytes);
        let (dec_ovk1, dec_pairs1) = decode_tx_secret_material(&encoded_blob).unwrap();
        let (r1, add1) =
            reconstruct_tx_secret_keys_from_extra(&dec_ovk1, dec_pairs1, &tx1.prefix().extra)
                .unwrap();
        assert!(add1.is_empty(), "standard 2-out tx has no additional keys");

        let msg1 = "xmr402-nonce-standard-4-addr-hmac";
        let proof1 = generate_tx_proof_v2(tx1.hash(), &r1, &add1, standard_4_addr, msg1, &mut rng);
        assert_eq!(proof1.len(), 10 + 132);
        let verified_amt1 = verify_tx_proof_v2(&tx1, standard_4_addr, msg1, &proof1).unwrap();
        assert_eq!(verified_amt1, amount1);

        // === CASE 2: Subaddress `8...` recipient (2 outputs: payment + Change::new) ===
        let input2 = make_synthetic_input(&mut rng, sender_spend, 1_000_000_000_000);
        let input2_pair = vec![(input2.key(), input2.commitment().commit())];
        let mut ovk2 = Zeroizing::new([0u8; 32]);
        rng.fill_bytes(&mut *ovk2);

        let amount2 = 98_765_432_100u64;
        let signable2 = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk2.clone(),
            vec![input2],
            vec![(subaddr_8, amount2)],
            Change::new(sender_vp.clone(), None),
            vec![],
            fee_rate,
        )
        .unwrap();
        let tx2 = signable2
            .sign(&mut rng, &Zeroizing::new(sender_spend))
            .unwrap();

        let (r2, add2) = reconstruct_tx_secret_keys(&ovk2, input2_pair, &tx2).unwrap();
        assert!(
            add2.is_empty(),
            "2-output subaddress + Change::new uses R = r * B_sub with empty additional_keys"
        );

        let msg2 = "xmr402-nonce-subaddress-8-addr-hmac";
        let proof2 = generate_tx_proof_v2(tx2.hash(), &r2, &add2, subaddr_8, msg2, &mut rng);
        assert_eq!(proof2.len(), 10 + 132);
        let verified_amt2 = verify_tx_proof_v2(&tx2, subaddr_8, msg2, &proof2).unwrap();
        assert_eq!(verified_amt2, amount2);

        // === CASE 3: Multi-output batch (`8...` subaddress + `4...` standard + Change::new = 3 outputs) ===
        let input3a = make_synthetic_input(&mut rng, sender_spend, 500_000_000_000);
        let input3b = make_synthetic_input(&mut rng, sender_spend, 600_000_000_000);
        // Pass unsorted inputs to verify key_image_sort invariance
        let input3_pairs = vec![
            (input3a.key(), input3a.commitment().commit()),
            (input3b.key(), input3b.commitment().commit()),
        ];
        let mut ovk3 = Zeroizing::new([0u8; 32]);
        rng.fill_bytes(&mut *ovk3);

        let amt3_sub = 42_000_000_000u64;
        let amt3_std = 77_000_000_000u64;
        let signable3 = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            ovk3.clone(),
            vec![input3b, input3a],
            vec![(subaddr_8, amt3_sub), (other_4_addr, amt3_std)],
            Change::new(sender_vp, None),
            vec![],
            fee_rate,
        )
        .unwrap();
        let tx3 = signable3
            .sign(&mut rng, &Zeroizing::new(sender_spend))
            .unwrap();

        let (r3, add3) = reconstruct_tx_secret_keys(&ovk3, input3_pairs, &tx3).unwrap();
        assert_eq!(
            add3.len(),
            3,
            "3-output subaddress batch emits 3 additional_keys"
        );

        let msg3 = "xmr402-nonce-batch-multi-out";
        let proof3_sub = generate_tx_proof_v2(tx3.hash(), &r3, &add3, subaddr_8, msg3, &mut rng);
        assert_eq!(proof3_sub.len(), 10 + 4 * 132);
        assert_eq!(
            verify_tx_proof_v2(&tx3, subaddr_8, msg3, &proof3_sub).unwrap(),
            amt3_sub
        );

        let proof3_std = generate_tx_proof_v2(tx3.hash(), &r3, &add3, other_4_addr, msg3, &mut rng);
        assert_eq!(
            verify_tx_proof_v2(&tx3, other_4_addr, msg3, &proof3_std).unwrap(),
            amt3_std
        );

        // === CASE 4: Negative tamper checks ===
        assert!(verify_tx_proof_v2(&tx2, subaddr_8, "wrong-nonce", &proof2).is_err());
        assert!(verify_tx_proof_v2(&tx2, standard_4_addr, msg2, &proof2).is_err());
        assert!(verify_tx_proof_v2(&tx1, subaddr_8, msg1, &proof1).is_err());
        assert!(verify_tx_proof_v2(&tx1, standard_4_addr, msg2, &proof2).is_err());
    }
}
