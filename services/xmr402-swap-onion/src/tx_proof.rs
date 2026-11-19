//! Canonical Monero `OutProofV2` (`wallet2::get_tx_proof` / `wallet2::check_tx_proof`)
//! prover and stateless Pedersen-commitment verifier for `XMR402` v2.0.

use curve25519_dalek::{
    constants::ED25519_BASEPOINT_TABLE,
    edwards::{CompressedEdwardsY, EdwardsPoint},
};
use monero_address::MoneroAddress;
use monero_wallet::{
    ed25519::{Commitment, Point, Scalar},
    extra::Extra,
    io::VarInt,
    primitives::keccak256,
    ringct::{EncryptedAmount, RctProofs},
    send::TransactionKeys,
    transaction::Transaction,
};
use rand_core::{CryptoRng, RngCore};
use zeroize::Zeroizing;

pub const TXPROOF_V2_DOMAIN: &[u8] = b"TXPROOF_V2";
pub const OUTPROOF_V2_HEADER: &str = "OutProofV2";
pub const BASE58_PK_LEN: usize = 44; // 32 bytes -> 4 * 11 Monero Base58 chars
pub const BASE58_SIG_LEN: usize = 88; // 64 bytes -> 8 * 11 Monero Base58 chars
pub const BASE58_ENTRY_LEN: usize = BASE58_PK_LEN + BASE58_SIG_LEN; // 132 chars

/// Reconstruct `(tx_key, additional_tx_keys)` for any `Transaction::V2` created by `monero-wallet = "0.2.0"`
/// from its `outgoing_view_key`, spent `inputs` (`(output.key(), output.commitment().commit())`), and `tx.extra`.
pub fn reconstruct_tx_secret_keys(
    outgoing_view_key: &Zeroizing<[u8; 32]>,
    input_keys_and_commitments: Vec<(Point, Point)>,
    tx: &Transaction,
) -> Result<(Zeroizing<Scalar>, Vec<Zeroizing<Scalar>>), &'static str> {
    let extra =
        Extra::read(&mut tx.prefix().extra.as_slice()).map_err(|_| "failed to parse tx.extra")?;
    let (_, additional_pks) = extra.keys().ok_or("missing tx pubkey in tx.extra")?;
    let additional_len = additional_pks.as_ref().map_or(0, Vec::len);

    let mut key_iter = TransactionKeys::new(outgoing_view_key, input_keys_and_commitments);
    let tx_key = key_iter.next().ok_or("TransactionKeys exhausted")?;
    let mut additional_keys = Vec::with_capacity(additional_len);
    for _ in 0..additional_len {
        additional_keys.push(key_iter.next().ok_or("TransactionKeys exhausted")?);
    }
    Ok((tx_key, additional_keys))
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

    let k: curve25519_dalek::Scalar = Scalar::random(rng).into();
    let x_bytes = match b_point_opt {
        Some(b_pt) => (k * b_pt).compress().to_bytes(),
        None => (&k * ED25519_BASEPOINT_TABLE).compress().to_bytes(),
    };
    let y_bytes = (k * a_point).compress().to_bytes();
    let sep = keccak256(TXPROOF_V2_DOMAIN);
    let a_bytes = a_point.compress().to_bytes();
    let b_bytes = b_point_opt
        .map(|b| b.compress().to_bytes())
        .unwrap_or([0u8; 32]);

    // s_comm_2: msg || D || X || Y || sep || R || A || B (8 * 32 = 256 bytes)
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
    let s = k - c * r;

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

    let (d0, sig0) =
        generate_single_tx_proof_v2(&prefix_hash, (*tx_key).into(), a_point, b_point_opt, rng);
    out.push_str(&monero_base58::encode(&d0));
    out.push_str(&monero_base58::encode(&sig0));

    for add_k in additional_tx_keys {
        let r_i: curve25519_dalek::Scalar = (**add_k).into();
        let (di, sigi) = generate_single_tx_proof_v2(&prefix_hash, r_i, a_point, b_point_opt, rng);
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
) -> Result<u64, &'static str> {
    if !proof.starts_with(OUTPROOF_V2_HEADER) {
        return Err("missing OutProofV2 header");
    }
    let body = &proof[OUTPROOF_V2_HEADER.len()..];
    if body.is_empty() || !body.len().is_multiple_of(BASE58_ENTRY_LEN) {
        return Err("invalid OutProofV2 length");
    }
    let num_sigs = body.len() / BASE58_ENTRY_LEN;
    if num_sigs > 32 {
        return Err("excessive OutProofV2 signature entries");
    }

    let extra = Extra::read(&mut tx.prefix().extra.as_slice()).map_err(|_| "invalid tx.extra")?;
    let (primary_pks, additional_pks_opt) = extra.keys().ok_or("missing tx pubkey")?;
    let additional_pks = additional_pks_opt.unwrap_or_default();
    if 1 + additional_pks.len() != num_sigs {
        return Err("signature count mismatch with additional tx pubkeys");
    }

    let mut shared_secrets = Vec::with_capacity(num_sigs);
    let mut sigs = Vec::with_capacity(num_sigs);
    for i in 0..num_sigs {
        let entry = &body[i * BASE58_ENTRY_LEN..(i + 1) * BASE58_ENTRY_LEN];
        let d_raw: [u8; 32] = monero_base58::decode(&entry[..BASE58_PK_LEN])
            .ok_or("base58 pk decode failed")?
            .try_into()
            .map_err(|_| "decoded pk not 32 bytes")?;
        let sig_raw: [u8; 64] = monero_base58::decode(&entry[BASE58_PK_LEN..])
            .ok_or("base58 sig decode failed")?
            .try_into()
            .map_err(|_| "decoded sig not 64 bytes")?;

        let d_point = CompressedEdwardsY(d_raw)
            .decompress()
            .ok_or("invalid shared secret point D")?;
        let c_bytes: [u8; 32] = sig_raw[0..32].try_into().map_err(|_| "bad c slice")?;
        let s_bytes: [u8; 32] = sig_raw[32..64].try_into().map_err(|_| "bad s slice")?;
        let c = Option::<curve25519_dalek::Scalar>::from(
            curve25519_dalek::Scalar::from_canonical_bytes(c_bytes),
        )
        .ok_or("non-canonical sig.c")?;
        let s = Option::<curve25519_dalek::Scalar>::from(
            curve25519_dalek::Scalar::from_canonical_bytes(s_bytes),
        )
        .ok_or("non-canonical sig.s")?;

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
        return Err("no valid Schnorr DLEQ signature in OutProofV2");
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
        return Err("expected RingCT Transaction::V2");
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
            let (mask, amount) = match base.encrypted_amounts.get(n).ok_or("missing ecdhInfo")? {
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
                    let dec_amt_bytes = dec_amt.to_bytes();
                    let mut amt8 = [0u8; 8];
                    amt8.copy_from_slice(&dec_amt_bytes[..8]);
                    (Scalar::from(dec_mask), u64::from_le_bytes(amt8))
                }
            };
            // Verify Pedersen commitment `C_n == mask * G + amount * H`
            let expected_c = Commitment::new(mask, amount).commit().compress();
            if expected_c != *base.commitments.get(n).ok_or("missing outPk")? {
                return Err("Pedersen commitment mismatch on matched output");
            }
            received = received.checked_add(amount).ok_or("amount overflow")?;
        }
    }

    if received == 0 {
        return Err("zero funds received by address in tx");
    }
    Ok(received)
}
