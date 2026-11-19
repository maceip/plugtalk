//! Monero 25-word English Electrum mnemonic encoding and decoding.
//!
//! Uses the canonical 1,626-word Monero English Electrum dictionary where every
//! word has a unique 3-character ASCII prefix, encoding a canonical 32-byte
//! little-endian Ed25519 spend scalar into 24 base-1626 words plus a 25th IEEE
//! CRC-32 checksum word over the 24 concatenated 3-character prefixes.

use std::{collections::HashMap, sync::OnceLock};

use anyhow::{anyhow, bail, Result};
use monero_wallet::ed25519::Scalar;
use rand_core::OsRng;
use zeroize::Zeroizing;

/// Number of words in the canonical Monero English Electrum dictionary.
pub const WORDLIST_LEN: usize = 1626;

/// Number of words in a complete Monero Electrum seed phrase.
pub const MNEMONIC_WORD_COUNT: usize = 25;

const RAW_ENGLISH_WORDLIST: &str = include_str!("wordlist_english.txt");

struct Dictionary {
    words: Vec<&'static str>,
    by_word: HashMap<&'static str, usize>,
}

fn dictionary() -> &'static Dictionary {
    static DICT: OnceLock<Dictionary> = OnceLock::new();
    DICT.get_or_init(|| {
        let words: Vec<&'static str> = RAW_ENGLISH_WORDLIST
            .lines()
            .map(str::trim)
            .filter(|w| !w.is_empty())
            .collect();
        assert_eq!(
            words.len(),
            WORDLIST_LEN,
            "Monero English Electrum dictionary must have exact 1,626 words"
        );
        let mut by_word = HashMap::with_capacity(WORDLIST_LEN);
        let mut prefixes = HashMap::with_capacity(WORDLIST_LEN);
        for (idx, &word) in words.iter().enumerate() {
            assert!(
                word.len() >= 3 && word.is_ascii(),
                "dictionary word {word:?} at {idx} must be >= 3 ASCII chars"
            );
            assert!(
                by_word.insert(word, idx).is_none(),
                "duplicate dictionary word {word:?}"
            );
            assert!(
                prefixes.insert(&word[..3], idx).is_none(),
                "duplicate 3-char prefix {:?} at {word:?}",
                &word[..3]
            );
        }
        Dictionary { words, by_word }
    })
}

/// Canonical 1,626-word Monero English Electrum word list.
pub fn english_words() -> &'static [&'static str] {
    &dictionary().words
}

/// Generate a fresh, non-zero canonical Ed25519 private spend scalar (`32 B` LE).
pub fn generate_spend_key() -> Zeroizing<[u8; 32]> {
    loop {
        let scalar = Scalar::random(&mut OsRng);
        let dalek: curve25519_dalek::Scalar = scalar.into();
        if dalek != curve25519_dalek::Scalar::ZERO {
            return Zeroizing::new(dalek.to_bytes());
        }
    }
}

/// Parse and validate a canonical non-zero Ed25519 scalar from 32 little-endian bytes.
pub fn validate_canonical_spend_scalar(spend_bytes: &[u8; 32]) -> Result<Scalar> {
    let ct_opt = curve25519_dalek::Scalar::from_canonical_bytes(*spend_bytes);
    let dalek = Option::<curve25519_dalek::Scalar>::from(ct_opt)
        .ok_or_else(|| anyhow!("spend key is not a canonical Ed25519 scalar"))?;
    if dalek == curve25519_dalek::Scalar::ZERO {
        bail!("spend key scalar must not be zero");
    }
    Ok(Scalar::from(dalek))
}

