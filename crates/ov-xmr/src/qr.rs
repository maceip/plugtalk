//! Pure-Rust ISO/IEC 18004 QR code matrix encoder (`EcLevel::M`) for zero-JS
//! hardware wallet & swap deposit rendering.

use qrcode::{Color, EcLevel, QrCode};
use serde::{Deserialize, Serialize};

/// Maximum payload byte length accepted for QR matrix encoding.
pub const MAX_QR_PAYLOAD_BYTES: usize = 1024;

/// JSON contract returned by `nativeXmrQrMatrixJson`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QrMatrixResult {
    pub ok: bool,
    pub size: usize,
    pub rows: Vec<String>,
    pub error: String,
}

impl QrMatrixResult {
    pub fn err(msg: impl Into<String>) -> Self {
        Self {
            ok: false,
            size: 0,
            rows: Vec::new(),
            error: msg.into(),
        }
    }
}

/// Encode `payload` into a square ISO/IEC 18004 QR module matrix (`'1'` = dark, `'0'` = light).
pub fn encode_qr_matrix(payload: &str) -> QrMatrixResult {
    if payload.trim().is_empty() {
        return QrMatrixResult::err("QR payload must not be empty");
    }
    if payload.len() > MAX_QR_PAYLOAD_BYTES {
        return QrMatrixResult::err(format!(
            "QR payload exceeds {MAX_QR_PAYLOAD_BYTES} bytes (got {} bytes)",
            payload.len()
        ));
    }

    let code = match QrCode::with_error_correction_level(payload.as_bytes(), EcLevel::M) {
        Ok(c) => c,
        Err(err) => return QrMatrixResult::err(format!("QR encoding failed: {err}")),
    };

    let size = code.width();
    let colors = code.to_colors();
    let mut rows = Vec::with_capacity(size);
    for y in 0..size {
        let mut row = String::with_capacity(size);
        let row_start = y * size;
        for x in 0..size {
            let ch = match colors[row_start + x] {
                Color::Dark => '1',
                Color::Light => '0',
            };
            row.push(ch);
        }
        rows.push(row);
    }

    QrMatrixResult {
        ok: true,
        size,
        rows,
        error: String::new(),
    }
}

/// Encode `payload` into the exact JSON schema of `nativeXmrQrMatrixJson`.
pub fn encode_qr_matrix_json(payload: &str) -> String {
    let res = encode_qr_matrix(payload);
    serde_json::to_string(&res).unwrap_or_else(|_| {
        r#"{"ok":false,"size":0,"rows":[],"error":"serialization error"}"#.to_string()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const FINDER_7X7: [&str; 7] = [
        "1111111", "1000001", "1011101", "1011101", "1011101", "1000001", "1111111",
    ];

    #[test]
    fn encodes_real_iso18004_qr_matrix_with_finder_patterns_and_bounds_checks() {
        let uri = "monero:82ffYh7xRhFBvBHEHGV8TNRBoYucBFNoyYnwrJJjnMGpHKPWosZeB4BDr7pBG4DCux1WKepdAV6rRTimi1LqWMacF88hvQm?tx_amount=0.25";
        let res = encode_qr_matrix(uri);
        assert!(res.ok, "unexpected error: {}", res.error);
        assert!(res.size >= 21);
        assert_eq!(res.rows.len(), res.size);
        for row in &res.rows {
            assert_eq!(row.len(), res.size);
            assert!(row.bytes().all(|b| b == b'0' || b == b'1'));
        }

        // Verify canonical 7x7 ISO/IEC 18004 finder patterns in top-left, top-right, and bottom-left corners:
        for (dy, expected_row) in FINDER_7X7.iter().enumerate() {
            assert_eq!(&res.rows[dy][..7], *expected_row);
            assert_eq!(&res.rows[dy][res.size - 7..], *expected_row);
            assert_eq!(&res.rows[res.size - 7 + dy][..7], *expected_row);
        }

        // Verify JSON contract serialization:
        let json: serde_json::Value = serde_json::from_str(&encode_qr_matrix_json(uri)).unwrap();
        assert_eq!(json["ok"], true);
        assert_eq!(json["size"].as_u64().unwrap() as usize, res.size);
        assert_eq!(json["rows"].as_array().unwrap().len(), res.size);
        assert_eq!(json["error"], "");

        // Reject empty and > 1024 byte inputs cleanly:
        let empty = encode_qr_matrix("   ");
        assert!(!empty.ok);
        assert_eq!(empty.size, 0);
        assert!(empty.rows.is_empty());

        let oversize = encode_qr_matrix(&"x".repeat(1025));
        assert!(!oversize.ok);
        assert_eq!(oversize.size, 0);
        assert!(oversize.rows.is_empty());
    }
}