/// Encode a canonical 32-byte little-endian spend scalar into a 25-word Monero
/// English Electrum mnemonic.
pub fn encode_mnemonic(spend_bytes: &[u8; 32]) -> Result<Zeroizing<String>> {
    validate_canonical_spend_scalar(spend_bytes)?;
    let dict = dictionary();
    const N: u64 = WORDLIST_LEN as u64;

    let mut indices = Zeroizing::new([0usize; MNEMONIC_WORD_COUNT]);
    for chunk_i in 0..8 {
        let chunk: [u8; 4] = spend_bytes[chunk_i * 4..chunk_i * 4 + 4]
            .try_into()
            .expect("4-byte slice");
        let val = u64::from(u32::from_le_bytes(chunk));
        let w1 = val % N;
        let w2 = (val / N + w1) % N;
        let w3 = (val / N / N + w2) % N;
        indices[chunk_i * 3] = w1 as usize;
        indices[chunk_i * 3 + 1] = w2 as usize;
        indices[chunk_i * 3 + 2] = w3 as usize;
    }

    let mut trimmed = Zeroizing::new(String::with_capacity(24 * 3));
    for &idx in &indices[..24] {
        trimmed.push_str(&dict.words[idx][..3]);
    }
    let crc = crc32fast::hash(trimmed.as_bytes()) as usize;
    indices[24] = indices[crc % 24];

    let mut phrase = String::with_capacity(25 * 8);
    for (pos, &idx) in indices.iter().enumerate() {
        if pos > 0 {
            phrase.push(' ');
        }
        phrase.push_str(dict.words[idx]);
    }
    Ok(Zeroizing::new(phrase))
}

/// Decode and verify a 25-word Monero English Electrum mnemonic into its
/// canonical 32-byte little-endian spend scalar.
pub fn decode_mnemonic(phrase: &str) -> Result<Zeroizing<[u8; 32]>> {
    let dict = dictionary();
    let normalized = Zeroizing::new(phrase.trim().to_ascii_lowercase());
    let tokens: Vec<&str> = normalized.split_whitespace().collect();
    if tokens.len() != MNEMONIC_WORD_COUNT {
        bail!(
            "Monero mnemonic must contain exact {MNEMONIC_WORD_COUNT} words, got {}",
            tokens.len()
        );
    }

    let mut indices = Zeroizing::new([0usize; MNEMONIC_WORD_COUNT]);
    for (i, &word) in tokens.iter().enumerate() {
        let Some(&idx) = dict.by_word.get(word) else {
            bail!(
                "unknown Monero mnemonic word at position {}: {:?}",
                i + 1,
                word
            );
        };
        indices[i] = idx;
    }

    let mut trimmed = Zeroizing::new(String::with_capacity(24 * 3));
    for &idx in &indices[..24] {
        trimmed.push_str(&dict.words[idx][..3]);
    }
    let crc = crc32fast::hash(trimmed.as_bytes()) as usize;
    let expected_checksum_idx = indices[crc % 24];
    if indices[24] != expected_checksum_idx {
        bail!(
            "Monero mnemonic checksum mismatch: expected {:?}, got {:?}",
            dict.words[expected_checksum_idx],
            dict.words[indices[24]]
        );
    }

    const N: u64 = WORDLIST_LEN as u64;
    let mut bytes = Zeroizing::new([0u8; 32]);
    for chunk_i in 0..8 {
        let w1 = indices[chunk_i * 3] as u64;
        let w2 = indices[chunk_i * 3 + 1] as u64;
        let w3 = indices[chunk_i * 3 + 2] as u64;
        let val = w1 + N * (((N - w1) + w2) % N) + N * N * (((N - w2) + w3) % N);
        if val % N != w1 {
            bail!("invalid base-1626 word triad in chunk {}", chunk_i + 1);
        }
        let u = u32::try_from(val)
            .map_err(|_| anyhow!("u32 overflow in mnemonic triad {}", chunk_i + 1))?;
        bytes[chunk_i * 4..chunk_i * 4 + 4].copy_from_slice(&u.to_le_bytes());
    }

    validate_canonical_spend_scalar(&bytes)?;
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    const VECTORS: [(&str, &str, &str); 5] = [
        (
            "82a13b87b69555ba976601302e2498aed4875185c87b9133bf8d214f16e9eb0b",
            "5ea51b4da3e87ded053383ca38945d38c3bb35d6b84bf7a1c45b2a4f713f8705",
            "reruns today hookup itself thorn nirvana symptoms jukebox patio unquoted sushi long diode digit rewind hacksaw obvious soothe nightly return agile hobby algebra awesome nirvana",
        ),
        (
            "31e28ef4feca46915bdbf7b192af866e154cb7dbc704e9a39b6ce24ac89c1102",
            "68cef3455e6967a9751959914c3cbc5d990cafa07fb65be15c5478d17abe8a02",
            "cafe aided wounded lumber hounded water yoyo gasp aerial merger ungainly gaze ruby yacht tell playful smash issued sifting whole erase anxiety dash deity sifting",
        ),
        (
            "ea111187a598d5ab5fdabf8adb27df79005a106c7e3dc11797d77c4c48bace0b",
            "66dac59f937a9883ce6c12a515755bb744079d1a456a08964e92fc2a0748630a",
            "fight hoisting uptight nibs womanly pepper does plotting dolphin fugitive popular chlorine turnip organs ambush people hospital ledge puppy anybody gourmet cuddled because candy womanly",
        ),
        (
            "a6e0194a91f45a4f08633efc405e63d7c509d926759e7a9b7b945f235a8d300e",
            "09e13dda6b81a3d739f6714bed246071dc184dde0cb3edc71b5a984b1b67f003",
            "roped waist elapse cider reruns aggravate jetting bested azure omnibus hull economics depth reheat tobacco exit under locker money actress certain cupcake drinks examine reheat",
        ),
        (
            "09ec1221eee3d94452d688e8894c0917b73d14dbcda3ef673b038a0874e5ee02",
            "9a669bdaa1a4f2de752435db6eead238ff3c191797e0a86515b85e880c7bda01",
            "pigment mice pitched examine damp jobs going viewpoint terminal ultimate asylum cogs saved wayside stylishly asylum opposite after ghetto malady mural uphill maps metro pigment",
        ),
    ];

    #[test]
    fn dictionary_has_1626_words_and_unique_prefixes() {
        let words = english_words();
        assert_eq!(words.len(), 1626);
        assert_eq!(words[0], "abbey");
        assert_eq!(words[840], "magically");
        assert_eq!(words[1625], "zoom");
    }

    #[test]
    fn official_electrum_seed_vectors_roundtrip_and_match_view_keys() {
        for (spend_hex, view_hex, phrase) in VECTORS {
            let spend_vec = hex::decode(spend_hex).unwrap();
            let spend: [u8; 32] = spend_vec.try_into().unwrap();
            let encoded = encode_mnemonic(&spend).unwrap();
            assert_eq!(encoded.as_str(), phrase);

            let decoded = decode_mnemonic(phrase).unwrap();
            assert_eq!(*decoded, spend);

            let view_scalar = Scalar::hash(&*decoded);
            let view_dalek: curve25519_dalek::Scalar = view_scalar.into();
            assert_eq!(hex::encode(view_dalek.to_bytes()), view_hex);
        }
    }

    #[test]
    fn rejects_invalid_count_unknown_word_checksum_and_noncanonical_scalar() {
        assert!(decode_mnemonic("abbey abducts").is_err());

        let mut bad_word: Vec<&str> = VECTORS[0].2.split_whitespace().collect();
        bad_word[3] = "abracadabra";
        assert!(decode_mnemonic(&bad_word.join(" ")).is_err());

        let mut bad_crc: Vec<&str> = VECTORS[0].2.split_whitespace().collect();
        bad_crc[24] = "abbey";
        assert!(decode_mnemonic(&bad_crc.join(" ")).is_err());

        // Non-canonical scalar (`0xff..ff >= l`) must be rejected on encode & decode:
        let non_canonical = [0xffu8; 32];
        assert!(encode_mnemonic(&non_canonical).is_err());
        assert!(encode_mnemonic(&[0u8; 32]).is_err());
    }
}
